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
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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

        private fun cleanUserAgent(raw: String): String {
            // 剥除 Android WebView 专有的 Version/4.0 和 ; wv 标记，模拟官方纯净 Chrome，避免触发 Google 等站点的 CookieMismatch 拦截
            return raw.replace("; wv", "")
                .replace("Version/4.0 ", "")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 默认新建一个初始主身份标签页
        createNewTab(initialUrl = "", isClone = false)

        setupListeners()
        setupShortcuts()
        setupBackNavigation()
    }

    // ==================== 多窗口与多账号网页分身 ====================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewTab(initialUrl: String = "", isClone: Boolean = false): TabItem {
        val tabId = UUID.randomUUID().toString()
        val webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val settings = webView.settings
        if (defaultMobileUa.isEmpty()) {
            defaultMobileUa = cleanUserAgent(settings.userAgentString)
        }
        settings.userAgentString = defaultMobileUa

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 开启多窗口支持与脚本弹窗代理，适配 target="_blank"
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        val profileName = if (isClone) {
            "Profile_Clone_${UUID.randomUUID().toString().take(8)}"
        } else {
            "Profile_Default"
        }

        val newTab = TabItem(
            id = tabId,
            title = if (isClone) "网页分身" else "新标签页",
            url = initialUrl,
            isDesktop = false,
            isClone = isClone,
            profileName = profileName,
            webView = webView
        )

        // 核心：基于 AndroidX WebKit 架构实现真正的引擎级分身隔离 (各 Profile 持有完全独立的 Cookie 与 Storage)
        setupProfileForTab(newTab, webView)

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
                val spawnedTab = createNewTab(initialUrl = "", isClone = newTab.isClone)
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
                val cm = newTab.customCookieManager ?: CookieManager.getInstance()
                cm.flush()
                url?.let {
                    newTab.url = it
                    if (newTab.id == activeTabId) {
                        updateSecurityIcon(it)
                    }
                }
                unlockViewportScaling(view)
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

    private fun setupProfileForTab(tab: TabItem, webView: WebView) {
        val globalCm = CookieManager.getInstance()
        globalCm.setAcceptCookie(true)
        globalCm.setAcceptThirdPartyCookies(webView, true)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                val profileStore = ProfileStore.getInstance()
                val profile = profileStore.getOrCreateProfile(tab.profileName)
                WebViewCompat.setProfile(webView, tab.profileName)
                tab.customCookieManager = profile.cookieManager
            } catch (e: Exception) {
                e.printStackTrace()
                tab.customCookieManager = globalCm
            }
        } else {
            tab.customCookieManager = globalCm
        }

        val cm = tab.customCookieManager ?: globalCm
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
    }

    private fun switchTab(targetTabId: String) {
        val targetTab = tabs.find { it.id == targetTabId } ?: return
        activeTabId = targetTabId

        // 仅切换可见容器，各 Profile 保持完全独立的 Cookie 会话，绝不清除，绝不掉线！
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

        // 释放分身 Profile 存储资源
        if (removingTab.isClone && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().deleteProfile(removingTab.profileName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        tabs.removeAt(targetIndex)

        if (tabs.isEmpty()) {
            createNewTab(initialUrl = "", isClone = false)
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
     * 根据保存的 Cookie 凭据，一键写入 Cookie 并直达该网站登录态 (完美支持 __Secure- / __Host- 前缀)
     */
    private fun launchWithSavedCookie(record: SavedCookieRecord) {
        val cleanEntryUrl = DomainUtils.getCleanEntryUrl(record.url, record.domain)
        val rootDomain = DomainUtils.getRootDomain(record.domain)
        val cookies = record.getCookieList()

        if (cookies.isEmpty()) {
            Toast.makeText(this, "该记录中暂无有效 Cookie", Toast.LENGTH_SHORT).show()
            return
        }

        // 选择当前活动的空白标签或新建标签
        val activeTab = getActiveTab()
        val targetTab = if (activeTab != null && (activeTab.url.isBlank() || activeTab.url == "about:blank")) {
            activeTab
        } else {
            createNewTab(initialUrl = "", isClone = false)
        }

        val cookieManager = targetTab.customCookieManager ?: CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        targetTab.webView?.let { cookieManager.setAcceptThirdPartyCookies(it, true) }

        val isHttps = cleanEntryUrl.startsWith("https://")
        val domainUrl = if (rootDomain.isNotEmpty()) "https://$rootDomain" else cleanEntryUrl

        var pendingCount = 0
        val writeList = mutableListOf<Pair<String, String>>()

        for (item in cookies) {
            val isSecureCookie = isHttps ||
                    item.name.startsWith("__Secure-") ||
                    item.name.startsWith("__Host-") ||
                    item.isSecure

            val secureAttr = if (isSecureCookie) "; Secure; SameSite=Lax" else ""

            if (item.name.startsWith("__Host-")) {
                // RFC 6265bis 规范：__Host- 前缀绝对不能携带 Domain 属性，必须带有 Path=/ 和 Secure
                val hostCookie = "${item.name}=${item.value}; Path=/; Secure"
                writeList.add(Pair(cleanEntryUrl, hostCookie))
            } else {
                // 基础路径注入
                val baseCookie = "${item.name}=${item.value}; Path=/$secureAttr"
                writeList.add(Pair(cleanEntryUrl, baseCookie))

                // 根域名注入（保证子域和 API 均可携带，如 hark.com / bilibili.com）
                // 排除 accounts.google. 等高敏子域，防止因作用域冲突触发 Google CookieMismatch 拦截
                val isStrictSubdomain = record.domain.startsWith("accounts.google.") ||
                        record.domain.startsWith("login.")
                if (rootDomain.isNotEmpty() && !isStrictSubdomain) {
                    val domainCookie = "${item.name}=${item.value}; Domain=.$rootDomain; Path=/$secureAttr"
                    writeList.add(Pair(domainUrl, domainCookie))
                }
            }
        }

        pendingCount = writeList.size

        val onAllDone = {
            cookieManager.flush()
            binding.root.post {
                loadUrlInTab(targetTab, cleanEntryUrl)
                Toast.makeText(this, "已写入 ${cookies.size} 项 Cookie，正在免密直达 ${record.domain}", Toast.LENGTH_SHORT).show()
            }
        }

        for ((target, cookieStr) in writeList) {
            cookieManager.setCookie(target, cookieStr) {
                pendingCount--
                if (pendingCount <= 0) {
                    onAllDone()
                }
            }
        }
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

        // 顶部一键即时翻译为中文
        binding.btnTranslate.setOnClickListener {
            val wv = getActiveTab()?.webView
            if (wv != null && wv.url != null) {
                com.cookiebox.app.engine.WebTranslator.translateInPage(wv, "zh-CN")
                Toast.makeText(this, "正在将当前网页翻译为中文...", Toast.LENGTH_SHORT).show()
            }
        }

        // 顶部页面缩放与工具箱
        binding.btnMoreTools.setOnClickListener {
            openToolsDialog()
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
        dialog.onNewTabRequested = { isClone ->
            createNewTab(initialUrl = "", isClone = isClone)
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
            detailDialog.customCookieManager = getActiveTab()?.customCookieManager
            detailDialog.show(supportFragmentManager, "CookieDetailSheet")
        }
        dialog.show(supportFragmentManager, "StorageBottomSheet")
    }

    private fun unlockViewportScaling(webView: WebView?) {
        val js = """
            (function() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (meta) {
                    var c = meta.getAttribute('content') || '';
                    c = c.replace(/user-scalable\s*=\s*no/gi, 'user-scalable=yes')
                         .replace(/maximum-scale\s*=\s*1(\.0)?/gi, 'maximum-scale=5.0');
                    if (!c.includes('user-scalable')) {
                        c += ', user-scalable=yes, maximum-scale=5.0';
                    }
                    meta.setAttribute('content', c);
                }
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    private fun openToolsDialog() {
        val tab = getActiveTab() ?: return
        val wv = tab.webView ?: return
        val currentTextZoom = wv.settings.textZoom

        val dialog = com.cookiebox.app.ui.WebToolsBottomSheetDialog.newInstance(currentTextZoom)
        dialog.onZoomChanged = { zoom ->
            wv.settings.textZoom = zoom
        }
        dialog.onTranslateInPage = {
            com.cookiebox.app.engine.WebTranslator.translateInPage(wv, "zh-CN")
            Toast.makeText(this, "正在网页内即时翻译为中文...", Toast.LENGTH_SHORT).show()
        }
        dialog.onTranslateProxy = {
            val currentUrl = wv.url ?: tab.url
            if (currentUrl.isNotBlank()) {
                val proxyUrl = com.cookiebox.app.engine.WebTranslator.getTranslateProxyUrl(currentUrl, "zh-CN")
                loadUrlInTab(tab, proxyUrl)
                Toast.makeText(this, "正在通过翻译阅读器载入...", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.onRestoreTranslate = {
            wv.reload()
            Toast.makeText(this, "已重新加载并还原原网页", Toast.LENGTH_SHORT).show()
        }
        dialog.show(supportFragmentManager, "WebToolsBottomSheet")
    }

    private fun openCookieSheet() {
        val tab = getActiveTab()
        val currentUrl = tab?.webView?.url ?: tab?.url ?: binding.etUrl.text.toString()
        val currentTitle = tab?.title ?: ""
        val currentUa = tab?.webView?.settings?.userAgentString ?: defaultMobileUa

        val dialog = CookieBottomSheetDialog.newInstance(currentUrl, currentTitle, currentUa)
        dialog.customCookieManager = tab?.customCookieManager
        dialog.show(supportFragmentManager, "CookieBottomSheet")
    }

    private fun showClearCookiesDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_all_cookies)
            .setMessage(R.string.clear_confirm_message)
            .setPositiveButton("立即清除") { _, _ ->
                val activeTab = getActiveTab()
                val cm = activeTab?.customCookieManager ?: CookieManager.getInstance()
                cm.removeAllCookies {
                    cm.flush()
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
