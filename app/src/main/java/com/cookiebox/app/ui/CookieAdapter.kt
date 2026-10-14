package com.cookiebox.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cookiebox.app.R
import com.cookiebox.app.databinding.ItemCookieCardBinding
import com.cookiebox.app.engine.CookieDictionary
import com.cookiebox.app.model.CookieItem

class CookieAdapter(
    private val onCopyPair: (CookieItem) -> Unit,
    private val onCopyValueOnly: (CookieItem) -> Unit
) : RecyclerView.Adapter<CookieAdapter.CookieViewHolder>() {

    private val allItems = mutableListOf<CookieItem>()
    private val displayItems = mutableListOf<CookieItem>()

    fun setData(items: List<CookieItem>) {
        allItems.clear()
        allItems.addAll(items)
        filter("")
    }

    fun filter(query: String) {
        displayItems.clear()
        if (query.isBlank()) {
            displayItems.addAll(allItems)
        } else {
            val lower = query.lowercase().trim()
            displayItems.addAll(allItems.filter {
                val meta = CookieDictionary.getMeta(it.name)
                it.name.lowercase().contains(lower) ||
                it.value.lowercase().contains(lower) ||
                meta.description.lowercase().contains(lower) ||
                meta.category.lowercase().contains(lower)
            })
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CookieViewHolder {
        val binding = ItemCookieCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CookieViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CookieViewHolder, position: Int) {
        holder.bind(displayItems[position])
    }

    override fun getItemCount(): Int = displayItems.size

    inner class CookieViewHolder(
        private val binding: ItemCookieCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CookieItem) {
            val meta = CookieDictionary.getMeta(item.name)

            binding.tvCookieName.text = item.name
            binding.tvCookieValue.text = item.value
            binding.tvDomainTag.text = if (item.domain.isNotEmpty()) "域名: ${item.domain}" else "当前网站"
            binding.tvValueLength.text = "长度: ${item.value.length}"

            binding.tvCookieCategory.text = meta.category
            if (meta.isCritical) {
                binding.tvCookieCategory.setTextColor(binding.root.context.getColor(R.color.danger))
            } else {
                binding.tvCookieCategory.setTextColor(binding.root.context.getColor(R.color.primary))
            }

            binding.tvCookieDescription.text = "💡 ${meta.description}"

            // 复制 name=value
            binding.btnCopySingle.setOnClickListener {
                onCopyPair(item)
            }

            // 单独复制 value 纯值
            binding.btnCopyValueOnly.setOnClickListener {
                onCopyValueOnly(item)
            }

            // 点击值卡片区域也快速复制纯值
            binding.layoutValueClickable.setOnClickListener {
                onCopyValueOnly(item)
            }
        }
    }
}
