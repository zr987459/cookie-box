package com.cookiebox.app.mcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** 工具调用中的可预期错误：作为 isError=true 的工具结果返回给 AI。 */
class ToolError(message: String) : Exception(message)

interface McpTool {
    val name: String
    val description: String
    val inputSchema: JSONObject
    /** 返回放进 text 内容的 JSON。工作线程调用。 */
    fun call(args: JSONObject): JSONObject
}

/**
 * MCP Streamable HTTP + JSON-RPC 2.0（ADR-0004 §4.1）：单端点 `/mcp`，只回 application/json，不开 SSE。
 */
class McpProtocol(
    private val ctx: Context,
    private val auth: McpAuth,
    private val tools: List<McpTool>
) {
    companion object {
        val SUPPORTED_VERSIONS = listOf("2025-03-26", "2025-06-18", "2025-11-25")
        const val LATEST = "2025-11-25"
        private const val ENDPOINT = "/mcp"
    }

    private val sessions: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val toolMap = tools.associateBy { it.name }

    fun handle(req: HttpReq): HttpResp {
        // 第一道门：Host（防 DNS rebinding）
        if (!auth.hostOk(req.headers["host"])) return HttpResp.text(403, "forbidden: host")

        // 第二道门：Origin（无 Origin 放行；有则必须在白名单）
        val origin = req.headers["origin"]
        val cors = when (auth.checkOrigin(origin)) {
            McpAuth.OriginResult.DENIED -> return HttpResp.text(403, "forbidden: origin")
            McpAuth.OriginResult.ALLOWED -> mapOf(
                "Access-Control-Allow-Origin" to origin!!,
                "Vary" to "Origin",
                "Access-Control-Expose-Headers" to "Mcp-Session-Id, MCP-Protocol-Version"
            )
            McpAuth.OriginResult.NONE -> emptyMap()
        }

        val path = req.path.trimEnd('/').ifEmpty { "/" }
        if (path != ENDPOINT) return HttpResp.text(404, "not found", cors)

        if (req.method == "OPTIONS") {
            return HttpResp(204, headers = cors + mapOf(
                "Access-Control-Allow-Methods" to "POST, DELETE, OPTIONS",
                "Access-Control-Allow-Headers" to "Authorization, Content-Type, Accept, Mcp-Session-Id, MCP-Protocol-Version, Last-Event-ID",
                "Access-Control-Max-Age" to "600"
            ))
        }

        // 第三道门：Token + 失败限流
        if (auth.isCoolingDown(req.remoteIp)) return HttpResp.text(429, "too many failed attempts", cors + ("Retry-After" to "60"))
        if (!auth.tokenOk(req.headers["authorization"], req.remoteIp)) {
            return HttpResp.text(401, "unauthorized", cors + ("WWW-Authenticate" to "Bearer"))
        }

        val pv = req.headers["mcp-protocol-version"]
        if (pv != null && pv !in SUPPORTED_VERSIONS) {
            return HttpResp.text(400, "unsupported MCP-Protocol-Version", cors)
        }

        return when (req.method) {
            "POST" -> handlePost(req, cors)
            "DELETE" -> {
                req.headers["mcp-session-id"]?.let { sessions.remove(it) }
                HttpResp(200, headers = cors)
            }
            "GET" -> HttpResp.text(405, "SSE stream not supported", cors + ("Allow" to "POST, DELETE"))
            else -> HttpResp.text(405, "method not allowed", cors + ("Allow" to "POST, DELETE"))
        }
    }

    private fun handlePost(req: HttpReq, cors: Map<String, String>): HttpResp {
        val sid = req.headers["mcp-session-id"]
        if (sid != null && sid !in sessions) {
            return HttpResp.json(404, rpcError(null, -32001, "Session not found").toString(), cors)
        }
        val text = String(req.body, Charsets.UTF_8).trim()
        val parsed: Any = try {
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        } catch (e: Exception) {
            return HttpResp.json(400, rpcError(null, -32700, "Parse error").toString(), cors)
        }

        val extra = HashMap<String, String>(cors)
        if (parsed is JSONArray) {
            val out = JSONArray()
            for (i in 0 until parsed.length()) {
                val msg = parsed.optJSONObject(i)
                if (msg == null) {
                    out.put(rpcError(null, -32600, "Invalid Request")); continue
                }
                dispatch(msg, extra)?.let { out.put(it) }
            }
            return if (out.length() == 0) HttpResp(202, headers = extra) else HttpResp.json(200, out.toString(), extra)
        }
        val resp = dispatch(parsed as JSONObject, extra) ?: return HttpResp(202, headers = extra)
        return HttpResp.json(200, resp.toString(), extra)
    }

    /** 返回 null 表示通知 / 客户端响应，不需要回包。 */
    private fun dispatch(msg: JSONObject, extraHeaders: MutableMap<String, String>): JSONObject? {
        if (!msg.has("method")) return null // 客户端发来的 response
        val method = msg.optString("method")
        val hasId = msg.has("id") && !msg.isNull("id")
        val id: Any? = if (hasId) msg.get("id") else null
        if (!hasId) return null // 通知（notifications/initialized 等）
        val params = msg.optJSONObject("params") ?: JSONObject()

        return try {
            when (method) {
                "initialize" -> {
                    val requested = params.optString("protocolVersion", "")
                    val version = if (requested in SUPPORTED_VERSIONS) requested else LATEST
                    val newSid = newSessionId()
                    sessions.add(newSid)
                    extraHeaders["Mcp-Session-Id"] = newSid
                    rpcResult(id, JSONObject()
                        .put("protocolVersion", version)
                        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                        .put("serverInfo", JSONObject().put("name", "cookiebox").put("version", appVersion()))
                        .put("instructions",
                            "CookieBox 是用户手机上的浏览器。先调用 list_sessions 查看用户授权给你的标签（customName 是用户起的名字，用来区分账号），" +
                                "再用 get_cookies / http_request 以该标签的登录身份访问已授权站点。只能访问用户授权的「身份 × 域名」。"))
                }
                "ping" -> rpcResult(id, JSONObject())
                "tools/list" -> {
                    val arr = JSONArray()
                    tools.forEach {
                        arr.put(JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", it.inputSchema))
                    }
                    rpcResult(id, JSONObject().put("tools", arr))
                }
                "tools/call" -> {
                    val name = params.optString("name")
                    val tool = toolMap[name] ?: return rpcError(id, -32602, "Unknown tool: $name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val (text, isError) = try {
                        tool.call(args).toString() to false
                    } catch (e: ToolError) {
                        (e.message ?: "error") to true
                    } catch (e: Exception) {
                        // 不把异常详情（可能含 URL 参数）写进日志，只回给调用方类型名
                        "内部错误：${e.javaClass.simpleName}" to true
                    }
                    rpcResult(id, JSONObject()
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                        .put("isError", isError))
                }
                else -> rpcError(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            rpcError(id, -32603, "Internal error")
        }
    }

    private fun rpcResult(id: Any?, result: JSONObject) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)

    private fun rpcError(id: Any?, code: Int, message: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun newSessionId(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun appVersion(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
