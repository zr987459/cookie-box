package com.cookiebox.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cookiebox.app.databinding.ItemCookieCardBinding
import com.cookiebox.app.model.CookieItem

class CookieAdapter(
    private val onCopyClick: (CookieItem) -> Unit
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
                it.name.lowercase().contains(lower) || it.value.lowercase().contains(lower)
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
            binding.tvCookieName.text = item.name
            binding.tvCookieValue.text = item.value
            binding.tvDomainTag.text = if (item.domain.isNotEmpty()) item.domain else "当前域名"
            binding.tvValueLength.text = "长度: ${item.value.length}"

            binding.btnCopySingle.setOnClickListener {
                onCopyClick(item)
            }
        }
    }
}
