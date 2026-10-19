package com.cookiebox.app.mcp.tools

import android.content.Context
import com.cookiebox.app.mcp.McpTool
import com.cookiebox.app.mcp.McpToolContext
import com.cookiebox.app.mcp.McpToolException
import com.cookiebox.app.mcp.McpToolResult
import com.cookiebox.app.mcp.PageWaiter
import org.json.JSONObject

/** fill：填写输入框 / 文本域 / 下拉框 / 可编辑区域（写操作） */
class PageFill(private val context: Context) : McpTool {

    companion object {
        private const val MAX_TEXT = 100_000

        private val SCRIPT = PageSupport.FIND_JS + """
(function(){
var el;
try{el=__cbFind(__S__,__I__);}catch(e){return JSON.stringify({ok:false,error:'invalid selector'});}
if(!el)return JSON.stringify({ok:false,error:'element not found'});
if(el.tagName==='LABEL'&&el.control)el=el.control;
var v=__T__,sub=__SUB__,tag=el.tagName.toLowerCase();
try{el.scrollIntoView({block:'center'});}catch(e){}
try{el.focus({preventScroll:true});}catch(e){}
function ev(n){try{el.dispatchEvent(new Event(n,{bubbles:true}));}catch(e){}}
if(tag==='input'||tag==='textarea'){
var ty=(el.type||'').toLowerCase();
if(ty==='checkbox'||ty==='radio'){var want=/^(1|true|on|yes|checked)$/i.test(v);if(el.checked!==want)el.click();}
else{var proto=tag==='input'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;
var d=Object.getOwnPropertyDescriptor(proto,'value');
if(d&&d.set)d.set.call(el,v);else el.value=v;
try{el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:v}));}catch(e){ev('input');}
ev('change');}
}else if(tag==='select'){
var ok=false;for(var i=0;i<el.options.length;i++){var op=el.options[i];if(op.value===v||op.text.trim()===v){el.selectedIndex=i;ok=true;break;}}
if(!ok)return JSON.stringify({ok:false,error:'option not found'});
ev('input');ev('change');
}else if(el.isContentEditable){
try{var rg=document.createRange();rg.selectNodeContents(el);var se=window.getSelection();se.removeAllRanges();se.addRange(rg);}catch(e){}
var done=false;try{done=document.execCommand('insertText',false,v);}catch(e){}
if(!done){el.textContent=v;ev('input');}
}else{return JSON.stringify({ok:false,error:'element is not fillable ('+tag+')'});}
if(sub){
var f=el.form||(el.closest&&el.closest('form'));
var kd={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
var go=true;try{go=el.dispatchEvent(new KeyboardEvent('keydown',kd));el.dispatchEvent(new KeyboardEvent('keypress',kd));el.dispatchEvent(new KeyboardEvent('keyup',kd));}catch(e){}
if(f&&go){if(typeof f.requestSubmit==='function')f.requestSubmit();else f.submit();}
}
return JSON.stringify({ok:true,tag:tag,length:v.length});
})()
"""
    }

    override val name = "fill"
    override val description =
        "Fill a form field on the tab's current page (authorized domain only): input / textarea (works with React/Vue " +
            "controlled inputs), checkbox / radio (text 'true' or 'false'), select (option value or visible text) or a " +
            "contenteditable element. selector is CSS or text=...; index picks the n-th match. submit=true then presses Enter " +
            "and submits the enclosing form. This is a write operation: the user may be asked to confirm on the phone (up to 60 s)."
    override val inputSchema: JSONObject = ToolSupport.schema(
        JSONObject()
            .put("session_id", ToolSupport.str("Tab id from list_sessions"))
            .put("selector", ToolSupport.str("CSS selector, or text=... (matches label / placeholder / aria-label text too)"))
            .put("text", ToolSupport.str("Value to fill"))
            .put("index", JSONObject().put("type", "integer").put("description", "Which match to use (default 0)"))
            .put("submit", JSONObject().put("type", "boolean").put("description", "Press Enter and submit the form afterwards (default false)"))
            .put("timeout_ms", JSONObject().put("type", "integer").put("description", "Max wait for a navigation after submit (default 30000)")),
        listOf("session_id", "selector", "text")
    )
    override val annotations: JSONObject = JSONObject()
        .put("readOnlyHint", false).put("destructiveHint", true).put("idempotentHint", true).put("openWorldHint", true)

    override fun call(arguments: JSONObject, context: McpToolContext): McpToolResult {
        val session = ToolSupport.resolveSession(this.context, ToolSupport.requireString(arguments, "session_id"))
        context.logLabel = PageSupport.label(session)
        val selector = ToolSupport.requireString(arguments, "selector")
        val raw = arguments.opt("text")
        if (raw == null || raw == JSONObject.NULL) throw McpToolException("缺少参数 text")
        val text = raw.toString()
        if (text.length > MAX_TEXT) throw McpToolException("text 太长（上限 $MAX_TEXT 字符）")
        val index = arguments.optInt("index", 0).coerceAtLeast(0)
        val submit = arguments.optBoolean("submit", false)
        val timeout = PageSupport.timeoutArg(arguments)

        return PageSupport.withTabLock(session.id) {
            PageSupport.ensureLoaded(session, PageSupport.DEFAULT_TIMEOUT_MS)
            val page = PageSupport.currentPage(this.context, session, name, context)
            // 摘要不含填写内容（可能是密码），只给长度
            val idx = if (index > 0) " 第 ${index + 1} 个" else ""
            val sub = if (submit) "，并提交" else ""
            PageSupport.confirmWrite(
                this.context, name, page,
                "填写 ${PageSupport.summaryText(selector, 100)}$idx（${text.length} 个字符）$sub", context
            )
            PageSupport.currentPage(this.context, session, name, context)
            val values = mapOf(
                "__S__" to JSONObject.quote(selector),
                "__I__" to index.toString(),
                "__T__" to JSONObject.quote(text),
                "__SUB__" to submit.toString()
            )
            val script = Regex("__(S|I|T|SUB)__").replace(SCRIPT) { m -> values[m.value] ?: m.value }
            val gen = PageWaiter.generation(session.id)
            val r = PageSupport.evalJson(session.id, script)
            if (!r.optBoolean("ok")) throw McpToolException("fill：${r.optString("error", "failed")}")
            val after = PageSupport.settleAfterAction(session.id, gen, true, timeout)
            r.put("navigated", after.optBoolean("navigated"))
            if (after.has("loadTimedOut")) r.put("loadTimedOut", after.optBoolean("loadTimedOut"))
            if (after.has("url")) r.put("url", after.optString("url"))
            McpToolResult.json(r)
        }
    }
}
