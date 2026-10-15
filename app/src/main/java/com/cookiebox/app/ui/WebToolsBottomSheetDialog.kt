package com.cookiebox.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.cookiebox.app.databinding.DialogWebToolsSheetBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class WebToolsBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogWebToolsSheetBinding? = null
    private val binding get() = _binding

    var currentZoom: Int = 100
    var onZoomChanged: ((Int) -> Unit)? = null
    var onTranslateInPage: (() -> Unit)? = null
    var onTranslateProxy: (() -> Unit)? = null
    var onRestoreTranslate: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = DialogWebToolsSheetBinding.inflate(inflater, container, false)
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding?.let { b ->
            updateZoomText(currentZoom)

            // 翻译按钮监听
            b.btnTranslateInPage.setOnClickListener {
                dismiss()
                onTranslateInPage?.invoke()
            }

            b.btnTranslateProxy.setOnClickListener {
                dismiss()
                onTranslateProxy?.invoke()
            }

            b.btnRestoreTranslate.setOnClickListener {
                dismiss()
                onRestoreTranslate?.invoke()
            }

            // 缩放步进调节
            b.btnZoomIn.setOnClickListener {
                val newZoom = (currentZoom + 10).coerceAtMost(250)
                setZoom(newZoom)
            }

            b.btnZoomOut.setOnClickListener {
                val newZoom = (currentZoom - 10).coerceAtLeast(50)
                setZoom(newZoom)
            }

            b.btnZoomReset.setOnClickListener {
                setZoom(100)
            }

            // 比例胶囊快捷切换
            b.chipZoom80.setOnClickListener { setZoom(80) }
            b.chipZoom100.setOnClickListener { setZoom(100) }
            b.chipZoom125.setOnClickListener { setZoom(125) }
            b.chipZoom150.setOnClickListener { setZoom(150) }
            b.chipZoom200.setOnClickListener { setZoom(200) }
        }
    }

    private fun setZoom(zoom: Int) {
        currentZoom = zoom
        updateZoomText(zoom)
        onZoomChanged?.invoke(zoom)
    }

    private fun updateZoomText(zoom: Int) {
        binding?.tvZoomPercentage?.text = "$zoom%"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(zoom: Int): WebToolsBottomSheetDialog {
            val dialog = WebToolsBottomSheetDialog()
            dialog.currentZoom = zoom
            return dialog
        }
    }
}
