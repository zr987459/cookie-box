package com.cookiebox.app.mcp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * 写操作确认框（ADR-0004 §5.4）。透明 Activity 上弹对话框；返回 / 点外部 = 拒绝。
 * 多个请求排队时逐个确认。超时的请求自动关闭。
 */
class McpConfirmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "mcp_confirm_id"
    }

    private var dialog: AlertDialog? = null
    private var currentId: Long = -1
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { onExpired() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showFor(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 正在确认的请求优先；新的会在它之后排队显示
        if (dialog?.isShowing != true) showFor(intent)
    }

    private fun showFor(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        val req = (if (id > 0) WriteGuard.get(id) else null) ?: WriteGuard.firstPending()
        if (req == null) {
            finish()
            return
        }
        show(req)
    }

    private fun show(req: WriteGuard.Request) {
        currentId = req.id
        val pad = dp(24)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        fun line(label: String, value: String) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(8), 0, 0)
            })
            box.addView(TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
                setTextIsSelectable(true)
            })
        }
        line("工具", req.tool)
        line("标签 / 身份", req.label)
        line("目标站点", req.host)
        line("内容", req.summary)

        var sessionBox: MaterialCheckBox? = null
        var trustBox: MaterialCheckBox? = null
        if (req.offerRemember) {
            sessionBox = MaterialCheckBox(this).apply {
                text = "本次会话免确认（${req.siteDomain}）"
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
                minHeight = dp(48)
            }
            trustBox = MaterialCheckBox(this).apply {
                text = "信任该站点，以后不再询问"
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
                minHeight = dp(48)
            }
            box.addView(sessionBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            box.addView(trustBox)
        }

        var decided = false
        val d = AlertDialog.Builder(this)
            .setTitle("允许 AI 执行写操作？")
            .setView(box)
            .setPositiveButton("允许") { _, _ ->
                decided = true
                WriteGuard.resolve(this, req.id, true, sessionBox?.isChecked == true, trustBox?.isChecked == true)
            }
            .setNegativeButton("拒绝") { _, _ ->
                decided = true
                WriteGuard.resolve(this, req.id, false, false, false)
            }
            .create()
        d.setCanceledOnTouchOutside(false)
        d.setOnCancelListener {
            if (!decided) {
                decided = true
                WriteGuard.resolve(this, req.id, false, false, false)
            }
        }
        d.setOnDismissListener {
            handler.removeCallbacks(expire)
            dialog = null
            if (!decided) WriteGuard.resolve(this, req.id, false, false, false)
            next()
        }
        dialog = d
        d.show()
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, (req.deadline - System.currentTimeMillis()).coerceAtLeast(0))
    }

    private fun onExpired() {
        // 请求已超时被拒：关掉对话框（dismiss 时对已结束的请求 resolve 是空操作）
        dialog?.dismiss()
    }

    private fun next() {
        if (isFinishing) return
        val req = WriteGuard.firstPending()
        if (req == null || req.id == currentId) {
            finish()
            return
        }
        show(req)
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
