package com.cookiebox.app.model

data class TabGroup(
    val domain: String,
    val rootDomain: String,
    val tabs: MutableList<TabItem> = mutableListOf(),
    var isExpanded: Boolean = true
)
