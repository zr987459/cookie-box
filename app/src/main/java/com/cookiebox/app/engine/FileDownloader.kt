package com.cookiebox.app.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用内下载器：不依赖系统「下载管理器」（部分国产系统会禁用或拦截它）。
 * - 自己发请求，逐跳带上当前标签身份的 Cookie / UA / Referer，手动跟随跨域重定向
 * - 优先使用服务器返回的 Content-Disposition 文件名（支持 filename*=UTF-8''）
 * - Android 10+ 写入 MediaStore「下载」目录，无需存储权限；旧系统写入公共下载目录
 * - 通知栏显示进度，完成后点击即可打开（APK 直接进入安装）
 */
class FileDownloader(context: Context) {

    data class Result(val uri: Uri, val fileName: String, val mimeType: String)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val ids = AtomicInteger(4000)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "文件下载", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    fun download(
        url: String,
        userAgent: String,
        referer: String?,
        cookieManager: CookieManager,
        contentDisposition: String?,
        mimeHint: String?,
        onDone: (Result?, String?) -> Unit
    ) {
        val notifyId = ids.incrementAndGet()
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                var current = URL(url)
                var hops = 0
                while (true) {
                    val c = current.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 20_000
                    c.readTimeout = 60_000
                    c.setRequestProperty("User-Agent", userAgent)
                    c.setRequestProperty("Accept", "*/*")
                    if (!referer.isNullOrBlank() && referer.startsWith("http")) c.setRequestProperty("Referer", referer)
                    val cookies = try { cookieManager.getCookie(current.toString()) } catch (e: Exception) { null }
                    if (!cookies.isNullOrBlank()) c.setRequestProperty("Cookie", cookies)
                    val code = c.responseCode
                    val location = c.getHeaderField("Location")
                    if (code in 300..399 && location != null && hops < 10) {
                        c.disconnect()
                        current = URL(current, location)
                        hops++
                        continue
                    }
                    if (code !in 200..299) {
                        c.disconnect()
                        throw IOException("服务器返回 HTTP $code")
                    }
                    conn = c
                    break
                }
                val c = conn!!
                val finalUrl = current.toString()
                val headerCd = c.getHeaderField("Content-Disposition")
                var mime = c.contentType?.substringBefore(';')?.trim()?.lowercase()
                if (mime.isNullOrBlank() || mime == "application/octet-stream" || mime == "binary/octet-stream") {
                    mime = mimeHint?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: mime
                }
                var fileName = fileNameFrom(headerCd) ?: fileNameFrom(contentDisposition)
                    ?: URLUtil.guessFileName(finalUrl, null, mime)
                fileName = sanitize(fileName)
                val ext = fileName.substringAfterLast('.', "").lowercase()
                if (mime.isNullOrBlank() || mime == "application/octet-stream" || mime == "binary/octet-stream") {
                    mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                }
                val total = c.contentLengthLong
                notifyProgress(notifyId, fileName, 0, total)
                val result = c.inputStream.use { input ->
                    save(input, fileName, mime!!) { done -> notifyProgress(notifyId, fileName, done, total) }
                }
                notifyDone(notifyId, result)
                main.post { onDone(result, null) }
            } catch (e: Exception) {
                notifyFailed(notifyId, e.message ?: e.javaClass.simpleName)
                main.post { onDone(null, e.message ?: e.javaClass.simpleName) }
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** 保存网页脚本生成的数据（blob: / data:） */
    fun saveBytes(bytes: ByteArray, fileName: String, mimeType: String, onDone: (Result?, String?) -> Unit) {
        val notifyId = ids.incrementAndGet()
        executor.execute {
            try {
                val result = bytes.inputStream().use { save(it, sanitize(fileName), mimeType) {} }
                notifyDone(notifyId, result)
                main.post { onDone(result, null) }
            } catch (e: Exception) {
                main.post { onDone(null, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun openIntent(result: Result): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(result.uri, result.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---------- 写文件 ----------

    private fun save(input: InputStream, fileName: String, mime: String, onProgress: (Long) -> Unit): Result {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在「下载」目录创建文件")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("无法写入文件")
                out.use { copy(input, it, onProgress) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                throw e
            }
            // MediaStore 遇到重名会自动改名，读回真实文件名
            var realName = fileName
            try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) realName = cur.getString(0) ?: fileName
                }
            } catch (_: Exception) {}
            Result(uri, realName, mime)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = uniqueFile(dir, fileName)
            try {
                FileOutputStream(file).use { copy(input, it, onProgress) }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            Result(scanForUri(file, mime), file.name, mime)
        }
    }

    private fun copy(input: InputStream, out: OutputStream, onProgress: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var lastReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            val now = System.currentTimeMillis()
            if (now - lastReport > 500) {
                lastReport = now
                onProgress(done)
            }
        }
        out.flush()
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }

    private fun scanForUri(file: File, mime: String): Uri {
        val lock = Object()
        var scanned: Uri? = null
        var finished = false
        MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), arrayOf(mime)) { _, uri ->
            synchronized(lock) {
                scanned = uri
                finished = true
                lock.notifyAll()
            }
        }
        synchronized(lock) {
            if (!finished) lock.wait(5_000)
        }
        return scanned ?: Uri.fromFile(file)
    }

    // ---------- 文件名 ----------

    private fun fileNameFrom(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            val raw = m.groupValues[2].trim().trim('"')
            val decoded = try { URLDecoder.decode(raw.replace("+", "%2B"), charset) } catch (e: Exception) { raw }
            if (decoded.isNotBlank()) return decoded
        }
        Regex("filename\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val v = m.groupValues[1]
            if (v.isNotBlank()) return decodeMaybe(v)
        }
        Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val v = m.groupValues[1].trim()
            if (v.isNotBlank()) return decodeMaybe(v)
        }
        return null
    }

    private fun decodeMaybe(v: String): String =
        if (v.contains('%')) try { URLDecoder.decode(v.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { v } else v

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        return cleaned.ifBlank { "download" }.take(150)
    }

    // ---------- 通知 ----------

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    @Suppress("MissingPermission")
    private fun post(id: Int, builder: NotificationCompat.Builder) {
        if (!canNotify()) return
        try {
            NotificationManagerCompat.from(appContext).notify(id, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun notifyProgress(id: Int, name: String, done: Long, total: Long) {
        val b = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (total > 0) {
            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
            b.setProgress(100, pct, false).setContentText("$pct%  ${human(done)} / ${human(total)}")
        } else {
            b.setProgress(0, 0, true).setContentText(human(done))
        }
        post(id, b)
    }

    private fun notifyDone(id: Int, result: Result) {
        val pi = PendingIntent.getActivity(
            appContext, id, openIntent(result),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        post(
            id, NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(result.fileName)
                .setContentText("下载完成，点按打开")
                .setContentIntent(pi)
                .setAutoCancel(true)
        )
    }

    private fun notifyFailed(id: Int, reason: String) {
        post(
            id, NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("下载失败")
                .setContentText(reason)
                .setAutoCancel(true)
        )
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
    }
}
