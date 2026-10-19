package com.cookiebox.app.mcp

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cookiebox.app.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 站点授权（ADR-0004 §5.5）：按「身份 × 域名」授权，三种粒度——逐个勾选、授权该身份全部站点、授权全部标签。
 * 候选域名 = 服务开启期间访问过的站点（visited_hosts）+ 手动添加。界面全部用主题色，遵守 AAA 对比度门禁。
 */
class McpGrantsActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    companion object {
        private const val DEFAULT_PROFILE = "Profile_Default"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(16), 0)
        }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(attrColor(com.google.android.material.R.attr.colorOnSurface))
            contentDescription = "返回"
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(TextView(this).apply {
            text = "站点授权"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(32))
        }
        val scroll = ScrollView(this)
        scroll.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private class ProfileRow(val name: String, val label: String)

    private fun profiles(): List<ProfileRow> {
        val snapshot = try { McpTabs.snapshot() } catch (e: Exception) { emptyList() }
        val byProfile = LinkedHashMap<String, MutableList<SessionInfo>>()
        byProfile[DEFAULT_PROFILE] = mutableListOf()
        snapshot.forEach { byProfile.getOrPut(it.profileName) { mutableListOf() }.add(it) }
        McpGrants.knownProfiles(this).forEach { byProfile.getOrPut(it) { mutableListOf() } }
        return byProfile.map { (name, tabs) ->
            val names = tabs.map { it.customName.ifBlank { it.title } }.filter { it.isNotBlank() }.distinct().take(3)
            val label = when {
                name == DEFAULT_PROFILE -> "默认身份（普通标签）"
                tabs.isEmpty() -> "分身（标签已关闭）"
                names.isEmpty() -> "分身"
                else -> "分身：" + names.joinToString("、")
            }
            ProfileRow(name, label)
        }
    }

    private fun render() {
        content.removeAllViews()

        val allTabs = McpGrants.allTabs(this)
        content.addView(switchView("授权全部标签", allTabs) { checked ->
            if (checked) {
                AlertDialog.Builder(this)
                    .setTitle("授权全部标签？")
                    .setMessage("AI 将可以使用所有身份在访问过的全部站点上的登录状态，包括以后新访问的站点。")
                    .setPositiveButton("授权") { _, _ -> McpGrants.setAllTabs(this, true); render() }
                    .setNegativeButton("取消") { _, _ -> render() }
                    .setOnCancelListener { render() }
                    .show()
            } else {
                McpGrants.setAllTabs(this, false)
                render()
            }
        })
        content.addView(note(
            "候选站点来自 AI 访问开启期间打开过的网站，也可以手动添加。授权某个域名后，它的子域名同样可用。" +
                "未授权的身份不会出现在 AI 看到的标签列表里。"
        ))

        for (p in profiles()) {
            content.addView(sectionTitle(p.label))
            if (p.name != DEFAULT_PROFILE) content.addView(note(p.name))

            val profileAll = McpGrants.profileAll(this, p.name)
            val effectiveAll = allTabs || profileAll
            val sw = switchView("授权该身份全部站点", effectiveAll) { checked ->
                McpGrants.setProfileAll(this, p.name, checked)
                render()
            }
            sw.isEnabled = !allTabs
            content.addView(sw)

            val candidates = McpGrants.candidates(this, p.name)
            val checked = McpGrants.checkedDomains(this, p.name)
            val manual = McpGrants.manual(this, p.name).toSet()
            val shown = LinkedHashSet<String>().apply { addAll(candidates); addAll(checked) }
            if (shown.isEmpty()) {
                content.addView(note("还没有候选站点：开启 AI 访问后用这个身份打开网站，或手动添加。"))
            }
            for (domain in shown) {
                val cb = MaterialCheckBox(this).apply {
                    text = if (domain in manual) "$domain（手动）" else domain
                    textSize = 15f
                    setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
                    isChecked = effectiveAll || domain in checked
                    isEnabled = !effectiveAll
                    minHeight = dp(48)
                    setOnCheckedChangeListener { _, c -> McpGrants.setDomainChecked(this@McpGrantsActivity, p.name, domain, c) }
                    if (domain in manual) {
                        setOnLongClickListener {
                            AlertDialog.Builder(this@McpGrantsActivity)
                                .setTitle("移除 $domain？")
                                .setMessage("从手动列表中移除并取消授权。")
                                .setPositiveButton("移除") { _, _ -> McpGrants.removeManual(this@McpGrantsActivity, p.name, domain); render() }
                                .setNegativeButton("取消", null)
                                .show()
                            true
                        }
                    }
                }
                content.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            content.addView(outlinedButton("添加域名") { addDomainDialog(p) })
        }

        content.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
        content.addView(outlinedButton("清除全部授权") {
            AlertDialog.Builder(this)
                .setTitle("清除全部授权？")
                .setMessage("所有身份的授权都会取消，手动添加的域名也会清除。访问记录保留。")
                .setPositiveButton("清除") { _, _ -> McpGrants.clearAll(this); render() }
                .setNegativeButton("取消", null)
                .show()
        })
    }

    private fun addDomainDialog(p: ProfileRow) {
        val input = EditText(this).apply {
            hint = "例如 example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
            setHintTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("为「${p.label}」添加并授权域名")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val d = McpGrants.parseManualDomain(input.text.toString())
                if (d == null) {
                    Toast.makeText(this, "域名格式不正确", Toast.LENGTH_SHORT).show()
                } else {
                    McpGrants.addManual(this, p.name, d)
                    McpGrants.setDomainChecked(this, p.name, d, true)
                    render()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 视图工具 ----------

    private fun switchView(label: String, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch =
        MaterialSwitch(this).apply {
            text = label
            textSize = 16f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
            isChecked = checked
            minHeight = dp(52)
            setOnCheckedChangeListener { _, c -> onChange(c) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(attrColor(androidx.appcompat.R.attr.colorPrimary))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
            bottomMargin = dp(4)
        }
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun outlinedButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
