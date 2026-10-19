# TASKS.md — CookieBox 任务看板

状态：`待办` · `进行中(<角色/代号>)` · `待审` · `完成` · `阻塞(<原因>)`
粒度：每个任务约半天（≤4 小时）。认领时把状态改为「进行中」并写上代号。
基线：main @ 09f6d04（v1.7.0，versionCode 27，含 c7bcce2 + 接力文档）；integrate/1.7 @ 7288fb1（比 main 多 1 个加固提交，少 1 个文档提交）。
认领顺序建议：T-001 → T-002 / T-006 / T-007 / T-015（可并行）→ T-005a → T-005b → T-009a…

## P0 — 数据安全与零泄露

| ID | 任务 | 范围（只能改这些） | 验收 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-001 | 把 integrate/1.7 上的加固提交 7288fb1 合入 main（两边已分叉，用 merge commit），并完成一次编译与覆盖安装验证 | 合并操作；不改代码 | `assembleDebug` 通过；main 同时包含 7288fb1 与 09f6d04；从 1.6.12 与 1.7.0(c7bcce2) 覆盖安装后储存库记录数不变 | 需主人批准（CI 手动运行会发预发布版） | 完成 |
| T-002 | AccountVault 密钥获取改为「别名存在就不重建」，与 SecureStore 同规则（可直接复用 SecureStore，别名 `cookiebox_account_key` 不变） | `engine/AccountVault.kt` | 别名存在但取不出时返回失败而不是生成新密钥；1.7.0 覆盖安装后已存账号可解密 | — | 待审(R-001/R-002 代码通过；需拆出独立分支后合入) |
| T-003 | 签名密钥泄露处置方案（决策文档，不改代码） | `docs/adr/0002-signing-key.md` | 列出选项与对已安装用户的影响：A 维持现状并记录风险；B 新密钥 + APK v3 签名轮换（Android 9+ 可平滑升级，7–8 需重装）；C 新密钥，全员重装（会丢本地数据，不推荐）。是否清理 git 历史（需 force push）由主人决定 | — | 阻塞(待主人决策) |
| T-004 | CI 拆分：`integrate/**`、`task/**` 推送只编译不发布；发布仅 main / tag；加入 gitleaks 密钥扫描步骤 | `.github/workflows/` | 推任务分支只出 Artifact 不出 Release；扫描能跑通（已知的 jks/口令先加入白名单并注明 T-003） | — | 待办 |
| T-005a | 存储层可测性：为 SecureStore 抽出接口（如 `CryptoBox`），CookieStorageRepository 改为构造注入，默认实现不变 | `engine/SecureStore.kt`、`engine/CookieStorageRepository.kt` | 公开 API 与落盘格式零变化；调用方无需改动 | T-001 | 待办 |
| T-005b | 迁移矩阵单元测试（Robolectric + 假 CryptoBox） | `app/src/test/**`、`app/build.gradle.kts`（仅 testImplementation） | 覆盖：仅明文 / 仅密文 / 两者并存 / 密文解不开 / 连续两次解不开 / 恢复合并 / 损坏 JSON / 加密不可用降级；全部断言「记录不少于迁移前」 | T-005a | 待办 |
| T-006 | 日志脱敏审计：全仓 `printStackTrace` / `Log` 检查，确保不输出 Cookie 值、密码；release 构建关闭调试日志 | 涉及文件逐一列在 HANDOFF | grep 结果附在 HANDOFF；无敏感值输出 | — | 待办 |
| T-007 | 剪贴板敏感标记：复制凭据时设置 `ClipDescription.EXTRA_IS_SENSITIVE`（Android 13+） | `MainActivity.kt`(copyText)、`ui/CookieBottomSheetDialog.kt`、`ui/StorageBottomSheetDialog.kt`、`ui/AccountsBottomSheetDialog.kt` 中的复制点 | 13+ 系统剪贴板预览不显示明文；低版本行为不变 | — | 待审(R-002 代码通过：import 已补，静态检查可编译；需拆独立分支后合入) |
| T-008 | 标签会话持久化评估：`browser_prefs` 里保存的 URL 可能带 token 参数，给出方案（加密或剥离敏感 query）并实现 | `engine/BrowserPrefs.kt` | 旧会话数据仍能恢复；新保存的数据不含明文 token 参数或已加密 | T-005a（如复用 CryptoBox） | 待办 |
| T-015 | 收紧下载 JS 桥：`CookieBoxDl` 从全局 `addJavascriptInterface` 改为 `addWebMessageListener`（仅主框架、仅当前页 origin），或仅在 blob 下载期间注册、完成即移除 | `MainActivity.kt`（BlobBridge / downloadBlob 相关段） | 普通网页 `window.CookieBoxDl` 不存在；blob / data 下载行为不变；一次性 token 校验保留 | — | 待办(R-002：b036eee 已回退为全局 addJavascriptInterface，与 main 现状一致，T-015 实际未实现，需重新认领) |
| T-016 | 统一敏感页注入策略：`isSensitiveLoginPage` 由写死 hark.com 改为可配置规则（域名列表 + 页面含密码框时只允许自动填充脚本），视口 / 缩放 / 翻译 / 下载注入统一走它 | `MainActivity.kt`（isSensitiveLoginPage 及各注入调用点）、可新增 `engine/ScriptPolicy.kt` | 规则可单测；hark.com 现有行为不变；登录页不再被视口 / 缩放脚本改写 | 建议在 T-009a 之后 | 待办 |

## P1 — 可维护性

| ID | 任务 | 范围 | 验收 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-009a | MainActivity 拆分①：下载相关（startDownload / blob / dataUrl / 权限）移到 `ui/browser/DownloadController.kt` | `MainActivity.kt`、新文件 | 行为不变；MainActivity 行数下降 | T-001 | 待办 |
| T-009b | MainActivity 拆分②：标签管理（create/switch/close/session）移到 `ui/browser/TabController.kt` | 同上 | 同上；会话恢复兼容旧数据 | T-009a | 待办 |
| T-009c | MainActivity 拆分③：账号自动填充流程移到 `ui/browser/AutofillController.kt` | 同上 | 同上 | T-009b | 待办 |
| T-010 | 文档收拢：根 ARCHITECTURE.md 内容并入 docs/ARCHITECTURE.md 后删除根文件；README 下载说明改为 Releases | `ARCHITECTURE.md`、`README.md`、`docs/` | 链接无死链 | — | 待办 |
| T-017 | 评估 `usesCleartextTraffic=true`：改用 network_security_config，默认禁止明文，仅用户手动输入 http:// 时提示后放行 | `AndroidManifest.xml`、新增 `res/xml/network_security_config.xml` | https 站点不受影响；http 站点有明确提示 | — | 待办 |
| T-018 | 电脑版视口去闪烁：`FORCE_DESKTOP_VIEWPORT_JS` 从 onPageFinished 改为 `WebViewCompat.addDocumentStartJavaScript`（支持时）+ `useWideViewPort`，不支持时保持现状 | `MainActivity.kt`（applyDesktopViewport 相关） | 响应式站点不再先显示手机版再跳变；登录页按 T-016 跳过 | T-016 | 待办 |
| T-019 | 主界面 UI 重构：浮动药丸地址栏（SSL 挂锁 / 域名排版 / 内嵌进度）、悬浮胶囊底栏（标签角标 / 提取胶囊）、Slate 深色模式 | `res/layout/activity_main.xml`、`res/values/{dimens,colors_ui}.xml`、`res/values-night/**`、`res/drawable/bg_lock_chip.xml`、`res/drawable/bg_tab_badge.xml`、`docs/design/` | 不改 Kotlin、id 全保留；`assembleDebug` 通过；浅色 / 深色真机各过一遍主界面与 5 个底部弹窗 | — | 待审 |

## P2 — 路线图功能（ROADMAP 阶段四）

| ID | 任务 | 范围 | 验收 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-011 | 设计：本地加密导出 / 导入备份格式（带版本号、口令派生密钥，不依赖 Keystore 以便换机） | `docs/adr/0003-backup-format.md` | 格式、KDF 参数、兼容策略写清 | T-005b | 待办 |
| T-012 | 实现导出备份（按 T-011） | 待 T-011 定 | — | T-011 | 待办 |
| T-013 | 实现导入备份：按 id 合并，绝不覆盖本地已有记录 | 待 T-011 定 | 导入不丢本地数据 | T-012 | 待办 |
| T-014 | 设计：Cookie 编辑与写回 WebView | `docs/adr/` | — | — | 待办 |
| T-024 | 标签自定义名称：标签管理里点铅笔或长按改名，区分多账号；会话里新增 `label` 键持久化（旧数据缺省为空），分身标签打开的新窗口沿用名字；也为后续 MCP `list_sessions` 提供可辨认的名字 | `model/TabItem.kt`、`engine/BrowserPrefs.kt`、`ui/TabsAdapter.kt`、`ui/TabsBottomSheetDialog.kt`、`MainActivity.kt`（会话保存/恢复、重命名弹窗）、`res/layout/item_tab_row.xml`、新增 `res/drawable/ic_edit.xml` | 改名后重启仍在；1.9.0 覆盖安装旧标签照常恢复 | — | 待审(Hark) |

## P2b — 本机 MCP 服务（ADR-0004，未获批前全部阻塞，不得提交功能代码）

分支：每个任务从 `main` 切 `task/T-xxx-短名`，PR 到 `main`，不直接推 main。versionCode：T-026 起 ≥ 31，每次发版高于所有分支当前最大值；签名不变。设计与安全底线以 docs/adr/0004-mcp-server.md 为准。

| ID | 任务 | 范围（只能改这些） | 验收 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-025 | ADR-0004：MCP 服务红线例外（只写文档） | `docs/adr/0004-mcp-server.md`、`TASKS.md`、`HANDOFF.md` | 写清冲突点、例外范围、安全底线、对已有数据零影响；主人批准后把状态改为「已采纳」，并按 ADR §7 修订 AGENTS.md §3/§4 与 docs/ARCHITECTURE.md | — | 待审(待主人批准 ADR-0004) |
| T-026 | 前台服务 + MCP 握手 + Token + Origin/Host 校验 + 设置开关 | 新增 `mcp/McpService.kt`、`mcp/McpHttpServer.kt`、`mcp/McpProtocol.kt`、`mcp/McpAuth.kt`、`mcp/McpPrefs.kt`、`mcp/McpTokenStore.kt`；`AndroidManifest.xml`（`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、service 声明）；`MainActivity.kt`（仅设置入口）；新增设置界面布局与字符串；`app/build.gradle.kts`（仅 versionCode/versionName）。不引入新依赖 | 默认关闭且端口不可连接；开启后通知「AI 访问已开启」，点按可关；`initialize` / `tools/list`（空列表）/ `ping` 正常；无/错 Token 401；有 Origin 且不在 Origin 白名单 / Host 不符 403，无 Origin 放行；协议版本支持 2025-03-26 / 2025-06-18 / 2025-11-25；局域网开关默认关、开启有风险提示；Token 用新别名 `cookiebox_mcp_key` 加密存 `mcp_prefs`；从 1.9.1 覆盖安装标签、储存库、账号库全部保留 | T-025 获批 | 阻塞(待主人批准 ADR-0004) |
| T-027 | 三个只读工具（list_sessions / get_cookies / http_request）+ 站点白名单 UI | 新增 `mcp/TabRegistry.kt`、`mcp/MainThreadRunner.kt`、`mcp/tools/ListSessions.kt`、`mcp/tools/GetCookies.kt`、`mcp/tools/HttpRequest.kt`、白名单界面与布局；`MainActivity.kt`（仅注册 TabRegistry 实现）；`mcp/McpPrefs.kt`（grants、max_body_bytes） | 只看得到白名单里的「身份 × 域名」；list_sessions 不含 Cookie；授权支持逐个/整身份/全部三种粒度；get_cookies 只返回 name/value；候选域名来自 visited_hosts + 手动列表；http_request 的 page 通道用随机 window 变量 + 轮询分块取结果（不用 addJavascriptInterface），GET 遇 CORS/CSP 回退 native、非 GET 不自动回退；native 通道不设全局 CookieHandler、关闭自动重定向手动逐跳、多条 Set-Cookie 逐条写回该 Profile；回环地址全集拦截并用已解析 IP 连接；正文按两通道各自上限截断；非 GET 在 T-028 前按「每次确认」处理不可用时拒绝；日志无 Cookie/Token/正文 | T-026 | 阻塞(待主人批准 ADR-0004) |
| T-028 | 写操作确认弹窗 + 调用记录页 | 新增 `mcp/WriteGuard.kt`、`mcp/CallLog.kt`、确认界面（Activity/对话框）与调用记录界面及布局；`mcp/tools/HttpRequest.kt`（接入确认）；`AndroidManifest.xml`（确认界面声明） | 确认三档（每次确认 / 信任站点免确认（默认）/ 全部免确认，后者开启时提示一次）；需确认时显示工具名/身份/站点/摘要，60 s 超时拒绝；「该站点以后不再询问」写入 grants，可撤销；调用记录只含时间/工具/站点/结果/耗时，可一键清空 | T-027 | 阻塞(待主人批准 ADR-0004) |
| T-029 | 页面操作工具（open_url / get_page_text / screenshot / click / fill / eval_js） | 新增 `mcp/tools/Page*.kt`、`mcp/PageWaiter.kt`；`mcp/TabRegistry.kt`（主线程句柄扩展）；`MainActivity.kt`（仅暴露加载完成回调与 pendingUrl 触发） | 等待加载完成可靠且有超时；pendingUrl 标签先触发加载；click/fill/eval_js 按 T-028 的确认档位处理；注入走统一判定入口但 MCP 默认放行；不新增 addJavascriptInterface | T-028、T-016 | 阻塞(待主人批准 ADR-0004) |

## 已完成

| ID | 任务 | 结果 |
|---|---|---|
| A-001 | 架构师：AGENTS.md / TASKS.md / HANDOFF.md / docs 骨架 | 完成（见 HANDOFF 2026-10-17） |
| A-002 | 架构师：补 JS 注入 / API 提交规则，新增 T-015～T-018，更新基线与 T-001 验收，标注架构文档与 main 代码的差异 | 完成（见 HANDOFF 2026-10-17 01:15） |
