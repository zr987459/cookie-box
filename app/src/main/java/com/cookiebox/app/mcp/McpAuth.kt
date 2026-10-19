package com.cookiebox.app.mcp

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 请求准入（ADR-0004 §5.3）：
 * - Host 必须是 127.0.0.1 / localhost / [::1] 加本服务端口；局域网开启时外加本机网卡地址。防 DNS rebinding。
 * - 无 Origin 放行；有 Origin 时只有在白名单里才放行。
 * - Bearer Token 常量时间比较；同一来源 60 s 内失败 10 次，冷却 60 s。
 */
class McpAuth(
    private val port: Int,
    private val lanEnabled: Boolean,
    private val tokenProvider: () -> String?,
    private val originAllowlist: () -> Set<String>
) {

    enum class OriginResult { NONE, ALLOWED, DENIED }

    private class Failures(var windowStart: Long, var count: Int, var blockedUntil: Long)

    private val failures = ConcurrentHashMap<String, Failures>()

    companion object {
        private const val FAIL_WINDOW_MS = 60_000L
        private const val FAIL_LIMIT = 10
        private const val COOLDOWN_MS = 60_000L
        private const val MAX_TRACKED = 256
    }

    // ---------- Host ----------

    fun hostAllowed(hostHeader: String?): Boolean {
        val host = hostHeader?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (host.isEmpty()) return false
        val (name, portPart) = splitHostPort(host) ?: return false
        if (portPart != port) return false
        if (name == "127.0.0.1" || name == "localhost" || name == "::1") return true
        if (!lanEnabled) return false
        return localAddresses().contains(name)
    }

    /** "a.b:1", "[::1]:1" → (host, port)；缺端口返回 null（客户端必然带端口，因为不是 80） */
    private fun splitHostPort(host: String): Pair<String, Int>? {
        return if (host.startsWith("[")) {
            val end = host.indexOf(']')
            if (end < 0) return null
            val name = host.substring(1, end)
            val rest = host.substring(end + 1)
            if (!rest.startsWith(":")) return null
            val p = rest.substring(1).toIntOrNull() ?: return null
            name to p
        } else {
            val idx = host.lastIndexOf(':')
            if (idx <= 0) return null
            val p = host.substring(idx + 1).toIntOrNull() ?: return null
            host.substring(0, idx) to p
        }
    }

    private fun localAddresses(): Set<String> {
        val out = HashSet<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (nif in ifaces) {
                if (!nif.isUp) continue
                for (addr in nif.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    out.add(addressText(addr))
                }
            }
        } catch (e: Exception) {
            // 取不到网卡时只允许回环
        }
        return out
    }

    // ---------- Origin ----------

    fun checkOrigin(originHeader: String?): OriginResult {
        if (originHeader == null) return OriginResult.NONE
        val origin = McpPrefs.normalizeOrigin(originHeader)
        return if (origin.isNotEmpty() && originAllowlist().contains(origin)) OriginResult.ALLOWED
        else OriginResult.DENIED
    }

    // ---------- Token ----------

    fun isBlocked(remote: InetAddress): Boolean {
        val f = failures[addressText(remote)] ?: return false
        return System.currentTimeMillis() < f.blockedUntil
    }

    fun tokenValid(authorization: String?, remote: InetAddress): Boolean {
        val expected = tokenProvider()
        val presented = authorization?.trim()?.let {
            if (it.length > 7 && it.substring(0, 7).equals("Bearer ", ignoreCase = true)) it.substring(7).trim() else null
        }
        val ok = expected != null && presented != null &&
            MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))
        if (!ok) recordFailure(remote) else failures.remove(addressText(remote))
        return ok
    }

    private fun recordFailure(remote: InetAddress) {
        val now = System.currentTimeMillis()
        if (failures.size > MAX_TRACKED) failures.clear()
        val f = failures.getOrPut(addressText(remote)) { Failures(now, 0, 0) }
        synchronized(f) {
            if (now - f.windowStart > FAIL_WINDOW_MS) {
                f.windowStart = now
                f.count = 0
            }
            f.count++
            if (f.count >= FAIL_LIMIT) {
                f.blockedUntil = now + COOLDOWN_MS
                f.count = 0
                f.windowStart = now
            }
        }
    }

    private fun addressText(addr: InetAddress): String {
        val s = addr.hostAddress ?: return ""
        return (if (addr is Inet6Address) s.substringBefore('%') else s).lowercase(Locale.ROOT)
    }
}
