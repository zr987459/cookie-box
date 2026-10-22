package com.cookiebox.app.engine

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 身份（Profile）隔离的唯一入口。MainActivity 建标签、MCP 取 CookieManager 都走这里，规则只有一份。
 *
 * 调用顺序铁律（T-030）：
 *   val wv = WebView(context)
 *   val r = ProfileIsolation.attach(wv, profileName)   // ← 必须是对 wv 的第一个调用
 *   wv.settings / webViewClient / setAcceptThirdPartyCookies ...  // 之后才能碰
 * androidx 文档要求 setProfile「在对 WebView 做任何其他事之前调用（挂到 View 树除外）」。
 * Chromium 目前只在「已导航 / 已 evaluateJavascript / 已 getProfile / 已设过」时抛异常，
 * 但契约以文档为准，不依赖实现细节。
 *
 * 不再静默降级：MULTI_PROFILE 不支持或 setProfile 失败时返回 isolated=false 并记录原因，
 * 由 UI 禁用「新账号」入口并提示用户升级 Android System WebView。
 */
object ProfileIsolation {

    const val DEFAULT_PROFILE = "Profile_Default"
    private const val TAG = "CookieBox/Profile"

    data class Result(
        /** 该标签的 Cookie / Storage 是否落在自己的 Profile 里 */
        val isolated: Boolean,
        val cookieManager: CookieManager,
        /** 未隔离的原因（给诊断页看），隔离成功为 null */
        val error: String?
    )

    /** 本进程里是否有过 setProfile 失败；有则视为「这台机器不能可靠隔离」 */
    @Volatile
    var attachFailed: Boolean = false
        private set

    fun isSupported(): Boolean =
        try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (e: Throwable) { false }

    /** 能否创建一个与默认身份互不串号的新身份 */
    fun canIsolate(): Boolean = isSupported() && !attachFailed

    /**
     * 把 [webView] 绑到 [profileName]。必须在 new WebView 之后立刻调用（见类注释）。
     */
    fun attach(webView: WebView, profileName: String): Result {
        val global = CookieManager.getInstance()
        if (!isSupported()) {
            return Result(false, global, "系统 WebView 不支持 MULTI_PROFILE（需要 110 及以上）")
        }
        return try {
            WebViewCompat.setProfile(webView, profileName)
            val cm = ProfileStore.getInstance().getProfile(profileName)?.cookieManager
                ?: throw IllegalStateException("setProfile 后取不到 Profile：$profileName")
            Result(true, cm, null)
        } catch (e: Throwable) {
            attachFailed = true
            // 只记异常类型与消息（不含任何 Cookie / 账号数据）
            Log.e(TAG, "setProfile($profileName) 失败，标签退回全局 CookieManager：${e.javaClass.simpleName}: ${e.message}")
            Result(false, global, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 按身份名取 CookieManager（不依赖已打开的标签）。
     * - 不支持多 Profile：只有默认身份映射到全局 CookieManager，其他身份返回 null，绝不把它们混进默认身份。
     * - 支持：只取已存在的 Profile；默认身份不存在时允许创建。
     */
    fun cookieManagerFor(profileName: String): CookieManager? {
        if (!isSupported()) {
            return if (profileName == DEFAULT_PROFILE) CookieManager.getInstance() else null
        }
        return try {
            val store = ProfileStore.getInstance()
            store.getProfile(profileName)?.cookieManager
                ?: if (profileName == DEFAULT_PROFILE) store.getOrCreateProfile(profileName).cookieManager else null
        } catch (e: Throwable) {
            Log.e(TAG, "cookieManagerFor($profileName) 失败：${e.javaClass.simpleName}")
            null
        }
    }

    /** 当前系统 WebView 的包名与版本，供诊断与提示文案使用 */
    fun webViewVersion(context: Context): String = try {
        val pkg = WebViewCompat.getCurrentWebViewPackage(context)
        if (pkg == null) "未知" else "${pkg.packageName} ${pkg.versionName}"
    } catch (e: Throwable) {
        "未知"
    }

    const val UNSUPPORTED_TIP =
        "系统 WebView 版本过低，分身无法隔离，请升级 Android System WebView（应用商店搜索「Android System WebView」）。"
}
