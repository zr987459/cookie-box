package com.cookiebox.app.model

import android.webkit.WebView

data class TabItem(
    val id: String,
    var title: String = "新标签页",
    var url: String = "",
    var isDesktop: Boolean = false,
    var isIsolated: Boolean = false, // 是否为独立多开标签（多账号登录隔离）
    var savedCookies: Map<String, String> = emptyMap(), // 该标签保存的 Cookie 快照
    var webView: WebView? = null
)
