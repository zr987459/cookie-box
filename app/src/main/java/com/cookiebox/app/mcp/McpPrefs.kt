package com.cookiebox.app.mcp

import android.content.Context

/**
 * MCP 服务设置，独立文件 mcp_prefs（ADR-0004 §5.6）。
 * 不读写任何已有 SharedPreferences 文件和键。键名一经发布即冻结（AGENTS.md §3）。
 */
class McpPrefs(context: Context) {

    companion object {
        const val FILE = "mcp_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_PORT = "port"
        const val KEY_LAN_ENABLED = "lan_enabled"
        const val KEY_TOKEN_ENC = "token_enc"
        const val KEY_ORIGIN_ALLOWLIST = "origin_allowlist"

        const val DEFAULT_PORT = 18765
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /** Origin 统一成小写、去掉末尾斜杠，便于精确比较 */
        fun normalizeOrigin(raw: String): String = raw.trim().lowercase().trimEnd('/')
    }

    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) { sp.edit().putBoolean(KEY_ENABLED, v).commit() }

    var port: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT).takeIf { it in MIN_PORT..MAX_PORT } ?: DEFAULT_PORT
        set(v) { sp.edit().putInt(KEY_PORT, v.coerceIn(MIN_PORT, MAX_PORT)).commit() }

    var lanEnabled: Boolean
        get() = sp.getBoolean(KEY_LAN_ENABLED, false)
        set(v) { sp.edit().putBoolean(KEY_LAN_ENABLED, v).commit() }

    var tokenEnc: String?
        get() = sp.getString(KEY_TOKEN_ENC, null)
        set(v) { sp.edit().putString(KEY_TOKEN_ENC, v).commit() }

    /** Origin 白名单，默认为空（有 Origin 头的请求一律拒绝） */
    var originAllowlist: Set<String>
        get() = sp.getStringSet(KEY_ORIGIN_ALLOWLIST, emptySet())
            ?.map { normalizeOrigin(it) }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        set(v) {
            val clean = v.map { normalizeOrigin(it) }.filter { it.isNotEmpty() }.toSet()
            sp.edit().putStringSet(KEY_ORIGIN_ALLOWLIST, HashSet(clean)).commit()
        }
}
