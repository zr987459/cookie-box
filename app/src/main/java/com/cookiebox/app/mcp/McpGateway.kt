package com.cookiebox.app.mcp

/**
 * HTTP 请求进入 MCP 协议前的准入链（ADR-0004 §5.3）：
 * 路径 → Host（防 DNS rebinding）→ Origin（无则放行，有则查白名单）→ CORS 预检 → 失败冷却 → Bearer Token。
 */
class McpGateway(
    private val auth: McpAuth,
    private val protocol: McpProtocol
) {

    companion object {
        const val PATH = "/mcp"
        private const val ALLOW_METHODS = "POST, GET, DELETE, OPTIONS"
        private const val ALLOW_HEADERS =
            "Authorization, Content-Type, Accept, Mcp-Session-Id, MCP-Protocol-Version, Last-Event-ID"
    }

    fun handle(req: McpHttpServer.Request): McpHttpServer.Response {
        if (req.path != PATH && req.path != "$PATH/") return McpHttpServer.Response(404)
        if (!auth.hostAllowed(req.header("host"))) return McpHttpServer.Response(403)

        val origin = req.header("origin")
        val extra = when (auth.checkOrigin(origin)) {
            McpAuth.OriginResult.DENIED -> return McpHttpServer.Response(403)
            McpAuth.OriginResult.ALLOWED -> mapOf(
                "Access-Control-Allow-Origin" to origin!!.trim(),
                "Access-Control-Expose-Headers" to "Mcp-Session-Id",
                "Vary" to "Origin"
            )
            McpAuth.OriginResult.NONE -> emptyMap()
        }

        if (req.method == "OPTIONS") {
            return McpHttpServer.Response(
                204,
                extra + mapOf(
                    "Allow" to ALLOW_METHODS,
                    "Access-Control-Allow-Methods" to ALLOW_METHODS,
                    "Access-Control-Allow-Headers" to ALLOW_HEADERS,
                    "Access-Control-Max-Age" to "600"
                )
            )
        }

        if (auth.isBlocked(req.remote)) return McpHttpServer.Response(429, extra + ("Retry-After" to "60"))
        if (!auth.tokenValid(req.header("authorization"), req.remote)) return McpHttpServer.Response(401, extra)

        return protocol.handle(req, extra)
    }
}
