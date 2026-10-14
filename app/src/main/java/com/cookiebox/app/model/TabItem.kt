package com.cookiebox.app.model

import android.webkit.WebView

data class TabItem(
    val id: String,
    var title: String = "新标签页",
    var url: String = "",
    var isDesktop: Boolean = false,
    var webView: WebView? = null
)
