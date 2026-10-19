package com.cookiebox.app.mcp.tools

import android.content.Context
import com.cookiebox.app.mcp.McpTool
import com.cookiebox.app.mcp.McpToolContext
import com.cookiebox.app.mcp.McpToolException
import com.cookiebox.app.mcp.McpToolResult
import com.cookiebox.app.mcp.PageWaiter
import org.json.JSONObject

/** click：点击页面元素（写操作，按写确认档位处理） */
class PageClick(private val context: Context) : McpTool {

    companion object {
        private val SCRIPT = PageSupport.FIND_JS + """
(function(){
var el;
try{el=__cbFind(__S__,__I__);}catch(e){return JSON.stringify({ok:false,error:'invalid selector'});}
if(!el)return JSON.stringify({ok:false,error:'element not found'});
try{el.scrollIntoView({block:'center',inline:'center'});}catch(e){}
var r=el.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;
var o={bubbles:true,cancelable:true,composed:true,view:window,clientX:x,clientY:y,button:0,buttons:1,pointerId:1,pointerType:'touch',isPrimary:true};
function fire(T,n){try{el.dispatchEvent(new T(n,o));}catch(e){}}
if(typeof PointerEvent==='function')fire(PointerEvent,'pointerdown');
fire(MouseEvent,'mousedown');
try{if(typeof el.focus==='function')el.focus({preventScroll:true});}catch(e){}
o.buttons=0;
if(typeof PointerEvent==='function')fire(PointerEvent,'pointerup');
fire(MouseEvent,'mouseup');
if(typeof el.click==='function')el.click();else fire(MouseEvent,'click');
var tx=(el.innerText||el.value||el.getAttribute('aria-label')||'').replace(/\s+/g,' ').trim().slice(0,80);
return JSON.stringify({ok:true,tag:el.tagName.toLowerCase(),text:tx});
})()
"""
    }

    override val name = "click"
    override val description =
        "Click an element on the tab's current page (authorized domain only). selector is a CSS selector or text=... " +
            "(innermost visible element containing that text); index picks the n-th match (default 0). Scrolls it into " +
            "view and fires pointer/mouse events plus click(). This is a write operation: the user may be asked to confirm " +
            "on the phone (up to 60 s). If the click starts a navigation, waits for it to load (wait_for_navigation, default true)."
    override val inputSchema: JSONObject = ToolSupport.schema(
        JSONObject()
            .put("session_id", ToolSupport.str("Tab id from list_sessions"))
            .put("selector", ToolSupport.str("CSS selector, or text=Visible text"))
            .put("index", JSONObject().put("type", "integer").put("description", "Which match to use (default 0)"))
            .put("wait_for_navigation", JSONObject().put("type", "boolean").put("description", "Wait for a triggered navigation to finish (default true)"))
            .put("timeout_ms", JSONObject().put("type", "integer").put("description", "Max wait for navigation in ms (default 30000)")),
        listOf("session_id", "selector")
    )
    override val annotations: JSONObject = JSONObject()
        .put("readOnlyHint", false).put("destructiveHint", true).put("idempotentHint", false).put("openWorldHint", true)

    override fun call(arguments: JSONObject, context: McpToolContext): McpToolResult {
        val session = ToolSupport.resolveSession(this.context, ToolSupport.requireString(arguments, "session_id"))
        context.logLabel = PageSupport.label(session)
        val selector = ToolSupport.requireString(arguments, "selector")
        val index = arguments.optInt("index", 0).coerceAtLeast(0)
        val waitNav = arguments.optBoolean("wait_for_navigation", true)
        val timeout = PageSupport.timeoutArg(arguments)

        return PageSupport.withTabLock(session.id) {
            PageSupport.ensureLoaded(session, PageSupport.DEFAULT_TIMEOUT_MS)
            val page = PageSupport.currentPage(this.context, session, name, context)
            val idx = if (index > 0) " 第 ${index + 1} 个" else ""
            PageSupport.confirmWrite(this.context, name, page, "点击 ${PageSupport.summaryText(selector, 100)}$idx", context)
            // 确认期间页面可能已经变了：再核一次
            PageSupport.currentPage(this.context, session, name, context)
            val values = mapOf("__S__" to JSONObject.quote(selector), "__I__" to index.toString())
            val script = Regex("__(S|I)__").replace(SCRIPT) { m -> values[m.value] ?: m.value }
            val gen = PageWaiter.generation(session.id)
            val r = PageSupport.evalJson(session.id, script)
            if (!r.optBoolean("ok")) throw McpToolException("click：${r.optString("error", "failed")}")
            val after = PageSupport.settleAfterAction(session.id, gen, waitNav, timeout)
            r.put("navigated", after.optBoolean("navigated"))
            if (after.has("loadTimedOut")) r.put("loadTimedOut", after.optBoolean("loadTimedOut"))
            if (after.has("url")) r.put("url", after.optString("url"))
            McpToolResult.json(r)
        }
    }
}
