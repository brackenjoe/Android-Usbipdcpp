package com.yunsmall.usbipdcpp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.yunsmall.usbipdcpp.util.isCameraDevice
import androidx.core.app.NotificationCompat
import android.annotation.SuppressLint
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class UsbService : Service() {

    companion object {
        private const val TAG = "UsbService"
        private const val NOTIFICATION_CHANNEL_ID = "usbipd_service"
        private const val NOTIFICATION_ID = 1
        // Fork: "fingerprint needed to reconnect", while a dial loop waits for the key's window.
        // The channel id, AUTH_CHANNEL_ID, is in NotificationCheck.kt (the UI checks it too)
        private const val AUTH_NOTIFICATION_ID = 2
        // 默认监听端口：端口输入框的初值也取这里，避免两处各写一份字面量
        const val DEFAULT_PORT = 3240
    }

    /**
     * 对外可见的全部 Service 状态。UI 与通知栏都从这一份渲染，
     * 不再各自缓存一份靠手动刷新同步
     */
    data class UiState(
        val nativeReady: Boolean = false,
        val serverRunning: Boolean = false,
        // 停止服务器后保留上次运行值（stopServer 只清运行标志与设备表），
        // 供 UI 显示与重启参考
        val port: Int = DEFAULT_PORT,
        // 每次成功启动生成的标记（Service 实例 ID + 递增代次）：UI 据此判断
        // "服务器是否刚启动"，不依赖 UI 自己保存的运行状态
        val startMarker: String = "",
        // 服务器启动时实际生效的监听范围（fork 新增）：true 只监听 127.0.0.1。
        // 取启动那一刻的设置值而非当前设置——设置改了要重启服务器才生效，UI
        // 显示的必须是实际状态
        val loopbackOnly: Boolean = true,
        // deviceName -> busid，UI 展示 busid 用
        val boundDevices: Map<String, String> = emptyMap()
    )

    /**
     * 一次性事件：SharedFlow 无重放，没有收集者时事件自然丢弃（UI 不在，
     * 或服务刚绑定、UI 还没开始 collect 的窗口，都正合预期）——丢的只是
     * 提示，绑定状态经 _state 发布，不依赖事件送达
     */
    sealed interface Event {
        /** 设备被拔出；wasBound 表示拔出前处于绑定状态，UI 据此决定是否提示 */
        data class DeviceDetached(val device: UsbDevice, val wasBound: Boolean) : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Service 实例标识：进程重建后实例变化，供 startMarker 区分"UI 重建"与
    // "服务重建后的新启动"
    private val instanceId = UUID.randomUUID().toString()
    // 同一实例内的启动代次：停服再启递增；只在 serviceNativeContext 串行读写
    private var startGeneration = 0L

    // 拨出各循环的状态（fork 新增）：与 UiState 分开发布——字节计数约每 0.5 秒
    // 刷新一次，混进 UiState 会让整个界面和通知跟着重组/重发
    private val _dialLoops = MutableStateFlow<List<LoopStatus>>(emptyList())
    val dialLoops: StateFlow<List<LoopStatus>> = _dialLoops.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // 服务内部协程：native 初始化、状态变化刷新通知、拔出清理。
    // 状态写入本身线程安全（MutableStateFlow.update 是 CAS 循环），
    // 用 Main.immediate 只是让通知更新落在主线程
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // native 操作的统一上下文：native 线程 + 不可取消。
    // 启停、绑定与登记、拔出清理都必须执行到底：中途取消会留下 native 已生效而
    // Kotlin 未登记的半成品（设备已在库里绑定，这里却既没记录还会被关掉连接），
    // 变成谁也清不掉的孤儿。需要放弃的时机统一由 onDestroy 的 release 兜底，
    // 不靠取消来打断；调用方（UI）被取消只影响它自己能否拿到结果
    // 复用 UsbIpNative.jniContext 实例而非自行组合：嵌套 withContext 的内联
    // fast path 按 interceptor 实例相等判定，共享同一对象让两侧不可能偏离
    private val serviceNativeContext = UsbIpNative.jniContext

    private val binder = UsbBinder()

    // ==================== 拨出（fork 新增，见 DialOut） ====================
    // dialOut 与 dialInstances 的写入都在 dialLock 内；dialBusy 只在主线程读写
    private val dialLock = Any()
    private var dialOut: DialOut? = null
    // 每个拨出实例当前是否还有线程（含正在收尾的）：任一为 true 就要持有唤醒锁。
    // 实例被替换后旧实例的线程还会跑一小会儿，所以按实例记，不按"当前实例"算
    private val dialBusy = mutableMapOf<DialOut, Boolean>()
    private var wakeLock: PowerManager.WakeLock? = null

    // fork 的设置：UI 写、服务读，同一份 SharedPreferences
    private val settings by lazy { AppSettings(this) }

    // 保存活跃的USB连接。只在 nativeDispatcher 线程读写（与 native 调用同一临界区），
    // 对外的绑定状态经 _state 发布，所以这里用普通 map 即可
    private data class DeviceInfo(
        val connection: UsbDeviceConnection,
        val fd: Int,
        val busid: String
    )
    private val activeDevices = mutableMapOf<String, DeviceInfo>()

    // 曾共享设备的历史记录：拔出、停服、进程重启都不清，只有手动解绑才删
    private val sharedDeviceStore by lazy { SharedDeviceStore(this) }

    // onStartCommand 之前 notify 同 ID 通知会先于 startForeground 生效，语义混乱，
    // 这里等前台通知建立后再由状态驱动更新
    private var notificationStarted = false

    // onDestroy 开始清理（release + 关全部连接）后置位，拒绝新到的 bindDevice：
    // 那种绑定要么在清理后失败，要么在清理前成功又被清理线程连根拔掉，留下
    // "Kotlin 已登记、两边都已清理"的残影状态。@Volatile：写于主线程
    //（onDestroy），读于 native 线程（bindDevice 的 withContext 内）
    @Volatile
    private var destroying = false

    inner class UsbBinder : Binder() {
        fun getService(): UsbService = this@UsbService
    }

    // 服务自注册的拔出监听：Activity 销毁（用户退出 UI）后服务仍在前台运行，
    // 拔出清理不能依赖 UI 层，否则 activeDevices 残留、连接无法清理。
    // 这是拔出清理的唯一路径，UI 只消费 Event 弹提示
    private val deviceDetachedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let { usbDevice ->
                // onReceive 在主线程，设备清理要切到 native 线程；起协程而非
                // runBlocking，避免阻塞主线程
                serviceScope.launch { handleDeviceDetached(usbDevice) }
            }
        }
    }

    init {
        // 状态变化的唯一出口：通知栏文案跟着 _state 走，不用在每个变更点手写刷新
        serviceScope.launch {
            // 通知文案只取决于 serverRunning：只在它变化时刷新
            state.map { it.serverRunning }.distinctUntilChanged()
                .collect { if (notificationStarted) updateNotification() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // native 初始化含库加载与 libusb 初始化，放 native 线程执行不阻塞主线程
        serviceScope.launch {
            val ready = UsbIpNative.init()
            _state.update { it.copy(nativeReady = ready) }
            if (!ready) {
                Log.e(TAG, "Native initialization failed, USB/IP features unavailable")
            }
        }

        startDialOutController()

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        // 系统广播不受 RECEIVER_NOT_EXPORTED 限制（其只拦其他应用来源），
        // 导出反而给伪造"设备拔出"的广播留口。Fork: on every API level
        ContextCompat.registerReceiver(this, deviceDetachedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationStarted = true
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // 置位要尽早：清理线程还在排队等待在途 native 调用期间，迟到的
        // bindDevice 仍可能被成功受理
        destroying = true
        // 拨出先停：关闭全部 socket、结束全部线程（不阻塞），并释放唤醒锁
        stopDialOutForDestroy()
        updateAuthNotification(false)
        try {
            unregisterReceiver(deviceDetachedReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered", e)
        }
        // 这里的取消不会与下面的清理打架：init() 若还没开始会被取消（g_initialized
        // 保持 false，release 跳过 libusb_exit）；已经开始的那个跑在 jniContext
        //（NonCancellable）上会跑完并置位，release 的 libusb_exit 正好配对
        serviceScope.cancel()
        // native 清理含 join 线程等耗时操作，在主线程 runBlocking 会卡 ANR，
        // 放到后台线程执行（进程退出时系统回收，不保证执行完）。
        // closeAllDevices 也放在 native 线程内：销毁期间 MainActivity 的协程
        // 可能仍在 nativeDispatcher 上执行 bindDevice，map 写必须同线程串行。
        // 闭包持有 this 是强引用：JVM 下 Service 对象在清理线程运行期间不会被
        // GC，不存在 C++ 那样的 use-after-free。
        // 清理线程可能要等正在执行的 native 调用（如 bindDevice 卡在 USB 传输上）
        // 结束，极端情况下等很久；不加超时：onDestroy 是终结路径，线程挂住最坏到
        // 进程退出由系统回收——Android 的进程退出是系统 kill，与线程是否守护无关
        //（不设 isDaemon），而超时会让 release 根本不执行，清理更不完整。
        // release 先于 closeAllDevices：release 内部先停服务器，LibusbServer::stop
        // 会把可用与使用中的设备全部 release_and_close/unref（已对照库源码确认），
        // 返回后库已与 fd 无关，再关 UsbDeviceConnection，避免"libusb 仍在用已
        // 关闭 fd"的窗口。
        // Service 若被系统销毁后又同进程重建（前台服务下罕见），本清理与新
        // 实例的 init 先后不定：坏顺序（init 先、清理后）会把刚初始化的
        // libusb exit 掉，靠 startServerImpl 的幂等 init 恢复（绑定必须先启动
        // 服务器，必经它）；若调度极端到清理晚于新实例启动服务器，会连带把新
        // server 停掉——一次服务中断，重启服务器即恢复，不为此引入代际协议
        // 清理期间的 native 日志是否送达 UI 取决于日志回调是否还挂着：Activity
        // 先于 Service 销毁时回调已被 onDispose 清掉，那段日志只进 logcat——
        // 不影响清理本身，不为此保活回调
        // 线程具名：清理卡住或失败时能从日志定位到这段逻辑（匿名只有 Thread-N）
        thread(name = "usbipd-native-cleanup") {
            // 起止各记一行：清理可能要等正在执行的 native 调用（不可取消）跑完，
            // 两行日志的时间差能直接看出清理卡在哪一段
            Log.i(TAG, "Native cleanup started")
            // 外层再兜一层：清理异常逃到这个裸线程的默认异常处理器会终止进程，
            // 而 Service 被 stopSelf/系统回收并不等于进程退出（App 可能仍在前台）。
            // 终局清理尽力而为，失败只记日志
            try {
                UsbIpNative.runOnNativeThread {
                    // release 内部会先停服务器并释放 libusb，不用再单独判断服务是否运行；
                    // finally 保证 release 抛异常时连接也被关掉
                    try {
                        UsbIpNative.release()
                    } finally {
                        closeAllDevices()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Native cleanup failed", t)
            }
            Log.i(TAG, "Native cleanup finished")
        }
        // super 最后调用：销毁类回调的通行顺序是先清理自己持有的资源、再让基类
        // 收尾（创建类回调如 onCreate 相反，super 最先）——与 MainActivity.onDestroy
        // 采用同一约定
        super.onDestroy()
    }

    suspend fun startServer(port: Int): Boolean {
        // 同 bindDevice：销毁开始后拒绝（见 destroying 声明处）——清理线程已把
        // native 全部释放，此时启动会在销毁流程之外留下无人管理的服务器
        if (destroying) {
            return false
        }
        // 检查、启动与 state 更新在同一临界区内构成事务：临界区外的检查会与
        // 并发的 startServer 交错——后进入者会把 state.port 填成与实际监听不
        // 一致的值。UI 的 isStarting 禁用只是防连点，不作为并发正确性的依赖；
        // state.port 一律用 JNI 返回的实际监听端口回填，不用入参推断
        return withContext(serviceNativeContext) {
            // 复查销毁标志：入口检查与本块开始之间主线程可能已置位（同 bindDevice）
            if (destroying) {
                return@withContext false
            }
            // 已在运行时返回"实际监听端口是否即本次入参"：同端口视为幂等成功，
            // 不同端口返回 false（服务器并未按入参启动）。比较用的是 state.port
            // 这个实际值，不是拿入参回填/推断（见上方事务注释）
            if (state.value.serverRunning) {
                return@withContext state.value.port == port
            }

            val loopbackOnly = settings.loopbackOnly
            val actualPort = UsbIpNative.startServer(port, loopbackOnly)
            val success = actualPort >= 0
            if (success) {
                // 回填 native 返回的实际端口：state 落后于 native（OOM 级更新
                // 失败使 serverRunning 停在 false 而服务器实际在跑）时本短路口
                // 不生效，走这里——返回的"已在运行"端口是真实值，显示不错位。
                // startMarker 在 lambda 外算好：_state.update 的 CAS 重试会
                // 重复执行 lambda，在里面递增会多算代次
                startGeneration += 1
                val marker = "$instanceId:$startGeneration"
                _state.update {
                    it.copy(
                        serverRunning = true,
                        port = actualPort,
                        loopbackOnly = loopbackOnly,
                        startMarker = marker
                    )
                }
            }
            success
        }
    }

    suspend fun stopServer() {
        // 同 startServer/bindDevice：销毁开始后拒绝（见 destroying 声明处）——
        // 清理线程负责停服务器与释放全部资源，此时的停止只会与清理流程重复
        // 执行，拦下让三个操作入口的销毁语义保持一致
        if (destroying) {
            return
        }
        withContext(serviceNativeContext) {
            // 复查销毁标志：入口检查与本块开始之间主线程可能已置位（同 bindDevice）
            if (destroying) {
                return@withContext
            }
            try {
                UsbIpNative.stopServer()
            } finally {
                // 清理放 finally：stop 万一抛异常也要让连接与状态回到"已停止"，
                // 否则 UI 显示运行中而 native 已停，用户重试也无法纠正。
                // closeAllDevices 在 native 线程内（withContext 保证），避免与
                // 并发绑定冲突；顺序不可反——先让库停止使用 fd 再关连接
                closeAllDevices()
                _state.update { it.copy(serverRunning = false, boundDevices = emptyMap()) }
            }
        }
    }

    suspend fun bindDevice(usbManager: UsbManager, device: UsbDevice): DeviceBindResult {
        // 销毁开始后拒绝新绑定（见 destroying 声明处）；通过此检查后才入队的
        // 调用由临界区内的复查拦截
        if (destroying) {
            return DeviceBindResult.Failure.ServerNotRunning
        }
        // Fork: no camera permission in this build, so a video-class device is never
        // bound, whichever path got here (a tap, the automatic re-bind of a
        // remembered device, a permission result arriving after a recreation)
        if (isCameraDevice(device)) {
            return DeviceBindResult.Failure.CameraUnsupported
        }
        // 整个方法体在 native 线程：设备表读写必须与 native 调用同临界区。
        // 上下文不可取消（见 serviceNativeContext）：绑定与登记必然一起完成或一起
        // 不发生，不存在"native 已绑定而 Kotlin 没登记"的孤儿；调用方被取消只影响
        // 它自己能否拿到结果
        return withContext(serviceNativeContext) {
            // 复查销毁标志：入口检查与本块开始之间主线程可能已置位（onDestroy
            // 与 bindDevice 都从主线程进入）；清理任务排在同一 jniContext 上、
            // 本任务先于它执行，在临界区内拦下即避免"刚绑定就被清理"的残影
            if (destroying) {
                return@withContext DeviceBindResult.Failure.ServerNotRunning
            }
            // 防御：同一设备已绑定则拒绝，否则覆盖 map 条目导致旧连接泄漏
            if (activeDevices.containsKey(device.deviceName)) {
                return@withContext DeviceBindResult.Failure.DeviceInUse
            }

            // 成功登记后连接归 activeDevices 管，其余路径（含异常）统一由 finally 关闭：
            // 绑定失败或抛异常时没有登记，留着连接就是 fd 泄漏。
            // Fork: openDevice is inside the try. It throws
            // SecurityException when the system holds no permission for the device
            // (a permission revoked, or never granted), which must not crash the app
            var connection: UsbDeviceConnection? = null
            var registered = false
            try {
                val opened = try {
                    usbManager.openDevice(device)
                } catch (e: SecurityException) {
                    Log.w(TAG, "openDevice refused (no permission): ${device.deviceName}")
                    return@withContext DeviceBindResult.Failure.DeviceOpenFailed
                }
                if (opened == null) {
                    return@withContext DeviceBindResult.Failure.DeviceOpenFailed
                }
                connection = opened
                // fd < 0 实际不会出现（openDevice 成功后必有 fd），防御在此：
                // 返回失败，连接由 finally 统一关闭
                // getFileDescriptor 是公开 SDK API（API 12 起），返回 int（原始
                // fd，不是 FileDescriptor 对象），非隐藏 API，不需要反射兜底
                val fd = opened.fileDescriptor
                if (fd < 0) {
                    return@withContext DeviceBindResult.Failure.DeviceOpenFailed
                }

                when (val result = UsbIpNative.bindUsbDevice(fd, device.vendorId, device.productId)) {
                    is DeviceBindResult.Success -> {
                        // 绑定成功即记入历史：拔出/停服/重启都保留，重插时可据此恢复。
                        // 持久化是附属功能：这里抛异常会让登记被跳过、finally 关掉连接，
                        // 而 native 侧已经绑定，所以失败只记日志
                        runCatching { sharedDeviceStore.remember(device) }
                            .onFailure { e -> Log.w(TAG, "remember device history failed", e) }
                        activeDevices[device.deviceName] = DeviceInfo(opened, fd, result.busid)
                        // 登记成功即接管连接所有权（finally 据此决定关不关连接）。
                        // registered 的置位点精确对齐"登记成功"：put 失败（OOM 级）
                        // 时仍为 false，finally 关掉未登记的连接（native 侧此时已
                        // 持有绑定、无人登记，残留由 stopServer/拔出/release 的
                        // 统一回收链兜底；能走到这里就是 OOM 级，不再加 unbind
                        // 兜底）；提前置位反而会让失败时连接绕过 finally 外逃。
                        // 置位又必须先于 state 更新：
                        // state 失败同样不回滚登记，连接已归设备表管就不能再关，
                        // 留给 stopServer/拔出/release 统一回收——在此之前 UI 显示
                        // 与 native 实际不一致，不做主动自愈
                        registered = true
                        _state.update {
                            it.copy(boundDevices = it.boundDevices + (device.deviceName to result.busid))
                        }
                        Log.i(TAG, "Device bound: ${device.deviceName} -> ${result.busid}")
                        result
                    }
                    is DeviceBindResult.Failure -> result
                }
            } catch (t: CancellationException) {
                // 结构化并发规范：取消必须向上传播，不能转成普通失败结果
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "bindDevice failed unexpectedly: ${device.deviceName}", t)
                // registered 已置位说明 native 绑定与设备表登记都已完成，失败的
                // 只是后续的 state 发布（OOM 级）——真相是"绑上了"，谎报失败会让
                // 用户重试时收到 DeviceInUse 更困惑；按真相返回 Success，UI 状态
                // 由 stopServer/拔出/release 的回收链最终对齐。不回滚 native 绑定：
                // 触发本分支的故障下回滚同样可能失败，多一条路径换不来确定性，
                // 保持登记交给回收链是单一处理路径
                val boundBusid = if (registered) activeDevices[device.deviceName]?.busid else null
                if (boundBusid != null) {
                    DeviceBindResult.Success(boundBusid)
                } else {
                    DeviceBindResult.Failure.UnknownError
                }
            } finally {
                if (!registered) {
                    // close 兜异常：释放失败只记日志——让它从 finally 抛出会
                    // 变成协程未捕获异常（调用方 scope 无兜底，App 直接崩）
                    runCatching { connection?.close() }
                        .onFailure { e -> Log.w(TAG, "close device failed", e) }
                }
            }
        }
    }

    // 解绑路径共用的历史记录删除：失败只记日志，不能阻断连接关闭与状态清理
    private fun forgetHistory(device: UsbDevice) {
        runCatching { sharedDeviceStore.forgetModel(device.vendorId, device.productId) }
            .onFailure { e -> Log.w(TAG, "forget device history failed", e) }
    }

    suspend fun unbindDevice(device: UsbDevice): DeviceUnbindResult {
        val deviceName = device.deviceName
        // 同 startServer/bindDevice：销毁开始后拒绝（见 destroying 声明处）——
        // 清理线程会把全部设备连根清理，此时的解绑只是重复操作；按幂等成功
        // 上报，与 DeviceNotFound 分支同一语义：目标（连接关、状态清）终将达成
        if (destroying) {
            // 清理线程正在释放 native 资源，连接与状态不需要再动；但用户这次
            // 点击仍是解绑意图，历史记录必须删掉（纯 SharedPreferences 操作，
            // 与 native 清理无冲突）
            forgetHistory(device)
            return DeviceUnbindResult.Success
        }
        // 同 bindDevice：挂 serviceNativeContext，cleanup 不会被调用方取消打断
        return withContext(serviceNativeContext) {
            // 复查销毁标志：入口检查与本块开始之间主线程可能已置位（同 bindDevice）
            if (destroying) {
                // 同入口分支：用户这次点击仍是解绑意图
                forgetHistory(device)
                return@withContext DeviceUnbindResult.Success
            }
            val info = activeDevices[deviceName]
            if (info == null) {
                // 设备表里已没有它（停服/拔出清理已完成）：连接与状态无需再动，
                // 但用户这次点击仍是解绑意图，历史记录必须删掉。解绑目标已达成，
                // 按幂等成功上报，避免"记录已删却提示失败"
                forgetHistory(device)
                return@withContext DeviceUnbindResult.Success
            }

            // native 侧已无此设备（如已被拔出清理）时 Kotlin 侧连接和状态同样要清，
            // 否则连接泄漏、UI 一直显示已绑定
            fun cleanup() {
                // 先摘登记再关连接：close 出任何问题都不影响摘除与状态更新，
                // 否则 UI 永久显示"已绑定"而 native 侧已解绑（closeAllDevices 同款处理）
                val removed = activeDevices.remove(deviceName)
                // 用户主动解绑才删历史记录；拔出与停服路径不走这里。
                // 按设备自身的 VID/PID 删除，不依赖 activeDevices：停服清空设备表后
                // 用户再点解绑（过渡窗口）时记录仍要被删掉，否则下次启动自动绑定
                // 会把刚解绑的设备拉回来。删除失败不能跳过后面的连接关闭与状态清理
                forgetHistory(device)
                runCatching { removed?.connection?.close() }
                    .onFailure { e -> Log.w(TAG, "close device failed", e) }
                _state.update { it.copy(boundDevices = it.boundDevices - deviceName) }
            }

            // 异常时保持登记不动：设备可能仍在 native 侧绑定着，清掉 Kotlin 记录
            // 会让它变成谁也解不了的孤儿，保留登记调用方还能重试
            try {
                when (val result = UsbIpNative.unbindUsbDevice(info.fd)) {
                    DeviceUnbindResult.Success -> {
                        cleanup()
                        Log.i(TAG, "Device unbound: $deviceName")
                        result
                    }
                    // 服务器已不在/设备已消失：Kotlin 侧连接和状态照清，并按幂等
                    // 成功上报——解绑目标（连接关、状态清）已达成，报 Failure 会让
                    // UI 提示"解绑失败"而实际已清，自相矛盾
                    DeviceUnbindResult.Failure.DeviceNotFound,
                    DeviceUnbindResult.Failure.ServerNotRunning -> {
                        cleanup()
                        Log.w(TAG, "Device already gone in native: $deviceName")
                        DeviceUnbindResult.Success
                    }
                    DeviceUnbindResult.Failure.DeviceInUse -> result
                    else -> {
                        Log.e(TAG, "Unknown unbind error: $result for $deviceName")
                        result
                    }
                }
            } catch (t: CancellationException) {
                // 同 bindDevice：取消必须向上传播，不能转成普通失败结果
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "unbindDevice failed unexpectedly: $deviceName", t)
                DeviceUnbindResult.Failure.UnknownError
            }
        }
    }

    /**
     * 设备拔出清理：整块在 native 线程执行，先通知 native 清理再关 Kotlin 连接
     * （notify_device_removed 同步移除设备或触发会话停止；物理拔出后 fd 已失效，
     * 且 native 的 libusb handle 持有独立 fd，close 互不影响）。
     * 只由 Service 自己的接收器调用，UI 不再参与清理
     */
    private suspend fun handleDeviceDetached(usbDevice: UsbDevice) {
        // 同 bindDevice/unbindDevice 用 serviceNativeContext：登记、native 通知、
        // 状态更新是一次清理事务，不被调用方取消打断。
        // 事件也在块内发出：块外是协程恢复点，Service 恰好在销毁时事件就发不出去
        withContext(serviceNativeContext) {
            val info = activeDevices.remove(usbDevice.deviceName)
            if (info == null) {
                if (!_events.tryEmit(Event.DeviceDetached(usbDevice, false))) {
                    Log.w(TAG, "DeviceDetached event dropped (buffer full): ${usbDevice.deviceName}")
                }
                return@withContext
            }
            // native 通知失败也要把 Kotlin 侧清理做完：设备已物理拔出，连接和状态
            // 必须一并清掉，否则该设备永远显示"已绑定"
            try {
                UsbIpNative.notifyDeviceRemoved(info.busid)
            } catch (t: CancellationException) {
                // 同 bindDevice：取消必须向上传播，不能转成普通失败结果
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "notifyDeviceRemoved failed: ${usbDevice.deviceName}", t)
            } finally {
                // close 兜异常：失败不能跳过后面的状态清理与事件，否则 UI 会把
                // 已拔出的设备永久显示为"已绑定"
                runCatching { info.connection.close() }
                    .onFailure { e -> Log.w(TAG, "close device failed", e) }
                _state.update { it.copy(boundDevices = it.boundDevices - usbDevice.deviceName) }
                // 事件与状态更新同生共死：取消异常会跳过 finally 之后的语句，
                // 那样 state 已清而 UI 收不到"设备已拔出"的提示。tryEmit 挤掉
                //（buffer 满，UI 主线程被长任务卡住的极端情形）只丢提示、状态
                // 已清——记一行便于排查"没有提示"的反馈
                if (!_events.tryEmit(Event.DeviceDetached(usbDevice, true))) {
                    Log.w(TAG, "DeviceDetached event dropped (buffer full): ${usbDevice.deviceName}")
                }
            }
            Log.i(TAG, "Device detached: ${usbDevice.deviceName}")
        }
    }

    // 只在 nativeDispatcher 线程调用。"关设备"与"清绑定列表"在这里捆绑：调用点
    //（stopServer/onDestroy 清理）不必各自记得清 _state——否则 closeAllDevices
    // 之后 UI 会一直显示已绑定而连接早已关闭（两者经 _state 是同一真相源）。
    // 逐个 close 兜异常：单个失败不应中断其余设备的关闭与状态清理
    private fun closeAllDevices() {
        // 防误用断言：拦最现实的误调路径——主线程（serviceScope 是 Main.immediate，
        // 在里面直接调用会命中）。完整的"必须持有 nativeDispatcher 许可"校验
        // 需要暴露调度器，不为假设场景引入
        check(Looper.myLooper() != Looper.getMainLooper()) { "closeAllDevices must not run on the main thread" }
        activeDevices.values.forEach {
            runCatching { it.connection.close() }
                .onFailure { e -> Log.w(TAG, "close device failed", e) }
        }
        activeDevices.clear()
        _state.update { it.copy(boundDevices = emptyMap()) }
        Log.i(TAG, "All devices closed")
    }

    /**
     * 拨出的唯一驱动：服务器运行中 + 设置开启 + 主机有效时，持有一个 [DialOut]，
     * 其循环数跟随已绑定设备数（0 台则不拨）；条件不满足或配置变了就停掉重建。
     * 触发源：服务器启停、绑定数变化、拨出设置变化。
     */
    private fun startDialOutController() {
        val inputs = combine(
            state.map { Triple(it.serverRunning, it.port, it.boundDevices.size) }.distinctUntilChanged(),
            settings.values.map { Triple(it.dialOutEnabled, it.dialHost, it.dialPort) }.distinctUntilChanged(),
            // No key, no dialing: a missing, invalidated or unsupported key all stop it
            DialInKey.state.map { it == DialInKey.State.PRESENT }.distinctUntilChanged()
        ) { server, dial, keyPresent -> Triple(server, dial, keyPresent) }
        serviceScope.launch {
            inputs.collect { (server, dial, keyPresent) ->
                val (running, localPort, deviceCount) = server
                val (enabled, host, port) = dial
                // 套接字操作与线程创建不放主线程；applyDialTarget 自带 dialLock
                withContext(Dispatchers.IO) {
                    val usable = dialTargetUsable(running, enabled, host, port, keyPresent)
                    applyDialTarget(if (usable) DialTarget(host, port, localPort) else null, deviceCount)
                }
            }
        }
    }

    // The server's connection filter only admits dial-out's registered ports
    private val nativeDialPorts = object : DialPortRegistrar {
        override fun register(port: Int) = UsbIpNative.registerDialPort(port)
        override fun unregister(port: Int) = UsbIpNative.unregisterDialPort(port)
    }

    private data class DialTarget(val host: String, val port: Int, val localPort: Int)

    private fun applyDialTarget(target: DialTarget?, deviceCount: Int) {
        synchronized(dialLock) {
            if (destroying) return
            val current = dialOut
            if (current != null &&
                (target == null || current.host != target.host || current.port != target.port ||
                    current.localPort != target.localPort)
            ) {
                // 停掉旧实例：它的线程随后自行收尾，观察协程等到它清空后退出
                current.stop()
                dialOut = null
                _dialLoops.value = emptyList()
                Log.i(TAG, "Dial-out stopped")
            }
            if (target == null) return
            val dial = dialOut ?: DialOut(
                host = target.host,
                port = target.port,
                localPort = target.localPort,
                registrar = nativeDialPorts,
                authenticator = DialInKey.authenticator,
                vpn = AndroidVpnNetworkProvider(this),
                log = { Log.i(TAG, "dial-out: $it") }
            ).also {
                dialOut = it
                Log.i(TAG, "Dial-out to ${target.host}:${target.port}, local server port ${target.localPort}")
                observeDialOut(it)
            }
            dial.setLoopCount(deviceCount)
        }
    }

    // 把一个 DialOut 的循环状态转给 UI，并据此维护唤醒锁。实例停止且线程清空后退出。
    // 在 serviceScope（主线程）上收集：dialBusy 与唤醒锁都只在主线程动
    private fun observeDialOut(dial: DialOut) {
        serviceScope.launch {
            dial.loopStatuses.collect { list ->
                dialBusy[dial] = list.isNotEmpty()
                if (synchronized(dialLock) { dialOut === dial }) {
                    _dialLoops.value = list
                    updateAuthNotification(list.any { it.phase == LoopStatus.Phase.LOCKED })
                } else if (list.isEmpty()) {
                    // The replaced instance is gone; the current one decides the notification
                    if (synchronized(dialLock) { dialOut == null }) updateAuthNotification(false)
                }
                updateWakeLock()
                if (dial.isStopped && list.isEmpty()) {
                    dialBusy.remove(dial)
                    updateWakeLock()
                    cancel()
                }
            }
        }
    }

    // 有任何拨出循环存在就持有、没有就释放。不设超时：释放由"循环清空"和
    // onDestroy 保证，带超时反而会在长时间拨出中悄悄失效
    @SuppressLint("WakelockTimeout")
    private fun updateWakeLock() {
        val need = dialBusy.values.any { it }
        val held = wakeLock?.isHeld == true
        if (need && !held) {
            val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usbipdcpp:dialout")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            lock.acquire()
            Log.i(TAG, "Wake lock held")
        } else if (!need && held) {
            wakeLock?.release()
            Log.i(TAG, "Wake lock released")
        }
    }

    private fun stopDialOutForDestroy() {
        synchronized(dialLock) {
            dialOut?.stop()
            dialOut = null
        }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
            .onFailure { e -> Log.w(TAG, "wake lock release failed", e) }
    }

    // Posted while any loop is LOCKED (the key's window after the fingerprint is
    // over, so nothing is dialed); cleared when none is. Opening the app and
    // passing the fingerprint prompt opens the window, and the loops notice by
    // themselves within DialOut.LOCKED_PROBE_MS. Main thread only
    private var authNotified = false

    private fun updateAuthNotification(needed: Boolean) {
        if (needed == authNotified) return
        authNotified = needed
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!needed) {
            manager.cancel(AUTH_NOTIFICATION_ID)
            return
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            AUTH_NOTIFICATION_ID,
            NotificationCompat.Builder(this, AUTH_CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notify_fingerprint_needed))
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(AUTH_CHANNEL_ID, getString(R.string.notify_channel_auth), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "USB/IP Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            // 动态显示运行状态：服务器未运行时提示已停止，避免误导
            .setContentText(getString(if (state.value.serverRunning) R.string.server_running else R.string.server_stopped))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }
}
