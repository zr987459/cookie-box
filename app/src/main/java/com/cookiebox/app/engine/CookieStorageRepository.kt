package com.cookiebox.app.engine

import android.content.Context
import android.content.SharedPreferences
import com.cookiebox.app.model.SavedCookieRecord
import org.json.JSONArray

/**
 * Cookie 储存库（v1.7.0 起 Keystore AES-256-GCM 加密）。
 *
 * 向后兼容铁律：任何情况下都不清空、不覆盖用户已存的数据。
 *  - 读取：密文 KEY_RECORDS_ENC 与旧版明文 KEY_RECORDS 都会读；两者同时存在时按 id 合并
 *          （密文为准），不再直接删除明文，避免降级/降级写入期间新增的记录丢失。
 *  - 迁移：明文先确认是合法 JSON，再加密 + 回读校验，成功后用同一次 commit() 原子地
 *          写入密文并删除明文；任一步失败都保留明文，下次启动再试。
 *  - 密文解不开（Keystore 暂时异常或密钥丢失）：每一份解不开的密文都单独备份到
 *          KEY_RECORDS_ENC_UNREADABLE[_时间戳]，覆盖 KEY_RECORDS_ENC 之前也会再检查一次；
 *          之后每次读取都尝试解开这些备份，能解开就合并回来，合并写入成功后才删除备份。
 *  - 明文 JSON 损坏：原样备份到 KEY_RECORDS_CORRUPT 后再继续，不让后续保存把它覆盖掉。
 *  - 加密不可用（个别 ROM Keystore 异常）：降级为明文写入，保证新数据不丢。
 */
class CookieStorageRepository(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("cookie_vault_storage", Context.MODE_PRIVATE)

    private val crypto = SecureStore(KEY_ALIAS)

    companion object {
        /** 旧版明文（v1.6.x 及之前），键名保持不变以便兼容读取 */
        private const val KEY_RECORDS = "key_saved_cookie_records"
        private const val KEY_RECORDS_ENC = "key_saved_cookie_records_enc"
        /** 前缀：第一份备份就是这个键本身，之后的备份追加 "_<毫秒时间戳>" */
        private const val KEY_RECORDS_ENC_UNREADABLE = "key_saved_cookie_records_enc_unreadable"
        private const val KEY_RECORDS_CORRUPT = "key_saved_cookie_records_corrupt"
        private const val KEY_ALIAS = "cookiebox_cookie_vault_key"

        /** 多个对话框各自 new 一个实例，锁必须是进程级的 */
        private val LOCK = Any()
    }

    fun getAllRecords(): List<SavedCookieRecord> = synchronized(LOCK) {
        loadRecords().sortedByDescending { it.timestamp }
    }

    fun getRecordsByDomain(domain: String): List<SavedCookieRecord> {
        val root = DomainUtils.getRootDomain(domain)
        return getAllRecords().filter {
            it.domain.equals(domain, ignoreCase = true) ||
            DomainUtils.getRootDomain(it.domain).equals(root, ignoreCase = true)
        }
    }

    fun saveRecord(record: SavedCookieRecord) = synchronized(LOCK) {
        val current = getAllRecords().toMutableList()
        // 允许同一网站保存多个账号！仅当 ID 相同时执行替换更新，否则直接新增
        val existingIndex = current.indexOfFirst { it.id == record.id }
        if (existingIndex >= 0) {
            current[existingIndex] = record
        } else {
            current.add(0, record)
        }
        persist(current)
        Unit
    }

    fun deleteRecord(id: String) = synchronized(LOCK) {
        val current = getAllRecords().toMutableList()
        current.removeAll { it.id == id }
        persist(current)
        Unit
    }

    /** 用户在储存库里主动点「清空」才会走到这里 */
    fun clearAll() = synchronized(LOCK) {
        val editor = sp.edit()
            .remove(KEY_RECORDS)
            .remove(KEY_RECORDS_ENC)
            .remove(KEY_RECORDS_CORRUPT)
        unreadableBackupKeys().forEach { editor.remove(it) }
        editor.commit()
        Unit
    }

    // ------------------------------------------------------------- 内部

    /** 读取全部来源并按 id 合并；必要时完成迁移/恢复（仅在加密写入成功后才删除旧来源） */
    private fun loadRecords(): List<SavedCookieRecord> {
        val legacy = sp.getString(KEY_RECORDS, null)
        val enc = sp.getString(KEY_RECORDS_ENC, null)

        var encPlain: String? = null
        if (enc != null) {
            encPlain = crypto.decrypt(enc)
            if (encPlain == null) backupUnreadable(enc) // 先保住，绝不让后续写入覆盖它
        }

        // 以前解不开的备份，现在能解开的就收回来
        val recovered = mutableListOf<SavedCookieRecord>()
        val recoveredKeys = mutableListOf<String>()
        for (key in unreadableBackupKeys()) {
            val plain = sp.getString(key, null)?.let { crypto.decrypt(it) } ?: continue
            if (!isValidJsonArray(plain)) continue
            recovered += parse(plain)
            recoveredKeys += key
        }

        val legacyValid = legacy != null && isValidJsonArray(legacy)
        val merged = mergeById(parse(encPlain), recovered, parse(legacy))

        if (legacyValid || recoveredKeys.isNotEmpty()) {
            // 迁移 / 恢复：只有在加密 + 回读校验成功后，才删除明文和已恢复的备份。
            // 当前密文若解不开，上面已备份，覆盖它是安全的。
            writeEncrypted(merged, removeLegacy = legacyValid, removeKeys = recoveredKeys)
        }
        return merged
    }

    /** 主列表优先；其余来源只补充主列表里没有的 id，宁可多留不可少留 */
    private fun mergeById(vararg sources: List<SavedCookieRecord>): List<SavedCookieRecord> {
        val byId = LinkedHashMap<String, SavedCookieRecord>()
        for (src in sources) for (r in src) if (!byId.containsKey(r.id)) byId[r.id] = r
        return byId.values.toList()
    }

    private fun toJson(list: List<SavedCookieRecord>): String {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    /** 只在加密成功时写入；失败返回 false 且什么都不动 */
    private fun writeEncrypted(
        list: List<SavedCookieRecord>,
        removeLegacy: Boolean,
        removeKeys: List<String>
    ): Boolean {
        val enc = crypto.encryptVerified(toJson(list)) ?: return false
        val editor = sp.edit().putString(KEY_RECORDS_ENC, enc)
        if (removeLegacy) editor.remove(KEY_RECORDS)
        removeKeys.forEach { editor.remove(it) }
        return editor.commit()
    }

    private fun persist(list: List<SavedCookieRecord>) {
        // 覆盖前再确认一次：当前密文若解不开，先备份
        sp.getString(KEY_RECORDS_ENC, null)?.let { if (crypto.decrypt(it) == null) backupUnreadable(it) }
        val json = toJson(list)
        val enc = crypto.encryptVerified(json)
        if (enc != null) {
            sp.edit().putString(KEY_RECORDS_ENC, enc).remove(KEY_RECORDS).commit()
        } else {
            // 加密不可用：降级明文，并去掉旧密文，避免下次读到过期数据（解不开的密文已另行备份）
            sp.edit().putString(KEY_RECORDS, json).remove(KEY_RECORDS_ENC).commit()
        }
    }

    private fun unreadableBackupKeys(): List<String> =
        sp.all.keys.filter { it.startsWith(KEY_RECORDS_ENC_UNREADABLE) }.sorted()

    /** 每份不同的解不开的密文各存一份，已有相同内容则跳过 */
    private fun backupUnreadable(enc: String) {
        val keys = unreadableBackupKeys()
        if (keys.any { sp.getString(it, null) == enc }) return
        val key = if (!sp.contains(KEY_RECORDS_ENC_UNREADABLE)) KEY_RECORDS_ENC_UNREADABLE
                  else "${KEY_RECORDS_ENC_UNREADABLE}_${System.currentTimeMillis()}"
        sp.edit().putString(key, enc).commit()
    }

    private fun parse(raw: String?): List<SavedCookieRecord> {
        if (raw == null) return emptyList()
        val list = mutableListOf<SavedCookieRecord>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                try {
                    list.add(SavedCookieRecord.fromJson(array.getJSONObject(i)))
                } catch (e: Exception) {
                    e.printStackTrace() // 单条坏数据跳过，不影响其他记录
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (!sp.contains(KEY_RECORDS_CORRUPT)) {
                // 备份本身也尽量加密；加密不可用时原样保存，宁可明文也不丢
                sp.edit().putString(KEY_RECORDS_CORRUPT, crypto.encrypt(raw) ?: raw).commit()
            }
        }
        return list
    }

    private fun isValidJsonArray(s: String): Boolean = try {
        JSONArray(s); true
    } catch (e: Exception) {
        false
    }
}
