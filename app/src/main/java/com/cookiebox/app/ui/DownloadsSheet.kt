package com.cookiebox.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.cookiebox.app.R
import com.cookiebox.app.engine.DownloadStore
import com.cookiebox.app.engine.DownloadStore.Status
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 下载管理面板。
 * - 顶部写明文件保存位置，并可一键打开系统「下载」文件夹
 * - 每行：文件名、状态（大小 · 时间 / 进度 / 失败原因）；下载中可取消
 * - 点按：已完成→打开；失败/中断/取消且有链接→重新下载
 * - 行尾「⋮」：打开、分享、复制下载链接、重新下载、删除文件、仅移除记录
 * 动作由 MainActivity 实现（打开文件、发起系统删除确认等需要 Activity）。
 */
class DownloadsSheet(
    private val context: Context,
    private val store: DownloadStore,
    private val actions: Actions
) {

    interface Actions {
        fun open(rec: DownloadStore.Rec)
        fun share(rec: DownloadStore.Rec)
        fun copyUrl(rec: DownloadStore.Rec)
        fun retry(rec: DownloadStore.Rec)
        fun cancel(rec: DownloadStore.Rec)
        /** 删除文件和记录 */
        fun delete(recs: List<DownloadStore.Rec>)
        fun openFolder()
    }

    private val d = context.resources.displayMetrics.density
    private val onSurface = ContextCompat.getColor(context, R.color.on_surface)
    private val secondary = ContextCompat.getColor(context, R.color.on_surface_variant)
    private val primary = ContextCompat.getColor(context, R.color.primary)
    private val danger = ContextCompat.getColor(context, R.color.danger)
    private val iconColor = ContextCompat.getColor(context, R.color.cb_icon)
    private val ripple = TypedValue().also {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
    }.resourceId
    private val rippleBorderless = TypedValue().also {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
    }.resourceId

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var summary: TextView

    fun show() {
        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }
        root.addView(View(context).apply {
            setBackgroundResource(R.drawable.bg_search_box)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10)
            }
        })

        // 标题行：下载 + 清理
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "下载"
            textSize = 18f
            setTextColor(onSurface)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(textButton("清理") { showCleanMenu() })
        root.addView(header)

        // 保存位置 + 打开文件夹
        val loc = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setBackgroundResource(ripple)
            contentDescription = "打开系统下载文件夹"
            setOnClickListener { actions.openFolder() }
        }
        loc.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_storage)
            imageTintList = ColorStateList.valueOf(iconColor)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        summary = TextView(context).apply {
            textSize = 13f
            setTextColor(secondary)
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        loc.addView(summary)
        loc.addView(TextView(context).apply {
            text = "打开文件夹"
            textSize = 13f
            setTextColor(primary)
            setPadding(0, 0, dp(8), 0)
        })
        root.addView(loc)

        root.addView(View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4); bottomMargin = dp(4); marginEnd = dp(8)
            }
        })

        empty = TextView(context).apply {
            text = "还没有下载记录。\n网页里点下载链接后，文件会出现在这里。"
            textSize = 14f
            setTextColor(secondary)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }
        root.addView(empty)

        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = NestedScrollView(context).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(scroll)

        val listener: () -> Unit = { render() }
        store.addListener(listener)
        dialog.setOnDismissListener { store.removeListener(listener) }
        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        render()
        dialog.show()

        // 后台补扫「下载」目录里本应用写入的旧文件
        Thread { store.syncWithMediaStore() }.start()
    }

    private fun render() {
        val recs = store.all()
        val running = recs.count { it.status == Status.RUNNING }
        summary.text = buildString {
            append("保存在 ").append(store.locationHint())
            if (running > 0) append(" · ").append(running).append(" 个下载中")
        }
        empty.visibility = if (recs.isEmpty()) View.VISIBLE else View.GONE
        list.removeAllViews()
        recs.forEach { list.addView(row(it)) }
    }

    private fun row(rec: DownloadStore.Rec): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(6), 0, dp(6))
            setBackgroundResource(ripple)
            setOnClickListener { primaryAction(rec) }
            setOnLongClickListener { showRowMenu(rec); true }
        }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_file_download)
            imageTintList = ColorStateList.valueOf(
                when (rec.status) {
                    Status.DONE -> primary
                    Status.RUNNING -> primary
                    Status.FAILED, Status.INTERRUPTED -> danger
                    Status.CANCELED -> iconColor
                }
            )
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(context).apply {
            text = rec.fileName
            textSize = 15f
            setTextColor(onSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        if (rec.status == Status.RUNNING) {
            texts.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = rec.total <= 0
                max = 100
                if (rec.total > 0) progress = (rec.done * 100 / rec.total).toInt().coerceIn(0, 100)
                progressTintList = ColorStateList.valueOf(primary)
                indeterminateTintList = ColorStateList.valueOf(primary)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                    topMargin = dp(4); bottomMargin = dp(2)
                }
            })
        }
        texts.addView(TextView(context).apply {
            text = statusLine(rec)
            textSize = 12f
            setTextColor(if (rec.status == Status.FAILED || rec.status == Status.INTERRUPTED) danger else secondary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(texts)

        if (rec.status == Status.RUNNING) {
            row.addView(iconButton(R.drawable.ic_close, "取消下载 ${rec.fileName}") { actions.cancel(rec) })
        } else {
            row.addView(iconButton(R.drawable.ic_more_vert, "${rec.fileName} 的更多操作") { showRowMenu(rec) })
        }
        row.contentDescription = "${rec.fileName}，${statusLine(rec)}"
        return row
    }

    private fun statusLine(rec: DownloadStore.Rec): String {
        val time = DateUtils.getRelativeTimeSpanString(
            rec.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        )
        return when (rec.status) {
            Status.RUNNING -> if (rec.total > 0) {
                "${(rec.done * 100 / rec.total).coerceIn(0, 100)}% · ${human(rec.done)} / ${human(rec.total)}"
            } else "下载中 · ${human(rec.done)}"
            Status.DONE -> listOfNotNull(rec.total.takeIf { it > 0 }?.let { human(it) }, time.toString()).joinToString(" · ")
            Status.FAILED -> "失败：${rec.error ?: "未知错误"}"
            Status.INTERRUPTED -> rec.error ?: "下载中断"
            Status.CANCELED -> "已取消 · $time"
        }
    }

    private fun canRetry(rec: DownloadStore.Rec) =
        rec.url?.let { it.startsWith("http://") || it.startsWith("https://") } == true

    private fun primaryAction(rec: DownloadStore.Rec) {
        when {
            rec.status == Status.DONE -> actions.open(rec)
            rec.status == Status.RUNNING -> Unit
            canRetry(rec) -> actions.retry(rec)
            else -> showRowMenu(rec)
        }
    }

    private fun showRowMenu(rec: DownloadStore.Rec) {
        if (rec.status == Status.RUNNING) {
            AlertDialog.Builder(context)
                .setTitle(rec.fileName)
                .setItems(arrayOf("取消下载")) { _, _ -> actions.cancel(rec) }
                .show()
            return
        }
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (rec.status == Status.DONE) {
            labels += "打开"; acts += { actions.open(rec) }
            labels += "分享"; acts += { actions.share(rec) }
        }
        if (canRetry(rec)) {
            labels += "重新下载"; acts += { actions.retry(rec) }
            labels += "复制下载链接"; acts += { actions.copyUrl(rec) }
        }
        val hasFile = rec.uri != null || rec.path != null
        if (hasFile) {
            labels += "删除文件"; acts += { confirmDelete(listOf(rec)) }
        }
        labels += "仅从列表移除"; acts += { store.remove(listOf(rec.id)) }
        AlertDialog.Builder(context)
            .setTitle(rec.fileName)
            .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
            .show()
    }

    private fun confirmDelete(recs: List<DownloadStore.Rec>) {
        if (recs.isEmpty()) return
        val msg = if (recs.size == 1) "删除「${recs[0].fileName}」？文件会从手机里删掉，无法恢复。"
        else "删除这 ${recs.size} 个文件？文件会从手机里删掉，无法恢复。"
        AlertDialog.Builder(context)
            .setTitle("删除文件")
            .setMessage(msg)
            .setPositiveButton("删除") { _, _ -> actions.delete(recs) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCleanMenu() {
        val recs = store.all()
        val broken = recs.filter { it.status == Status.FAILED || it.status == Status.INTERRUPTED || it.status == Status.CANCELED }
        val done = recs.filter { it.status == Status.DONE }
        val labels = arrayOf(
            "删除失败和中断的下载（${broken.size}）",
            "删除全部已下载的文件（${done.size}）",
            "清空列表，保留文件"
        )
        AlertDialog.Builder(context)
            .setTitle("清理")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> if (broken.isNotEmpty()) actions.delete(broken)
                    1 -> confirmDelete(done)
                    2 -> store.remove(recs.filter { it.status != Status.RUNNING }.map { it.id })
                }
            }
            .show()
    }

    private fun iconButton(icon: Int, desc: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(iconColor)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundResource(rippleBorderless)
        contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    private fun textButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(primary)
        gravity = Gravity.CENTER
        minWidth = dp(56)
        minHeight = dp(44)
        setPadding(dp(12), 0, dp(12), 0)
        setBackgroundResource(ripple)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
