package com.cookiebox.app.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cookiebox.app.databinding.ItemTabCardBinding
import com.cookiebox.app.databinding.ItemTabRowBinding
import com.cookiebox.app.engine.DomainUtils
import com.cookiebox.app.model.TabGroup
import com.cookiebox.app.model.TabItem

class TabsAdapter(
    private val onTabClick: (TabItem) -> Unit,
    private val onCloseClick: (TabItem) -> Unit,
    private val onRenameClick: (TabItem) -> Unit = {},
    /** T-030：标签所属身份的显示名与颜色（由 MainActivity 的 IdentityRegistry 提供） */
    private val identityOf: ((TabItem) -> Pair<String, Int>)? = null
) : RecyclerView.Adapter<TabsAdapter.TabGroupViewHolder>() {

    private val allTabs = mutableListOf<TabItem>()
    private val displayGroups = mutableListOf<TabGroup>()
    private var currentActiveId: String = ""

    fun submitList(newTabs: List<TabItem>, activeId: String) {
        allTabs.clear()
        allTabs.addAll(newTabs)
        currentActiveId = activeId

        groupTabs()
        notifyDataSetChanged()
    }

    private fun groupTabs() {
        displayGroups.clear()
        val groupMap = LinkedHashMap<String, TabGroup>()

        for (tab in allTabs) {
            val (domainKey, rootDomain) = resolveTabDomain(tab)
            val group = groupMap.getOrPut(domainKey) {
                TabGroup(
                    domain = domainKey,
                    rootDomain = rootDomain,
                    isExpanded = true // 默认展开
                )
            }
            group.tabs.add(tab)
        }

        // 包含当前正在查看标签的分组强制保持展开状态
        groupMap.values.forEach { g ->
            if (g.tabs.any { it.id == currentActiveId }) {
                g.isExpanded = true
            }
        }

        displayGroups.addAll(groupMap.values)
    }

    private fun resolveTabDomain(tab: TabItem): Pair<String, String> {
        val effectiveUrl = tab.webView?.url?.takeIf { it != "about:blank" } ?: tab.pendingUrl ?: tab.url
        if (effectiveUrl.isBlank() || effectiveUrl == "about:blank") {
            return Pair("新标签页", "")
        }
        val host = try {
            Uri.parse(effectiveUrl).host ?: ""
        } catch (e: Exception) {
            ""
        }
        if (host.isBlank()) return Pair("新标签页", "")
        val root = DomainUtils.getRootDomain(host)
        return Pair(root.ifEmpty { host }, root)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabGroupViewHolder {
        val binding = ItemTabCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TabGroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabGroupViewHolder, position: Int) {
        holder.bind(displayGroups[position])
    }

    override fun getItemCount(): Int = displayGroups.size

    inner class TabGroupViewHolder(
        private val binding: ItemTabCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(group: TabGroup) {
            binding.tvGroupDomain.text = group.domain

            val accountCount = group.tabs.filter { !it.identityPending }.map { it.profileName }.distinct().size
            binding.tvGroupBadge.text = if (accountCount > 1) {
                "${group.tabs.size} 个标签 · ${accountCount} 个账号"
            } else {
                "${group.tabs.size} 个标签"
            }

            // 是否高亮卡片边框（若当前活动标签位于此组中）
            val containsActive = group.tabs.any { it.id == currentActiveId }
            binding.cardTabGroup.strokeWidth = if (containsActive) 2 else 0

            // 抽屉展开状态
            updateExpandState(group.isExpanded, animate = false)

            binding.layoutGroupHeaderClickable.setOnClickListener {
                group.isExpanded = !group.isExpanded
                updateExpandState(group.isExpanded, animate = true)
            }

            // 动态填充该网站下的所有标签（主标签与分身标签收纳在同一卡片内）
            binding.layoutTabsContainer.removeAllViews()
            val inflater = LayoutInflater.from(binding.root.context)

            group.tabs.forEachIndexed { index, tab ->
                val rowBinding = ItemTabRowBinding.inflate(
                    inflater, binding.layoutTabsContainer, false
                )

                val isActive = tab.id == currentActiveId
                rowBinding.tvActiveBadge.visibility = if (isActive) View.VISIBLE else View.GONE
                // T-030：每个标签显示所属账号（颜色圆点 + 名称）；没能真正隔离时明确标「未隔离」
                val ident = identityOf?.invoke(tab)
                val ctx = binding.root.context
                if (tab.identityPending || ident == null) {
                    rowBinding.tvIsolatedBadge.visibility = View.GONE
                } else {
                    rowBinding.tvIsolatedBadge.visibility = View.VISIBLE
                    val unisolated = tab.isClone && !tab.isolated
                    rowBinding.tvIsolatedBadge.text = if (unisolated) "${ident.first} · 未隔离" else ident.first
                    rowBinding.tvIsolatedBadge.setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            ctx, if (unisolated) com.cookiebox.app.R.color.danger else com.cookiebox.app.R.color.on_surface
                        )
                    )
                    val dot = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(ident.second)
                        val px = (8 * ctx.resources.displayMetrics.density).toInt()
                        setSize(px, px)
                    }
                    rowBinding.tvIsolatedBadge.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                    rowBinding.tvIsolatedBadge.compoundDrawablePadding = (4 * ctx.resources.displayMetrics.density).toInt()
                }

                val pageTitle = if (tab.title.isNotBlank()) tab.title else "新标签页"
                val pageUrl = if (tab.url.isNotBlank()) tab.url else "about:blank"
                if (tab.customName.isNotBlank()) {
                    // 自定义名放首行，原网页标题挪到第二行，方便多账号一眼区分
                    rowBinding.tvTabTitle.text = tab.customName
                    rowBinding.tvTabUrl.text = "$pageTitle · $pageUrl"
                } else {
                    rowBinding.tvTabTitle.text = pageTitle
                    rowBinding.tvTabUrl.text = pageUrl
                }

                rowBinding.topTabDivider.visibility = if (index == 0) View.GONE else View.VISIBLE

                rowBinding.layoutTabRowClickable.setOnClickListener {
                    onTabClick(tab)
                }

                rowBinding.layoutTabRowClickable.setOnLongClickListener {
                    onRenameClick(tab)
                    true
                }

                rowBinding.btnRenameTab.setOnClickListener {
                    onRenameClick(tab)
                }

                rowBinding.btnCloseTab.setOnClickListener {
                    onCloseClick(tab)
                }

                binding.layoutTabsContainer.addView(rowBinding.root)
            }
        }

        private fun updateExpandState(expanded: Boolean, animate: Boolean) {
            binding.layoutTabsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            val targetRotation = if (expanded) 180f else 0f
            if (animate) {
                binding.ivExpandArrow.animate().rotation(targetRotation).setDuration(200).start()
            } else {
                binding.ivExpandArrow.rotation = targetRotation
            }
        }
    }
}
