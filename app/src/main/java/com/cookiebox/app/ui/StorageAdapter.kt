package com.cookiebox.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cookiebox.app.databinding.ItemStorageRecordBinding
import com.cookiebox.app.model.SavedCookieRecord
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
    private val displayRecords = mutableListOf<SavedCookieRecord>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submitList(items: List<SavedCookieRecord>) {
        allRecords.clear()
        allRecords.addAll(items)
        filter("")
    }

    fun filter(query: String) {
        displayRecords.clear()
        if (query.isBlank()) {
            displayRecords.addAll(allRecords)
        } else {
            val q = query.lowercase().trim()
            displayRecords.addAll(allRecords.filter {
                it.domain.lowercase().contains(q) ||
                it.title.lowercase().contains(q) ||
                it.url.lowercase().contains(q)
            })
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StorageViewHolder {
        val binding = ItemStorageRecordBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return StorageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StorageViewHolder, position: Int) {
        holder.bind(displayRecords[position])
    }

    override fun getItemCount(): Int = displayRecords.size

    inner class StorageViewHolder(
        private val binding: ItemStorageRecordBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(record: SavedCookieRecord) {
            binding.tvRecordDomain.text = if (record.domain.isNotEmpty()) record.domain else "未知域名"
            binding.tvRecordTitle.text = if (record.title.isNotEmpty()) record.title else record.url
            binding.chipRecordCount.text = "${record.count} 项"
            binding.tvRecordTime.text = "储存时间: ${dateFormat.format(Date(record.timestamp))}"

            binding.btnLaunchWithCookie.setOnClickListener {
                onLaunchWithCookie(record)
            }
            binding.btnQuickCopyHeader.setOnClickListener {
                onQuickCopy(record)
            }
            binding.btnViewDetails.setOnClickListener {
                onViewDetails(record)
            }
            binding.btnDeleteRecord.setOnClickListener {
                onDelete(record)
            }
        }
    }
}
