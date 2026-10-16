package com.cookiebox.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cookiebox.app.R
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
    private val onKeepAlive: (SavedCookieRecord) -> Unit,
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

        val groupMap = LinkedHashMap<String, WebsiteGroup>()
        for (record in filteredRecords) {
            val root = DomainUtils.getRootDomain(record.domain).ifEmpty { record.domain }
            val group = groupMap.getOrPut(root) {
                WebsiteGroup(
                    domain = if (record.domain.isNotEmpty()) record.domain else root,
                    rootDomain = root,
                    isExpanded = false
                )
            }
            group.accounts.add(record)
        }

        if (query.isNotBlank()) {
            groupMap.values.forEach { it.isExpanded = true }
        } else if (groupMap.size == 1) {
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

            updateExpandState(group.isExpanded, animate = false)

            binding.layoutHeaderClickable.setOnClickListener {
                group.isExpanded = !group.isExpanded
                updateExpandState(group.isExpanded, animate = true)
            }

            binding.layoutAccountsContainer.removeAllViews()
            val inflater = LayoutInflater.from(binding.root.context)

            group.accounts.forEachIndexed { index, account ->
                val rowBinding = ItemStorageAccountRowBinding.inflate(
                    inflater, binding.layoutAccountsContainer, false
                )

                rowBinding.tvAccountName.text = if (account.title.isNotBlank()) account.title else "账号 ${index + 1}"
                rowBinding.tvAccountCookiesBadge.text = "${account.count} 项"
                rowBinding.tvAccountTime.text = dateFormat.format(Date(account.timestamp))

                // 健康度角标显示
                when (account.checkStatus) {
                    200 -> {
                        rowBinding.tvHealthBadge.text = "💚 存活"
                        rowBinding.tvHealthBadge.setTextColor(ContextCompat.getColor(binding.root.context, R.color.success))
                    }
                    401 -> {
                        rowBinding.tvHealthBadge.text = "⚠️ 失效"
                        rowBinding.tvHealthBadge.setTextColor(ContextCompat.getColor(binding.root.context, R.color.danger))
                    }
                    else -> {
                        rowBinding.tvHealthBadge.text = "⚪ 未检测"
                        rowBinding.tvHealthBadge.setTextColor(ContextCompat.getColor(binding.root.context, R.color.on_surface_variant))
                    }
                }

                rowBinding.topDividerAccount.visibility = if (index == 0) View.GONE else View.VISIBLE

                rowBinding.btnLaunchAccount.setOnClickListener {
                    onLaunchWithCookie(account)
                }

                rowBinding.btnKeepAliveAccount.setOnClickListener {
                    onKeepAlive(account)
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
