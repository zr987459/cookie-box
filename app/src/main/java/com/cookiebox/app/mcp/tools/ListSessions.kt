package com.cookiebox.app.mcp.tools

import android.content.Context
import com.cookiebox.app.mcp.McpSessions
import com.cookiebox.app.mcp.McpTool
import org.json.JSONArray
import org.json.JSONObject

/** list_sessions：列出用户授权给 AI 的标签。不返回 Cookie。 */
class ListSessions(private val ctx: Context) : McpTool {
    override val name = "list_sessions"
    override val description =
        "列出 CookieBox 里用户授权给你的浏览器标签。customName 是用户起的名字（用于区分账号），" +
            "profileName 相同的标签共用同一套登录状态；authorizedDomains 是你可以用该标签身份访问的域名（含子域）。不返回 Cookie。"
    override val inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

    override fun call(args: JSONObject): JSONObject {
        val arr = JSONArray()
        val cache = HashMap<String, Set<String>>()
        for (s in McpSessions.snapshot(ctx)) {
            val domains = cache.getOrPut(s.profileName) { McpSessions.allowedDomains(ctx, s.profileName) }
            if (domains.isEmpty()) continue
            val hostOk = s.host.isNotEmpty() && McpSessions.hostAllowed(s.host, domains)
            arr.put(JSONObject()
                .put("id", s.id)
                .put("customName", s.customName)
                .put("title", if (hostOk) s.title else "")
                .put("host", if (hostOk) s.host else "")
                .put("url", if (hostOk) s.url else "")
                .put("isClone", s.isClone)
                .put("profileName", s.profileName)
                .put("profileLabel", McpSessions.profileLabel(s))
                .put("loaded", s.live)
                .put("authorizedDomains", JSONArray(domains.toList())))
        }
        return JSONObject().put("sessions", arr).put("count", arr.length())
            .put("note", if (arr.length() == 0) "没有已授权的标签：请让用户在 CookieBox 菜单「AI 访问（MCP）→ 站点授权」里勾选" else "")
    }
}
