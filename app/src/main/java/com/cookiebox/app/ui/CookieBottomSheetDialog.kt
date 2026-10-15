package com.cookiebox.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.cookiebox.app.R
import com.cookiebox.app.databinding.DialogCookieSheetBinding
import com.cookiebox.app.engine.CookieExtractor
import com.cookiebox.app.engine.CookieFormatter
import com.cookiebox.app.engine.CookieStorageRepository
import com.cookiebox.app.model.CookieItem
import com.cookiebox.app.model.SavedCookieRecord
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File
import java.util.UUID

class CookieBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogCookieSheetBinding? = null
    private val binding get() = _binding

    private var targetUrl: String = ""
    private var webTitle: String = ""
    private var currentUserAgent: String = ""
    private var preloadedJson: String? = null
    private var cookieList: List<CookieItem> = emptyList()
    private var adapter: CookieAdapter? = null

    var customCookieManager: CookieManager? = null

    private enum class Mode {
        LIST, HEADER, JSON, NETSCAPE, PYTHON, CURL
    }
    private var currentMode = Mode.LIST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetUrl = arguments?.getString(ARG_URL) ?: ""
        webTitle = arguments?.getString(ARG_TITLE) ?: ""
        currentUserAgent = arguments?.getString(ARG_UA) ?: ""
        preloadedJson = arguments?.getString(ARG_JSON)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = DialogCookieSheetBinding.inflate(inflater, container, false)
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val host = try {
            Uri.parse(targetUrl).host ?: targetUrl
        } catch (e: Exception) {
            targetUrl
        }

        binding?.let { b ->
            b.tvSheetSubtitle.text = if (host.isNotBlank()) host else "当前页面"

            val newAdapter = CookieAdapter(
                onCopyPair = { item ->
                    copyToClipboard("${item.name}=${item.value}", "Cookie: ${item.name}")
                    Toast.makeText(requireContext(), "已复制 [${item.name}] 键值对", Toast.LENGTH_SHORT).show()
                },
                onCopyValueOnly = { item ->
                    copyToClipboard(item.value, "Cookie Value: ${item.name}")
                    Toast.makeText(requireContext(), "已复制 [${item.name}] 纯值", Toast.LENGTH_SHORT).show()
                }
            )
            adapter = newAdapter

            b.rvCookies.layoutManager = LinearLayoutManager(requireContext())
            b.rvCookies.adapter = newAdapter

            // 提取或载入数据 (支持指定独立 Profile 的 CookieManager)
            val cm = customCookieManager ?: CookieManager.getInstance()
            cookieList = if (!preloadedJson.isNullOrBlank()) {
                val record = SavedCookieRecord(
                    id = "",
                    title = webTitle,
                    domain = host,
                    url = targetUrl,
                    cookieJson = preloadedJson!!,
                    count = 0
                )
                record.getCookieList()
            } else {
                CookieExtractor.extractCookies(targetUrl, cm)
            }

            if (cookieList.isEmpty()) {
                b.tvEmptyHint.visibility = View.VISIBLE
                b.rvCookies.visibility = View.GONE
                b.layoutCodeContainer.visibility = View.GONE
                b.btnCopyAll.isEnabled = false
                b.btnSaveStorage.isEnabled = false
                b.btnTopQuickCopy.isEnabled = false
                b.btnShareCookie.isEnabled = false
                b.btnSaveAsFile.isEnabled = false
            } else {
                b.tvEmptyHint.visibility = View.GONE
                newAdapter.setData(cookieList)
                b.btnCopyAll.isEnabled = true
                b.btnSaveStorage.isEnabled = true
                b.btnTopQuickCopy.isEnabled = true
                b.btnShareCookie.isEnabled = true
                b.btnSaveAsFile.isEnabled = true
            }

            setupFormatChips(host)
            setupSearch()

            // 1. 顶部显眼一键复制
            b.btnTopQuickCopy.setOnClickListener {
                copyCurrentFormat(host)
            }

            // 2. 导出为 TXT 本地文件 (免剪贴板限制)
            b.btnSaveAsFile.setOnClickListener {
                exportToLocalFile(host)
            }

            // 3. 系统原生分享发送到便签/微信 (100% 绕过悬浮窗剪贴板限制)
            b.btnShareCookie.setOnClickListener {
                shareViaSystem(host)
            }

            // 4. 点击代码展示框直接秒级复制
            b.scrollCodeView.setOnClickListener { copyCurrentFormat(host) }
            b.tvCodeContent.setOnClickListener { copyCurrentFormat(host) }

            // 5. 底部主复制按钮
            b.btnCopyAll.setOnClickListener {
                copyCurrentFormat(host)
            }

            b.btnSaveStorage.setOnClickListener {
                saveToVault(host)
            }
        }
    }

    private fun getCurrentText(host: String): Pair<String, String> {
        return when (currentMode) {
            Mode.LIST, Mode.HEADER -> Pair(CookieFormatter.toHeaderString(cookieList), "Header 键值对")
            Mode.JSON -> Pair(CookieFormatter.toJson(cookieList), "JSON 格式")
            Mode.NETSCAPE -> Pair(CookieFormatter.toNetscape(cookieList, host), "Netscape txt")
            Mode.PYTHON -> Pair(CookieFormatter.toPythonSnippet(cookieList, targetUrl, currentUserAgent), "Python 脚本")
            Mode.CURL -> Pair(CookieFormatter.toCurlCommand(cookieList, targetUrl, currentUserAgent), "cURL 命令")
        }
    }

    private fun copyCurrentFormat(host: String) {
        if (cookieList.isEmpty()) {
            Toast.makeText(requireContext(), "当前没有可提取的 Cookie", Toast.LENGTH_SHORT).show()
            return
        }

        val (textToCopy, formatName) = getCurrentText(host)
        val success = copyToClipboard(textToCopy, "CookieBox_$formatName")
        if (success) {
            Toast.makeText(requireContext(), "✅ 已成功复制全部 ${cookieList.size} 个 Cookie ($formatName)！", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "剪贴板被系统限制，正在为您调起系统分享...", Toast.LENGTH_SHORT).show()
            shareViaSystem(host)
        }
    }

    private fun shareViaSystem(host: String) {
        if (cookieList.isEmpty()) return
        val (textToCopy, _) = getCurrentText(host)
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "$host Cookie 凭据")
                putExtra(Intent.EXTRA_TEXT, textToCopy)
            }
            startActivity(Intent.createChooser(shareIntent, "发送/复制 Cookie 到便签或其他应用"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "调起分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportToLocalFile(host: String) {
        if (cookieList.isEmpty()) return
        val (textToCopy, _) = getCurrentText(host)
        try {
            val cleanHost = host.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
            val fileName = "CookieBox_${cleanHost}.txt"
            val downloadDir = File("/storage/emulated/0/Download")
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val file = File(downloadDir, fileName)
            file.writeText(textToCopy)
            Toast.makeText(requireContext(), "📁 已成功导出到手机下载目录: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "保存文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(text: String, label: String): Boolean {
        return try {
            val clipboard = requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun saveToVault(host: String) {
        if (cookieList.isEmpty()) return
        val repo = CookieStorageRepository(requireContext())
        val existingDomainRecords = repo.getRecordsByDomain(host)

        val defaultName = when {
            webTitle.isNotBlank() && existingDomainRecords.isEmpty() -> webTitle
            existingDomainRecords.isNotEmpty() -> "$host (账号 ${existingDomainRecords.size + 1})"
            else -> "$host (主账号)"
        }

        val input = android.widget.EditText(requireContext()).apply {
            setText(defaultName)
            setSelection(defaultName.length)
            setPadding(48, 32, 48, 16)
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("存入 Cookie 储存库")
            .setMessage("为该账号设置一个备注名称（支持同一网站存多个不同账号）：")
            .setView(input)
            .setPositiveButton("确认保存") { _, _ ->
                val finalTitle = input.text.toString().trim().ifBlank { defaultName }
                val record = SavedCookieRecord(
                    id = UUID.randomUUID().toString(),
                    title = finalTitle,
                    domain = host,
                    url = targetUrl,
                    cookieJson = CookieFormatter.toJson(cookieList),
                    count = cookieList.size,
                    timestamp = System.currentTimeMillis()
                )
                repo.saveRecord(record)
                Toast.makeText(requireContext(), "✅ 已成功保存「$finalTitle」到储存库！", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupSearch() {
        binding?.etSearchCookie?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter?.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFormatChips(host: String) {
        binding?.chipGroupFormats?.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val b = binding ?: return@setOnCheckedStateChangeListener
            when (checkedIds[0]) {
                R.id.chipFormatList -> {
                    currentMode = Mode.LIST
                    b.rvCookies.visibility = View.VISIBLE
                    b.layoutSearch.visibility = View.VISIBLE
                    b.layoutCodeContainer.visibility = View.GONE
                    b.btnCopyAll.text = "一键复制全部 Cookie (Header)"
                }
                R.id.chipFormatHeader -> {
                    currentMode = Mode.HEADER
                    showTextView(CookieFormatter.toHeaderString(cookieList))
                    b.btnCopyAll.text = "复制全部 Header 字符串"
                }
                R.id.chipFormatJson -> {
                    currentMode = Mode.JSON
                    showTextView(CookieFormatter.toJson(cookieList))
                    b.btnCopyAll.text = "复制全部结构化 JSON"
                }
                R.id.chipFormatNetscape -> {
                    currentMode = Mode.NETSCAPE
                    showTextView(CookieFormatter.toNetscape(cookieList, host))
                    b.btnCopyAll.text = "复制 Netscape cookies.txt"
                }
                R.id.chipFormatPython -> {
                    currentMode = Mode.PYTHON
                    showTextView(CookieFormatter.toPythonSnippet(cookieList, targetUrl, currentUserAgent))
                    b.btnCopyAll.text = "复制 Python 爬虫脚本"
                }
                R.id.chipFormatCurl -> {
                    currentMode = Mode.CURL
                    showTextView(CookieFormatter.toCurlCommand(cookieList, targetUrl, currentUserAgent))
                    b.btnCopyAll.text = "复制 cURL 命令"
                }
            }
        }
    }

    private fun showTextView(content: String) {
        binding?.let { b ->
            b.rvCookies.visibility = View.GONE
            b.layoutSearch.visibility = View.GONE
            b.layoutCodeContainer.visibility = View.VISIBLE
            b.tvCodeContent.text = content
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        adapter = null
    }

    companion object {
        private const val ARG_URL = "arg_url"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_UA = "arg_ua"
        private const val ARG_JSON = "arg_json"

        fun newInstance(url: String, title: String, userAgent: String): CookieBottomSheetDialog {
            val dialog = CookieBottomSheetDialog()
            val args = Bundle().apply {
                putString(ARG_URL, url)
                putString(ARG_TITLE, title)
                putString(ARG_UA, userAgent)
            }
            dialog.arguments = args
            return dialog
        }

        fun newInstanceFromRecord(record: SavedCookieRecord, userAgent: String): CookieBottomSheetDialog {
            val dialog = CookieBottomSheetDialog()
            val args = Bundle().apply {
                putString(ARG_URL, record.url)
                putString(ARG_TITLE, record.title)
                putString(ARG_UA, userAgent)
                putString(ARG_JSON, record.cookieJson)
            }
            dialog.arguments = args
            return dialog
        }
    }
}
