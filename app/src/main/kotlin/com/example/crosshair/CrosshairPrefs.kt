package com.example.crosshair

import android.content.Context
import android.content.SharedPreferences

class CrosshairPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("crosshair_prefs", Context.MODE_PRIVATE)

    var imageUri: String?
        get() = prefs.getString(KEY_IMAGE_URI, null)
        set(value) = prefs.edit().putString(KEY_IMAGE_URI, value).apply()

    var size: Int
        get() = prefs.getInt(KEY_SIZE, 40)
        set(value) = prefs.edit().putInt(KEY_SIZE, value).apply()

    var offsetX: Int
        get() = prefs.getInt(KEY_OFFSET_X, 0)
        set(value) = prefs.edit().putInt(KEY_OFFSET_X, value).apply()

    var offsetY: Int
        get() = prefs.getInt(KEY_OFFSET_Y, 0)
        set(value) = prefs.edit().putInt(KEY_OFFSET_Y, value).apply()

    var notchMode: Boolean
        get() = prefs.getBoolean(KEY_NOTCH_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTCH_MODE, value).apply()

    var transparency: Float
        get() = prefs.getFloat(KEY_TRANSPARENCY, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TRANSPARENCY, value).apply()

    var serviceRunning: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_RUNNING, value).apply()

    companion object {
        private const val KEY_IMAGE_URI = "image_uri"
        private const val KEY_SIZE = "size"
        private const val KEY_OFFSET_X = "offset_x"
        private const val KEY_OFFSET_Y = "offset_y"
        private const val KEY_NOTCH_MODE = "notch_mode"
        private const val KEY_TRANSPARENCY = "transparency"
        private const val KEY_SERVICE_RUNNING = "service_running"
    }
}
