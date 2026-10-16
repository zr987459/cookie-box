package com.cookiebox.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cookiebox.app.databinding.DialogAccountEditBinding
import com.cookiebox.app.databinding.DialogAccountsSheetBinding
import com.cookiebox.app.databinding.ItemAccountCredentialBinding
import com.cookiebox.app.engine.AccountVault
import com.cookiebox.app.engine.CookieStorageRepository
import com.cookiebox.app.model.AccountCredential
import com.cookiebox.app.model.SavedCookieRecord
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 账号管理：保存每个网站的多个账号密码，支持一键填入、绑定 Cookie 免密进入、CSV 导入导出。
 */
class AccountsBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogAccountsSheetBinding? = null
    private val b get() = _binding!!

    private lateinit var vault: AccountVault
    private lateinit var cookieRepo: CookieStorageRepository
    private val adapter = AccountsAdapter()

    /** 当前标签页网址，用于预填新增账号的网站 */
    var currentUrl: String = ""
    var onLaunchRecord: ((SavedCookieRecord) -> Unit)? = null
    var onOpenAndFill: ((AccountCredential, String) -> Unit)? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = requireContext().contentResolver.openInputStream(uri)!!.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val r = vault.importCsv(text)
            AlertDialog.Builder(requireContext())
                .setTitle("导入完成")
                .setMessage(
                    "新增 ${r.added} 个，更新 ${r.updated} 个，跳过 ${r.skipped} 条。\n\n" +
                            "导入的 CSV 文件含明文密码，建议导入后在文件管理里删除它。"
                )
                .setPositiveButton("好的", null)
                .show()
            refresh()
        } catch (e: Exception) {
            toast("导入失败：${e.message}")
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            requireContext().contentResolver.openOutputStream(uri)!!.use {
                it.write(("\uFEFF" + vault.exportCsv()).toByteArray(Charsets.UTF_8))
            }
            toast("已导出，文件含明文密码，请妥善保管")
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogAccountsSheetBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vault = AccountVault(requireContext())
        cookieRepo = CookieStorageRepository(requireContext())

        b.rvAccounts.layoutManager = LinearLayoutManager(requireContext())
        b.rvAccounts.adapter = adapter

        b.btnAddAccount.setOnClickListener { showEditDialog(null) }
        b.btnAccountsMore.setOnClickListener { showImportExportMenu(it) }

        b.etSearchAccounts.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refresh()
    }

    private fun refresh() {
        val list = vault.getAll()
        val cookies = cookieRepo.getAllRecords().associateBy { it.id }
        _binding ?: return
        b.tvAccountsSubtitle.text = "共 ${list.size} 个账号 · 密码加密保存在本机"
        b.tvEmptyAccounts.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.rvAccounts.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(list, cookies)
        adapter.filter(b.etSearchAccounts.text?.toString() ?: "")
    }

    // ------------------------------------------------------------- 导入导出

    private fun showImportExportMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "从 CSV 文件导入（Chrome / Edge / Bitwarden 等）")
        popup.menu.add(0, 2, 1, "同步 Cookie 储存库里的账号")
        popup.menu.add(0, 3, 2, "如何从 Chrome / 谷歌密码管理器导出？")
        popup.menu.add(0, 4, 3, "导出为 CSV（明文密码）")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> importLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                2 -> {
                    val r = vault.importFromCookieRecords(cookieRepo.getAllRecords())
                    toast(if (r.added > 0) "已同步 ${r.added} 个账号，可直接免密进入，密码可之后补充" else "Cookie 储存库里的账号都已同步过了")
                    refresh()
                }
                3 -> AlertDialog.Builder(requireContext())
                    .setTitle("导出旧密码")
                    .setMessage(
                        "谷歌密码管理器 / 安卓 Chrome：\n" +
                                "Chrome 右上角 ⋮ → 设置 → 密码管理器 → ⚙ 设置 → 导出密码，得到 CSV 文件。\n\n" +
                                "Edge：设置 → 个人资料 → 密码 → ⋯ → 导出密码。\n\n" +
                                "Firefox：设置 → 密码 → ⋯ → 导出。\n\n" +
                                "Bitwarden / 1Password / KeePass：选择导出为 CSV。\n\n" +
                                "然后回到这里点「从 CSV 文件导入」选中那个文件即可，同一网站同一账号会自动合并。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
                4 -> AlertDialog.Builder(requireContext())
                    .setTitle("导出账号密码")
                    .setMessage("导出的 CSV 文件包含明文密码，任何拿到文件的人都能看到。确定导出吗？")
                    .setPositiveButton("导出") { _, _ ->
                        val name = "CookieBox-账号-" + SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date()) + ".csv"
                        exportLauncher.launch(name)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            true
        }
        popup.show()
    }

    // ------------------------------------------------------------- 新增 / 编辑

    private fun showEditDialog(existing: AccountCredential?) {
        val eb = DialogAccountEditBinding.inflate(layoutInflater)
        val prefillSite = existing?.loginUrl?.ifBlank { existing.domain }
            ?: currentUrl.takeIf { it.startsWith("http") } ?: ""
        eb.etSite.setText(prefillSite)
        eb.etUsername.setText(existing?.username ?: "")
        eb.etPassword.setText(existing?.let { vault.passwordOf(it) } ?: "")
        eb.etNote.setText(existing?.note ?: "")

        var options: List<SavedCookieRecord?> = listOf(null)
        fun loadCookieOptions() {
            val root = AccountVault.rootOf(eb.etSite.text?.toString() ?: "")
            val recs = if (root.isEmpty()) emptyList() else cookieRepo.getRecordsByDomain(root)
            options = listOf<SavedCookieRecord?>(null) + recs
            val labels = options.map { r ->
                r?.let { "${it.title.ifBlank { "未命名" }}（${it.count} 项 · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it.timestamp))}）" }
                    ?: "不绑定"
            }
            eb.spLinkCookie.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
            val idx = options.indexOfFirst { it?.id == existing?.linkedCookieId }.coerceAtLeast(0)
            eb.spLinkCookie.setSelection(idx)
        }
        loadCookieOptions()
        eb.etSite.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) loadCookieOptions() }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) "新增账号" else "编辑账号")
            .setView(eb.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val site = eb.etSite.text?.toString()?.trim().orEmpty()
                val user = eb.etUsername.text?.toString()?.trim().orEmpty()
                val pass = eb.etPassword.text?.toString().orEmpty()
                val root = AccountVault.rootOf(site)
                if (root.isEmpty()) { eb.tilSite.error = "请填写网站"; return@setOnClickListener }
                if (user.isEmpty() && pass.isEmpty()) { toast("账号和密码至少填一个"); return@setOnClickListener }
                val linked = options.getOrNull(eb.spLinkCookie.selectedItemPosition)?.id
                val dup = vault.find(root, user)
                if (dup != null && dup.id != existing?.id) {
                    toast("$root 已经有账号 $user 了"); return@setOnClickListener
                }
                val base = existing ?: AccountCredential(
                    id = UUID.randomUUID().toString(), domain = root, username = user
                )
                vault.save(base.copy(
                    domain = root,
                    loginUrl = if (site.startsWith("http")) site else "https://$site",
                    username = user,
                    note = eb.etNote.text?.toString()?.trim().orEmpty(),
                    linkedCookieId = linked,
                    updatedAt = System.currentTimeMillis()
                ))
                // 密码通过 upsert 加密写入（留空则清空）
                val saved = vault.find(root, user)!!
                if (pass.isNotEmpty()) vault.upsert(saved.loginUrl, user, pass)
                else vault.save(saved.copy(encryptedPassword = ""))
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------- 列表

    private inner class AccountsAdapter : RecyclerView.Adapter<AccountsAdapter.VH>() {
        private var all: List<AccountCredential> = emptyList()
        private var shown: List<AccountCredential> = emptyList()
        private var cookies: Map<String, SavedCookieRecord> = emptyMap()
        private val revealed = HashSet<String>()

        fun submit(list: List<AccountCredential>, cookieMap: Map<String, SavedCookieRecord>) {
            all = list; cookies = cookieMap
        }

        fun filter(q: String) {
            val k = q.trim().lowercase()
            shown = if (k.isEmpty()) all else all.filter {
                it.domain.lowercase().contains(k) || it.username.lowercase().contains(k) ||
                        it.note.lowercase().contains(k) || it.loginUrl.lowercase().contains(k)
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemAccountCredentialBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: VH, position: Int) = h.bind(shown[position])

        inner class VH(val v: ItemAccountCredentialBinding) : RecyclerView.ViewHolder(v.root) {
            fun bind(a: AccountCredential) {
                v.tvAccDomain.text = a.domain
                v.tvAccUsername.text = a.username.ifBlank { "（未填账号）" }
                v.tvAccNote.visibility = if (a.note.isBlank()) View.GONE else View.VISIBLE
                v.tvAccNote.text = a.note

                val cookie = a.linkedCookieId?.let { cookies[it] }
                v.tvAccLinked.visibility = if (cookie != null) View.VISIBLE else View.GONE
                v.btnAccLaunch.isEnabled = cookie != null
                v.btnAccLaunch.alpha = if (cookie != null) 1f else 0.4f
                v.btnAccLaunch.setOnClickListener {
                    if (cookie != null) { dismiss(); onLaunchRecord?.invoke(cookie) }
                }

                val hasPass = a.encryptedPassword.isNotEmpty()
                val isShown = revealed.contains(a.id)
                v.tvAccPassword.text = when {
                    !hasPass -> "（未保存密码）"
                    isShown -> vault.passwordOf(a) ?: "（无法解密，请编辑后重新输入）"
                    else -> "••••••••"
                }
                v.btnAccToggle.visibility = if (hasPass) View.VISIBLE else View.GONE
                v.btnAccToggle.setOnClickListener {
                    if (isShown) revealed.remove(a.id) else revealed.add(a.id)
                    notifyItemChanged(adapterPosition)
                }

                v.btnAccFill.setOnClickListener {
                    val pass = vault.passwordOf(a) ?: ""
                    dismiss()
                    onOpenAndFill?.invoke(a, pass)
                }
                v.btnAccCopyUser.setOnClickListener { copy(a.username, false) }
                v.btnAccCopyPass.isEnabled = hasPass
                v.btnAccCopyPass.setOnClickListener {
                    val p = vault.passwordOf(a)
                    if (p == null) toast("无法解密，请编辑后重新输入密码") else copy(p, true)
                }

                v.btnAccMore.setOnClickListener { anchor ->
                    val pm = PopupMenu(requireContext(), anchor)
                    pm.menu.add(0, 1, 0, "编辑")
                    pm.menu.add(0, 2, 1, "删除")
                    pm.setOnMenuItemClickListener {
                        when (it.itemId) {
                            1 -> showEditDialog(a)
                            2 -> AlertDialog.Builder(requireContext())
                                .setTitle("删除账号")
                                .setMessage("删除 ${a.domain} 的 ${a.username}？已绑定的 Cookie 不受影响。")
                                .setPositiveButton("删除") { _, _ -> vault.delete(a.id); refresh() }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                        true
                    }
                    pm.show()
                }
            }
        }
    }

    private fun copy(text: String, sensitive: Boolean) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(if (sensitive) "密码" else "账号", text)
        if (sensitive) {
            // Android 13+ 不在剪贴板预览里显示密码
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
        toast(if (sensitive) "已复制密码" else "已复制账号")
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = AccountsBottomSheetDialog()
    }
}
