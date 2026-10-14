package com.cookiebox.app.engine

object CookieDictionary {

    data class CookieMeta(
        val category: String, // 核心凭据, 安全校验, 用户标识, 设备指纹, 统计分析, 常规参数
        val description: String,
        val isCritical: Boolean = false
    )

    private val DICTIONARY = mapOf(
        // Bilibili
        "SESSDATA" to CookieMeta("核心凭据", "B站核心登录凭据 (最关键！有效期内拥有此项即可免密登录账号)", true),
        "bili_jct" to CookieMeta("安全校验", "CSRF 防护 Token (点赞、投币、发动态、修改资料等操作必须携带)", true),
        "DedeUserID" to CookieMeta("用户标识", "当前登录的 B站数字 UID 账号"),
        "DedeUserID__ckMd5" to CookieMeta("安全校验", "用户 UID 的 MD5 校验码，防止篡改"),
        "sid" to CookieMeta("会话凭据", "B站临时会话标识符 Session ID"),
        "buvid3" to CookieMeta("设备指纹", "B站设备与浏览器硬件指纹唯一识别码"),
        "buvid4" to CookieMeta("设备指纹", "B站新版设备风控识别码"),
        "_uuid" to CookieMeta("设备指纹", "B站前端生成的全局唯一访问标识符"),

        // Baidu
        "BDUSS" to CookieMeta("核心凭据", "百度全系核心登录凭据 (贴吧/网盘/文库/搜索免密通用)", true),
        "BDUSS_BFESS" to CookieMeta("核心凭据", "百度跨域 SameSite=None 核心鉴权凭据", true),
        "BAIDUID" to CookieMeta("设备指纹", "百度客户端与浏览器唯一硬件特征标识"),
        "BAIDUID_BFESS" to CookieMeta("设备指纹", "百度跨域设备识别特征"),
        "STOKEN" to CookieMeta("安全校验", "百度安全操作与二次验证令牌"),
        "BD_UPN" to CookieMeta("常规参数", "百度搜索个性化偏好记录"),

        // Weibo
        "SUB" to CookieMeta("核心凭据", "微博核心登录鉴权 Token (包含用户完整身份，爬虫必备)", true),
        "SUBP" to CookieMeta("安全校验", "微博用户权限与设备签名校验 Token", true),
        "_T_WM" to CookieMeta("设备指纹", "微博移动端访客标识与设备特征码"),
        "ALF" to CookieMeta("常规参数", "微博当前会话有效期限时间戳"),
        "SSOLoginState" to CookieMeta("会话凭据", "微博单点登录 (SSO) 状态记录"),

        // GitHub
        "user_session" to CookieMeta("核心凭据", "GitHub 网页核心登录会话凭据", true),
        "__Host-user_session_same_site" to CookieMeta("核心凭据", "GitHub SameSite 隔离的安全登录凭据", true),
        "dotcom_user" to CookieMeta("用户标识", "当前登录的 GitHub 用户名"),
        "logged_in" to CookieMeta("常规参数", "GitHub 网页登录状态标记 (yes/no)"),
        "_gh_sess" to CookieMeta("会话凭据", "GitHub 服务端加密会话数据"),

        // Zhihu
        "z_c0" to CookieMeta("核心凭据", "知乎核心登录鉴权 Token", true),
        "d_c0" to CookieMeta("设备指纹", "知乎设备与访客识别特征码"),
        "_xsrf" to CookieMeta("安全校验", "知乎跨站请求防护 CSRF Token", true),

        // General Common Web Tokens
        "token" to CookieMeta("核心凭据", "通用系统鉴权令牌 (Token)", true),
        "auth_token" to CookieMeta("核心凭据", "身份认证授权令牌", true),
        "access_token" to CookieMeta("核心凭据", "接口访问授权 Access Token", true),
        "session" to CookieMeta("会话凭据", "服务端会话 Session 标识"),
        "sessionid" to CookieMeta("会话凭据", "通用服务端会话 Session ID", true),
        "JSESSIONID" to CookieMeta("会话凭据", "Java / Spring 服务端会话标识", true),
        "PHPSESSID" to CookieMeta("会话凭据", "PHP 架构服务端会话标识", true),
        "ASPSESSIONID" to CookieMeta("会话凭据", "ASP.NET 服务端会话标识"),
        "csrf_token" to CookieMeta("安全校验", "CSRF 跨站请求伪造防护安全令牌"),
        "xsrf_token" to CookieMeta("安全校验", "XSRF 防护安全令牌"),
        "uid" to CookieMeta("用户标识", "用户唯一数字账号 ID"),
        "user_id" to CookieMeta("用户标识", "用户唯一身份标识"),
        "cf_clearance" to CookieMeta("安全校验", "Cloudflare 5秒盾人机验证通过凭据", true),
        "remember_token" to CookieMeta("核心凭据", "记住我 / 免密自动登录凭据")
    )

    /**
     * 根据 Cookie 名称智能返回介绍信息
     */
    fun getMeta(name: String): CookieMeta {
        // 1. 精确匹配
        DICTIONARY[name]?.let { return it }

        // 2. 忽略大小写匹配
        for ((k, v) in DICTIONARY) {
            if (k.equals(name, ignoreCase = true)) return v
        }

        // 3. 启发式智能推断
        val lower = name.lowercase()
        return when {
            lower.contains("sess") || lower.contains("sid") ->
                CookieMeta("会话凭据", "服务端会话识别标识 (Session)")
            lower.contains("token") || lower.contains("auth") ->
                CookieMeta("核心凭据", "鉴权认证令牌 (Token)", isCritical = true)
            lower.contains("csrf") || lower.contains("xsrf") ->
                CookieMeta("安全校验", "CSRF 跨站请求伪造防护安全码")
            lower.contains("uid") || lower.contains("user") || lower.contains("account") ->
                CookieMeta("用户标识", "用户身份或账号特征参数")
            lower.contains("device") || lower.contains("did") || lower.contains("uuid") || lower.contains("fp") ->
                CookieMeta("设备指纹", "设备或浏览器软硬件环境指纹")
            lower.contains("sign") || lower.contains("sig") ->
                CookieMeta("安全校验", "防接口篡改签名校验参数")
            else ->
                CookieMeta("站点凭据", "网站业务运行时常规 Cookie")
        }
    }
}
