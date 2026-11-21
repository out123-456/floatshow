package com.qiqi.floatshow.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * 导入内容的校验与解码. 只负责"能不能用、有多大", 不负责界面.
 * 图片按原始像素读回来检查尺寸, 不缩放内存里的样本 —
 * 这里的上限只是拒绝明显超出处理能力的文件, 真正的解码策略在后面渲染面板时再决定.
 */
object PanelContentPicker {

    private val TEXT_EXTENSIONS = setOf("txt", "md", "log")
    private val FONT_EXTENSIONS = setOf("ttf", "otf")

    /** 文本文件的显示名(去掉路径与扩展名), 取不到时返回 null */
    fun textFileName(context: Context, uri: Uri): String? {
        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment
        return name?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun extensionOf(context: Context, uri: Uri): String? {
        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: return null
        return name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
    }

    fun isTextFile(context: Context, uri: Uri): Boolean = extensionOf(context, uri) in TEXT_EXTENSIONS

    fun isFontFile(context: Context, uri: Uri): Boolean = extensionOf(context, uri) in FONT_EXTENSIONS

    /** 读取文本. 空文件返回 null */
    fun readText(context: Context, uri: Uri): String? = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        }
        text?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 只解码尺寸判断文件是否可用, 不把整张图读进内存 */
    fun canReadImage(context: Context, uri: Uri): Boolean = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        options.outWidth > 0 && options.outHeight > 0
    }.getOrDefault(false)

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index < 0) return@use null
            cursor.getString(index)
        }
    }.getOrNull()
}
