package com.cookiebox.app.mcp

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cookiebox.app.R
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 调用记录页（ADR-0004 §5.7）：时间 / 工具 / 标签 / 站点 / 结果 / 耗时，可一键清空 */
class McpCallLogActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
        }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(attrColor(com.google.android.material.R.attr.colorOnSurface))
            contentDescription = "返回"
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(TextView(this).apply {
            text = "调用记录"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "清空"
            setOnClickListener { confirmClear() }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(32))
        }
        val scroll = ScrollView(this)
        scroll.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        val list = CallLog.entries(this)
        content.addView(TextView(this).apply {
            text = if (list.isEmpty()) "还没有调用记录。" else "最近 ${list.size} 条（最多保留 ${CallLog.MAX_ENTRIES} 条），只记录时间、工具、标签、站点、结果和耗时。"
            textSize = 13f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(4), 0, dp(8))
        })
        // 一次最多画 200 条，避免一次创建过多视图
        for (e in list.take(200)) {
            val resultText = when (e.result) {
                CallLog.RESULT_OK -> "成功"
                CallLog.RESULT_DENIED -> "已拒绝"
                else -> "错误"
            }
            val resultColor = when (e.result) {
                CallLog.RESULT_OK -> getColor(R.color.success)
                CallLog.RESULT_DENIED -> getColor(R.color.danger)
                else -> getColor(R.color.danger)
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = e.tool
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(TextView(this).apply {
                text = resultText
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resultColor)
            })
            item.addView(top)
            val detail = listOfNotNull(
                timeFormat.format(Date(e.time)),
                e.label.takeIf { it.isNotBlank() },
                e.domain.takeIf { it.isNotBlank() },
                "${e.durationMs} ms"
            ).joinToString(" · ")
            item.addView(TextView(this).apply {
                text = detail
                textSize = 13f
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            content.addView(item)
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空调用记录？")
            .setPositiveButton("清空") { _, _ -> CallLog.clear(this); render() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
