package com.cookiebox.app.ui

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
    private val binding get() = _binding!!

    var onTabSelected: ((String) -> Unit)? = null
    var onTabClosed: ((String) -> Unit)? = null
    var onNewTabRequested: (() -> Unit)? = null

    private var tabList: List<TabItem> = emptyList()
    private var activeId: String = ""
    private lateinit var adapter: TabsAdapter

    fun setTabsData(tabs: List<TabItem>, currentActiveId: String) {
        this.tabList = tabs
        this.activeId = currentActiveId
        if (::adapter.isInitialized) {
            adapter.submitList(tabList, activeId)
            updateCountText()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTabsSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TabsAdapter(
            onTabClick = { tab ->
                onTabSelected?.invoke(tab.id)
                dismiss()
            },
            onCloseClick = { tab ->
                onTabClosed?.invoke(tab.id)
            }
        )

        binding.rvTabs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTabs.adapter = adapter
        adapter.submitList(tabList, activeId)
        updateCountText()

        binding.btnNewTab.setOnClickListener {
            onNewTabRequested?.invoke()
            dismiss()
        }
    }

    private fun updateCountText() {
        binding.tvTabCount.text = "当前打开 ${tabList.size} 个窗口"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): TabsBottomSheetDialog = TabsBottomSheetDialog()
    }
}
