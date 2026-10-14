package com.cookiebox.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cookiebox.app.databinding.ItemTabCardBinding
import com.cookiebox.app.model.TabItem

class TabsAdapter(
    private val onTabClick: (TabItem) -> Unit,
    private val onCloseClick: (TabItem) -> Unit
) : RecyclerView.Adapter<TabsAdapter.TabViewHolder>() {

    private val tabs = mutableListOf<TabItem>()
    private var currentActiveId: String = ""

    fun submitList(newTabs: List<TabItem>, activeId: String) {
        tabs.clear()
        tabs.addAll(newTabs)
        currentActiveId = activeId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val binding = ItemTabCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.bind(tabs[position])
    }

    override fun getItemCount(): Int = tabs.size

    inner class TabViewHolder(
        private val binding: ItemTabCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(tab: TabItem) {
            val isActive = tab.id == currentActiveId

            binding.tvTabTitle.text = if (tab.title.isNotBlank()) tab.title else "新标签页"
            binding.tvTabUrl.text = if (tab.url.isNotBlank()) tab.url else "about:blank"

            binding.tvActiveBadge.visibility = if (isActive) View.VISIBLE else View.GONE
            binding.cardTab.strokeWidth = if (isActive) 3 else 0

            binding.root.setOnClickListener {
                onTabClick(tab)
            }

            binding.btnCloseTab.setOnClickListener {
                onCloseClick(tab)
            }
        }
    }
}
