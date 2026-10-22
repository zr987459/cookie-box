package com.cookiebox.app.ui

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cookiebox.app.R
import com.cookiebox.app.databinding.ItemTabGroupHeaderBinding
import com.cookiebox.app.databinding.ItemTabIdentityRowBinding
import com.cookiebox.app.engine.DomainUtils
import com.cookiebox.app.model.TabItem

/**
 * T-030：标签面板按网站分组的扁平列表（组头 + 身份标签行）。
 * 组和组内都按最近使用排序；空白标签单独一组「新标签页」。
 */
class TabsAdapter(
    private val onTabClick: (TabItem) -> Unit,
    private val onCloseClick: (TabItem) -> Unit,
    private val onRenameClick: (TabItem) -> Unit = {},
    /** 标签所属身份的显示名与颜色（由 MainActivity 的 IdentityRegistry 提供） */
    private val identityOf: ((TabItem) -> Pair<String, Int>)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val site: String, val count: Int, val favicon: android.graphics.Bitmap?) : Row()
        data class Tab(val tab: TabItem) : Row()
    }

    private val rows = mutableListOf<Row>()
    private var currentActiveId: String = ""

    /** 分组后的网站数（给面板标题用） */
    var siteCount: Int = 0
        private set

    fun submitList(newTabs: List<TabItem>, activeId: String) {
        currentActiveId = activeId
        rows.clear()
        val groups = newTabs.groupBy { siteKey(it) }
            .entries
            .sortedByDescending { e -> e.value.maxOf { recency(it) } }
        siteCount = groups.count { it.key.isNotEmpty() }
        for ((site, list) in groups) {
            val sorted = list.sortedByDescending { recency(it) }
            val favicon = sorted.firstNotNullOfOrNull { it.webView?.favicon }
            rows.add(Row.Header(site, sorted.size, favicon))
            sorted.forEach { rows.add(Row.Tab(it)) }
        }
        notifyDataSetChanged()
    }

    /** 当前活动标签的 lastActiveAt 可能还没刷新，排序时视为最新 */
    private fun recency(t: TabItem): Long = if (t.id == currentActiveId) Long.MAX_VALUE else t.lastActiveAt

    private fun siteKey(tab: TabItem): String {
        val url = tab.webView?.url?.takeIf { it != "about:blank" } ?: tab.pendingUrl ?: tab.url
        if (url.isBlank() || url == "about:blank") return ""
        val host = try { Uri.parse(url).host } catch (e: Exception) { null } ?: return ""
        return DomainUtils.getRootDomain(host).ifBlank { host }
    }

    fun tabAt(position: Int): TabItem? = (rows.getOrNull(position) as? Row.Tab)?.tab

    override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Header) 0 else 1
    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderVH(ItemTabGroupHeaderBinding.inflate(inflater, parent, false))
        } else {
            TabVH(ItemTabIdentityRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(r)
            is Row.Tab -> (holder as TabVH).bind(r.tab)
        }
    }

    inner class HeaderVH(private val b: ItemTabGroupHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(h: Row.Header) {
            b.tvGroupSite.text = h.site.ifEmpty { "新标签页" }
            b.tvGroupCount.text = "${h.count} 个标签"
            if (h.favicon != null) {
                b.ivGroupFavicon.setImageBitmap(h.favicon)
                b.ivGroupFavicon.imageTintList = null
            } else {
                b.ivGroupFavicon.setImageResource(if (h.site.isEmpty()) R.drawable.ic_add else R.drawable.ic_public)
                b.ivGroupFavicon.imageTintList =
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(b.root.context, R.color.cb_icon))
            }
        }
    }

    inner class TabVH(private val b: ItemTabIdentityRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(tab: TabItem) {
            val ctx = b.root.context
            val isActive = tab.id == currentActiveId
            val ident = identityOf?.invoke(tab)
            if (tab.identityPending || ident == null) {
                b.viewIdentityDot.visibility = View.INVISIBLE
                b.tvIdentityName.text = "未选账号"
            } else {
                b.viewIdentityDot.visibility = View.VISIBLE
                b.viewIdentityDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ident.second)
                }
                b.tvIdentityName.text = ident.first
            }
            val unisolated = tab.isClone && !tab.isolated
            when {
                unisolated -> {
                    b.tvRowBadge.visibility = View.VISIBLE
                    b.tvRowBadge.text = "未隔离"
                    b.tvRowBadge.setTextColor(ContextCompat.getColor(ctx, R.color.danger))
                }
                isActive -> {
                    b.tvRowBadge.visibility = View.VISIBLE
                    b.tvRowBadge.text = "当前"
                    b.tvRowBadge.setTextColor(ContextCompat.getColor(ctx, R.color.primary))
                }
                else -> b.tvRowBadge.visibility = View.GONE
            }
            val title = tab.title.takeIf { it.isNotBlank() && it != "about:blank" } ?: "新标签页"
            b.tvPageTitle.text = title
            b.root.contentDescription = "${b.tvIdentityName.text}，$title" + if (isActive) "，当前标签" else ""
            b.layoutTabRow.setOnClickListener { onTabClick(tab) }
            b.layoutTabRow.setOnLongClickListener {
                if (!tab.identityPending) onRenameClick(tab)
                true
            }
            b.btnCloseTab.setOnClickListener { onCloseClick(tab) }
        }
    }
}
