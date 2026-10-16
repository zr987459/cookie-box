package com.cookiebox.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.cookiebox.app.databinding.ActivityMainBinding
import com.cookiebox.app.engine.BrowserPrefs
import com.cookiebox.app.engine.DomainUtils
import com.cookiebox.app.engine.WebTranslator
import com.cookiebox.app.model.SavedCookieRecord
import com.cookiebox.app.model.TabItem
import com.cookiebox.app.ui.CookieBottomSheetDialog
import com.cookiebox.app.ui.StorageBottomSheetDialog
import com.cookiebox.app.ui.TabsBottomSheetDialog
import com.cookiebox.app.ui.WebToolsBottomSheetDialog
import com.google.android.material.chip.Chip
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: BrowserPrefs

    private val tabs = mutableListOf<TabItem>()
    private var activeTabId: String = ""
    private var defaultMobileUa: String = ""

    private var tabsDialog: TabsBottomSheetDialog? = null

    // 文件上传
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback
            filePathCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        }

    // 视频全屏
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // 旧系统（Android 9 及以下）下载需要存储权限
    private var pendingDownload: (() -> Unit)? = null
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingDownload
            pendingDownload = null
            if (granted) action?.invoke() else toast("没有存储权限，无法下载")
        }

    private var lastBackPressAt = 0L

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val DEFAULT_PROFILE = "Profile_Default"

        private fun cleanUserAgent(raw: String): String {
            // 剥除 Android WebView 专有的 Version/4.0 和 ; wv 标记，模拟官方纯净 Chrome，避免触发 Google 等站点的 CookieMismatch 拦截
            return raw.replace("; wv", "")
                .replace("Version/4.0 ", "")
        }

        /** 解除网站对双指缩放的封锁：仅改写 viewport 属性，绝不污染 window 变量或插入多余节点，防止触发反爬虫检测 */
        private val UNLOCK_ZOOM_JS = """
            (function() {
                var metas = document.querySelectorAll('meta[name="viewport"]');
                for (var i = 0; i < metas.length; i++) {
                    var c = metas[i].getAttribute('content') || '';
                    if (/user-scalable\s*=\s*(no|0)/i.test(c)) {
                        metas[i].setAttribute('content', c.replace(/user-scalable\s*=\s*(no|0)/gi, 'user-scalable=yes').replace(/maximum-scale\s*=\s*[0-9.]+/gi, 'maximum-scale=5.0'));
                    }
                }
            })();
        """.trimIndent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = BrowserPrefs(this)

        restoreSessionOrCreateTab()

        setupListeners()
        renderShortcuts()
        setupBackNavigation()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        saveSession()
        tabs.forEach { (it.customCookieManager ?: CookieManager.getInstance()).flush() }
    }

    /** 外部分享/打开链接到本浏览器 */
    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                Regex("https?://\\S+").find(text)?.value ?: text
            }
            else -> null
        }
        if (!url.isNullOrBlank()) {
            val active = getActiveTab()
            if (active != null && active.url.isBlank()) loadUrlInTab(active, url) else createNewTab(url, false)
        }
    }

    // ==================== 会话保存与恢复 ====================

    private fun saveSession() {
        val saved = tabs.map {
            BrowserPrefs.SavedTab(
                url = it.webView?.url?.takeIf { u -> u != "about:blank" } ?: it.pendingUrl ?: it.url,
                title = it.title,
                isDesktop = it.isDesktop,
                isClone = it.isClone,
                profileName = it.profileName
            )
        }
        prefs.saveSession(saved, tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0))
    }

    private fun restoreSessionOrCreateTab() {
        val (saved, activeIndex) = prefs.loadSession()
        if (saved.isEmpty()) {
            createNewTab(initialUrl = "", isClone = false)
            return
        }
        val created = saved.map { s ->
            createNewTab(
                initialUrl = "",
                isClone = s.isClone,
                profileName = s.profileName,
                switchTo = false,
                desktop = s.isDesktop
            ).also {
                it.title = s.title.ifBlank { it.title }
                it.url = s.url
                // 登录流程的网址带一次性参数，重启后再打开会报 400，恢复成空白标签
                if (isSensitiveLoginPage(s.url)) {
                    it.url = ""
                    it.title = if (it.isClone) "网页分身" else "新标签页"
                } else {
                    it.pendingUrl = s.url.takeIf { u -> u.isNotBlank() }
                }
            }
        }
        switchTab(created[activeIndex.coerceIn(0, created.size - 1)].id)
    }

    // ==================== 多窗口与多账号网页分身 ====================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewTab(
        initialUrl: String = "",
        isClone: Boolean = false,
        profileName: String? = null,
        openerId: String? = null,
        switchTo: Boolean = true,
        desktop: Boolean = false
    ): TabItem {
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
        settings.userAgentString = if (desktop) DESKTOP_UA else defaultMobileUa
        applyBrowserIdentity(settings, desktop)

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // 双指缩放：开启内置缩放手势，隐藏老式 +/- 按钮
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 开启多窗口支持与脚本弹窗代理，适配 target="_blank"
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        val resolvedProfile = profileName ?: if (isClone) {
            "Profile_Clone_${UUID.randomUUID().toString().take(8)}"
        } else {
            DEFAULT_PROFILE
        }

        val newTab = TabItem(
            id = tabId,
            title = if (isClone) "网页分身" else "新标签页",
            url = initialUrl,
            isDesktop = desktop,
            isClone = isClone,
            profileName = resolvedProfile,
            webView = webView,
            openerId = openerId
        )

        // 核心：基于 AndroidX WebKit 架构实现真正的引擎级分身隔离 (各 Profile 持有完全独立的 Cookie 与 Storage)
        setupProfileForTab(newTab, webView)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    newTab.title = title
                    refreshTabsDialog()
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
                // 弹窗沿用打开者的身份（同一个 Profile），保证登录态一致
                val spawnedTab = createNewTab(
                    initialUrl = "",
                    isClone = newTab.isClone,
                    profileName = newTab.profileName,
                    openerId = newTab.id,
                    desktop = newTab.isDesktop
                )
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

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    if (params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    toast("无法打开文件选择器")
                    false
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                binding.layoutFullscreen.addView(
                    view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                binding.layoutFullscreen.visibility = View.VISIBLE
                setSystemBarsHidden(true)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val scheme = request.url.scheme?.lowercase() ?: return false
                if (scheme in setOf("http", "https", "about", "data", "blob", "javascript", "file")) {
                    return false
                }
                // weixin:// / intent:// 等外部应用链接：仅在用户点击时询问是否打开，避免网页自动跳转骚扰
                if (request.hasGesture()) {
                    handleExternalScheme(newTab, url)
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let {
                    if (it == "about:blank") return@let
                    newTab.url = it
                    newTab.pendingUrl = null
                    if (newTab.id == activeTabId) {
                        if (!binding.etUrl.isFocused) {
                            binding.etUrl.setText(it)
                        }
                        updateSecurityIcon(it)
                        binding.layoutShortcuts.visibility = View.GONE
                    }
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val cm = newTab.customCookieManager ?: CookieManager.getInstance()
                cm.flush()
                url?.let {
                    if (it == "about:blank") return@let
                    newTab.url = it
                    if (newTab.id == activeTabId) {
                        updateSecurityIcon(it)
                    }
                }
                unlockZoom(view)
                if (newTab.id == activeTabId) updateNavButtons()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url == "about:blank") {
                    // 回退到了主页
                    newTab.url = ""
                    if (newTab.id == activeTabId) {
                        binding.etUrl.setText("")
                        updateSecurityIcon("")
                        binding.layoutShortcuts.visibility = View.VISIBLE
                    }
                }
                if (newTab.id == activeTabId) {
                    if (url != null && url != "about:blank" && !binding.etUrl.isFocused) binding.etUrl.setText(url)
                    if (url != null && url != "about:blank") binding.layoutShortcuts.visibility = View.GONE
                    updateNavButtons()
                }
            }
        }

        // 下载：带上当前身份的 Cookie 和 UA，登录后才能下的文件也能下载
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(newTab, url, userAgent, contentDisposition, mimeType)
        }

        // 长按链接 / 图片
        webView.setOnLongClickListener { showLongPressMenu(newTab) }

        tabs.add(newTab)
        if (switchTo) switchTab(tabId) else updateTabBadge()

        if (initialUrl.isNotBlank()) {
            loadUrlInTab(newTab, initialUrl)
        }

        return newTab
    }

    /**
     * 还原真实无指纹 Chrome 浏览器身份：
     * 1. 彻底拦截并移除 WebView 默认带的 X-Requested-With 头（防止 Cloudflare / Hark 识别出应用包名）
     * 2. 规范化 Client Hints（Sec-CH-UA / navigator.userAgentData），与官方独立 Chrome 完全一致
     */
    private fun applyBrowserIdentity(settings: WebSettings, desktop: Boolean) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                val ua = settings.userAgentString
                val m = Regex("Chrome/(\\d+)([\\d.]*)").find(ua)
                val major = m?.groupValues?.get(1) ?: "124"
                val full = m?.let { it.groupValues[1] + it.groupValues[2] } ?: "$major.0.0.0"
                val brands = listOf(
                    UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build(),
                    UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build(),
                    UserAgentMetadata.BrandVersion.Builder().setBrand("Not_A Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build()
                )
                val builder = UserAgentMetadata.Builder()
                    .setBrandVersionList(brands)
                    .setFullVersion(full)
                    .setMobile(!desktop)
                if (desktop) {
                    builder.setPlatform("Windows").setPlatformVersion("10.0.0").setModel("").setArchitecture("x86").setBitness(64)
                } else {
                    builder.setPlatform("Android").setPlatformVersion(Build.VERSION.RELEASE ?: "14").setModel(Build.MODEL ?: "")
                }
                WebSettingsCompat.setUserAgentMetadata(settings, builder.build())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 登录页（Google、Hark 等）对页面被改动极度敏感，绝对不注入任何脚本 */
    private fun isSensitiveLoginPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path?.lowercase() ?: ""
        return host.startsWith("accounts.") || host.startsWith("login.") || host.startsWith("signin.") ||
                host.startsWith("passport.") || host.startsWith("auth.") || host.startsWith("sso.") ||
                host.endsWith("accounts.google.com") || host.endsWith("accounts.youtube.com") ||
                path.contains("login") || path.contains("signin") || path.contains("auth") || path.contains("signup") ||
                host == "hark.com"
    }

    private fun unlockZoom(view: WebView?) {
        val wv = view ?: return
        if (isSensitiveLoginPage(wv.url)) return
        wv.evaluateJavascript(UNLOCK_ZOOM_JS, null)
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
        closeFindBar()

        // 仅切换可见容器，各 Profile 保持完全独立的 Cookie 会话，绝不清除，绝不掉线！
        binding.layoutWebContainer.removeAllViews()
        targetTab.webView?.let {
            if (it.parent != null) {
                (it.parent as? ViewGroup)?.removeView(it)
            }
            binding.layoutWebContainer.addView(it)
        }

        // 恢复的标签切过来时才真正加载，避免启动时同时加载所有网页
        targetTab.pendingUrl?.let { pending ->
            targetTab.pendingUrl = null
            targetTab.webView?.loadUrl(pending)
        }

        if (targetTab.url.isNotBlank()) {
            binding.etUrl.setText(targetTab.url)
            updateSecurityIcon(targetTab.url)
            binding.layoutShortcuts.visibility = View.GONE
        } else {
            binding.etUrl.setText("")
            updateSecurityIcon("")
            binding.layoutShortcuts.visibility = View.VISIBLE
        }
        binding.progressBar.visibility = View.GONE

        updateTabBadge()
        updateNavButtons()
    }

    /** 关闭标签（分身标签会删除其登录状态，先确认） */
    private fun requestCloseTab(tabId: String) {
        val tab = tabs.find { it.id == tabId } ?: return
        val profileStillUsed = tabs.any { it.id != tabId && it.profileName == tab.profileName }
        if (tab.isClone && !profileStillUsed) {
            AlertDialog.Builder(this)
                .setTitle("关闭分身标签？")
                .setMessage("关闭后这个分身的登录状态会被删除。需要保留的话，先点「提取」→「保存至储存库」。")
                .setPositiveButton("关闭并删除") { _, _ -> closeTab(tabId) }
                .setNegativeButton("取消", null)
                .show()
        } else {
            closeTab(tabId)
        }
    }

    private fun closeTab(tabId: String) {
        val targetIndex = tabs.indexOfFirst { it.id == tabId }
        if (targetIndex < 0) return

        val removingTab = tabs[targetIndex]
        if (customView != null && tabId == activeTabId) exitFullscreen()
        removingTab.webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        tabs.removeAt(targetIndex)

        // 释放分身 Profile：只有没有其他标签（如它打开的弹窗）在用时才删除
        if (removingTab.isClone && tabs.none { it.profileName == removingTab.profileName } &&
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        ) {
            try {
                ProfileStore.getInstance().deleteProfile(removingTab.profileName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (tabs.isEmpty()) {
            createNewTab(initialUrl = "", isClone = false)
        } else if (tabId == activeTabId) {
            val opener = tabs.find { it.id == removingTab.openerId }
            if (opener != null) {
                switchTab(opener.id)
            } else {
                val nextIndex = if (targetIndex >= tabs.size) tabs.size - 1 else targetIndex
                switchTab(tabs[nextIndex].id)
            }
        } else {
            updateTabBadge()
        }

        refreshTabsDialog()
    }

    private fun refreshTabsDialog() {
        val dialog = tabsDialog
        if (dialog != null && dialog.isAdded && !dialog.isStateSaved) {
            dialog.setTabsData(tabs, activeTabId)
        }
    }

    private fun updateTabBadge() {
        binding.tvTabBadge.text = if (tabs.size > 99) "∞" else tabs.size.toString()
    }

    private fun updateNavButtons() {
        val wv = getActiveTab()?.webView
        val canBack = wv?.canGoBack() == true
        val canForward = wv?.canGoForward() == true
        binding.btnNavBack.alpha = if (canBack) 1f else 0.35f
        binding.btnNavForward.alpha = if (canForward) 1f else 0.35f
        binding.btnNavForward.isEnabled = canForward
    }

    private fun getActiveTab(): TabItem? {
        return tabs.find { it.id == activeTabId }
    }

    // ==================== 页面加载与一键免密直达 ====================

    private fun resolveInput(raw: String): String {
        val input = raw.trim()
        return when {
            input.isEmpty() -> ""
            Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(input) -> input
            input.startsWith("about:") || input.startsWith("javascript:") -> input
            !input.contains(" ") && (Patterns.WEB_URL.matcher(input).matches() ||
                    input.startsWith("localhost") ||
                    Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(input)) -> {
                if (input.startsWith("localhost") || input.first().isDigit()) "http://$input" else "https://$input"
            }
            else -> prefs.searchUrl(input)
        }
    }

    private fun loadUrlInTab(tab: TabItem, input: String) {
        val targetUrl = resolveInput(input)
        if (targetUrl.isEmpty()) return

        tab.url = targetUrl
        tab.pendingUrl = null
        if (tab.id == activeTabId) {
            binding.etUrl.setText(targetUrl)
            updateSecurityIcon(targetUrl)
            binding.layoutShortcuts.visibility = View.GONE
        }
        tab.webView?.loadUrl(targetUrl)
    }

    private fun goHome() {
        val tab = getActiveTab() ?: return
        tab.url = ""
        tab.title = if (tab.isClone) "网页分身" else "新标签页"
        tab.webView?.stopLoading()
        tab.webView?.loadUrl("about:blank")
        binding.etUrl.setText("")
        updateSecurityIcon("")
        binding.layoutShortcuts.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        refreshTabsDialog()
    }

    /**
     * 根据保存的 Cookie 凭据，一键写入 Cookie 并直达该网站登录态 (完美支持 __Secure- / __Host- 前缀)
     */
    private fun launchWithSavedCookie(record: SavedCookieRecord) {
        val cleanEntryUrl = DomainUtils.getCleanEntryUrl(record.url, record.domain)
        val rootDomain = DomainUtils.getRootDomain(record.domain)
        val cookies = record.getCookieList()

        if (cookies.isEmpty()) {
            toast("该记录中暂无有效 Cookie")
            return
        }

        // 选择当前活动的空白标签或新建标签
        val activeTab = getActiveTab()
        val targetTab = if (activeTab != null && !activeTab.isClone && (activeTab.url.isBlank() || activeTab.url == "about:blank")) {
            activeTab
        } else {
            createNewTab(initialUrl = "", isClone = false)
        }

        val cookieManager = targetTab.customCookieManager ?: CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        targetTab.webView?.let { cookieManager.setAcceptThirdPartyCookies(it, true) }

        val isHttps = cleanEntryUrl.startsWith("https://")
        val isStrictSubdomain = record.domain.startsWith("accounts.google.") || record.domain.startsWith("login.")

        var pendingCount: Int
        val writeList = mutableListOf<Pair<String, String>>()

        for (item in cookies) {
            val isSecureCookie = isHttps ||
                    item.name.startsWith("__Secure-") ||
                    item.name.startsWith("__Host-") ||
                    item.isSecure

            val secureAttr = if (isSecureCookie) "; Secure; SameSite=Lax" else ""

            if (item.name.startsWith("__Host-") || isStrictSubdomain) {
                // RFC 6265bis：__Host- 必须是 host-only，不能带 Domain 属性，只按当前主机写一份
                writeList.add(Pair(cleanEntryUrl, "${item.name}=${item.value}; Path=/; Secure"))
            } else if (rootDomain.isNotEmpty()) {
                // 如果有根域名（如 hark.com / bilibili.com），只写一份带 Domain=.rootDomain 的 Cookie！
                // 这样当前域名、所有子域、所有 API 都会自动带上该 Cookie，并且绝对不会出现重复重名的冲突！
                writeList.add(Pair("https://$rootDomain", "${item.name}=${item.value}; Domain=.$rootDomain; Path=/$secureAttr"))
            } else {
                writeList.add(Pair(cleanEntryUrl, "${item.name}=${item.value}; Path=/$secureAttr"))
            }
        }

        pendingCount = writeList.size

        val onAllDone = {
            cookieManager.flush()
            binding.root.post {
                loadUrlInTab(targetTab, cleanEntryUrl)
                toast("已写入 ${cookies.size} 项 Cookie，正在免密直达 ${record.domain}")
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
        } else if (url.isBlank()) {
            binding.ivSecurity.setImageResource(R.drawable.ic_search)
            binding.ivSecurity.setColorFilter(getColor(R.color.on_surface_variant))
        } else {
            binding.ivSecurity.setImageResource(R.drawable.ic_public)
            binding.ivSecurity.setColorFilter(getColor(R.color.on_surface_variant))
        }
    }

    private fun toggleActiveTabUserAgent() {
        val tab = getActiveTab() ?: return
        tab.isDesktop = !tab.isDesktop
        val settings = tab.webView?.settings ?: return

        if (tab.isDesktop) {
            settings.userAgentString = DESKTOP_UA
            applyBrowserIdentity(settings, true)
            toast(getString(R.string.current_ua_desktop))
        } else {
            settings.userAgentString = defaultMobileUa
            applyBrowserIdentity(settings, false)
            toast(getString(R.string.current_ua_mobile))
        }

        if (tab.url.isNotBlank()) {
            tab.webView?.reload()
        }
    }

    // ==================== 交互 ====================

    private fun setupListeners() {
        // 地址栏回车加载
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val input = binding.etUrl.text.toString().trim()
                getActiveTab()?.let { loadUrlInTab(it, input) }
                binding.etUrl.clearFocus()
                hideKeyboard()
                true
            } else {
                false
            }
        }
        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            updateUrlBarButtons()
            if (!hasFocus) {
                // 放弃编辑时还原为当前网址
                val url = getActiveTab()?.url.orEmpty()
                if (binding.etUrl.text.toString() != url) binding.etUrl.setText(url)
            }
        }
        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateUrlBarButtons()
        })
        binding.btnClearUrl.setOnClickListener { binding.etUrl.setText("") }

        // 刷新 / 停止
        binding.btnRefresh.setOnClickListener {
            val wv = getActiveTab()?.webView ?: return@setOnClickListener
            if (binding.progressBar.visibility == View.VISIBLE) wv.stopLoading() else wv.reload()
        }

        binding.btnMenu.setOnClickListener { showMainMenu(it) }

        // 页内查找
        binding.etFind.setOnEditorActionListener { _, _, _ ->
            getActiveTab()?.webView?.findNext(true)
            true
        }
        binding.etFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val wv = getActiveTab()?.webView ?: return
                val q = s?.toString().orEmpty()
                if (q.isEmpty()) {
                    wv.clearMatches()
                    binding.tvFindCount.text = "0/0"
                } else {
                    wv.findAllAsync(q)
                }
            }
        })
        binding.btnFindPrev.setOnClickListener { getActiveTab()?.webView?.findNext(false) }
        binding.btnFindNext.setOnClickListener { getActiveTab()?.webView?.findNext(true) }
        binding.btnFindClose.setOnClickListener { closeFindBar() }

        // 底部导航栏
        binding.btnNavBack.setOnClickListener {
            val wv = getActiveTab()?.webView
            if (wv != null && wv.canGoBack()) wv.goBack()
        }
        binding.btnNavForward.setOnClickListener {
            val wv = getActiveTab()?.webView
            if (wv != null && wv.canGoForward()) wv.goForward()
        }
        binding.btnNavHome.setOnClickListener { goHome() }
        binding.btnNavTabs.setOnClickListener { openTabsDialog() }
        binding.btnNavTabs.setOnLongClickListener {
            createNewTab(initialUrl = "", isClone = false)
            toast("已新建标签页")
            true
        }
        binding.btnNavStorage.setOnClickListener { openStorageDialog() }
        binding.btnNavExtract.setOnClickListener { openCookieSheet() }
    }

    private fun updateUrlBarButtons() {
        val focused = binding.etUrl.isFocused
        binding.btnClearUrl.visibility =
            if (focused && binding.etUrl.text.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnRefresh.visibility = if (focused) View.GONE else View.VISIBLE
    }

    private fun showMainMenu(anchor: View) {
        val tab = getActiveTab()
        val hasPage = tab != null && tab.url.isNotBlank()
        val popup = PopupMenu(this, anchor)
        val m = popup.menu
        m.add(0, 1, 0, "新建标签页")
        m.add(0, 2, 1, "新建分身标签（另一个账号）")
        if (hasPage) {
            m.add(0, 3, 2, "页内查找")
            m.add(0, 4, 3, "翻译为中文")
            m.add(0, 5, 4, "缩放与翻译工具")
        }
        m.add(0, 6, 5, "桌面版网站").apply {
            isCheckable = true
            isChecked = tab?.isDesktop == true
        }
        if (hasPage) {
            m.add(0, 7, 6, "添加到首页快捷入口")
            m.add(0, 8, 7, "复制链接")
            m.add(0, 9, 8, "分享网页")
            m.add(0, 10, 9, "用其他浏览器打开")
        }
        m.add(0, 11, 10, "搜索引擎：${prefs.searchEngine}")
        m.add(0, 12, 11, "清除当前身份的 Cookie 和缓存")

        popup.setOnMenuItemClickListener { item ->
            val wv = tab?.webView
            when (item.itemId) {
                1 -> createNewTab(initialUrl = "", isClone = false)
                2 -> createNewTab(initialUrl = "", isClone = true)
                3 -> openFindBar()
                4 -> wv?.let {
                    toast("正在翻译为中文...")
                    WebTranslator.translateInPage(it, "zh-CN") { msg -> toast(msg) }
                }
                5 -> openToolsDialog()
                6 -> toggleActiveTabUserAgent()
                7 -> tab?.let {
                    val url = wv?.url ?: it.url
                    if (prefs.addShortcut(it.title, url)) {
                        renderShortcuts()
                        toast("已添加到首页")
                    } else {
                        toast("首页已有这个网址")
                    }
                }
                8 -> tab?.let { copyText(wv?.url ?: it.url, "已复制链接") }
                9 -> tab?.let { shareText(wv?.url ?: it.url, it.title) }
                10 -> tab?.let { openExternally(wv?.url ?: it.url) }
                11 -> chooseSearchEngine()
                12 -> showClearCookiesDialog()
            }
            true
        }
        popup.show()
    }

    private fun chooseSearchEngine() {
        val names = prefs.searchEngines.keys.toTypedArray()
        val current = names.indexOf(prefs.searchEngine)
        AlertDialog.Builder(this)
            .setTitle("默认搜索引擎")
            .setSingleChoiceItems(names, current) { d, which ->
                prefs.searchEngine = names[which]
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 页内查找 ----------

    private fun openFindBar() {
        val wv = getActiveTab()?.webView ?: return
        binding.layoutFindBar.visibility = View.VISIBLE
        wv.setFindListener { activeMatch, numberOfMatches, _ ->
            binding.tvFindCount.text =
                if (numberOfMatches == 0) "0/0" else "${activeMatch + 1}/$numberOfMatches"
        }
        binding.etFind.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etFind, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFindBar() {
        if (binding.layoutFindBar.visibility != View.VISIBLE) return
        binding.layoutFindBar.visibility = View.GONE
        binding.etFind.setText("")
        tabs.forEach { it.webView?.clearMatches() }
        hideKeyboard()
    }

    // ---------- 长按菜单 ----------

    private fun showLongPressMenu(tab: TabItem): Boolean {
        val wv = tab.webView ?: return false
        val hit = wv.hitTestResult
        val extra = hit.extra ?: return false
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                showLinkMenu(tab, extra, null)
                true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // extra 是图片地址，链接地址需要另外取
                val handler = Handler(Looper.getMainLooper()) { msg ->
                    val href = msg.data.getString("url")
                    showLinkMenu(tab, href?.takeIf { it.isNotBlank() } ?: extra, extra)
                    true
                }
                wv.requestFocusNodeHref(handler.obtainMessage())
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                showLinkMenu(tab, null, extra)
                true
            }
            else -> false // 其他情况交给 WebView（文字选择等）
        }
    }

    private fun showLinkMenu(tab: TabItem, link: String?, image: String?) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (link != null) {
            labels += "在新标签页打开"
            actions += { createNewTab(link, tab.isClone, tab.profileName, openerId = tab.id, desktop = tab.isDesktop) }
            labels += "在后台新标签页打开"
            actions += {
                createNewTab(link, tab.isClone, tab.profileName, openerId = tab.id, switchTo = false, desktop = tab.isDesktop)
                toast("已在后台打开")
            }
            labels += "在新分身中打开（另一个账号）"
            actions += { createNewTab(link, isClone = true) }
            labels += "复制链接"
            actions += { copyText(link, "已复制链接") }
            labels += "分享链接"
            actions += { shareText(link, null) }
        }
        if (image != null) {
            if (!image.startsWith("data:")) {
                labels += "保存图片"
                actions += { startDownload(tab, image, tab.webView?.settings?.userAgentString ?: defaultMobileUa, null, null) }
                labels += "在新标签页查看图片"
                actions += { createNewTab(image, tab.isClone, tab.profileName, openerId = tab.id, desktop = tab.isDesktop) }
            }
            labels += "复制图片链接"
            actions += { copyText(image, "已复制图片链接") }
        }
        AlertDialog.Builder(this)
            .setTitle((link ?: image).orEmpty().take(120))
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    // ---------- 下载 ----------

    private fun startDownload(tab: TabItem, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("该文件由网页脚本生成，暂不支持下载")
            return
        }
        val action = {
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    val cookies = (tab.customCookieManager ?: CookieManager.getInstance()).getCookie(url)
                    if (!cookies.isNullOrBlank()) addRequestHeader("Cookie", cookies)
                    addRequestHeader("User-Agent", userAgent ?: defaultMobileUa)
                    tab.webView?.url?.let { addRequestHeader("Referer", it) }
                    if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                    setTitle(fileName)
                    setDescription(Uri.parse(url).host)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                toast("开始下载：$fileName")
            } catch (e: Exception) {
                toast("下载失败：${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    // ---------- 外部应用链接 ----------

    private fun handleExternalScheme(tab: TabItem, url: String) {
        val intent = try {
            if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } catch (e: Exception) {
            return
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        val fallback = intent.getStringExtra("browser_fallback_url")
        // Android 11+ 的包可见性限制下可能查不到应用名，查不到也照常询问
        val appLabel = try {
            packageManager.resolveActivity(intent, 0)?.loadLabel(packageManager)?.toString()
        } catch (e: Exception) {
            null
        }
        AlertDialog.Builder(this)
            .setMessage(if (appLabel != null) "网页想打开「$appLabel」" else "网页想打开外部应用")
            .setPositiveButton("打开") { _, _ ->
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    if (!fallback.isNullOrBlank()) tab.webView?.loadUrl(fallback)
                    else toast("没有可以打开此链接的应用")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 全屏 ----------

    private fun exitFullscreen() {
        val view = customView ?: return
        binding.layoutFullscreen.removeView(view)
        binding.layoutFullscreen.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        setSystemBarsHidden(false)
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, binding.root)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------- 弹窗 ----------

    private fun openTabsDialog() {
        val dialog = TabsBottomSheetDialog.newInstance()
        tabsDialog = dialog
        dialog.onTabSelected = { tabId -> switchTab(tabId) }
        dialog.onTabClosed = { tabId -> requestCloseTab(tabId) }
        dialog.onNewTabRequested = { isClone -> createNewTab(initialUrl = "", isClone = isClone) }
        dialog.onDismissListener = { tabsDialog = null }
        dialog.show(supportFragmentManager, "TabsBottomSheet")
        dialog.setTabsData(tabs, activeTabId)
    }

    private fun openStorageDialog() {
        val dialog = StorageBottomSheetDialog.newInstance()
        dialog.defaultUserAgent = defaultMobileUa
        dialog.customCookieManager = getActiveTab()?.customCookieManager
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

    private fun openToolsDialog() {
        val tab = getActiveTab() ?: return
        val wv = tab.webView ?: return
        val currentTextZoom = wv.settings.textZoom

        val dialog = WebToolsBottomSheetDialog.newInstance(currentTextZoom)
        dialog.onZoomChanged = { zoom ->
            wv.settings.textZoom = zoom
        }
        dialog.onTranslateInPage = {
            toast("正在翻译为中文...")
            WebTranslator.translateInPage(wv, "zh-CN") { msg -> toast(msg) }
        }
        dialog.onTranslateProxy = {
            val currentUrl = wv.url ?: tab.url
            if (currentUrl.isNotBlank()) {
                val proxyUrl = WebTranslator.getTranslateProxyUrl(currentUrl, "zh-CN")
                loadUrlInTab(tab, proxyUrl)
                toast("正在通过翻译阅读器载入...")
            }
        }
        dialog.onRestoreTranslate = {
            WebTranslator.restoreInPage(wv)
            toast("已还原为原文")
        }
        dialog.show(supportFragmentManager, "WebToolsBottomSheet")
    }

    private fun openCookieSheet() {
        val tab = getActiveTab()
        val currentUrl = tab?.webView?.url?.takeIf { it != "about:blank" } ?: tab?.url ?: binding.etUrl.text.toString()
        if (currentUrl.isBlank()) {
            toast("先打开并登录一个网站，再点提取")
            return
        }
        val currentTitle = tab?.title ?: ""
        val currentUa = tab?.webView?.settings?.userAgentString ?: defaultMobileUa

        val dialog = CookieBottomSheetDialog.newInstance(currentUrl, currentTitle, currentUa)
        dialog.customCookieManager = tab?.customCookieManager
        dialog.show(supportFragmentManager, "CookieBottomSheet")
    }

    private fun showClearCookiesDialog() {
        val activeTab = getActiveTab() ?: return
        val who = if (activeTab.isClone) "这个分身" else "默认身份（所有非分身标签）"
        AlertDialog.Builder(this)
            .setTitle("清除 Cookie 和缓存")
            .setMessage("将清除$who 的全部 Cookie、网站存储和缓存，并登出其中所有账号。其他分身不受影响。")
            .setPositiveButton("立即清除") { _, _ ->
                val cm = activeTab.customCookieManager ?: CookieManager.getInstance()
                cm.removeAllCookies {
                    cm.flush()
                    clearWebStorage(activeTab.profileName)
                    tabs.filter { it.profileName == activeTab.profileName }.forEach {
                        it.webView?.clearCache(true)
                    }
                    toast(getString(R.string.clear_success))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearWebStorage(profileName: String) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getProfile(profileName)?.webStorage?.deleteAllData()
            } else {
                WebStorage.getInstance().deleteAllData()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------- 首页快捷入口 ----------

    private fun renderShortcuts() {
        val group = binding.chipGroupShortcuts
        group.removeAllViews()
        prefs.getShortcuts().forEach { sc ->
            val chip = Chip(this).apply {
                text = sc.title
                isCheckable = false
                setOnClickListener { getActiveTab()?.let { tab -> loadUrlInTab(tab, sc.url) } }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(sc.title)
                        .setMessage(sc.url)
                        .setPositiveButton("从首页删除") { _, _ ->
                            prefs.removeShortcut(sc.url)
                            renderShortcuts()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            }
            group.addView(chip)
        }
    }

    // ---------- 返回键 ----------

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = getActiveTab()
                val wv = tab?.webView
                when {
                    customView != null -> exitFullscreen()
                    binding.layoutFindBar.visibility == View.VISIBLE -> closeFindBar()
                    binding.etUrl.isFocused -> {
                        binding.etUrl.clearFocus()
                        hideKeyboard()
                    }
                    wv != null && wv.canGoBack() -> wv.goBack()
                    // 由链接/弹窗打开的标签，退到底时关闭并回到原标签
                    tab != null && tab.openerId != null && tabs.any { it.id == tab.openerId } -> requestCloseTab(tab.id)
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressAt < 2000) {
                            finish()
                        } else {
                            lastBackPressAt = now
                            toast("再按一次退出")
                        }
                    }
                }
            }
        })
    }

    // ---------- 工具 ----------

    private fun copyText(text: String, tip: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("CookieBox", text))
        toast(tip)
    }

    private fun shareText(text: String, subject: String?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        startActivity(Intent.createChooser(intent, "分享到"))
    }

    private fun openExternally(url: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "用其他浏览器打开"))
        } catch (e: Exception) {
            toast("没有可用的浏览器")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    override fun onDestroy() {
        tabs.forEach { it.webView?.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
