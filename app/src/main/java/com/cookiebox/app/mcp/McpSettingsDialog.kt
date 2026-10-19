package com.cookiebox.app.mcp

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/** 设置入口：开关、局域网、Token、站点授权、端口与 Origin 白名单（ADR-0004 §5）。 */
object McpSettingsDialog {

    fun show(act: Activity) {
        val ctx = act
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 8), dp(ctx, 24), dp(ctx, 8))
        }
        val intro = TextView(ctx).apply {
            text = "开启后，你自己的 AI 客户端（Claude、Cursor 等，需在本机或通过 adb 端口转发连接）可以用你授权的标签身份访问网站。默认只监听本机。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val status = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
        }
        val enable = SwitchCompat(ctx).apply {
            text = "开启 AI 访问（MCP）"
            isChecked = McpPrefs.isEnabled(ctx)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(ctx, 12), 0, 0)
        }
        val lan = SwitchCompat(ctx).apply {
            text = "允许局域网访问（有风险）"
            isChecked = McpPrefs.isLanEnabled(ctx)
            setPadding(0, dp(ctx, 8), 0, 0)
        }

        val handler = Handler(Looper.getMainLooper())
        fun refresh() {
            val port = McpPrefs.port(ctx)
            status.text = when {
                McpService.isRunning -> buildString {
                    append("● 运行中\n地址：http://127.0.0.1:${McpService.runningPort}/mcp")
                    if (McpService.runningLan) {
                        McpAuth.lanAddresses().forEach { append("\n局域网：http://$it:${McpService.runningPort}/mcp") }
                    }
                }
                McpPrefs.isEnabled(ctx) && McpService.lastError != null -> "启动失败：${McpService.lastError}"
                McpPrefs.isEnabled(ctx) -> "正在启动…（端口 $port）"
                else -> "○ 已关闭（端口未监听）"
            }
        }
        fun refreshSoon() {
            refresh()
            handler.postDelayed({ refresh() }, 600)
            handler.postDelayed({ refresh() }, 1500)
        }

        enable.setOnCheckedChangeListener { _, on ->
            if (on) {
                try {
                    McpTokenStore.get(ctx)
                } catch (e: Exception) {
                    toast(ctx, "无法生成访问密钥（Keystore 不可用）")
                    enable.isChecked = false
                    return@setOnCheckedChangeListener
                }
                McpPrefs.setEnabled(ctx, true)
                McpService.start(ctx)
            } else {
                McpPrefs.setEnabled(ctx, false)
                McpService.stop(ctx)
            }
            refreshSoon()
        }

        lan.setOnCheckedChangeListener { _, on ->
            if (on && !McpPrefs.isLanEnabled(ctx)) {
                AlertDialog.Builder(ctx)
                    .setTitle("开启局域网访问？")
                    .setMessage("同一 Wi-Fi 下的其他设备可以尝试连接这个端口。HTTP 是明文传输，访问密钥可能被同网段的人截获。只在你信任的网络里开启，用完记得关掉。")
                    .setPositiveButton("仍然开启") { _, _ ->
                        McpPrefs.setLanEnabled(ctx, true)
                        restartIfRunning(ctx)
                        refreshSoon()
                    }
                    .setNegativeButton("取消") { _, _ -> lan.isChecked = false }
                    .setOnCancelListener { lan.isChecked = false }
                    .show()
            } else if (!on && McpPrefs.isLanEnabled(ctx)) {
                McpPrefs.setLanEnabled(ctx, false)
                restartIfRunning(ctx)
                refreshSoon()
            }
        }

        val btnConfig = button(ctx, "复制客户端配置（含密钥）") {
            val token = tokenOrToast(ctx) ?: return@button
            val url = "http://127.0.0.1:${McpPrefs.port(ctx)}/mcp"
            val cfg = JSONObject().put("mcpServers", JSONObject().put("cookiebox", JSONObject()
                .put("type", "http")
                .put("url", url)
                .put("headers", JSONObject().put("Authorization", "Bearer $token"))))
            copySensitive(ctx, cfg.toString(2), "已复制客户端配置")
        }
        val btnToken = button(ctx, "复制访问密钥（Token）") {
            val token = tokenOrToast(ctx) ?: return@button
            copySensitive(ctx, token, "已复制访问密钥")
        }
        val btnReset = button(ctx, "重置访问密钥") {
            AlertDialog.Builder(ctx)
                .setTitle("重置访问密钥？")
                .setMessage("旧密钥立即失效，已配置的 AI 客户端需要换成新密钥。")
                .setPositiveButton("重置") { _, _ ->
                    try {
                        McpTokenStore.reset(ctx)
                        toast(ctx, "已重置，旧密钥已失效")
                    } catch (e: Exception) {
                        toast(ctx, "重置失败（Keystore 不可用）")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        val btnGrants = button(ctx, "站点授权…") { McpGrantsDialog.show(act) }
        val btnAdvanced = button(ctx, "端口与 Origin 白名单…") { showAdvanced(act) { refreshSoon() } }

        root.addView(intro)
        root.addView(enable)
        root.addView(status)
        root.addView(btnGrants)
        root.addView(btnConfig)
        root.addView(btnToken)
        root.addView(btnReset)
        root.addView(lan)
        root.addView(btnAdvanced)

        refresh()
        AlertDialog.Builder(ctx)
            .setTitle("AI 访问（MCP）")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton("完成", null)
            .show()
    }

    private fun showAdvanced(act: Activity, onSaved: () -> Unit) {
        val ctx = act
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 8), dp(ctx, 24), 0)
        }
        val portLabel = TextView(ctx).apply { text = "端口（1024–65535）" }
        val portInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(McpPrefs.port(ctx).toString())
        }
        val originLabel = TextView(ctx).apply {
            text = "Origin 白名单（每行一个，例如 http://localhost:6274）。原生客户端不发 Origin，不用填。"
            setPadding(0, dp(ctx, 12), 0, 0)
        }
        val originInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            minLines = 2
            setText(McpPrefs.originAllowlist(ctx).joinToString("\n"))
        }
        box.addView(portLabel); box.addView(portInput); box.addView(originLabel); box.addView(originInput)
        AlertDialog.Builder(ctx)
            .setTitle("端口与 Origin 白名单")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val p = portInput.text.toString().trim().toIntOrNull()
                if (p == null || p !in 1024..65535) {
                    toast(ctx, "端口无效，未修改端口")
                } else {
                    McpPrefs.setPort(ctx, p)
                }
                McpPrefs.setOriginAllowlist(ctx, originInput.text.toString().split('\n'))
                restartIfRunning(ctx)
                onSaved()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun restartIfRunning(ctx: Context) {
        if (McpPrefs.isEnabled(ctx)) {
            McpService.stop(ctx)
            Handler(Looper.getMainLooper()).postDelayed({
                if (McpPrefs.isEnabled(ctx)) McpService.start(ctx)
            }, 400)
        }
    }

    private fun tokenOrToast(ctx: Context): String? = try {
        McpTokenStore.get(ctx)
    } catch (e: Exception) {
        toast(ctx, "无法读取访问密钥（Keystore 不可用）")
        null
    }

    private fun copySensitive(ctx: Context, text: String, tip: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("CookieBox", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        cm.setPrimaryClip(clip)
        toast(ctx, tip)
    }

    private fun button(ctx: Context, label: String, onClick: () -> Unit): View =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 6) }
            setOnClickListener { onClick() }
        }

    internal fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    internal fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
}
