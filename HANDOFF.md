# HANDOFF.md — 接力记录（新记录追加在最上面）

模板见 AGENTS.md 第 6 节。

## 2026-10-20 06:00 CST · 开发者（Hark） · T-028
- 分支 / 提交：task/T-028-write-confirm（从 main @ 782ca10 切出；v1.12.0，versionCode 33）
- 完成：
  - `mcp/WriteGuard.kt`：三档 confirm_mode（每次确认 / 信任的站点免确认（默认）/ 全部免确认）；「信任的站点免确认」下信任站点与「本次会话免确认」（按 Mcp-Session-Id × 身份 × 站点，内存保存，DELETE 会话或服务停止即清空；无会话头时按本次服务运行）不询问；应用在前台直接弹框，同时发高优先级通知（新渠道 mcp_confirm）点按进入；60 s 无响应拒绝；服务停止时待确认的一律拒绝
  - `mcp/McpConfirmActivity.kt`：透明 Activity + 对话框，显示工具 / 标签 / 站点 / 摘要（方法 + 主机 + 路径，查询串只显示「?…」，正文只显示字节数）；可勾「本次会话免确认」「信任该站点，以后不再询问」（写入 grants.trusted）；返回 / 关闭 = 拒绝；多请求排队
  - `mcp/CallLog.kt` + `mcp/McpCallLogActivity.kt`：call_log 环形 500 条，只记时间 / 工具 / 标签 / 域名 / 结果（ok / denied / error）/ 耗时；一键清空
  - `tools/HttpRequest.kt`：开放 POST / PUT / PATCH / DELETE / OPTIONS（body 或 body_base64）；写操作先经 WriteGuard；page 通道写操作失败不自动回退 native（注入失败 / 标签关闭等未发出请求的情况除外）；native 重定向 303 与 301/302 的 POST 改 GET 丢正文，307/308 保留方法与正文；页面脚本占位符改为单次替换
  - 站点授权页每个域名加「信任」勾选（可撤销）；设置页加写操作确认三档（全部免确认首次开启提示一次）与调用记录入口；McpProtocol 记录每次 tools/call，DELETE 会话清除会话免确认
  - McpPrefs 新增 confirm_mode、call_log、all_skip_warned
- 未完成 / 风险：后台弹确认依赖通知（Android 13+ 未授予通知权限时只能在应用前台确认，否则 60 s 后拒绝）；确认等待期间占用一个 HTTP 工作线程（并发上限 4）
- 兼容性验证：未编译、未真机验证（以 CI 为准）。只在 mcp_prefs 新增键；不读写其他已有文件、键与 Keystore 别名
- 泄露自查：确认框 / 通知 / 调用记录不含 Cookie、请求头值、查询值、正文；日志只含异常类型名
- 下一棒建议：真机验收（三档切换；本次会话免确认在客户端重连后失效；信任后不再询问且可在授权页取消；后台时通知可点开确认；60 s 超时拒绝；调用记录清空）→ 合入后认领 T-029

## 2026-10-20 05:40 CST · 开发者（Hark） · T-027
- 分支 / 提交：task/T-027-mcp-readonly-tools（从 main @ 5b0e383 切出；v1.11.0，versionCode 32）
- 完成：
  - `mcp/TabRegistry.kt`（McpTabs：只读快照 SessionInfo、主线程句柄 TabHandle / TabAccess、evalJs；Activity 不在时退化为 browser_prefs 只读会话，id 为 saved-N）、`mcp/MainThreadRunner.kt`（主线程同步执行 / 异步回调等待，带超时）
  - `mcp/McpGrants.kt`：grants JSON（all / profiles.{all, manual, domains.{trust}}）、visited_hosts（按身份，注册域名，最多 200，服务关闭时不记录，服务启动时补入当前标签）、子域匹配
  - `mcp/tools/ListSessions.kt`：只列有授权的身份；标签当前站点未授权时不返回标题与网址；不含 Cookie
  - `mcp/tools/GetCookies.kt`：url 或 domain，只返回 {name, value}
  - `mcp/tools/HttpRequest.kt`：page 通道（随机 window 变量 + 100 ms 轮询 + 256 KiB 分块取回，无 addJavascriptInterface，securitypolicyviolation 区分 CSP），auto 时 GET/HEAD 失败或超过 page_max_body_bytes 回退 native；native 通道（不设全局 CookieHandler 且发现非空即报错、手动 Cookie、标签 UA / Accept-Language / strict-origin-when-cross-origin Referer、手动重定向最多 10 跳且每跳校验授权、Set-Cookie 逐条写回该身份并 flush、回环全集拦截：http 按已解析 IP 连接 + Host 头，https 保留域名做 SNI / 证书校验并在 TLS 前核对实际连接 IP；走系统代理时只做解析校验）；同一身份 × 域名串行；正文按两通道上限截断
  - `mcp/McpGrantsActivity.kt`：授权全部标签 / 授权该身份全部站点 / 逐个勾选，手动添加域名（长按移除），清除全部授权
  - McpPrefs 新增 grants、visited_hosts、max_body_bytes（默认 1 MiB，上限 8 MiB）、page_max_body_bytes（默认 256 KiB，上限 2 MiB）；设置页加「站点授权」入口与摘要；MainActivity 只加 3 行（attach / detach / onPageFinished 记录访问）
- 未完成 / 风险：非 GET/HEAD 按 TASKS 约定在 T-028 前一律拒绝；截断上限暂无设置界面（键已就绪）；trust 标记已落盘但 UI 留给 T-028；http 直连按 IP + Host 头依赖 Android HttpURLConnection 允许覆盖 Host（真机需验证虚拟主机站点）
- 兼容性验证：未编译、未真机验证（以 CI 为准）。只在 mcp_prefs 新增键；对 browser_prefs 只读；不读储存库 / 账号库；关闭开关时 visited_hosts 不记录，其余新代码不运行
- 泄露自查：日志只含异常类型名；错误消息不含 Cookie / 正文 / 请求头值；仓库内无真实凭据
- 下一棒建议：真机验收（未授权时 list_sessions 为空；授权后 get_cookies 含 HttpOnly；http_request 同源走 page、跨域 GET 回退 native 并能续期 Set-Cookie；redirect 到未授权域停止；访问 http://localhost 类域名被拒）→ 合入后认领 T-028

## 2026-10-20 02:20 CST · 开发者（Hark） · T-026
- 分支 / 提交：task/T-026-mcp-service（从 main @ 78ea455 切出；v1.10.0，versionCode 31）
- 完成：新增 `mcp/` 包：McpService（specialUse 前台服务，常驻通知「AI 访问已开启」，点按即关闭）、McpHttpServer（手写 ServerSocket，头 16 KiB / 体 1 MiB / 读超时 15 s / 总时限 20 s / 并发 4，按字节读 Content-Length，支持 Expect: 100-continue，chunked 返回 411）、McpGateway（路径 → Host → Origin 白名单 → CORS 预检 → 失败冷却 → Bearer Token）、McpAuth、McpProtocol（initialize / ping / tools/list / tools/call / 通知 202 / GET 405 / DELETE 结束会话，协议版本 2025-11-25 / 2025-06-18 / 2025-03-26，Mcp-Session-Id）、McpPrefs（mcp_prefs）、McpTokenStore（Keystore 新别名 cookiebox_mcp_key）、McpToolRegistry（T-026 为空）、McpSettingsActivity（总开关、地址、Token 显示 / 复制 / 重置、复制客户端配置、端口、局域网开关含风险提示、Origin 白名单）；MainActivity 只加菜单项「AI 访问（MCP）」；Manifest 加 FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE、service 与设置页声明
- 未完成 / 风险：工具列表为空（T-027 起）；未做自动化测试（T-005b 才引入测试依赖）；Android 12+ 进程被杀后系统重建服务时若不允许进前台，会自动关闭开关，需用户回设置页重新开启
- 兼容性验证：未编译、未真机验证（通过 GitHub API 提交，以 CI 结果为准）。存储只新增 mcp_prefs 与 Keystore 别名 cookiebox_mcp_key，不读写 cookie_vault_storage / account_vault / browser_prefs / cookiebox_download，不碰已有别名；开关默认关闭，关闭时服务不启动、端口不监听。从 1.6.x / 1.7.0 / 1.9.1 覆盖安装：未真机验证（代码路径上不触及旧数据）
- 泄露自查：无新增密钥；日志只含端口、状态码、异常类型名；Token 只在用户点「显示」时上屏，复制时剪贴板标记敏感；仓库内无真实 Token
- 下一棒建议：真机验收（开关关闭端口不可连；开启后 curl 带 / 不带 Token、带 Origin 的返回码；MCP 客户端 initialize 与 tools/list）→ 合入后认领 T-027

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
