package com.cookiebox.app.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 浏览器偏好：首页快捷入口、搜索引擎、标签会话（重启恢复）。 */
class BrowserPrefs(context: Context) {

    data class Shortcut(val title: String, val url: String)

    data class SavedTab(
        val url: String,
        val title: String,
        val isDesktop: Boolean,
        val isClone: Boolean,
        val profileName: String,
        val customName: String = ""
    )

    private val sp = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)

    // ---------- 搜索引擎 ----------
    val searchEngines = linkedMapOf(
        "Bing" to "https://www.bing.com/search?q=",
        "百度" to "https://www.baidu.com/s?wd=",
        "Google" to "https://www.google.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q="
    )

    var searchEngine: String
        get() = sp.getString("search_engine", "Bing")?.takeIf { searchEngines.containsKey(it) } ?: "Bing"
        set(v) = sp.edit().putString("search_engine", v).apply()

    fun searchUrl(query: String): String =
        searchEngines[searchEngine] + java.net.URLEncoder.encode(query, "UTF-8")

    // ---------- 快捷入口 ----------
    private val defaultShortcuts = listOf(
        Shortcut("哔哩哔哩", "https://m.bilibili.com"),
        Shortcut("微博", "https://weibo.com"),
        Shortcut("GitHub", "https://github.com/login"),
        Shortcut("百度", "https://www.baidu.com"),
        Shortcut("知乎", "https://www.zhihu.com"),
        Shortcut("V2EX", "https://www.v2ex.com")
    )

    fun getShortcuts(): List<Shortcut> {
        val raw = sp.getString("shortcuts", null) ?: return defaultShortcuts
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Shortcut(o.optString("title"), o.optString("url"))
            }.filter { it.url.isNotBlank() }
        } catch (e: Exception) {
            defaultShortcuts
        }
    }

    private fun saveShortcuts(list: List<Shortcut>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url)) }
        sp.edit().putString("shortcuts", arr.toString()).apply()
    }

    /** 返回 false 表示已存在 */
    fun addShortcut(title: String, url: String): Boolean {
        val list = getShortcuts().toMutableList()
        if (list.any { it.url == url }) return false
        list.add(Shortcut(title.ifBlank { url }.take(20), url))
        saveShortcuts(list)
        return true
    }

    fun removeShortcut(url: String) {
        saveShortcuts(getShortcuts().filterNot { it.url == url })
    }

    // ---------- T-030 一次性迁移标记（新增键，不改旧键） ----------
    var defaultIdentityMigrated: Boolean
        get() = sp.getBoolean("t030_default_identity_migrated", false)
        set(v) = sp.edit().putBoolean("t030_default_identity_migrated", v).apply()

    // ---------- 标签会话 ----------
    fun saveSession(tabs: List<SavedTab>, activeIndex: Int) {
        val arr = JSONArray()
        tabs.forEach {
            arr.put(
                JSONObject()
                    .put("url", it.url)
                    .put("title", it.title)
                    .put("desktop", it.isDesktop)
                    .put("clone", it.isClone)
                    .put("profile", it.profileName)
                    .put("label", it.customName)
            )
        }
        sp.edit().putString("session_tabs", arr.toString()).putInt("session_active", activeIndex).apply()
    }

    fun loadSession(): Pair<List<SavedTab>, Int> {
        val raw = sp.getString("session_tabs", null) ?: return Pair(emptyList(), 0)
        return try {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SavedTab(
                    o.optString("url"),
                    o.optString("title"),
                    o.optBoolean("desktop"),
                    o.optBoolean("clone"),
                    o.optString("profile", "Profile_Default"),
                    o.optString("label", "")
                )
            }
            Pair(list, sp.getInt("session_active", 0))
        } catch (e: Exception) {
            Pair(emptyList(), 0)
        }
    }
}
