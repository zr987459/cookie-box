package com.cookiebox.app.engine

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.cookiebox.app.model.AccountCredential
import com.cookiebox.app.model.SavedCookieRecord
import org.json.JSONArray
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 账号密码库：本机存储，密码用 Android Keystore 中不可导出的 AES-256-GCM 密钥加密。
 */
class AccountVault(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("account_vault", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ASK_SAVE = "ask_to_save"
        private const val KEY_NEVER_SITES = "never_sites"
        private const val KEY_ALIAS = "cookiebox_account_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        fun rootOf(urlOrHost: String): String {
            val s = urlOrHost.trim()
            if (s.isEmpty()) return ""
            val host = try {
                if (s.contains("://")) Uri.parse(s).host ?: s else Uri.parse("https://$s").host ?: s
            } catch (e: Exception) {
                s
            }
            return DomainUtils.getRootDomain(host.lowercase()).ifEmpty { host.lowercase() }
        }
    }

    // ------------------------------------------------------------- 设置

    var askToSave: Boolean
        get() = sp.getBoolean(KEY_ASK_SAVE, true)
        set(v) { sp.edit().putBoolean(KEY_ASK_SAVE, v).apply() }

    fun isNeverSite(root: String) = sp.getStringSet(KEY_NEVER_SITES, emptySet())!!.contains(root)

    fun addNeverSite(root: String) {
        val set = HashSet(sp.getStringSet(KEY_NEVER_SITES, emptySet())!!)
        set.add(root)
        sp.edit().putStringSet(KEY_NEVER_SITES, set).apply()
    }

    // ------------------------------------------------------------- 增删改查

    @Synchronized
    fun getAll(): List<AccountCredential> {
        val raw = sp.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { AccountCredential.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }.sortedWith(compareBy({ it.domain }, { it.username.lowercase() }))
    }

    fun getForSite(urlOrHost: String): List<AccountCredential> {
        val root = rootOf(urlOrHost)
        if (root.isEmpty()) return emptyList()
        return getAll().filter { it.domain.equals(root, true) }
    }

    fun find(root: String, username: String): AccountCredential? =
        getAll().firstOrNull { it.domain.equals(root, true) && it.username == username }

    @Synchronized
    fun save(account: AccountCredential) {
        val list = getAll().toMutableList()
        val i = list.indexOfFirst { it.id == account.id }
        if (i >= 0) list[i] = account else list.add(account)
        persist(list)
    }

    /** 新建或更新同站同账号名的记录 */
    fun upsert(
        siteUrl: String,
        username: String,
        password: String,
        note: String = "",
        linkedCookieId: String? = null
    ): AccountCredential {
        val root = rootOf(siteUrl)
        val existing = find(root, username)
        val acc = (existing ?: AccountCredential(
            id = UUID.randomUUID().toString(),
            domain = root,
            loginUrl = siteUrl,
            username = username
        )).copy(
            encryptedPassword = if (password.isNotEmpty()) encrypt(password) else existing?.encryptedPassword ?: "",
            note = note.ifBlank { existing?.note ?: "" },
            linkedCookieId = linkedCookieId ?: existing?.linkedCookieId,
            loginUrl = existing?.loginUrl?.ifBlank { siteUrl } ?: siteUrl,
            updatedAt = System.currentTimeMillis()
        )
        save(acc)
        return acc
    }

    @Synchronized
    fun delete(id: String) {
        persist(getAll().filter { it.id != id })
    }

    /** Cookie 记录被删除时解除绑定 */
    @Synchronized
    fun unlinkCookie(cookieId: String) {
        persist(getAll().map { if (it.linkedCookieId == cookieId) it.copy(linkedCookieId = null) else it })
    }

    private fun persist(list: List<AccountCredential>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    fun passwordOf(acc: AccountCredential): String? =
        if (acc.encryptedPassword.isEmpty()) "" else decrypt(acc.encryptedPassword)

    // ------------------------------------------------------------- 导入

    data class ImportResult(val added: Int, val updated: Int, val skipped: Int)

    /**
     * 导入 CSV。兼容 Chrome / Google 密码管理器、Edge、Firefox、Bitwarden、1Password、KeePass 等导出格式，
     * 以及本 App 导出的格式。按表头识别列，没有表头时按 name,url,username,password,note 处理。
     */
    fun importCsv(text: String): ImportResult {
        val rows = parseCsv(text.removePrefix("\uFEFF"))
        if (rows.isEmpty()) return ImportResult(0, 0, 0)
        val header = rows[0].map { it.trim().lowercase() }

        fun col(vararg names: String): Int = header.indexOfFirst { h -> names.any { h == it } }

        var iUrl = col("url", "login_uri", "website", "web site", "origin", "网址", "网站", "login url")
        var iUser = col("username", "login_username", "user", "login", "email", "user name", "账号", "用户名", "login name")
        var iPass = col("password", "login_password", "pass", "密码")
        var iNote = col("note", "notes", "comment", "comments", "extra", "备注")
        var iName = col("name", "title", "名称", "标题")
        val hasHeader = iPass >= 0 || iUser >= 0
        if (!hasHeader) {
            iName = 0; iUrl = 1; iUser = 2; iPass = 3; iNote = 4
        }

        var added = 0
        var updated = 0
        var skipped = 0
        val body = if (hasHeader) rows.drop(1) else rows
        for (r in body) {
            fun at(i: Int) = if (i in r.indices) r[i].trim() else ""
            var url = at(iUrl)
            if (url.isEmpty()) url = at(iName)
            val user = at(iUser)
            val pass = at(iPass)
            if (url.isEmpty() || (user.isEmpty() && pass.isEmpty())) { skipped++; continue }
            // Android 应用条目（android://…）没有网址，跳过
            if (url.startsWith("android://")) { skipped++; continue }
            val root = rootOf(url)
            if (root.isEmpty()) { skipped++; continue }
            val exists = find(root, user) != null
            upsert(url, user, pass, at(iNote))
            if (exists) updated++ else added++
        }
        return ImportResult(added, updated, skipped)
    }

    /** 把 Cookie 储存库里已有的账号同步成账号条目（无密码，已绑定 Cookie，可一键免密） */
    fun importFromCookieRecords(records: List<SavedCookieRecord>): ImportResult {
        var added = 0
        var skipped = 0
        val all = getAll()
        for (rec in records) {
            if (all.any { it.linkedCookieId == rec.id }) { skipped++; continue }
            val root = rootOf(rec.domain.ifBlank { rec.url })
            if (root.isEmpty()) { skipped++; continue }
            val name = rec.title.ifBlank { "账号" }
            val existing = find(root, name)
            if (existing != null) {
                if (existing.linkedCookieId == null) save(existing.copy(linkedCookieId = rec.id))
                skipped++
                continue
            }
            save(
                AccountCredential(
                    id = UUID.randomUUID().toString(),
                    domain = root,
                    loginUrl = rec.url,
                    username = name,
                    note = "来自 Cookie 储存库",
                    linkedCookieId = rec.id
                )
            )
            added++
        }
        return ImportResult(added, 0, skipped)
    }

    /** 导出为 Chrome 兼容 CSV（明文密码） */
    fun exportCsv(): String {
        val sb = StringBuilder("name,url,username,password,note\n")
        for (a in getAll()) {
            val url = a.loginUrl.ifBlank { "https://${a.domain}/" }
            listOf(a.domain, url, a.username, passwordOf(a) ?: "", a.note)
                .joinToString(",") { csvEscape(it) }
                .let { sb.append(it).append('\n') }
        }
        return sb.toString()
    }

    private fun csvEscape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        // 自动识别分隔符（部分工具导出为分号或制表符）
        val firstLine = text.lineSequence().firstOrNull() ?: ""
        val sep = when {
            firstLine.count { it == ',' } >= 1 -> ','
            firstLine.count { it == ';' } >= 1 -> ';'
            firstLine.count { it == '\t' } >= 1 -> '\t'
            else -> ','
        }
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cur.append('"'); i++ } else inQuotes = false
                } else cur.append(c)
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    sep -> { row.add(cur.toString()); cur.clear() }
                    '\r' -> {}
                    '\n' -> {
                        row.add(cur.toString()); cur.clear()
                        if (row.any { it.isNotBlank() }) rows.add(row)
                        row = mutableListOf()
                    }
                    else -> cur.append(c)
                }
            }
            i++
        }
        row.add(cur.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }

    // ------------------------------------------------------------- 加密

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            return (ks.getKey(KEY_ALIAS, null) as? SecretKey)
                ?: throw IllegalStateException("KeyStore contains alias $KEY_ALIAS but key is missing/invalid")
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    /** 解密失败（例如换机后 Keystore 密钥不存在）返回 null */
    private fun decrypt(enc: String): String? = try {
        val data = Base64.decode(enc, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
