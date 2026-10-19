package com.cookiebox.app.mcp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HttpReq(
    val method: String,
    val target: String,
    /** 键为小写 */
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteIp: String
) {
    val path: String get() = target.substringBefore('?')
}

class HttpResp(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap()
) {
    companion object {
        fun text(status: Int, msg: String, headers: Map<String, String> = emptyMap()) =
            HttpResp(status, msg.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8", headers)

        fun json(status: Int, json: String, headers: Map<String, String> = emptyMap()) =
            HttpResp(status, json.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", headers)
    }
}

/**
 * 手写的最小 HTTP/1.1 服务端（ADR-0004 §4.1）：按字节读 Content-Length，
 * 请求头 ≤16 KiB、请求体 ≤1 MiB、读超时 15 s、最多 4 个并发处理线程，每个请求处理完即关闭连接。
 */
class McpHttpServer(
    private val port: Int,
    private val lan: Boolean,
    private val handler: (HttpReq) -> HttpResp
) {
    companion object {
        private const val MAX_HEADER = 16 * 1024
        private const val MAX_BODY = 1024 * 1024
        private const val READ_TIMEOUT_MS = 15_000
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val pool = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16))

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        val addr = if (lan) InetSocketAddress(port) else InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
        ss.bind(addr, 32)
        serverSocket = ss
        running = true
        thread(name = "mcp-accept", isDaemon = true) {
            while (running) {
                val s = try { ss.accept() } catch (e: Exception) { if (!running) break else continue }
                try {
                    pool.execute { handle(s) }
                } catch (e: RejectedExecutionException) {
                    try {
                        write(s.getOutputStream(), HttpResp.text(503, "busy"))
                    } catch (_: Exception) {
                    }
                    closeQuietly(s)
                }
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val resp = try {
                when (val r = read(input, socket.inetAddress?.hostAddress ?: "")) {
                    is ReadResult.Ok -> handler(r.req)
                    is ReadResult.Err -> HttpResp.text(r.status, r.msg)
                    ReadResult.Closed -> null
                }
            } catch (e: Exception) {
                HttpResp.text(500, "internal error")
            }
            if (resp != null) write(out, resp)
        } catch (_: Exception) {
        } finally {
            closeQuietly(socket)
        }
    }

    private sealed class ReadResult {
        class Ok(val req: HttpReq) : ReadResult()
        class Err(val status: Int, val msg: String) : ReadResult()
        object Closed : ReadResult()
    }

    private fun read(input: InputStream, ip: String): ReadResult {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b == -1) return if (head.size() == 0) ReadResult.Closed else ReadResult.Err(400, "bad request")
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > MAX_HEADER) return ReadResult.Err(431, "header too large")
        }
        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        val first = lines.firstOrNull()?.split(' ') ?: return ReadResult.Err(400, "bad request")
        if (first.size < 3) return ReadResult.Err(400, "bad request")
        val method = first[0].uppercase()
        val target = first[1]
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim().lowercase()
            val v = line.substring(idx + 1).trim()
            headers[k] = headers[k]?.let { "$it, $v" } ?: v
        }
        if (headers.containsKey("transfer-encoding")) return ReadResult.Err(411, "length required")
        val len = headers["content-length"]?.let { it.toIntOrNull() ?: return ReadResult.Err(400, "bad content-length") } ?: 0
        if (len < 0) return ReadResult.Err(400, "bad content-length")
        if (len > MAX_BODY) return ReadResult.Err(413, "body too large")
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            if (n == -1) return ReadResult.Err(400, "incomplete body")
            off += n
        }
        return ReadResult.Ok(HttpReq(method, target, headers, body, ip))
    }

    private fun write(out: OutputStream, resp: HttpResp) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(resp.status).append(' ').append(reason(resp.status)).append("\r\n")
        if (resp.contentType != null) sb.append("Content-Type: ").append(resp.contentType).append("\r\n")
        sb.append("Content-Length: ").append(resp.body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        for ((k, v) in resp.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (resp.body.isNotEmpty()) out.write(resp.body)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 411 -> "Length Required"; 413 -> "Payload Too Large"
        429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"; 503 -> "Service Unavailable"
        else -> "Status"
    }

    private fun closeQuietly(s: Socket) {
        try { s.close() } catch (_: Exception) {}
    }
}
