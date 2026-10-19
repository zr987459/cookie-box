package com.cookiebox.app.mcp

import android.content.Context
import com.cookiebox.app.mcp.tools.GetCookies
import com.cookiebox.app.mcp.tools.HttpRequest
import com.cookiebox.app.mcp.tools.ListSessions

/**
 * 已注册的 MCP 工具。T-027：list_sessions / get_cookies / http_request（GET、HEAD）；
 * T-028 接入写确认后开放非 GET；T-029 加入页面工具。
 */
object McpToolRegistry {
    fun tools(context: Context): List<McpTool> {
        val app = context.applicationContext
        return listOf(ListSessions(app), GetCookies(app), HttpRequest(app))
    }
}
