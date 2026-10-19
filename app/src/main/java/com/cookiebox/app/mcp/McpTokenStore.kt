package com.cookiebox.app.mcp

import android.content.Context
import android.util.Base64
import com.cookiebox.app.engine.SecureStore
import java.security.SecureRandom

/**
 * MCP 访问 Token：32 字节随机数，Base64URL；用新 Keystore 别名 `cookiebox_mcp_key` 加密后存 `mcp_prefs.token_enc`。
 * 别名取不出时只重新生成 MCP Token，不碰另外两个别名（ADR-0004 §5.6）。
 */
object McpTokenStore {

    private const val ALIAS = "cookiebox_mcp_key"

    @Volatile private var cached: String? = null

    /** 当前 Token；没有就生成。Keystore 不可用时抛异常（服务因此不启动）。 */
    @Synchronized
    fun get(c: Context): String {
        cached?.let { return it }
        McpPrefs.tokenEnc(c)?.let { enc ->
            SecureStore(ALIAS).decrypt(enc)?.let { cached = it; return it }
        }
        return reset(c)
    }

    /** 重置后旧 Token 立即失效（服务每次请求都读这里）。 */
    @Synchronized
    fun reset(c: Context): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val enc = SecureStore(ALIAS).encryptVerified(token)
            ?: throw IllegalStateException("Keystore unavailable")
        if (!McpPrefs.setTokenEnc(c, enc)) throw IllegalStateException("token save failed")
        cached = token
        return token
    }

    /** 常量时间比较。 */
    fun matches(c: Context, presented: String?): Boolean {
        if (presented.isNullOrEmpty()) return false
        val expected = try { get(c) } catch (e: Exception) { return false }
        val a = presented.toByteArray(Charsets.UTF_8)
        val b = expected.toByteArray(Charsets.UTF_8)
        var diff = a.size xor b.size
        for (i in b.indices) diff = diff or (b[i].toInt() xor (if (i < a.size) a[i].toInt() else 0))
        return diff == 0
    }
}
