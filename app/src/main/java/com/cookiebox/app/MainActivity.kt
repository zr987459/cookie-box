package com.cookiebox.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
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
import com.cookiebox.app.engine.DomainUtils
import com.cookiebox.app.model.SavedCookieRecord
import com.cookiebox.app.model.TabItem
import com.cookiebox.app.ui.CookieBottomSheetDialog
import com.cookiebox.app.ui.StorageBottomSheetDialog
import com.cookiebox.app.ui.TabsBottomSheetDialog
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val tabs = mutableListOf<TabItem>()
    private var activeTabId: String = ""
    private var defaultMobileUa: String = ""

    private var tabsDialog: TabsBottomSheetDialog? = null

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 默认新建一个初始标签页
        createNewTab(initialUrl = "")

        setupListeners()
        setupShortcuts()
        setupBackNavigation()
    }

    // ==================== 多窗口标签管理 (标准 Chromium 多标签机制，零会话丢失) ====================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewTab(initialUrl: String = ""): TabItem {
        val tabId = UUID.randomUUID().toString()
        val webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val settings = webView.settings
        if (defaultMobileUa.isEmpty()) {
            defaultMobileUa = settings.userAgentString
        }

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 开启多窗口支持与弹窗代理，适配 target="_blank"
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val newTab = TabItem(
            id = tabId,
            title = "新标签页",
            url = initialUrl,
            isDesktop = false,
            webView = webView
        )

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    newTab.title = title
                    val dialog = tabsDialog
                    if (dialog != null && dialog.isAdded && !dialog.isStateSaved) {
                        dialog.setTabsData(tabs, activeTabId)
                    }
                }
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newTab.id == activeTabId) {
                    if (newProgress < 100) {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.progressBar.progress = newProgress
                    } else {
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (resultMsg == null) return false
                val spawnedTab = createNewTab(initialUrl = "")
                val transport = resultMsg.obj as? WebView.WebViewTransport
                if (transport != null) {
                    transport.webView = spawnedTab.webView
                    resultMsg.sendToTarget()
                    return true
                }
                return false
            }

            override fun onCloseWindow(window: WebView?) {
                val tabToClose = tabs.find { it.webView == window }
                if (tabToClose != null) {
                    closeTab(tabToClose.id)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let {
                    newTab.url = it
                    if (newTab.id == activeTabId) {
                        if (!binding.etUrl.isFocused) {
                            binding.etUrl.setText(it)
                        }
                        updateSecurityIcon(it)
                        binding.layoutShortcuts.visibility = View.GONE
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                url?.let {
                    newTab.url = it
                    if (newTab.id == activeTabId) {
                        updateSecurityIcon(it)
                    }
                }
            }
        }

        tabs.add(newTab)
        switchTab(tabId)
        updateTabBadge()

        if (initialUrl.isNotBlank()) {
            loadUrlInTab(newTab, initialUrl)
        }

        return newTab
    }

    private fun switchTab(targetTabId: String) {
        val targetTab = tabs.find { it.id == targetTabId } ?: return
        activeTabId = targetTabId

        // 仅切换可见视图容器，绝不清除 Cookie，保持所有标签会话长效登录！
        binding.layoutWebContainer.removeAllViews()
        targetTab.webView?.let {
            if (it.parent != null) {
                (it.parent as? ViewGroup)?.removeView(it)
            }
            binding.layoutWebContainer.addView(it)
        }

        if (targetTab.url.isNotBlank()) {
            binding.etUrl.setText(targetTab.url)
            updateSecurityIcon(targetTab.url)
            binding.layoutShortcuts.visibility = View.GONE
        } else {
            binding.etUrl.setText("")
            binding.layoutShortcuts.visibility = View.VISIBLE
        }

        if (targetTab.isDesktop) {
            binding.btnSwitchUa.setImageResource(R.drawable.ic_mobile)
        } else {
            binding.btnSwitchUa.setImageResource(R.drawable.ic_desktop)
        }

        updateTabBadge()
    }

    private fun closeTab(tabId: String) {
        val targetIndex = tabs.indexOfFirst { it.id == tabId }
        if (targetIndex < 0) return

        val removingTab = tabs[targetIndex]
        removingTab.webView?.destroy()
        tabs.removeAt(targetIndex)

        if (tabs.isEmpty()) {
            createNewTab(initialUrl = "")
        } else {
            if (tabId == activeTabId) {
                val nextIndex = if (targetIndex >= tabs.size) tabs.size - 1 else targetIndex
                switchTab(tabs[nextIndex].id)
            } else {
                updateTabBadge()
            }
        }

        val dialog = tabsDialog
        if (dialog != null && dialog.isAdded && !dialog.isStateSaved) {
            dialog.setTabsData(tabs, activeTabId)
        }
    }

    private fun updateTabBadge() {
        binding.tvTabBadge.text = tabs.size.toString()
    }

    private fun getActiveTab(): TabItem? {
        return tabs.find { it.id == activeTabId }
    }

    // ==================== 页面加载与一键免密直达 ====================

    private fun loadUrlInTab(tab: TabItem, input: String) {
        val targetUrl = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.bing.com/search?q=$input"
        }

        tab.url = targetUrl
        if (tab.id == activeTabId) {
            binding.etUrl.setText(targetUrl)
            updateSecurityIcon(targetUrl)
            binding.layoutShortcuts.visibility = View.GONE
        }
        tab.webView?.loadUrl(targetUrl)
    }

    /**
     * 根据保存的 Cookie 凭据，一键写入 Cookie 并直达该网站登录态 (彻底解决进入失败与死锁问题)
     */
    private fun launchWithSavedCookie(record: SavedCookieRecord) {
        val cleanEntryUrl = DomainUtils.getCleanEntryUrl(record.url, record.domain)
        val rootDomain = DomainUtils.getRootDomain(record.domain)
        val cookies = record.getCookieList()

        if (cookies.isEmpty()) {
            Toast.makeText(this, "该记录中暂无有效 Cookie", Toast.LENGTH_SHORT).show()
            return
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        // 直接写入到目标 URL 以及根域名，避免异步计数器被中断导致无法跳转
        for (item in cookies) {
            val baseCookie = "${item.name}=${item.value}; Path=/"
            cookieManager.setCookie(cleanEntryUrl, baseCookie)

            if (rootDomain.isNotBlank()) {
                val domainCookie = "${item.name}=${item.value}; Domain=.$rootDomain; Path=/"
                cookieManager.setCookie("https://$rootDomain", domainCookie)
                cookieManager.setCookie(cleanEntryUrl, domainCookie)
            }
        }
        cookieManager.flush()

        // 立即跳转至目标网站，零阻塞！
        val current = getActiveTab()
        if (current != null && (current.url.isBlank() || current.url == "about:blank")) {
            loadUrlInTab(current, cleanEntryUrl)
        } else {
            createNewTab(initialUrl = cleanEntryUrl)
        }

        Toast.makeText(this, "已写入 Cookie，正在免密直达 ${record.domain}", Toast.LENGTH_SHORT).show()
    }

    private fun updateSecurityIcon(url: String) {
        if (url.startsWith("https://")) {
            binding.ivSecurity.setImageResource(R.drawable.ic_lock)
            binding.ivSecurity.setColorFilter(getColor(R.color.primary))
        } else {
            binding.ivSecurity.setImageResource(R.drawable.ic_lock)
            binding.ivSecurity.setColorFilter(getColor(R.color.on_surface_variant))
        }
    }

    private fun toggleActiveTabUserAgent() {
        val tab = getActiveTab() ?: return
        tab.isDesktop = !tab.isDesktop
        val settings = tab.webView?.settings ?: return

        if (tab.isDesktop) {
            settings.userAgentString = DESKTOP_UA
            binding.btnSwitchUa.setImageResource(R.drawable.ic_mobile)
            Toast.makeText(this, R.string.current_ua_desktop, Toast.LENGTH_SHORT).show()
        } else {
            settings.userAgentString = defaultMobileUa
            binding.btnSwitchUa.setImageResource(R.drawable.ic_desktop)
            Toast.makeText(this, R.string.current_ua_mobile, Toast.LENGTH_SHORT).show()
        }

        if (!tab.url.isNullOrBlank()) {
            tab.webView?.reload()
        }
    }

    private fun setupListeners() {
        // 地址栏回车加载
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val input = binding.etUrl.text.toString().trim()
                getActiveTab()?.let { loadUrlInTab(it, input) }
                hideKeyboard()
                true
            } else {
                false
            }
        }

        // 刷新
        binding.btnRefresh.setOnClickListener {
            getActiveTab()?.webView?.reload()
        }

        // 切换桌面/移动端 UA
        binding.btnSwitchUa.setOnClickListener {
            toggleActiveTabUserAgent()
        }

        // 清理 Cookie 与全量缓存
        binding.btnClear.setOnClickListener {
            showClearCookiesDialog()
        }

        // 底部导航栏：后退
        binding.btnNavBack.setOnClickListener {
            val wv = getActiveTab()?.webView
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                binding.layoutShortcuts.visibility = View.VISIBLE
            }
        }

        // 底部导航栏：前进
        binding.btnNavForward.setOnClickListener {
            val wv = getActiveTab()?.webView
            if (wv != null && wv.canGoForward()) {
                wv.goForward()
            }
        }

        // 底部导航栏：多窗口管理
        binding.btnNavTabs.setOnClickListener {
            openTabsDialog()
        }

        // 底部导航栏：Cookie 储存库
        binding.btnNavStorage.setOnClickListener {
            openStorageDialog()
        }

        // 底部导航栏：核心提取胶囊按钮
        binding.btnNavExtract.setOnClickListener {
            openCookieSheet()
        }
    }

    private fun openTabsDialog() {
        val dialog = TabsBottomSheetDialog.newInstance()
        tabsDialog = dialog
        dialog.onTabSelected = { tabId ->
            switchTab(tabId)
        }
        dialog.onTabClosed = { tabId ->
            closeTab(tabId)
        }
        dialog.onNewTabRequested = { _ ->
            createNewTab(initialUrl = "")
        }
        dialog.onDismissListener = {
            tabsDialog = null
        }
        dialog.show(supportFragmentManager, "TabsBottomSheet")
        dialog.setTabsData(tabs, activeTabId)
    }

    private fun openStorageDialog() {
        val dialog = StorageBottomSheetDialog.newInstance()
        // 一键直达并注入 Cookie 核心交互
        dialog.onLaunchRecord = { record ->
            launchWithSavedCookie(record)
        }
        dialog.onViewRecordDetails = { record ->
            val ua = getActiveTab()?.webView?.settings?.userAgentString ?: defaultMobileUa
            val detailDialog = CookieBottomSheetDialog.newInstanceFromRecord(record, ua)
            detailDialog.show(supportFragmentManager, "CookieDetailSheet")
        }
        dialog.show(supportFragmentManager, "StorageBottomSheet")
    }

    private fun openCookieSheet() {
        val tab = getActiveTab()
        val currentUrl = tab?.webView?.url ?: tab?.url ?: binding.etUrl.text.toString()
        val currentTitle = tab?.title ?: ""
        val currentUa = tab?.webView?.settings?.userAgentString ?: defaultMobileUa

        val dialog = CookieBottomSheetDialog.newInstance(currentUrl, currentTitle, currentUa)
        dialog.show(supportFragmentManager, "CookieBottomSheet")
    }

    private fun showClearCookiesDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_all_cookies)
            .setMessage(R.string.clear_confirm_message)
            .setPositiveButton("立即清除") { _, _ ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeAllCookies {
                    cookieManager.flush()
                    tabs.forEach {
                        it.webView?.clearCache(true)
                        it.webView?.clearHistory()
                    }
                    Toast.makeText(this, R.string.clear_success, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupShortcuts() {
        val shortcuts = mapOf(
            binding.chipBilibili to "https://m.bilibili.com",
            binding.chipWeibo to "https://weibo.com",
            binding.chipGithub to "https://github.com/login",
            binding.chipBaidu to "https://www.baidu.com",
            binding.chipZhihu to "https://www.zhihu.com",
            binding.chipV2ex to "https://www.v2ex.com"
        )
        shortcuts.forEach { (chip, url) ->
            chip.setOnClickListener {
                getActiveTab()?.let { tab -> loadUrlInTab(tab, url) }
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = getActiveTab()?.webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else if (binding.layoutShortcuts.visibility != View.VISIBLE) {
                    binding.layoutShortcuts.visibility = View.VISIBLE
                } else if (tabs.size > 1) {
                    getActiveTab()?.let { closeTab(it.id) }
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

    override fun onDestroy() {
        tabs.forEach { it.webView?.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
