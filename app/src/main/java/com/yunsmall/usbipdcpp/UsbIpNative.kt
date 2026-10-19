package com.yunsmall.usbipdcpp

import android.os.Looper
import android.util.Log
import androidx.annotation.WorkerThread
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

// 本文件三条纪律（细节与理由见各处注释）：
// 1. 所有 JNI 调用经 jniContext：native 侧串行 + 不可取消
//    （fork 例外：registerDialPort/unregisterDialPort 是同步直调，只碰
//    native 侧自己的端口表锁，见两者的注释）
// 2. 库加载守卫（libraryLoaded）只收在公开方法里，*Impl 不自行判断
// 3. 公开 suspend 方法自己切到 jniContext，调用方不必记得切
object UsbIpNative {
    private const val TAG = "UsbIpNative"

    // 加载放在类初始化里：访问本对象任意成员都会先触发这里，从而保证任何 JNI
    // 调用都在库加载之后——UI 首次组合里的 setLogCallback 早于 Service.onCreate
    // 执行时（冷启动竞态）也不会抛 UnsatisfiedLinkError。
    // 首次触达点（Service 构造、组合期 setLogCallback 等）都在主线程：加载是
    // dlopen 级别的毫秒级操作，对启动预算无实质影响，不做后台预热
    // 异常必须就地捕获：逃出类初始化会让 JVM 把这个类标记为初始化失败，之后
    // 每次访问都抛 NoClassDefFoundError，外层的 try-catch 也救不回来
    private val libraryLoaded: Boolean = try {
        System.loadLibrary("usbipdcpp_native")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Failed to load native library", e)
        false
    } catch (e: Throwable) {
        // 部分 ROM 的加载失败不是 UnsatisfiedLinkError（如 SecurityException）：
        // 漏出去会让类初始化失败，之后每次访问都变成 NoClassDefFoundError
        Log.e(TAG, "Unexpected error loading native library", e)
        false
    }

    // LibusbServer 内部没有锁，状态变量/容器并发访问会把状态搞乱，所有调用必须
    // 串行。下面每个 suspend 方法内部都切到这个 dispatcher，调用方不必自己记得
    // 切；需要"Kotlin 侧状态与 native 调用同临界区"的场景（UsbService 的设备表）
    // 仍可在外面再包一层同一 dispatcher 的 withContext——嵌套多层也不会重复派发
    // 或排队等待：withContext 发现新上下文的 interceptor 与本协程相同（按实例
    // 相等比较）时直接把 block 内联执行，连调度队列都不进，不依赖 LimitedDispatcher
    // 的队列行为。UsbService.serviceNativeContext 直接复用下方的 jniContext 实例：
    // 实例相等不靠"两处都记得写上 nativeDispatcher"这种约定维持
    private val nativeDispatcher = NativeTaskDispatcher(Dispatchers.IO.limitedParallelism(1))

    // 给限流 dispatcher 包一层"任务执行期线程标记"：runOnNativeThread 的误用检查
    //（在 native 任务内阻塞等待自己）需要知道"当前线程是否正跑在 native 任务里"，
    // 而限流器的许可持有状态不可观测，这个标记是唯一的运行时信号。包装类保持
    // 单实例参与协程上下文：withContext 的内联 fast path 按 interceptor 实例
    // 相等判定，jniContext 与其复用者（UsbService.serviceNativeContext）共用
    // 同一个包装实例，嵌套派发的内联行为不变
    private class NativeTaskDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private val inNativeTask = ThreadLocal<Boolean>()

        // 当前线程是否正在执行 native 任务：runOnNativeThread 的误用检查读它
        val isInNativeTask: Boolean
            get() = inNativeTask.get() == true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            // 标记由任务实际执行的线程设置（dispatch 的调用线程与 worker 线程
            // 可能不同）；remove 而非 set(false)：池线程长活，不留残值
            delegate.dispatch(context, Runnable {
                inNativeTask.set(true)
                try {
                    block.run()
                } finally {
                    inNativeTask.remove()
                }
            })
        }

        override fun isDispatchNeeded(context: CoroutineContext): Boolean =
            delegate.isDispatchNeeded(context)
    }

    // 所有 JNI 调用都在这个上下文执行：
    // - nativeDispatcher 保证串行（LibusbServer 无锁）
    // - NonCancellable 保证一旦开始就不会被取消：JNI 调用是原子的，被取消会
    //   让 native 与 Kotlin 两侧状态不一致——native 已经绑定设备而调用方拿不到
    //   结果，既没登记到设备表也没机会关掉已打开的 connection
    // internal：UsbService.serviceNativeContext 直接复用本实例——嵌套
    // withContext 走内联 fast path 的前提（interceptor 实例相等）由复用同一
    // 对象保证，而不是靠各调用点自觉组合出等价上下文
    internal val jniContext = nativeDispatcher + NonCancellable

    // ==================== JNI 实现，全部私有 ====================
    // 库没加载成功时 JNI 调用会抛 UnsatisfiedLinkError（冷启动崩溃的根因），
    // 守卫统一收在下面的公开方法里：分散到各调用点判断，漏一处就是崩溃
    private external fun nativeInitImpl(): Boolean
    private external fun setLogCallbackImpl(callback: LogCallback?)
    private external fun bindUsbDeviceImpl(fd: Int, vendorId: Int, productId: Int, outBusid: Array<String?>): Int
    private external fun unbindUsbDeviceImpl(fd: Int): Int
    private external fun notifyDeviceRemovedImpl(busid: String)
    private external fun startServerImpl(port: Int, loopbackOnly: Boolean): Int
    private external fun stopServerImpl()
    private external fun registerDialPortImpl(port: Int)
    private external fun unregisterDialPortImpl(port: Int)
    private external fun releaseImpl()

    // 错误码 - 必须与 JNI 层 usbipd_jni.cpp 中的 ErrorCode 命名空间保持一致
    private object ErrorCode {
        const val SUCCESS = 0
        const val DEVICE_NOT_FOUND = 1
        const val DEVICE_IN_USE = 2
        const val DEVICE_OPEN_FAILED = 3
        const val GET_DESCRIPTOR_FAILED = 4
        const val GET_CONFIG_FAILED = 5
        const val CLAIM_INTERFACE_FAILED = 6
        const val DEVICE_ALREADY_BOUND = 7
        const val HUB_FILTERED = 8
        const val SERVER_NOT_RUNNING = 9
        const val UNKNOWN_ERROR = 99
    }

    // ==================== 公开 API ====================

    /**
     * 初始化 native 层。返回 false 表示 native 不可用（库加载或 libusb 初始化失败），
     * 调用方必须跳过后续所有 native 操作
     */
    suspend fun init(): Boolean {
        if (!libraryLoaded) return false
        return withContext(jniContext) { nativeInitImpl() }
    }

    /**
     * 设置日志回调，传 null 表示清除（释放 JNI 全局引用）。
     * 由 UI 线程在组合/销毁时调用，必须同步执行（不能改成异步/suspend 派发）：
     * onDispose 的清除若排队执行，Activity 销毁后全局引用可能来不及释放；
     * native 侧实现同样必须保持非阻塞——这是组合路径上的同步调用，阻塞就是 ANR；
     * 库不可用时静默忽略
     */
    fun setLogCallback(callback: LogCallback?) {
        if (libraryLoaded) setLogCallbackImpl(callback)
    }

    suspend fun bindUsbDevice(fd: Int, vendorId: Int, productId: Int): DeviceBindResult {
        if (!libraryLoaded) return DeviceBindResult.Failure.UnknownError
        return withContext(jniContext) {
            val outBusid = arrayOfNulls<String>(1)
            val code = bindUsbDeviceImpl(fd, vendorId, productId, outBusid)
            if (code == ErrorCode.SUCCESS) {
                // JNI 层 SUCCESS 时必已写 busid，防御性检查防止未来实现改动导致 NPE
                val busid = outBusid[0]
                if (busid != null) {
                    DeviceBindResult.Success(busid)
                } else {
                    // 契约违约：native 声称成功却没交出 busid——交付失败（NewStringUTF/
                    // SetObjectArrayElement）时 native 会自行回滚并返回 UNKNOWN_ERROR，
                    // 走的是 else 分支，能进到这里说明 native 假报了成功，不是重复解绑。
                    // 调用方没有登记、稍后只会关连接，不主动解绑的话 native 侧留下谁也
                    // 解不了的孤儿。走 InContext 版本而非公开方法：已持有 jniContext
                    // 许可，再经公开方法的 withContext 会排队等待自己（不依赖内联
                    // fast path）；库加载守卫在公开层，本调用已在外层守卫之后
                    val rollback = unbindUsbDeviceInContext(fd)
                    // 分两路记，避免把"没得回滚"（DeviceNotFound 等）与成功混在同一
                    // 措辞里：回滚失败时 native 侧成孤儿且无人能解——busid 本就不可得，
                    // 只能等 release/重启服务器整体回收
                    if (rollback is DeviceUnbindResult.Success) {
                        Log.e(TAG, "bind returned SUCCESS but busid is null, rolled back by fd, fd=$fd")
                    } else {
                        Log.e(TAG, "bind returned SUCCESS but busid is null, rollback by fd failed: ${rollback::class.simpleName}, fd=$fd")
                    }
                    DeviceBindResult.Failure.UnknownError
                }
            } else {
                errorCodeToBindFailure(code)
            }
        }
    }

    suspend fun unbindUsbDevice(fd: Int): DeviceUnbindResult {
        if (!libraryLoaded) return DeviceUnbindResult.Failure.UnknownError
        return withContext(jniContext) { unbindUsbDeviceInContext(fd) }
    }

    // 不带 withContext 的版本：供已持有 jniContext 许可的调用方使用（bindUsbDevice
    // 的契约违约回滚）。在许可内再经公开方法嵌套 withContext 会排队等待自己——
    // 这里不依赖"同 dispatcher 内联 fast path"这类实现细节。库加载守卫由公开
    // 方法统一收口，调用方必须保证已过守卫且已在 jniContext 内
    private fun unbindUsbDeviceInContext(fd: Int): DeviceUnbindResult {
        return when (val code = unbindUsbDeviceImpl(fd)) {
            ErrorCode.SUCCESS -> DeviceUnbindResult.Success
            ErrorCode.DEVICE_NOT_FOUND -> DeviceUnbindResult.Failure.DeviceNotFound
            ErrorCode.DEVICE_IN_USE -> DeviceUnbindResult.Failure.DeviceInUse
            ErrorCode.SERVER_NOT_RUNNING -> DeviceUnbindResult.Failure.ServerNotRunning
            else -> {
                Log.e(TAG, "Unknown unbind error: $code")
                DeviceUnbindResult.Failure.UnknownError
            }
        }
    }

    /**
     * 通知 native 设备已消失。无结果返回：native 侧查无此 busid 属正常情形
     *（设备已被其他路径清理）；处理失败只记 native 日志。调用方按"拔出清理
     * 必须做完"的语义执行，不依赖此处的成败
     */
    suspend fun notifyDeviceRemoved(busid: String) {
        if (!libraryLoaded) return
        withContext(jniContext) { notifyDeviceRemovedImpl(busid) }
    }

    /**
     * 启动服务器。返回实际监听端口：成功启动或已在运行时都是当前真实端口
     *（已在运行时可能是先前启动的原端口，不是本次入参），失败返回 -1——
     * 调用方回填 state 的 port 必须用它，不能用入参推断。
     * loopbackOnly（fork 新增）：true 时只监听 127.0.0.1，false 时监听所有 IPv4 接口；
     * 服务器已在运行时本参数不生效（返回的仍是先前启动的端口），调用方自己记
     * 生效的值
     */
    suspend fun startServer(port: Int, loopbackOnly: Boolean): Int {
        if (!libraryLoaded) return -1
        return withContext(jniContext) { startServerImpl(port, loopbackOnly) }
    }

    suspend fun stopServer() {
        if (!libraryLoaded) return
        withContext(jniContext) { stopServerImpl() }
    }

    /**
     * Fork: tell the server's connection filter that a connection from
     * 127.0.0.1:[port] is the app's own dialer, good for one accept. Called by
     * [DialOut] from its dial threads, after it bound the port and before it
     * connects. Deliberately NOT through [jniContext]: that executor is
     * single-threaded and device binding can hold it for seconds, and a
     * registration queued behind it would make the dial fail. Native side takes
     * only the registry's own mutex, never the server's. Library unavailable:
     * ignored (there is no server to refuse the dial either).
     */
    fun registerDialPort(port: Int) {
        if (libraryLoaded) registerDialPortImpl(port)
    }

    /**
     * Fork: withdraw a registration. Called before the dialer's socket is
     * closed, on every path where the connection will not be accepted; for a
     * port the filter already consumed it does nothing. Synchronous, like
     * [registerDialPort].
     */
    fun unregisterDialPort(port: Int) {
        if (libraryLoaded) unregisterDialPortImpl(port)
    }

    suspend fun release() {
        if (!libraryLoaded) return
        withContext(jniContext) { releaseImpl() }
    }

    /**
     * 在 native 线程同步执行代码块，供 Service 销毁等非协程场景使用。
     * 注意：runBlocking 会阻塞当前线程——禁止在主线程调用（会 ANR）；
     * 也禁止在 native 上下文（nativeDispatcher/jniContext 的任务）内调用：
     * 当前线程若正持有该限流 dispatcher 的唯一许可，阻塞等待自己的许可会自死锁
     *（与 withContext 的内联 fast path 不同，那是同协程切换，这里是阻塞整个线程）。
     * 现有唯一调用点是 onDestroy 的专用清理线程（裸线程，不持有许可）。
     * 两种误用都能在运行时拦下：主线程检查 + native 任务内线程标记（见
     * nativeDispatcher 声明处的包装层）。
     * internal：仅供本应用模块内的服务层使用，不作为公开 API
     */
    @WorkerThread
    internal fun runOnNativeThread(block: suspend () -> Unit) {
        // 防误用断言（全构建生效）：主线程调用会让 runBlocking 卡到 native 完成，
        // 断言让它立刻失败而非静默卡顿到 ANR
        check(Looper.myLooper() != Looper.getMainLooper()) { "runOnNativeThread must not be called on the main thread" }
        // 同线程已在 native 任务内时（持有唯一许可）：runBlocking 阻塞本线程、
        // 再取许可必然排队等待自己（静默死锁，无日志难排查），入口处直接失败
        check(!nativeDispatcher.isInNativeTask) { "runOnNativeThread must not be called from inside a native task" }
        runBlocking {
            // 用 jniContext 而非裸 dispatcher：与其余 JNI 调用同一纪律
            //（串行 + 不可取消），换调用场景时不会踩"被取消留下半截状态"的坑
            withContext(jniContext) {
                block()
            }
        }
    }

    // JNI 错误码 → 失败结果：映射只有这一处，加错误码时不会漏改调用方
    private fun errorCodeToBindFailure(code: Int): DeviceBindResult.Failure = when (code) {
        ErrorCode.DEVICE_NOT_FOUND -> DeviceBindResult.Failure.DeviceNotFound
        ErrorCode.DEVICE_IN_USE -> DeviceBindResult.Failure.DeviceInUse
        // 同 busid 已绑定同样表示"用不了"，复用同一提示
        ErrorCode.DEVICE_ALREADY_BOUND -> DeviceBindResult.Failure.DeviceInUse
        ErrorCode.DEVICE_OPEN_FAILED -> DeviceBindResult.Failure.DeviceOpenFailed
        ErrorCode.GET_DESCRIPTOR_FAILED -> DeviceBindResult.Failure.GetDescriptorFailed
        ErrorCode.GET_CONFIG_FAILED -> DeviceBindResult.Failure.GetConfigFailed
        ErrorCode.CLAIM_INTERFACE_FAILED -> DeviceBindResult.Failure.ClaimInterfaceFailed
        ErrorCode.HUB_FILTERED -> DeviceBindResult.Failure.HubFiltered
        ErrorCode.SERVER_NOT_RUNNING -> DeviceBindResult.Failure.ServerNotRunning
        else -> DeviceBindResult.Failure.UnknownError
    }
}
