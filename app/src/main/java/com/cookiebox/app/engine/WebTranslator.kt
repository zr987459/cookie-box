package com.cookiebox.app.engine

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 网页翻译
 *
 * 旧方案是向网页注入 translate.google.com/translate_a/element.js 小部件，
 * 但大部分网站的 CSP 会禁止加载外部脚本，谷歌也已停止维护该小部件，所以经常"点了没反应"。
 *
 * 新方案：
 *  1. 用 evaluateJavascript 读出网页里的可见文字节点（不受网页 CSP 限制）；
 *  2. 由 App 原生网络请求去调用翻译接口（谷歌失败时自动切换微软翻译）；
 *  3. 再把译文写回对应的文字节点，原地翻译、不刷新、不丢登录态。
 */
object WebTranslator {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_CHARS_PER_BATCH = 1800
    private const val MAX_ITEMS_PER_BATCH = 60

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var msToken: String? = null
    @Volatile
    private var msTokenTime = 0L

    /** 收集文字节点：返回 [[id, text], ...]，节点对象挂在 window.__cbTr 上 */
    private const val COLLECT_JS = """
(function(){
  var st = window.__cbTr || (window.__cbTr = {nodes: [], orig: [], done: new WeakSet()});
  var skip = {SCRIPT:1, STYLE:1, NOSCRIPT:1, TEXTAREA:1, INPUT:1, CODE:1, PRE:1, SVG:1, MATH:1, IFRAME:1, CANVAS:1, SELECT:1, OPTION:1};
  var out = [];
  var root = document.body || document.documentElement;
  if (!root) return JSON.stringify(out);
  var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: function(n){
      var t = n.nodeValue;
      if (!t || !t.trim() || t.trim().length < 2) return NodeFilter.FILTER_REJECT;
      if (st.done.has(n)) return NodeFilter.FILTER_REJECT;
      if (/^[\s\d\p{P}\p{S}]+$/u.test(t)) return NodeFilter.FILTER_REJECT;
      var p = n.parentElement;
      while (p) {
        if (skip[p.tagName] || p.isContentEditable || p.getAttribute('translate') === 'no' || p.classList.contains('notranslate')) return NodeFilter.FILTER_REJECT;
        p = p.parentElement;
      }
      return NodeFilter.FILTER_ACCEPT;
    }
  });
  var n, c = 0;
  while ((n = w.nextNode()) && c < 2500) {
    var id = st.nodes.length;
    st.nodes.push(n); st.orig.push(n.nodeValue); st.done.add(n);
    out.push([id, n.nodeValue.trim()]);
    c++;
  }
  if (document.title && !st.titleOrig) { st.titleOrig = document.title; out.push([-1, document.title]); }
  return JSON.stringify(out);
})();
"""

    /**
     * 原地翻译当前网页。可重复调用：已翻译过的节点会跳过，只翻译新加载出来的内容。
     * @param onResult 主线程回调，返回给用户看的提示
     */
    fun translateInPage(
        webView: WebView,
        targetLanguage: String = "zh-CN",
        onResult: ((String) -> Unit)? = null
    ) {
        webView.evaluateJavascript(COLLECT_JS) { raw ->
            val items = parseCollected(raw)
            if (items.isEmpty()) {
                onResult?.invoke("没有找到需要翻译的新内容")
                return@evaluateJavascript
            }
            Thread {
                var okCount = 0
                var error: String? = null
                for (batch in batches(items)) {
                    try {
                        val translated = translateTexts(batch.map { it.second }, targetLanguage)
                        val apply = JSONArray()
                        batch.forEachIndexed { i, (id, _) ->
                            val t = translated.getOrNull(i)
                            if (!t.isNullOrBlank()) apply.put(JSONArray().put(id).put(t))
                        }
                        okCount += apply.length()
                        val js = applyJs(apply)
                        main.post { webView.evaluateJavascript(js, null) }
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                        break
                    }
                }
                val msg = when {
                    okCount > 0 && error == null -> "翻译完成"
                    okCount > 0 -> "部分内容已翻译（网络中断：$error）"
                    else -> "翻译失败：$error，可在「网页工具」里改用翻译阅读器"
                }
                main.post { onResult?.invoke(msg) }
            }.start()
        }
    }

    /** 还原为原文（不刷新页面） */
    fun restoreInPage(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function(){
              var st = window.__cbTr; if (!st) return;
              for (var i = 0; i < st.nodes.length; i++) { try { st.nodes[i].nodeValue = st.orig[i]; } catch(e){} }
              if (st.titleOrig) document.title = st.titleOrig;
              window.__cbTr = null;
            })();
            """.trimIndent(), null
        )
    }

    /** 方式二：谷歌翻译阅读器（整页代理），作为兜底 */
    fun getTranslateProxyUrl(url: String, targetLanguage: String = "zh-CN"): String {
        return try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            "https://translate.google.com/translate?sl=auto&tl=$targetLanguage&hl=$targetLanguage&u=$encoded"
        } catch (e: Exception) {
            url
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private fun applyJs(arr: JSONArray): String {
        // 保留原节点首尾空白，避免单词粘连
        return """
            (function(list){
              var st = window.__cbTr; if (!st) return;
              list.forEach(function(p){
                if (p[0] === -1) { document.title = p[1]; return; }
                var n = st.nodes[p[0]]; if (!n) return;
                var o = st.orig[p[0]];
                var lead = o.match(/^\s*/)[0], tail = o.match(/\s*$/)[0];
                n.nodeValue = lead + p[1] + tail;
              });
            })($arr);
        """.trimIndent()
    }

    private fun parseCollected(raw: String?): List<Pair<Int, String>> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        return try {
            // evaluateJavascript 返回的是 JSON 编码后的字符串，需要先解一层
            val json = JSONArray("[$raw]").getString(0)
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val p = arr.getJSONArray(it)
                Pair(p.getInt(0), p.getString(1))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun batches(items: List<Pair<Int, String>>): List<List<Pair<Int, String>>> {
        val result = mutableListOf<List<Pair<Int, String>>>()
        var cur = mutableListOf<Pair<Int, String>>()
        var len = 0
        for (it in items) {
            val text = if (it.second.length > MAX_CHARS_PER_BATCH) it.second.take(MAX_CHARS_PER_BATCH) else it.second
            if (cur.isNotEmpty() && (len + text.length > MAX_CHARS_PER_BATCH || cur.size >= MAX_ITEMS_PER_BATCH)) {
                result.add(cur); cur = mutableListOf(); len = 0
            }
            cur.add(Pair(it.first, text)); len += text.length
        }
        if (cur.isNotEmpty()) result.add(cur)
        return result
    }

    /** 先谷歌，失败再微软 */
    private fun translateTexts(texts: List<String>, target: String): List<String?> {
        return try {
            googleTranslate(texts, target)
        } catch (g: Exception) {
            try {
                microsoftTranslate(texts, target)
            } catch (m: Exception) {
                throw Exception("谷歌(${g.message}) / 微软(${m.message}) 均不可用")
            }
        }
    }

    private fun googleTranslate(texts: List<String>, target: String): List<String?> {
        val body = texts.joinToString("&") { "q=" + URLEncoder.encode(it, "UTF-8") }
        val resp = http(
            "https://translate.googleapis.com/translate_a/t?client=gtx&sl=auto&tl=$target&dt=t&format=text",
            "POST", body, "application/x-www-form-urlencoded;charset=UTF-8"
        )
        val arr = JSONArray(resp)
        val out = (0 until arr.length()).map { i ->
            when (val v = arr.get(i)) {
                is String -> v
                is JSONArray -> v.optString(0)
                else -> null
            }
        }
        // 只有一条时接口可能返回 ["译文","en"]，要特别处理
        if (texts.size == 1 && out.size == 2 && arr.opt(0) is String) return listOf(out[0])
        if (out.size != texts.size) throw Exception("返回数量不符")
        return out
    }

    private fun microsoftTranslate(texts: List<String>, target: String): List<String?> {
        val token = getMsToken()
        val to = when (target) {
            "zh-CN", "zh" -> "zh-Hans"
            "zh-TW" -> "zh-Hant"
            else -> target
        }
        val payload = JSONArray().apply { texts.forEach { put(JSONObject().put("Text", it)) } }
        val resp = http(
            "https://api-edge.cognitive.microsofttranslator.com/translate?api-version=3.0&to=$to",
            "POST", payload.toString(), "application/json", "Bearer $token"
        )
        val arr = JSONArray(resp)
        return (0 until arr.length()).map {
            arr.getJSONObject(it).optJSONArray("translations")?.optJSONObject(0)?.optString("text")
        }
    }

    private fun getMsToken(): String {
        val now = System.currentTimeMillis()
        msToken?.let { if (now - msTokenTime < 8 * 60 * 1000) return it }
        val t = http("https://edge.microsoft.com/translate/auth", "GET").trim()
        if (t.isEmpty() || t.startsWith("<")) throw Exception("token")
        msToken = t
        msTokenTime = now
        return t
    }

    private fun http(
        url: String,
        method: String,
        body: String? = null,
        contentType: String? = null,
        auth: String? = null
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", UA)
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            if (auth != null) conn.setRequestProperty("Authorization", auth)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
