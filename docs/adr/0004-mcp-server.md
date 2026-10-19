# ADR-0004：本机 MCP 服务（让 AI 客户端使用已登录的标签会话）

- 状态：**提议中（待仓库主人批准）**
- 日期：2026-10-20
- 关联任务：T-025（本文）、T-026、T-027、T-028、T-029
- 基线：main @ fd967e7（v1.9.1，versionCode 30）

> 本 ADR 获批前，任何 MCP 功能代码都不得提交；T-026～T-029 保持「阻塞(待主人批准 ADR-0004)」。

## 1. 背景

希望 AI 客户端（Claude Desktop、Cursor、手机上的 MCP 客户端等）通过 MCP 协议使用曲奇盒里**已登录的网站会话**：先读数据、代调站点接口，后期再操作页面。可以理解为「手机上的 Playwright MCP」，区别是用的是用户真实的登录态。T-024 已让标签支持自定义名称（`TabItem.customName`），AI 靠它辨认是哪个账号。

## 2. 与 AGENTS.md 红线的冲突点

| 红线条文（AGENTS.md §4） | MCP 为什么冲突 |
|---|---|
| 「除 WebView 访问用户输入的网址外不发任何请求」 | `http_request` 由应用自己（非 WebView）带 Cookie 发 HTTP 请求，目标网址来自 AI 而非用户输入。 |
| 「不得把页面数据（Cookie、表单值、Storage）回传给除本机 UI 以外的任何地方」 | `get_cookies`、`http_request` 的响应正文、`get_page_text`、`screenshot`、`eval_js` 的返回值都会交给本机 UI 以外的进程（AI 客户端），而且 AI 客户端可能把内容再发往云端模型。 |
| 「最多打印键名和长度」 | 不冲突，MCP 代码同样遵守。 |
| 「新桥一律用 addWebMessageListener…」「注入必须经过敏感页判定」 | 不冲突，但 T-029 的 `click / fill / eval_js` 属于新的注入路径，必须走统一判定（T-016）。 |
| §3 数据兼容红线 | 不冲突：只新增文件与别名，见 §6。 |

此外，应用会第一次**监听本机端口**（以前只有出站的 WebView 流量）。这不在红线条文里，但属于同一类风险，一并纳入例外范围。

## 3. 决策

批准一项**范围严格受限、默认关闭**的红线例外：

1. 新增前台服务 `McpService`，仅在用户手动开启时在 `127.0.0.1` 上监听一个端口，实现 MCP Streamable HTTP 传输。
2. 例外**只覆盖**以下数据流，其他一律仍按红线执行：
   - 经用户逐项授权的「身份（Profile）× 域名」的 Cookie、页面文本、截图、页面脚本结果，可经 MCP 交给**持有当前 Token 的本机（或用户另行开启的局域网）客户端**。
   - 应用可为已授权的「身份 × 域名」代发 HTTP(S) 请求，目标主机必须属于该授权域名。
3. 以下内容**不在例外内**，永远禁止：公网暴露、内置隧道 / 内网穿透 / 中继、任何上报或统计、把数据主动推送到任何远端、读取储存库（`cookie_vault_storage`）和账号库（`account_vault`）里的数据、读取账号库密码。

## 4. 架构

```
app/src/main/java/com/cookiebox/app/mcp/
├── McpService.kt          前台服务：生命周期、常驻通知「AI 访问已开启」（点按一键关闭）
├── McpHttpServer.kt       手写 ServerSocket HTTP/1.1，最小实现，无新依赖
├── McpProtocol.kt         JSON-RPC 2.0：initialize / notifications/initialized / tools/list / tools/call / ping
├── McpAuth.kt             Bearer Token 校验（常量时间比较）、Origin / Host 校验、失败限流
├── McpPrefs.kt            mcp_prefs 读写（开关、端口、局域网开关、白名单、截断上限、调用记录）
├── McpTokenStore.kt       Token 生成与 Keystore 加密（新别名 cookiebox_mcp_key）
├── TabRegistry.kt         窄接口：只读标签快照 + 主线程执行器
├── MainThreadRunner.kt    把工具调用切到主线程，带超时
├── WriteGuard.kt          写操作确认（T-028）
├── CallLog.kt             调用记录（T-028）
└── tools/                 ListSessions / GetCookies / HttpRequest（T-027），页面工具（T-029）
```

依赖方向：`MainActivity` → `mcp`（只为注册 `TabRegistry` 实现和打开设置页）；`mcp` → `engine` / `model`；`mcp` 不引用 `ui`，`engine` 不引用 `mcp`。MainActivity 只增加注册与入口，**不把 MCP 逻辑写进 MainActivity**。

### 4.1 传输（Streamable HTTP）
- 单一端点 `POST /mcp`，请求体为 JSON-RPC 2.0；响应 `Content-Type: application/json`（不实现 SSE 流，规范允许）。
- `GET /mcp` 返回 405；通知（无 `id`）返回 202 空正文；`DELETE /mcp` 结束会话。
- `initialize` 时生成 `Mcp-Session-Id`（随机，内存保存，服务停止即失效）；协议版本按客户端请求协商，支持列表实现时以 modelcontextprotocol.io 当时的正式版为准（至少 2025-03-26、2025-06-18）。
- `serverInfo.name = "cookiebox"`，`capabilities = { tools: {} }`。
- HTTP 实现要点（防止常见坑）：按**字节**读取 `Content-Length`（中文正文不能按字符读）；请求体上限 1 MiB、请求头上限 16 KiB、读超时 15 s、并发连接上限 4；不支持 `Transfer-Encoding: chunked` 请求时返回 411/400 而不是挂起。

### 4.2 TabRegistry（窄接口）
```kotlin
interface TabRegistry {
    fun snapshot(): List<SessionInfo>          // 只读快照，不含 WebView / CookieManager 引用
    fun <T> onMain(timeoutMs: Long, block: (Handle) -> T): T  // 主线程执行，超时抛 McpTimeout
}
data class SessionInfo(val id: String, val customName: String, val title: String,
                       val host: String, val isClone: Boolean, val profileName: String)
```
- `MainActivity` 存活时由它注册实现；Activity 被销毁而服务仍在时，快照退化为 `BrowserPrefs.loadSession()` 的**只读**结果（MCP 代码绝不写 `browser_prefs`）。
- `session_id` 是本进程内的标签 id，重启后失效；失效时返回明确错误，提示客户端重新 `list_sessions`。
- **授权与 Cookie 的真正归属是 Profile**：所有非分身标签共用 `Profile_Default`，同一分身的弹窗共用该分身 Profile。白名单按「profileName × 域名」记录，`list_sessions` 只是把它展开到标签。
- `ProfileStore`、`CookieManager` 与所有 WebView 操作一律在主线程执行；默认超时 10 s（页面操作 30 s），超时返回错误，不阻塞 HTTP 线程池以外的东西。

### 4.3 工具

第一批（T-027，只读为主）：

| 工具 | 参数 | 返回 | 读/写 |
|---|---|---|---|
| `list_sessions` | 无 | 每个已授权标签：`id`、`customName`、`title`、`host`、`isClone`、`profileName`、该身份已授权域名列表。**不返回 Cookie。** | 读 |
| `get_cookies` | `session_id`, `domain` | 该身份下该域名的 Cookie（名、值；能取到的属性）。`domain` 必须属于已授权域名。 | 读（敏感，见 §5.5） |
| `http_request` | `session_id`, `url`, `method`, `headers?`, `body?` | 状态码、响应头、正文（按 `max_body_bytes` 截断并标注 `truncated`），文本按 charset 解码，二进制返回 Base64 | GET/HEAD = 读；其他 = 写 |

`http_request` 细则：
- 不需要 WebView 打开；Cookie 取自该 Profile 的 `CookieManager.getCookie(url)`（主线程取，后台线程发请求）。
- 只允许 `https://`；`http://` 仅当该域名被用户单独勾选「允许明文」时放行。
- 目标主机必须等于或是已授权域名的子域；**不自动跟随重定向**，改为逐跳手动跟随（最多 5 跳），每一跳重新校验域名并重新取 Cookie，跨出授权域名即停止并返回 3xx 原样结果。
- 解析后的 IP 若为回环、私网、链路本地、组播地址则拒绝（防止借域名打到本机 MCP 端口或局域网设备）。
- 调用方传入的 `Cookie`、`Host` 头被忽略；响应的 `Set-Cookie` **不写回** WebView，不改变登录状态。
- 默认截断上限 256 KiB，可在设置里调整（上限 4 MiB）。

第二批（T-029，页面操作，写操作需确认）：`open_url`、`get_page_text`、`screenshot`（读）；`click(selector)`、`fill(selector, text)`、`eval_js(code)`（写）。
- 「等待加载完成」：以 `onPageFinished` + `document.readyState === 'complete'` + 进度 100 为准，带超时；标签处于 `pendingUrl` 状态时先触发加载再等待。
- 所有注入走 T-016 的统一敏感页判定；含密码框的页面 `eval_js` 一律拒绝，`fill` 拒绝写入 `type=password`。
- 不新增 `addJavascriptInterface`；结果经 `evaluateJavascript` 回调取得。
- 页面内容可能包含针对 AI 的「提示注入」。写操作强制手机确认（§5.4）是主要缓解手段，工具返回的页面文本会附带来源说明。

## 5. 安全底线（全部必须实现，任一缺失不得发版）

### 5.1 开关与生命周期
- 默认关闭；只能在设置里手动开启。关闭时 `ServerSocket` 已关闭，端口不可连接，行为与 1.9.1 完全一致。
- 前台服务常驻通知「AI 访问已开启」，点按（或通知上的「关闭」按钮）一键停止服务并关闭开关。
- 服务被系统杀掉后，只有开关仍为开时才允许恢复；用户在系统里强行停止应用后不自动重启。

### 5.2 网络暴露
- 只绑定 `127.0.0.1`。
- 局域网访问是**独立开关**，默认关闭；开启前弹出明确风险提示（同一 Wi-Fi 下的设备可尝试连接；HTTP 明文传输，Token 可能被同网段嗅探；只在可信网络使用）。开启后通知文案变为「AI 访问已开启（局域网）」。
- 不做任何公网暴露、UPnP、内置隧道或中继。

### 5.3 认证与来源校验
- 每次开启生成 32 字节随机 Token（`SecureRandom`，Base64URL），可在设置里「重置」，重置后旧 Token 立即失效。
- 无 Token 或错误 Token 一律 401；比较用常量时间；同一来源 1 分钟内失败 10 次进入 60 s 冷却。
- 带 `Origin` 头的请求一律 403（浏览器网页发出的请求必带 Origin，原生 MCP 客户端不带），防 DNS rebinding 与网页跨站调用。
- `Host` 头必须是 `127.0.0.1:<端口>` / `localhost:<端口>`（局域网开启时外加本机局域网 IP），否则 403。
- Token 在设置页可复制，剪贴板标记为敏感（同 T-007）。

### 5.4 写操作确认
- 写操作 = 非 GET/HEAD 的 `http_request`、`click`、`fill`、`eval_js`。
- 每次写操作在手机上确认，显示：工具名、标签自定义名 / 身份、目标站点、摘要（方法 + 路径、选择器、脚本前 200 字；不显示 Cookie 和请求头值）。
- Activity 在前台时用对话框；在后台时发高优先级通知，点按进入确认界面。设备锁定时必须先解锁才能确认（锁屏上不提供「允许」按钮）。60 s 无响应视为拒绝。
- 可选「本次会话内该站点不再询问」：作用域 = `Mcp-Session-Id` × profileName × 域名 × 工具类别；只存内存，服务停止或 Token 重置即清空。

### 5.5 站点白名单
- 用户逐个勾选允许 AI 访问的「身份 × 域名」（从现有标签里选，也可手填域名）。不在名单里的标签不出现在 `list_sessions`，按 id 调用也返回「未授权」。
- 域名不得是 IP 字面量、单段名或明显的公共后缀（如 `com`、`com.cn`、`github.io`，内置一张小表，不引入 PSL 依赖）。
- **建议（待主人定）**：`get_cookies` 额外需要每个域名单独勾选「允许读取 Cookie 值」，默认不勾。理由：`http_request` 让 AI 用登录态办事而不拿到凭据本身；而 Cookie 值一旦交出，就可能被 AI 客户端带离手机长期使用，风险高一个等级。

### 5.6 存储
- 新建 SharedPreferences 文件 `mcp_prefs`，**不读写任何已有文件和键**。键：`enabled`、`port`、`lan_enabled`、`token_enc`、`grants`（JSON）、`max_body_bytes`、`call_log`（JSON 环形，最多 500 条）。
- Token 用新 Keystore 别名 `cookiebox_mcp_key` 加密后存 `token_enc`。复用 `SecureStore` 的「别名存在就不重建」规则；别名取不出时让用户重新开启（重新生成 Token），不碰其他两个别名。
- `mcp_prefs` 同样受 `allowBackup=false` 与 data_extraction_rules 约束：现有规则对 sharedpref / database / root / file 均为 `path="."` 全排除，新文件自动覆盖，无需改动。

### 5.7 零泄露
- 日志、Toast、异常、通知里不得出现 Cookie 值、Token、请求头值、请求 / 响应正文；最多打印工具名、键名和长度。
- 调用记录页只记：时间、工具名、标签名 / 身份、目标域名、结果（成功 / 拒绝 / 错误码）、耗时；不记参数和返回内容。提供一键清空。

## 6. 对已有数据的影响

**零。**
- 不改名、不删除、不改变含义任何已有 SharedPreferences 文件和键（`cookie_vault_storage`、`account_vault`、`browser_prefs`、`cookiebox_download`）。
- 不触碰已有 Keystore 别名 `cookiebox_cookie_vault_key`、`cookiebox_account_key`；只新增 `cookiebox_mcp_key`。
- MCP 代码对 `browser_prefs` 只读，不读储存库与账号库。
- 关闭开关时没有任何新代码路径在运行（服务未启动，端口未监听）。
- 从 1.9.1 覆盖安装：`mcp_prefs` 不存在即视为「关闭」；标签、储存库、账号库照常加载。
- 新增清单项：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE` 权限与 `<service android:foregroundServiceType="specialUse" android:exported="false">`（targetSdk 34 要求声明类型；本应用不上架 Play，specialUse 最贴切，备选 dataSync）。新增权限不影响覆盖安装。
- 签名不变；versionCode 只增：T-026 起 ≥ 31，之后每次发版高于所有分支当前最大值。

## 7. 获批后需要同步修改的规范文本

获批后由架构师（或随 T-026 的 PR）修改 AGENTS.md：
- §3 表格新增：`mcp_prefs` / `enabled`、`port`、`lan_enabled`、`token_enc`、`grants`、`max_body_bytes`、`call_log`；Keystore 别名 `cookiebox_mcp_key`（MCP Token）。
- §4 第三条改为：「应用不得新增任何联网上报、统计 SDK、远程配置；除 WebView 访问用户输入的网址、以及 ADR-0004 规定的 MCP 服务（默认关闭、本机监听、逐站授权）外不发任何请求。」
- §4 网页注入条款末尾加：「例外：ADR-0004 授权范围内，经用户授权的数据可交给持有 Token 的 MCP 客户端。」
- docs/ARCHITECTURE.md 增加 `mcp/` 模块与第 4 节「安全边界 · 本机服务」。

## 8. 备选方案

| 方案 | 结论 |
|---|---|
| A. 不做 MCP，维持红线 | 最安全，但无法实现目标。 |
| B. 本 ADR：本机 MCP，默认关闭，逐站授权，写操作确认 | **推荐**。 |
| C. 引入 Ktor / NanoHTTPD | NanoHTTPD 约 60 KB 但已停止维护；Ktor 体积数 MB。需求只是单端点 JSON，手写更可控，不引入。 |
| D. 云端中继 / 隧道，让远程 AI 直接访问 | 拒绝：等于把用户登录态暴露到公网。 |
| E. 走 Android IPC（AIDL / ContentProvider）而不是 HTTP | 主流 MCP 客户端不支持；可作为未来补充。 |

## 9. 后果

- 正面：AI 客户端能以用户真实登录态读取数据、调用接口；默认关闭，对不用此功能的用户零影响。
- 代价：新增一个本机攻击面（端口 + Token）；被授权站点的 Cookie 和数据可能经 AI 客户端离开手机，风险由用户逐站承担；前台服务常驻通知、少量耗电。
- 残余风险（不防护）：root 设备或同机恶意应用窃取 Token；用户自行把 Token 交给不可信客户端；局域网模式下的明文嗅探（已提示）；AI 在用户确认后执行的写操作本身的后果。

## 10. 需要主人裁决

1. 是否批准本例外（§3 的范围）。
2. §5.5 的「`get_cookies` 单独授权、默认不勾」是否采纳。
3. 前台服务类型用 `specialUse`（推荐）还是 `dataSync`。
4. 任务分支是否按本次指令从 `main` 切出并 PR 到 `main`（AGENTS.md §2 原写「从集成分支切出」，`integrate/1.7` 已落后 main，建议同步修改 §2）。
