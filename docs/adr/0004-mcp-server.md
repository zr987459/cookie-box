# ADR-0004：本机 MCP 服务（让 AI 客户端使用已登录的标签会话）

- 状态：**已采纳**（2026-10-20 主人批准，裁决见 §10）
- 日期：2026-10-20（修订 1：主人要求放宽对 AI 的限制、对站点表现得与浏览器本身一致；修订 2：审查意见，修正技术可行性）
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
- `initialize` 时生成 `Mcp-Session-Id`（随机，内存保存，服务停止即失效）；协议版本按客户端请求协商，支持 2025-03-26、2025-06-18、2025-11-25（客户端请求的版本在列表内则原样回应，否则回应列表中最新版）；之后的请求校验 `MCP-Protocol-Version` 头，缺省按 2025-03-26 处理。
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
| `get_cookies` | `session_id`, `domain` 或 `url` | `[{name, value}]`。取自该 Profile 的 `CookieManager.getCookie(url)`（只给 `name=value; …`），内容包括 HttpOnly Cookie，但**不提供** domain / path / expires / HttpOnly 等属性。只传 `domain` 时按 `https://<domain>/` 取，路径受限的 Cookie 需传具体 `url`。目标必须属于已授权域名。 | 读 |
| `http_request` | `session_id`, `url`, `method`, `headers?`, `body?`, `mode?`(`auto`/`page`/`native`) | 状态码、响应头、正文（按 `max_body_bytes` 截断并标注 `truncated`），文本按 charset 解码，二进制返回 Base64 | GET/HEAD = 读；其他 = 写 |

`http_request` 细则（修订 1：对站点与该标签的浏览器行为保持一致）：

两种发送通道，`mode=auto` 时自动选择：
- **`page` 通道（标签已加载且目标与页面同源 / 站点允许跨域时优先）**：在该标签页面上下文里执行 `fetch(url, {credentials:'include'})`。请求由 WebView 自己的网络栈发出，TLS / HTTP2 指纹、Cookie、`Sec-Fetch-*`、`Origin`、`Referer`、UA 与页面里的正常请求完全相同；`Set-Cookie` 由 WebView 自然处理。受浏览器 CORS、CSP 与禁用请求头规则约束。
  - **取结果的机制**（`evaluateJavascript` 不等待 Promise，直接返回只会得到 `"{}"`）：主线程生成随机变量名 `k = "__cb_" + 16 字节随机十六进制`，注入一段**立即返回**的脚本：启动 `fetch`，在 `then/catch` 里把 `{state:'done'|'error', status, headers, bodyB64, truncated, error}` 写入 `window[k]`（初始为 `{state:'pending'}`），响应体读取时按 `page_max_body_bytes` 截断，二进制统一 Base64。随后每 100 ms 用 `evaluateJavascript("JSON.stringify(window[k] && window[k].state)")` 轮询，完成后按 256 KiB 分块取回 `bodyB64`（`window[k].bodyB64.substr(off, len)`），取完立即 `delete window[k]`。总超时 30 s；超时、标签被关闭或页面在等待期间导航（变量随文档丢失）都视为失败。
  - 备选机制：创建 WebView 时用 `WebViewCompat.addWebMessageListener` 注册限定 origin 的监听对象，脚本完成后 `postMessage` 回传。它只对注册**之后**的导航生效，已打开的页面需重新加载，且要求 WebView 支持 `WEB_MESSAGE_LISTENER`；因此作为可选优化，默认用上面的轮询方案。**不得使用 `addJavascriptInterface`。**
  - 变量名每次随机、用完删除，页面脚本可以看到它但猜不到下一次的名字；回传内容只是本次 fetch 的结果，不涉及页面其他数据。
  - **回退**：`mode=auto` 时，若 fetch 被拒（CORS 失败或 CSP `connect-src` 拦截都表现为 `TypeError`；脚本同时监听 `securitypolicyviolation` 以在结果里区分原因），或响应超过 `page_max_body_bytes`，GET / HEAD 自动改走 `native` 通道重发，返回值标注 `channel:"native"` 与回退原因。`mode=page` 时不回退，直接返回错误。
  - **非 GET 请求不自动回退**：CORS「简单请求」（如表单 POST）不发预检，浏览器会先把请求发到站点、只是不让脚本读响应，此时写操作可能已经生效；自动重发会造成重复提交。因此非 GET 在 page 通道失败时返回错误（附原因），由调用方决定是否以 `mode=native` 重试。
  - `page_max_body_bytes` 默认 256 KiB（上限 2 MiB），单独小于 native 通道的上限，避免大字符串在 JS 与 Java 之间反复拷贝。
- **`native` 通道（标签未加载、page 通道回退或二进制大响应时）**：后台线程用 `HttpURLConnection` 发送，不需要 WebView 打开。为与该标签保持一致：
  - 进程内不设置全局 `CookieHandler`（当前 main 没有任何 `CookieHandler.setDefault` 调用）；native 通道每次发送前检查 `CookieHandler.getDefault() == null`，非空则报错，不去改动全局状态。`Cookie` 头完全由我们手动设置。
  - Cookie 取自该 Profile 的 `CookieManager.getCookie(url)`（主线程取）。
  - `User-Agent` 用该标签 WebView 实际的 UA（区分电脑版模式），`Accept-Language` 与 WebView 一致；`Referer` 默认取该标签当前页面；调用方传入的请求头覆盖默认值（`Host`、`Cookie` 除外，`Cookie` 始终来自该身份）。
  - `setInstanceFollowRedirects(false)`，重定向手动跟随（规则见下）。
  - 响应的多个 `Set-Cookie` 头从 `headerFields["Set-Cookie"]`（大小写不敏感）逐条取出，每条用 `setCookie(<本跳请求 URL>, <整条头值>)` 写回该 Profile 的 CookieManager（属性由 CookieManager 自己解析，HttpOnly / Secure / Domain / Path 生效），每一跳写回后再取下一跳的 Cookie，全部结束后 `flush()`。让站点轮换的会话凭据与 WebView 保持同步，避免掉线。
  - 已知差异：TLS / HTTP2 指纹与 Chromium 不同（仓库 `CookieKeepAliveEngine` 注释里已记录这一点），对指纹很敏感的站点优先用 `page` 通道。
- 同一「身份 × 域名」的请求串行发送（与 WebView 的连接复用一致，也避免突发并发）。
- 允许 `http://` 与 `https://`；目标主机必须等于或是已授权域名的子域。native 通道重定向逐跳手动跟随（最多 10 跳，301/302/303 按浏览器规则改 GET 并丢弃正文，307/308 保持方法与正文），每跳重新校验域名、写回 Set-Cookie、重新取 Cookie；跳出授权域名时停止并原样返回 3xx。page 通道的重定向由浏览器处理（`redirect:'follow'`），结果里报告最终 URL，最终 URL 不在授权域名内则丢弃正文并报错。
- **回环地址拦截**（防止借域名打回本机 MCP 端口）：拒绝 `127.0.0.0/8`、`::1`、`0.0.0.0`、`::`（未指定地址）、IPv4 映射的 `::ffff:127.0.0.0/104` 与 `::ffff:0.0.0.0`。native 通道先用 `InetAddress.getAllByName` 解析，任一结果命中即拒绝；然后**用已校验的 IP 建立连接**（URL 主机仍为原域名以保留 `Host` 与 TLS SNI / 证书校验，经自定义 `Dns`/`SocketFactory` 或按 IP 连接 + `HostnameVerifier` 校验原域名实现），防止校验与连接之间的 DNS 重绑定。每一跳重定向都重新解析和校验。page 通道无法控制解析，靠 §5.3 的 Host 校验 + Token 防护本机端口。其他地址不限制。
- native 通道正文默认截断上限 1 MiB，可在设置里调整（上限 8 MiB）；page 通道见上。

第二批（T-029，页面操作，写操作需确认）：`open_url`、`get_page_text`、`screenshot`（读）；`click(selector)`、`fill(selector, text)`、`eval_js(code)`（写）。
- 「等待加载完成」：以 `onPageFinished` + `document.readyState === 'complete'` + 进度 100 为准，带超时；标签处于 `pendingUrl` 状态时先触发加载再等待。
- 注入走 T-016 的统一判定入口，但对 MCP 调用默认放行；`fill` 可以填写密码框（AI 的值来自调用方，不读取账号库）。
- 不新增 `addJavascriptInterface`。同步结果直接取 `evaluateJavascript` 回调；`eval_js` 的代码若返回 Promise，或 `click` / `fill` 需要等待后续变化，统一复用 page 通道的「随机 window 变量 + 轮询 + 分块取回」机制。
- 页面内容可能包含针对 AI 的「提示注入」；工具返回的页面文本附带来源说明，写操作是否确认由 §5.4 的用户设置决定。

## 5. 安全底线

> 修订 1 的取舍：**限制 AI 的条目改为用户可调、默认宽松**（§5.4、§5.5）；**防止手机上其他应用 / 网页冒用这个端口的条目保留**（§5.1～§5.3、§5.6、§5.7）——它们不影响 AI 能做什么，只保证能用这个端口的只有你的 AI 客户端。

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
- `Origin` 校验（防 DNS rebinding 与网页跨站调用）：无 `Origin` 头（原生 MCP 客户端）放行；有 `Origin` 头时默认 403，除非它在设置里的「Origin 白名单」中（精确匹配 scheme + host + port，例如 MCP Inspector 的 `http://localhost:6274`，或 Electron 客户端的 `file://` / 自定义 scheme；白名单默认为空）。命中白名单的请求回 `Access-Control-Allow-Origin: <该 Origin>` 并处理 `OPTIONS` 预检。
- `Host` 头必须是 `127.0.0.1:<端口>` / `localhost:<端口>`（局域网开启时外加本机局域网 IP），否则 403。
- Token 在设置页可复制，剪贴板标记为敏感（同 T-007）。

### 5.4 写操作确认（用户可调）
- 写操作 = 非 GET/HEAD 的 `http_request`、`click`、`fill`、`eval_js`。
- 设置里三档，**默认「信任的站点免确认」**：
  1. 每次确认；
  2. 信任的站点免确认（默认）：授权时可勾「信任，写操作不询问」，未勾的站点仍弹确认；
  3. 全部免确认：开启时提示一次「AI 将可以在已授权站点上提交、发帖、付款等，无需你确认」。
- 需要确认时显示：工具名、标签自定义名 / 身份、目标站点、摘要（方法 + 路径、选择器、脚本前 200 字；不显示 Cookie 与请求头值）。Activity 在前台用对话框，在后台发高优先级通知点按进入；60 s 无响应视为拒绝。确认框里可选「该站点以后不再询问」（写入 `grants`，随时可在白名单里取消）。

### 5.5 站点授权（用户可调）
- 授权单位是「身份（profileName）× 域名」。白名单界面提供：逐个勾选、「授权该身份全部站点」、「授权全部标签」三种粒度。未授权的不出现在 `list_sessions`，按 id 调用返回「未授权」。
- **候选域名来源**：Android `CookieManager` 无法列举有 Cookie 的域名，只能按 URL 取。因此 MCP 服务开启后，由 `TabRegistry` 在标签主框架导航完成时把注册域名（去掉 `www.` 等前缀后的 host）按 profileName 记入 `mcp_prefs.visited_hosts`（每身份最多 200 个，按最近使用淘汰），启动时再补入当前所有标签 URL 的 host；加上用户手动添加的域名。服务关闭期间不记录。
- `get_cookies` 不再需要单独授权：被授权的「身份 × 域名」即可读取全部 Cookie（含 HttpOnly）。
- 「授权该身份全部站点 / 授权全部标签」= 授权该身份 `visited_hosts` 中的全部域名 + 用户手动列表，以及之后新访问到的域名（自动纳入）；目标必须等于或是其中某个域名的子域。

### 5.6 存储
- 新建 SharedPreferences 文件 `mcp_prefs`，**不读写任何已有文件和键**。键：`enabled`、`port`、`lan_enabled`、`token_enc`、`grants`（JSON，含每站「信任」标记）、`confirm_mode`、`max_body_bytes`、`page_max_body_bytes`、`visited_hosts`（JSON，按 profileName 分组）、`origin_allowlist`、`call_log`（JSON 环形，最多 500 条）。
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
- §3 表格新增：`mcp_prefs` / `enabled`、`port`、`lan_enabled`、`token_enc`、`grants`、`confirm_mode`、`max_body_bytes`、`page_max_body_bytes`、`visited_hosts`、`origin_allowlist`、`call_log`；Keystore 别名 `cookiebox_mcp_key`（MCP Token）。
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
- 代价：新增一个本机攻击面（端口 + Token）；被授权站点的 Cookie 和数据可能经 AI 客户端离开手机；免确认档位下 AI 的写操作直接生效，后果由用户承担；前台服务常驻通知、少量耗电。
- 残余风险（不防护）：root 设备或同机恶意应用窃取 Token；用户自行把 Token 交给不可信客户端；局域网模式下的明文嗅探（已提示）；AI 写操作本身的后果；站点服务条款对自动化访问的限制（`page` 通道行为与浏览器一致，但请求节奏由 AI 决定，频率过高仍可能触发站点风控）。

## 10. 需要主人裁决

2026-10-20 主人裁决（全部按推荐）：

1. **批准**本例外（§3 的范围）。
2. `get_cookies` 随站点授权；§5.4 写确认默认档位为「信任的站点免确认」。
3. 前台服务类型用 `specialUse`。
4. 任务分支从 `main` 切出并 PR 到 `main`；AGENTS.md §2 已同步修改。
