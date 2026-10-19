package com.cookiebox.app.mcp

import android.content.Context
import com.cookiebox.app.mcp.tools.GetCookies
import com.cookiebox.app.mcp.tools.HttpRequest
import com.cookiebox.app.mcp.tools.ListSessions
import com.cookiebox.app.mcp.tools.PageClick
import com.cookiebox.app.mcp.tools.PageEvalJs
import com.cookiebox.app.mcp.tools.PageFill
import com.cookiebox.app.mcp.tools.PageOpenUrl
import com.cookiebox.app.mcp.tools.PageScreenshot
import com.cookiebox.app.mcp.tools.PageText

/**
 * 已注册的 MCP 工具。T-027：list_sessions / get_cookies / http_request；T-028：写确认；
 * T-029：页面工具 open_url / get_page_text / screenshot（读）与 click / fill / eval_js（写）。
 */
object McpToolRegistry {
    fun tools(context: Context): List<McpTool> {
        val app = context.applicationContext
        return listOf(
            ListSessions(app), GetCookies(app), HttpRequest(app),
            PageOpenUrl(app), PageText(app), PageScreenshot(app), PageClick(app), PageFill(app), PageEvalJs(app)
        )
    }
}
