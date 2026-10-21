package com.yunsmall.usbipdcpp

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 应用级设置。与设备历史记录分开存储：两者的生命周期和语义不同，
 * 不共用键空间。
 *
 * fork 的设置（拨出、仅监听回环）与上游的自动绑定开关放在同一个文件里，写法
 * 相同：每个设置一个读写属性，直接落在 SharedPreferences 上。SharedPreferences
 * 在进程内是同一个对象，所以各处各自 new 一个 AppSettings 看到的是同一份值。
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 曾共享过的设备重新插入时是否自动绑定。Fork: off by default, so nothing is shared or asked for until Bind is tapped */
    var autoBindRememberedDevices: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BIND_REMEMBERED_DEVICES, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_BIND_REMEMBERED_DEVICES, value).apply()
        }

    /**
     * 服务器只监听 127.0.0.1 而不是所有网卡（fork 新增）；默认开启。
     * 下次启动服务器时生效。
     */
    var loopbackOnly: Boolean
        get() = prefs.getBoolean(KEY_LOOPBACK_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LOOPBACK_ONLY, value).apply()
        }

    /** 向主机拨出（fork 新增）；默认关闭 */
    var dialOutEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIAL_OUT_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DIAL_OUT_ENABLED, value).apply()
        }

    /** 拨出的主机；没有默认值，空串表示未设置。写入时去掉首尾空白 */
    var dialHost: String
        get() = prefs.getString(KEY_DIAL_HOST, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DIAL_HOST, value.trim()).apply()
        }

    /** 拨出的端口；默认 [DEFAULT_DIAL_PORT] */
    var dialPort: Int
        get() = prefs.getInt(KEY_DIAL_PORT, DEFAULT_DIAL_PORT)
        set(value) {
            prefs.edit().putInt(KEY_DIAL_PORT, value).apply()
        }

    /** fork 的设置在某一时刻的快照，UI 与服务据此观察变化 */
    data class Values(
        val loopbackOnly: Boolean = true,
        val dialOutEnabled: Boolean = false,
        val dialHost: String = "",
        val dialPort: Int = DEFAULT_DIAL_PORT
    )

    fun snapshot(): Values = Values(
        loopbackOnly = loopbackOnly,
        dialOutEnabled = dialOutEnabled,
        dialHost = dialHost,
        dialPort = dialPort
    )

    /**
     * 设置变化的流：先发当前值，之后每次任一设置被写入就发新值。别处的 AppSettings
     * 实例写入也会触发（同一个 SharedPreferences）。监听器随收集者注册、取消时注销，
     * 所以不会被 SharedPreferences 的弱引用提前回收。
     */
    val values: Flow<Values> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(snapshot())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(snapshot())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    companion object {
        const val DEFAULT_DIAL_PORT = 3241

        private const val PREFS_NAME = "app_settings"
        private const val KEY_AUTO_BIND_REMEMBERED_DEVICES = "auto_bind_remembered_devices"
        private const val KEY_LOOPBACK_ONLY = "loopback_only"
        private const val KEY_DIAL_OUT_ENABLED = "dial_out_enabled"
        private const val KEY_DIAL_HOST = "dial_out_host"
        private const val KEY_DIAL_PORT = "dial_out_port"
    }
}
