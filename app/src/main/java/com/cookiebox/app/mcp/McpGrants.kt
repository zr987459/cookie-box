package com.cookiebox.app.mcp

import android.content.Context
import com.cookiebox.app.engine.DomainUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 站点授权与候选域名（ADR-0004 §5.5）。授权单位是「身份（profileName）× 注册域名」。
 *
 * mcp_prefs.grants 结构：
 * ```
 * {
 *   "all": false,                         // 授权全部标签
 *   "profiles": {
 *     "Profile_Default": {
 *       "all": false,                     // 授权该身份全部站点
 *       "manual": ["example.com"],        // 用户手动添加的候选域名
 *       "domains": { "bilibili.com": { "trust": false } },  // 逐个勾选
 *       "trusted": ["bilibili.com"]       // 信任：写操作免确认（「信任的站点免确认」模式下生效，含子域）
 *     }
 *   }
 * }
 * ```
 * 「全部」= 该身份 visited_hosts + manual 中的全部域名（之后新访问到的自动纳入）。
 * 目标主机必须等于某个授权域名或是它的子域。
 */
object McpGrants {

    private const val MAX_VISITED_PER_PROFILE = 200
    private val lock = Any()

    // ---------- 域名工具 ----------

    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun normalizeHost(raw: String): String =
        raw.trim().trim('.').lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")

    /** host → 记录用的注册域名；IP 字面量原样保留 */
    fun registrableDomain(host: String): String {
        val h = normalizeHost(host)
        if (h.isEmpty()) return ""
        if (ipv4.matches(h) || h.contains(':')) return h
        if (!h.contains('.')) return h
        return DomainUtils.getRootDomain(h)
    }

    /** 用户手动输入的域名：可带 scheme / 路径，取 host 后保留原层级（不自动上提到根域） */
    fun parseManualDomain(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (s.contains("://")) s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfter('@')
        if (!s.startsWith("[")) s = s.substringBefore(':')
        val h = normalizeHost(s).removePrefix("*.")
        if (h.isEmpty() || h.any { it.isWhitespace() }) return null
        return h
    }

    fun hostMatches(host: String, domain: String): Boolean {
        val h = normalizeHost(host)
        val d = normalizeHost(domain)
        if (h.isEmpty() || d.isEmpty()) return false
        return h == d || h.endsWith(".$d")
    }

    // ---------- visited_hosts ----------

    /** 主框架导航完成时调用（主线程）。服务关闭期间不记录。 */
    fun recordVisit(context: Context, profileName: String, url: String) {
        val prefs = McpPrefs(context)
        if (!prefs.enabled) return
        val host = hostOf(url) ?: return
        val domain = registrableDomain(host)
        if (domain.isEmpty()) return
        synchronized(lock) {
            val root = parseObj(prefs.visitedHostsJson)
            val arr = root.optJSONArray(profileName) ?: JSONArray()
            if (arr.length() > 0 && arr.optString(0) == domain) return
            val next = JSONArray().put(domain)
            for (i in 0 until arr.length()) {
                val d = arr.optString(i)
                if (d.isNotEmpty() && d != domain && next.length() < MAX_VISITED_PER_PROFILE) next.put(d)
            }
            root.put(profileName, next)
            prefs.visitedHostsJson = root.toString()
        }
    }

    fun visited(context: Context, profileName: String): List<String> {
        val arr = parseObj(McpPrefs(context).visitedHostsJson).optJSONArray(profileName) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
    }

    /** 所有出现过的身份名（visited 或 grants 里） */
    fun knownProfiles(context: Context): Set<String> {
        val out = LinkedHashSet<String>()
        val v = parseObj(McpPrefs(context).visitedHostsJson)
        v.keys().forEach { out.add(it) }
        val g = parseObj(McpPrefs(context).grantsJson).optJSONObject("profiles")
        g?.keys()?.forEach { out.add(it) }
        return out
    }

    fun hostOf(url: String): String? = try {
        val u = java.net.URI(url.trim())
        val scheme = u.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") null
        else u.host?.let { normalizeHost(it) }?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    // ---------- grants 读取 ----------

    fun allTabs(context: Context): Boolean = parseObj(McpPrefs(context).grantsJson).optBoolean("all", false)

    fun profileAll(context: Context, profileName: String): Boolean =
        profileObj(parseObj(McpPrefs(context).grantsJson), profileName, create = false)?.optBoolean("all", false) ?: false

    fun manual(context: Context, profileName: String): List<String> {
        val p = profileObj(parseObj(McpPrefs(context).grantsJson), profileName, create = false) ?: return emptyList()
        val arr = p.optJSONArray("manual") ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
    }

    /** 逐个勾选的域名 */
    fun checkedDomains(context: Context, profileName: String): Set<String> {
        val p = profileObj(parseObj(McpPrefs(context).grantsJson), profileName, create = false) ?: return emptySet()
        val d = p.optJSONObject("domains") ?: return emptySet()
        return d.keys().asSequence().toSet()
    }

    /** 候选域名 = visited + manual（去重，保序） */
    fun candidates(context: Context, profileName: String): List<String> =
        LinkedHashSet<String>().apply {
            addAll(manual(context, profileName))
            addAll(visited(context, profileName))
        }.toList()

    /** 该身份当前生效的授权域名 */
    fun authorizedDomains(context: Context, profileName: String): List<String> {
        val root = parseObj(McpPrefs(context).grantsJson)
        val p = profileObj(root, profileName, create = false)
        val all = root.optBoolean("all", false) || (p?.optBoolean("all", false) ?: false)
        val checked = p?.optJSONObject("domains")?.keys()?.asSequence()?.toList() ?: emptyList()
        return if (all) LinkedHashSet<String>().apply { addAll(checked); addAll(candidates(context, profileName)) }.toList()
        else checked
    }

    fun isAuthorized(context: Context, profileName: String, host: String): Boolean =
        authorizedDomains(context, profileName).any { hostMatches(host, it) }

    fun hasAnyGrant(context: Context, profileName: String): Boolean = authorizedDomains(context, profileName).isNotEmpty()

    // ---------- grants 写入（设置页，主线程） ----------

    fun setAllTabs(context: Context, value: Boolean) = edit(context) { it.put("all", value) }

    fun setProfileAll(context: Context, profileName: String, value: Boolean) = edit(context) {
        profileObj(it, profileName, create = true)!!.put("all", value)
    }

    fun setDomainChecked(context: Context, profileName: String, domain: String, checked: Boolean) = edit(context) {
        val p = profileObj(it, profileName, create = true)!!
        val d = p.optJSONObject("domains") ?: JSONObject().also { o -> p.put("domains", o) }
        if (checked) {
            if (!d.has(domain)) d.put(domain, JSONObject().put("trust", false))
        } else {
            d.remove(domain)
        }
    }

    fun addManual(context: Context, profileName: String, domain: String) = edit(context) {
        val p = profileObj(it, profileName, create = true)!!
        val arr = p.optJSONArray("manual") ?: JSONArray().also { a -> p.put("manual", a) }
        for (i in 0 until arr.length()) if (arr.optString(i) == domain) return@edit
        arr.put(domain)
    }

    fun removeManual(context: Context, profileName: String, domain: String) = edit(context) {
        val p = profileObj(it, profileName, create = true)!!
        val arr = p.optJSONArray("manual") ?: return@edit
        val next = JSONArray()
        for (i in 0 until arr.length()) arr.optString(i).let { d -> if (d != domain) next.put(d) }
        p.put("manual", next)
        p.optJSONObject("domains")?.remove(domain)
    }

    // ---------- 信任（写操作免确认，T-028） ----------

    fun trusted(context: Context, profileName: String): List<String> {
        val p = profileObj(parseObj(McpPrefs(context).grantsJson), profileName, create = false) ?: return emptyList()
        val out = LinkedHashSet<String>()
        p.optJSONArray("trusted")?.let { arr -> for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) } }
        p.optJSONObject("domains")?.let { d -> d.keys().forEach { k -> if (d.optJSONObject(k)?.optBoolean("trust", false) == true) out.add(k) } }
        return out.toList()
    }

    fun isTrusted(context: Context, profileName: String, host: String): Boolean =
        trusted(context, profileName).any { hostMatches(host, it) }

    fun setTrusted(context: Context, profileName: String, domain: String, value: Boolean) = edit(context) {
        val p = profileObj(it, profileName, create = true)!!
        val arr = p.optJSONArray("trusted") ?: JSONArray()
        val next = JSONArray()
        for (i in 0 until arr.length()) arr.optString(i).let { d -> if (d.isNotEmpty() && d != domain) next.put(d) }
        if (value) next.put(domain)
        p.put("trusted", next)
        p.optJSONObject("domains")?.optJSONObject(domain)?.put("trust", value)
    }

    /** 清除全部授权（不清 visited_hosts） */
    fun clearAll(context: Context) {
        synchronized(lock) { McpPrefs(context).grantsJson = null }
    }

    private fun edit(context: Context, block: (JSONObject) -> Unit) {
        synchronized(lock) {
            val prefs = McpPrefs(context)
            val root = parseObj(prefs.grantsJson)
            block(root)
            prefs.grantsJson = root.toString()
        }
    }

    private fun profileObj(root: JSONObject, profileName: String, create: Boolean): JSONObject? {
        var profiles = root.optJSONObject("profiles")
        if (profiles == null) {
            if (!create) return null
            profiles = JSONObject()
            root.put("profiles", profiles)
        }
        var p = profiles.optJSONObject(profileName)
        if (p == null && create) {
            p = JSONObject()
            profiles.put(profileName, p)
        }
        return p
    }

    private fun parseObj(raw: String?): JSONObject = try {
        if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
    } catch (e: Exception) {
        JSONObject()
    }
}
