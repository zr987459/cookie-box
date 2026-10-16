# TASKS.md — CookieBox 任务看板

状态：`待办` · `进行中(<角色/代号>)` · `待审` · `完成` · `阻塞(<原因>)`
粒度：每个任务约半天（≤4 小时）。认领时把状态改为「进行中」并写上代号。
基线：main @ 09f6d04（v1.7.0，versionCode 27，含 c7bcce2 + 接力文档）；integrate/1.7 @ 7288fb1（比 main 多 1 个加固提交，少 1 个文档提交）。
认领顺序建议：T-001 → T-002 / T-006 / T-007 / T-015（可并行）→ T-005a → T-005b → T-009a…

## P0 — 数据安全与零泄露

| ID | 任务 | 范围（只能改这些） | 验收 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-001 | 把 integrate/1.7 上的加固提交 7288fb1 合入 main（两边已分叉，用 merge commit），并完成一次编译与覆盖安装验证 | 合并操作；不改代码 | `assembleDebug` 通过；main 同时包含 7288fb1 与 09f6d04；从 1.6.12 与 1.7.0(c7bcce2) 覆盖安装后储存库记录数不变 | 需主人批准（CI 手动运行会发预发布版） | 完成 |
| T-002 | AccountVault 密钥获取改为「别名存在就不重建」，与 SecureStore 同规则（可直接复用 SecureStore，别名 `cookiebox_account_key` 不变） | `engine/AccountVault.kt` | 别名存在但取不出时返回失败而不是生成新密钥；1.7.0 覆盖安装后已存账号可解密 | — | 待办 |
| T-003 | 签名密钥泄露处置方案（决策文档，不改代码） | `docs/adr/0002-signing-key.md` | 列出选项与对已安装用户的影响：A 维持现状并记录风险；B 新密钥 + APK v3 签名轮换（Android 9+ 可平滑升级，7–8 需重装）；C 新密钥，全员重装（会丢本地数据，不推荐）。是否清理 git 历史（需 force push）由主人决定 | — | 阻塞(待主人决策) |
| T-004 | CI 拆分：`integrate/**`、`task/**` 推送只编译不发布；发布仅 main / tag；加入 gitleaks 密钥扫描步骤 | `.github/workflows/` | 推任务分支只出 Artifact 不出 Release；扫描能跑通（已知的 jks/口令先加入白名单并注明 T-003） | — | 待办 |
| T-005a | 存储层可测性：为 SecureStore 抽出接口（如 `CryptoBox`），CookieStorageRepository 改为构造注入，默认实现不变 | `engine/SecureStore.kt`、`engine/CookieStorageRepository.kt` | 公开 API 与落盘格式零变化；调用方无需改动 | T-001 | 待办 |
| T-005b | 迁移矩阵单元测试（Robolectric + 假 CryptoBox） | `app/src/test/**`、`app/build.gradle.kts`（仅 testImplementation） | 覆盖：仅明文 / 仅密文 / 两者并存 / 密文解不开 / 连续两次解不开 / 恢复合并 / 损坏 JSON / 加密不可用降级；全部断言「记录不少于迁移前」 | T-005a | 待办 |
| T-006 | 日志脱敏审计：全仓 `printStackTrace` / `Log` 检查，确保不输出 Cookie 值、密码；release 构建关闭调试日志 | 涉及文件逐一列在 HANDOFF | grep 结果附在 HANDOFF；无敏感值输出 | — | 待办 |
| T-007 | 剪贴板敏感标记：复制凭据时设置 `ClipDescription.EXTRA_IS_SENSITIVE`（Android 13+） | `MainActivity.kt`(copyText)、`ui/CookieBottomSheetDialog.kt`、`ui/StorageBottomSheetDialog.kt`、`ui/AccountsBottomSheetDialog.kt` 中的复制点 | 13+ 系统剪贴板预览不显示明文；低版本行为不变 | — | 待办 |
| T-008 | 标签会话持久化评估：`browser_prefs` 里保存的 URL 可能带 token 参数，给出方案（加密或剥离敏感 query）并实现 | `engine/BrowserPrefs.kt` | 旧会话数据仍能恢复；新保存的数据不含明文 token 参数或已加密 | T-005a（如复用 CryptoBox） | 待办 |
| T-015 | 收紧下载 JS 桥：`CookieBoxDl` 从全局 `addJavascriptInterface` 改为 `addWebMessageListener`（仅主框架、仅当前页 origin），或仅在 blob 下载期间注册、完成即移除 | `MainActivity.kt`（BlobBridge / downloadBlob 相关段） | 普通网页 `window.CookieBoxDl` 不存在；blob / data 下载行为不变；一次性 token 校验保留 | — | 待办 |
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

## P2 — 路线图功能（ROADMAP 阶段四）

| ID | 任务 | 范围 | 验收 | 依赖 | 状态 |
|---|---|---|---|---|---|
| T-011 | 设计：本地加密导出 / 导入备份格式（带版本号、口令派生密钥，不依赖 Keystore 以便换机） | `docs/adr/0003-backup-format.md` | 格式、KDF 参数、兼容策略写清 | T-005b | 待办 |
| T-012 | 实现导出备份（按 T-011） | 待 T-011 定 | — | T-011 | 待办 |
| T-013 | 实现导入备份：按 id 合并，绝不覆盖本地已有记录 | 待 T-011 定 | 导入不丢本地数据 | T-012 | 待办 |
| T-014 | 设计：Cookie 编辑与写回 WebView | `docs/adr/` | — | — | 待办 |

## 已完成

| ID | 任务 | 结果 |
|---|---|---|
| A-001 | 架构师：AGENTS.md / TASKS.md / HANDOFF.md / docs 骨架 | 完成（见 HANDOFF 2026-10-17） |
| A-002 | 架构师：补 JS 注入 / API 提交规则，新增 T-015～T-018，更新基线与 T-001 验收，标注架构文档与 main 代码的差异 | 完成（见 HANDOFF 2026-10-17 01:15） |
