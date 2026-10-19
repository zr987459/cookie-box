package com.cookiebox.app.mcp.tools

import android.content.Context
import com.cookiebox.app.mcp.McpGrants
import com.cookiebox.app.mcp.McpTool
import com.cookiebox.app.mcp.McpToolContext
import com.cookiebox.app.mcp.McpToolException
import com.cookiebox.app.mcp.McpToolResult
import com.cookiebox.app.mcp.PageWaiter
import org.json.JSONObject

/** open_url：在指定标签打开授权站点的网址并等待加载完成（读操作） */
class PageOpenUrl(private val context: Context) : McpTool {

    override val name = "open_url"
    override val description =
        "Navigate an existing tab (session_id from list_sessions) to a URL on an authorized domain, using that tab's " +
            "logged-in identity, and wait until the page has finished loading (timeout_ms, default 30000). Returns the final " +
            "url, title and whether the load timed out. Use get_page_text / screenshot afterwards to read the page."
    override val inputSchema: JSONObject = ToolSupport.schema(
        JSONObject()
            .put("session_id", ToolSupport.str("Tab id from list_sessions"))
            .put("url", ToolSupport.str("Absolute http(s) URL on an authorized domain"))
            .put("wait", JSONObject().put("type", "boolean").put("description", "Wait for the load to finish (default true)"))
            .put("timeout_ms", JSONObject().put("type", "integer").put("description", "Max wait in ms (1000–120000, default 30000)")),
        listOf("session_id", "url")
    )
    override val annotations: JSONObject = JSONObject().put("readOnlyHint", true).put("openWorldHint", true)

    override fun call(arguments: JSONObject, context: McpToolContext): McpToolResult {
        val session = ToolSupport.resolveSession(this.context, ToolSupport.requireString(arguments, "session_id"))
        context.logLabel = PageSupport.label(session)
        val url = ToolSupport.requireString(arguments, "url")
        val host = McpGrants.hostOf(url) ?: throw McpToolException("url 必须是 http:// 或 https:// 网址")
        context.logDomain = host
        ToolSupport.requireAuthorized(this.context, session, host)
        val wait = arguments.optBoolean("wait", true)
        val timeout = PageSupport.timeoutArg(arguments)

        return PageSupport.withTabLock(session.id) {
            PageSupport.ensureLoaded(session, timeout)
            val gen = PageWaiter.generation(session.id)
            if (!com.cookiebox.app.mcp.McpTabs.navigate(session.id, url)) {
                throw McpToolException("无法在该标签打开网址（曲奇盒界面未运行或标签已关闭）")
            }
            val out = JSONObject().put("requestedUrl", url)
            if (wait) {
                val r = PageSupport.waitLoad(session.id, gen, timeout)
                out.put("loadTimedOut", r.timedOut).put("elapsedMs", r.elapsedMs)
                val st = r.status
                if (st != null) {
                    val finalHost = McpGrants.hostOf(st.url)
                    out.put("url", st.url)
                    if (finalHost != null && McpGrants.isAuthorized(this.context, session.profileName, finalHost)) {
                        out.put("title", st.title)
                    } else {
                        out.put("warning", "page ended on a domain that is not authorized for this identity ($finalHost); page tools will refuse it")
                    }
                }
            } else {
                out.put("loading", true)
            }
            McpToolResult.json(out)
        }
    }
}
