package com.cookiebox.app.mcp

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** MCP 工具：T-027 起实现具体工具，T-026 的列表为空 */
interface McpTool {
    val name: String
    val description: String
    val inputSchema: JSONObject
    /** 可选：readOnlyHint / destructiveHint 等 */
    val annotations: JSONObject? get() = null

    /** 工具执行失败请抛 McpToolException，消息会作为 isError 结果返回，不得含 Cookie / Token / 正文 */
    fun call(arguments: JSONObject, context: McpToolContext): McpToolResult
}

class McpToolContext(val sessionId: String?)

class McpToolException(message: String) : Exception(message)

class McpToolResult(val content: JSONArray, val isError: Boolean = false, val structured: JSONObject? = null) {
    companion object {
        fun text(text: String, isError: Boolean = false): McpToolResult =
            McpToolResult(JSONArray().put(JSONObject().put("type", "text").put("text", text)), isError)

        fun json(obj: JSONObject): McpToolResult =
            McpToolResult(JSONArray().put(JSONObject().put("type", "text").put("text", obj.toString())), false, obj)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("content", content)
        if (isError) put("isError", true)
        if (structured != null) put("structuredContent", structured)
    }
}

/**
 * MCP Streamable HTTP（JSON-RPC 2.0 over POST，只回 application/json，不开 SSE 流）。
 * 支持 initialize / notifications/… / ping / tools/list / tools/call；GET 405；DELETE 结束会话。
 * 准入（Host / Origin / Token）由 [McpAuth] 在调用 [handle] 之前完成。
 */
class McpProtocol(
    private val serverVersion: String,
    private val tools: List<McpTool>
) {

    companion object {
        private const val TAG = "CookieBoxMcp"
        /** 新到旧；客户端请求的版本在列表内则原样回应，否则回应最新版 */
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        private const val MAX_SESSIONS = 32

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        private const val JSON = "application/json"
    }

    private class Session(val protocolVersion: String, val createdAt: Long)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    fun handle(req: McpHttpServer.Request, extraHeaders: Map<String, String>): McpHttpServer.Response {
        return when (req.method) {
            "POST" -> handlePost(req, extraHeaders)
            "DELETE" -> {
                val sid = req.header("mcp-session-id")
                if (sid != null && sessions.remove(sid) != null) McpHttpServer.Response(204, extraHeaders)
                else McpHttpServer.Response(404, extraHeaders)
            }
            "GET" -> McpHttpServer.Response(405, extraHeaders + ("Allow" to "POST, DELETE"))
            else -> McpHttpServer.Response(405, extraHeaders + ("Allow" to "POST, DELETE"))
        }
    }

    private fun handlePost(req: McpHttpServer.Request, extra: Map<String, String>): McpHttpServer.Response {
        val contentType = req.header("content-type")?.substringBefore(';')?.trim()?.lowercase()
        if (contentType != null && contentType != JSON) return McpHttpServer.Response(415, extra)

        val text = String(req.body, Charsets.UTF_8).trim()
        if (text.startsWith("[")) {
            // 2025-06-18 起规范已移除批量请求
            return jsonResponse(200, extra, errorObj(JSONObject.NULL, INVALID_REQUEST, "Batch requests are not supported"))
        }
        val msg = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return jsonResponse(400, extra, errorObj(JSONObject.NULL, PARSE_ERROR, "Parse error"))
        }
        if (msg.optString("jsonrpc") != "2.0") {
            return jsonResponse(400, extra, errorObj(msg.opt("id") ?: JSONObject.NULL, INVALID_REQUEST, "jsonrpc must be \"2.0\""))
        }

        val method = msg.optString("method", "")
        val hasId = msg.has("id") && !msg.isNull("id")
        val id: Any = if (hasId) msg.get("id") else JSONObject.NULL

        // 客户端发来的响应或通知：无需回包
        if (method.isEmpty()) return McpHttpServer.Response(202, extra)
        if (!hasId) return McpHttpServer.Response(202, extra)

        if (method == "initialize") return initialize(msg, id, extra)

        // 会话与协议版本校验（initialize 之外）
        val sid = req.header("mcp-session-id")
        if (sid != null && !sessions.containsKey(sid)) {
            return jsonResponse(404, extra, errorObj(id, INVALID_REQUEST, "Unknown or expired session; re-initialize"))
        }
        val version = req.header("mcp-protocol-version")?.trim()
        if (version != null && version !in SUPPORTED_VERSIONS) {
            return jsonResponse(400, extra, errorObj(id, INVALID_REQUEST, "Unsupported MCP-Protocol-Version"))
        }

        val params = msg.optJSONObject("params") ?: JSONObject()
        val body = when (method) {
            "ping" -> resultObj(id, JSONObject())
            "tools/list" -> resultObj(id, listTools())
            "tools/call" -> callTool(id, params, sid)
            else -> errorObj(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
        return jsonResponse(200, extra, body)
    }

    private fun initialize(msg: JSONObject, id: Any, extra: Map<String, String>): McpHttpServer.Response {
        val requested = msg.optJSONObject("params")?.optString("protocolVersion", "") ?: ""
        val version = if (requested in SUPPORTED_VERSIONS) requested else SUPPORTED_VERSIONS.first()
        val sid = newSessionId()
        if (sessions.size >= MAX_SESSIONS) {
            sessions.entries.minByOrNull { it.value.createdAt }?.let { sessions.remove(it.key) }
        }
        sessions[sid] = Session(version, System.currentTimeMillis())

        val result = JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "cookiebox").put("title", "CookieBox").put("version", serverVersion))
            .put(
                "instructions",
                "CookieBox exposes the user's logged-in browser tabs on this phone. " +
                    "Use list_sessions to see authorized tabs; customName identifies which account a tab belongs to."
            )
        return jsonResponse(200, extra + ("Mcp-Session-Id" to sid), resultObj(id, result))
    }

    private fun listTools(): JSONObject {
        val arr = JSONArray()
        for (t in tools) {
            val o = JSONObject()
                .put("name", t.name)
                .put("description", t.description)
                .put("inputSchema", t.inputSchema)
            t.annotations?.let { o.put("annotations", it) }
            arr.put(o)
        }
        return JSONObject().put("tools", arr)
    }

    private fun callTool(id: Any, params: JSONObject, sid: String?): JSONObject {
        val name = params.optString("name", "")
        val tool = tools.firstOrNull { it.name == name }
            ?: return errorObj(id, INVALID_PARAMS, "Unknown tool: $name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val result = try {
            tool.call(args, McpToolContext(sid))
        } catch (e: McpToolException) {
            McpToolResult.text(e.message ?: "Tool error", isError = true)
        } catch (e: Exception) {
            // 只记类型名，不记消息（可能含页面数据）
            Log.w(TAG, "tool $name failed: ${e.javaClass.simpleName}")
            McpToolResult.text("Internal error in tool $name", isError = true)
        }
        return resultObj(id, result.toJson())
    }

    private fun newSessionId(): String {
        val b = ByteArray(16).also { random.nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun resultObj(id: Any, result: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

    private fun errorObj(id: Any, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun jsonResponse(status: Int, extra: Map<String, String>, body: JSONObject): McpHttpServer.Response =
        McpHttpServer.Response(status, extra, body.toString().toByteArray(Charsets.UTF_8), JSON)
}
