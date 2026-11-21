package com.qiqi.floatshow.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 全局设置的唯一读写入口.
 * 悬浮窗 Service 监听 [SharedPreferences] 的变化`,因此设置页改动会即时生效.
 */
class SettingsRepository(context: Context) {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── 准星 ──────────────────────────────────────────────
    var crosshairEnabled: Boolean
        get() = prefs.getBoolean(KEY_CROSSHAIR_ENABLED, false)
        set(v) = prefs.edit { putBoolean(KEY_CROSSHAIR_ENABLED, v) }

    var crosshairImageUri: String?
        get() = prefs.getString(KEY_CROSSHAIR_IMAGE, null)
        set(v) = prefs.edit { putString(KEY_CROSSHAIR_IMAGE, v) }

    /** 准星边长(px),支持亚像素(最小 [MIN_SIZE]) */
    var crosshairSize: Float
        get() = prefs.getFloat(KEY_CROSSHAIR_SIZE, DEFAULT_SIZE)
        set(v) = prefs.edit { putFloat(KEY_CROSSHAIR_SIZE, v) }

    var crosshairOffsetX: Int
        get() = prefs.getInt(KEY_CROSSHAIR_OFFSET_X, 0)
        set(v) = prefs.edit { putInt(KEY_CROSSHAIR_OFFSET_X, v) }

    var crosshairOffsetY: Int
        get() = prefs.getInt(KEY_CROSSHAIR_OFFSET_Y, 0)
        set(v) = prefs.edit { putInt(KEY_CROSSHAIR_OFFSET_Y, v) }

    /** 是否把状态栏区域也算进垂直居中 */
    var crosshairNotchMode: Boolean
        get() = prefs.getBoolean(KEY_CROSSHAIR_NOTCH, false)
        set(v) = prefs.edit { putBoolean(KEY_CROSSHAIR_NOTCH, v) }

    /** 旋转角度(度), 只在有自定义图片时生效 */
    var crosshairRotation: Float
        get() = prefs.getFloat(KEY_CROSSHAIR_ROTATION, 0f)
        set(v) = prefs.edit { putFloat(KEY_CROSSHAIR_ROTATION, v) }

    var crosshairFlipH: Boolean
        get() = prefs.getBoolean(KEY_CROSSHAIR_FLIP_H, false)
        set(v) = prefs.edit { putBoolean(KEY_CROSSHAIR_FLIP_H, v) }

    var crosshairFlipV: Boolean
        get() = prefs.getBoolean(KEY_CROSSHAIR_FLIP_V, false)
        set(v) = prefs.edit { putBoolean(KEY_CROSSHAIR_FLIP_V, v) }

    /** 前台服务是否在运行, 只用于顶栏按钮的状态显示 */
    var serviceRunning: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        set(v) = prefs.edit { putBoolean(KEY_SERVICE_RUNNING, v) }

    /** 0f..1f`,直接对应 View 的 alpha */
    var crosshairAlpha: Float
        get() = prefs.getFloat(KEY_CROSSHAIR_ALPHA, 1f)
        set(v) = prefs.edit { putFloat(KEY_CROSSHAIR_ALPHA, v) }

    // ── 面板 ──────────────────────────────────────────────
    var panelEnabled: Boolean
        get() = prefs.getBoolean(KEY_PANEL_ENABLED, false)
        set(v) = prefs.edit { putBoolean(KEY_PANEL_ENABLED, v) }

    /** 面板左上角坐标(px)`,未初始化时为 [UNSET_POSITION] */
    var panelX: Int
        get() = prefs.getInt(KEY_PANEL_X, UNSET_POSITION)
        set(v) = prefs.edit { putInt(KEY_PANEL_X, v) }

    var panelY: Int
        get() = prefs.getInt(KEY_PANEL_Y, UNSET_POSITION)
        set(v) = prefs.edit { putInt(KEY_PANEL_Y, v) }

    /** 展开态尺寸(px), 0 表示还没设置过, 由服务按屏幕折算默认值 */
    var panelWidthPx: Int
        get() = prefs.getInt(KEY_PANEL_WIDTH, 0)
        set(v) = prefs.edit { putInt(KEY_PANEL_WIDTH, v) }

    var panelHeightPx: Int
        get() = prefs.getInt(KEY_PANEL_HEIGHT, 0)
        set(v) = prefs.edit { putInt(KEY_PANEL_HEIGHT, v) }

    /** 悬浮球左上角坐标(px). 与面板位置分开存, 否则收成球会把面板几何覆盖掉 */
    var panelBallX: Int
        get() = prefs.getInt(KEY_PANEL_BALL_X, UNSET_POSITION)
        set(v) = prefs.edit { putInt(KEY_PANEL_BALL_X, v) }

    var panelBallY: Int
        get() = prefs.getInt(KEY_PANEL_BALL_Y, UNSET_POSITION)
        set(v) = prefs.edit { putInt(KEY_PANEL_BALL_Y, v) }

    var panelExpanded: Boolean
        get() = prefs.getBoolean(KEY_PANEL_EXPANDED, false)
        set(v) = prefs.edit { putBoolean(KEY_PANEL_EXPANDED, v) }

    var panelToolbarAtBottom: Boolean
        get() = prefs.getBoolean(KEY_PANEL_TOOLBAR_BOTTOM, false)
        set(v) = prefs.edit { putBoolean(KEY_PANEL_TOOLBAR_BOTTOM, v) }

    // ── 面板内容层 ─────────────────────────────────────────
    /** 第 0 项是背景层, 之后依次是叠加层. 空列表表示面板还没有内容 */
    var panelLayers: List<PanelLayer>
        get() = PanelLayer.listFromJson(prefs.getString(KEY_PANEL_LAYERS, null))
        set(v) = prefs.edit { putString(KEY_PANEL_LAYERS, PanelLayer.listToJson(v)) }

    /** 导入的字体文件(content:// uri)`,未导入时为 null, 用系统默认字体 */
    var panelFontUri: String?
        get() = prefs.getString(KEY_PANEL_FONT_URI, null)
        set(v) = prefs.edit { putString(KEY_PANEL_FONT_URI, v) }

    /** 字体文件的显示名, 只用于设置页展示 */
    var panelFontName: String?
        get() = prefs.getString(KEY_PANEL_FONT_NAME, null)
        set(v) = prefs.edit { putString(KEY_PANEL_FONT_NAME, v) }

    /** 一次写入多项, 只触发一次监听回调 */
    fun editing(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }

    /** 任一层可见时服务就应存活 */
    val anyOverlayEnabled: Boolean
        get() = crosshairEnabled || panelEnabled

    companion object {
        const val PREFS_NAME = "floatshow_settings"
        const val DEFAULT_SIZE = 4f
        const val MIN_SIZE = 1f

        /** 滑条默认上限`,输入更大时滑条上限临时跟着扩展 */
        const val SIZE_SOFT_MAX = 100f

        /** 只对软上限做吸附`,滑到附近自动对上 */
        const val SIZE_SNAP_TOLERANCE = 5f
        const val UNSET_POSITION = -1

        const val KEY_SERVICE_RUNNING = "service_running"
        const val KEY_CROSSHAIR_ENABLED = "crosshair_enabled"
        const val KEY_CROSSHAIR_IMAGE = "crosshair_image"
        const val KEY_CROSSHAIR_SIZE = "crosshair_size_float"
        const val KEY_CROSSHAIR_OFFSET_X = "crosshair_offset_x"
        const val KEY_CROSSHAIR_OFFSET_Y = "crosshair_offset_y"
        const val KEY_CROSSHAIR_NOTCH = "crosshair_notch"
        const val KEY_CROSSHAIR_ALPHA = "crosshair_alpha"
        const val KEY_CROSSHAIR_ROTATION = "crosshair_rotation"
        const val KEY_CROSSHAIR_FLIP_H = "crosshair_flip_h"
        const val KEY_CROSSHAIR_FLIP_V = "crosshair_flip_v"

        const val MAX_SIZE = 4096f
        const val MAX_ROTATION = 360f
        const val KEY_PANEL_ENABLED = "panel_enabled"
        const val KEY_PANEL_X = "panel_x"
        const val KEY_PANEL_Y = "panel_y"
        const val KEY_PANEL_BALL_X = "panel_ball_x"
        const val KEY_PANEL_BALL_Y = "panel_ball_y"
        const val KEY_PANEL_WIDTH = "panel_width_px"
        const val KEY_PANEL_HEIGHT = "panel_height_px"
        const val KEY_PANEL_EXPANDED = "panel_expanded"
        const val KEY_PANEL_TOOLBAR_BOTTOM = "panel_toolbar_bottom"
        const val KEY_PANEL_LAYERS = "panel_layers_json"
        const val KEY_PANEL_FONT_URI = "panel_font_uri"
        const val KEY_PANEL_FONT_NAME = "panel_font_name"
        const val DEFAULT_PANEL_WIDTH_DP = 280
        const val DEFAULT_PANEL_HEIGHT_DP = 220
        /** 收缩态圆球直径 */
        const val PANEL_BALL_SIZE_DP = 40
        /** 最小化/关闭按钮的方形触发区, 也是工具栏高度 */
        const val PANEL_CONTROL_SIZE_DP = 40
        /** 面板最小宽高就是球本身 */
        const val MIN_PANEL_SIZE_DP = 40
    }
}
