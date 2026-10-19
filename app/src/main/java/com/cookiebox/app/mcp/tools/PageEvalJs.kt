package com.cookiebox.app.mcp.tools

import android.content.Context
import com.cookiebox.app.mcp.McpPrefs
import com.cookiebox.app.mcp.McpTool
import com.cookiebox.app.mcp.McpToolContext
import com.cookiebox.app.mcp.McpToolException
import com.cookiebox.app.mcp.McpToolResult
import org.json.JSONObject

/**
 * eval_js：在标签页面里执行脚本（写操作）。
 *
 * 不使用页面内 eval（受 CSP unsafe-eval 限制），而是把代码直接拼进 evaluateJavascript 的脚本：
 * - 代码含 return：作为 async 函数体执行（可用 await），取返回值；
 * - 否则先当作表达式 `return (code)`；若整体语法错误（代码是多条语句），退回直接执行，取最后一个表达式的值（不等待 Promise）。
 * 结果用随机 window 变量 + 轮询 + 分块取回（ADR-0004 §5.3 / §5.5），不新增 addJavascriptInterface。
 */
class PageEvalJs(private val context: Context) : McpTool {

    companion object {
        private const val MAX_CODE = 200_000
        private val RETURN_RE = Regex("\\breturn\\b")

        private val WRAPPER = """
(function(){
var k=__K__,lim=__LIM__;
window[k]={state:'pending'};
function ser(v){
if(v===undefined)return{type:'undefined',value:''};
var t=typeof v;
if(t==='function')return{type:'function',value:String(v).slice(0,500)};
try{if(typeof Element!=='undefined'&&v instanceof Element)return{type:'element',value:(v.outerHTML||'').slice(0,5000)};}catch(e){}
var s;try{s=JSON.stringify(v);}catch(e){s=undefined;}
if(s===undefined)s=String(v);
return{type:v===null?'null':(Array.isArray(v)?'array':t),value:s};}
function fin(o){if(o.value&&o.value.length>lim){o.value=o.value.slice(0,lim);o.truncated=true;}window[k]=o;}
Promise.resolve().then(function(){return (async function(){
__CODE__
})();}).then(function(v){var o=ser(v);o.state='done';fin(o);},function(e){fin({state:'error',value:String((e&&(e.stack||e.message))||e).slice(0,4000)});});
return 'ok';
})()
"""
    }

    override val name = "eval_js"
    override val description =
        "Run JavaScript in the tab's current page (authorized domain only) and return the result as JSON text. " +
            "If the code contains `return`, it runs as the body of an async function (await allowed); otherwise a single " +
            "expression is evaluated and awaited if it is a Promise, and multi-statement code returns the value of its last " +
            "statement (Promises not awaited). Not subject to the page's CSP eval restrictions. Results are truncated at the " +
            "page body limit. This is a write operation: the user may be asked to confirm on the phone (up to 60 s)."
    override val inputSchema: JSONObject = ToolSupport.schema(
        JSONObject()
            .put("session_id", ToolSupport.str("Tab id from list_sessions"))
            .put("code", ToolSupport.str("JavaScript code"))
            .put("timeout_ms", JSONObject().put("type", "integer").put("description", "Max time in ms (default 30000, max 120000)")),
        listOf("session_id", "code")
    )
    override val annotations: JSONObject = JSONObject()
        .put("readOnlyHint", false).put("destructiveHint", true).put("idempotentHint", false).put("openWorldHint", true)

    override fun call(arguments: JSONObject, context: McpToolContext): McpToolResult {
        val session = ToolSupport.resolveSession(this.context, ToolSupport.requireString(arguments, "session_id"))
        context.logLabel = PageSupport.label(session)
        val code = arguments.opt("code")?.takeIf { it is String && it.isNotBlank() } as String?
            ?: throw McpToolException("缺少参数 code")
        if (code.length > MAX_CODE) throw McpToolException("code 太长（上限 $MAX_CODE 字符）")
        val timeout = PageSupport.timeoutArg(arguments)
        val limit = McpPrefs(this.context).pageMaxBodyBytes

        return PageSupport.withTabLock(session.id) {
            PageSupport.ensureLoaded(session, PageSupport.DEFAULT_TIMEOUT_MS)
            val page = PageSupport.currentPage(this.context, session, name, context)
            PageSupport.confirmWrite(
                this.context, name, page,
                "执行脚本（${code.length} 个字符）：${PageSupport.summaryText(code, 160)}", context
            )
            val now = PageSupport.currentPage(this.context, session, name, context)

            val hasReturn = RETURN_RE.containsMatchIn(code)
            val body = if (hasReturn) code else "return (\n" + code.trim().trimEnd(';', ' ', '\n', '\t', '\r') + "\n);"
            val key = PageSupport.randomKey()
            val values = mapOf("__K__" to JSONObject.quote(key), "__LIM__" to limit.toString(), "__CODE__" to body)
            val script = Regex("__(K|LIM|CODE)__").replace(WRAPPER) { m -> values[m.value] ?: m.value }

            val res = PageSupport.runAsync(session.id, key, script, timeout)
            val out = JSONObject()
            if (res != null) {
                if (res.optString("state") == "error") {
                    throw McpToolException("脚本出错：${res.optString("value").take(2000)}")
                }
                out.put("type", res.optString("type"))
                    .put("value", res.optString("value"))
                    .put("awaited", true)
                if (res.optBoolean("truncated")) out.put("truncated", true)
            } else if (hasReturn) {
                throw McpToolException("脚本语法错误（代码作为 async 函数体解析失败）")
            } else {
                // 多条语句：直接执行，取最后一个表达式的值（evaluateJavascript 的 JSON 序列化）
                val raw = PageSupport.eval(session.id, code, timeout.coerceAtMost(30_000L))
                val v = raw ?: "null"
                out.put("type", "sync").put("awaited", false)
                if (v.length > limit) out.put("value", v.take(limit)).put("truncated", true) else out.put("value", v)
                if (v == "{}") out.put("hint", "an object that is not JSON-serializable or a Promise returns {}; use `return await ...` to await it")
            }
            out.put("url", now.url).put("note", PageSupport.UNTRUSTED_NOTE)
            McpToolResult.json(out)
        }
    }
}
