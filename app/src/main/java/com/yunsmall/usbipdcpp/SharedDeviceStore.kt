package com.yunsmall.usbipdcpp

import android.content.Context
import android.hardware.usb.UsbDevice

/**
 * 用户曾共享过的设备记录。
 *
 * 持久化的目的：设备拔出、停止服务器、进程重启都不该丢掉"这个设备被共享过"
 * 的信息——重插或重启后要据此恢复绑定。只有用户手动解绑才删除记录。
 *
 * 标识用 VID/PID + serial：重插后 deviceName 可能变化，不能当键。serial 在
 * 无 USB 权限时读不到，所以读取方需要支持只按 VID/PID 匹配。
 */
class SharedDeviceStore(context: Context) {

    /**
     * 设置界面展示用的一条记录（按 VID/PID 聚合，一个型号一行）。
     * serials 是同型号下出现过的 serial，空表示该记录没有读到 serial。
     */
    data class RememberedDevice(
        val vendorId: Int,
        val productId: Int,
        val serials: List<String>
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 记录一次成功绑定。调用时设备已授权，serial 可读；个别设备读不到时留空。
     */
    fun remember(device: UsbDevice) {
        // keyOf 在锁外算：serialNumber 读取可能阻塞（权限/驱动），不能占着写锁
        val key = keyOf(device)
        // 读-改-写要跨线程互斥：Service 在 native 线程写，设置界面在主线程删，
        // SharedPreferences 自身只保证单次操作原子（锁对象在 companion，所有实例共享）
        synchronized(writeLock) {
            // getStringSet 返回的集合文档要求不得修改，复制后再改
            val keys = prefs.getStringSet(KEY_DEVICES, emptySet()).orEmpty().toMutableSet()
            if (keys.add(key)) {
                prefs.edit().putStringSet(KEY_DEVICES, keys).apply()
            }
        }
    }

    /**
     * 按型号删除（手动解绑路径用）：从设备对象直接删除，不依赖记录键。
     * 设备表可能已被停服清空，解绑仍要能删掉记录。
     *
     * 记录是型号级的：设备刚插入时没有 USB 权限、读不到 serial，只能按
     * VID/PID 匹配，无法区分同型号的多台设备。若只删精确键，同型号另一台
     * 留下的记录会让刚解绑的这台重插后又被自动恢复；因此解绑任意一台
     * 即关闭该型号的自动恢复。
     */
    fun forgetModel(vendorId: Int, productId: Int) {
        val prefix = "$vendorId:$productId:"
        synchronized(writeLock) {
            val keys = prefs.getStringSet(KEY_DEVICES, emptySet()).orEmpty()
            val remaining = keys.filterNot { it.startsWith(prefix) }.toSet()
            if (remaining.size != keys.size) {
                prefs.edit().putStringSet(KEY_DEVICES, remaining).apply()
            }
        }
    }

    /** 设置界面的"全部清除" */
    fun clear() {
        synchronized(writeLock) {
            prefs.edit().remove(KEY_DEVICES).apply()
        }
    }

    /**
     * 该设备是否在历史记录中。按 VID/PID 匹配：设备刚插入时通常还没有
     * USB 权限，serial 读不到，无法区分同型号的多台设备——记录因此是
     * 型号级语义（见 forget 的说明）。
     */
    fun isRemembered(device: UsbDevice): Boolean {
        val prefix = "${device.vendorId}:${device.productId}:"
        // 读也拿同一把写锁：不是防 SharedPreferences 撕裂（它自身线程安全），
        // 而是让"整个存储由 writeLock 保护"成为无需解释的契约
        synchronized(writeLock) {
            return prefs.getStringSet(KEY_DEVICES, emptySet()).orEmpty().any { it.startsWith(prefix) }
        }
    }

    /**
     * 已记住的设备，按 VID/PID 聚合成一个型号一行：删除本来就是型号级语义，
     * 分开显示多行会让用户以为删掉一行只影响一台设备。
     */
    fun rememberedDevices(): List<RememberedDevice> {
        synchronized(writeLock) {
            return prefs.getStringSet(KEY_DEVICES, emptySet()).orEmpty()
            .mapNotNull { key ->
                val parts = key.split(':', limit = 3)
                val vendorId = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val productId = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                Triple(vendorId, productId, parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
            }
            .groupBy { it.first to it.second }
            .map { (model, entries) ->
                RememberedDevice(
                    vendorId = model.first,
                    productId = model.second,
                    serials = entries.mapNotNull { it.third }.distinct().sorted()
                )
            }
            .sortedWith(compareBy({ it.vendorId }, { it.productId }))
        }
    }

    private fun keyOf(device: UsbDevice): String {
        // serial 读取需要 USB 权限；异常或缺失时按空串记录，VID/PID 仍然有效
        val serial = runCatching { device.serialNumber }.getOrNull() ?: ""
        return "${device.vendorId}:${device.productId}:$serial"
    }

    private companion object {
        const val PREFS_NAME = "device_history"
        const val KEY_DEVICES = "shared_devices"

        // 所有实例共享的锁：Service 与 UI 各自 new 了一个 SharedDeviceStore，
        // 实例锁互不相干，挡不住跨实例的读-改-写竞争
        val writeLock = Any()
    }
}
