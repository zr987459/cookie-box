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
    var webView: WebView? = null,
    var openerId: String? = null, // 由哪个标签的链接/弹窗打开（返回到底时回到它）
    var pendingUrl: String? = null, // 恢复会话时延迟加载：切到该标签才真正载入
    var customName: String = "", // 用户自定义标签名（区分多账号；为空则显示网页标题）
    var isolated: Boolean = true, // T-030：该标签的身份是否真正落在独立 Profile（false = 退回了全局 CookieManager）
    var isolationError: String? = null, // 未隔离的原因，供诊断页显示
    var identityPending: Boolean = false, // T-030：空白标签还没决定用哪个身份（此时 webView 为 null）
    var lastActiveAt: Long = 0L // 最近一次切到该标签的时间，用于「上次用的账号」和标签面板排序
)
