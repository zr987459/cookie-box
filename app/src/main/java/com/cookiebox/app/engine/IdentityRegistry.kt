package com.cookiebox.app.engine

import android.content.Context
import org.json.JSONObject

/**
 * 身份（账号）的显示信息：名称与颜色圆点。T-030 起「分身」在界面上统一叫「账号」。
 *
 * 存在 browser_prefs 的新键 `identities`（T-030 新增，不改任何旧键）：
 *   {"Profile_Default":{"n":"","no":1,"c":0}, "Profile_Clone_ab12cd34":{"n":"B站小号","no":2,"c":1}}
 * n = 用户起的名字（空 = 用自动名），no = 自动编号（账号 N），c = 颜色序号。
 * 只存本机显示用的名字，不含任何凭据。
 */
class IdentityRegistry(context: Context) {

    private val sp = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    private val data: JSONObject = try {
        JSONObject(sp.getString(KEY, null) ?: "{}")
    } catch (e: Exception) {
        // 解析失败不覆盖：先备份原文，再从空表开始（只是显示名，丢了也能自动重新编号）
        sp.getString(KEY, null)?.let { sp.edit().putString("${KEY}_corrupt", it).apply() }
        JSONObject()
    }

    /** 确保身份有编号和颜色；返回是否新登记 */
    fun ensure(profile: String): Boolean {
        if (data.has(profile)) return false
        val usedNo = keys().map { data.getJSONObject(it).optInt("no") }.toSet()
        val usedColor = keys().map { data.getJSONObject(it).optInt("c") }.toSet()
        val no = if (profile == ProfileIsolation.DEFAULT_PROFILE) 1
        else generateSequence(2) { it + 1 }.first { it !in usedNo }
        val color = if (profile == ProfileIsolation.DEFAULT_PROFILE) 0
        else (0 until PALETTE.size).firstOrNull { it !in usedColor } ?: (no % PALETTE.size)
        data.put(profile, JSONObject().put("n", "").put("no", no).put("c", color))
        persist()
        return true
    }

    fun customName(profile: String): String = data.optJSONObject(profile)?.optString("n").orEmpty()

    fun name(profile: String): String {
        ensure(profile)
        val o = data.getJSONObject(profile)
        val n = o.optString("n")
        if (n.isNotBlank()) return n
        return if (profile == ProfileIsolation.DEFAULT_PROFILE) "主账号" else "账号 ${o.optInt("no", 2)}"
    }

    fun color(profile: String): Int {
        ensure(profile)
        return PALETTE[data.getJSONObject(profile).optInt("c", 0).mod(PALETTE.size)]
    }

    fun rename(profile: String, raw: String) {
        ensure(profile)
        data.getJSONObject(profile).put("n", raw.replace(Regex("\\s+"), " ").trim().take(24))
        persist()
    }

    /** 旧版的标签自定义名（customName）迁移成身份名：只在身份还没有名字时采用 */
    fun adoptLegacyName(profile: String, legacy: String) {
        if (legacy.isBlank() || customName(profile).isNotBlank()) return
        rename(profile, legacy)
    }

    fun forget(profile: String) {
        if (profile == ProfileIsolation.DEFAULT_PROFILE) return
        data.remove(profile)
        persist()
    }

    private fun keys(): List<String> = data.keys().asSequence().toList()

    private fun persist() {
        sp.edit().putString(KEY, data.toString()).apply()
    }

    companion object {
        private const val KEY = "identities"
        /** 身份圆点颜色（非文字，只作区分；浅色/深色底上都清晰可辨） */
        val PALETTE = intArrayOf(
            0xFF1A73E8.toInt(), // 蓝
            0xFFE8710A.toInt(), // 橙
            0xFF188038.toInt(), // 绿
            0xFFD93025.toInt(), // 红
            0xFF9334E6.toInt(), // 紫
            0xFF12B5CB.toInt(), // 青
            0xFFE52592.toInt(), // 粉
            0xFF8D6E63.toInt()  // 棕
        )
    }
}
