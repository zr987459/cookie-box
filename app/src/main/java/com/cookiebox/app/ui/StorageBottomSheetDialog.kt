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
import android.webkit.CookieManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.cookiebox.app.R
import com.cookiebox.app.databinding.DialogCookieStorageSheetBinding
import com.cookiebox.app.engine.CookieFormatter
import com.cookiebox.app.engine.CookieKeepAliveEngine
import com.cookiebox.app.engine.CookieStorageRepository
import com.cookiebox.app.model.SavedCookieRecord
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class StorageBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogCookieStorageSheetBinding? = null
    private val binding get() = _binding

    private lateinit var repo: CookieStorageRepository
    private lateinit var adapter: StorageAdapter

    var defaultUserAgent: String = ""
    var customCookieManager: CookieManager? = null

    var onViewRecordDetails: ((SavedCookieRecord) -> Unit)? = null
    var onLaunchRecord: ((SavedCookieRecord) -> Unit)? = null
    var onDismissListener: (() -> Unit)? = null
    var onOpenAccounts: (() -> Unit)? = null

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
        val cm = customCookieManager ?: CookieManager.getInstance()

        adapter = StorageAdapter(
            onLaunchWithCookie = { record ->
                dismiss()
                onLaunchRecord?.invoke(record)
            },
            onKeepAlive = { record ->
                // 单账号安全保鲜
                CookieKeepAliveEngine.safeKeepAlive(record, repo, defaultUserAgent, cm) { result ->
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    refreshData()
                }
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
                com.cookiebox.app.engine.AccountVault(requireContext()).unlinkCookie(record.id)
                Toast.makeText(requireContext(), R.string.record_deleted, Toast.LENGTH_SHORT).show()
                refreshData()
            }
        )

        binding?.let { b ->
            b.rvStorageRecords.layoutManager = LinearLayoutManager(requireContext())
            b.rvStorageRecords.adapter = adapter

            setupSearch()
            refreshData()

            // ⚡ 一键全量安全保鲜 (带防封控与防高频保护)
            b.btnKeepAliveAll.setOnClickListener {
                val records = repo.getAllRecords()
                if (records.isEmpty()) {
                    Toast.makeText(requireContext(), "当前没有可保鲜的账号记录", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                var successCount = 0
                var cooldownCount = 0

                for (rec in records) {
                    CookieKeepAliveEngine.safeKeepAlive(rec, repo, defaultUserAgent, cm) { res ->
                        if (res.isCooldown) cooldownCount++
                        else if (res.success) successCount++
                    }
                }
                refreshData()

                val tip = when {
                    successCount > 0 && cooldownCount > 0 -> "🛡️ 全量保鲜完成：$successCount 个账号成功续期，$cooldownCount 个账号因24h防封保护保持不变"
                    successCount > 0 -> "✅ 全量保鲜完成：$successCount 个账号全部成功续期！"
                    else -> "🛡️ 全部账号在 24 小时内均已保鲜过，已触发防风控保护，状态完好！"
                }
                Toast.makeText(requireContext(), tip, Toast.LENGTH_LONG).show()
            }

            // 💡 新手教程与常见使用场景弹窗
            b.btnOpenAccounts.setOnClickListener {
                dismiss()
                onOpenAccounts?.invoke()
            }

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
🍪 Cookie 使用与防封保鲜指南

1. 🔑 免密直达与多账号切换：
在储存库卡片点击「进入」，无需输入密码和验证码，0秒直接以该账号身份免密登录。

2. ⚡ 安全保鲜续期（绝无封号风险）：
点击卡片上的「保鲜续期」，系统会在本地静默向网站注入活跃状态，服务器检测到活跃后会自动将寿命顺延一个月！
【防封原则】：
• 严禁使用外挂式裸请求，全程使用纯正官方 Chrome 内核；
• 24小时内同一账号无需重复打卡，防止高频触发网站风控；
• 账号只要保持活跃，Cookie 几乎可以永久使用！

3. 📥 下载神器画质解锁 (yt-dlp / Alist)：
在详情中导出「Netscape txt」，导入到 yt-dlp 等工具，即可解锁 4K、1080P60 甚至大会员最高画质。

4. 🐍 Python 自动化与爬虫：
在详情中切换到「Python 代码」，复制自动生成的 requests 代码，一键绕过登录滑块和验证码！
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("💡 Cookie 使用与防封指南")
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
                b.btnKeepAliveAll.visibility = View.GONE
            } else {
                b.tvEmptyStorage.visibility = View.GONE
                b.rvStorageRecords.visibility = View.VISIBLE
                b.btnClearStorage.visibility = View.VISIBLE
                b.btnKeepAliveAll.visibility = View.VISIBLE
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
