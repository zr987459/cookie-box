package com.cookiebox.app.engine

import android.content.Context
import android.content.SharedPreferences
import com.cookiebox.app.model.SavedCookieRecord
import org.json.JSONArray

/**
 * Cookie 储存库（v1.7.0 起 Keystore AES-256-GCM 加密）。
 *
 * 向后兼容铁律：任何情况下都不清空、不覆盖用户已存的数据。
 *  - 读取：优先读密文 KEY_RECORDS_ENC；没有密文而有旧版明文 KEY_RECORDS，则原样解析并透明迁移。
 *  - 迁移：明文先确认是合法 JSON，再加密 + 回读校验，成功后用同一次 commit() 原子地
 *          写入密文并删除明文；任一步失败都保留明文，下次启动再试。
 *  - 密文解不开（例如 Keystore 密钥丢失）：若明文仍在则继续用明文；否则把密文原样备份到
 *          KEY_RECORDS_ENC_UNREADABLE，后续写入绝不覆盖这份备份。
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
        private const val KEY_RECORDS_ENC_UNREADABLE = "key_saved_cookie_records_enc_unreadable"
        private const val KEY_RECORDS_CORRUPT = "key_saved_cookie_records_corrupt"
        private const val KEY_ALIAS = "cookiebox_cookie_vault_key"

        /** 多个对话框各自 new 一个实例，锁必须是进程级的 */
        private val LOCK = Any()
    }

    fun getAllRecords(): List<SavedCookieRecord> = synchronized(LOCK) {
        parse(loadRaw()).sortedByDescending { it.timestamp }
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
    }

    fun deleteRecord(id: String) = synchronized(LOCK) {
        val current = getAllRecords().toMutableList()
        current.removeAll { it.id == id }
        persist(current)
    }

    /** 用户在储存库里主动点「清空」才会走到这里 */
    fun clearAll() = synchronized(LOCK) {
        sp.edit()
            .remove(KEY_RECORDS)
            .remove(KEY_RECORDS_ENC)
            .remove(KEY_RECORDS_ENC_UNREADABLE)
            .remove(KEY_RECORDS_CORRUPT)
            .commit()
        Unit
    }

    // ------------------------------------------------------------- 内部

    /** 返回当前有效的明文 JSON；没有任何数据时返回 null */
    private fun loadRaw(): String? {
        val legacy = sp.getString(KEY_RECORDS, null)
        val enc = sp.getString(KEY_RECORDS_ENC, null)

        if (enc != null) {
            val plain = crypto.decrypt(enc)
            if (plain != null) {
                // 已加密；若还残留明文（极少数中断情况），密文为准，顺手删掉明文
                if (legacy != null) sp.edit().remove(KEY_RECORDS).commit()
                return plain
            }
            // 密文解不开：先保住这份密文，绝不让后续写入覆盖它
            if (!sp.contains(KEY_RECORDS_ENC_UNREADABLE)) {
                sp.edit().putString(KEY_RECORDS_ENC_UNREADABLE, enc).commit()
            }
            return legacy
        }

        if (legacy != null) migrateLegacy(legacy)
        return legacy
    }

    /** 旧版明文 → 密文。只有在密文写入并校验成功后才删除明文 */
    private fun migrateLegacy(legacy: String) {
        if (!isValidJsonArray(legacy)) return // 由 parse() 负责备份损坏数据，这里不动它
        val enc = crypto.encryptVerified(legacy) ?: return
        sp.edit()
            .putString(KEY_RECORDS_ENC, enc)
            .remove(KEY_RECORDS)
            .commit()
    }

    private fun persist(list: List<SavedCookieRecord>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        val json = array.toString()
        val enc = crypto.encryptVerified(json)
        if (enc != null) {
            sp.edit().putString(KEY_RECORDS_ENC, enc).remove(KEY_RECORDS).commit()
        } else {
            // 加密不可用：降级明文，并去掉旧密文，避免下次读到过期数据（不可读的密文已另行备份）
            sp.edit().putString(KEY_RECORDS, json).remove(KEY_RECORDS_ENC).commit()
        }
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
