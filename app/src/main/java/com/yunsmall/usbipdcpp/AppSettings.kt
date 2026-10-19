package com.yunsmall.usbipdcpp

import android.content.Context

/**
 * 应用级设置。与设备历史记录分开存储：两者的生命周期和语义不同，
 * 不共用键空间。
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 曾共享过的设备重新插入时是否自动绑定；默认开启 */
    var autoBindRememberedDevices: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BIND_REMEMBERED_DEVICES, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_BIND_REMEMBERED_DEVICES, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "app_settings"
        const val KEY_AUTO_BIND_REMEMBERED_DEVICES = "auto_bind_remembered_devices"
    }
}
