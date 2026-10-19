package com.cookiebox.app.mcp

import android.content.Context
import android.util.Base64
import com.cookiebox.app.engine.SecureStore
import java.security.SecureRandom

/**
 * MCP Bearer Token：32 字节随机数，Base64URL。
 * 落盘只存 Keystore 新别名 cookiebox_mcp_key 加密后的密文（mcp_prefs.token_enc），
 * 复用 SecureStore「别名存在就不重建」规则，绝不触碰其他别名。
 * Token 不进日志、Toast、异常信息。
 */
object McpTokenStore {

    private const val KEY_ALIAS = "cookiebox_mcp_key"
    private const val TOKEN_BYTES = 32

    private val secure by lazy { SecureStore(KEY_ALIAS) }
    private val random = SecureRandom()

    @Volatile
    private var cached: String? = null

    /** 加密是否成功落盘；失败时 Token 只在内存里，进程被杀后需重新开启 */
    @Volatile
    var persisted: Boolean = false
        private set

    /** 当前 Token；没有或解不开时返回 null（服务据此拒绝所有请求） */
    fun current(context: Context): String? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val enc = McpPrefs(context).tokenEnc ?: return null
            val plain = if (SecureStore.isCiphertext(enc)) secure.decrypt(enc) else null
            if (plain != null && plain.isNotEmpty()) {
                cached = plain
                persisted = true
            }
            return cached
        }
    }

    /** 生成新 Token，旧 Token 立即失效 */
    fun regenerate(context: Context): String {
        synchronized(this) {
            val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
            val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val enc = secure.encryptVerified(token)
            val prefs = McpPrefs(context)
            if (enc != null) {
                prefs.tokenEnc = enc
                persisted = true
            } else {
                // Keystore 不可用：不落明文，只放内存
                prefs.tokenEnc = null
                persisted = false
            }
            cached = token
            return token
        }
    }
}
