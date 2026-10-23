package com.cookiebox.app.engine

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 下载记录（下载管理面板的数据源）。
 *
 * - 每次下载在开始时就记一条，进度、完成、失败、取消都更新这条记录，保存在 SharedPreferences（最多 300 条）。
 * - 进程被杀时仍在「下载中」的记录，下次启动标记为「已中断」，残留的半截文件可以在面板里删除。
 * - Android 10+ 还会扫描 MediaStore「下载」里本应用写入的文件（含 IS_PENDING 的半截文件，Android 11+），
 *   这样此前版本（还没有下载记录时）下载的文件也能在面板里找到和删除。
 */
class DownloadStore private constructor(context: Context) {

    enum class Status { RUNNING, DONE, FAILED, CANCELED, INTERRUPTED }

    data class Rec(
        val id: Long,
        var fileName: String,
        var uri: String? = null,
        var path: String? = null,
        var mime: String = "application/octet-stream",
        var url: String? = null,
        var referer: String? = null,
        var total: Long = -1,
        var done: Long = 0,
        var status: Status = Status.RUNNING,
        var error: String? = null,
        val time: Long = System.currentTimeMillis(),
        /** 从 MediaStore 扫描补进来的旧文件（没有下载链接） */
        var found: Boolean = false
    )

    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("cookiebox_downloads", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val items = LinkedHashMap<Long, Rec>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var lastSave = 0L
    private var notifyPosted = false

    init {
        load()
        // 进程刚启动，不可能有正在进行的下载：上次没跑完的都算中断
        var changed = false
        synchronized(items) {
            items.values.filter { it.status == Status.RUNNING }.forEach {
                it.status = Status.INTERRUPTED
                it.error = "应用被关闭，下载中断"
                changed = true
            }
        }
        if (changed) persist(force = true)
    }

    fun all(): List<Rec> = synchronized(items) { items.values.map { it.copy() } }.sortedByDescending { it.time }

    fun get(id: Long): Rec? = synchronized(items) { items[id]?.copy() }

    fun runningCount(): Int = synchronized(items) { items.values.count { it.status == Status.RUNNING } }

    fun create(fileName: String, url: String?, referer: String?, mime: String?): Long {
        val id = synchronized(items) {
            var id = System.currentTimeMillis()
            while (items.containsKey(id)) id++
            items[id] = Rec(id = id, fileName = fileName, url = url, referer = referer,
                mime = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
            trim()
            id
        }
        persist(force = true)
        return id
    }

    fun update(id: Long, force: Boolean = false, block: (Rec) -> Unit) {
        synchronized(items) { items[id]?.let(block) ?: return }
        persist(force)
    }

    fun remove(ids: Collection<Long>) {
        synchronized(items) { ids.forEach { items.remove(it) } }
        persist(force = true)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /**
     * 扫描「下载」目录里本应用写入的文件：
     * - 补上没有记录的旧文件（标记 found）
     * - 已完成的记录如果文件已被删除，标记为「文件已删除」
     * 在后台线程调用。
     */
    fun syncWithMediaStore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            checkFilesExist()
            return
        }
        val resolver = app.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.IS_PENDING
        )
        val found = ArrayList<Rec>()
        val existing = HashSet<String>()
        try {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val args = Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?")
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(app.packageName))
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                resolver.query(collection, proj, args, null)
            } else {
                resolver.query(collection, proj, "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?", arrayOf(app.packageName), null)
            }
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(0)).toString()
                    existing.add(uri)
                    val pending = c.getInt(5) == 1
                    found += Rec(
                        id = 0,
                        fileName = c.getString(1) ?: "未命名文件",
                        uri = uri,
                        mime = c.getString(2) ?: "application/octet-stream",
                        total = c.getLong(3),
                        done = c.getLong(3),
                        status = if (pending) Status.INTERRUPTED else Status.DONE,
                        error = if (pending) "未下载完的残留文件" else null,
                        time = c.getLong(4) * 1000,
                        found = true
                    )
                }
            }
        } catch (e: Exception) {
            return
        }
        synchronized(items) {
            val known = items.values.mapNotNull { it.uri }.toHashSet()
            found.filter { it.uri !in known }.forEach { r ->
                var id = r.time.coerceAtLeast(1)
                while (items.containsKey(id)) id++
                items[id] = r.copy(id = id)
            }
            items.values.forEach { r ->
                val u = r.uri ?: return@forEach
                if (r.status == Status.DONE && u.startsWith("content://media") && u !in existing && !fileExists(u)) {
                    r.status = Status.FAILED
                    r.error = "文件已被删除或移动"
                }
            }
            trim()
        }
        persist(force = true)
    }

    private fun checkFilesExist() {
        synchronized(items) {
            items.values.forEach { r ->
                val p = r.path ?: return@forEach
                if (r.status == Status.DONE && !File(p).exists()) {
                    r.status = Status.FAILED
                    r.error = "文件已被删除或移动"
                }
            }
        }
        persist(force = true)
    }

    private fun fileExists(uri: String): Boolean = try {
        app.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    } catch (e: Exception) { false }

    /** 文件实际保存位置的说明 */
    fun locationHint(): String = "手机存储 › ${Environment.DIRECTORY_DOWNLOADS}"

    // ---------- 存取 ----------

    private fun trim() {
        if (items.size <= MAX) return
        val drop = items.values.filter { it.status != Status.RUNNING }
            .sortedBy { it.time }.take(items.size - MAX).map { it.id }
        drop.forEach { items.remove(it) }
    }

    private fun persist(force: Boolean) {
        val now = System.currentTimeMillis()
        if (force || now - lastSave > 1500) {
            lastSave = now
            val arr = JSONArray()
            synchronized(items) { items.values.forEach { arr.put(toJson(it)) } }
            sp.edit().putString(KEY, arr.toString()).apply()
        }
        if (!notifyPosted) {
            notifyPosted = true
            main.postDelayed({
                notifyPosted = false
                listeners.forEach { it() }
            }, 250)
        }
    }

    private fun load() {
        val raw = sp.getString(KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val r = fromJson(arr.getJSONObject(i)) ?: continue
                items[r.id] = r
            }
        } catch (_: Exception) {
            // 记录损坏时保留原文以便排查，不影响下载本身
            sp.edit().putString(KEY + "_corrupt", raw).remove(KEY).apply()
        }
    }

    private fun toJson(r: Rec) = JSONObject().apply {
        put("id", r.id); put("name", r.fileName); put("uri", r.uri); put("path", r.path)
        put("mime", r.mime); put("url", r.url); put("referer", r.referer)
        put("total", r.total); put("done", r.done); put("status", r.status.name)
        put("error", r.error); put("time", r.time); put("found", r.found)
    }

    private fun fromJson(o: JSONObject): Rec? = try {
        Rec(
            id = o.getLong("id"),
            fileName = o.optString("name", "未命名文件"),
            uri = o.optStringOrNull("uri"),
            path = o.optStringOrNull("path"),
            mime = o.optString("mime", "application/octet-stream"),
            url = o.optStringOrNull("url"),
            referer = o.optStringOrNull("referer"),
            total = o.optLong("total", -1),
            done = o.optLong("done", 0),
            status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.FAILED),
            error = o.optStringOrNull("error"),
            time = o.optLong("time", 0),
            found = o.optBoolean("found", false)
        )
    } catch (e: Exception) { null }

    private fun JSONObject.optStringOrNull(k: String): String? =
        if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

    companion object {
        private const val KEY = "items"
        private const val MAX = 300

        @Volatile private var instance: DownloadStore? = null
        fun get(context: Context): DownloadStore =
            instance ?: synchronized(this) { instance ?: DownloadStore(context).also { instance = it } }
    }
}
