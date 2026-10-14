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
import com.cookiebox.app.model.CookieItem
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class CookieBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogCookieSheetBinding? = null
    private val binding get() = _binding!!

    private var targetUrl: String = ""
    private var currentUserAgent: String = ""
    private var cookieList: List<CookieItem> = emptyList()
    private lateinit var adapter: CookieAdapter

    private enum class Mode {
        LIST, HEADER, JSON, NETSCAPE, PYTHON, CURL
    }
    private var currentMode = Mode.LIST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetUrl = arguments?.getString(ARG_URL) ?: ""
        currentUserAgent = arguments?.getString(ARG_UA) ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogCookieSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val host = try {
            Uri.parse(targetUrl).host ?: targetUrl
        } catch (e: Exception) {
            targetUrl
        }
        binding.tvSheetSubtitle.text = if (host.isNotBlank()) host else "未载入网页"

        // 初始化 RecyclerView 与适配器
        adapter = CookieAdapter { item ->
            copyToClipboard("${item.name}=${item.value}", "Cookie: ${item.name}")
        }
        binding.rvCookies.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCookies.adapter = adapter

        // 提取数据
        cookieList = CookieExtractor.extractCookies(targetUrl)
        binding.chipCount.text = "${cookieList.size} 项"

        if (cookieList.isEmpty()) {
            binding.tvEmptyHint.visibility = View.VISIBLE
            binding.rvCookies.visibility = View.GONE
            binding.scrollCodeView.visibility = View.GONE
            binding.btnCopyAll.isEnabled = false
        } else {
            binding.tvEmptyHint.visibility = View.GONE
            adapter.setData(cookieList)
            binding.btnCopyAll.isEnabled = true
        }

        setupFormatChips(host)
        setupSearch()

        binding.btnCopyAll.setOnClickListener {
            copyCurrentFormat(host)
        }
    }

    private fun setupSearch() {
        binding.etSearchCookie.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFormatChips(host: String) {
        binding.chipGroupFormats.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            when (checkedIds[0]) {
                R.id.chipFormatList -> {
                    currentMode = Mode.LIST
                    binding.rvCookies.visibility = View.VISIBLE
                    binding.layoutSearch.visibility = View.VISIBLE
                    binding.scrollCodeView.visibility = View.GONE
                    binding.btnCopyAll.text = "一键复制全部 Cookie (Header)"
                }
                R.id.chipFormatHeader -> {
                    currentMode = Mode.HEADER
                    showTextView(CookieFormatter.toHeaderString(cookieList))
                    binding.btnCopyAll.text = "复制 Header 字符串"
                }
                R.id.chipFormatJson -> {
                    currentMode = Mode.JSON
                    showTextView(CookieFormatter.toJson(cookieList))
                    binding.btnCopyAll.text = "复制结构化 JSON"
                }
                R.id.chipFormatNetscape -> {
                    currentMode = Mode.NETSCAPE
                    showTextView(CookieFormatter.toNetscape(cookieList, host))
                    binding.btnCopyAll.text = "复制 Netscape cookies.txt"
                }
                R.id.chipFormatPython -> {
                    currentMode = Mode.PYTHON
                    showTextView(CookieFormatter.toPythonSnippet(cookieList, targetUrl, currentUserAgent))
                    binding.btnCopyAll.text = "复制 Python 爬虫脚本"
                }
                R.id.chipFormatCurl -> {
                    currentMode = Mode.CURL
                    showTextView(CookieFormatter.toCurlCommand(cookieList, targetUrl, currentUserAgent))
                    binding.btnCopyAll.text = "复制 cURL 命令"
                }
            }
        }
    }

    private fun showTextView(content: String) {
        binding.rvCookies.visibility = View.GONE
        binding.layoutSearch.visibility = View.GONE
        binding.scrollCodeView.visibility = View.VISIBLE
        binding.tvCodeContent.text = content
    }

    private fun copyCurrentFormat(host: String) {
        val textToCopy = when (currentMode) {
            Mode.LIST, Mode.HEADER -> CookieFormatter.toHeaderString(cookieList)
            Mode.JSON -> CookieFormatter.toJson(cookieList)
            Mode.NETSCAPE -> CookieFormatter.toNetscape(cookieList, host)
            Mode.PYTHON -> CookieFormatter.toPythonSnippet(cookieList, targetUrl, currentUserAgent)
            Mode.CURL -> CookieFormatter.toCurlCommand(cookieList, targetUrl, currentUserAgent)
        }
        copyToClipboard(textToCopy, "Cookie 数据")
    }

    private fun copyToClipboard(text: String, label: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_URL = "arg_url"
        private const val ARG_UA = "arg_ua"

        fun newInstance(url: String, userAgent: String): CookieBottomSheetDialog {
            val dialog = CookieBottomSheetDialog()
            val args = Bundle().apply {
                putString(ARG_URL, url)
                putString(ARG_UA, userAgent)
            }
            dialog.arguments = args
            return dialog
        }
    }
}
