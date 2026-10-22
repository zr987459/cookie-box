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
import com.cookiebox.app.engine.ProfileIsolation
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
    private lateinit var identities: com.cookiebox.app.engine.IdentityRegistry
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

    // 下载管理：Android 11+ 删除不属于本应用的文件需要系统确认，确认后再移除记录
    private var pendingDeleteIds: List<Long> = emptyList()
    private val deleteRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val ids = pendingDeleteIds
            pendingDeleteIds = emptyList()
            if (result.resultCode == RESULT_OK) {
                downloader.store.remove(ids)
                toast("已删除")
            } else toast("没有删除")
        }

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
        identities = com.cookiebox.app.engine.IdentityRegistry(this)
        // AI 访问（MCP）：只注册只读标签快照，逻辑全在 mcp 包
        com.cookiebox.app.mcp.McpTabs.attach(this) { tabs.toList() }

        restoreSessionOrCreateTab()

        setupListeners()
        setupAddressBarSwipe()
        watchBottomOverlays()
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
            if (active != null && active.url.isBlank()) loadUrlInTab(active, url) else createNewTab(url, lazy = true)
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
            createNewTab(initialUrl = "", lazy = true)
            return
        }
        val created = saved.map { s ->
            // 兼容旧数据：isClone=true 的「分身」就是一个普通身份，profileName 保持不变
            val blank = s.url.isBlank() || isSensitiveLoginPage(s.url)
            if (s.profileName != DEFAULT_PROFILE) identities.adoptLegacyName(s.profileName, s.customName)
            createNewTab(
                initialUrl = "",
                profileName = s.profileName.takeUnless { blank && it == DEFAULT_PROFILE },
                switchTo = false,
                desktop = s.isDesktop,
                lazy = blank
            ).also {
                it.title = s.title.ifBlank { it.title }
                it.customName = s.customName
                it.url = s.url
                // 登录流程的网址带一次性参数，重启后再打开会报 400，恢复成空白标签
                if (isSensitiveLoginPage(s.url)) {
                    it.url = ""
                    it.title = "新标签页"
                } else {
                    it.pendingUrl = s.url.takeIf { u -> u.isNotBlank() }
                }
            }
        }
        switchTab(created[activeIndex.coerceIn(0, created.size - 1)].id)
        migrateDefaultIdentityOnce(created)
    }

    /**
     * T-030 兼容迁移（只做一次，只针对默认身份）：
     * 旧版若 setProfile 实际失败，默认标签的登录状态在全局 CookieManager 里；修好调用顺序后默认标签改用
     * 「Profile_Default」，会看起来像被登出。这里对每个已保存的默认标签网址：若 Profile_Default 里没有任何
     * Cookie、而全局里有，就把全局的 Cookie 复制过去（只复制不删除，旧数据保持原样）。
     * Profile_Default 里已有 Cookie（说明旧版隔离本来就生效）时什么也不做。
     */
    private fun migrateDefaultIdentityOnce(created: List<TabItem>) {
        if (prefs.defaultIdentityMigrated) return
        val defaults = created.filter { it.profileName == DEFAULT_PROFILE && it.isolated }
        val target = defaults.firstOrNull()?.customCookieManager
        if (target == null) {
            // 没有隔离成功的默认标签：默认身份本来就用全局 CookieManager，无需迁移
            prefs.defaultIdentityMigrated = true
            return
        }
        val global = CookieManager.getInstance()
        if (target === global) { prefs.defaultIdentityMigrated = true; return }
        val urls = defaults.mapNotNull { (it.pendingUrl ?: it.url).takeIf { u -> u.startsWith("http") } }
            .distinctBy { DomainUtils.getRootDomain(android.net.Uri.parse(it).host ?: "") }
        var copiedSites = 0
        for (url in urls) {
            if (!target.getCookie(url).isNullOrBlank()) continue
            val src = parseCookieHeader(global.getCookie(url))
            if (src.isEmpty()) continue
            val host = android.net.Uri.parse(url).host ?: continue
            val root = DomainUtils.getRootDomain(host).ifBlank { host }
            val secure = if (url.startsWith("https://")) "; Secure" else ""
            src.forEach { (k, v) ->
                // getCookie 拿不到原始过期时间，统一给一年，避免变成会话 Cookie 重启即丢
                target.setCookie("https://$root", "$k=$v; Domain=.$root; Path=/; Max-Age=31536000$secure")
            }
            copiedSites++
        }
        target.flush()
        prefs.defaultIdentityMigrated = true
        if (copiedSites > 0) android.util.Log.i("CookieBox/Profile", "默认身份迁移：复制了 $copiedSites 个站点的登录状态")
    }

    // ==================== 标签 = 网站 + 身份（账号） ====================

    private fun createNewTab(
        initialUrl: String = "",
        isClone: Boolean = false,
        profileName: String? = null,
        openerId: String? = null,
        switchTo: Boolean = true,
        desktop: Boolean = false,
        /** true = 空白标签：先不建 WebView，等知道要去哪个站再决定身份（T-030） */
        lazy: Boolean = false
    ): TabItem {
        val tabId = UUID.randomUUID().toString()
        val resolvedProfile = profileName ?: if (isClone) newProfileName() else DEFAULT_PROFILE
        val newTab = TabItem(
            id = tabId,
            title = "新标签页",
            url = initialUrl,
            isDesktop = desktop,
            isClone = resolvedProfile != DEFAULT_PROFILE,
            profileName = resolvedProfile,
            webView = null,
            openerId = openerId,
            identityPending = lazy && profileName == null && !isClone
        )
        identities.ensure(resolvedProfile)
        if (!lazy) attachWebView(newTab)

        tabs.add(newTab)
        if (switchTo) switchTab(tabId) else updateTabBadge()

        if (initialUrl.isNotBlank()) {
            loadUrlInTab(newTab, initialUrl)
        }

        return newTab
    }

    /** 新身份的 Profile 名（沿用旧前缀，旧版的清理逻辑与 MCP 授权都认这个格式） */
    private fun newProfileName(): String = "Profile_Clone_${UUID.randomUUID().toString().take(8)}"

    /**
     * 给标签建 WebView 并绑定它的身份。空白标签延迟到这里才建，所以 setProfile 一定是对 WebView 的第一个调用。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun attachWebView(newTab: TabItem) {
        if (newTab.webView != null) return
        newTab.identityPending = false
        val desktop = newTab.isDesktop
        // ⚠️ 调用顺序不能变（T-030）：new WebView 之后第一件事就是绑定身份，
        // 之后才能访问 settings、设置 client、写 Cookie 策略。详见 ProfileIsolation 类注释。
        val webView = WebView(this)
        val isolation = ProfileIsolation.attach(webView, newTab.profileName)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        newTab.webView = webView
        newTab.isolated = isolation.isolated
        newTab.isolationError = isolation.error

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

        // 身份已在 new WebView 之后立刻绑定；这里只设置该身份 CookieManager 的接收策略
        newTab.customCookieManager = isolation.cookieManager
        isolation.cookieManager.setAcceptCookie(true)
        isolation.cookieManager.setAcceptThirdPartyCookies(webView, true)
        if (newTab.isClone && !isolation.isolated) warnIsolationUnavailable()

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
                        updateIdentityBar()
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
                    com.cookiebox.app.mcp.McpTabs.onPageFinished(newTab.profileName, it)
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
                    updateIdentityBar()
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
        // 上滑收起顶栏/底栏，下滑出现（T-030）
        webView.setOnScrollChangeListener { _, _, y, _, oldY -> onWebScrolled(newTab, y, y - oldY) }

        // 当前正显示这个标签：把刚建好的 WebView 放进容器
        if (newTab.id == activeTabId) {
            binding.layoutWebContainer.removeAllViews()
            binding.layoutWebContainer.addView(webView)
        }
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

    /** 分身无法隔离时提示一次（每次启动最多一次），不再静默共用 Cookie */
    private var isolationWarned = false
    private fun warnIsolationUnavailable() {
        if (isolationWarned) return
        isolationWarned = true
        binding.root.post {
            AlertDialog.Builder(this)
                .setTitle("账号无法隔离")
                .setMessage(ProfileIsolation.UNSUPPORTED_TIP + "\n\n当前：" + ProfileIsolation.webViewVersion(this) +
                        "\n\n在此之前，标有「未隔离」的账号标签与主账号共用同一份登录状态。")
                .setPositiveButton("知道了", null)
                .setNeutralButton("查看诊断") { _, _ -> showIsolationDiagnostics() }
                .show()
        }
    }

    /** 新身份入口统一检查：不能隔离就提示，不创建会串号的「分身」 */
    private fun ensureCanIsolate(): Boolean {
        if (ProfileIsolation.canIsolate()) return true
        AlertDialog.Builder(this)
            .setTitle("无法新建账号")
            .setMessage(ProfileIsolation.UNSUPPORTED_TIP + "\n\n当前：" + ProfileIsolation.webViewVersion(this))
            .setPositiveButton("知道了", null)
            .setNeutralButton("查看诊断") { _, _ -> showIsolationDiagnostics() }
            .show()
        return false
    }

    /** 分身诊断：WebView 版本、是否支持多 Profile、每个标签的身份与隔离状态（不含任何 Cookie 值） */
    private fun showIsolationDiagnostics() {
        val sb = StringBuilder()
        sb.append("系统 WebView：").append(ProfileIsolation.webViewVersion(this)).append('\n')
        sb.append("MULTI_PROFILE：").append(if (ProfileIsolation.isSupported()) "支持" else "不支持").append('\n')
        sb.append("本次启动 setProfile 失败过：").append(if (ProfileIsolation.attachFailed) "是" else "否").append("\n\n")
        tabs.forEachIndexed { i, t ->
            val name = t.customName.ifBlank { t.title.ifBlank { "新标签页" } }.take(18)
            sb.append(i + 1).append(". ").append(name).append('\n')
            sb.append("   身份：").append(identities.name(t.profileName)).append("（").append(t.profileName).append("）")
                .append(if (t.identityPending) " 待定" else "").append('\n')
            sb.append("   隔离：").append(if (t.isolated) "✓ 独立" else "✗ 未隔离").append('\n')
            t.isolationError?.let { sb.append("   原因：").append(it.take(160)).append('\n') }
        }
        AlertDialog.Builder(this)
            .setTitle("账号隔离诊断")
            .setMessage(sb.toString())
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制") { _, _ -> copyText(sb.toString(), "已复制诊断信息", isSensitive = false) }
            .show()
    }

    private fun switchTab(targetTabId: String) {
        val targetTab = tabs.find { it.id == targetTabId } ?: return
        activeTabId = targetTabId
        closeFindBar()

        // 仅切换可见容器，各 Profile 保持完全独立的 Cookie 会话，绝不清除，绝不掉线！
        targetTab.lastActiveAt = System.currentTimeMillis()
        hideIdentityChooser()
        if (::binding.isInitialized) showBars()
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
        updateIdentityBar()
    }

    /** 关闭标签（分身标签会删除其登录状态，先确认） */
    private fun requestCloseTab(tabId: String) {
        val tab = tabs.find { it.id == tabId } ?: return
        val profileStillUsed = tabs.any { it.id != tabId && it.profileName == tab.profileName }
        if (tab.isClone && !profileStillUsed) {
            AlertDialog.Builder(this)
                .setTitle("关闭「${identities.name(tab.profileName)}」的最后一个标签？")
                .setMessage("关闭后这个账号的登录状态会被删除。需要保留的话，先点「提取」→「保存至储存库」。")
                .setPositiveButton("关闭并删除") { _, _ -> closeTab(tabId) }
                .setNegativeButton("取消", null)
                .show()
        } else {
            closeTab(tabId)
        }
    }

    /** 全部关闭：主账号以外的账号会删除登录状态，先确认 */
    private fun requestCloseAllTabs() {
        val others = tabs.filter { it.isClone && !it.identityPending }.map { it.profileName }.distinct()
        val msg = if (others.isEmpty()) "关闭全部 ${tabs.size} 个标签？"
        else "关闭全部 ${tabs.size} 个标签？主账号以外的 ${others.size} 个账号（" +
                others.joinToString("、") { identities.name(it) }.take(60) +
                "）的登录状态会被删除。需要保留的话，先在胶囊上长按「保存到储存库」。"
        AlertDialog.Builder(this)
            .setTitle("全部关闭")
            .setMessage(msg)
            .setPositiveButton("全部关闭") { _, _ ->
                tabs.map { it.id }.forEach { closeTab(it) }
            }
            .setNegativeButton("取消", null)
            .show()
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
                identities.forget(removingTab.profileName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (tabs.isEmpty()) {
            createNewTab(initialUrl = "", lazy = true)
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
        updateIdentityBar()
    }

    /** 重命名身份（账号）：同一身份的所有标签一起改名 */
    private fun showRenameTabDialog(tabId: String) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        val profile = tab.profileName
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(this).apply {
            setText(identities.customName(profile))
            setSelection(text.length)
            hint = identities.name(profile) + "，例如：B站大号、微博小号2"
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_TAB_NAME_LENGTH))
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("账号名称")
            .setView(container)
            .setPositiveButton("保存") { _, _ -> applyTabName(tab, input.text.toString()) }
            .setNegativeButton("取消", null)
        if (identities.customName(profile).isNotBlank()) {
            builder.setNeutralButton("恢复默认") { _, _ -> applyTabName(tab, "") }
        }
        builder.show()
    }

    private fun applyTabName(tab: TabItem, raw: String) {
        // 去掉换行与首尾空白，避免列表错位；只存本机，不含任何凭据
        identities.rename(tab.profileName, raw)
        // 旧字段保持同步，降级到旧版时标签名仍在
        tabs.filter { it.profileName == tab.profileName }.forEach {
            it.customName = identities.customName(tab.profileName)
        }
        saveSession()
        refreshTabsDialog()
        onIdentitiesChanged()
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
        if (tab.webView == null) {
            if (tab.identityPending) {
                // 空白标签第一次去某个站：先决定身份，再建 WebView（setProfile 因此排在最前）
                chooseIdentityFor(tab, targetUrl)
                return
            }
            attachWebView(tab)
        }

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
        tab.title = "新标签页"
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

        // T-030：绝不覆盖已有身份。按顺序决定写到哪个身份：
        // 1) 已有某个身份恰好就是这个账号（Cookie 全部一致）→ 直接用它；
        // 2) 默认身份没登录这个站，或登录的就是同一账号 → 用默认身份（与旧版行为一致）；
        // 3) 默认身份登录了这个站的另一个账号 → 新建一个身份标签再写入；不能隔离时先问用户。
        val recordMap = cookies.associate { it.name to it.value }
        fun cookiesOf(cm: CookieManager?): Map<String, String> = parseCookieHeader(cm?.getCookie(cleanEntryUrl))

        val sameAccountProfile = tabs.map { it.profileName }.distinct().firstOrNull { p ->
            val have = cookiesOf(tabs.first { it.profileName == p }.customCookieManager)
            recordMap.all { (k, v) -> have[k] == v }
        }
        val defaultCm = tabs.firstOrNull { it.profileName == DEFAULT_PROFILE }?.customCookieManager
            ?: ProfileIsolation.cookieManagerFor(DEFAULT_PROFILE)
        val defaultHave = cookiesOf(defaultCm)
        val conflictsWithDefault = recordMap.any { (k, v) -> defaultHave[k] != null && defaultHave[k] != v }

        val targetProfile: String? = when {
            sameAccountProfile != null -> sameAccountProfile
            !conflictsWithDefault -> DEFAULT_PROFILE
            else -> null // 需要新身份
        }
        if (targetProfile == null && !ProfileIsolation.canIsolate()) {
            AlertDialog.Builder(this)
                .setTitle("默认身份已登录另一个账号")
                .setMessage("${record.domain} 在默认身份里已经登录了别的账号。" + ProfileIsolation.UNSUPPORTED_TIP +
                        "\n\n继续写入会覆盖默认身份里的登录状态（大号会被登出）。")
                .setPositiveButton("仍然覆盖") { _, _ -> writeRecordCookies(record, DEFAULT_PROFILE, forceTab = null) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        writeRecordCookies(record, targetProfile, forceTab = null)
    }

    /** 解析 CookieManager.getCookie 返回的 "a=1; b=2" 为 name→value（只在内存比较，不落盘、不打日志） */
    private fun parseCookieHeader(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()
    }

    /** 把储存库记录写入指定身份（null = 新建一个身份）并打开入口页 */
    private fun writeRecordCookies(record: SavedCookieRecord, profileName: String?, forceTab: TabItem?) {
        val cleanEntryUrl = DomainUtils.getCleanEntryUrl(record.url, record.domain)
        val rootDomain = DomainUtils.getRootDomain(record.domain)
        val cookies = record.getCookieList()
        val activeTab = getActiveTab()
        val targetTab = when {
            forceTab != null -> forceTab
            profileName == null -> createNewTab(initialUrl = "", isClone = true).also {
                it.customName = record.title.take(MAX_TAB_NAME_LENGTH)
            }
            activeTab != null && activeTab.profileName == profileName &&
                    (activeTab.url.isBlank() || activeTab.url == "about:blank") -> activeTab
            else -> createNewTab(
                initialUrl = "",
                isClone = profileName != DEFAULT_PROFILE,
                profileName = profileName
            )
        }

        // 复用的空白标签可能还没建 WebView（T-030 延迟创建）：先按目标身份建好，Cookie 才会写进正确的 Profile
        if (targetTab.webView == null) {
            if (profileName != null) {
                targetTab.profileName = profileName
                targetTab.isClone = profileName != DEFAULT_PROFILE
            }
            attachWebView(targetTab)
        }
        // 新身份没能隔离（setProfile 失败）：写进去就等于覆盖默认身份，宁可不写
        if (targetTab.profileName != DEFAULT_PROFILE && !targetTab.isolated && forceTab == null && profileName == null) {
            closeTab(targetTab.id)
            warnIsolationUnavailable()
            return
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
                val who = if (targetTab.profileName == DEFAULT_PROFILE) "默认身份" else "身份「${targetTab.customName.ifBlank { "新账号" }}」"
                toast("已写入$who ${cookies.size} 项 Cookie，正在免密直达 ${record.domain}")
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
            if (hasFocus) showBars()
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
        binding.btnNavTabs.setOnClickListener { openTabsDialog() }
        binding.btnNavTabs.setOnLongClickListener {
            createNewTab(initialUrl = "", lazy = true)
            toast("已新建标签页")
            true
        }
        binding.btnNavMore.setOnClickListener { showMoreSheet() }
        binding.btnNavExtract.setOnClickListener { openCookieSheet() }
    }

    private fun updateUrlBarButtons() {
        val focused = binding.etUrl.isFocused
        binding.btnClearUrl.visibility =
            if (focused && binding.etUrl.text.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnRefresh.visibility = if (focused) View.GONE else View.VISIBLE
    }

    /** T-030：「更多」底部面板（替代原 15 行 PopupMenu）。没有页面时不可用的项置灰 */
    private fun showMoreSheet() {
        val tab = getActiveTab()
        val wv = tab?.webView
        val hasPage = tab != null && tab.url.isNotBlank() && wv != null
        val pageUrl = { wv?.url?.takeIf { it != "about:blank" } ?: tab?.url.orEmpty() }
        val grid = listOf(
            com.cookiebox.app.ui.MoreSheet.Item("储存库", R.drawable.ic_storage) { openStorageDialog() },
            com.cookiebox.app.ui.MoreSheet.Item("账号密码", R.drawable.ic_key) { openAccountsDialog() },
            com.cookiebox.app.ui.MoreSheet.Item("页内查找", R.drawable.ic_search, hasPage) { openFindBar() },
            com.cookiebox.app.ui.MoreSheet.Item("翻译", R.drawable.ic_translate, hasPage) {
                wv?.let {
                    toast("正在翻译为中文...")
                    WebTranslator.translateInPage(it, "zh-CN") { msg -> toast(msg) }
                }
            },
            com.cookiebox.app.ui.MoreSheet.Item(
                "桌面版", R.drawable.ic_desktop, tab != null, checked = tab?.isDesktop == true
            ) { toggleActiveTabUserAgent() },
            com.cookiebox.app.ui.MoreSheet.Item("下载", R.drawable.ic_file_download, checked = downloader.store.runningCount() > 0) {
                openDownloadsSheet()
            }
        )
        val list = mutableListOf(
            com.cookiebox.app.ui.MoreSheet.Item("分享", R.drawable.ic_share, hasPage) { tab?.let { shareText(pageUrl(), it.title) } },
            com.cookiebox.app.ui.MoreSheet.Item("主页", R.drawable.ic_home, hasPage) { goHome() },
            com.cookiebox.app.ui.MoreSheet.Item("加到首页快捷入口", R.drawable.ic_add, hasPage) {
                tab?.let {
                    if (prefs.addShortcut(it.title, pageUrl())) {
                        renderShortcuts()
                        toast("已添加到首页")
                    } else {
                        toast("首页已有这个网址")
                    }
                }
            },
            com.cookiebox.app.ui.MoreSheet.Item("复制链接", R.drawable.ic_copy, hasPage) { copyText(pageUrl(), "已复制链接") },
            com.cookiebox.app.ui.MoreSheet.Item("用其他浏览器打开", R.drawable.ic_open_in_browser, hasPage) { openExternally(pageUrl()) },
            com.cookiebox.app.ui.MoreSheet.Item("保存本页填写的账号密码", R.drawable.ic_save, hasPage) { tab?.let { saveCurrentPageLogin(it) } },
            com.cookiebox.app.ui.MoreSheet.Item("缩放与翻译工具", R.drawable.ic_zoom_in, hasPage) { openToolsDialog() },
            com.cookiebox.app.ui.MoreSheet.Item("搜索引擎：${prefs.searchEngine}", R.drawable.ic_public) { chooseSearchEngine() },
            com.cookiebox.app.ui.MoreSheet.Item(
                "清除当前账号的数据", R.drawable.ic_delete, tab != null && !tab.identityPending
            ) { showClearCookiesDialog() },
            com.cookiebox.app.ui.MoreSheet.Item("AI 访问（MCP）", R.drawable.ic_bolt) {
                startActivity(Intent(this, com.cookiebox.app.mcp.McpSettingsActivity::class.java))
            },
            com.cookiebox.app.ui.MoreSheet.Item("账号隔离诊断", R.drawable.ic_lock) { showIsolationDiagnostics() }
        )
        com.cookiebox.app.ui.MoreSheet(this).show(grid, list)
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
            labels += "用新账号打开"
            actions += { if (ensureCanIsolate()) createNewTab(link, isClone = true) }
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
                    Snackbar.make(binding.root, "开始下载，可在「更多 → 下载」查看", Snackbar.LENGTH_SHORT)
                        .setAction("查看") { openDownloadsSheet() }
                        .show()
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

    // ---------- 下载管理 ----------

    private fun openDownloadsSheet() {
        val store = downloader.store
        com.cookiebox.app.ui.DownloadsSheet(this, store, object : com.cookiebox.app.ui.DownloadsSheet.Actions {
            override fun open(rec: com.cookiebox.app.engine.DownloadStore.Rec) {
                val uri = rec.uri ?: return toast("文件已不在")
                openDownloaded(FileDownloader.Result(Uri.parse(uri), rec.fileName, rec.mime, rec.id))
            }

            override fun share(rec: com.cookiebox.app.engine.DownloadStore.Rec) {
                val uri = rec.uri ?: return toast("文件已不在")
                try {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(rec.mime)
                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, rec.fileName))
                } catch (e: Exception) {
                    toast("无法分享该文件")
                }
            }

            override fun copyUrl(rec: com.cookiebox.app.engine.DownloadStore.Rec) {
                rec.url?.let { copyText(it, "已复制下载链接") }
            }

            override fun retry(rec: com.cookiebox.app.engine.DownloadStore.Rec) {
                val url = rec.url ?: return
                // 用来源网站对应标签的身份（Cookie）重新下载；找不到时用当前标签
                val host = rec.referer?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                val tab = tabs.firstOrNull { t -> host != null && runCatching { Uri.parse(t.url).host }.getOrNull() == host }
                    ?: getActiveTab()
                val cm = tab?.customCookieManager ?: CookieManager.getInstance()
                cm.flush()
                val ua = tab?.webView?.settings?.userAgentString ?: defaultMobileUa
                withStoragePermission {
                    store.remove(listOf(rec.id))
                    downloader.download(url, ua, rec.referer, cm, null, rec.mime) { result, error ->
                        onDownloadFinished(result, error)
                    }
                }
            }

            override fun cancel(rec: com.cookiebox.app.engine.DownloadStore.Rec) {
                downloader.cancel(rec.id)
            }

            override fun delete(recs: List<com.cookiebox.app.engine.DownloadStore.Rec>) {
                Thread {
                    val removed = ArrayList<Long>()
                    val needConfirm = ArrayList<Pair<Long, android.content.IntentSender>>()
                    var failed = 0
                    recs.forEach { r ->
                        try {
                            val sender = downloader.deleteFile(r)
                            if (sender == null) removed += r.id else needConfirm += r.id to sender
                        } catch (e: Exception) {
                            failed++
                        }
                    }
                    store.remove(removed)
                    runOnUiThread {
                        when {
                            needConfirm.isNotEmpty() -> {
                                // 系统一次只确认一个请求：多个文件时逐个确认第一个，其余提示用户再点一次
                                pendingDeleteIds = listOf(needConfirm[0].first)
                                deleteRequestLauncher.launch(
                                    androidx.activity.result.IntentSenderRequest.Builder(needConfirm[0].second).build()
                                )
                                if (needConfirm.size > 1) toast("还有 ${needConfirm.size - 1} 个文件需要逐个确认删除")
                            }
                            failed > 0 -> toast("有 $failed 个文件删不掉，请在「下载」文件夹里手动删除")
                            removed.isNotEmpty() -> toast(if (removed.size == 1) "已删除" else "已删除 ${removed.size} 个文件")
                        }
                    }
                }.start()
            }

            override fun openFolder() {
                val tries = listOf(
                    Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS),
                    Intent(Intent.ACTION_VIEW).setDataAndType(
                        Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                        "vnd.android.document/directory"
                    )
                )
                for (i in tries) {
                    try {
                        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return
                    } catch (_: Exception) {
                    }
                }
                toast("没有找到文件管理器，请在系统「文件」应用里打开 Download 文件夹")
            }
        }).show()
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
        dialog.onNewTabRequested = { _ -> createNewTab(initialUrl = "", lazy = true) }
        dialog.onDismissListener = { tabsDialog = null }
        dialog.identityOf = { t -> Pair(identities.name(t.profileName), identities.color(t.profileName)) }
        dialog.onCloseAllRequested = { requestCloseAllTabs() }
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
        dialog.onOpenStorage = { openStorageDialog() }
        dialog.show(supportFragmentManager, "CookieBottomSheet")
    }

    private fun showClearCookiesDialog() {
        val activeTab = getActiveTab() ?: return
        val who = "账号「${identities.name(activeTab.profileName)}」"
        AlertDialog.Builder(this)
            .setTitle("清除 Cookie 和缓存")
            .setMessage("将清除$who 的全部 Cookie、网站存储和缓存，并登出其中所有网站。其他账号不受影响。")
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

    // ---------- T-030：身份（账号）选择 ----------

    private val storageRepo by lazy { com.cookiebox.app.engine.CookieStorageRepository(this) }
    private var chooserCountdown: Runnable? = null

    /** 标签所在网站（注册域名），空白标签返回空串 */
    private fun siteOf(tab: TabItem): String = siteOfUrl(tab.webView?.url?.takeIf { it != "about:blank" } ?: tab.url)

    private fun siteOfUrl(url: String?): String {
        val host = try { Uri.parse(url ?: "").host } catch (e: Exception) { null } ?: return ""
        return DomainUtils.getRootDomain(host).ifBlank { host }
    }

    /** 小圆点（身份颜色） */
    private fun identityDot(color: Int, sizeDp: Int = 10): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            val px = (sizeDp * resources.displayMetrics.density).toInt()
            setSize(px, px)
        }

    /**
     * 空白标签第一次要去 [url]：
     * - 这个站还没有打开的标签 → 直接用主账号，不打扰；
     * - 已经有这个站的标签 → 弹出小底栏让用户选账号（默认选上次用的，3 秒不操作就用它）；
     *   储存库里该站保存的账号也列出来（选中会建一个新身份并写入 Cookie）。
     */
    private fun chooseIdentityFor(tab: TabItem, url: String) {
        val site = siteOfUrl(url)
        val siteProfiles = tabs.filter { it.id != tab.id && it.webView != null && siteOf(it) == site }
            .sortedByDescending { it.lastActiveAt }
            .map { it.profileName }
            .distinct()
        val openWith: (String) -> Unit = { profile ->
            hideIdentityChooser()
            tab.profileName = profile
            tab.isClone = profile != DEFAULT_PROFILE
            identities.ensure(profile)
            attachWebView(tab)
            loadUrlInTab(tab, url)
            onIdentitiesChanged()
        }
        if (site.isBlank() || siteProfiles.isEmpty()) {
            openWith(DEFAULT_PROFILE)
            return
        }
        if (!ProfileIsolation.canIsolate()) {
            // 不能隔离：没有「新账号」可选，直接用这个站上次用的身份
            openWith(siteProfiles.first())
            return
        }
        val records = try {
            storageRepo.getAllRecords().filter { siteOfUrl("https://" + it.domain.trimStart('.')) == site }
        } catch (e: Exception) { emptyList() }

        val chips = binding.layoutChooserChips
        chips.removeAllViews()
        val defaultProfile = siteProfiles.first()
        fun addChip(label: String, dotColor: Int?, checked: Boolean, onPick: () -> Unit) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = checked
                isCheckedIconVisible = false
                if (dotColor != null) {
                    chipIcon = identityDot(dotColor, 12)
                    isChipIconVisible = true
                } else {
                    setChipIconResource(R.drawable.ic_add)
                    isChipIconVisible = true
                }
                setOnClickListener { onPick() }
                val lp = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (8 * resources.displayMetrics.density).toInt()
                layoutParams = lp
            }
            chips.addView(chip)
        }
        siteProfiles.forEach { p ->
            addChip(identities.name(p), identities.color(p), p == defaultProfile) { openWith(p) }
        }
        records.forEach { r ->
            addChip("储存库 · " + r.title.ifBlank { r.domain }.take(14), null, false) {
                hideIdentityChooser()
                val profile = newProfileName()
                tab.profileName = profile
                tab.isClone = true
                identities.ensure(profile)
                identities.rename(profile, r.title.ifBlank { r.domain })
                attachWebView(tab)
                if (!tab.isolated) {
                    // 绑定失败就不写，避免覆盖主账号
                    warnIsolationUnavailable()
                    loadUrlInTab(tab, url)
                } else {
                    writeRecordCookies(r, profile, forceTab = tab)
                }
                onIdentitiesChanged()
            }
        }
        addChip("新账号", null, false) { openWith(newProfileName()) }

        binding.tvChooserTitle.text = "用哪个账号打开 $site？"
        binding.cardIdentityChooser.visibility = View.VISIBLE
        updateIdentityBar()
        binding.btnChooserClose.setOnClickListener { hideIdentityChooser() }
        // 用户开始滑动/触摸选项，就不再自动选择
        binding.scrollChooserChips.setOnTouchListener { _, _ -> cancelChooserCountdown(); false }

        var remain = 3
        val tick = object : Runnable {
            override fun run() {
                if (binding.cardIdentityChooser.visibility != View.VISIBLE) return
                if (remain <= 0) {
                    openWith(defaultProfile)
                    return
                }
                binding.tvChooserHint.text = "${remain} 秒后用「${identities.name(defaultProfile)}」打开"
                remain--
                mainHandler.postDelayed(this, 1000)
            }
        }
        chooserCountdown = tick
        tick.run()
    }

    private fun cancelChooserCountdown() {
        chooserCountdown?.let { mainHandler.removeCallbacks(it) }
        chooserCountdown = null
        binding.tvChooserHint.text = "点一个账号打开"
    }

    private fun hideIdentityChooser() {
        chooserCountdown?.let { mainHandler.removeCallbacks(it) }
        chooserCountdown = null
        if (::binding.isInitialized && binding.cardIdentityChooser.visibility != View.GONE) {
            binding.cardIdentityChooser.visibility = View.GONE
            updateIdentityBar()
        }
    }

    /** 身份名称/颜色或标签归属变化后刷新相关界面（标签面板、底部账号胶囊栏） */
    private fun onIdentitiesChanged() {
        refreshTabsDialog()
        updateIdentityBar()
    }

    // ---------- T-030：底部「同一网站不同账号」胶囊栏 ----------

    /** 当前网站每个账号对应的标签（同一账号有多个标签时取最近用过的） */
    private fun identitiesOnCurrentSite(): List<TabItem> {
        val active = getActiveTab() ?: return emptyList()
        val site = siteOf(active)
        if (site.isBlank() || active.identityPending) return emptyList()
        return tabs.filter { !it.identityPending && siteOf(it) == site }
            .groupBy { it.profileName }
            .map { (profile, list) -> list.firstOrNull { it.id == activeTabId && it.profileName == profile } ?: list.maxBy { it.lastActiveAt } }
            .sortedBy { it.profileName != DEFAULT_PROFILE } // 主账号排第一，其余按出现顺序
    }

    private fun updateIdentityBar() {
        if (!::binding.isInitialized) return
        val reps = identitiesOnCurrentSite()
        val bar = binding.layoutIdentityBar
        val show = reps.size >= 2 && binding.cardIdentityChooser.visibility != View.VISIBLE
        binding.scrollIdentityBar.visibility = if (show) View.VISIBLE else View.GONE
        applyContentBottomMargin()
        if (!show) {
            bar.removeAllViews()
            return
        }
        bar.removeAllViews()
        val active = getActiveTab()
        val gap = (6 * resources.displayMetrics.density).toInt()
        fun lp() = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = gap }
        reps.forEach { t ->
            val isCurrent = t.profileName == active?.profileName
            val chip = Chip(this).apply {
                text = identities.name(t.profileName) + if (t.isClone && !t.isolated) " · 未隔离" else ""
                chipIcon = identityDot(identities.color(t.profileName), 12)
                isChipIconVisible = true
                isCheckable = true
                isChecked = isCurrent
                isCheckedIconVisible = false
                elevation = 4 * resources.displayMetrics.density
                compactChip(this)
                contentDescription = "切换到账号 ${identities.name(t.profileName)}"
                setOnClickListener {
                    isChecked = isCurrent
                    if (!isCurrent) switchTab(t.id)
                }
                setOnLongClickListener { showIdentityCapsuleMenu(t); true }
                layoutParams = lp()
            }
            bar.addView(chip)
        }
        val plus = Chip(this).apply {
            text = "新账号"
            setChipIconResource(R.drawable.ic_add)
            isChipIconVisible = true
            elevation = 4 * resources.displayMetrics.density
            compactChip(this)
            contentDescription = "用新账号打开当前网站"
            setOnClickListener { openCurrentSiteWithNewIdentity() }
            layoutParams = lp()
        }
        bar.addView(plus)
    }

    // ---------- T-030：上滑网页收起顶栏和底栏，下滑出现 ----------

    private var barsHidden = false
    private var scrollAccum = 0

    /**
     * 网页底部让出的空间 = 根布局底边到「底栏 / 账号胶囊栏」顶边的实际距离 + 4dp。
     * 按实际位置计算而不是写死常量，底栏或胶囊栏换了尺寸也不会挡住网页（T-030 用户反馈）。
     * 底栏收起时为 0。
     */
    private fun applyContentBottomMargin() {
        val lp = binding.layoutContentArea.layoutParams as ViewGroup.MarginLayoutParams
        val gap = (4 * resources.displayMetrics.density).toInt()
        val want = when {
            barsHidden -> 0
            binding.root.height == 0 || binding.bottomAppBar.height == 0 ->
                resources.getDimensionPixelSize(R.dimen.cb_dock_clearance)
            else -> {
                var top = binding.bottomAppBar.top
                val bar = binding.scrollIdentityBar
                if (bar.visibility == View.VISIBLE && bar.height > 0) top = minOf(top, bar.top)
                (binding.root.height - top + gap).coerceAtLeast(0)
            }
        }
        if (lp.bottomMargin != want) {
            lp.bottomMargin = want
            binding.layoutContentArea.layoutParams = lp
        }
    }

    /** 底栏 / 胶囊栏布局变化（显示、换行、尺寸变化）后重新计算网页底部留白 */
    private fun watchBottomOverlays() {
        val l = View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (top != oldTop || bottom != oldBottom) binding.root.post { applyContentBottomMargin() }
        }
        binding.bottomAppBar.addOnLayoutChangeListener(l)
        binding.scrollIdentityBar.addOnLayoutChangeListener(l)
        binding.root.addOnLayoutChangeListener(l)
    }

    private fun onWebScrolled(tab: TabItem, scrollY: Int, dy: Int) {
        if (tab.id != activeTabId) return
        if (scrollY <= 0) { showBars(); return }
        if ((dy > 0) != (scrollAccum > 0)) scrollAccum = 0
        scrollAccum += dy
        val d = resources.displayMetrics.density
        if (scrollAccum > 48 * d) hideBars() else if (scrollAccum < -24 * d) showBars()
    }

    private fun hideBars() {
        if (barsHidden || binding.etUrl.isFocused || binding.layoutFindBar.visibility == View.VISIBLE ||
            binding.cardIdentityChooser.visibility == View.VISIBLE) return
        barsHidden = true
        binding.appBarLayout.setExpanded(false, true)
        val dy = (binding.bottomAppBar.height + resources.getDimensionPixelSize(R.dimen.cb_dock_margin_bottom)).toFloat() +
                (60 * resources.displayMetrics.density)
        binding.bottomAppBar.animate().translationY(dy).setDuration(180).start()
        binding.scrollIdentityBar.animate().translationY(dy).setDuration(180).start()
        applyContentBottomMargin()
    }

    private fun showBars() {
        scrollAccum = 0
        if (!barsHidden) return
        barsHidden = false
        binding.appBarLayout.setExpanded(true, true)
        binding.bottomAppBar.animate().translationY(0f).setDuration(180).start()
        binding.scrollIdentityBar.animate().translationY(0f).setDuration(180).start()
        applyContentBottomMargin()
    }

    /** 账号胶囊做成细条（视觉 24dp、可点区域 36dp），尽量少占网页空间（T-030 用户反馈两次嫌挡） */
    private fun compactChip(chip: Chip) {
        val d = resources.displayMetrics.density
        chip.chipMinHeight = 24 * d
        chip.textSize = 12f
        chip.chipStartPadding = 6 * d
        chip.chipEndPadding = 6 * d
        chip.textStartPadding = 2 * d
        chip.textEndPadding = 2 * d
        chip.chipIconSize = 12 * d
        chip.elevation = 0f
        chip.ensureAccessibleTouchTarget((36 * d).toInt())
    }

    /** 「＋」：用一个新身份打开当前网站的首页 */
    private fun openCurrentSiteWithNewIdentity() {
        val active = getActiveTab() ?: return
        val url = active.webView?.url?.takeIf { it != "about:blank" } ?: active.url
        val host = try { Uri.parse(url).host } catch (e: Exception) { null } ?: return
        if (!ensureCanIsolate()) return
        val entry = DomainUtils.getCleanEntryUrl(url, host)
        createNewTab(initialUrl = entry, isClone = true, desktop = active.isDesktop)
        onIdentitiesChanged()
    }

    /** 长按胶囊：重命名 / 保存到储存库 / 关闭 */
    private fun showIdentityCapsuleMenu(t: TabItem) {
        val name = identities.name(t.profileName)
        AlertDialog.Builder(this)
            .setTitle("账号「$name」")
            .setItems(arrayOf("重命名", "保存到储存库", "关闭")) { _, which ->
                when (which) {
                    0 -> showRenameTabDialog(t.id)
                    1 -> {
                        if (t.id != activeTabId) switchTab(t.id)
                        openCookieSheet()
                    }
                    2 -> requestCloseTab(t.id)
                }
            }
            .show()
    }

    /** 地址栏左右滑动切到上一个 / 下一个标签（编辑网址时不生效） */
    private fun setupAddressBarSwipe() {
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || binding.etUrl.isFocused) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val minDist = 60 * resources.displayMetrics.density
                if (kotlin.math.abs(dx) < minDist || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.5f || kotlin.math.abs(vx) < 400) return false
                val i = tabs.indexOfFirst { it.id == activeTabId }
                if (i < 0 || tabs.size < 2) return false
                val next = if (dx < 0) (i + 1) % tabs.size else (i - 1 + tabs.size) % tabs.size
                switchTab(tabs[next].id)
                val t = tabs[next]
                toast("${identities.name(t.profileName)} · ${t.title.ifBlank { "新标签页" }.take(20)}")
                return true
            }
        })
        binding.etUrl.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) && !binding.etUrl.isFocused }
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
        com.cookiebox.app.mcp.McpTabs.detach(this)
        tabs.forEach { it.webView?.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
