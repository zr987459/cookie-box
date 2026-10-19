package com.cookiebox.app.mcp.tools

import android.content.Context
import com.cookiebox.app.mcp.MainThreadRunner
import com.cookiebox.app.mcp.McpPrefs
import com.cookiebox.app.mcp.McpSessions
import com.cookiebox.app.mcp.McpTool
import com.cookiebox.app.mcp.ProfileCookies
import com.cookiebox.app.mcp.ToolError
import org.json.JSONArray
import org.json.JSONObject

/**
 * get_cookies：返回该身份在某网址可见的 Cookie（含 HttpOnly），只有 name / value。
 * Android CookieManager.getCookie(url) 只给 "name=value; …"，拿不到 domain/path/expires 等属性（ADR-0004 §4.3）。
 */
class GetCookies(private val ctx: Context) : McpTool {
    override val name = "get_cookies"
    override val description =
        "读取某个标签身份在指定网址下的 Cookie（含 HttpOnly），只返回 name 和 value。" +
            "传 domain 时按 https://<domain>/ 读取；路径受限的 Cookie 请传具体 url。目标必须属于该标签的已授权域名。"
    override val inputSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("session_id", JSONObject().put("type", "string").put("description", "list_sessions 返回的标签 id"))
            .put("domain", JSONObject().put("type", "string").put("description", "域名，如 bilibili.com"))
            .put("url", JSONObject().put("type", "string").put("description", "完整网址，优先于 domain")))
        .put("required", JSONArray().put("session_id"))

    override fun call(args: JSONObject): JSONObject {
        val sid = args.optString("session_id").ifBlank { throw ToolError("缺少 session_id") }
        val s = McpSessions.find(ctx, sid)
        val url = args.optString("url").trim().ifBlank {
            val d = McpPrefs.normalizeDomainInput(args.optString("domain")) ?: throw ToolError("需要 domain 或 url")
            "https://$d/"
        }
        McpSessions.requireAllowedUrl(ctx, s.profileName, url)
        val raw = MainThreadRunner.run { ProfileCookies.managerFor(s.profileName).getCookie(url) } ?: ""
        val arr = JSONArray()
        raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val idx = part.indexOf('=')
            if (idx < 0) arr.put(JSONObject().put("name", part).put("value", ""))
            else arr.put(JSONObject().put("name", part.substring(0, idx)).put("value", part.substring(idx + 1)))
        }
        return JSONObject()
            .put("session_id", sid)
            .put("profileName", s.profileName)
            .put("url", url)
            .put("count", arr.length())
            .put("cookies", arr)
            .put("cookieHeader", raw)
    }
}
