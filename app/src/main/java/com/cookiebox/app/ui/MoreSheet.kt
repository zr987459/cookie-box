package com.cookiebox.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.cookiebox.app.R
import com.cookiebox.app.databinding.DialogMoreSheetBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * T-030：「更多」底部面板。只负责摆放，动作由调用方传入。
 * 不可用的项（例如没有打开页面时的「页内查找」）置灰且不可点，而不是隐藏，位置保持稳定好记。
 */
class MoreSheet(private val context: Context) {

    data class Item(
        val label: String,
        val icon: Int,
        val enabled: Boolean = true,
        val checked: Boolean = false,
        val action: () -> Unit
    )

    fun show(grid: List<Item>, list: List<Item>) {
        val dialog = BottomSheetDialog(context)
        val b = DialogMoreSheetBinding.inflate(LayoutInflater.from(context))
        val density = context.resources.displayMetrics.density
        val iconColor = ContextCompat.getColor(context, R.color.cb_icon)
        val onSurface = ContextCompat.getColor(context, R.color.on_surface)
        val primary = ContextCompat.getColor(context, R.color.primary)
        val ripple = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        grid.forEach { item ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = (72 * density).toInt()
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                setBackgroundResource(ripple)
                isEnabled = item.enabled
                alpha = if (item.enabled) 1f else 0.38f
                contentDescription = item.label
                setOnClickListener { dialog.dismiss(); item.action() }
            }
            cell.addView(ImageView(context).apply {
                setImageResource(item.icon)
                imageTintList = ColorStateList.valueOf(if (item.checked) primary else iconColor)
                layoutParams = LinearLayout.LayoutParams((26 * density).toInt(), (26 * density).toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            cell.addView(TextView(context).apply {
                text = item.label
                setTextColor(if (item.checked) primary else onSurface)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, (6 * density).toInt(), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            cell.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            b.gridMore.addView(cell)
        }

        list.forEach { item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (48 * density).toInt()
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                setBackgroundResource(ripple)
                isEnabled = item.enabled
                alpha = if (item.enabled) 1f else 0.38f
                setOnClickListener { dialog.dismiss(); item.action() }
            }
            row.addView(ImageView(context).apply {
                setImageResource(item.icon)
                imageTintList = ColorStateList.valueOf(iconColor)
                layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            row.addView(TextView(context).apply {
                text = item.label
                setTextColor(onSurface)
                textSize = 15f
                setPadding((16 * density).toInt(), 0, 0, 0)
            })
            b.listMore.addView(row)
        }

        dialog.setContentView(b.root)
        dialog.show()
    }
}
