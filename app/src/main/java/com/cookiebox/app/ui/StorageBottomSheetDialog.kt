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

            // 💡 新手教程与常见使用场景弹窗
            b.btnCookieTutorial.setOnClickListener {
                showTutorialDialog()
            }

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

    private fun showTutorialDialog() {
        val tutorial = """
🍪 这些提取的 Cookie 具体怎么用？

1. 🔑 免密直达与多账号切换：
在储存库卡片点击「进入网站」，无需输入密码和扫码，0秒直接以该账号身份登录进入网站。支持同一网站保存大号、小号随时切换！

2. 📥 下载神器画质解锁 (yt-dlp / Alist)：
在详情中导出「Netscape txt」或「Header」，导入到 yt-dlp 等工具，即可解锁 4K、1080P60 甚至大会员画质下载。

3. 🐍 Python 自动化与爬虫：
在详情中切换到「Python 代码」，复制自动生成的 requests 请求代码，直接运行即可自动完成签到、发帖、数据抓取，免去折磨人的滑块和验证码！

4. 💻 终端与接口调试 (cURL)：
切换到「cURL 命令」，在终端里一键测试接口状态与抓取数据。
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("💡 Cookie 使用指南")
            .setMessage(tutorial)
            .setPositiveButton("我知道了", null)
            .show()
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
            b.tvStorageSubtitle.text = "已持久保存 ${list.size} 个网站账号凭据"
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
