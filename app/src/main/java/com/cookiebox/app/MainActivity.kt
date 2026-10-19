package com.cookiebox.app

import android.Manifest
import android.annotation.SuppressLint
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
import com.cookiebox.app.engine.AccountAutofill
import com.cookiebox.app.engine.AccountVault
import com.cookiebox.app.engine.BrowserPrefs
import com.cookiebox.app.engine.DomainUtils
import com.cookiebox.app.engine.FileDownloader
import com.cookiebox.app.engine.WebTranslator
import com.cookiebox.app.model.SavedCookieRecord
import com.cookiebox.app.model.TabItem
import com.cookiebox.app.model.AccountCredential
import com.cookiebox.app.ui.AccountsBottomSheetDialog
import com.cookiebox.app.ui.CookieBottomSheetDialog
import com.cookiebox.app.ui.StorageBottomSheetDialog
import com.cookiebox.app.ui.TabsBottomSheetDialog
import com.cookiebox.app.ui.WebToolsBottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: BrowserPrefs
    private lateinit var vault: AccountVault
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 打开网站后等待自动填入的账号：tabId -> (账号, 密码) */
    private val pendingFills = HashMap<String, Pair<AccountCredential, String>>()
    /** 每个页面只提示一次可填入：tabId -> url */
    private val fillPromptedUrl = HashMap<String, String>()

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

    // 应用内下载器（不依赖系统下载管理器）
    private val downloader by lazy { FileDownloader(this) }
    // blob: 下载请求的一次性令牌，防止网页自己调用桥接接口乱存文件
    private val blobTokens = mutableMapOf<String, String>()
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val DEFAULT_PROFILE = "Profile_Default"
        private const val MAX_TAB_NAME_LENGTH = 24

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

        /** 电脑版：把布局视口强制改为 1024px，让响应式网站按桌面宽度排版，再缩放适配屏幕 */
        private val FORCE_DESKTOP_VIEWPORT_JS = """
            (function () {
                var w = 1024;
                var m = document.querySelector("meta[name='viewport']");
                if (!m) {
                    m = document.createElement('meta');
                    m.setAttribute('name', 'viewport');
                    (document.head || document.documentElement).appendChild(m);
                }
                m.setAttribute('content', 'width=' + w + ', initial-scale=' + (screen.width / w) + ', user-scalable=yes');
            })();
        """.trimIndent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = BrowserPrefs(this)
        vault = AccountVault(this)

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
                profileName = it.profileName,
                customName = it.customName
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
                it.customName = s.customName
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
        // 分身标签里打开的新窗口属于同一个账号，沿用打开者的自定义名
        tabs.firstOrNull { it.id == openerId }?.let { opener ->
            if (opener.isClone && opener.profileName == resolvedProfile) newTab.customName = opener.customName
        }

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
                if (newTab.isDesktop) applyDesktopViewport(view)
                checkLoginForm(newTab)
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
        webView.addJavascriptInterface(BlobBridge(), "CookieBoxDl")

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
                path.contains("/login") || path.contains("/signin") || path.contains("/signup") ||
                (host == "hark.com" && path.contains("login"))
    }

    private fun applyDesktopViewport(view: WebView?) {
        val wv = view ?: return
        if (isSensitiveLoginPage(wv.url)) return
        wv.evaluateJavascript(FORCE_DESKTOP_VIEWPORT_JS, null)
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

    private fun showRenameTabDialog(tabId: String) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(this).apply {
            setText(tab.customName)
            setSelection(text.length)
            hint = tab.title.ifBlank { "例如：B站大号、微博小号2" }
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_TAB_NAME_LENGTH))
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("标签名称")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                applyTabName(tab, input.text.toString())
            }
            .setNegativeButton("取消", null)
        if (tab.customName.isNotBlank()) {
            builder.setNeutralButton("恢复默认") { _, _ -> applyTabName(tab, "") }
        }
        builder.show()
    }

    private fun applyTabName(tab: TabItem, raw: String) {
        // 去掉换行与首尾空白，避免列表错位；只存本机，不含任何凭据
        tab.customName = raw.replace(Regex("\\s+"), " ").trim().take(MAX_TAB_NAME_LENGTH)
        saveSession()
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
        m.add(0, 13, 1, "账号管理（账号密码）")
        if (hasPage) m.add(0, 14, 2, "保存本页填写的账号密码")

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
                13 -> openAccountsDialog()
                14 -> tab?.let { saveCurrentPageLogin(it) }
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
        when {
            url.startsWith("blob:") -> downloadBlob(tab, url, contentDisposition, mimeType)
            url.startsWith("data:") -> saveDataUrl(url, contentDisposition, mimeType)
            url.startsWith("http://") || url.startsWith("https://") -> {
                val cm = tab.customCookieManager ?: CookieManager.getInstance()
                cm.flush()
                val ua = userAgent?.takeIf { it.isNotBlank() } ?: tab.webView?.settings?.userAgentString ?: defaultMobileUa
                val referer = tab.webView?.url
                withStoragePermission {
                    askNotificationPermissionOnce()
                    toast("开始下载…")
                    downloader.download(url, ua, referer, cm, contentDisposition, mimeType) { result, error ->
                        onDownloadFinished(result, error)
                    }
                }
            }
            else -> toast("不支持下载这种链接")
        }
        closeBlankDownloadTab(tab)
    }

    /** 网页用 target=_blank 打开下载链接时会留下一个空白标签，下载开始后自动关掉它 */
    private fun closeBlankDownloadTab(tab: TabItem) {
        if (tab.openerId == null || tabs.size <= 1) return
        val current = tab.webView?.url
        if (tab.webView?.canGoBack() == true) return
        if (current.isNullOrBlank() || current == "about:blank") {
            binding.root.post { if (tabs.any { it.id == tab.id }) closeTab(tab.id) }
        }
    }

    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val sp = getSharedPreferences("cookiebox_download", MODE_PRIVATE)
        if (sp.getBoolean("asked_notify", false)) return
        sp.edit().putBoolean("asked_notify", true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun onDownloadFinished(result: FileDownloader.Result?, error: String?) {
        if (isFinishing || isDestroyed) return
        if (result == null) {
            toast("下载失败：${error ?: "未知错误"}")
            return
        }
        Snackbar.make(binding.root, "已保存到「下载」：${result.fileName}", Snackbar.LENGTH_LONG)
            .setAction("打开") { openDownloaded(result) }
            .show()
    }

    private fun openDownloaded(result: FileDownloader.Result) {
        try {
            startActivity(downloader.openIntent(result))
        } catch (e: Exception) {
            toast("没有可以打开该文件的应用")
        }
    }

    private fun saveDataUrl(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val comma = url.indexOf(',')
            if (comma < 0) throw IllegalArgumentException("格式错误")
            val meta = url.substring(5, comma)
            val payload = url.substring(comma + 1)
            val isBase64 = meta.endsWith(";base64", ignoreCase = true)
            val mime = meta.substringBefore(';').ifBlank { mimeType ?: "application/octet-stream" }
            val bytes = if (isBase64) Base64.decode(payload, Base64.DEFAULT)
            else Uri.decode(payload).toByteArray(Charsets.UTF_8)
            val name = URLUtil.guessFileName(null, contentDisposition, mime)
            withStoragePermission {
                downloader.saveBytes(bytes, name, mime) { result, error -> onDownloadFinished(result, error) }
            }
        } catch (e: Exception) {
            toast("下载失败：${e.message}")
        }
    }

    /** blob: 地址只能在网页里读取：注入脚本把数据转成 base64 交回来保存 */
    private fun downloadBlob(tab: TabItem, url: String, contentDisposition: String?, mimeType: String?) {
        val wv = tab.webView ?: return
        val token = UUID.randomUUID().toString()
        val name = URLUtil.guessFileName(null, contentDisposition, mimeType)
        blobTokens[token] = name

        val js = """
            (function () {
                var t = ${JSONObject.quote(token)};
                fetch(${JSONObject.quote(url)}).then(function (r) { return r.blob(); }).then(function (b) {
                    var fr = new FileReader();
                    fr.onloadend = function () { CookieBoxDl.onBlob(t, String(fr.result), b.type || ''); };
                    fr.readAsDataURL(b);
                }).catch(function (e) { CookieBoxDl.onBlobError(t, String(e)); });
            })();
        """.trimIndent()
        toast("正在保存网页生成的文件…")
        wv.evaluateJavascript(js, null)
    }

    private inner class BlobBridge {
        @JavascriptInterface
        fun onBlob(token: String, dataUrl: String, mime: String) {
            runOnUiThread {
                val name = blobTokens.remove(token) ?: return@runOnUiThread
                try {
                    val comma = dataUrl.indexOf(',')
                    val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                    val realMime = mime.ifBlank { "application/octet-stream" }
                    val finalName = if (name.endsWith(".bin") && realMime != "application/octet-stream") {
                        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(realMime)
                        if (ext != null) name.removeSuffix(".bin") + "." + ext else name
                    } else name
                    withStoragePermission {
                        downloader.saveBytes(bytes, finalName, realMime) { result, error -> onDownloadFinished(result, error) }
                    }
                } catch (e: Exception) {
                    toast("下载失败：${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun onBlobError(token: String, error: String) {
            runOnUiThread {
                if (blobTokens.remove(token) != null) toast("下载失败：网页文件已失效（$error）")
            }
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
        dialog.onTabRenameRequested = { tabId -> showRenameTabDialog(tabId) }
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
        dialog.onOpenAccounts = { openAccountsDialog() }
        dialog.show(supportFragmentManager, "StorageBottomSheet")
    }

    // ================================================================ 账号管理

    private fun openAccountsDialog() {
        val tab = getActiveTab()
        val dialog = AccountsBottomSheetDialog.newInstance()
        dialog.currentUrl = tab?.webView?.url?.takeIf { it.startsWith("http") } ?: tab?.url ?: ""
        dialog.onLaunchRecord = { record -> launchWithSavedCookie(record) }
        dialog.onOpenAndFill = { account, password -> openAndFill(account, password) }
        dialog.show(supportFragmentManager, "AccountsBottomSheet")
    }

    /** 当前页就是该网站则直接填入，否则打开该网站，等出现登录框后自动填入 */
    private fun openAndFill(account: AccountCredential, password: String) {
        val tab = getActiveTab() ?: createNewTab(initialUrl = "")
        val wv = tab.webView ?: return
        val curRoot = AccountVault.rootOf(wv.url ?: tab.url)
        if (curRoot.equals(account.domain, true)) {
            fillAccount(wv, account, password)
        } else {
            pendingFills[tab.id] = Pair(account, password)
            loadUrlInTab(tab, account.loginUrl.ifBlank { "https://${account.domain}/" })
            toast("正在打开 ${account.domain}，出现登录框后会自动填入")
        }
    }

    private fun fillAccount(wv: WebView, account: AccountCredential, password: String) {
        wv.evaluateJavascript(AccountAutofill.fillJs(account.username, password)) { r ->
            val code = r?.toIntOrNull() ?: 0
            toast(
                when {
                    code >= 3 -> "已填入 ${account.username}"
                    code == 1 -> "已填入账号，下一步出现密码框时可再次填入"
                    code == 2 -> "已填入密码"
                    else -> "这个页面没有找到登录框，可先点进登录页"
                }
            )
        }
    }

    /** 页面加载完后只读检测一次密码框（SPA 再延迟复查一次），不向页面注入任何东西 */
    private fun checkLoginForm(tab: TabItem) {
        val wv = tab.webView ?: return
        val url = wv.url ?: return
        if (!url.startsWith("http")) return
        val hasWork = pendingFills.containsKey(tab.id) || vault.getForSite(url).isNotEmpty()
        if (!hasWork) return
        val probe = {
            if (wv.url == url) {
                wv.evaluateJavascript(AccountAutofill.DETECT_JS) { r ->
                    if (r == "true") onPasswordFieldFound(tab)
                }
            }
        }
        probe()
        mainHandler.postDelayed({ probe() }, 1500)
        mainHandler.postDelayed({ probe() }, 4000)
    }

    /** 用户主动保存：读取本页登录框里已输入的账号密码 */
    private fun saveCurrentPageLogin(tab: TabItem) {
        val wv = tab.webView ?: return
        val (url, root) = tabUrlRoot(tab) ?: return
        wv.evaluateJavascript(AccountAutofill.READ_JS) { raw ->
            val arr = try { org.json.JSONArray(raw) } catch (e: Exception) { null }
            val user = arr?.optString(0).orEmpty().trim()
            val pass = arr?.optString(1).orEmpty()
            if (user.isEmpty() && pass.isEmpty()) {
                toast("本页登录框里还没有填写内容，先输入账号密码再点保存")
                return@evaluateJavascript
            }
            val existing = vault.find(root, user)
            AlertDialog.Builder(this)
                .setTitle(if (existing != null) "更新 $root 的账号？" else "保存 $root 的账号？")
                .setMessage("账号：${user.ifBlank { "（未识别到）" }}\n密码：${if (pass.isEmpty()) "（未填写）" else "已读取，加密保存在本机"}")
                .setPositiveButton("保存") { _, _ ->
                    vault.upsert(url, user, pass)
                    toast("已保存到账号管理")
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun tabUrlRoot(tab: TabItem): Pair<String, String>? {
        val url = tab.webView?.url ?: tab.url
        if (!url.startsWith("http")) return null
        val root = AccountVault.rootOf(url)
        return if (root.isEmpty()) null else Pair(url, root)
    }

    /** 页面出现密码框 */
    private fun onPasswordFieldFound(tab: TabItem) {
        val wv = tab.webView ?: return
        val (url, root) = tabUrlRoot(tab) ?: return

        pendingFills.remove(tab.id)?.let { (acc, pass) ->
            if (acc.domain.equals(root, true)) { fillAccount(wv, acc, pass); return }
        }
        if (tab.id != activeTabId) return
        if (fillPromptedUrl[tab.id] == url) return
        val accounts = vault.getForSite(url)
        if (accounts.isEmpty()) return
        fillPromptedUrl[tab.id] = url

        val text = if (accounts.size == 1) "使用已保存的 ${accounts[0].username.ifBlank { root }} 登录？"
        else "$root 有 ${accounts.size} 个已保存账号"
        Snackbar.make(binding.root, text, 8000)
            .setAnchorView(binding.bottomAppBar)
            .setAction("填入") {
                if (accounts.size == 1) {
                    fillAccount(wv, accounts[0], vault.passwordOf(accounts[0]) ?: "")
                } else {
                    val names = accounts.map { a -> a.username.ifBlank { "（未填账号）" } + if (a.note.isNotBlank()) " · ${a.note}" else "" }
                    AlertDialog.Builder(this)
                        .setTitle("选择要填入的账号")
                        .setItems(names.toTypedArray()) { _, i ->
                            fillAccount(wv, accounts[i], vault.passwordOf(accounts[i]) ?: "")
                        }
                        .show()
                }
            }
            .show()
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

    private fun copyText(text: String, tip: String, isSensitive: Boolean = true) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("CookieBox", text)
        if (isSensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
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
