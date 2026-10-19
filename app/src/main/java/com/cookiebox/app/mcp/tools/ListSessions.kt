package com.cookiebox.app.mcp.tools

import android.content.Context
import com.cookiebox.app.mcp.McpGrants
import com.cookiebox.app.mcp.McpTabs
import com.cookiebox.app.mcp.McpTool
import com.cookiebox.app.mcp.McpToolContext
import com.cookiebox.app.mcp.McpToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * list_sessions：列出已授权身份下的标签。不返回 Cookie。
 * 标签当前所在站点未授权时，不返回它的标题与网址（只告诉 AI 这个标签属于哪个身份）。
 */
class ListSessions(private val context: Context) : McpTool {

    override val name = "list_sessions"
    override val description =
        "List the user's CookieBox browser tabs whose identity (profile) has authorized sites. " +
            "Each tab belongs to a profile: all normal tabs share Profile_Default; each clone tab has its own profile " +
            "(a separate logged-in account). customName is the user's label for telling accounts apart. " +
            "authorizedDomains lists the sites you may use with get_cookies / http_request for that tab " +
            "(subdomains included). Session ids change when the app restarts. Never returns cookies."
    override val inputSchema: JSONObject = ToolSupport.schema(JSONObject())
    override val annotations: JSONObject = JSONObject().put("readOnlyHint", true).put("openWorldHint", false)

    override fun call(arguments: JSONObject, context: McpToolContext): McpToolResult {
        val snapshot = McpTabs.snapshot()
        val arr = JSONArray()
        val domainsCache = HashMap<String, List<String>>()
        for (s in snapshot) {
            val domains = domainsCache.getOrPut(s.profileName) { McpGrants.authorizedDomains(this.context, s.profileName) }
            if (domains.isEmpty()) continue
            val hostOk = s.host.isNotEmpty() && domains.any { McpGrants.hostMatches(s.host, it) }
            val o = JSONObject()
                .put("id", s.id)
                .put("customName", s.customName)
                .put("profileName", s.profileName)
                .put("isClone", s.isClone)
                .put("isDesktop", s.isDesktop)
                .put("authorizedDomains", JSONArray(domains))
            if (hostOk) {
                o.put("title", s.title)
                o.put("url", s.url)
                o.put("host", s.host)
                o.put("pageLoaded", s.loaded)
            } else {
                o.put("title", JSONObject.NULL)
                o.put("host", JSONObject.NULL)
                o.put("note", if (s.host.isEmpty()) "blank tab" else "current site not authorized")
            }
            arr.put(o)
        }
        val result = JSONObject().put("sessions", arr)
        if (snapshot.isNotEmpty() && snapshot.first().fromSavedSession) {
            result.put("note", "CookieBox UI is not running; tabs come from the saved session, pages are not loaded, http_request will use the native channel.")
        }
        if (arr.length() == 0) {
            result.put("hint", "No authorized sessions. Ask the user to authorize sites in CookieBox → AI 访问（MCP）→ 站点授权.")
        }
        return McpToolResult.json(result)
    }
}
