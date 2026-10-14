package com.cookiebox.app.model

data class CookieItem(
    val name: String,
    val value: String,
    val domain: String = "",
    val path: String = "/",
    val isHttpOnly: Boolean = false,
    val isSecure: Boolean = false
) {
    val keyValuePair: String
        get() = "$name=$value"
}
