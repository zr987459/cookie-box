package com.cookiebox.app.mcp

import java.util.concurrent.ConcurrentHashMap

/**
 * 页面加载等待（T-029）。
 *
 * MainActivity 只回调 onPageStarted / onPageFinished（主线程）；这里按标签记一个导航代数：
 * started 每次开始导航加一，finished 在完成时追上 started。判定「加载完成」需要同时满足：
 * - 触发导航后至少开始过一次新导航（给 3 s 宽限，同页锚点等不触发 onPageStarted 的情况照样往下判）；
 * - WebView.progress == 100 且 document.readyState == "complete"；
 * - finished 已追上 started，或上面两条已连续稳定 4 次（onPageFinished 偶尔不来，例如加载被中断）；
 * - 连续两次轮询都满足（中间有重定向时会重新开始导航）。
 * 超时不抛错，返回 timedOut=true，由工具决定如何回报。
 */
object PageWaiter {

    private const val POLL_MS = 150L
    private const val START_GRACE_MS = 3_000L

    private class State {
        @Volatile var started = 0L
        @Volatile var finished = 0L
    }

    private val states = ConcurrentHashMap<String, State>()

    private fun state(tabId: String) = states.getOrPut(tabId) { State() }

    /** 主线程 */
    fun onStarted(tabId: String) {
        val s = state(tabId)
        s.started = s.started + 1
    }

    /** 主线程 */
    fun onFinished(tabId: String) {
        val s = state(tabId)
        s.finished = s.started
    }

    fun generation(tabId: String): Long = states[tabId]?.started ?: 0L

    class Result(val timedOut: Boolean, val navigated: Boolean, val status: PageStatus?, val elapsedMs: Long)

    /**
     * 从 HTTP 线程调用。sinceGeneration 为触发导航前的 [generation]；传 null 表示不要求发生新导航，只等当前页面就绪。
     */
    fun waitForLoad(tabId: String, sinceGeneration: Long?, timeoutMs: Long): Result {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        var okStreak = 0
        var readyStreak = 0
        var last: PageStatus? = null
        while (true) {
            val now = System.currentTimeMillis()
            if (now > deadline) {
                return Result(true, sinceGeneration != null && generation(tabId) > sinceGeneration, last, now - start)
            }
            val s = state(tabId)
            val startedNew = sinceGeneration == null || s.started > sinceGeneration
            if (!startedNew && now - start < START_GRACE_MS) {
                sleep()
                continue
            }
            val status = try {
                McpTabs.pageStatus(tabId)
            } catch (e: McpTimeoutException) {
                null
            } ?: throw McpSessionExpiredException("标签已关闭或页面未加载")
            last = status
            val readyState = if (status.progress >= 100) {
                try {
                    McpTabs.evalJs(tabId, "document.readyState", 3_000L)
                } catch (e: McpTimeoutException) {
                    null
                }
            } else null
            val ready = status.progress >= 100 && readyState == "\"complete\""
            readyStreak = if (ready) readyStreak + 1 else 0
            val settled = s.finished >= s.started || readyStreak >= 4
            okStreak = if (ready && settled) okStreak + 1 else 0
            if (okStreak >= 2) {
                return Result(false, sinceGeneration != null && s.started > sinceGeneration, status, System.currentTimeMillis() - start)
            }
            sleep()
        }
    }

    private fun sleep() {
        try {
            Thread.sleep(POLL_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw McpToolException("已中断")
        }
    }
}
