package com.cookiebox.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.cookiebox.app.R
import com.cookiebox.app.databinding.DialogCookieStorageSheetBinding
import com.cookiebox.app.engine.CookieFormatter
import com.cookiebox.app.engine.CookieStorageRepository
import com.cookiebox.app.model.SavedCookieRecord
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class StorageBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogCookieStorageSheetBinding? = null
    private val binding get() = _binding

    private lateinit var repo: CookieStorageRepository
    private lateinit var adapter: StorageAdapter

    var onViewRecordDetails: ((SavedCookieRecord) -> Unit)? = null
    var onLaunchRecord: ((SavedCookieRecord) -> Unit)? = null
    var onDismissListener: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = DialogCookieStorageSheetBinding.inflate(inflater, container, false)
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = CookieStorageRepository(requireContext())

        adapter = StorageAdapter(
            onLaunchWithCookie = { record ->
                dismiss()
                onLaunchRecord?.invoke(record)
            },
            onQuickCopy = { record ->
                val list = record.getCookieList()
                val header = CookieFormatter.toHeaderString(list)
                copyToClipboard(header, "Header: ${record.domain}")
            },
            onViewDetails = { record ->
                dismiss()
                onViewRecordDetails?.invoke(record)
            },
            onDelete = { record ->
                repo.deleteRecord(record.id)
                Toast.makeText(requireContext(), R.string.record_deleted, Toast.LENGTH_SHORT).show()
                refreshData()
            }
        )

        binding?.let { b ->
            b.rvStorageRecords.layoutManager = LinearLayoutManager(requireContext())
            b.rvStorageRecords.adapter = adapter

            setupSearch()
            refreshData()

            b.btnClearStorage.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("清空储存库")
                    .setMessage("确定要清空所有已保存的网站 Cookie 记录吗？")
                    .setPositiveButton("清空") { _, _ ->
                        repo.clearAll()
                        refreshData()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun setupSearch() {
        binding?.etSearchStorage?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun refreshData() {
        val list = repo.getAllRecords()
        binding?.let { b ->
            b.tvStorageSubtitle.text = "已持久保存 ${list.size} 个网站凭据"
            if (list.isEmpty()) {
                b.tvEmptyStorage.visibility = View.VISIBLE
                b.rvStorageRecords.visibility = View.GONE
                b.btnClearStorage.visibility = View.GONE
            } else {
                b.tvEmptyStorage.visibility = View.GONE
                b.rvStorageRecords.visibility = View.VISIBLE
                b.btnClearStorage.visibility = View.VISIBLE
                adapter.submitList(list)
            }
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.invoke()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): StorageBottomSheetDialog = StorageBottomSheetDialog()
    }
}
