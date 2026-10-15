package com.cookiebox.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cookiebox.app.databinding.ItemStorageAccountRowBinding
import com.cookiebox.app.databinding.ItemStorageRecordBinding
import com.cookiebox.app.engine.DomainUtils
import com.cookiebox.app.model.SavedCookieRecord
import com.cookiebox.app.model.WebsiteGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StorageAdapter(
    private val onLaunchWithCookie: (SavedCookieRecord) -> Unit,
    private val onQuickCopy: (SavedCookieRecord) -> Unit,
    private val onViewDetails: (SavedCookieRecord) -> Unit,
    private val onDelete: (SavedCookieRecord) -> Unit
) : RecyclerView.Adapter<StorageAdapter.StorageViewHolder>() {

    private val allRecords = mutableListOf<SavedCookieRecord>()
    private val displayGroups = mutableListOf<WebsiteGroup>()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun submitList(items: List<SavedCookieRecord>) {
        allRecords.clear()
        allRecords.addAll(items)
        filter("")
    }

    fun filter(query: String) {
        displayGroups.clear()
        val filteredRecords = if (query.isBlank()) {
            allRecords
        } else {
            val q = query.lowercase().trim()
            allRecords.filter {
                it.domain.lowercase().contains(q) ||
                it.title.lowercase().contains(q) ||
                it.url.lowercase().contains(q)
            }
        }

        // 按网站顶级根域名自动归类合并到同一卡片
        val groupMap = LinkedHashMap<String, WebsiteGroup>()
        for (record in filteredRecords) {
            val root = DomainUtils.getRootDomain(record.domain).ifEmpty { record.domain }
            val group = groupMap.getOrPut(root) {
                WebsiteGroup(
                    domain = if (record.domain.isNotEmpty()) record.domain else root,
                    rootDomain = root,
                    isExpanded = false // 默认抽屉折叠收起，极致紧凑省空间
                )
            }
            group.accounts.add(record)
        }

        // 若搜索过滤时，默认自动展开匹配的网站卡片，方便查看
        if (query.isNotBlank()) {
            groupMap.values.forEach { it.isExpanded = true }
        } else if (groupMap.size == 1) {
            // 若只有一个网站，自动展开
            groupMap.values.firstOrNull()?.isExpanded = true
        }

        displayGroups.addAll(groupMap.values)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StorageViewHolder {
        val binding = ItemStorageRecordBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return StorageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StorageViewHolder, position: Int) {
        holder.bind(displayGroups[position])
    }

    override fun getItemCount(): Int = displayGroups.size

    inner class StorageViewHolder(
        private val binding: ItemStorageRecordBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(group: WebsiteGroup) {
            val displayDomain = if (group.rootDomain.isNotEmpty()) group.rootDomain else group.domain
            binding.tvWebsiteDomain.text = displayDomain
            binding.tvAccountsCountBadge.text = "${group.accounts.size} 个账号"

            // 抽屉展开状态更新
            updateExpandState(group.isExpanded, animate = false)

            // 点击卡片头部栏，触发抽屉展开/折叠
            binding.layoutHeaderClickable.setOnClickListener {
                group.isExpanded = !group.isExpanded
                updateExpandState(group.isExpanded, animate = true)
            }

            // 动态填充该网站下的所有账号行
            binding.layoutAccountsContainer.removeAllViews()
            val inflater = LayoutInflater.from(binding.root.context)

            group.accounts.forEachIndexed { index, account ->
                val rowBinding = ItemStorageAccountRowBinding.inflate(
                    inflater, binding.layoutAccountsContainer, false
                )

                rowBinding.tvAccountName.text = if (account.title.isNotBlank()) account.title else "账号 ${index + 1}"
                rowBinding.tvAccountCookiesBadge.text = "${account.count} 项"
                rowBinding.tvAccountTime.text = dateFormat.format(Date(account.timestamp))

                // 首项账号隐藏顶部分割线
                rowBinding.topDividerAccount.visibility = if (index == 0) View.GONE else View.VISIBLE

                rowBinding.btnLaunchAccount.setOnClickListener {
                    onLaunchWithCookie(account)
                }

                rowBinding.btnCopyAccount.setOnClickListener {
                    onQuickCopy(account)
                }

                rowBinding.btnDetailsAccount.setOnClickListener {
                    onViewDetails(account)
                }

                rowBinding.btnDeleteAccount.setOnClickListener {
                    onDelete(account)
                }

                binding.layoutAccountsContainer.addView(rowBinding.root)
            }
        }

        private fun updateExpandState(expanded: Boolean, animate: Boolean) {
            binding.layoutAccountsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            val targetRotation = if (expanded) 180f else 0f
            if (animate) {
                binding.ivExpandArrow.animate().rotation(targetRotation).setDuration(200).start()
            } else {
                binding.ivExpandArrow.rotation = targetRotation
            }
        }
    }
}
