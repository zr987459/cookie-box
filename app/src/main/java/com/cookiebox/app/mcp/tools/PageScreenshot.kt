package com.cookiebox.app.mcp.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import android.view.View
import com.cookiebox.app.mcp.McpGrants
import com.cookiebox.app.mcp.McpTabs
import com.cookiebox.app.mcp.McpTimeoutException
import com.cookiebox.app.mcp.McpTool
import com.cookiebox.app.mcp.McpToolContext
import com.cookiebox.app.mcp.McpToolException
import com.cookiebox.app.mcp.McpToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** screenshot：标签当前可视区域截图（JPEG / PNG，按 max_width 缩放） */
class PageScreenshot(private val context: Context) : McpTool {

    override val name = "screenshot"
    override val description =
        "Take a screenshot of the visible area of the tab's current page (authorized domain only). Returns an image " +
            "(JPEG by default, scaled to max_width, default 1080 px). A tab that is not on screen is rendered at the " +
            "browser area's size; if it was never displayed the image may be blank, in which case use get_page_text."
    override val inputSchema: JSONObject = ToolSupport.schema(
        JSONObject()
            .put("session_id", ToolSupport.str("Tab id from list_sessions"))
            .put("format", ToolSupport.str("jpeg (default) or png", listOf("jpeg", "png")))
            .put("quality", JSONObject().put("type", "integer").put("description", "JPEG quality 30–95 (default 70)"))
            .put("max_width", JSONObject().put("type", "integer").put("description", "Max width in px, 240–2000 (default 1080)")),
        listOf("session_id")
    )
    override val annotations: JSONObject = JSONObject().put("readOnlyHint", true).put("openWorldHint", true)

    override fun call(arguments: JSONObject, context: McpToolContext): McpToolResult {
        val session = ToolSupport.resolveSession(this.context, ToolSupport.requireString(arguments, "session_id"))
        context.logLabel = PageSupport.label(session)
        val png = (ToolSupport.optString(arguments, "format") ?: "jpeg").lowercase() == "png"
        val quality = arguments.optInt("quality", 70).coerceIn(30, 95)
        val maxWidth = arguments.optInt("max_width", 1080).coerceIn(240, 2000)

        return PageSupport.withTabLock(session.id) {
            PageSupport.ensureLoaded(session, PageSupport.DEFAULT_TIMEOUT_MS)
            val page = PageSupport.currentPage(this.context, session, name, context)
            val shot = try {
                McpTabs.onMain(15_000L) { access ->
                    val wv = access.tab(session.id)?.webView ?: throw McpToolException("标签页面未加载")
                    // 截图前再核一次当前地址，避免检查后页面已跳到未授权站点
                    val nowHost = McpGrants.hostOf(wv.url ?: "")
                    if (nowHost == null || !McpGrants.isAuthorized(this.context, session.profileName, nowHost)) {
                        throw McpToolException("页面已跳转到未授权站点（$nowHost）")
                    }
                    var w = wv.width
                    var h = wv.height
                    if (w <= 0 || h <= 0) {
                        // 从未显示过的后台标签：按屏幕宽、约 80% 屏幕高排版一次再画
                        val dm = this.context.resources.displayMetrics
                        w = dm.widthPixels
                        h = (dm.heightPixels * 0.8f).toInt()
                        wv.measure(
                            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                        )
                        wv.layout(0, 0, w, h)
                    }
                    val scale = if (w > maxWidth) maxWidth.toFloat() / w else 1f
                    val bw = (w * scale).toInt().coerceAtLeast(1)
                    val bh = (h * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    canvas.scale(scale, scale)
                    // WebView 内容按滚动位置绘制，平移回可视区域
                    canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
                    wv.draw(canvas)
                    bmp
                }
            } catch (e: McpTimeoutException) {
                throw McpToolException("截图超时（主线程忙）")
            }
            val width = shot.width
            val height = shot.height
            val bos = ByteArrayOutputStream()
            try {
                shot.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, bos)
            } finally {
                shot.recycle()
            }
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            val info = JSONObject().put("width", width).put("height", height).put("url", page.url).put("title", page.title)
                .put("format", if (png) "png" else "jpeg").put("bytes", bos.size()).put("note", PageSupport.UNTRUSTED_NOTE)
            val content = JSONArray()
                .put(JSONObject().put("type", "image").put("data", b64).put("mimeType", if (png) "image/png" else "image/jpeg"))
                .put(JSONObject().put("type", "text").put("text", info.toString()))
            McpToolResult(content)
        }
    }
}
