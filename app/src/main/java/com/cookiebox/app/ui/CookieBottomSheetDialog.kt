package com.cookiebox.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

            // 提取或载入数据
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
                CookieExtractor.extractCookies(targetUrl)
            }

            b.chipCount.text = "${cookieList.size} 项"

            if (cookieList.isEmpty()) {
                b.tvEmptyHint.visibility = View.VISIBLE
                b.rvCookies.visibility = View.GONE
                b.scrollCodeView.visibility = View.GONE
                b.btnCopyAll.isEnabled = false
                b.btnSaveStorage.isEnabled = false
                b.btnTopQuickCopy.isEnabled = false
            } else {
                b.tvEmptyHint.visibility = View.GONE
                newAdapter.setData(cookieList)
                b.btnCopyAll.isEnabled = true
                b.btnSaveStorage.isEnabled = true
                b.btnTopQuickCopy.isEnabled = true
            }

            setupFormatChips(host)
            setupSearch()

            // 顶部快捷一键复制全部 Header
            b.btnTopQuickCopy.setOnClickListener {
                val header = CookieFormatter.toHeaderString(cookieList)
                copyToClipboard(header, "Cookie Header")
                Toast.makeText(requireContext(), "已复制全部 ${cookieList.size} 项 Cookie Header 字符串！", Toast.LENGTH_SHORT).show()
            }

            // 底部主操作按钮复制当前格式
            b.btnCopyAll.setOnClickListener {
                copyCurrentFormat(host)
            }

            b.btnSaveStorage.setOnClickListener {
                saveToVault(host)
            }
        }
    }

    private fun saveToVault(host: String) {
        if (cookieList.isEmpty()) return
        val repo = CookieStorageRepository(requireContext())
        val record = SavedCookieRecord(
            id = UUID.randomUUID().toString(),
            title = if (webTitle.isNotBlank()) webTitle else host,
            domain = host,
            url = targetUrl,
            cookieJson = CookieFormatter.toJson(cookieList),
            count = cookieList.size,
            timestamp = System.currentTimeMillis()
        )
        repo.saveRecord(record)
        Toast.makeText(requireContext(), R.string.saved_success, Toast.LENGTH_SHORT).show()
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
                    b.scrollCodeView.visibility = View.GONE
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
                    b.btnCopyAll.text = "复制结构化 JSON"
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
            b.scrollCodeView.visibility = View.VISIBLE
            b.tvCodeContent.text = content
        }
    }

    private fun copyCurrentFormat(host: String) {
        if (cookieList.isEmpty()) {
            Toast.makeText(requireContext(), "当前没有可提取的 Cookie", Toast.LENGTH_SHORT).show()
            return
        }

        val (textToCopy, formatName) = when (currentMode) {
            Mode.LIST, Mode.HEADER -> Pair(CookieFormatter.toHeaderString(cookieList), "Header 键值对")
            Mode.JSON -> Pair(CookieFormatter.toJson(cookieList), "JSON 格式")
            Mode.NETSCAPE -> Pair(CookieFormatter.toNetscape(cookieList, host), "Netscape txt")
            Mode.PYTHON -> Pair(CookieFormatter.toPythonSnippet(cookieList, targetUrl, currentUserAgent), "Python 脚本")
            Mode.CURL -> Pair(CookieFormatter.toCurlCommand(cookieList, targetUrl, currentUserAgent), "cURL 命令")
        }

        copyToClipboard(textToCopy, "Cookie 数据")
        Toast.makeText(requireContext(), "已复制全部 ${cookieList.size} 个 Cookie ($formatName) 到剪贴板！", Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String, label: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
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
