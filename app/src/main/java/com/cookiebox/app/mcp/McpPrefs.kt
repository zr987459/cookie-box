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
        const val KEY_GRANTS = "grants"
        const val KEY_VISITED_HOSTS = "visited_hosts"
        const val KEY_MAX_BODY_BYTES = "max_body_bytes"
        const val KEY_PAGE_MAX_BODY_BYTES = "page_max_body_bytes"

        /** native 通道正文截断：默认 1 MiB，上限 8 MiB */
        const val DEFAULT_MAX_BODY = 1024 * 1024
        const val LIMIT_MAX_BODY = 8 * 1024 * 1024
        /** page 通道正文截断：默认 256 KiB，上限 2 MiB */
        const val DEFAULT_PAGE_MAX_BODY = 256 * 1024
        const val LIMIT_PAGE_MAX_BODY = 2 * 1024 * 1024
        private const val MIN_BODY = 16 * 1024

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

    var maxBodyBytes: Int
        get() = sp.getInt(KEY_MAX_BODY_BYTES, DEFAULT_MAX_BODY).coerceIn(MIN_BODY, LIMIT_MAX_BODY)
        set(v) { sp.edit().putInt(KEY_MAX_BODY_BYTES, v.coerceIn(MIN_BODY, LIMIT_MAX_BODY)).commit() }

    var pageMaxBodyBytes: Int
        get() = sp.getInt(KEY_PAGE_MAX_BODY_BYTES, DEFAULT_PAGE_MAX_BODY).coerceIn(MIN_BODY, LIMIT_PAGE_MAX_BODY)
        set(v) { sp.edit().putInt(KEY_PAGE_MAX_BODY_BYTES, v.coerceIn(MIN_BODY, LIMIT_PAGE_MAX_BODY)).commit() }

    /** 站点授权 JSON（结构见 [McpGrants]） */
    var grantsJson: String?
        get() = sp.getString(KEY_GRANTS, null)
        set(v) { sp.edit().putString(KEY_GRANTS, v).commit() }

    /** 按 profileName 分组的已访问注册域名 JSON：{"Profile_Default":["a.com",...]}，新的在前 */
    var visitedHostsJson: String?
        get() = sp.getString(KEY_VISITED_HOSTS, null)
        set(v) { sp.edit().putString(KEY_VISITED_HOSTS, v).apply() }
}
