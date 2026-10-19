package com.cookiebox.app.mcp.tools

import android.content.Context
import android.os.LocaleList
import android.util.Base64
import android.util.Log
import com.cookiebox.app.mcp.McpGrants
import com.cookiebox.app.mcp.McpPrefs
import com.cookiebox.app.mcp.McpSessionExpiredException
import com.cookiebox.app.mcp.McpTabs
import com.cookiebox.app.mcp.McpTimeoutException
import com.cookiebox.app.mcp.McpTool
import com.cookiebox.app.mcp.McpToolContext
import com.cookiebox.app.mcp.McpToolException
import com.cookiebox.app.mcp.McpToolResult
import com.cookiebox.app.mcp.WriteGuard
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * http_request（ADR-0004 §4.3）：以某个标签身份向已授权域名发请求。
 * - page 通道：在标签页面里 fetch(credentials:'include')，随机 window 变量 + 轮询 + 分块取回，不用 addJavascriptInterface。
 * - native 通道：HttpURLConnection，手动 Cookie、手动重定向（最多 10 跳）、Set-Cookie 逐条写回该身份，回环地址拦截。
 * - GET / HEAD / OPTIONS 为读；其他方法是写操作，先经 [WriteGuard] 确认（T-028）。
 * - 写操作在 page 通道失败时不自动改走 native（简单请求可能已经送达站点），由调用方决定是否 mode=native 重试。
 */
class HttpRequest(private val context: Context) : McpTool {

    companion object {
        private const val TAG = "CookieBoxMcp"
        private const val PAGE_TIMEOUT_MS = 30_000L
        private const val POLL_INTERVAL_MS = 100L
        private const val CHUNK_CHARS = 256 * 1024
        private const val MAX_REDIRECTS = 10
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000

        private val READ_METHODS = setOf("GET", "HEAD", "OPTIONS")
        private val ALLOWED_METHODS = setOf("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE")
        /** 调用方不能覆盖的请求头 */
        private val BLOCKED_HEADERS = setOf("host", "cookie", "content-length", "connection", "transfer-encoding", "keep-alive", "upgrade", "te", "trailer")

        private val locks = ConcurrentHashMap<String, Any>()
        private val random = SecureRandom()

        private val PAGE_FETCH_JS = """
(function(){
var k=__K__,lim=__LIM__;
if(window[k])return 'dup';
window[k]={state:'pending'};
var csp=null;
function onv(e){try{var d=(e&&(e.effectiveDirective||e.violatedDirective))||'';if(d.indexOf('connect')>=0)csp=d;}catch(x){}}
try{document.addEventListener('securitypolicyviolation',onv);}catch(x){}
function b64(u){var s='';for(var i=0;i<u.length;i+=32768){s+=String.fromCharCode.apply(null,u.subarray(i,i+32768));}return btoa(s);}
function fin(o){try{document.removeEventListener('securitypolicyviolation',onv);}catch(x){}window[k]=o;}
var init={method:__M__,headers:__H__,credentials:'include',redirect:'follow',cache:'no-store'};
var bb=__B__;if(bb!==null){if(__BB64__){var bs=atob(bb),ba=new Uint8Array(bs.length);for(var j=0;j<bs.length;j++)ba[j]=bs.charCodeAt(j);init.body=ba;}else{init.body=bb;}}
fetch(__U__,init).then(function(r){
var h={};r.headers.forEach(function(v,n){h[n]=v;});
var meta={state:'done',status:r.status,statusText:r.statusText,url:r.url,redirected:r.redirected,headers:h};
if(!r.body){return r.arrayBuffer().then(function(buf){var u=new Uint8Array(buf);var tr=u.length>lim;meta.truncated=tr;meta.size=u.length;meta.bodyB64=b64(tr?u.subarray(0,lim):u);fin(meta);});}
var rd=r.body.getReader(),chunks=[],n=0,tr=false;
function pump(){return rd.read().then(function(x){
if(x.done)return;
var v=x.value;
if(n+v.length>lim){chunks.push(v.subarray(0,lim-n));n=lim;tr=true;try{rd.cancel();}catch(e){}return;}
chunks.push(v);n+=v.length;return pump();});}
return pump().then(function(){var u=new Uint8Array(n),o=0;for(var i=0;i<chunks.length;i++){u.set(chunks[i],o);o+=chunks[i].length;}
meta.truncated=tr;meta.size=n;meta.bodyB64=b64(u);fin(meta);});
}).catch(function(e){fin({state:'error',error:String((e&&e.message)||e),name:(e&&e.name)||'',csp:csp});});
return 'ok';
})()
""".trimIndent()

        fun isBlockedAddress(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isAnyLocalAddress) return true
            if (a is Inet4Address) {
                val b = a.address
                return (b[0].toInt() and 0xff) == 127 || b.all { it.toInt() == 0 }
            }
            if (a is Inet6Address) {
                val b = a.address
                val mapped = (0..9).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
                if (mapped) {
                    val first = b[12].toInt() and 0xff
                    return first == 127 || (12..15).all { b[it].toInt() == 0 }
                }
            }
            return false
        }
    }

    override val name = "http_request"
    override val description =
        "Send an HTTP(S) request as a tab's logged-in identity to an authorized site, exactly like the browser would " +
            "(same cookies, User-Agent, Referer). mode=page runs fetch() inside the tab's page (same TLS fingerprint and " +
            "headers as the site's own requests, subject to CORS/CSP); mode=native sends from the app with the profile's " +
            "cookies and writes Set-Cookie back to the profile; mode=auto (default) tries page first when the tab is loaded " +
            "and falls back to native for GET/HEAD. POST/PUT/PATCH/DELETE are write operations: the user may be asked to " +
            "confirm on the phone (up to 60 s) and a denial returns an error; a failed write on the page channel is NOT retried " +
            "automatically (it may already have reached the site), retry with mode=native only if appropriate. Text bodies are returned as text, " +
            "binary as base64; large bodies are truncated (truncated=true)."
    override val inputSchema: JSONObject = ToolSupport.schema(
        JSONObject()
            .put("session_id", ToolSupport.str("Tab id from list_sessions"))
            .put("url", ToolSupport.str("Absolute http(s) URL on an authorized domain"))
            .put("method", ToolSupport.str("HTTP method, default GET", listOf("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE")))
            .put(
                "headers",
                JSONObject().put("type", "object").put("description", "Extra request headers (Cookie and Host are ignored)")
                    .put("additionalProperties", JSONObject().put("type", "string"))
            )
            .put("body", ToolSupport.str("Request body as text (set Content-Type in headers); ignored for GET/HEAD"))
            .put("body_base64", ToolSupport.str("Binary request body as base64 (instead of body)"))
            .put("mode", ToolSupport.str("Channel: auto (default), page or native", listOf("auto", "page", "native"))),
        listOf("session_id", "url")
    )
    override val annotations: JSONObject = JSONObject()
        .put("readOnlyHint", false).put("destructiveHint", true).put("idempotentHint", false).put("openWorldHint", true)

    private class Outcome(val result: JSONObject)
    private class PageFailure(val reason: String, val detail: String) : Exception(reason)

    override fun call(arguments: JSONObject, context: McpToolContext): McpToolResult {
        val session = ToolSupport.resolveSession(this.context, ToolSupport.requireString(arguments, "session_id"))
        val url = ToolSupport.requireString(arguments, "url")
        val host = McpGrants.hostOf(url) ?: throw McpToolException("url 必须是 http:// 或 https:// 网址")
        context.logLabel = session.customName.ifBlank { session.profileName }
        context.logDomain = host
        ToolSupport.requireAuthorized(this.context, session, host)

        val method = (ToolSupport.optString(arguments, "method") ?: "GET").uppercase(Locale.ROOT)
        if (method !in ALLOWED_METHODS) throw McpToolException("不支持的方法 $method")
        val mode = (ToolSupport.optString(arguments, "mode") ?: "auto").lowercase(Locale.ROOT)
        if (mode !in setOf("auto", "page", "native")) throw McpToolException("mode 只能是 auto / page / native")
        val headers = parseHeaders(arguments.opt("headers"))
        val isWrite = method !in READ_METHODS
        val body: Body? = if (method == "GET" || method == "HEAD") null else parseBody(arguments)

        if (isWrite) {
            // 摘要只含方法 + 路径（不含查询值、请求头值、正文）
            val path = try { URI(url).rawPath?.ifEmpty { "/" } ?: "/" } catch (e: Exception) { "/" }
            val query = if (url.substringBefore('#').contains('?')) "?…" else ""
            val bodyNote = body?.let { "（正文 ${it.bytes.size} 字节）" } ?: ""
            WriteGuard.check(
                this.context, name, context.logLabel, session.profileName, host,
                "$method $host$path$query$bodyNote", context.sessionId
            )
        }

        // 同一「身份 × 域名」串行
        val lock = locks.getOrPut(session.profileName + "|" + McpGrants.registrableDomain(host)) { Any() }
        val out = synchronized(lock) {
            when (mode) {
                "native" -> native(session, method, url, headers, body, null)
                "page" -> try {
                    page(session, method, url, headers, body)
                } catch (e: PageFailure) {
                    throw McpToolException("page 通道失败（${e.reason}）：${e.detail}")
                }
                else -> {
                    val pageUsable = McpTabs.onMain { it.tab(session.id)?.loaded == true }
                    if (!pageUsable) {
                        // 页面没加载：写操作也直接走 native（没有发出过任何请求，不存在重复提交）
                        native(session, method, url, headers, body, JSONObject().put("from", "page").put("reason", "tab page not loaded"))
                    } else {
                        try {
                            page(session, method, url, headers, body)
                        } catch (e: PageFailure) {
                            if (isWrite && e.reason != "inject_failed" && e.reason != "tab_closed") {
                                // 写操作可能已送达站点：不自动重发
                                throw McpToolException(
                                    "page 通道失败（${e.reason}）：${e.detail}。写操作不会自动重试，请求可能已送达站点；" +
                                        "确认未生效后可用 mode=native 重试"
                                )
                            }
                            native(session, method, url, headers, body, JSONObject().put("from", "page").put("reason", e.reason).put("detail", e.detail))
                        }
                    }
                }
            }
        }
        return McpToolResult.json(out.result)
    }

    private class Body(val bytes: ByteArray, val isBase64: Boolean, val text: String?)

    private fun parseBody(args: JSONObject): Body? {
        val b64 = ToolSupport.optString(args, "body_base64")
        if (b64 != null) {
            val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: IllegalArgumentException) {
                throw McpToolException("body_base64 不是有效的 Base64")
            }
            return Body(bytes, true, b64)
        }
        val v = args.opt("body")
        if (v == null || v == JSONObject.NULL) return null
        val text = v.toString()
        return Body(text.toByteArray(Charsets.UTF_8), false, text)
    }

    private fun parseHeaders(raw: Any?): Map<String, String> {
        if (raw == null || raw == JSONObject.NULL) return emptyMap()
        if (raw !is JSONObject) throw McpToolException("headers 必须是对象")
        val out = LinkedHashMap<String, String>()
        for (k in raw.keys()) {
            val name = k.trim()
            if (name.isEmpty() || name.any { it == '\r' || it == '\n' || it == ':' }) continue
            if (name.lowercase(Locale.ROOT) in BLOCKED_HEADERS) continue
            val v = raw.opt(k)?.toString() ?: continue
            if (v.any { it == '\r' || it == '\n' }) continue
            out[name] = v
        }
        return out
    }

    // =====================================================================
    // page 通道
    // =====================================================================

    private fun page(session: ToolSupport.Session, method: String, url: String, headers: Map<String, String>, body: Body?): Outcome {
        val limit = McpPrefs(context).pageMaxBodyBytes
        val k = "__cb_" + ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val kq = JSONObject.quote(k)
        val h = JSONObject()
        headers.forEach { (n, v) -> h.put(n, v) }
        // 单次替换所有占位符，避免调用方的请求头 / 正文里恰好含占位符被二次替换
        val values = mapOf(
            "__K__" to kq,
            "__LIM__" to limit.toString(),
            "__M__" to JSONObject.quote(method),
            "__H__" to h.toString(),
            "__U__" to JSONObject.quote(url),
            "__BB64__" to if (body?.isBase64 == true) "true" else "false",
            "__B__" to if (body == null) "null" else JSONObject.quote(body.text ?: "")
        )
        val script = Regex("__(K|LIM|M|H|U|BB64|B)__").replace(PAGE_FETCH_JS) { m -> values[m.value] ?: m.value }

        val deadline = System.currentTimeMillis() + PAGE_TIMEOUT_MS
        try {
            val started = decode(evalOrFail(session.id, script))
            if (started != "ok") throw PageFailure("inject_failed", "script did not start")

            val stateScript = "(function(){var o=window[$kq];return o?o.state:'gone';})()"
            var state: String? = null
            while (true) {
                if (System.currentTimeMillis() > deadline) throw PageFailure("timeout", "page fetch exceeded 30 s")
                Thread.sleep(POLL_INTERVAL_MS)
                state = decode(evalOrFail(session.id, stateScript))
                if (state == "pending") continue
                break
            }
            if (state == "gone" || state == null) throw PageFailure("navigated", "the page navigated while waiting")

            val metaScript = "(function(){var o=window[$kq];if(!o)return null;var c={};for(var p in o){if(p!=='bodyB64')c[p]=o[p];}" +
                "c.b64len=o.bodyB64?o.bodyB64.length:0;return JSON.stringify(c);})()"
            val metaText = decode(evalOrFail(session.id, metaScript)) ?: throw PageFailure("navigated", "result lost")
            val meta = try { JSONObject(metaText) } catch (e: JSONException) { throw PageFailure("bad_result", "unreadable result") }

            if (state == "error") {
                val csp = meta.optString("csp", "")
                val reason = if (csp.isNotEmpty() && csp != "null") "csp" else "fetch_error"
                val detail = (meta.optString("name", "") + " " + meta.optString("error", "")).trim().take(200)
                throw PageFailure(reason, if (reason == "csp") "blocked by CSP $csp" else "$detail (often CORS)")
            }

            val finalUrl = meta.optString("url", url)
            val finalHost = McpGrants.hostOf(finalUrl)
            if (finalHost == null || !McpGrants.isAuthorized(context, session.profileName, finalHost)) {
                throw McpToolException("请求被重定向到未授权的站点（$finalHost），已丢弃正文")
            }
            if (meta.optBoolean("truncated", false)) {
                throw PageFailure("too_large", "response exceeds page_max_body_bytes ($limit)")
            }

            val b64len = meta.optInt("b64len", 0)
            val sb = StringBuilder(b64len)
            var off = 0
            while (off < b64len) {
                if (System.currentTimeMillis() > deadline) throw PageFailure("timeout", "reading body exceeded 30 s")
                val chunk = decode(
                    evalOrFail(session.id, "(function(){var o=window[$kq];return o&&o.bodyB64?o.bodyB64.substr($off,$CHUNK_CHARS):null;})()")
                ) ?: throw PageFailure("navigated", "result lost while reading body")
                if (chunk.isEmpty()) break
                sb.append(chunk)
                off += chunk.length
            }
            val bytes = Base64.decode(sb.toString(), Base64.DEFAULT)

            val respHeaders = meta.optJSONObject("headers") ?: JSONObject()
            val contentType = respHeaders.optString("content-type", "")
            val result = JSONObject()
                .put("channel", "page")
                .put("status", meta.optInt("status"))
                .put("statusText", meta.optString("statusText", ""))
                .put("url", finalUrl)
                .put("redirected", meta.optBoolean("redirected", false))
                .put("headers", respHeaders)
            putBody(result, bytes, contentType, false)
            return Outcome(result)
        } catch (e: McpSessionExpiredException) {
            throw PageFailure("tab_closed", e.message ?: "tab closed")
        } catch (e: McpTimeoutException) {
            throw PageFailure("timeout", "main thread busy")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PageFailure("interrupted", "interrupted")
        } finally {
            try {
                McpTabs.evalJs(session.id, "try{delete window[$kq];}catch(e){}")
            } catch (e: Exception) {
                // 标签已关闭等：变量随页面一起消失
            }
        }
    }

    private fun evalOrFail(tabId: String, script: String): String? = McpTabs.evalJs(tabId, script)

    /** evaluateJavascript 的回调是 JSON 编码的值；字符串解出原文，null / undefined 返回 null */
    private fun decode(raw: String?): String? {
        if (raw == null || raw == "null" || raw == "undefined") return null
        if (raw.startsWith("\"")) {
            return try { JSONArray("[$raw]").getString(0) } catch (e: JSONException) { null }
        }
        return raw
    }

    // =====================================================================
    // native 通道
    // =====================================================================

    private class TabContext(val url: String, val userAgent: String)

    private fun native(
        session: ToolSupport.Session,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: Body?,
        fallback: JSONObject?
    ): Outcome {
        if (CookieHandler.getDefault() != null) {
            // 进程里有人设了全局 CookieHandler，会污染 Cookie 管理；不去改它，直接报错
            throw McpToolException("native 通道不可用：进程设置了全局 CookieHandler")
        }
        val tab = McpTabs.onMain { access ->
            val t = access.tab(session.id) ?: throw McpToolException("session_id 已失效，请重新调用 list_sessions")
            TabContext(t.url, t.userAgent)
        }
        val limit = McpPrefs(context).maxBodyBytes

        var current = try { URL(url) } catch (e: Exception) { throw McpToolException("url 格式不正确") }
        var currentMethod = method
        var currentBody = body
        var redirects = 0
        val chain = JSONArray()
        while (true) {
            val host = McpGrants.normalizeHost(current.host ?: "")
            if (!McpGrants.isAuthorized(context, session.profileName, host)) {
                throw McpToolException("未授权：$host")
            }
            val conn = open(current)
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.requestMethod = currentMethod

                conn.setRequestProperty("User-Agent", tab.userAgent)
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Accept-Language", acceptLanguage())
                referrerFor(tab.url, current.toString())?.let { conn.setRequestProperty("Referer", it) }
                headers.forEach { (n, v) -> conn.setRequestProperty(n, v) }

                val cookie = McpTabs.onMain { access -> access.cookieManagerFor(session.profileName)?.getCookie(current.toString()) }
                if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)

                val sendBody = currentBody
                if (sendBody != null && currentMethod != "GET" && currentMethod != "HEAD") {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(sendBody.bytes.size)
                    conn.outputStream.use { it.write(sendBody.bytes) }
                }

                val code = conn.responseCode
                val setCookies = conn.headerFields.entries
                    .filter { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }
                    .flatMap { it.value ?: emptyList() }
                if (setCookies.isNotEmpty()) {
                    val hopUrl = current.toString()
                    McpTabs.onMain { access ->
                        val cm = access.cookieManagerFor(session.profileName)
                        setCookies.forEach { cm?.setCookie(hopUrl, it) }
                    }
                }

                val location = conn.getHeaderField("Location")
                if (code in setOf(301, 302, 303, 307, 308) && !location.isNullOrBlank()) {
                    val next = try { URL(current, location) } catch (e: Exception) { null }
                    val nextHost = next?.host?.let { McpGrants.normalizeHost(it) }
                    val nextOk = next != null && next.protocol.lowercase(Locale.ROOT) in setOf("http", "https") &&
                        nextHost != null && McpGrants.isAuthorized(context, session.profileName, nextHost)
                    if (nextOk && redirects < MAX_REDIRECTS) {
                        chain.put(JSONObject().put("status", code).put("url", current.toString()))
                        // 301/302 的 POST 与所有 303（HEAD 除外）改 GET 并丢弃正文；307/308 保持方法与正文
                        if ((code == 303 && currentMethod != "HEAD") || (code in setOf(301, 302) && currentMethod == "POST")) {
                            currentMethod = "GET"
                            currentBody = null
                        }
                        redirects++
                        drain(conn)
                        current = next!!
                        continue
                    }
                    // 跳出授权域名或超过 10 跳：原样返回 3xx
                    val result = buildNativeResult(conn, code, current, chain, limit, currentMethod)
                    result.put(
                        "redirectStopped",
                        if (!nextOk) "next location is outside authorized domains" else "too many redirects (max $MAX_REDIRECTS)"
                    )
                    result.put("location", location)
                    fallback?.let { result.put("fallback", it) }
                    flush(session)
                    return Outcome(result)
                }

                val result = buildNativeResult(conn, code, current, chain, limit, currentMethod)
                fallback?.let { result.put("fallback", it) }
                flush(session)
                return Outcome(result)
            } catch (e: McpToolException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "native request failed: ${e.javaClass.simpleName}")
                throw McpToolException("网络错误：${e.javaClass.simpleName}")
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 解析 + 回环拦截 + 按已校验 IP 建连（https 保留原域名做 SNI / 证书校验，连接后再核对实际 IP） */
    private fun open(url: URL): HttpURLConnection {
        val scheme = url.protocol.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") throw McpToolException("只支持 http / https")
        val host = url.host ?: throw McpToolException("url 缺少主机名")
        val addrs = try {
            InetAddress.getAllByName(host)
        } catch (e: IOException) {
            throw McpToolException("域名解析失败：$host")
        }
        if (addrs.isEmpty() || addrs.any { isBlockedAddress(it) }) {
            throw McpToolException("拒绝访问本机回环地址")
        }
        val direct = try {
            ProxySelector.getDefault()?.select(url.toURI())?.all { it.type() == Proxy.Type.DIRECT } ?: true
        } catch (e: Exception) {
            true
        }
        return if (scheme == "https") {
            val c = url.openConnection() as HttpsURLConnection
            if (direct) c.sslSocketFactory = GuardedSslSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory())
            c
        } else if (direct) {
            val ip = addrs.first().hostAddress ?: throw McpToolException("域名解析失败：$host")
            val byIp = URL("http", ip, url.port, url.file)
            val c = byIp.openConnection() as HttpURLConnection
            val hostHeader = if (url.port == -1 || url.port == 80) host else "$host:${url.port}"
            c.setRequestProperty("Host", hostHeader)
            c
        } else {
            // 走系统代理：由代理解析域名，已做过解析结果校验
            url.openConnection() as HttpURLConnection
        }
    }

    private fun buildNativeResult(
        conn: HttpURLConnection,
        code: Int,
        current: URL,
        chain: JSONArray,
        limit: Int,
        method: String
    ): JSONObject {
        val respHeaders = JSONObject()
        var setCookieCount = 0
        for ((k, v) in conn.headerFields) {
            if (k == null) continue
            if (k.equals("Set-Cookie", ignoreCase = true)) {
                setCookieCount += v?.size ?: 0
                continue
            }
            respHeaders.put(k.lowercase(Locale.ROOT), (v ?: emptyList()).joinToString(", "))
        }
        val result = JSONObject()
            .put("channel", "native")
            .put("status", code)
            .put("statusText", conn.responseMessage ?: "")
            .put("url", current.toString())
            .put("redirected", chain.length() > 0)
            .put("headers", respHeaders)
        if (chain.length() > 0) result.put("redirectChain", chain)
        if (setCookieCount > 0) result.put("setCookieApplied", setCookieCount)

        if (method == "HEAD" || code == 204 || code == 304) {
            putBody(result, ByteArray(0), conn.contentType ?: "", false)
            return result
        }
        val stream: InputStream? = try {
            if (code >= 400) conn.errorStream else conn.inputStream
        } catch (e: IOException) {
            conn.errorStream
        }
        var truncated = false
        val bytes = if (stream == null) ByteArray(0) else stream.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                val room = limit - out.size()
                if (n > room) {
                    out.write(buf, 0, room.coerceAtLeast(0))
                    truncated = true
                    break
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        putBody(result, bytes, conn.contentType ?: "", truncated)
        return result
    }

    private fun drain(conn: HttpURLConnection) {
        try {
            (conn.errorStream ?: conn.inputStream)?.use { s ->
                val buf = ByteArray(8 * 1024)
                var total = 0
                while (total < 64 * 1024) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
        } catch (e: IOException) {
            // 忽略
        }
    }

    private fun flush(session: ToolSupport.Session) {
        try {
            McpTabs.onMain { access -> access.cookieManagerFor(session.profileName)?.flush() }
        } catch (e: Exception) {
            // flush 失败不影响本次结果
        }
    }

    /** strict-origin-when-cross-origin，与浏览器默认一致 */
    private fun referrerFor(pageUrl: String, target: String): String? {
        return try {
            val p = URI(pageUrl)
            val t = URI(target)
            val ps = p.scheme?.lowercase(Locale.ROOT) ?: return null
            if (ps != "http" && ps != "https" || p.host == null) return null
            val ts = t.scheme?.lowercase(Locale.ROOT) ?: return null
            val origin = "$ps://${p.rawAuthority}"
            val sameOrigin = ps == ts && p.rawAuthority.equals(t.rawAuthority, ignoreCase = true)
            when {
                sameOrigin -> pageUrl.substringBefore('#')
                ps == "https" && ts == "http" -> null
                else -> "$origin/"
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun acceptLanguage(): String {
        val tags = mutableListOf<String>()
        val list = LocaleList.getDefault()
        for (i in 0 until list.size()) {
            val l = list[i]
            val tag = l.toLanguageTag()
            if (tag !in tags) tags.add(tag)
            if (l.language.isNotEmpty() && l.language !in tags) tags.add(l.language)
        }
        if (tags.isEmpty()) tags.add("en")
        return tags.take(6).mapIndexed { i, t ->
            if (i == 0) t else "$t;q=${"%.1f".format(Locale.ROOT, (10 - i).coerceAtLeast(1) / 10.0)}"
        }.joinToString(",")
    }

    // =====================================================================
    // 正文
    // =====================================================================

    private fun putBody(result: JSONObject, bytes: ByteArray, contentType: String, truncated: Boolean) {
        result.put("contentType", contentType)
        result.put("bodyBytes", bytes.size)
        result.put("truncated", truncated)
        if (bytes.isEmpty()) {
            result.put("body", "")
            return
        }
        val ct = contentType.lowercase(Locale.ROOT)
        val isText = ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") ||
            ct.contains("javascript") || ct.contains("x-www-form-urlencoded") || ct.contains("ecmascript") ||
            (ct.isEmpty() && looksLikeText(bytes))
        if (isText) {
            val charset = Regex("charset=\"?([A-Za-z0-9._\\-]+)").find(contentType)?.groupValues?.get(1)
            val cs = try {
                if (charset != null) Charset.forName(charset) else Charsets.UTF_8
            } catch (e: Exception) {
                Charsets.UTF_8
            }
            result.put("body", String(bytes, cs))
        } else {
            result.put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 512)
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xff
            if (b == 0) return false
        }
        return true
    }

    /**
     * 包一层默认 SSLSocketFactory：TLS 套在已连接的套接字上之前，核对实际连接的 IP，
     * 防止「校验时解析到公网、连接时被重绑定到回环」。只在直连（无代理）时使用。
     */
    private class GuardedSslSocketFactory(private val base: SSLSocketFactory) : SSLSocketFactory() {
        private fun check(s: Socket): Socket {
            val a = s.inetAddress
            if (a != null && isBlockedAddress(a)) {
                try { s.close() } catch (e: IOException) {}
                throw IOException("blocked loopback connection")
            }
            return s
        }

        override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites
        override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket {
            check(s)
            return base.createSocket(s, host, port, autoClose)
        }
        override fun createSocket(): Socket = base.createSocket()
        override fun createSocket(host: String?, port: Int): Socket = check(base.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            check(base.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket = check(base.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            check(base.createSocket(address, port, localAddress, localPort))
    }
}
