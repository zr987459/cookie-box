package com.cookiebox.app.model

data class WebsiteGroup(
    val domain: String,
    val rootDomain: String,
    val accounts: MutableList<SavedCookieRecord> = mutableListOf()
)
