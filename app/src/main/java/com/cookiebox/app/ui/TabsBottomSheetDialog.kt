package com.cookiebox.app.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    var onCloseAllRequested: (() -> Unit)? = null

    private var tabList: List<TabItem> = emptyList()
    private var activeId: String = ""
    private var adapter: TabsAdapter? = null

    fun setTabsData(tabs: List<TabItem>, currentActiveId: String) {
        this.tabList = tabs
        this.activeId = currentActiveId
        val b = _binding ?: return
        adapter?.submitList(tabList, activeId)
        updateCount(b)
    }

    private fun updateCount(b: DialogTabsSheetBinding) {
        val sites = adapter?.siteCount ?: 0
        b.tvTabCount.text = if (sites > 0) "$sites 个网站 · ${tabList.size} 个标签" else "${tabList.size} 个标签"
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
            updateCount(b)

            // 行左滑关闭（组头不响应）
            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
                override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                    if (vh is TabsAdapter.TabVH) super.getSwipeDirs(rv, vh) else 0
                override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                    val pos = vh.adapterPosition
                    val tab = newAdapter.tabAt(pos)
                    // 先复位这一行：关闭可能弹确认框被取消，关闭成功后列表会整体刷新
                    newAdapter.notifyItemChanged(pos)
                    if (tab != null) onTabClosed?.invoke(tab.id)
                }
            }).attachToRecyclerView(b.rvTabs)

            b.btnNewTab.setOnClickListener {
                dismiss()
                onNewTabRequested?.invoke(false)
            }
            // 长按 +：新账号标签（全新的空身份）
            b.btnNewTab.setOnLongClickListener {
                dismiss()
                onNewTabRequested?.invoke(true)
                true
            }
            b.btnCloseAll.setOnClickListener { onCloseAllRequested?.invoke() }
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
