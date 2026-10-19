package com.cookiebox.app.mcp

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cookiebox.app.databinding.ActivityMcpSettingsBinding
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * AI 访问（MCP）设置页（T-026）：总开关、连接地址、Token（显示 / 复制 / 重置）、端口、局域网开关、Origin 白名单。
 * Token 只在用户点「显示」时出现在屏幕上；复制时剪贴板标记为敏感；任何地方都不打日志。
 */
class McpSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMcpSettingsBinding
    private lateinit var prefs: McpPrefs
    private var tokenVisible = false
    private var updatingUi = false

    private val stateListener: () -> Unit = { render() }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMcpSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = McpPrefs(this)

        binding.btnBack.setOnClickListener { finish() }

        binding.switchEnable.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked) enable() else McpService.stop(this)
            render()
        }

        binding.btnShowToken.setOnClickListener {
            tokenVisible = !tokenVisible
            render()
        }
        binding.btnCopyToken.setOnClickListener {
            val token = McpTokenStore.current(this) ?: return@setOnClickListener toast("请先开启 AI 访问")
            copySensitive("CookieBox MCP Token", token, "已复制令牌")
        }
        binding.btnResetToken.setOnClickListener { confirmResetToken() }
        binding.btnCopyConfig.setOnClickListener { copyClientConfig() }

        binding.btnSavePort.setOnClickListener { savePort() }

        binding.switchLan.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked) confirmLan() else {
                prefs.lanEnabled = false
                McpService.restart(this)
                render()
            }
        }

        binding.btnSaveOrigins.setOnClickListener {
            val list = binding.etOrigins.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            val bad = list.firstOrNull { !it.contains("://") && it != "null" }
            if (bad != null) return@setOnClickListener toast("格式不对：$bad（需要 scheme://host:port）")
            prefs.originAllowlist = list.toSet()
            toast(if (list.isEmpty()) "已清空，所有带 Origin 的请求都会被拒绝" else "已保存 ${list.size} 个来源")
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        // 服务被系统或用户强行停止后，开关与实际状态对齐
        val starting = System.currentTimeMillis() - McpService.startRequestedAt < 10_000
        if (prefs.enabled && !McpService.isRunning && !starting) prefs.enabled = false
        McpService.addListener(stateListener)
        binding.etPort.setText(prefs.port.toString())
        binding.etOrigins.setText(prefs.originAllowlist.sorted().joinToString("\n"))
        render()
    }

    override fun onPause() {
        McpService.removeListener(stateListener)
        super.onPause()
    }

    private fun enable() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 每次开启都生成新 Token（ADR-0004 §5.3）
        McpTokenStore.regenerate(this)
        tokenVisible = false
        prefs.enabled = true
        McpService.start(this)
        if (!McpTokenStore.persisted) toast("系统密钥库不可用：令牌只保存在内存里，应用被关闭后需重新开启")
    }

    private fun render() {
        updatingUi = true
        val running = McpService.isRunning
        val enabled = prefs.enabled
        binding.switchEnable.isChecked = enabled || running
        binding.switchLan.isChecked = prefs.lanEnabled

        val port = prefs.port
        binding.tvStatus.text = when {
            running && prefs.lanEnabled -> "运行中 · 局域网可访问 · 端口 $port"
            running -> "运行中 · 仅本机 · 端口 $port"
            enabled -> "正在启动…"
            McpService.lastError != null -> "已关闭：${McpService.lastError}"
            else -> "已关闭，端口未监听"
        }

        val endpoints = mutableListOf("http://127.0.0.1:$port${McpGateway.PATH}")
        if (prefs.lanEnabled) lanIpv4().forEach { endpoints.add("http://$it:$port${McpGateway.PATH}") }
        binding.tvEndpoint.text = endpoints.joinToString("\n")

        val token = McpTokenStore.current(this)
        binding.tvToken.text = when {
            !(enabled || running) || token == null -> "开启后生成"
            tokenVisible -> token
            else -> "•".repeat(12) + "（${token.length} 位）"
        }
        binding.btnShowToken.text = if (tokenVisible) "隐藏" else "显示"
        val hasToken = token != null && (enabled || running)
        binding.btnShowToken.isEnabled = hasToken
        binding.btnCopyToken.isEnabled = hasToken
        binding.btnResetToken.isEnabled = hasToken
        binding.btnCopyConfig.isEnabled = hasToken
        updatingUi = false
    }

    private fun confirmResetToken() {
        AlertDialog.Builder(this)
            .setTitle("重置令牌？")
            .setMessage("旧令牌立即失效，已连接的 AI 客户端需要换成新令牌。")
            .setPositiveButton("重置") { _, _ ->
                McpTokenStore.regenerate(this)
                tokenVisible = false
                toast("已重置令牌")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmLan() {
        AlertDialog.Builder(this)
            .setTitle("允许局域网访问？")
            .setMessage(
                "开启后，同一 Wi-Fi 或热点下的其他设备可以尝试连接这个端口。\n\n" +
                    "• 连接是 HTTP 明文，令牌可能被同一网络里的人截获；\n" +
                    "• 拿到令牌的人可以使用你已授权的网站登录态；\n" +
                    "• 只在自己家里等可信网络使用，用完请关闭。"
            )
            .setPositiveButton("我了解风险，开启") { _, _ ->
                prefs.lanEnabled = true
                McpService.restart(this)
                render()
            }
            .setNegativeButton("取消") { _, _ -> render() }
            .setOnCancelListener { render() }
            .show()
    }

    private fun savePort() {
        val p = binding.etPort.text.toString().trim().toIntOrNull()
        if (p == null || p !in McpPrefs.MIN_PORT..McpPrefs.MAX_PORT) {
            toast("端口需在 ${McpPrefs.MIN_PORT}–${McpPrefs.MAX_PORT} 之间")
            return
        }
        prefs.port = p
        McpService.restart(this)
        toast("已保存端口 $p")
        render()
    }

    private fun copyClientConfig() {
        val token = McpTokenStore.current(this) ?: return toast("请先开启 AI 访问")
        val config = JSONObject().put(
            "mcpServers",
            JSONObject().put(
                "cookiebox",
                JSONObject()
                    .put("type", "http")
                    .put("url", "http://127.0.0.1:${prefs.port}${McpGateway.PATH}")
                    .put("headers", JSONObject().put("Authorization", "Bearer $token"))
            )
        )
        copySensitive("CookieBox MCP 配置", config.toString(2), "已复制客户端配置（含令牌）")
    }

    private fun copySensitive(label: String, text: String, okMsg: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            else putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
        toast(okMsg)
    }

    private fun lanIpv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (e: Exception) {
        emptyList()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
