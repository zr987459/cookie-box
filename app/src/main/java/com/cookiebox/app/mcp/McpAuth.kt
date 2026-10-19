package com.cookiebox.app.mcp

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Host / Origin / Token 校验与失败限流（ADR-0004 §5.3）。
 * 不记录任何 Token 或请求头的值。
 */
class McpAuth(private val ctx: Context, private val port: Int, private val lan: Boolean) {

    enum class OriginResult { NONE, ALLOWED, DENIED }

    private val failures = ConcurrentHashMap<String, MutableList<Long>>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    fun hostOk(hostHeader: String?): Boolean {
        val host = hostHeader?.trim()?.lowercase() ?: return false
        if (host.isEmpty()) return false
        val allowed = mutableSetOf("127.0.0.1:$port", "localhost:$port", "[::1]:$port")
        if (lan) allowed += lanAddresses().map { "$it:$port" }
        return host in allowed
    }

    fun checkOrigin(origin: String?): OriginResult {
        if (origin == null) return OriginResult.NONE
        val o = origin.trim().trimEnd('/')
        return if (McpPrefs.originAllowlist(ctx).any { it.equals(o, ignoreCase = true) }) OriginResult.ALLOWED
        else OriginResult.DENIED
    }

    fun isCoolingDown(ip: String): Boolean {
        val until = cooldownUntil[ip] ?: return false
        if (System.currentTimeMillis() < until) return true
        cooldownUntil.remove(ip)
        return false
    }

    /** 校验 Authorization: Bearer <token>；失败计数，1 分钟内 10 次进入 60 秒冷却。 */
    fun tokenOk(authorization: String?, ip: String): Boolean {
        val token = authorization?.trim()?.let {
            if (it.length > 7 && it.substring(0, 7).equals("Bearer ", ignoreCase = true)) it.substring(7).trim() else null
        }
        if (McpTokenStore.matches(ctx, token)) {
            failures.remove(ip)
            return true
        }
        val now = System.currentTimeMillis()
        val list = failures.getOrPut(ip) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > 60_000L }
            list.add(now)
            if (list.size >= 10) {
                cooldownUntil[ip] = now + 60_000L
                list.clear()
            }
        }
        return false
    }

    companion object {
        fun lanAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
