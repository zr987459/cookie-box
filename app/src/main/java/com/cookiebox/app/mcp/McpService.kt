package com.cookiebox.app.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cookiebox.app.R
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 本机 MCP 服务（ADR-0004）。
 * - 只有 mcp_prefs.enabled 为 true 时运行；关闭即关闭 ServerSocket，端口不再监听。
 * - 前台服务（specialUse），常驻通知「AI 访问已开启」，点按通知即一键关闭。
 * - 默认只绑定 127.0.0.1；局域网开关打开时绑定 0.0.0.0（仍需 Token，Host 校验放行本机网卡地址）。
 */
class McpService : Service() {

    companion object {
        private const val TAG = "CookieBoxMcp"
        const val ACTION_START = "com.cookiebox.app.mcp.action.START"
        const val ACTION_STOP = "com.cookiebox.app.mcp.action.STOP"
        const val ACTION_RESTART = "com.cookiebox.app.mcp.action.RESTART"

        private const val CHANNEL_ID = "mcp_service"
        private const val NOTIFICATION_ID = 0x4D4350 // "MCP"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 最近一次启动失败的原因（如端口被占用），给设置页展示；不含任何敏感值 */
        @Volatile
        var lastError: String? = null
            private set

        /** 最近一次请求启动的时间；设置页据此避免在启动途中把开关误判为「已停止」 */
        @Volatile
        var startRequestedAt: Long = 0L
            private set

        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }
        private fun notifyChanged() { mainHandler.post { listeners.forEach { it() } } }

        /** 从前台界面开启：调用方须先把 enabled 设为 true 并生成 Token */
        fun start(context: Context) {
            startRequestedAt = System.currentTimeMillis()
            lastError = null
            val i = Intent(context, McpService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, i)
        }

        /** 端口 / 局域网开关变更后重启监听（仅在运行中有效） */
        fun restart(context: Context) {
            if (!isRunning) return
            startRequestedAt = System.currentTimeMillis()
            val i = Intent(context, McpService::class.java).setAction(ACTION_RESTART)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            McpPrefs(context).enabled = false
            if (!isRunning) {
                context.stopService(Intent(context, McpService::class.java))
                notifyChanged()
                return
            }
            val i = Intent(context, McpService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(i)
            } catch (e: IllegalStateException) {
                context.stopService(Intent(context, McpService::class.java))
            }
        }
    }

    private var server: McpHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = McpPrefs(this)
        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            shutdown()
            return START_NOT_STICKY
        }
        // ACTION_START / ACTION_RESTART / 系统重建（intent 为 null）
        if (!promoteToForeground(prefs.lanEnabled)) {
            // 系统重建时不允许再进前台（Android 12+ 后台限制）等：放弃并关闭开关，保持状态一致
            prefs.enabled = false
            shutdown()
            return START_NOT_STICKY
        }
        if (!prefs.enabled) {
            shutdown()
            return START_NOT_STICKY
        }
        if (McpTokenStore.current(this) == null) {
            // Token 丢失（Keystore 不可用且进程被杀）：不能无 Token 运行
            lastError = "Token 丢失，请重新开启"
            prefs.enabled = false
            shutdown()
            return START_NOT_STICKY
        }
        startServer(prefs)
        return START_STICKY
    }

    private fun startServer(prefs: McpPrefs) {
        server?.stop()
        server = null
        val port = prefs.port
        val lan = prefs.lanEnabled
        val bind = if (lan) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")
        val auth = McpAuth(
            port = port,
            lanEnabled = lan,
            tokenProvider = { McpTokenStore.current(applicationContext) },
            originAllowlist = { McpPrefs(applicationContext).originAllowlist }
        )
        McpTabs.init(applicationContext)
        McpTabs.seedVisited()
        WriteGuard.init(applicationContext)
        WriteGuard.resetSessions()
        val protocol = McpProtocol(appVersion(), McpToolRegistry.tools(applicationContext)) { entry ->
            CallLog.add(applicationContext, entry)
        }
        val gateway = McpGateway(auth, protocol)
        val s = McpHttpServer(bind, port, gateway::handle)
        try {
            s.start()
            server = s
            isRunning = true
            lastError = null
            updateNotification(lan, port)
            Log.i(TAG, "listening lan=$lan port=$port")
        } catch (e: IOException) {
            Log.w(TAG, "bind failed port=$port: ${e.javaClass.simpleName}")
            lastError = "端口 $port 无法监听（可能被占用），请换一个端口"
            prefs.enabled = false
            shutdown()
            return
        }
        notifyChanged()
    }

    private fun shutdown() {
        WriteGuard.denyAllPending()
        WriteGuard.resetSessions()
        server?.stop()
        server = null
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyChanged()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        isRunning = false
        notifyChanged()
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun promoteToForeground(lan: Boolean): Boolean {
        return try {
            ensureChannel()
            val n = buildNotification(lan, McpPrefs(this).port)
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException 等
            Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}")
            lastError = "系统不允许在后台开启，请在应用内重新打开开关"
            false
        }
    }

    private fun updateNotification(lan: Boolean, port: Int) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(lan, port))
        } catch (e: SecurityException) {
            // 未授予通知权限：前台服务仍在运行，只是通知不可见
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI 访问（MCP）", NotificationManager.IMPORTANCE_LOW).apply {
                description = "AI 访问开启期间常驻，点按即可关闭"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(lan: Boolean, port: Int): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, McpService::class.java).setAction(ACTION_STOP), piFlags
        )
        val settingsIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, McpSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags
        )
        val title = if (lan) "AI 访问已开启（局域网）" else "AI 访问已开启"
        val where = if (lan) "局域网与本机 · 端口 $port" else "仅本机 127.0.0.1:$port"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText("$where · 点按关闭")
            .setContentIntent(stopIntent)
            .addAction(0, "关闭", stopIntent)
            .addAction(0, "设置", settingsIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
