package com.cookiebox.app.mcp.tools

import android.content.Context
import com.cookiebox.app.mcp.McpGrants
import com.cookiebox.app.mcp.McpTabs
import com.cookiebox.app.mcp.McpTool
import com.cookiebox.app.mcp.McpToolContext
import com.cookiebox.app.mcp.McpToolException
import com.cookiebox.app.mcp.McpToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * get_cookies：读该身份在已授权域名下的 Cookie（含 HttpOnly），只返回 {name, value}。
 * CookieManager.getCookie(url) 只给 name=value，取不到 domain / path / expires 等属性。
 */
class GetCookies(private val context: Context) : McpTool {

    override val name = "get_cookies"
    override val description =
        "Read the cookies (including HttpOnly) that the tab's profile would send to a URL on an authorized site. " +
            "Pass either url (exact, needed for path-scoped cookies) or domain (reads https://<domain>/). " +
            "Returns only name/value pairs; attributes such as path or expiry are not available."
    override val inputSchema: JSONObject = ToolSupport.schema(
        JSONObject()
            .put("session_id", ToolSupport.str("Tab id from list_sessions"))
            .put("url", ToolSupport.str("Full http(s) URL to read cookies for"))
            .put("domain", ToolSupport.str("Domain, used as https://<domain>/ when url is not given")),
        listOf("session_id")
    )
    override val annotations: JSONObject = JSONObject().put("readOnlyHint", true).put("openWorldHint", false)

    override fun call(arguments: JSONObject, context: McpToolContext): McpToolResult {
        val session = ToolSupport.resolveSession(this.context, ToolSupport.requireString(arguments, "session_id"))
        val url = ToolSupport.optString(arguments, "url")
            ?: ToolSupport.optString(arguments, "domain")?.let { d ->
                val host = McpGrants.parseManualDomain(d) ?: throw McpToolException("domain 格式不正确")
                "https://$host/"
            }
            ?: throw McpToolException("需要 url 或 domain")
        val host = McpGrants.hostOf(url) ?: throw McpToolException("url 必须是 http:// 或 https:// 网址")
        context.logLabel = session.customName.ifBlank { session.profileName }
        context.logDomain = host
        ToolSupport.requireAuthorized(this.context, session, host)

        val raw = McpTabs.onMain { access ->
            val cm = access.cookieManagerFor(session.profileName)
                ?: throw McpToolException("该身份已不存在（分身标签可能已关闭）")
            cm.getCookie(url)
        }
        val arr = JSONArray()
        raw?.split(";")?.forEach { pair ->
            val t = pair.trim()
            if (t.isEmpty()) return@forEach
            val i = t.indexOf('=')
            if (i < 0) arr.put(JSONObject().put("name", "").put("value", t))
            else arr.put(JSONObject().put("name", t.substring(0, i).trim()).put("value", t.substring(i + 1).trim()))
        }
        return McpToolResult.json(
            JSONObject()
                .put("url", url)
                .put("profileName", session.profileName)
                .put("count", arr.length())
                .put("cookies", arr)
        )
    }
}
