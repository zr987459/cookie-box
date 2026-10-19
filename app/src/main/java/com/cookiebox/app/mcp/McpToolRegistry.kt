package com.cookiebox.app.mcp

import android.content.Context

/**
 * 已注册的 MCP 工具。T-026 只有握手，列表为空；
 * T-027 加入 list_sessions / get_cookies / http_request，T-029 加入页面工具。
 */
object McpToolRegistry {
    @Suppress("UNUSED_PARAMETER")
    fun tools(context: Context): List<McpTool> = emptyList()
}
