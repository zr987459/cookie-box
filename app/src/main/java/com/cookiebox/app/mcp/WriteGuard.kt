package com.cookiebox.app.mcp

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cookiebox.app.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 写操作确认（ADR-0004 §5.4）。三档：每次确认 / 信任的站点免确认（默认）/ 全部免确认。
 * 「信任的站点免确认」下，信任的站点以及本次会话里已放行的「身份 × 站点」不再询问。
 * 需要确认时：应用在前台直接弹确认框，否则发高优先级通知点按进入；60 s 无响应视为拒绝。
 * 确认框只显示工具名、标签名 / 身份、目标站点、摘要（方法 + 路径等），不显示 Cookie 与请求头值。
 */
object WriteGuard {

    private const val TAG = "CookieBoxMcp"
    const val TIMEOUT_MS = 60_000L
    private const val CHANNEL_ID = "mcp_confirm"
    private const val NOTIFICATION_BASE = 0x4D5000

    class Request(
        val id: Long,
        val tool: String,
        val label: String,
        val profileName: String,
        val host: String,
        /** 「信任」或「本次会话免确认」记到哪个域名上（匹配到的授权域名） */
        val siteDomain: String,
        val summary: String,
        val sessionKey: String,
        val createdAt: Long,
        val offerRemember: Boolean
    ) {
        internal val latch = CountDownLatch(1)
        @Volatile internal var allowed = false
        val deadline: Long get() = createdAt + TIMEOUT_MS
        internal fun isOpen(): Boolean = latch.count > 0 && System.currentTimeMillis() < deadline
    }

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Request>()
    /** 本次会话免确认：sessionKey|profile|domain */
    private val sessionAllow = ConcurrentHashMap.newKeySet<String>()
    private val startedActivities = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null
    @Volatile private var callbacksRegistered = false

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!callbacksRegistered && app is Application) {
            callbacksRegistered = true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) { startedActivities.incrementAndGet() }
                override fun onActivityStopped(activity: Activity) { startedActivities.updateAndGet { maxOf(0, it - 1) } }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }
    }

    /**
     * 写操作前调用（HTTP 工作线程）。放行则返回；拒绝或超时抛 [McpDeniedException]。
     * @param mcpSessionId 客户端的 Mcp-Session-Id；没有时按「本次服务运行」计
     */
    fun check(
        context: Context,
        tool: String,
        label: String,
        profileName: String,
        host: String,
        summary: String,
        mcpSessionId: String?
    ) {
        val prefs = McpPrefs(context)
        val mode = prefs.confirmMode
        if (mode == McpPrefs.CONFIRM_NEVER) return

        val siteDomain = McpGrants.authorizedDomains(context, profileName)
            .filter { McpGrants.hostMatches(host, it) }
            .maxByOrNull { it.length }
            ?: McpGrants.registrableDomain(host)
        val sessionKey = mcpSessionId ?: "service"
        if (mode == McpPrefs.CONFIRM_TRUSTED_SKIP) {
            if (McpGrants.isTrusted(context, profileName, host)) return
            if (sessionAllow.contains(allowKey(sessionKey, profileName, siteDomain))) return
        }

        val req = Request(
            id = nextId.getAndIncrement(),
            tool = tool,
            label = label,
            profileName = profileName,
            host = host,
            siteDomain = siteDomain,
            summary = summary.take(200),
            sessionKey = sessionKey,
            createdAt = System.currentTimeMillis(),
            offerRemember = mode == McpPrefs.CONFIRM_TRUSTED_SKIP
        )
        pending[req.id] = req
        try {
            show(context.applicationContext, req)
            val answered = req.latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!answered) throw McpDeniedException("用户在 60 秒内没有确认，已拒绝这次写操作")
            if (!req.allowed) throw McpDeniedException("用户拒绝了这次写操作")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw McpDeniedException("确认被中断，已拒绝")
        } finally {
            pending.remove(req.id)
            cancelNotification(context.applicationContext, req.id)
        }
    }

    fun get(id: Long): Request? = pending[id]?.takeIf { it.isOpen() }

    /** 最早的待确认请求 */
    fun firstPending(): Request? =
        pending.values.filter { it.isOpen() }.minByOrNull { it.createdAt }

    /**
     * 用户在确认框里做出选择（主线程）。
     * @param rememberSession 本次会话内该身份 × 站点不再询问
     * @param trustSite 写入 grants 的信任标记，以后不再询问（可在站点授权里取消）
     */
    fun resolve(context: Context, id: Long, allow: Boolean, rememberSession: Boolean, trustSite: Boolean) {
        val req = pending[id]?.takeIf { it.isOpen() } ?: return
        if (allow && req.offerRemember) {
            if (trustSite) McpGrants.setTrusted(context, req.profileName, req.siteDomain, true)
            if (rememberSession) sessionAllow.add(allowKey(req.sessionKey, req.profileName, req.siteDomain))
        }
        req.allowed = allow
        req.latch.countDown()
    }

    fun endSession(sessionId: String) {
        sessionAllow.removeAll { it.startsWith("$sessionId|") }
    }

    fun resetSessions() {
        sessionAllow.clear()
    }

    fun denyAllPending() {
        pending.values.forEach { it.allowed = false; it.latch.countDown() }
    }

    private fun allowKey(sessionKey: String, profile: String, domain: String) = "$sessionKey|$profile|$domain"

    private fun show(context: Context, req: Request) {
        val intent = Intent(context, McpConfirmActivity::class.java)
            .putExtra(McpConfirmActivity.EXTRA_ID, req.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (startedActivities.get() > 0) {
            mainHandler.post {
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "confirm activity failed: ${e.javaClass.simpleName}")
                }
            }
        }
        postNotification(context, req, intent)
    }

    private fun postNotification(context: Context, req: Request, intent: Intent) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "AI 写操作确认", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "AI 客户端请求提交、修改或删除时请你确认"
                        setShowBadge(false)
                    }
                )
            }
            val pi = PendingIntent.getActivity(
                context, req.id.toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cookie)
                .setContentTitle("AI 请求写操作：${req.host}")
                .setContentText("${req.label} · ${req.tool} · 点按确认（60 秒内）")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setTimeoutAfter(TIMEOUT_MS)
                .setContentIntent(pi)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + (req.id % 1000).toInt(), n)
        } catch (e: SecurityException) {
            // 没有通知权限：只能依赖前台弹窗
        } catch (e: Exception) {
            Log.w(TAG, "confirm notification failed: ${e.javaClass.simpleName}")
        }
    }

    private fun cancelNotification(context: Context, id: Long) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_BASE + (id % 1000).toInt())
        } catch (e: Exception) {
            // 忽略
        }
    }
}
