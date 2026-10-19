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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cookiebox.app.R
import com.cookiebox.app.mcp.tools.GetCookies
import com.cookiebox.app.mcp.tools.HttpRequest
import com.cookiebox.app.mcp.tools.ListSessions

/**
 * 本机 MCP 前台服务（ADR-0004 §5.1）：只有用户在设置里开启时才运行；
 * 常驻通知「AI 访问已开启」，点按或按「关闭」一键停止并关掉开关。
 */
class McpService : Service() {

    companion object {
        const val ACTION_STOP = "com.cookiebox.app.mcp.STOP"
        private const val CHANNEL_ID = "mcp_service"
        private const val NOTIF_ID = 4201

        @Volatile var isRunning = false
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile var runningPort = 0
            private set
        @Volatile var runningLan = false
            private set

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, McpService::class.java))
            } catch (e: Exception) {
                lastError = "服务无法启动（${e.javaClass.simpleName}）"
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, McpService::class.java)) } catch (_: Exception) {}
        }
    }

    private var server: McpHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 之后必须先 startForeground，再决定是否停止
        val lan = McpPrefs.isLanEnabled(this)
        val port = McpPrefs.port(this)
        try {
            createChannel()
            val n = buildNotification(port, lan)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // 例如系统重启粘性服务时不允许从后台进入前台
            lastError = "系统不允许在后台启动（${e.javaClass.simpleName}），请打开 CookieBox 重新开启"
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP || !McpPrefs.isEnabled(this)) {
            if (intent?.action == ACTION_STOP) McpPrefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (server == null) {
            try {
                McpTokenStore.get(this)
                val auth = McpAuth(applicationContext, port, lan)
                val tools = listOf(ListSessions(applicationContext), GetCookies(applicationContext), HttpRequest(applicationContext))
                val protocol = McpProtocol(applicationContext, auth, tools)
                server = McpHttpServer(port, lan) { protocol.handle(it) }.also { it.start() }
                isRunning = true
                runningPort = port
                runningLan = lan
                lastError = null
                recordCurrentHosts()
            } catch (e: Exception) {
                server?.stop()
                server = null
                isRunning = false
                lastError = if (e is java.net.BindException) "端口 $port 已被占用，请在设置里换一个端口"
                else "服务启动失败（${e.javaClass.simpleName}）"
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /** 启动时把当前所有标签的域名补进授权候选。 */
    private fun recordCurrentHosts() {
        try {
            val ctx = applicationContext
            McpSessions.snapshotOnMain(ctx).forEach { McpPrefs.recordVisit(ctx, it.profileName, it.url) }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        isRunning = false
        runningPort = 0
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "AI 访问（MCP）", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "AI 访问开启期间常驻，点按即可关闭"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    private fun buildNotification(port: Int, lan: Boolean): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, McpService::class.java).setAction(ACTION_STOP), flags
        )
        val title = if (lan) "AI 访问已开启（局域网）" else "AI 访问已开启"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cookie)
            .setContentTitle(title)
            .setContentText("端口 $port · 点按关闭")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(stopIntent)
            .addAction(0, "关闭", stopIntent)
            .build()
    }
}
