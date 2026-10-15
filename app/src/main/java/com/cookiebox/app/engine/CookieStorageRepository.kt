package com.cookiebox.app.engine

import android.content.Context
import android.content.SharedPreferences
import com.cookiebox.app.model.SavedCookieRecord
import org.json.JSONArray

class CookieStorageRepository(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("cookie_vault_storage", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_RECORDS = "key_saved_cookie_records"
    }

    @Synchronized
    fun getAllRecords(): List<SavedCookieRecord> {
        val raw = sp.getString(KEY_RECORDS, null) ?: return emptyList()
        val list = mutableListOf<SavedCookieRecord>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                list.add(SavedCookieRecord.fromJson(array.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun getRecordsByDomain(domain: String): List<SavedCookieRecord> {
        val root = DomainUtils.getRootDomain(domain)
        return getAllRecords().filter {
            it.domain.equals(domain, ignoreCase = true) ||
            DomainUtils.getRootDomain(it.domain).equals(root, ignoreCase = true)
        }
    }

    @Synchronized
    fun saveRecord(record: SavedCookieRecord) {
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

    @Synchronized
    fun deleteRecord(id: String) {
        val current = getAllRecords().toMutableList()
        current.removeAll { it.id == id }
        persist(current)
    }

    @Synchronized
    fun clearAll() {
        sp.edit().remove(KEY_RECORDS).apply()
    }

    private fun persist(list: List<SavedCookieRecord>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        sp.edit().putString(KEY_RECORDS, array.toString()).apply()
    }
}
