package com.cookiebox.app.model

import org.json.JSONObject

/**
 * 网站账号凭据（账号 + 密码 + 备注），密码以 Android Keystore 加密后存储
 */
data class AccountCredential(
    val id: String,
    val domain: String,          // 根域名，如 bilibili.com
    val loginUrl: String = "",   // 登录页或网址
    val username: String,
    val encryptedPassword: String = "",
    val note: String = "",
    val linkedCookieId: String? = null, // 绑定的 Cookie 储存库记录，用于一键免密
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("domain", domain)
        put("loginUrl", loginUrl)
        put("username", username)
        put("encryptedPassword", encryptedPassword)
        put("note", note)
        put("linkedCookieId", linkedCookieId ?: "")
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = AccountCredential(
            id = o.getString("id"),
            domain = o.optString("domain"),
            loginUrl = o.optString("loginUrl"),
            username = o.optString("username"),
            encryptedPassword = o.optString("encryptedPassword"),
            note = o.optString("note"),
            linkedCookieId = o.optString("linkedCookieId").ifBlank { null },
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}
