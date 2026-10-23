package com.cookiebox.app.mcp

import android.app.Activity
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.cookiebox.app.engine.BrowserPrefs
import com.cookiebox.app.model.TabItem
import java.lang.ref.WeakReference

/** 标签只读快照：不含 WebView / CookieManager 引用（ADR-0004 §4.2） */
data class SessionInfo(
    val id: String,
    val customName: String,
    val title: String,
    val url: String,
    val host: String,
    val isClone: Boolean,
    val isDesktop: Boolean,
    val profileName: String,
    /** 页面已真正加载（不是等待切换才加载的恢复标签） */
    val loaded: Boolean,
    /** 来自 browser_prefs 的只读恢复数据（MainActivity 不在时） */
    val fromSavedSession: Boolean
)

/** 主线程内可用的标签句柄；只能在 [TabRegistry.onMain] 的 block 里使用 */
class TabHandle(
    val id: String,
    val profileName: String,
    val url: String,
    val webView: WebView?,
    val userAgent: String,
    val cookieManager: CookieManager?
) {
    val loaded: Boolean get() = webView != null && !webView.url.isNullOrBlank() && webView.url != "about:blank"
}

interface TabAccess {
    fun tab(id: String): TabHandle?
    /** 该身份的 CookieManager；身份已不存在时返回 null */
    fun cookieManagerFor(profileName: String): CookieManager?
}

interface TabRegistry {
    fun snapshot(): List<SessionInfo>
    fun <T> onMain(timeoutMs: Long = MainThreadRunner.DEFAULT_TIMEOUT_MS, block: (TabAccess) -> T): T
}

class McpSessionExpiredException(message: String) : Exception(message)

/**
 * TabRegistry 的唯一实现。MainActivity 存活时读它的标签列表；
 * Activity 被销毁而服务仍在时，退化为 browser_prefs 的只读会话（MCP 代码绝不写 browser_prefs）。
 */
object McpTabs : TabRegistry {

    private const val DEFAULT_PROFILE = "Profile_Default"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var activityRef: WeakReference<Activity>? = null
    @Volatile
    private var tabsProvider: (() -> List<TabItem>)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** MainActivity.onCreate 调用 */
    fun attach(activity: Activity, provider: () -> List<TabItem>) {
        appContext = activity.applicationContext
        // 尽早开始统计前台 Activity，写确认据此决定直接弹框还是只发通知
        WriteGuard.init(activity)
        activityRef = WeakReference(activity)
        tabsProvider = provider
    }

    /** MainActivity.onDestroy 调用 */
    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
            tabsProvider = null
        }
    }

    /** 主框架导航完成（MainActivity 的 onPageFinished 调用，主线程） */
    fun onPageFinished(profileName: String, url: String) {
        val ctx = appContext ?: return
        McpGrants.recordVisit(ctx, profileName, url)
    }

    /** 服务启动时把当前所有标签的站点补进 visited_hosts（主线程） */
    fun seedVisited() {
        val ctx = appContext ?: return
        val live = liveTabs() ?: return
        live.forEach { t ->
            val url = t.webView?.url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: t.pendingUrl ?: t.url
            if (url.isNotBlank()) McpGrants.recordVisit(ctx, t.profileName, url)
        }
    }

    private fun liveTabs(): List<TabItem>? {
        val a = activityRef?.get() ?: return null
        if (a.isDestroyed) return null
        return tabsProvider?.invoke()
    }

    override fun snapshot(): List<SessionInfo> = MainThreadRunner.run {
        val live = liveTabs()
        if (live != null) {
            live.map { t ->
                val url = t.webView?.url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: t.pendingUrl ?: t.url
                SessionInfo(
                    id = t.id,
                    customName = t.customName,
                    title = t.title,
                    url = url,
                    host = McpGrants.hostOf(url) ?: "",
                    isClone = t.isClone,
                    isDesktop = t.isDesktop,
                    profileName = t.profileName,
                    loaded = t.pendingUrl == null && !t.webView?.url.isNullOrBlank() && t.webView?.url != "about:blank",
                    fromSavedSession = false
                )
            }
        } else {
            savedSnapshot()
        }
    }

    private fun savedSnapshot(): List<SessionInfo> {
        val ctx = appContext ?: return emptyList()
        val (saved, _) = BrowserPrefs(ctx).loadSession()
        return saved.mapIndexed { i, s ->
            SessionInfo(
                id = "saved-$i",
                customName = s.customName,
                title = s.title,
                url = s.url,
                host = McpGrants.hostOf(s.url) ?: "",
                isClone = s.isClone,
                isDesktop = s.isDesktop,
                profileName = s.profileName,
                loaded = false,
                fromSavedSession = true
            )
        }
    }

    override fun <T> onMain(timeoutMs: Long, block: (TabAccess) -> T): T = MainThreadRunner.run(timeoutMs) {
        block(access)
    }

    /**
     * 在标签页面里执行脚本并等待 evaluateJavascript 回调（从 HTTP 线程调用）。
     * 返回回调的原始字符串（JSON 编码）；标签已关闭或页面未加载时抛 [McpSessionExpiredException]。
     */
    fun evalJs(tabId: String, script: String, timeoutMs: Long = 5_000L): String? =
        MainThreadRunner.await<String?>(timeoutMs) { done ->
            val wv = access.tab(tabId)?.webView ?: throw McpSessionExpiredException("标签已关闭或页面未加载")
            wv.evaluateJavascript(script) { r -> done(r) }
        }

    private val access = object : TabAccess {
        override fun tab(id: String): TabHandle? {
            val live = liveTabs()
            if (live != null) {
                val t = live.firstOrNull { it.id == id } ?: return null
                val wv = t.webView
                return TabHandle(
                    id = t.id,
                    profileName = t.profileName,
                    url = wv?.url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: t.pendingUrl ?: t.url,
                    webView = if (t.pendingUrl == null) wv else null,
                    userAgent = wv?.settings?.userAgentString ?: defaultUa(t.isDesktop),
                    cookieManager = t.customCookieManager ?: cookieManagerFor(t.profileName)
                )
            }
            val s = savedSnapshot().firstOrNull { it.id == id } ?: return null
            return TabHandle(
                id = s.id,
                profileName = s.profileName,
                url = s.url,
                webView = null,
                userAgent = defaultUa(s.isDesktop),
                cookieManager = cookieManagerFor(s.profileName)
            )
        }

        override fun cookieManagerFor(profileName: String): CookieManager? =
            // T-030：与 MainActivity 共用同一条规则——不支持多 Profile 时只有默认身份映射到全局，
            // 其他身份返回 null，绝不把分身的请求混进默认身份
            com.cookiebox.app.engine.ProfileIsolation.cookieManagerFor(profileName)
    }

    private fun defaultUa(desktop: Boolean): String {
        if (desktop) return DESKTOP_UA
        val ctx = appContext ?: return DESKTOP_UA
        return try {
            WebSettings.getDefaultUserAgent(ctx).replace("; wv", "").replace("Version/4.0 ", "")
        } catch (e: Exception) {
            DESKTOP_UA
        }
    }
}
