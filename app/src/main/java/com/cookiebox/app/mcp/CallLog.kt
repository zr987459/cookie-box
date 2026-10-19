package com.cookiebox.app.mcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 调用记录（ADR-0004 §5.7）：只记时间、工具名、标签名 / 身份、目标域名、结果、耗时；
 * 不记参数和返回内容。环形保存最多 500 条，可一键清空。
 */
object CallLog {

    const val MAX_ENTRIES = 500

    const val RESULT_OK = "ok"
    const val RESULT_DENIED = "denied"
    const val RESULT_ERROR = "error"

    class Entry(
        val time: Long,
        val tool: String,
        val label: String,
        val domain: String,
        val result: String,
        val durationMs: Long
    )

    private val lock = Any()
    @Volatile
    private var cache: MutableList<Entry>? = null

    fun add(context: Context, entry: Entry) {
        synchronized(lock) {
            val list = load(context)
            list.add(entry)
            while (list.size > MAX_ENTRIES) list.removeAt(0)
            save(context, list)
        }
    }

    /** 新的在前 */
    fun entries(context: Context): List<Entry> = synchronized(lock) { load(context).toList().asReversed() }

    fun clear(context: Context) {
        synchronized(lock) {
            cache = mutableListOf()
            McpPrefs(context).callLogJson = null
        }
    }

    private fun load(context: Context): MutableList<Entry> {
        cache?.let { return it }
        val list = mutableListOf<Entry>()
        try {
            val raw = McpPrefs(context).callLogJson
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        Entry(
                            o.optLong("t"), o.optString("tool"), o.optString("label"),
                            o.optString("domain"), o.optString("result"), o.optLong("ms")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            list.clear()
        }
        cache = list
        return list
    }

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("t", it.time).put("tool", it.tool).put("label", it.label)
                    .put("domain", it.domain).put("result", it.result).put("ms", it.durationMs)
            )
        }
        McpPrefs(context).callLogJson = arr.toString()
    }
}
