package com.cookiebox.app.mcp

import android.app.Activity
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton

/**
 * 站点授权（ADR-0004 §5.5）：授权单位是「身份（profileName）× 域名」。
 * 三种粒度：逐个域名 / 该身份全部站点 / 全部标签。候选域名 = 访问过的域名 + 当前标签 + 手动添加。
 */
object McpGrantsDialog {

    fun show(act: Activity) {
        val ctx = act
        val grants = McpPrefs.grants(ctx)
        val tabs = try { McpSessions.snapshotOnMain(ctx) } catch (e: Exception) { emptyList() }
        val visited = McpPrefs.visitedHosts(ctx)

        // 身份列表与显示名
        val profiles = linkedSetOf<String>()
        tabs.forEach { profiles.add(it.profileName) }
        profiles.addAll(grants.profiles.keys)
        profiles.addAll(visited.keys)
        if (profiles.isEmpty()) profiles.add(McpPrefs.DEFAULT_PROFILE)

        fun label(p: String): String {
            val t = tabs.filter { it.profileName == p }
            if (t.isEmpty() && p == McpPrefs.DEFAULT_PROFILE) return "默认身份（普通标签）"
            if (t.isNotEmpty() && !t.first().isClone) return "默认身份（普通标签，${t.size} 个）"
            val named = t.firstOrNull { it.customName.isNotBlank() }?.customName
            val tail = p.removePrefix("Profile_Clone_")
            return when {
                named != null -> "分身「$named」"
                t.isNotEmpty() -> "分身 $tail（${t.first().title.take(16)}）"
                else -> "分身 $tail（标签已关闭）"
            }
        }

        fun candidates(p: String): List<String> {
            val set = linkedSetOf<String>()
            grants.profiles[p]?.domains?.keys?.let { set.addAll(it) }
            tabs.filter { it.profileName == p }.forEach { t -> McpPrefs.registrableDomain(t.url)?.let { set.add(it) } }
            visited[p]?.let { set.addAll(it) }
            grants.profiles[p]?.manual?.let { set.addAll(it) }
            return set.toList()
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(McpSettingsDialog.dp(ctx, 20), McpSettingsDialog.dp(ctx, 8), McpSettingsDialog.dp(ctx, 20), McpSettingsDialog.dp(ctx, 8))
        }

        lateinit var render: () -> Unit
        render = {
            container.removeAllViews()
            container.addView(TextView(ctx).apply {
                text = "AI 只能看到并访问你勾选的「身份 × 域名」（含子域）。同一身份的所有标签共用登录状态。"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            val allCb = CheckBox(ctx).apply {
                text = "授权全部标签的全部站点（含以后访问的新站点）"
                isChecked = grants.all
                setOnCheckedChangeListener { _, on -> grants.all = on; render() }
            }
            container.addView(allCb)

            for (p in profiles) {
                val pg = grants.profile(p)
                container.addView(TextView(ctx).apply {
                    text = label(p)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(0, McpSettingsDialog.dp(ctx, 14), 0, 0)
                })
                val pAll = CheckBox(ctx).apply {
                    text = "该身份全部站点（含以后访问的新站点）"
                    isChecked = grants.all || pg.all
                    isEnabled = !grants.all
                    setOnCheckedChangeListener { _, on -> pg.all = on; render() }
                }
                container.addView(pAll)
                val cands = candidates(p)
                if (cands.isEmpty()) {
                    container.addView(TextView(ctx).apply {
                        text = "还没有候选域名：开启 AI 访问后用该身份打开网站，或手动添加。"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    })
                }
                for (d in cands) {
                    container.addView(CheckBox(ctx).apply {
                        text = d
                        val coveredByAll = grants.all || pg.all
                        isChecked = coveredByAll || pg.domains.containsKey(d)
                        isEnabled = !coveredByAll
                        setOnCheckedChangeListener { _, on ->
                            if (on) { pg.domains[d] = pg.domains[d] ?: false } else { pg.domains.remove(d) }
                        }
                    })
                }
                container.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = "＋ 手动添加域名"
                    isAllCaps = false
                    setOnClickListener {
                        val input = EditText(ctx).apply {
                            hint = "例如 bilibili.com"
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        }
                        AlertDialog.Builder(ctx)
                            .setTitle("添加域名")
                            .setView(LinearLayout(ctx).apply {
                                setPadding(McpSettingsDialog.dp(ctx, 24), 0, McpSettingsDialog.dp(ctx, 24), 0)
                                addView(input, LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                            })
                            .setPositiveButton("添加并授权") { _, _ ->
                                val d = McpPrefs.normalizeDomainInput(input.text.toString())
                                if (d == null) {
                                    McpSettingsDialog.toast(ctx, "域名无效")
                                } else {
                                    pg.manual.add(d)
                                    pg.domains[d] = pg.domains[d] ?: false
                                    render()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                })
            }
        }
        render()

        AlertDialog.Builder(ctx)
            .setTitle("站点授权")
            .setView(ScrollView(ctx).apply { addView(container) })
            .setPositiveButton("保存") { _, _ ->
                // 不保存空的身份条目
                grants.profiles.entries.removeAll { (_, g) -> !g.all && g.domains.isEmpty() && g.manual.isEmpty() }
                McpPrefs.saveGrants(ctx, grants)
                McpSettingsDialog.toast(ctx, "授权已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
