# HANDOFF.md — 接力记录（新记录追加在最上面）

模板见 AGENTS.md 第 6 节。

## 2026-10-20 02:30 CST · 开发者（Hark） · T-026 + T-027
- 分支 / 提交：task/T-026-T-027-mcp（主人要求尽快出可测安装包，两个任务合在一个分支 / 一个 PR，提交内按模块区分）
- 完成：新增 `mcp/`：前台服务 McpService（specialUse，默认关闭，通知点按即关）、手写 HTTP/1.1 McpHttpServer（头 ≤16 KiB、体 ≤1 MiB、15 s 超时、4 线程、chunked 回 411）、McpProtocol（initialize / ping / tools/list / tools/call，协议 2025-03-26 / 2025-06-18 / 2025-11-25，Mcp-Session-Id，只回 JSON 不开 SSE）、McpAuth（Host 白名单、Origin 白名单、Bearer 常量时间比较、1 分钟 10 次失败冷却 60 s）、McpTokenStore（32 字节 Base64URL，新别名 `cookiebox_mcp_key` 加密存 `mcp_prefs.token_enc`）、McpPrefs、TabRegistry + MainThreadRunner、三个工具 list_sessions / get_cookies / http_request（page 通道：随机 window 变量 + 100 ms 轮询 + 256 KiB 分块取回；GET/HEAD 遇 CORS/CSP/超限回退 native；native：不设全局 CookieHandler、手动逐跳重定向 ≤10、每跳校验授权与回环、多条 Set-Cookie 逐条写回该 Profile 并 flush）、设置弹窗与站点授权弹窗（逐个 / 整身份 / 全部，手动添加域名）。MainActivity 只加：注册 TabRegistry、菜单入口、onPageFinished 记录候选域名、开关为开时启动服务。versionCode 31 / 1.10.0
- 未完成 / 风险：①非 GET/HEAD 一律拒绝，等 T-028；②native 通道「用已解析 IP 建连」未实现，目前是建连前解析校验回环（每跳都校验），存在理论上的 DNS 重绑定窗口；即使命中也只会打到本机 MCP 端口，而它要求 Host 为 127.0.0.1/localhost 且需 Token，故影响有限，T-027 审查时决定是否补；③session_id 为本进程标签 id，应用重启后失效；④Activity 不在时 list_sessions 退化为已保存会话（只读），page 通道不可用；⑤未编译、未真机验证，以 CI 结果为准
- 兼容性验证：未编译、未真机验证；只新增 `mcp_prefs` 文件与 `cookiebox_mcp_key` 别名，不读写任何已有键；开关默认关，关闭时服务不启动、端口不监听
- 泄露自查：无新增密钥；代码不打印 Cookie / Token / 请求头值 / 正文；Token 复制到剪贴板标记为敏感
- 下一棒建议：真机验证（开启 → curl initialize / tools/list → 授权一个站点 → get_cookies / http_request），再认领 T-028

## 2026-10-20 01:50 CST · 系统架构师（Hark） · T-025（主人批准）
- 分支 / 提交：task/T-025-mcp-adr（PR #4 追加提交）
- 完成：主人批准 ADR-0004：例外批准、写确认默认「信任的站点免确认」、前台服务 specialUse、以后任务分支从 main 切。ADR 状态改「已采纳」并记录裁决；按 ADR §7 修订 AGENTS.md §2（基线改 main）、§3（登记 mcp_prefs 键与 cookiebox_mcp_key，标注 T-026 起）、§4（MCP 例外条款）与 docs/ARCHITECTURE.md（mcp 模块、依赖方向、本机服务边界）；TASKS.md T-025 完成、T-026～T-029 改为待办
- 未完成 / 风险：PR #4 需主人合入 main 后，T-026 才能从 main 切分支
- 兼容性验证：不涉及存储（仅文档）
- 泄露自查：无新增密钥与敏感日志
- 下一棒建议：合入 PR #4 → 开发者从 main 切 task/T-026-mcp-service，versionCode 31

## 2026-10-20 01:45 CST · 系统架构师（Hark） · T-025（修订 2）
- 分支 / 提交：task/T-025-mcp-adr（PR #4 追加提交）
- 完成：按 PR #4 审查意见修正技术可行性，方向不变：page 通道改为「随机 window 变量 + evaluateJavascript 轮询 + 分块取回」（addWebMessageListener 为可选优化，不用 addJavascriptInterface），GET 遇 CORS/CSP 回退 native、非 GET 不自动回退，page 正文上限单独 256 KiB；授权候选域名改为 visited_hosts（导航积累）+ 手动列表；get_cookies 只返回 name/value；Origin 改为无 Origin 放行、有 Origin 走白名单；回环地址全集 + 用已解析 IP 连接；native 不设全局 CookieHandler、手动重定向、多条 Set-Cookie 逐条写回；协议版本补 2025-11-25
- 未完成 / 风险：仍待主人批准 ADR-0004；按 IP 连接同时保留 SNI/证书校验在 HttpURLConnection 上实现较繁琐，T-027 实现时需重点审查
- 兼容性验证：不涉及存储（仅文档）
- 泄露自查：无新增密钥与敏感日志
- 下一棒建议：主人批准后认领 T-026

## 2026-10-20 01:30 CST · 系统架构师（Hark） · T-025（修订 1）
- 分支 / 提交：task/T-025-mcp-adr（PR #4 追加提交）
- 完成：按主人指示放宽对 AI 的限制：写确认改为三档、默认「信任的站点免确认」；授权支持整身份 / 全部；get_cookies 随站点授权；http_request 新增 page 通道（标签内 fetch，与浏览器请求无差别）并让 native 通道沿用该标签 UA / Referer、Set-Cookie 写回，对站点表现与浏览器一致；允许 http、只拒回环 IP
- 未完成 / 风险：保留 Token、只绑 127.0.0.1、Origin/Host 校验、日志不含凭据（防同机其他应用和网页冒用端口，不限制 AI）。仍需主人批准 ADR-0004
- 兼容性验证：不涉及存储（仅文档）
- 泄露自查：无新增密钥与敏感日志
- 下一棒建议：主人批准后认领 T-026

## 2026-10-20 01:20 CST · 系统架构师（Hark） · T-025
- 分支 / 提交：task/T-025-mcp-adr @ （见 PR）
- 完成：新增 docs/adr/0004-mcp-server.md（本机 MCP 服务的红线例外提议：冲突点、例外范围、架构、工具、安全底线、对已有数据零影响、获批后的规范修订文本）；TASKS.md 新增 T-025～T-029，实现任务全部「阻塞(待主人批准 ADR-0004)」
- 未完成 / 风险：未提交任何功能代码。需主人裁决 ADR §10 的 4 项（是否批准；get_cookies 是否单独授权；FGS 类型；分支基线由 integrate 改为 main 并同步 AGENTS.md §2）。注意 TabItem.id 每次启动重新生成，白名单按 profileName × 域名记录而非标签 id
- 兼容性验证：不涉及存储（仅文档）
- 泄露自查：无新增密钥与敏感日志；文档示例未使用任何真实值
- 下一棒建议：主人批准 ADR-0004 后，开发者认领 T-026（前台服务 + 握手 + Token + 设置开关）

## 2026-10-20 01:00 CST · 开发者（Hark） · T-024
- 分支 / 提交：task/T-024-tab-custom-name（v1.9.1，versionCode 30）
- 完成：TabItem 新增 customName；标签行新增铅笔按钮 + 长按改名（最多 24 字，可「恢复默认」）；有自定义名时首行显示名字，第二行显示「网页标题 · 网址」；browser_prefs.session_tabs 每项新增 `label` 键；分身标签弹出的新窗口沿用名字
- 未完成 / 风险：通过 GitHub API 提交，本棒无本地编译环境，以 CI 结果为准
- 兼容性验证：未编译、未真机验证。存储只新增键：旧会话缺 `label` 时 optString 返回空串，不改名不删键；旧版本读新数据会忽略 `label`
- 泄露自查：无新增密钥与敏感日志；名称只存本机
- 下一棒建议：主人真机验证后合入 main；之后可做 MCP 设计（ADR）

## 2026-10-17 03:45 CST · 地面总执行官（本地智能体） · T-019 与安全加固合并完成
- 分支 / 提交：main (v1.8.0, versionCode 28)
- 完成：
  - 合入 Hark 的 T-019 UI 重构：主界面全面重构为「浮动药丸地址栏 + 悬浮胶囊底栏 + Slate 深色模式」；引入 `dimens.xml`、`values-night/` 深色规范，保留所有 ViewBinding ID，兼容性完好
  - 合入 T-002 安全加固：AccountVault Keystore 密钥获取增加 `containsAlias` 保护，防止别名存在时误重新生成覆盖旧数据
  - 合入 T-007 隐私保护：CookieSheet、StorageSheet、MainActivity 全量复制凭据补齐 Android 13+ `EXTRA_IS_SENSITIVE` 防窥
  - 修复编译 import：补齐 `CookieBottomSheetDialog` 与 `StorageBottomSheetDialog` 中的 `import android.os.Build`
  - 修复 JS 桥稳定性：保持 `BlobBridge` 稳定挂载，杜绝动态注销导致的 `ReferenceError`
- 兼容性验证：覆盖安装 1.6.x / 1.7.0 / 1.7.1 均 100% 完整保留已有 Cookie 与账号库
- 泄露自查：无新增密钥或敏感日志

## 2026-10-17 03:10 CST · 开发者（UI/UX，Hark） · T-019
- 分支 / 提交：task/T-019-ui-refresh
- 完成：主界面重构为「浮动药丸地址栏 + 悬浮胶囊底栏 + Slate 深色」；新增 dimens.xml、colors_ui.xml、values-night/{colors,colors_ui,themes}.xml、bg_lock_chip、bg_tab_badge；设计说明 docs/design/ui-refresh-t019.md
- 兼容性验证：不涉及存储（只改布局与资源，所有 ViewBinding id 保留，Kotlin 未改动）
- 泄露自查：无新增密钥与敏感日志

## 2026-10-17 01:30 CST · 地面工程执行官（本地智能体） · 接力执行
- 分支 / 提交：main（merge commit）
- 完成：
  - T-001：已将 integrate/1.7 上的关键加固提交 7288fb1 干净合入 main 分支，无任何冲突
  - 存储安全基线已完全合入主干：Cookie 储存库完成 Keystore AES-256-GCM 加密与无感迁移保护；关停云备份与换机迁移泄漏通道
- 兼容性验证：main 分支已集成 1.7.0 (27)，包含完整的无损向前兼容代码，手机直接覆盖安装保留已有数据
- 下一棒建议：派遣 Hark 账号（开发工程师角色）在 integrate/1.7 或 task 分支认领 T-002（AccountVault 别名保护）与 T-015（收紧下载 JS 桥）

## 2026-10-17 01:15 CST · 系统架构师 · A-002
- 分支 / 提交：main
- 完成：
  - 新增 AGENTS.md、TASKS.md、docs/ARCHITECTURE.md
- 风险说明：详见 TASKS.md
