package com.cookiebox.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cookiebox.app.databinding.ActivityMainBinding
import com.cookiebox.app.ui.CookieBottomSheetDialog

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isDesktopMode = false
    private var defaultMobileUa: String = ""

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupListeners()
        setupShortcuts()
        setupBackNavigation()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings = binding.webView.settings
        defaultMobileUa = webSettings.userAgentString

        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 确保 Cookie 开启，包含第三方 Cookie
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return true // 阻止跳转外部 App Scheme，保持在浏览器内部
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let {
                    if (!binding.etUrl.isFocused) {
                        binding.etUrl.setText(it)
                    }
                    binding.layoutShortcuts.visibility = View.GONE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // 持久化同步 Cookie
                CookieManager.getInstance().flush()
            }
        }
    }

    private fun setupListeners() {
        // 地址栏回车加载
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val input = binding.etUrl.text.toString().trim()
                loadUrlFromInput(input)
                hideKeyboard()
                true
            } else {
                false
            }
        }

        // 后退
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                binding.layoutShortcuts.visibility = View.VISIBLE
            }
        }

        // 刷新
        binding.btnRefresh.setOnClickListener {
            binding.webView.reload()
        }

        // 桌面端 / 移动端 UA 切换
        binding.btnSwitchUa.setOnClickListener {
            toggleUserAgent()
        }

        // 清理当前 Cookie 与会话
        binding.btnClear.setOnClickListener {
            showClearCookiesDialog()
        }

        // 提取 Cookie 悬浮核心操作
        binding.fabExtractCookie.setOnClickListener {
            openCookieSheet()
        }
    }

    private fun loadUrlFromInput(input: String) {
        if (input.isEmpty()) return

        val targetUrl = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.bing.com/search?q=$input"
        }

        binding.etUrl.setText(targetUrl)
        binding.layoutShortcuts.visibility = View.GONE
        binding.webView.loadUrl(targetUrl)
    }

    private fun toggleUserAgent() {
        isDesktopMode = !isDesktopMode
        val settings = binding.webView.settings

        if (isDesktopMode) {
            settings.userAgentString = DESKTOP_UA
            binding.btnSwitchUa.setImageResource(R.drawable.ic_mobile)
            Toast.makeText(this, R.string.current_ua_desktop, Toast.LENGTH_SHORT).show()
        } else {
            settings.userAgentString = defaultMobileUa
            binding.btnSwitchUa.setImageResource(R.drawable.ic_desktop)
            Toast.makeText(this, R.string.current_ua_mobile, Toast.LENGTH_SHORT).show()
        }

        if (binding.webView.url != null) {
            binding.webView.reload()
        }
    }

    private fun showClearCookiesDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_all_cookies)
            .setMessage(R.string.clear_confirm_message)
            .setPositiveButton("立即清除") { _, _ ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeAllCookies {
                    cookieManager.flush()
                    binding.webView.clearCache(true)
                    binding.webView.clearHistory()
                    Toast.makeText(this, R.string.clear_success, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openCookieSheet() {
        val currentUrl = binding.webView.url ?: binding.etUrl.text.toString()
        val currentUa = binding.webView.settings.userAgentString
        val dialog = CookieBottomSheetDialog.newInstance(currentUrl, currentUa)
        dialog.show(supportFragmentManager, "CookieBottomSheet")
    }

    private fun setupShortcuts() {
        binding.chipBilibili.setOnClickListener { loadUrlFromInput("https://m.bilibili.com") }
        binding.chipWeibo.setOnClickListener { loadUrlFromInput("https://weibo.com") }
        binding.chipGithub.setOnClickListener { loadUrlFromInput("https://github.com/login") }
        binding.chipBaidu.setOnClickListener { loadUrlFromInput("https://www.baidu.com") }
        binding.chipZhihu.setOnClickListener { loadUrlFromInput("https://www.zhihu.com") }
        binding.chipV2ex.setOnClickListener { loadUrlFromInput("https://www.v2ex.com") }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else if (binding.layoutShortcuts.visibility != View.VISIBLE) {
                    binding.layoutShortcuts.visibility = View.VISIBLE
                } else {
                    finish()
                }
            }
        })
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
    }
}
