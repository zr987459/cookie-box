package com.cookiebox.app.model

import org.json.JSONArray
import org.json.JSONObject

data class SavedCookieRecord(
    val id: String,
    val title: String,
    val domain: String,
    val url: String,
    var cookieJson: String,
    var count: Int,
    val timestamp: Long = System.currentTimeMillis(),
    var lastCheckTime: Long = 0L,
    var checkStatus: Int = 0, // 0: 未检测, 200: 正常存活, 401: 已失效
    var statusMessage: String = ""
) {
    fun getCookieList(): List<CookieItem> {
        val list = mutableListOf<CookieItem>()
        try {
            val array = JSONArray(cookieJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val isSecure = obj.optBoolean("isSecure", false) ||
                        obj.optString("name").startsWith("__Secure-") ||
                        obj.optString("name").startsWith("__Host-")
                list.add(
                    CookieItem(
                        name = obj.optString("name"),
                        value = obj.optString("value"),
                        domain = obj.optString("domain", domain),
                        path = obj.optString("path", "/"),
                        isSecure = isSecure
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
        obj.put("lastCheckTime", lastCheckTime)
        obj.put("checkStatus", checkStatus)
        obj.put("statusMessage", statusMessage)
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
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                lastCheckTime = obj.optLong("lastCheckTime", 0L),
                checkStatus = obj.optInt("checkStatus", 0),
                statusMessage = obj.optString("statusMessage", "")
            )
        }
    }
}
