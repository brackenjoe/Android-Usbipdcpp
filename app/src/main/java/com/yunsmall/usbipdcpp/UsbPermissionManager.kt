package com.yunsmall.usbipdcpp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * What to do with a USB permission broadcast. [granted] comes from the system's
 * own record ([hasPermission]), never from the broadcast's extra: another app
 * cannot reach the receiver, but nothing here depends on that.
 * [claimMismatch]: the broadcast claimed something else. A check that throws
 * counts as not granted.
 */
data class PermissionDecision(val granted: Boolean, val claimMismatch: Boolean)

fun decidePermissionResult(claimedGranted: Boolean, hasPermission: () -> Boolean): PermissionDecision {
    val actual = try {
        hasPermission()
    } catch (e: Exception) {
        false
    }
    return PermissionDecision(granted = actual, claimMismatch = claimedGranted != actual)
}

/**
 * USB 权限受理与设备列表变化的统一入口。
 * permissionIntent 用 lazy：PendingIntent.getBroadcast 会创建系统对象，只有
 * 真正发起权限请求才需要；本类存在"被构造但从未发起过请求"的路径
 *（onDestroy 里的兜底 unregister），构造期不做这类副作用
 */
class UsbPermissionManager(
    private val context: Context,
    private val usbManager: UsbManager
) {
    companion object {
        private const val TAG = "UsbPermissionManager"
        const val ACTION_USB_PERMISSION = "com.yunsmall.usbipdcpp.USB_PERMISSION"
        // PendingIntent 的 requestCode 是系统匹配键的一部分：用项目内唯一值，
        // 避免将来新增的 broadcast PendingIntent（默认 requestCode 也是 0）被合并
        private const val PERMISSION_REQUEST_CODE = 1001
        // 待处理登记的陈旧窗口：结果若被系统吞掉永不投递，超时后允许对该设备
        // 重新发起
        private const val PERMISSION_PENDING_TIMEOUT_MS = 60_000L
    }

    private val permissionIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        PendingIntent.getBroadcast(context, PERMISSION_REQUEST_CODE, intent, flags)
    }

    // 统一锁对象：onReceive 与 requestPermission 各自 synchronized(this) 时
    // this 指向不同对象（Receiver 实例 vs Manager 实例），互斥完全不生效
    private val lock = Any()
    // 已受理、等待系统结果的设备名 → 受理时刻（elapsedRealtime），
    // 结果到达或拔出时移除。只存设备名不存回调：USB 权限对话框打开期间
    // Activity 可能重建，回调随旧组合作废；结果改由 onPermissionResult 交给
    // 重建后的新监听器。记时刻用于陈旧判定：结果若被系统吞掉永不投递，
    // 条目会永久挡住该设备
    private val pendingDevices = mutableMapOf<String, Long>()
    // 监听器只在主线程读写（组合与广播 onReceive 都在主线程），无需加锁
    private var onDeviceListChanged: (() -> Unit)? = null
    // 权限结果的唯一出口，详见 setOnPermissionResultListener
    private var onPermissionResult: ((UsbDevice, Boolean) -> Unit)? = null

    fun setOnDeviceListChangedListener(listener: (() -> Unit)?) {
        onDeviceListChanged = listener
    }

    /**
     * 权限结果统一从"当前监听器"交付，而不是按请求登记回调：对话框打开期间
     * Activity 可能重建，登记在旧实例上的回调无人认领（结果广播会由新实例的
     * 接收器收到），只有交付给当前存活的 UI 才能把绑定流程接上。
     * 已有权限的即时结果同样走此出口，调用方不必区分快慢两条路径。
     * 监听器在"已有权限"路径上是同步调用：实现必须立即返回，不得在此回调中
     * 重入 requestPermission（会形成同栈递归）
     */
    fun setOnPermissionResultListener(listener: ((UsbDevice, Boolean) -> Unit)?) {
        onPermissionResult = listener
    }

    // 权限结果的唯一投递点：监听器实现来自组合层，而两条投递路径的调用栈
    // 都在边界上——onReceive 是系统回调，已有权限的同步交付在 Compose 事件
    // 栈，监听器的异常穿过任一边界都会崩掉进程，在此兜住并记录
    private fun deliverResult(device: UsbDevice, granted: Boolean) {
        try {
            onPermissionResult?.invoke(device, granted)
        } catch (t: Throwable) {
            Log.e(TAG, "Permission result listener threw", t)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    val claimed = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                    // 系统权限结果必带 EXTRA_DEVICE；缺失属非常规实现，忽略即可——
                    // 该设备名的 pending 登记由超时窗口放行重试
                    device?.let { usbDevice ->
                        // Fork: the extra is only a claim; what counts is whether the
                        // system holds the permission for this device now
                        val decision = decidePermissionResult(claimed) { usbManager.hasPermission(usbDevice) }
                        val granted = decision.granted
                        if (decision.claimMismatch) {
                            Log.w(TAG, "Permission broadcast claims $claimed for ${usbDevice.deviceName}, system says ${decision.granted}")
                        }
                        Log.d(TAG, "USB permission result for ${usbDevice.deviceName}: $granted")
                        // 锁内只改登记表，锁外交付结果：监听器可能再次发起权限
                        // 请求，锁内同步执行用户代码（锁不可重入）有死锁风险
                        synchronized(lock) {
                            pendingDevices.remove(usbDevice.deviceName)
                        }
                        // 无条件交付：请求可能跨 Activity 重建（对话框期间旋转），
                        // 新实例的登记表是空的，不能以"有登记"为交付前提；设备
                        // 是否仍在由监听器侧核实。无监听器（UI 恰在重建间隙）时
                        // 结果丢弃，用户重新点击即可。
                        // 已知代价：超时接管后旧对话框若其实没死，新旧两个结果
                        // 都会交付（第二次以 DeviceInUse 被拒）——能区分新旧请求
                        // 的方案（如请求令牌）都要求新实例识别旧请求，令牌却随旧
                        // 实例销毁，与跨重建交付冲突，取交付优先
                        deliverResult(usbDevice, granted)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB device attached")
                    onDeviceListChanged?.invoke()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { usbDevice ->
                        Log.d(TAG, "USB device detached: ${usbDevice.deviceName}")
                        // 拔出后权限结果广播不会返回，清掉对应登记，
                        // 否则条目残留会让该设备名后续无法再发起权限请求
                        synchronized(lock) {
                            pendingDevices.remove(usbDevice.deviceName)
                        }
                    }
                    // 设备物理拔出，设备列表必须刷新（解绑清理由 UsbService 自己的
                    // 接收器负责，与本管理器无关）
                    onDeviceListChanged?.invoke()
                }
            }
        }
    }

    fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        // RECEIVER_NOT_EXPORTED 只拦"其他应用"发来的广播：系统（system uid）
        // 的插拔广播不受影响，权限结果广播经 PendingIntent 以本应用身份投递
        //（AMS 对同 uid 放行），两条来源都照收；显式导出反而给伪造广播留口。
        // Fork: on every API level. ContextCompat does it below API 33 with a
        // signature permission of the app's own (androidx merges it into the manifest)
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    fun getDeviceList(): Map<String, UsbDevice> {
        // hub 对 USB/IP 导出无意义（导出的是拓扑节点而非功能设备）。
        // 上游 AOSP 已在 UsbHostManager 里把 hub 从 UsbManager 剔除，这里再滤一道：
        // 定制 ROM 可能改掉该行为，界面不该出现这种设备
        return usbManager.deviceList.filterValues { !isHubDevice(it) }
    }

    // 对齐 AOSP UsbHostManager 的 hub 判定：设备描述符声明了 hub 类的直接算；
    // 描述符没给类（bDeviceClass=0）的从接口取，全部接口都是 hub 类才算，
    // 避免误伤带 hub 的复合设备
    private fun isHubDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_HUB) return true
        if (device.deviceClass != 0 || device.interfaceCount == 0) return false
        return (0 until device.interfaceCount).all {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HUB
        }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * @return true 表示请求已受理，结果经 [setOnPermissionResultListener] 交付
     *         （已有权限时同步交付）；false 表示同设备最近一次请求仍在途、本次未受理
     */
    fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) {
            // 已有权限：同步交付，调用方不必感知快慢两条路径。监听器为 null
            //（UI 恰在重建间隙）时交付被丢弃——调用方都在组合内调用本方法，
            // 监听器必然已装；保留一行日志便于异常时序下排查
            if (onPermissionResult != null) {
                deliverResult(device, true)
            } else {
                Log.w(TAG, "Permission already granted but no listener to deliver to")
            }
            return true
        }

        // 锁内只登记设备名与受理时刻，锁外发起系统请求：避免持锁调用外部代码。
        // 同一设备最近一次请求仍在途时拒绝新的：对同一设备的重复系统请求行为
        // 不定（可能重复弹窗），受理方也会失去"已有一次在途"这个提示依据；
        // 超过陈旧窗口视为结果已被系统吞掉（不投递广播），覆盖登记重新发起，
        // 否则该设备名会被永久挡住，只有 Activity 重建才能恢复
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val submittedAt = pendingDevices[device.deviceName]
            if (submittedAt != null && now - submittedAt < PERMISSION_PENDING_TIMEOUT_MS) {
                return false
            }
            if (submittedAt != null) {
                // 能到这里说明登记已超时：记一行便于排查"为何用户看到两个
                // 权限弹窗/结果错位"——前一次请求被判定为结果已丢失并接管
                Log.w(TAG, "Stale permission request taken over: ${device.deviceName}")
            }
            pendingDevices[device.deviceName] = now
        }
        // 抛异常时撤掉登记再重抛：AOSP 的系统服务异常会连带崩溃进程（残留状态
        // 没有存活的载体），但定制 ROM 可能抛而不崩（如 SecurityException），
        // 残留会让该设备名从此无法再发起请求
        try {
            usbManager.requestPermission(device, permissionIntent)
        } catch (t: Throwable) {
            synchronized(lock) {
                pendingDevices.remove(device.deviceName)
            }
            throw t
        }
        return true
    }
}