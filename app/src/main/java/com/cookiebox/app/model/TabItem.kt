package com.cookiebox.app.model

import android.webkit.CookieManager
import android.webkit.WebView

data class TabItem(
    val id: String,
    var title: String = "新标签页",
    var url: String = "",
    var isDesktop: Boolean = false,
    var isClone: Boolean = false, // 是否为独立网页分身标签（独立账号登录）
    var profileName: String = "Profile_Default", // 独立 Profile 容器名
    var customCookieManager: CookieManager? = null,
    var webView: WebView? = null
)
