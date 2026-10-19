package com.cookiebox.app.mcp

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class McpTimeoutException(message: String) : Exception(message)

/**
 * 把工具里的 WebView / CookieManager / ProfileStore 操作切到主线程执行，带超时（ADR-0004 §4.2）。
 * 只能从非主线程（HTTP 工作线程）调用；在主线程上调用会直接执行 block。
 */
object MainThreadRunner {

    const val DEFAULT_TIMEOUT_MS = 10_000L

    private val handler = Handler(Looper.getMainLooper())

    fun isMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** 在主线程同步执行 block 并返回结果 */
    fun <T> run(timeoutMs: Long = DEFAULT_TIMEOUT_MS, block: () -> T): T {
        if (isMain()) return block()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Any?>()
        val error = AtomicReference<Throwable?>()
        handler.post {
            try {
                result.set(block())
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw McpTimeoutException("主线程操作超时")
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    /**
     * 在主线程启动一个异步操作（如 evaluateJavascript），等待它通过 done 回调交回结果。
     * start 在主线程执行；done 可以在任意线程调用，只认第一次。
     */
    fun <T> await(timeoutMs: Long = DEFAULT_TIMEOUT_MS, start: (done: (T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Any?>()
        val error = AtomicReference<Throwable?>()
        val done: (T) -> Unit = { v ->
            if (latch.count > 0) {
                result.set(v)
                latch.countDown()
            }
        }
        val task = Runnable {
            try {
                start(done)
            } catch (t: Throwable) {
                error.set(t)
                latch.countDown()
            }
        }
        if (isMain()) task.run() else handler.post(task)
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw McpTimeoutException("主线程操作超时")
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }
}
