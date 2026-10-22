package com.cookiebox.app.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.cookiebox.app.databinding.DialogTabsSheetBinding
import com.cookiebox.app.model.TabItem
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class TabsBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogTabsSheetBinding? = null
    private val binding get() = _binding

    var onTabSelected: ((String) -> Unit)? = null
    var onTabClosed: ((String) -> Unit)? = null
    var onTabRenameRequested: ((String) -> Unit)? = null
    var onNewTabRequested: ((isIsolated: Boolean) -> Unit)? = null
    var onDismissListener: (() -> Unit)? = null
    var identityOf: ((TabItem) -> Pair<String, Int>)? = null

    private var tabList: List<TabItem> = emptyList()
    private var activeId: String = ""
    private var adapter: TabsAdapter? = null

    fun setTabsData(tabs: List<TabItem>, currentActiveId: String) {
        this.tabList = tabs
        this.activeId = currentActiveId
        val b = _binding ?: return
        adapter?.submitList(tabList, activeId)
        b.tvTabCount.text = "当前打开 ${tabList.size} 个窗口"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = DialogTabsSheetBinding.inflate(inflater, container, false)
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val newAdapter = TabsAdapter(
            onTabClick = { tab ->
                onTabSelected?.invoke(tab.id)
                dismiss()
            },
            onCloseClick = { tab ->
                onTabClosed?.invoke(tab.id)
            },
            onRenameClick = { tab ->
                onTabRenameRequested?.invoke(tab.id)
            },
            identityOf = { tab -> identityOf?.invoke(tab) ?: Pair("", 0) }
        )
        adapter = newAdapter

        binding?.let { b ->
            b.rvTabs.layoutManager = LinearLayoutManager(requireContext())
            b.rvTabs.adapter = newAdapter
            newAdapter.submitList(tabList, activeId)
            b.tvTabCount.text = "当前打开 ${tabList.size} 个窗口"

            b.btnNewTab.setOnClickListener {
                dismiss()
                onNewTabRequested?.invoke(false)
            }

            // T-030：只保留一个「新标签」入口；新标签打开网站时再按网站决定用哪个账号
            b.btnNewIsolatedTab.visibility = View.GONE
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.invoke()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        adapter = null
    }

    companion object {
        fun newInstance(): TabsBottomSheetDialog = TabsBottomSheetDialog()
    }
}
