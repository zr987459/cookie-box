package com.cookiebox.app.engine

import android.net.Uri

object DomainUtils {

    /**
     * 智能提取顶级/根域名 (例如 passport.bilibili.com -> bilibili.com)
     */
    fun getRootDomain(domain: String): String {
        val clean = domain.trim().trimStart('.').lowercase()
        if (clean.isBlank()) return ""
        val parts = clean.split(".")
        if (parts.size <= 2) return clean

        // 常见二级国别后缀
        val specialSuffixes = listOf(
            "com.cn", "org.cn", "net.cn", "gov.cn", "edu.cn",
            "co.uk", "org.uk", "me.uk", "com.hk", "org.hk"
        )
        for (suffix in specialSuffixes) {
            if (clean.endsWith(".$suffix") && parts.size >= 3) {
                return parts.takeLast(3).joinToString(".")
            }
        }
        return parts.takeLast(2).joinToString(".")
    }

    /**
     * 智能解析免密直达的入口 URL (避开 /login /passport 等登录拦截页，直达主站首页或原内容页)
     */
    fun getCleanEntryUrl(originalUrl: String, domain: String): String {
        val root = getRootDomain(domain)
        if (originalUrl.isBlank()) {
            return if (root.isNotEmpty()) "https://$root" else "https://$domain"
        }

        try {
            val uri = Uri.parse(originalUrl)
            val host = uri.host?.lowercase() ?: ""
            val path = uri.path?.lowercase() ?: ""

            // 检测是否包含登录/通行证重定向特征
            val isLoginPage = path.contains("login") ||
                    path.contains("signin") ||
                    path.contains("passport") ||
                    path.contains("oauth") ||
                    path.contains("auth") ||
                    host.startsWith("passport.") ||
                    host.startsWith("login.")

            if (isLoginPage) {
                // 常见网站移动端首页适配
                return when (root) {
                    "bilibili.com" -> "https://m.bilibili.com"
                    "weibo.com" -> "https://weibo.com"
                    "baidu.com" -> "https://www.baidu.com"
                    "zhihu.com" -> "https://www.zhihu.com"
                    "github.com" -> "https://github.com"
                    "v2ex.com" -> "https://www.v2ex.com"
                    else -> if (root.isNotEmpty()) "https://$root" else "https://$domain"
                }
            }

            return originalUrl
        } catch (e: Exception) {
            return if (root.isNotEmpty()) "https://$root" else "https://$domain"
        }
    }
}
