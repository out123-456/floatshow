package com.qiqi.floatshow.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 面板内容区. 现在只有一句占位文字, 后续在这里接图片 / Markdown.
 * 内容区自己处理点击, 不参与拖动, 拖动只发生在工具栏与四周边缘.
 */
object PanelContentHost {

    fun build(context: Context): View {
        val text = TextView(context).apply {
            text = context.getString(com.qiqi.floatshow.R.string.panel_placeholder)
            gravity = Gravity.CENTER
            setPadding(pad(context, 12), pad(context, 12), pad(context, 12), pad(context, 12))
        }
        return FrameLayout(context).apply {
            addView(
                text,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
    }

    private fun pad(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
