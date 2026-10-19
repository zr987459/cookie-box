package com.cookiebox.app.mcp.tools

import android.content.Context
import com.cookiebox.app.mcp.McpGrants
import com.cookiebox.app.mcp.McpTabs
import com.cookiebox.app.mcp.McpToolException
import org.json.JSONArray
import org.json.JSONObject

/** 工具公共逻辑：参数读取、会话解析、授权检查。错误消息不含 Cookie / Token / 正文。 */
internal object ToolSupport {

    const val UNAUTHORIZED_HINT = "请在曲奇盒「AI 访问（MCP）→ 站点授权」里授权该身份与域名"

    class Session(val id: String, val profileName: String, val customName: String)

    fun schema(properties: JSONObject, required: List<String> = emptyList()): JSONObject =
        JSONObject().put("type", "object").put("properties", properties).apply {
            if (required.isNotEmpty()) put("required", JSONArray(required))
            put("additionalProperties", false)
        }

    fun str(desc: String, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", "string").put("description", desc).apply {
            if (enum != null) put("enum", JSONArray(enum))
        }

    fun requireString(args: JSONObject, key: String): String {
        val v = args.opt(key)
        if (v !is String || v.isBlank()) throw McpToolException("缺少参数 $key")
        return v.trim()
    }

    fun optString(args: JSONObject, key: String): String? {
        val v = args.opt(key)
        return if (v is String && v.isNotBlank()) v.trim() else null
    }

    /** 按 session_id 找标签；该身份没有任何授权时与「不存在」同样处理，不泄露标签存在与否 */
    fun resolveSession(context: Context, sessionId: String): Session {
        val info = McpTabs.snapshot().firstOrNull { it.id == sessionId }
            ?: throw McpToolException("session_id 不存在或已失效（应用重启后标签 id 会变化），请重新调用 list_sessions")
        if (!McpGrants.hasAnyGrant(context, info.profileName)) {
            throw McpToolException("session_id 不存在或未授权，请重新调用 list_sessions")
        }
        return Session(info.id, info.profileName, info.customName)
    }

    fun requireAuthorized(context: Context, session: Session, host: String) {
        if (!McpGrants.isAuthorized(context, session.profileName, host)) {
            throw McpToolException("未授权：该身份没有授权 $host。$UNAUTHORIZED_HINT")
        }
    }
}
