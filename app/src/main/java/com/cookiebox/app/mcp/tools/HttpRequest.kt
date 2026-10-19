package com.cookiebox.app.mcp.tools

import android.content.Context
import android.util.Base64
import android.webkit.WebSettings
import android.webkit.WebView
import com.cookiebox.app.mcp.MainThreadRunner
import com.cookiebox.app.mcp.McpPrefs
import com.cookiebox.app.mcp.McpSessions
import com.cookiebox.app.mcp.McpTool
import com.cookiebox.app.mcp.ProfileCookies
import com.cookiebox.app.mcp.SessionInfo
import com.cookiebox.app.mcp.TabRegistryHolder
import com.cookiebox.app.mcp.ToolError
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * http_request：以某个标签的登录身份请求已授权站点（ADR-0004 §4.3）。
 * 本版本只开放 GET / HEAD；非 GET 写操作需要 T-028 的确认机制，在此之前一律拒绝。
 */
class HttpRequest(private val ctx: Context) : McpTool {

    override val name = "http_request"
    override val description =
        "用某个标签的登录身份（同一套 Cookie）请求已授权站点，返回状态码、响应头和正文。" +
            "mode=page 在标签页面里用浏览器 fetch 发出（最像真人浏览器，但受 CORS/CSP 限制）；mode=native 由应用后台发送，" +
            "沿用该标签的 UA / Referer / Cookie 并把 Set-Cookie 写回；mode=auto（默认）优先 page，失败自动改用 native。" +
            "当前版本只支持 GET 和 HEAD。文本正文按 charset 解码，二进制返回 Base64。"

    override val inputSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("session_id", JSONObject().put("type", "string").put("description", "list_sessions 返回的标签 id"))
            .put("url", JSONObject().put("type", "string").put("description", "http(s) 网址，主机必须属于已授权域名"))
            .put("method", JSONObject().put("type", "string").put("enum", JSONArray().put("GET").put("HEAD")).put("default", "GET"))
            .put("headers", JSONObject().put("type", "object").put("description", "额外请求头（Cookie、Host 会被忽略）")
                .put("additionalProperties", JSONObject().put("type", "string")))
            .put("mode", JSONObject().put("type", "string").put("enum", JSONArray().put("auto").put("page").put("native")).put("default", "auto")))
        .put("required", JSONArray().put("session_id").put("url"))

    private class PageFail(val reason: String, val fallbackOk: Boolean) : Exception(reason)

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private const val MAX_REDIRECTS = 10
        private const val CHUNK = 256 * 1024
    }

    override fun call(args: JSONObject): JSONObject {
        val sid = args.optString("session_id").ifBlank { throw ToolError("缺少 session_id") }
        val url = args.optString("url").trim().ifBlank { throw ToolError("缺少 url") }
        val method = args.optString("method", "GET").trim().uppercase().ifEmpty { "GET" }
        if (method != "GET" && method != "HEAD") {
            throw ToolError("暂不支持 $method：写操作需要用户确认功能（T-028）上线后才开放，当前只支持 GET / HEAD")
        }
        val mode = args.optString("mode", "auto").lowercase().ifEmpty { "auto" }
        if (mode !in setOf("auto", "page", "native")) throw ToolError("mode 只能是 auto / page / native")
        val headers = LinkedHashMap<String, String>()
        args.optJSONObject("headers")?.let { h ->
            for (k in h.keys()) {
                if (k.equals("cookie", true) || k.equals("host", true) || k.equals("content-length", true)) continue
                headers[k] = h.optString(k)
            }
        }

        val s = McpSessions.find(ctx, sid)
        val host = McpSessions.requireAllowedUrl(ctx, s.profileName, url)
        val lockKey = s.profileName + "|" + (McpPrefs.registrableDomain(url) ?: host)
        val lock = locks.getOrPut(lockKey) { Any() }

        synchronized(lock) {
            val webView = if (mode != "native") pageWebView(s) else null
            if (mode == "page" && webView == null) {
                throw ToolError("该标签没有可用的已加载页面（或当前页面不在授权域名内），请改用 mode=native 或先在 CookieBox 里打开该标签")
            }
            if (webView != null) {
                try {
                    return viaPage(s, webView, url, method, headers)
                } catch (e: PageFail) {
                    if (mode == "page" || !e.fallbackOk) throw ToolError("page 通道失败：${e.reason}")
                    return viaNative(s, url, method, headers).put("fallbackReason", e.reason)
                }
            }
            return viaNative(s, url, method, headers)
        }
    }

    // ------------------------------------------------------------------ page 通道

    /** 标签已载入且当前页面属于授权域名时，返回它的 WebView。 */
    private fun pageWebView(s: SessionInfo): WebView? {
        if (!s.live) return null
        if (s.host.isEmpty() || !McpSessions.hostAllowed(s.host, McpSessions.allowedDomains(ctx, s.profileName))) return null
        return MainThreadRunner.run { TabRegistryHolder.get()?.webViewFor(s.id) }
    }

    private fun viaPage(s: SessionInfo, wv: WebView, url: String, method: String, headers: Map<String, String>): JSONObject {
        val max = McpPrefs.pageMaxBodyBytes(ctx)
        val key = "__cb_" + randomHex(16)
        val k = JSONObject.quote(key)
        val script = """
            (function(){
              var k=$k;
              try {
                window[k]={state:'pending'};
                var csp=false;
                var onv=function(){csp=true;};
                document.addEventListener('securitypolicyviolation',onv);
                fetch(${JSONObject.quote(url)},{method:${JSONObject.quote(method)},headers:${JSONObject(headers as Map<*, *>)},credentials:'include',redirect:'follow',cache:'no-store'})
                .then(function(r){
                  var hs={}; r.headers.forEach(function(v,n){hs[n]=v;});
                  var meta={state:'done',status:r.status,statusText:r.statusText,url:r.url,headers:hs};
                  if(${JSONObject.quote(method)}==='HEAD'){meta.bodyB64='';meta.size=0;meta.truncated=false;window[k]=meta;return;}
                  return r.arrayBuffer().then(function(buf){
                    var total=buf.byteLength, n=Math.min(total,$max);
                    var b=new Uint8Array(buf,0,n), str='';
                    for(var i=0;i<n;i+=32768){str+=String.fromCharCode.apply(null,b.subarray(i,Math.min(i+32768,n)));}
                    meta.bodyB64=btoa(str); meta.size=total; meta.truncated=total>$max;
                    window[k]=meta;
                  });
                })
                .catch(function(e){window[k]={state:'error',error:String((e&&e.message)||e),csp:csp};})
                .then(function(){document.removeEventListener('securitypolicyviolation',onv);});
                return 'started';
              } catch(e) { window[k]={state:'error',error:String(e),csp:false}; return 'error'; }
            })();
        """.trimIndent()

        MainThreadRunner.evalJs(wv, script) ?: throw PageFail("脚本未执行", true)

        val deadline = System.currentTimeMillis() + 30_000
        var state = "pending"
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            state = jsString(MainThreadRunner.evalJs(wv, "(function(){var o=window[$k];return o?o.state:'gone';})()")) ?: "gone"
            if (state != "pending") break
        }
        try {
            when (state) {
                "pending" -> throw PageFail("超时（30 秒）", false)
                "gone" -> throw PageFail("页面在请求期间发生了跳转或被关闭", method == "GET" || method == "HEAD")
                "error" -> {
                    val err = JSONObject(jsString(MainThreadRunner.evalJs(wv, "JSON.stringify(window[$k])")) ?: "{}")
                    val why = if (err.optBoolean("csp")) "被页面 CSP 拦截" else "fetch 失败（多为跨域 CORS 限制）：${err.optString("error")}"
                    throw PageFail(why, true)
                }
            }
            val metaJs = "(function(){var o=window[$k],c={};for(var p in o){if(p!=='bodyB64')c[p]=o[p];}c.b64len=(o.bodyB64||'').length;return JSON.stringify(c);})()"
            val meta = JSONObject(jsString(MainThreadRunner.evalJs(wv, metaJs)) ?: throw PageFail("取结果失败", true))
            if (meta.optBoolean("truncated")) throw PageFail("响应超过 page 通道上限 ${max / 1024} KiB", true)

            val finalUrl = meta.optString("url").ifBlank { url }
            val finalHost = try { URL(finalUrl).host.lowercase() } catch (e: Exception) { "" }
            if (!McpSessions.hostAllowed(finalHost, McpSessions.allowedDomains(ctx, s.profileName))) {
                throw ToolError("请求被重定向到未授权域名 $finalHost，已丢弃正文")
            }

            val b64len = meta.optInt("b64len")
            val sb = StringBuilder(b64len)
            var off = 0
            while (off < b64len) {
                val part = jsString(MainThreadRunner.evalJs(wv, "window[$k].bodyB64.substr($off,$CHUNK)"))
                    ?: throw PageFail("分块取回失败", true)
                if (part.isEmpty()) break
                sb.append(part)
                off += part.length
            }
            val bytes = if (sb.isEmpty()) ByteArray(0) else Base64.decode(sb.toString(), Base64.DEFAULT)
            val hs = meta.optJSONObject("headers") ?: JSONObject()
            val result = JSONObject()
                .put("channel", "page")
                .put("status", meta.optInt("status"))
                .put("statusText", meta.optString("statusText"))
                .put("url", finalUrl)
                .put("headers", hs)
                .put("size", meta.optInt("size"))
                .put("truncated", false)
            putBody(result, bytes, hs.optString("content-type"))
            return result
        } finally {
            try { MainThreadRunner.evalJs(wv, "delete window[$k];", 3_000) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ native 通道

    private fun viaNative(s: SessionInfo, startUrl: String, startMethod: String, extra: Map<String, String>): JSONObject {
        if (CookieHandler.getDefault() != null) throw ToolError("进程内存在全局 CookieHandler，为避免串号拒绝发送")
        val max = McpPrefs.maxBodyBytes(ctx)
        val (ua, pageUrl) = MainThreadRunner.run {
            val wv = TabRegistryHolder.get()?.webViewFor(s.id)
            Pair(wv?.settings?.userAgentString ?: defaultUa(s.isDesktop), wv?.url ?: s.url)
        }
        var referer: String? = pageUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        var url = URL(startUrl)
        var method = startMethod
        var hops = 0
        val wroteCookies = mutableListOf<String>()
        try {
            while (true) {
                McpSessions.requireAllowedUrl(ctx, s.profileName, url.toString())
                rejectLoopback(url.host)

                val cookie: String? = MainThreadRunner.run { ProfileCookies.managerFor(s.profileName).getCookie(url.toString()) }
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.instanceFollowRedirects = false
                    conn.useCaches = false
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.requestMethod = method
                    conn.setRequestProperty("User-Agent", ua)
                    conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    conn.setRequestProperty("Accept-Language", acceptLanguage())
                    referer?.let { conn.setRequestProperty("Referer", it) }
                    for ((k, v) in extra) conn.setRequestProperty(k, v)
                    if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)

                    val code = conn.responseCode
                    val setCookies = conn.headerFields.entries
                        .filter { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }
                        .flatMap { it.value ?: emptyList() }
                    if (setCookies.isNotEmpty()) {
                        val here = url.toString()
                        MainThreadRunner.run {
                            val cm = ProfileCookies.managerFor(s.profileName)
                            setCookies.forEach { cm.setCookie(here, it) }
                        }
                        wroteCookies += setCookies.map { it.substringBefore('=').trim() }
                    }

                    val location = conn.getHeaderField("Location")
                    if (code in 300..399 && code != 304 && location != null && hops < MAX_REDIRECTS) {
                        val next = try { URL(url, location) } catch (e: Exception) { null }
                        val nextOk = next != null && (next.protocol == "http" || next.protocol == "https") &&
                            McpSessions.hostAllowed(next.host, McpSessions.allowedDomains(ctx, s.profileName))
                        if (nextOk) {
                            if (code in 301..303 && method != "HEAD") method = "GET"
                            referer = url.toString()
                            url = next!!
                            hops++
                            continue
                        }
                        // 跳出授权域名：原样返回 3xx
                        return buildNative(conn, code, url, ByteArray(0), false, hops, wroteCookies)
                            .put("note", "重定向目标不在授权域名内，已停止跟随")
                    }

                    val (bytes, truncated) = if (method == "HEAD") Pair(ByteArray(0), false) else readLimited(
                        if (code >= 400) conn.errorStream else conn.inputStream, max
                    )
                    return buildNative(conn, code, url, bytes, truncated, hops, wroteCookies)
                } finally {
                    conn.disconnect()
                }
            }
        } finally {
            if (wroteCookies.isNotEmpty()) {
                try { MainThreadRunner.run { ProfileCookies.managerFor(s.profileName).flush() } } catch (_: Exception) {}
            }
        }
    }

    private fun buildNative(
        conn: HttpURLConnection, code: Int, url: URL, bytes: ByteArray, truncated: Boolean,
        hops: Int, wroteCookies: List<String>
    ): JSONObject {
        val hs = JSONObject()
        for ((k, v) in conn.headerFields) {
            if (k == null || v == null) continue
            hs.put(k.lowercase(), v.joinToString(", "))
        }
        val result = JSONObject()
            .put("channel", "native")
            .put("status", code)
            .put("statusText", conn.responseMessage ?: "")
            .put("url", url.toString())
            .put("redirects", hops)
            .put("headers", hs)
            .put("size", bytes.size)
            .put("truncated", truncated)
            .put("setCookieWritten", JSONArray(wroteCookies))
        putBody(result, bytes, conn.contentType)
        return result
    }

    private fun readLimited(stream: InputStream?, max: Int): Pair<ByteArray, Boolean> {
        if (stream == null) return Pair(ByteArray(0), false)
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                val room = max - out.size()
                if (n > room) {
                    out.write(buf, 0, room.coerceAtLeast(0))
                    return Pair(out.toByteArray(), true)
                }
                out.write(buf, 0, n)
            }
            return Pair(out.toByteArray(), false)
        }
    }

    /** 拒绝回环 / 未指定地址（含 IPv4 映射），防止借域名打回本机端口（ADR-0004 §4.3）。 */
    private fun rejectLoopback(host: String) {
        val addrs = try { InetAddress.getAllByName(host) } catch (e: Exception) { throw ToolError("无法解析域名 $host") }
        for (a in addrs) {
            val b = a.address
            val mappedBad = a is Inet6Address && b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } &&
                b[10].toInt() == -1 && b[11].toInt() == -1 &&
                (b[12].toInt() == 127 || (b[12].toInt() == 0 && b[13].toInt() == 0 && b[14].toInt() == 0 && b[15].toInt() == 0))
            if (a.isLoopbackAddress || a.isAnyLocalAddress || mappedBad) {
                throw ToolError("拒绝访问：$host 解析到本机回环地址")
            }
        }
    }

    // ------------------------------------------------------------------ 工具函数

    private fun putBody(result: JSONObject, bytes: ByteArray, contentType: String?) {
        val ct = contentType?.lowercase() ?: ""
        val charsetName = Regex("charset=([\\w.:-]+)").find(ct)?.groupValues?.get(1)
        val looksText = ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") ||
            ct.contains("javascript") || ct.contains("x-www-form-urlencoded")
        val charset = try { charsetName?.let { Charset.forName(it) } } catch (e: Exception) { null } ?: Charsets.UTF_8
        val text = if (looksText || ct.isEmpty()) decodeStrict(bytes, charset) else null
        if (text != null) {
            result.put("bodyEncoding", "text").put("body", text)
        } else {
            result.put("bodyEncoding", "base64").put("body", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    private fun decodeStrict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        if (cs == Charsets.UTF_8) null else String(bytes, cs)
    }

    /** evaluateJavascript 回调值是 JSON 字面量；字符串去掉外层引号，null / undefined 返回 null。 */
    private fun jsString(v: String?): String? {
        if (v == null || v == "null" || v == "undefined") return null
        return try {
            JSONArray("[$v]").let { if (it.isNull(0)) null else it.get(0).toString() }
        } catch (e: Exception) {
            null
        }
    }

    private fun defaultUa(desktop: Boolean): String {
        if (desktop) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }
        val raw = try { WebSettings.getDefaultUserAgent(ctx) } catch (e: Exception) { "Mozilla/5.0 (Linux; Android)" }
        return raw.replace("; wv", "").replace("Version/4.0 ", "")
    }

    private fun acceptLanguage(): String {
        val l = Locale.getDefault()
        val tag = l.toLanguageTag()
        return if (l.language.isNotEmpty() && tag != l.language) "$tag,${l.language};q=0.9,en;q=0.8" else "$tag,en;q=0.8"
    }

    private fun randomHex(n: Int): String {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
