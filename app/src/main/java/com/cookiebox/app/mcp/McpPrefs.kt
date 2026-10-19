package com.cookiebox.app.mcp

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.cookiebox.app.engine.DomainUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 服务设置（ADR-0004 §5.6）。只读写新文件 `mcp_prefs`，绝不碰已有的 SharedPreferences。
 */
object McpPrefs {

    const val FILE = "mcp_prefs"
    const val DEFAULT_PORT = 8765
    const val DEFAULT_PROFILE = "Profile_Default"

    private const val K_ENABLED = "enabled"
    private const val K_PORT = "port"
    private const val K_LAN = "lan_enabled"
    private const val K_TOKEN = "token_enc"
    private const val K_GRANTS = "grants"
    private const val K_CONFIRM = "confirm_mode"
    private const val K_MAX_BODY = "max_body_bytes"
    private const val K_PAGE_MAX_BODY = "page_max_body_bytes"
    private const val K_VISITED = "visited_hosts"
    private const val K_ORIGINS = "origin_allowlist"

    private const val MAX_VISITED_PER_PROFILE = 200

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- 开关 ----------

    fun isEnabled(c: Context) = sp(c).getBoolean(K_ENABLED, false)
    fun setEnabled(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_ENABLED, v).apply() }

    fun port(c: Context): Int = sp(c).getInt(K_PORT, DEFAULT_PORT).takeIf { it in 1024..65535 } ?: DEFAULT_PORT
    fun setPort(c: Context, v: Int) { sp(c).edit().putInt(K_PORT, v.coerceIn(1024, 65535)).apply() }

    fun isLanEnabled(c: Context) = sp(c).getBoolean(K_LAN, false)
    fun setLanEnabled(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_LAN, v).apply() }

    fun tokenEnc(c: Context): String? = sp(c).getString(K_TOKEN, null)
    fun setTokenEnc(c: Context, v: String): Boolean = sp(c).edit().putString(K_TOKEN, v).commit()

    /** 写确认档位：always / trusted（默认）/ never。写操作在 T-028 接入。 */
    fun confirmMode(c: Context): String = sp(c).getString(K_CONFIRM, "trusted") ?: "trusted"

    fun maxBodyBytes(c: Context): Int = sp(c).getInt(K_MAX_BODY, 1024 * 1024).coerceIn(16 * 1024, 8 * 1024 * 1024)
    fun pageMaxBodyBytes(c: Context): Int = sp(c).getInt(K_PAGE_MAX_BODY, 256 * 1024).coerceIn(16 * 1024, 2 * 1024 * 1024)

    fun originAllowlist(c: Context): List<String> =
        (sp(c).getString(K_ORIGINS, "") ?: "").split('\n').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

    fun setOriginAllowlist(c: Context, list: List<String>) {
        sp(c).edit().putString(K_ORIGINS, list.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.joinToString("\n")).apply()
    }

    // ---------- 授权 ----------

    class ProfileGrant(
        var all: Boolean = false,
        /** 域名 -> 是否「信任，写操作免确认」（T-028 使用） */
        val domains: LinkedHashMap<String, Boolean> = linkedMapOf(),
        /** 用户手动添加的候选域名 */
        val manual: LinkedHashSet<String> = linkedSetOf()
    )

    class Grants(
        var all: Boolean = false,
        val profiles: LinkedHashMap<String, ProfileGrant> = linkedMapOf()
    ) {
        fun profile(name: String): ProfileGrant = profiles.getOrPut(name) { ProfileGrant() }
    }

    fun grants(c: Context): Grants {
        val raw = sp(c).getString(K_GRANTS, null) ?: return Grants()
        return try {
            val o = JSONObject(raw)
            val g = Grants(all = o.optBoolean("all", false))
            val ps = o.optJSONObject("profiles") ?: JSONObject()
            for (name in ps.keys()) {
                val po = ps.optJSONObject(name) ?: continue
                val pg = ProfileGrant(all = po.optBoolean("all", false))
                po.optJSONObject("domains")?.let { d ->
                    for (dom in d.keys()) pg.domains[dom] = d.optJSONObject(dom)?.optBoolean("trusted", false) ?: false
                }
                po.optJSONArray("manual")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { pg.manual.add(it) }
                }
                g.profiles[name] = pg
            }
            g
        } catch (e: Exception) {
            Grants()
        }
    }

    fun saveGrants(c: Context, g: Grants) {
        val ps = JSONObject()
        for ((name, pg) in g.profiles) {
            val d = JSONObject()
            for ((dom, trusted) in pg.domains) d.put(dom, JSONObject().put("trusted", trusted))
            ps.put(name, JSONObject().put("all", pg.all).put("domains", d).put("manual", JSONArray(pg.manual.toList())))
        }
        sp(c).edit().putString(K_GRANTS, JSONObject().put("all", g.all).put("profiles", ps).toString()).apply()
    }

    // ---------- 访问过的域名（授权候选，ADR-0004 §5.5） ----------

    fun visitedHosts(c: Context): Map<String, List<String>> {
        val raw = sp(c).getString(K_VISITED, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { k ->
                val arr = o.optJSONArray(k) ?: JSONArray()
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 把 URL 的注册域名记为该身份的候选域名；MCP 关闭时不记录。 */
    fun recordVisit(c: Context, profileName: String, url: String?) {
        if (!isEnabled(c)) return
        val dom = registrableDomain(url) ?: return
        synchronized(this) {
            val map = visitedHosts(c).mapValues { it.value.toMutableList() }.toMutableMap()
            val list = map.getOrPut(profileName) { mutableListOf() }
            if (list.firstOrNull() == dom) return
            list.remove(dom)
            list.add(0, dom)
            while (list.size > MAX_VISITED_PER_PROFILE) list.removeAt(list.size - 1)
            val o = JSONObject()
            for ((k, v) in map) o.put(k, JSONArray(v))
            sp(c).edit().putString(K_VISITED, o.toString()).apply()
        }
    }

    fun registrableDomain(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = try { Uri.parse(url) } catch (e: Exception) { return null }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.trim('.') ?: return null
        if (host.isEmpty()) return null
        // IP 地址不取根域
        if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return host
        return DomainUtils.getRootDomain(host).ifEmpty { host }
    }

    /** 规范化用户手填的域名：去掉协议、路径、端口、前导点与 *.。 */
    fun normalizeDomainInput(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringBefore(':')
        s = s.removePrefix("*.").trim('.')
        if (s.isEmpty() || s.contains(' ')) return null
        return s
    }
}
