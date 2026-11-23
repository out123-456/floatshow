package com.qiqi.floatshow.text

import android.content.Context
import android.graphics.Typeface
import android.net.Uri

/**
 * 自定义字体的唯一加载入口.
 *
 * 主界面(Compose)与悬浮面板(原生 View)共用同一份 [Typeface]:
 * Typeface 是系统级对象, 两边复用同一个实例不会有第二次解析开销;
 * Compose 侧用 FontFamily(typeface) 包一层, 原生侧直接 setTypeface.
 *
 * 结果按 uri 缓存: 同一个 uri 只真正解析一次, 失败也记下来供界面标红.
 */
object FontLoader {

    private var cachedUri: String? = null
    private var cachedTypeface: Typeface? = null
    private var cachedFailed = false
    private var hasLoaded = false

    /** 可能做 IO, 不要在 UI 线程直接调用 */
    @Synchronized
    fun load(context: Context, uri: String?): Typeface? {
        if (hasLoaded && uri == cachedUri) return cachedTypeface
        hasLoaded = true
        cachedUri = uri
        cachedTypeface = null
        cachedFailed = false
        if (uri.isNullOrBlank()) return null

        cachedTypeface = runCatching {
            // 用文件描述符建 Typeface: 支持 ttf/otf, 也不要求文件带后缀
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                Typeface.Builder(pfd.fileDescriptor).build()
            }
        }.getOrNull()
        if (cachedTypeface == null) cachedFailed = true
        return cachedTypeface
    }

    /** 最近一次 [load] 是否失败(uri 为空不算失败) */
    @Synchronized
    fun failed(): Boolean = cachedFailed
}
