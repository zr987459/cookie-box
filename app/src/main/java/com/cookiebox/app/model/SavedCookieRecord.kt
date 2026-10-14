package com.cookiebox.app.model

import org.json.JSONArray
import org.json.JSONObject

data class SavedCookieRecord(
    val id: String,
    val title: String,
    val domain: String,
    val url: String,
    val cookieJson: String,
    val count: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getCookieList(): List<CookieItem> {
        val list = mutableListOf<CookieItem>()
        try {
            val array = JSONArray(cookieJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    CookieItem(
                        name = obj.optString("name"),
                        value = obj.optString("value"),
                        domain = obj.optString("domain", domain),
                        path = obj.optString("path", "/")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("title", title)
        obj.put("domain", domain)
        obj.put("url", url)
        obj.put("cookieJson", cookieJson)
        obj.put("count", count)
        obj.put("timestamp", timestamp)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): SavedCookieRecord {
            return SavedCookieRecord(
                id = obj.getString("id"),
                title = obj.optString("title", "未命名会话"),
                domain = obj.optString("domain", ""),
                url = obj.optString("url", ""),
                cookieJson = obj.optString("cookieJson", "[]"),
                count = obj.optInt("count", 0),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}
