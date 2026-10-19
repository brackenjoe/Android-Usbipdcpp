package com.yunsmall.usbipdcpp.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.yunsmall.usbipdcpp.AppSettings
import com.yunsmall.usbipdcpp.DeviceBindResult
import com.yunsmall.usbipdcpp.DeviceUnbindResult
import com.yunsmall.usbipdcpp.LogCallback
import com.yunsmall.usbipdcpp.R
import com.yunsmall.usbipdcpp.SharedDeviceStore
import com.yunsmall.usbipdcpp.UsbIpNative
import com.yunsmall.usbipdcpp.UsbPermissionManager
import com.yunsmall.usbipdcpp.UsbService
import com.yunsmall.usbipdcpp.util.displayName
import com.yunsmall.usbipdcpp.util.getDeviceIpAddress
import com.yunsmall.usbipdcpp.util.isCameraDevice
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 沿用拆分前的 "MainActivity" 标签：logcat 过滤习惯不受文件拆分影响
private const val TAG = "MainActivity"

// 本文件命名约定：deviceName 一律指 UsbDevice.deviceName（系统设备名，各表的
// 键）；给用户显示的名字（productName）统一叫 displayName

// 相机权限 pending 的陈旧判定阈值：进程被杀后系统对话框可能已消失、授权结果
// 永远不会投递，saved state 恢复出的 pending 收不到任何复位路径、会一直挡住
// 相机设备的绑定；超过该时限由用户下一次点击触发接管（见 onBindDevice）
private const val CAMERA_PENDING_TIMEOUT_MS = 60_000L

// 用 DateTimeFormatter 而非 SimpleDateFormat：后者非线程安全，虽然 addLog 目前
// 只在主线程调用（native 回调经 mainHandler.post、手动刷新在主线程），但不必给
// 调用方留这个隐性前提；每行日志一次格式化的热路径上同样复用实例
private val logTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

fun setLanguage(language: String) {
    val localeList = if (language == "system") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(language)
    }
    AppCompatDelegate.setApplicationLocales(localeList)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    usbManager: UsbManager,
    permissionManager: UsbPermissionManager,
    usbService: UsbService?
) {
    var isStarting by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    // 用 rememberSaveable：旋转不该丢掉用户刚输入的端口
    var portText by rememberSaveable { mutableStateOf(UsbService.DEFAULT_PORT.toString()) }
    // 日志不用 rememberSaveable：旋转丢日志无害（native 日志仍在持续产生），
    // 把最多 500 条字符串序列化进 Bundle 是纯开销
    val logBuffer = remember { LogBuffer() }
    var devices by remember { mutableStateOf(mapOf<String, UsbDevice>()) }
    var showFullLog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    // saveable：切换语言会重建 Activity，否则设置对话框会被关掉
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // 在途设备集合（Set 而非单值：允许多台设备同时发起绑定/解绑，逐设备
    // 显示进度并禁用按钮；改成单值会破坏并发语义）
    var busyDevices by remember { mutableStateOf(setOf<String>()) }
    // 存设备名而非 UsbDevice 对象（非 Parcelable 无法存 SavedState）：
    // 权限对话框期间系统回收 Activity（如"不保留活动"）后重建时，
    // remember 状态会丢，rememberSaveable 保证授权回调仍能找到待绑定设备。
    // 常规路径靠系统投递兜底（跨重建、跨进程恢复都会重新送达），launch 抛异常
    // 的路径下面已复位；但进程被杀后对话框可能已消失、结果永远不来，恢复出的
    // pending 会卡住相机设备的绑定，onBindDevice 里用受理时间戳判陈旧接管
    var pendingBindDeviceName by rememberSaveable { mutableStateOf<String?>(null) }
    // 受理时间（elapsedRealtime）：只服务于上面的陈旧判定。同一开机内单调，
    // 进程被杀重建（Bundle 恢复）时基准不变；跨设备重启时任务栈随重启清空，
    // 不存在跨基准恢复出的旧值
    var pendingBindSubmittedAt by rememberSaveable { mutableStateOf(0L) }

    // Service 的状态是唯一真相源，UI 只观察：不再自己存一份 serverRunning/
    // boundDevices 并在每个变更点手动刷新。Service 未绑定时用空状态占位：
    // 只读，用 flowOf 表达不可变；remember 保持实例稳定，否则 collectAsState
    // 会在每次重组因新 flow 实例重启收集
    val emptyServiceState = remember { flowOf(UsbService.UiState()) }
    // collectAsState 返回的 State 对象跨重组稳定，委托读取每次都是当前值；
    // 且 rememberLauncherForActivityResult 的回调经 rememberUpdatedState 转发，
    // 调用的永远是最新组合的 performBind——两条路都读不到过期的 nativeReady
    val serviceState by (usbService?.state ?: emptyServiceState).collectAsState(UsbService.UiState())
    val serverRunning = serviceState.serverRunning
    val boundDevices = serviceState.boundDevices

    // 启动成功后回填实际使用的端口；只在运行中回填，否则旋转重建时会把用户
    // 已经输入但还没提交的端口覆盖回默认值（rememberSaveable 就白搭了）
    LaunchedEffect(serviceState.port, serverRunning) {
        if (serverRunning) {
            portText = serviceState.port.toString()
        }
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appSettings = remember { AppSettings(context) }
    val sharedDeviceStore = remember { SharedDeviceStore(context) }
    // 开关初值取持久化设置，变更即时写回
    var autoBindRemembered by rememberSaveable { mutableStateOf(appSettings.autoBindRememberedDevices) }
    // 本次插入周期内已尝试自动绑定的设备：权限被拒或绑定失败时不反复弹窗；
    // 拔出后设备名从设备表消失，重插时会重新尝试。saveable：旋转重建后
    // 不能把用户刚拒绝过的权限对话框再弹一遍
    var autoBindAttempted by rememberSaveable { mutableStateOf(setOf<String>()) }
    // 授权回调路径横跨权限对话框与 Activity 重建，经 rememberUpdatedState 读最新
    // usbService：直接读快照有拿到服务绑定前 null 的风险，绑定必然失败
    val currentUsbService by rememberUpdatedState(usbService)

    // 服务/native 就绪校验的唯一入口：发起绑定前与权限结果回来后共用，判断与
    // 文案不会各改各的。nativeReady 直读 service.state.value 而非组合快照
    // serviceState：usbService 刚连上的组合帧内，快照还是旧 flow 的旧值
    fun requireReadyService(): UsbService? {
        val service = currentUsbService ?: run {
            Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
            return null
        }
        // native 初始化失败时绑定无意义：明确提示，而非等 native 返回模糊错误
        if (!service.state.value.nativeReady) {
            Toast.makeText(context, context.getString(R.string.native_init_failed), Toast.LENGTH_SHORT).show()
            return null
        }
        return service
    }

    // 执行设备绑定（USB 权限 + native 绑定）。请求受理后记为本次插入已尝试；
    // 未受理（服务未就绪/同设备已有在途请求）不记，下次列表刷新还能再试
    fun performBind(device: UsbDevice, notifyPending: Boolean = true) {
        // 先校验再发请求：服务/native 不可用时不该先把权限对话框弹给用户；
        // bindAfterPermission 里会重新校验（结果可能跨重建抵达）
        if (requireReadyService() == null) {
            return
        }
        // 结果经 onPermissionResult 监听器回来（已有权限时为同步交付）
        if (!permissionManager.requestPermission(device)) {
            // 请求未受理：同设备已有待处理的权限请求。自动路径不提示——
            // 手动请求在途时它必然返回 false，提示只是噪音
            if (notifyPending) {
                Toast.makeText(context, context.getString(R.string.permission_request_pending), Toast.LENGTH_SHORT).show()
            }
            return
        }
        autoBindAttempted = autoBindAttempted + device.deviceName
    }

    // 权限通过后的绑定段。不做成发起请求时的内嵌闭包：USB 权限对话框打开
    // 期间 Activity 可能重建（旋转/系统回收），结果要由重建后的组合接着处理
    fun bindAfterPermission(device: UsbDevice) {
        // 结果可能跨重建抵达，service 与 native 状态重新取当前值再校验
        //（校验规则与入口见 requireReadyService——与 performBind 共用同一实现，
        // 不是重复代码）
        val service = requireReadyService() ?: return
        // displayName 是给用户看的名字；device.deviceName 是系统设备名（键）
        val displayName = device.displayName(context)
        scope.launch {
            busyDevices = busyDevices + device.deviceName
            try {
                val result = service.bindDevice(usbManager, device)
                // 绑定状态由 Service 的 StateFlow 自动传播到 UI，不用手动同步
                when (result) {
                    is DeviceBindResult.Success -> {
                        Toast.makeText(context, context.getString(R.string.bind_success, displayName), Toast.LENGTH_SHORT).show()
                    }
                    is DeviceBindResult.Failure -> {
                        Toast.makeText(context, context.getString(R.string.bind_failed, result.getMessage(context)), Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                busyDevices = busyDevices - device.deviceName
            }
        }
    }

    // 曾共享设备的自动恢复：设备插入触发列表刷新后，命中历史记录且当前未绑定的
    // 设备自动走绑定流程（含权限请求）。只在服务器运行时做——bind 需要 native
    // 服务器存在；attempted 保证一次插入只尝试一次。服务器每次启动清空尝试记录：
    // 停服会清空绑定，仍插着的设备需要一次重新绑定的机会
    // 服务器每次成功启动（停服再启、进程重建后的启动）清空尝试记录，让仍插着的
    // 历史设备重新获得一次自动绑定机会。startMarker 由 Service 生成：旋转重建
    // 不会改变它，不会误清；也不能改用 UI 自己保存的 serverRunning 状态推断，
    // 那会把进程重建后的启动误判成"一直在运行"。清空放在自动绑定块内而不是
    // 单独的 effect：保证"先清空、再遍历"的顺序不依赖 effect 启动次序
    var seenStartMarker by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(devices, serverRunning, autoBindRemembered, serviceState.startMarker) {
        if (serviceState.startMarker.isNotEmpty() && serviceState.startMarker != seenStartMarker) {
            seenStartMarker = serviceState.startMarker
            autoBindAttempted = emptySet()
        }
        if (!autoBindRemembered || !serverRunning) return@LaunchedEffect
        devices.values.forEach { device ->
            if (device.deviceName in boundDevices || device.deviceName in busyDevices) return@forEach
            if (device.deviceName in autoBindAttempted) return@forEach
            if (!sharedDeviceStore.isRemembered(device)) return@forEach
            // 相机设备手动路径会先要 CAMERA 权限；自动路径不替用户再弹一个
            // 运行时权限框，权限未授予时留给手动绑定走完整流程
            if (isCameraDevice(device) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            ) return@forEach
            performBind(device, notifyPending = false)
        }
    }

    // 相机权限 pending 的整体复位：设备名与时间戳是同一在途请求状态的两半，
    // 成对清理，避免陈旧判定读到不配对的半步
    fun clearPendingCameraBind() {
        pendingBindDeviceName = null
        pendingBindSubmittedAt = 0L
    }

    // 相机权限请求
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val deviceName = pendingBindDeviceName ?: return@rememberLauncherForActivityResult
        clearPendingCameraBind()
        // Activity 重建后从设备列表重新查找设备对象；授权期间设备被拔掉时给个提示，
        // 否则用户点完"允许"看起来毫无反应
        val device = usbManager.deviceList[deviceName] ?: run {
            Toast.makeText(context, context.getString(R.string.device_unavailable), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        if (granted) {
            performBind(device)
        } else {
            // 用户主动拒绝相机权限：文案与"设备不可用"区分，避免误导
            Toast.makeText(context, context.getString(R.string.camera_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // logBuffer 是 remember 的实例：闭包捕获的是实例引用而非值快照，
    // DisposableEffect 里首次组合捕获的本函数实例读写的一直是当前状态，
    // 无需 rememberUpdatedState 转发
    fun addLog(message: String) {
        logBuffer.append("[${LocalTime.now().format(logTimeFormat)}] $message")
    }

    fun refreshDevices() {
        val newDevices = permissionManager.getDeviceList()
        // 每次刷新都按刚取到的真实设备集合裁剪"已尝试"记录：拔出后重插才能
        // 重新自动绑定。不能用 devices 状态做裁剪——重建后它初始为空，
        // 会把 Saveable 恢复出的记录误清
        autoBindAttempted = autoBindAttempted.intersect(newDevices.keys)
        devices = newDevices
        addLog("Found ${newDevices.size} USB device(s)")
    }

    val ipAddress = remember { mutableStateOf<String?>(null) }

    // 获取IP地址
    LaunchedEffect(serverRunning) {
        if (serverRunning) {
            // 网络接口枚举可能耗时（多虚拟网卡时），放 IO 线程避免卡主线程
            ipAddress.value = withContext(Dispatchers.IO) { getDeviceIpAddress() }
        } else {
            // 停止后清掉：旧地址在下次启动前已无意义，留着是过期状态
            ipAddress.value = null
        }
    }

    // 设置native日志回调（注册/清除与主线程转发见 NativeLogBridge）
    NativeLogBridge { addLog(it) }

    // 首次组合刷新一次设备列表；之后由插拔广播与手动刷新按钮驱动
    LaunchedEffect(Unit) { refreshDevices() }

    // 监听USB设备插入/拔出（通过BroadcastReceiver）
    DisposableEffect(permissionManager) {
        permissionManager.setOnDeviceListChangedListener {
            refreshDevices()
        }
        onDispose {
            permissionManager.setOnDeviceListChangedListener(null)
        }
    }

    // USB 权限结果：发起请求的组合可能已经不在了（对话框期间旋转/系统回收，
    // 旧回调随组合作废），结果到达时在本组合接着绑定，与相机权限路径的
    // 跨重建处理对齐。key 用 permissionManager 即可：监听器不捕获 usbService
    // 快照，绑定段经 currentUsbService（rememberUpdatedState）读最新绑定
    DisposableEffect(permissionManager) {
        permissionManager.setOnPermissionResultListener { device, granted ->
            if (!granted) {
                // 手动与自动路径共用这份记录：拒绝后本次插入不再自动请求权限
                autoBindAttempted = autoBindAttempted + device.deviceName
                // 用户主动拒绝权限，不能报"设备不可用"——既误导又会让人以为设备坏了
                Toast.makeText(context, context.getString(R.string.usb_permission_denied), Toast.LENGTH_SHORT).show()
                return@setOnPermissionResultListener
            }
            // 授权期间设备被拔掉时给个提示（同相机权限回调）：从当前设备列表
            // 重新查找设备对象，找不到说明已不在
            val current = usbManager.deviceList[device.deviceName]
            if (current == null) {
                Toast.makeText(context, context.getString(R.string.device_unavailable), Toast.LENGTH_SHORT).show()
                return@setOnPermissionResultListener
            }
            bindAfterPermission(current)
        }
        onDispose {
            permissionManager.setOnPermissionResultListener(null)
        }
    }

    // 拔出提示：设备清理由 Service 统一处理（它的接收器在 Activity 销毁后仍有效），
    // UI 只消费事件弹提示，绑定状态经 serviceState 自动更新
    LaunchedEffect(usbService) {
        usbService?.events?.collect { event ->
            when (event) {
                is UsbService.Event.DeviceDetached -> {
                    if (event.wasBound) {
                        val displayName = event.device.displayName(context)
                        Toast.makeText(context, context.getString(R.string.device_detached, displayName), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            MainTopBar(
                onSettingsClick = { showSettings = true },
                onAboutClick = { showAbout = true },
                onLibraryClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/yunsmall/usbipdcpp")
                            )
                        )
                    }.onFailure { e ->
                        // 无浏览器等场景不能静默：至少留日志
                        Log.e(TAG, "Failed to open usbipdcpp page", e)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ServerControlPanel(
                serverRunning = serverRunning,
                isStarting = isStarting,
                isStopping = isStopping,
                portText = portText,
                onPortChange = { portText = it },
                onStart = {
                    // 端口越界（如 65536）会在 native 侧按 unsigned short 回绕成别的
                    // 端口，UI 显示与实际监听不一致，这里先拦下
                    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                    if (port == null) {
                        Toast.makeText(context, context.getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
                        return@ServerControlPanel
                    }
                    // 服务/native 就绪校验统一走 requireReadyService：与绑定、
                    // 解绑路径同一个入口（校验规则与提示文案不各改各的），
                    // 服务引用经 currentUsbService 读最新绑定，不再单独取快照
                    val service = requireReadyService() ?: return@ServerControlPanel
                    isStarting = true
                    scope.launch {
                        // finally 复位：协程被取消（组合销毁）时不残留"启动中"——
                        // 重建虽会重新初始化状态，但结构上不留半截
                        try {
                            // 启动结果由 Service 的 StateFlow 反映，成功后 UI 自动切到运行态；
                            // 失败（如端口被占用）状态不变，必须显式提示，否则用户只看到按钮弹回
                            val started = service.startServer(port)
                            if (!started) {
                                Toast.makeText(context, context.getString(R.string.start_failed), Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            isStarting = false
                        }
                    }
                },
                onStop = {
                    // 同 onStart：就绪校验走唯一入口 requireReadyService
                    val service = requireReadyService() ?: return@ServerControlPanel
                    isStopping = true
                    scope.launch {
                        try {
                            // 停止后 serverRunning/绑定列表由 Service 的 StateFlow 清空
                            service.stopServer()
                        } finally {
                            // 同 onStart：取消（组合销毁）时不残留"停止中"
                            isStopping = false
                        }
                    }
                }
            )

            StatusCard(
                serverRunning = serverRunning,
                boundCount = boundDevices.size,
                ipAddress = ipAddress.value,
                // 显示端口直接取状态源：portText 是输入框草稿（LaunchedEffect
                // 回填前的过渡帧会短暂偏离实际监听端口）
                port = serviceState.port
            )

            DeviceListSection(
                devices = devices,
                boundDevices = boundDevices,
                busyDevices = busyDevices,
                onBindDevice = { device ->
                    if (!serverRunning) {
                        Toast.makeText(context, context.getString(R.string.please_start_server), Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    // 用 latest 引用检查：与后面 performBind 的读取来源一致
                    if (currentUsbService == null) {
                        Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    // 摄像头设备需要先获取 CAMERA 权限
                    if (isCameraDevice(device) &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    ) {
                        if (pendingBindDeviceName != null) {
                            // 陈旧判定：进程被杀后系统对话框可能已消失、授权结果
                            // 永远不会投递，超时视为陈旧，清掉后按新请求继续
                            if (SystemClock.elapsedRealtime() - pendingBindSubmittedAt > CAMERA_PENDING_TIMEOUT_MS) {
                                clearPendingCameraBind()
                            } else {
                                // 请求在途：忽略新的，防止 pendingBindDeviceName
                                // 被覆盖导致授权后绑错设备；提示一下，静默忽略
                                // 会被当成点击没生效
                                Toast.makeText(context, context.getString(R.string.permission_request_pending), Toast.LENGTH_SHORT).show()
                                return@DeviceListSection
                            }
                        }
                        pendingBindDeviceName = device.deviceName
                        pendingBindSubmittedAt = SystemClock.elapsedRealtime()
                        try {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        } catch (e: Exception) {
                            // launch 抛异常不会有任何回调，不复位会让该设备一直
                            // 卡在"已有待处理请求"（rememberSaveable 跨重建保留）
                            clearPendingCameraBind()
                            Log.e(TAG, "Failed to launch camera permission request", e)
                        }
                    } else {
                        performBind(device)
                    }
                },
                onUnbindDevice = { device ->
                    // 同 onStart：就绪校验走唯一入口 requireReadyService
                    val service = requireReadyService() ?: return@DeviceListSection
                    val displayName = device.displayName(context)
                    scope.launch {
                        busyDevices = busyDevices + device.deviceName
                        try {
                            val result = service.unbindDevice(device)
                            // 解绑状态由 Service 的 StateFlow 自动传播到 UI
                            when (result) {
                                is DeviceUnbindResult.Success -> {
                                    Toast.makeText(context, context.getString(R.string.unbind_success, displayName), Toast.LENGTH_SHORT).show()
                                }
                                is DeviceUnbindResult.Failure -> {
                                    Toast.makeText(context, context.getString(R.string.unbind_failed, result.getMessage(context)), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } finally {
                            busyDevices = busyDevices - device.deviceName
                        }
                    }
                },
                onRefresh = { refreshDevices() }
            )

            LogSection(
                logMessages = logBuffer.messages,
                logVersion = logBuffer.version,
                onClear = { logBuffer.clear() },
                onViewFullLog = { showFullLog = true },
                onCopyLog = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Log", logBuffer.messages.joinToString("\n")))
                    Toast.makeText(context, context.getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            autoBindRemembered = autoBindRemembered,
            onAutoBindChange = { checked ->
                autoBindRemembered = checked
                appSettings.autoBindRememberedDevices = checked
                // 重新打开时清空尝试记录：用户显式期望自动绑定重新生效，
                // 之前被拒/失败的设备也应获得新机会
                if (checked) autoBindAttempted = emptySet()
            },
            sharedDeviceStore = sharedDeviceStore,
            onDismiss = { showSettings = false }
        )
    }

    if (showFullLog) {
        FullLogDialog(
            logMessages = logBuffer.messages,
            logVersion = logBuffer.version,
            onDismiss = { showFullLog = false }
        )
    }

    if (showAbout) {
        // 当前包名查不到自己的信息理论上不可能，但规范上还是防御一下
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get package info", e)
            "unknown"
        }
        val githubUrl = "https://github.com/yunsmall/Android-Usbipdcpp"
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.about_description))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.about_version, version))
                    Text(stringResource(R.string.about_license))
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            // 无浏览器等场景不能崩：与顶栏"关于 usbipdcpp"同一处理
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                            }.onFailure { e ->
                                Log.e(TAG, "Failed to open GitHub page", e)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.about_github), color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

// native 日志回调的桥接：注册/清除与主线程转发都收在这里。addLog 经 lambda
// 传入（闭包捕获 remember 的 logBuffer 实例）：DisposableEffect(Unit) 只在
// 首次组合注册一次，捕获首次组合的 lambda 也无碍——其行为只依赖稳定的
// logBuffer，不读取易变的组合快照
@Composable
private fun NativeLogBridge(addLog: (String) -> Unit) {
    DisposableEffect(Unit) {
        // onLog 由 native 日志线程回调，直接更新 Compose 状态会跨线程写，
        // 切到主线程再执行
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : LogCallback {
            override fun onLog(level: Int, message: String) {
                // native pattern 的输出末尾一般无空白，trim 是防御性清理；
                // onDispose 后队列里可能残留少量已 post 的任务：它们只写已销毁
                // 组合的状态（无观察者、不触发重组），无需追踪撤销
                mainHandler.post { addLog(message.trim()) }
            }
        }
        UsbIpNative.setLogCallback(callback)
        onDispose {
            // 必须清回调：旋转重建时新 setLogCallback 会替换旧引用（无需清理），
            // 但应用退后台（Activity 销毁、不再有新回调）时不清的话，JNI 的
            // 全局引用会一直持有旧 Activity，导致泄漏。
            // 旋转时旧实例的销毁必然先于新实例创建（系统重建顺序固定），此处
            // 清除不会误清新回调；分屏双开等反向时序下最坏只是回调被清空（日志
            // 停止刷新，旋转/重进即恢复），无正确性影响，不值得加清理令牌。
            // 清完回调再清队列：队列里已 post 的残留日志任务只会写已销毁的
            // 组合状态
            UsbIpNative.setLogCallback(null)
            mainHandler.removeCallbacksAndMessages(null)
        }
    }
}

// 顶栏：标题、设置入口与溢出菜单（关于本应用、关于 usbipdcpp）。溢出菜单的
// 展开状态局部化在这里；"关于"弹窗是 MainScreen 的状态，经回调传出。
// 文字按钮改图标：窄屏上三个文本按钮会挤压标题
// TopAppBar 是实验性 API，opt-in 必须挂在调用点所在的函数上——
// MainScreen 上的那份 @OptIn 只覆盖它自己的函数体，管不到这里
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onLibraryClick: () -> Unit
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.app_title)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
            }
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.about)) },
                        onClick = {
                            showOverflowMenu = false
                            onAboutClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.about_usbipdcpp)) },
                        onClick = {
                            showOverflowMenu = false
                            onLibraryClick()
                        }
                    )
                }
            }
        }
    )
}

// 设置对话框：自动绑定开关与已记住设备的管理。用对话框而不是独立页面；
// 设置项多了再抽页面
@Composable
private fun SettingsDialog(
    autoBindRemembered: Boolean,
    onAutoBindChange: (Boolean) -> Unit,
    sharedDeviceStore: SharedDeviceStore,
    onDismiss: () -> Unit
) {
    // 对话框每次打开时重新读取：关闭后组合销毁，下次打开拿到最新记录
    var remembered by remember { mutableStateOf(sharedDeviceStore.rememberedDevices()) }
    // 语言与自动绑定开关一样即时生效；AppCompat 的语言变更会重建 Activity。
    // 跟随系统时 getApplicationLocales() 为空、toLanguageTags() 也是空串，
    // 必须用资源配置里的实际语言判断，否则中文系统会错误显示 English
    val context = LocalContext.current
    val appLocales = AppCompatDelegate.getApplicationLocales()
    val currentLanguageTag = if (appLocales.isEmpty) {
        // 用 AppCompat 的系统默认 locale 列表：跨 API 级别，不直接碰
        // Configuration.locales（API 24+）
        LocaleListCompat.getDefault().toLanguageTags()
    } else {
        appLocales.toLanguageTags()
    }
    var language by rememberSaveable {
        mutableStateOf(if (currentLanguageTag.startsWith("zh")) "zh" else "en")
    }
    var showLanguageMenu by remember { mutableStateOf(false) }
    // 选项列表驱动 UI：以后加语言只需往这里加一项
    val languageOptions = listOf(
        "en" to R.string.language_en,
        "zh" to R.string.language_zh
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            // 内容整体可滚动：记录多时对话框不会被撑出屏幕
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 通用地球符号：语言被切错时，用户即使看不懂界面文字，
                        // 也能认出这里是语言设置
                        Text("🌐", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.language))
                    }
                    Box {
                        OutlinedButton(onClick = { showLanguageMenu = true }) {
                            Text(
                                stringResource(
                                    languageOptions.firstOrNull { it.first == language }?.second
                                        ?: R.string.language_en
                                )
                            )
                        }
                        DropdownMenu(
                            expanded = showLanguageMenu,
                            onDismissRequest = { showLanguageMenu = false }
                        ) {
                            languageOptions.forEach { (tag, labelRes) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(labelRes)) },
                                    onClick = {
                                        setLanguage(tag)
                                        language = tag
                                        showLanguageMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.auto_bind_remembered_devices))
                        Text(
                            stringResource(R.string.auto_bind_remembered_devices_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = autoBindRemembered, onCheckedChange = onAutoBindChange)
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.remembered_devices),
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (remembered.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                sharedDeviceStore.clear()
                                remembered = sharedDeviceStore.rememberedDevices()
                            }
                        ) {
                            Text(
                                stringResource(R.string.clear_all),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                if (remembered.isEmpty()) {
                    Text(
                        stringResource(R.string.no_remembered_devices),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    remembered.forEach { device ->
                        RememberedDeviceRow(
                            device = device,
                            onDelete = {
                                sharedDeviceStore.forgetModel(device.vendorId, device.productId)
                                remembered = sharedDeviceStore.rememberedDevices()
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun RememberedDeviceRow(
    device: SharedDeviceStore.RememberedDevice,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "VID: ${device.vendorId.toString(16).uppercase()}  PID: ${device.productId.toString(16).uppercase()}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (device.serials.isNotEmpty()) {
                Text(
                    text = device.serials.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}
