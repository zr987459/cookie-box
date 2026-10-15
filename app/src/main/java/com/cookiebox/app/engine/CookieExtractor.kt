package com.cookiebox.app.engine

import android.net.Uri
import android.webkit.CookieManager
import com.cookiebox.app.model.CookieItem

object CookieExtractor {

    /**
     * 从指定的 CookieManager (支持多 Profile 独立分身) 中提取目标 URL 及其根域名的全部 Cookie (含 HttpOnly)
     */
    fun extractCookies(
        url: String,
        cookieManager: CookieManager = CookieManager.getInstance()
    ): List<CookieItem> {
        if (url.isBlank() || !url.startsWith("http")) {
            return emptyList()
        }

        val domain = try {
            Uri.parse(url).host ?: ""
        } catch (e: Exception) {
            ""
        }

        val cookieMap = LinkedHashMap<String, String>()

        // 1. 获取完整 URL 的 Cookie
        val rawCookies = cookieManager.getCookie(url)
        parseAndMergeCookies(rawCookies, cookieMap)

        // 2. 如果存在二级或子域名，补充读取根域名的 Cookie
        if (domain.isNotEmpty()) {
            val rootDomainUrl = "https://$domain"
            if (rootDomainUrl != url) {
                val rootCookies = cookieManager.getCookie(rootDomainUrl)
                parseAndMergeCookies(rootCookies, cookieMap)
            }
        }

        return cookieMap.map { (k, v) ->
            val isSecure = k.startsWith("__Secure-") || k.startsWith("__Host-")
            CookieItem(
                name = k,
                value = v,
                domain = domain,
                path = "/",
                isSecure = isSecure
            )
        }
    }

    private fun parseAndMergeCookies(raw: String?, targetMap: LinkedHashMap<String, String>) {
        if (raw.isNullOrBlank()) return
        val pairs = raw.split(";")
        for (pair in pairs) {
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val key = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (key.isNotEmpty() && !targetMap.containsKey(key)) {
                    targetMap[key] = value
                }
            }
        }
    }
}
