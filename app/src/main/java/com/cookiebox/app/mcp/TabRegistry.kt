package com.cookiebox.app.mcp

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.cookiebox.app.engine.BrowserPrefs
import java.lang.ref.WeakReference

/** 标签只读快照，不含 WebView / CookieManager 引用（ADR-0004 §4.2）。 */
data class SessionInfo(
    val id: String,
    val customName: String,
    val title: String,
    val url: String,
    val isClone: Boolean,
    val profileName: String,
    val isDesktop: Boolean,
    /** 标签的 WebView 已创建并载入（非延迟加载状态） */
    val live: Boolean
) {
    val host: String get() = try { Uri.parse(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
}

/** 由 MainActivity 实现；所有方法只在主线程调用。 */
interface TabRegistry {
    fun snapshot(): List<SessionInfo>
    fun webViewFor(sessionId: String): WebView?
}

object TabRegistryHolder {
    @Volatile private var ref: WeakReference<TabRegistry>? = null

    fun register(r: TabRegistry) { ref = WeakReference(r) }
    fun unregister(r: TabRegistry) { if (ref?.get() === r) ref = null }
    fun get(): TabRegistry? = ref?.get()
}

/** MCP 侧访问标签与授权的工具函数。除注明外可在工作线程调用。 */
object McpSessions {

    /** 当前标签快照；Activity 不在时退化为上次保存的会话（只读，绝不写 browser_prefs）。 */
    fun snapshot(ctx: Context): List<SessionInfo> = MainThreadRunner.run { snapshotOnMain(ctx) }

    /** 主线程调用。 */
    fun snapshotOnMain(ctx: Context): List<SessionInfo> {
        TabRegistryHolder.get()?.let { return it.snapshot() }
        val (saved, _) = BrowserPrefs(ctx).loadSession()
        return saved.mapIndexed { i, t ->
            SessionInfo("saved-$i", t.customName, t.title, t.url, t.isClone, t.profileName, t.isDesktop, live = false)
        }
    }

    fun find(ctx: Context, sessionId: String): SessionInfo =
        snapshot(ctx).firstOrNull { it.id == sessionId }
            ?: throw ToolError("session_id 无效或已失效（应用重启后标签 id 会变），请重新调用 list_sessions")

    /** 该身份被授权的域名集合（ADR-0004 §5.5）。 */
    fun allowedDomains(ctx: Context, profileName: String): Set<String> {
        val g = McpPrefs.grants(ctx)
        val pg = g.profiles[profileName]
        if (g.all || pg?.all == true) {
            val set = linkedSetOf<String>()
            McpPrefs.visitedHosts(ctx)[profileName]?.let { set.addAll(it) }
            pg?.manual?.let { set.addAll(it) }
            pg?.domains?.keys?.let { set.addAll(it) }
            return set
        }
        return pg?.domains?.keys?.toSet() ?: emptySet()
    }

    fun hostAllowed(host: String, domains: Set<String>): Boolean {
        val h = host.lowercase().trim('.')
        if (h.isEmpty()) return false
        return domains.any { d -> h == d || h.endsWith(".$d") }
    }

    /** 校验 URL：http/https 且主机属于该身份的授权域名，返回小写主机名。 */
    fun requireAllowedUrl(ctx: Context, profileName: String, url: String): String {
        val uri = try { Uri.parse(url) } catch (e: Exception) { null }
        val scheme = uri?.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw ToolError("只支持 http / https 网址")
        val host = uri?.host?.lowercase() ?: throw ToolError("网址缺少主机名")
        if (!hostAllowed(host, allowedDomains(ctx, profileName))) {
            throw ToolError("未授权：该标签所属身份没有授权访问 $host。请让用户在 CookieBox「AI 访问（MCP）→ 站点授权」里勾选")
        }
        return host
    }

    fun profileLabel(s: SessionInfo): String =
        if (!s.isClone) "默认身份" else "分身 ${s.profileName.removePrefix("Profile_Clone_")}"
}

/** 主线程调用：取某身份的 CookieManager（不支持多 Profile 的设备上所有身份共用全局 CookieManager）。 */
object ProfileCookies {
    fun managerFor(profileName: String): CookieManager {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                return ProfileStore.getInstance().getOrCreateProfile(profileName).cookieManager
            } catch (e: Exception) {
                // 退回全局
            }
        }
        return CookieManager.getInstance()
    }
}
