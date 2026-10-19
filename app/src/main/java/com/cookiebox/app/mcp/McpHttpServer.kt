package com.cookiebox.app.mcp

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 最小 HTTP/1.1 服务（ServerSocket，无第三方依赖），只为 MCP Streamable HTTP 单端点服务。
 * - 每个连接只处理一个请求，响应后关闭（Connection: close）。
 * - 请求头上限 16 KiB、请求体上限 1 MiB、单次读超时 15 s、单请求总时限 20 s、并发连接上限 4。
 * - Content-Length 按字节读取；不支持 chunked 请求体（返回 411）。
 * 日志只记状态码与长度，不记头值和正文。
 */
class McpHttpServer(
    private val bindAddress: InetAddress,
    private val port: Int,
    private val handler: (Request) -> Response
) {

    class Request(
        val method: String,
        val path: String,
        /** 键已转小写；同名头以逗号合并 */
        val headers: Map<String, String>,
        val body: ByteArray,
        val remote: InetAddress
    ) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    }

    class Response(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        val contentType: String? = null
    )

    companion object {
        private const val TAG = "CookieBoxMcp"
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 1024 * 1024
        private const val MAX_HEADERS = 100
        private const val READ_TIMEOUT_MS = 15_000
        private const val TOTAL_DEADLINE_MS = 20_000L
        private const val MAX_CONNECTIONS = 4

        fun reason(code: Int): String = when (code) {
            100 -> "Continue"
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Status"
        }
    }

    private class HttpError(val status: Int) : Exception()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = ThreadPoolExecutor(
        0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
        ThreadFactory { r -> Thread(r, "mcp-conn").apply { isDaemon = true } }
    )

    /** 绑定失败直接抛 IOException，由调用方展示「端口被占用」 */
    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(bindAddress, port), 16)
        } catch (e: IOException) {
            try { ss.close() } catch (ignored: IOException) {}
            throw e
        }
        serverSocket = ss
        acceptThread = Thread({ acceptLoop(ss) }, "mcp-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (ignored: IOException) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        pool.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: SocketException) {
                break // stop() 关闭了 ServerSocket
            } catch (e: IOException) {
                continue
            }
            try {
                pool.execute { serve(socket) }
            } catch (e: RejectedExecutionException) {
                // 并发已满：直接 503，不占用工作线程
                try {
                    socket.soTimeout = 2_000
                    writeResponse(socket.getOutputStream(), Response(503, mapOf("Retry-After" to "1")))
                } catch (ignored: IOException) {
                } finally {
                    closeQuietly(socket)
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val deadline = System.currentTimeMillis() + TOTAL_DEADLINE_MS
            val response = try {
                val req = readRequest(input, output, socket.inetAddress, deadline)
                try {
                    handler(req)
                } catch (e: Exception) {
                    Log.w(TAG, "handler failed: ${e.javaClass.simpleName}")
                    Response(500)
                }
            } catch (e: HttpError) {
                Response(e.status)
            } catch (e: java.net.SocketTimeoutException) {
                Response(408)
            }
            writeResponse(output, response)
        } catch (e: IOException) {
            // 客户端断开等，忽略
        } finally {
            closeQuietly(socket)
        }
    }

    private fun readRequest(input: InputStream, output: OutputStream, remote: InetAddress, deadline: Long): Request {
        val head = readHead(input, deadline)
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: throw HttpError(400)
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) throw HttpError(400)
        val method = requestLine[0].uppercase(Locale.ROOT)
        val target = requestLine[1]
        val path = target.substringBefore('?').substringBefore('#')

        val headers = LinkedHashMap<String, String>()
        var count = 0
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            if (++count > MAX_HEADERS) throw HttpError(431)
            val idx = line.indexOf(':')
            if (idx <= 0) throw HttpError(400)
            val name = line.substring(0, idx).trim().lowercase(Locale.ROOT)
            val value = line.substring(idx + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }

        if (headers.containsKey("transfer-encoding")) throw HttpError(411)
        val lengthHeader = headers["content-length"]
        val length = when {
            lengthHeader == null -> 0L
            else -> lengthHeader.toLongOrNull()?.takeIf { it >= 0 } ?: throw HttpError(400)
        }
        if (length > MAX_BODY_BYTES) throw HttpError(413)

        if (length > 0 && headers["expect"]?.lowercase(Locale.ROOT) == "100-continue") {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            if (System.currentTimeMillis() > deadline) throw HttpError(408)
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw HttpError(400)
            read += n
        }
        return Request(method, path, headers, body, remote)
    }

    /** 读到 \r\n\r\n 为止，超过 16 KiB 返回 431 */
    private fun readHead(input: InputStream, deadline: Long): String {
        val buf = ByteArrayOutputStream(1024)
        var state = 0 // 匹配 \r\n\r\n 的进度
        while (true) {
            if (System.currentTimeMillis() > deadline) throw HttpError(408)
            val b = input.read()
            if (b < 0) throw IOException("eof")
            buf.write(b)
            if (buf.size() > MAX_HEADER_BYTES) throw HttpError(431)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        val bytes = buf.toByteArray()
        return String(bytes, 0, bytes.size - 4, Charsets.ISO_8859_1)
    }

    private fun writeResponse(output: OutputStream, r: Response) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        if (r.contentType != null) sb.append("Content-Type: ").append(r.contentType).append("\r\n")
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        if (r.status == 401) sb.append("WWW-Authenticate: Bearer realm=\"cookiebox\"\r\n")
        for ((k, v) in r.headers) {
            // 防止头注入
            if (k.any { it == '\r' || it == '\n' } || v.any { it == '\r' || it == '\n' }) continue
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (r.body.isNotEmpty()) output.write(r.body)
        output.flush()
    }

    private fun closeQuietly(socket: Socket) {
        try { socket.close() } catch (ignored: IOException) {}
    }
}
