package com.cookiebox.app.mcp

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 把 WebView / CookieManager / ProfileStore 操作切到主线程执行，带超时（ADR-0004 §4.2）。 */
object MainThreadRunner {

    private val main = Handler(Looper.getMainLooper())

    fun <T> run(timeoutMs: Long = 10_000, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Any? = null
        var error: Throwable? = null
        main.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw ToolError("主线程繁忙，操作超时")
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** 在主线程执行脚本并等待回调值（evaluateJavascript 返回的 JSON 字面量）。工作线程调用。 */
    fun evalJs(webView: WebView, script: String, timeoutMs: Long = 10_000): String? {
        val latch = CountDownLatch(1)
        var value: String? = null
        var error: Throwable? = null
        main.post {
            try {
                webView.evaluateJavascript(script) { v ->
                    value = v
                    latch.countDown()
                }
            } catch (t: Throwable) {
                error = t
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw ToolError("页面脚本执行超时")
        error?.let { throw ToolError("页面脚本无法执行：${it.javaClass.simpleName}") }
        return value
    }
}
