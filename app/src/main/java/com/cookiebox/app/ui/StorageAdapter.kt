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
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

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
                    rootDomain = root
                )
            }
            group.accounts.add(record)
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
            binding.chipAccountsCount.text = "${group.accounts.size} 个账号"

            // 动态填充该网站下的所有账号行
            binding.layoutAccountsContainer.removeAllViews()
            val inflater = LayoutInflater.from(binding.root.context)

            group.accounts.forEachIndexed { index, account ->
                val rowBinding = ItemStorageAccountRowBinding.inflate(
                    inflater, binding.layoutAccountsContainer, false
                )

                rowBinding.tvAccountName.text = if (account.title.isNotBlank()) account.title else "账号 ${index + 1}"
                rowBinding.chipAccountCookies.text = "${account.count} 项"
                rowBinding.tvAccountTime.text = "储存时间: ${dateFormat.format(Date(account.timestamp))}"

                // 最后一个账号隐藏底部分割线
                rowBinding.dividerAccount.visibility = if (index == group.accounts.size - 1) View.GONE else View.VISIBLE

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
    }
}
