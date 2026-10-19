#include <jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <libusb-1.0/libusb.h>

#include <spdlog/spdlog.h>
#include <spdlog/sinks/android_sink.h>

#include <usbipdcpp/Server.h>
#include <usbipdcpp/LibusbHandler/LibusbServer.h>
#include <usbipdcpp/LibusbHandler/tools.h>

#include "jni_callback_sink.h"
#include "jni_log_callback.h"
#include "dial_port_filter.h"

#define LOG_TAG "UsbIpNative"

// 错误码定义 - 必须与 Kotlin 层 UsbIpNative.kt 中的常量保持一致
namespace ErrorCode {
    constexpr int SUCCESS = 0;
    constexpr int DEVICE_NOT_FOUND = 1;
    constexpr int DEVICE_IN_USE = 2;
    constexpr int DEVICE_OPEN_FAILED = 3;
    constexpr int GET_DESCRIPTOR_FAILED = 4;
    constexpr int GET_CONFIG_FAILED = 5;
    constexpr int CLAIM_INTERFACE_FAILED = 6;
    constexpr int DEVICE_ALREADY_BOUND = 7;
    constexpr int HUB_FILTERED = 8;
    constexpr int SERVER_NOT_RUNNING = 9;
    constexpr int UNKNOWN_ERROR = 99;
}

namespace {
    std::mutex g_server_mutex;
    // 锁内纪律：不得调用可能经 JNI 回调再回 native 入口的路径——那会重入本锁
    //（std::mutex 非递归，同线程死锁）。spdlog 输出例外：它经 jni_log 自己的
    // g_mutex，与本锁无关
    // g_server、g_server_running、g_server_port、g_initialized 四者的读写全部在 g_server_mutex 内。
    // g_server 与 g_server_running 成对维护：置 true 只发生在启动成功之后，失败/停止
    // 路径都在置 true 之前返回或一并归零，不存在"running 为 true 而 server 为空"的
    // 状态——stopServerImpl 等处的短路判断依赖这个不变量，不需要自愈分支。
    // g_initialized 的 libusb 初始化也在锁内完成：并发调用 nativeInit 时后来的调用
    // 会在锁上排队等前者做完，不会出现"看到已初始化标记但 libusb 尚未就绪"的窗口
    std::unique_ptr<usbipdcpp::LibusbServer> g_server;
    bool g_initialized = false;
    bool g_server_running = false;
    // 最近一次成功启动的监听端口，-1 表示尚未启动过。供"已运行"分支如实回答
    // 实际端口（调用方不能用入参回填）；停止时不清——running 为 false 时无人读取
    int g_server_port = -1;

    // Fork: source ports the app's own dialer has registered (see
    // dial_port_filter.h). Its own mutex, never g_server_mutex: the filter runs
    // on the network thread, and stopServerImpl holds g_server_mutex while
    // stop() joins that thread. registerDialPort/unregisterDialPort are plain
    // synchronous JNI calls for the same reason (and because the Kotlin side
    // calls them from dial threads, not from jniContext, which device binding
    // can block for seconds). Cleared when the server starts and stops.
    dialfilter::Registry g_dial_ports;

    // 幂等 libusb 初始化，调用方须持有 g_server_mutex。nativeInitImpl 与
    // startServerImpl 共用：init 与启动的其他状态操作落在同一临界区内，
    // 中间没有释放锁的窗口，"g_initialized 全部在锁内维护"不依赖调用方串行
    bool try_init_locked() {
        if (g_initialized) {
            return true;
        }
        libusb_set_option(nullptr, LIBUSB_OPTION_WEAK_AUTHORITY);
        int err = libusb_init(nullptr);
        if (err < 0) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to initialize libusb: %s", libusb_strerror(err));
            // 未置位，允许下次重试
            return false;
        }
        g_initialized = true;
        return true;
    }

    int toErrorCode(usbipdcpp::DeviceOperationResult result) {
        using namespace usbipdcpp;
        switch (result) {
            case DeviceOperationResult::Success: return ErrorCode::SUCCESS;
            case DeviceOperationResult::DeviceNotFound: return ErrorCode::DEVICE_NOT_FOUND;
            case DeviceOperationResult::DeviceInUse: return ErrorCode::DEVICE_IN_USE;
            case DeviceOperationResult::DeviceAlreadyBound: return ErrorCode::DEVICE_ALREADY_BOUND;
            case DeviceOperationResult::DeviceOpenFailed: return ErrorCode::DEVICE_OPEN_FAILED;
            case DeviceOperationResult::GetDescriptorFailed: return ErrorCode::GET_DESCRIPTOR_FAILED;
            case DeviceOperationResult::GetConfigFailed: return ErrorCode::GET_CONFIG_FAILED;
            case DeviceOperationResult::ClaimInterfaceFailed: return ErrorCode::CLAIM_INTERFACE_FAILED;
            case DeviceOperationResult::HubFiltered: return ErrorCode::HUB_FILTERED;
            // DeviceFiltered 只在设置了 device_bind_filter 时才会出现，本应用没设，
            // 归入未知错误即可
            default: return ErrorCode::UNKNOWN_ERROR;
        }
    }
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    // 只声明版本：没有需要注册的方法表（走默认的符号查找），JavaVM 也不用缓存
    //（jni_log::init 自己经 env->GetJavaVM 取，规范上不会失败）
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    // 刻意留空：Android 上 System.loadLibrary 的库随进程存活，不会触发 unload，
    // 资源统一由 releaseImpl 清理；若将来需要支持 unload（如 ClassLoader 隔离的
    // 测试环境），这里要补 g_server 析构与 jni_log 的全局引用清理
}

JNIEXPORT jboolean JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_nativeInitImpl(JNIEnv* env, jobject thiz) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Initializing native layer");

    // 初始化全程持 g_server_mutex：并发调用只有一个真正执行，其余在锁上等到做完后
    // 直接看到"已初始化"。不用原子 CAS：它只保证初始化执行一次而不保证已完成，后来的
    // 调用会乐观返回"已初始化"，而 libusb 可能尚未就绪
    std::lock_guard<std::mutex> lock(g_server_mutex);
    // 快照必须在 try_init_locked 之前取：它幂等，成功返回后 g_initialized 恒为
    // true，之后再读就分不出"本次真做了初始化"与"之前已初始化"
    bool was_initialized = g_initialized;
    if (!try_init_locked()) {
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        was_initialized ? "Already initialized" : "Native layer initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_setLogCallbackImpl(JNIEnv* env, jobject thiz, jobject callback) {
    if (callback == nullptr) {
        // null 表示清除回调：UI 销毁（应用退后台）时释放全局引用，
        // 否则旧 Activity 会被 JNI 全局引用一直持有导致泄漏
        jni_log::cleanup(env);
        return;
    }

    jobject callback_global = env->NewGlobalRef(callback);
    if (callback_global == nullptr) {
        // OOM 时 NewGlobalRef 返回 null 并挂 OutOfMemoryError：不清掉会抛给 Kotlin
        // 调用方（setLogCallback 没有 try-catch），冒到 Compose 里就是崩溃
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "NewGlobalRef failed");
        return;
    }

    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) {
        // OOM 时 GetObjectClass 可能返回 null，传给 GetMethodID 是 UB（ART 下 abort）
        env->ExceptionClear();
        env->DeleteGlobalRef(callback_global);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "GetObjectClass failed");
        return;
    }
    jmethodID log_method = env->GetMethodID(callback_class, "onLog", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(callback_class);
    if (log_method == nullptr) {
        // GetMethodID 失败时 JNI 栈上挂着 NoSuchMethodError，清掉并释放引用。
        // 不重置 spdlog 与旧回调：保留旧回调继续输出日志，比静默丢日志更合理
        env->ExceptionClear();
        env->DeleteGlobalRef(callback_global);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to find onLog method");
        return;
    }

    // make_shared/spdlog 可能抛 bad_alloc，C++ 异常不能穿越 JNI 边界（ART 无法
    // unwind，直接 terminate）。callback_ref_owned 标记引用是否还在本函数手上：
    // 构造在所有权转移之前，异常时 jni_log 与旧 logger 都保持原状
    bool callback_ref_owned = true;
    try {
        // logcat tag 统一为 LOG_TAG 的值：本模块的 spdlog 输出与 __android_log_print
        // 输出共用一个 tag，过滤时不会漏看一半
        auto android_sink = std::make_shared<spdlog::sinks::android_sink_mt>("UsbIpNative");
        auto jni_sink = std::make_shared<jni_callback_sink_mt>(jni_log::log_callback);

        // 每次重建（Activity 旋转/重启）都新建 logger 替换 default：set_default_logger
        // 会同时把新 logger 注册进 registry（loggers_[name]）并设为 default，两处
        // 都是同名替换——旧 logger 的两个 registry 引用全被顶掉，引用计数归零
        // 析构，不会逐次累积
        auto logger = std::make_shared<spdlog::logger>("usbipdcpp", spdlog::sinks_init_list{android_sink, jni_sink});
        logger->set_level(spdlog::level::debug);

        auto init_result = jni_log::init(env, callback_global, log_method);
        // init 无论成败都不再由本函数持有该引用（失败时它已当场释放）；
        // 唯一例外是 init 抛出（取锁失败）——"抛出即未接管"（契约见
        // jni_log_callback.h），引用仍在手上，由 catch 依 callback_ref_owned 释放
        callback_ref_owned = false;
        if (init_result != jni_log::InitResult::Installed) {
            // 回调没装上：保持旧 logger 不动，避免出现"新 logger 已生效但回调
            // 还是旧的"的错位状态
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to install log callback");
            return;
        }

        // init 与 set_default_logger 之间的窗口（回调已换、default 仍是旧
        // logger）无害：两代 logger 的 jni sink 都经 jni_log 全局查表取当前
        // 回调，窗口内日志照常送到新回调，不会落回旧 Activity。跨代一致性不
        // 靠这里保证——回调更替本就跨 set(null)、set(新) 两次调用
        spdlog::set_default_logger(logger);
        // spdlog::set_pattern 的实际链路：set_formatter → registry::set_formatter
        // 遍历已注册 logger → logger 逐个 sink 设置 formatter（formatter 归属
        // sink，非 logger）。上一行 set_default_logger 已把本 logger 注册进
        // registry，遍历必然命中：android_sink 与 jni_sink 都按此 pattern 输出
        spdlog::set_pattern("[%H:%M:%S] [%l] %v");

        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Log callback set");
    } catch (const std::exception& e) {
        if (callback_ref_owned) {
            env->DeleteGlobalRef(callback_global);
        }
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "setLogCallback threw: %s", e.what());
    } catch (...) {
        if (callback_ref_owned) {
            env->DeleteGlobalRef(callback_global);
        }
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "setLogCallback threw unknown exception");
    }
}

JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_bindUsbDeviceImpl(
    JNIEnv* env, jobject thiz, jint fd, jint vendor_id, jint product_id, jobjectArray outBusid) {

    // busid 与 rollback 定义在 try 外：外层 catch 也要用——库若在绑定完成之后
    // 抛异常（当前实现里绑定动作之后已无可抛点，防其演进），已落库的绑定
    // 必须尽力回滚
    std::string busid;
    // 交付失败按 busid 兜底移除：fd 解绑失败说明 fd 匹配已不可靠，
    // notify_device_removed 走 busid 键，是独立于 fd 的第二条清理路径。
    // noexcept + 内层 try：回滚自身再抛异常同样不能穿越边界，那样连"设备残留"
    // 都没人知道，至少要留下日志。
    // 调用方必须持有 g_server_mutex（LibusbServer 无锁，所有调用须串行）；
    // busid 走参数、fd 按值捕获：lambda 不持有任何栈上引用，即便将来被外带
    //（包成 std::function 等）也不会悬空
    auto rollback = [fd](const std::string& busid) noexcept {
        // g_server 检查之前的异常同样会落到 catch
        if (!g_server) {
            return;
        }
        try {
            if (g_server->unbind_host_device_by_fd(static_cast<intptr_t>(fd)) == usbipdcpp::DeviceOperationResult::Success) {
                return;
            }
            // busid 为空说明异常发生在取到 busid 之前——取 busid（get_device_busid）
            // 自身失败也是这一情形，此时没有按 busid 可兜底的对象，跳过是预期；
            // 顺带免去一次空键查询
            if (busid.empty()) {
                return;
            }
            auto notify_result = g_server->notify_device_removed(busid);
            // 本 lambda 声明 noexcept：内部记日志一律用 __android_log_print
            //（libc 函数，无 C++ 异常契约）——spdlog 不能出现在"绝对不抛"的承诺里
            if (notify_result == usbipdcpp::DeviceOperationResult::Success) {
                __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Rollback: fd unbind failed, busid fallback succeeded");
            } else {
                // 两条路径都失败：native 侧留下孤儿设备，需要人工关注
                __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Rollback failed by both fd and busid: %d", static_cast<int>(notify_result));
            }
        } catch (const std::exception& e) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Rollback threw: %s", e.what());
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Rollback threw unknown exception");
        }
    };

    // 异常路径的重新持锁回滚，两个 catch 分支共用（异常传到 catch 时 try 内的
    // 锁已随栈展开释放）。参数同 rollback 的纪律：不捕获栈上状态。rollback
    // 本身 noexcept，但 lock_guard 构造可能抛——再包一层，任何后续抛出都会
    // 穿越 extern "C" 边界，只允许记日志后放弃回滚
    auto try_rollback = [](const std::string& busid, const auto& rollback) noexcept {
        try {
            std::lock_guard<std::mutex> lock(g_server_mutex);
            rollback(busid);
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "bindUsbDevice rollback skipped: lock acquisition threw");
        }
    };

    // 整体兜底：库调用与 std::string 分配都可能抛（如 bad_alloc），C++ 异常穿越
    // extern "C" 的 JNI 边界时 ART 无法 unwind，会直接 terminate
    try {
        spdlog::info("Binding USB device: fd={}, vid=0x{:04x}, pid=0x{:04x}", fd, vendor_id, product_id);

        // 出参数组无效就无法把 busid 交回调用方：先验后绑，否则会留下"绑定成功但
        // 调用方拿不到 busid"的半成品状态（Kotlin 侧拿不到 busid 只会关连接，不会解绑）
        if (outBusid == nullptr || env->GetArrayLength(outBusid) == 0) {
            // 非数组入参会在 GetArrayLength 上挂异常（反射等入口的理论情形，
            // 正常 Kotlin 调用到不了）：清掉再返回，残留异常会传播到调用方
            env->ExceptionClear();
            spdlog::error("outBusid is null or empty");
            return ErrorCode::UNKNOWN_ERROR;
        }

        std::lock_guard<std::mutex> lock(g_server_mutex);

        if (!g_initialized) {
            spdlog::error("Native layer not initialized");
            return ErrorCode::UNKNOWN_ERROR;
        }

        if (!g_server) {
            spdlog::error("Server not created");
            return ErrorCode::SERVER_NOT_RUNNING;
        }

        // 先取 busid 再绑定：取信息失败时尚未绑定，直接返回即可，不存在"已绑定却
        // 交付不出 busid"的中间态。临时 wrap 是 usbipdcpp 的标准用法（库内部绑定与
        // 客户端连接时也各 wrap 一次，lazy binding 互不影响）；libusb 对 wrap 进来的
        // fd 设 fd_keep，close 临时句柄不会关闭原 fd（UsbDeviceConnection 不受影响）。
        // fd_keep 与 wrap API 同为 libusb 1.0.23 引入，不存在"老版本会关 fd"的组合
        libusb_device_handle* temp_handle = nullptr;
        int err = libusb_wrap_sys_device(nullptr, static_cast<intptr_t>(fd), &temp_handle);
        if (err < 0) {
            spdlog::error("Failed to get device info for busid: {}", libusb_strerror(err));
            return ErrorCode::DEVICE_OPEN_FAILED;
        }

        // 临时 handle 用 RAII 托管：get_device_busid 的 std::string 构造可能抛，
        // 走外层 catch 时不能漏掉 libusb_close。作用域限定在取 busid 这段，
        // 之后的绑定要在"临时 handle 已关闭"的状态下进行
        {
            struct TempHandleGuard {
                libusb_device_handle* handle;
                ~TempHandleGuard() {
                    if (handle) libusb_close(handle);
                }
            } guard{temp_handle};

            libusb_device* dev = libusb_get_device(temp_handle);
            if (!dev) {
                spdlog::error("libusb_get_device returned nullptr");
                return ErrorCode::DEVICE_NOT_FOUND;
            }
            busid = usbipdcpp::get_device_busid(dev);
        }

        // 库返回空 busid 属异常状态：空串会一路交付成 Success("")，之后按
        // busid 的解绑与拔出通知全部失效；此处在绑定之前，直接按"没找到"拒绝
        if (busid.empty()) {
            spdlog::error("get_device_busid returned empty");
            return ErrorCode::DEVICE_NOT_FOUND;
        }

        auto result = g_server->bind_host_device_with_wrapped_fd(static_cast<intptr_t>(fd));
        if (result != usbipdcpp::DeviceOperationResult::Success) {
            spdlog::error("bind_host_device_with_wrapped_fd failed: {}", static_cast<int>(result));
            return toErrorCode(result);
        }

        // 输出 busid（数组有效性已在入口校验）
        jstring busid_str = env->NewStringUTF(busid.c_str());
        if (busid_str == nullptr) {
            // OOM 时 NewStringUTF 返回 null：写 null 进数组会让 Kotlin 侧
            // outBusid[0]!! 抛 NPE，回滚绑定并返回错误
            env->ExceptionClear();
            rollback(busid);
            return ErrorCode::UNKNOWN_ERROR;
        }
        env->SetObjectArrayElement(outBusid, 0, busid_str);
        // DeleteLocalRef 在挂起异常时也是安全调用（JNI 规范允许），先释放再统一检查
        env->DeleteLocalRef(busid_str);

        // 写入失败时同样回滚：契约是"要么把 busid 交给调用方，要么 native 不留绑定"，
        // 否则调用方拿不到 busid 也没法解绑（正常参数下 SetObjectArrayElement 不会失败）
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            rollback(busid);
            return ErrorCode::UNKNOWN_ERROR;
        }

        // "成功"日志放在交付完成之后：上面的失败分支会回滚，日志若在前，
        // 回滚失败时 logcat 会留下误导性的"绑定成功"
        spdlog::info("Device bound successfully: {}", busid);

        return ErrorCode::SUCCESS;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "bindUsbDevice threw exception: %s", e.what());
        // 绑定未发生时 unbind 查无此 fd，走 notify 兜底也是无害的 no-op
        try_rollback(busid, rollback);
        return ErrorCode::UNKNOWN_ERROR;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "bindUsbDevice threw unknown exception");
        try_rollback(busid, rollback);
        return ErrorCode::UNKNOWN_ERROR;
    }
}

// 返回实际监听端口（成功启动或已在运行时都是当前真实端口），失败返回 -1：
// 调用方据此回填状态，不用入参推断
JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_startServerImpl(JNIEnv* env, jobject thiz, jint port, jboolean loopback_only) {

    // 端口越界直接拒绝：endpoint 内部按 unsigned short 存端口，65536 会回绕成 0
    //（系统随机分配端口），99999 回绕成别的端口，UI 显示与实际监听不一致
    if (port < 1 || port > 65535) {
        spdlog::error("Invalid port: {}", port);
        return -1;
    }

    // 无条件做一次初始化（幂等）：JNI 是公共边界，不假设调用方一定先调过
    // nativeInit，自己保证 libusb 就绪，失败就直接返回。init 与下面的启动检查/
    // 创建在同一临界区内完成，不依赖"调用方串行"这个跨层假设
    std::lock_guard<std::mutex> lock(g_server_mutex);
    if (!try_init_locked()) {
        return -1;
    }

    // 日志放在校验与 init 之后：只有真正进入启动流程才发这条，端口非法或
    // 未就绪失败时不产生"即将启动"的误导输出
    spdlog::info("Starting USB/IP server on port {} ({})", port,
                 loopback_only ? "loopback only" : "all interfaces");

    if (g_server_running) {
        // 返回实际监听端口而不是成功布尔：调用方用它回填状态，拿不到这个
        // 区分就可能在状态落后时把入参填成显示端口
        spdlog::info("Server already running");
        return g_server_port;
    }

    try {
        // 默认策略是任何提交失败都断开会话（对齐内核 stub 的悲观处理）；
        // Android 的 UVC ISO 提交会因带宽/资源问题偶发 LIBUSB_ERROR_IO，
        // 此时设备仍在：宽松策略只让本次 URB 以 EPIPE 结束，会话继续，
        // 避免一次瞬时错误把整台设备从客户端踢掉
        usbipdcpp::LibusbServerConfig config;
        config.device_handler_config.submit_error_policy =
                usbipdcpp::LibusbSubmitErrorPolicy::DisconnectOnDeviceGone;
        g_server = std::make_unique<usbipdcpp::LibusbServer>(config);
        g_server->set_hotplug_enabled(false);
        // Fork: connection filter, before start() (the library does not support
        // setting it later). Loopback-only: only the app's own dialer gets in,
        // once per registered port. All interfaces: network peers pass as in
        // upstream, but a loopback peer still must be a registered dialer.
        // NOTE the all-interfaces mode is unprotected against other apps on the
        // phone reaching the server through the phone's own WiFi address: that
        // is not loopback, so allow() lets it through. The UI says so.
        // Stale registrations from before this start are dropped first.
        g_dial_ports.clear();
        const bool filter_loopback_only = (loopback_only == JNI_TRUE);
        g_server->get_server().set_connection_filter([filter_loopback_only](const asio::ip::tcp::endpoint& peer) {
            return dialfilter::allow(peer, g_dial_ports, filter_loopback_only);
        });
        // Fork: loopback only is the default (Kotlin passes the setting), so a
        // phone on field WiFi does not expose its USB devices; dial-out reaches
        // the server over 127.0.0.1. The two endpoint constructors differ in
        // type, hence if/else rather than a ternary.
        const auto listen_port = static_cast<unsigned short>(port);
        asio::ip::tcp::endpoint endpoint;
        if (loopback_only == JNI_TRUE) {
            endpoint = asio::ip::tcp::endpoint(asio::ip::address_v4::loopback(), listen_port);
        } else {
            endpoint = asio::ip::tcp::endpoint(asio::ip::tcp::v4(), listen_port);
        }
        // v1.0.8 起 start 不再抛异常，启动失败（如端口被占用）通过返回值报告
        auto ec = g_server->start(endpoint);
        if (ec) {
            spdlog::error("Failed to start server: {}", ec.message());
            // start 失败路径内部已自清理（热插拔监控、libusb 事件线程），无需 stop 直接析构
            g_server.reset();
            return -1;
        }
        g_server_running = true;
        g_server_port = port;
        spdlog::info("Server started successfully");
        return port;
    } catch (const std::exception& e) {
        // make_unique 等构造路径的异常兜底（start 本身不再抛）
        spdlog::error("Failed to start server: {}", e.what());
        if (g_server) {
            try {
                g_server->stop();
            } catch (...) {}
        }
        g_server.reset();
        return -1;
    }
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_stopServerImpl(JNIEnv* env, jobject thiz) {

    spdlog::info("Stopping USB/IP server");

    std::lock_guard<std::mutex> lock(g_server_mutex);

    // || !g_server 是防御分支：按 g_server 声明处的不变量，running 为 true
    // 时 server 必然存在；这里兜住不变量万一被破坏的情形（免得对空指针 stop）
    if (!g_server_running || !g_server) {
        spdlog::info("Server not running");
        return;
    }

    try {
        g_server->stop();
        g_server.reset();
        g_server_running = false;
        g_dial_ports.clear();

        spdlog::info("Server stopped successfully");
    } catch (const std::exception& e) {
        // 停止失败也必须重置状态，否则 g_server_running 卡在 true 无法再次启动
        spdlog::error("Error stopping server: {}", e.what());
        g_server.reset();
        g_server_running = false;
        g_dial_ports.clear();
    }
}

// Fork: the app's dialer registers the local port it bound on 127.0.0.1 just
// before it connects to the server, and unregisters it when the connection is
// not going to be accepted. Plain synchronous calls: no g_server_mutex, no
// allocation beyond the set node. A bad port is ignored.
JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_registerDialPortImpl(JNIEnv* env, jobject thiz, jint port) {
    if (port < 1 || port > 65535) {
        return;
    }
    try {
        g_dial_ports.add(static_cast<std::uint16_t>(port));
    } catch (...) {
        // bad_alloc: the dialer's connect will be refused, which fails closed
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "registerDialPort threw");
    }
}

// Unregistering a port the filter already consumed is a harmless no-op.
JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_unregisterDialPortImpl(JNIEnv* env, jobject thiz, jint port) {
    if (port < 1 || port > 65535) {
        return;
    }
    try {
        g_dial_ports.remove(static_cast<std::uint16_t>(port));
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "unregisterDialPort threw");
    }
}

JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_unbindUsbDeviceImpl(
    JNIEnv* env, jobject thiz, jint fd) {

    // 同 bindUsbDeviceImpl：库调用可能抛，异常不能穿越 extern "C" 边界
    try {
        spdlog::info("Unbinding USB device with fd={}", fd);

        std::lock_guard<std::mutex> lock(g_server_mutex);

        if (!g_initialized) {
            spdlog::error("Native layer not initialized");
            return ErrorCode::UNKNOWN_ERROR;
        }

        if (!g_server) {
            spdlog::error("Server not created");
            return ErrorCode::SERVER_NOT_RUNNING;
        }

        auto result = g_server->unbind_host_device_by_fd(static_cast<intptr_t>(fd));
        int errorCode = toErrorCode(result);

        if (errorCode == ErrorCode::SUCCESS) {
            spdlog::info("Device unbound successfully");
        } else {
            spdlog::error("Failed to unbind device: {}", errorCode);
        }

        return errorCode;
    } catch (const std::exception& e) {
        spdlog::error("unbindUsbDevice threw exception: {}", e.what());
        return ErrorCode::UNKNOWN_ERROR;
    } catch (...) {
        spdlog::error("unbindUsbDevice threw unknown exception");
        return ErrorCode::UNKNOWN_ERROR;
    }
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_notifyDeviceRemovedImpl(
    JNIEnv* env, jobject thiz, jstring busid) {

    // 同 bind/unbind：字符串构造与库调用都可能抛，异常不能穿越 extern "C" 边界
    try {
        // Kotlin 侧 busid 是非空 String，正常到不了这里；JNI 是公共边界
        //（反射等入口也可能进来），null 传给 GetStringUTFChars 在 ART 上是 UB
        if (busid == nullptr) {
            spdlog::error("notifyDeviceRemoved: busid is null");
            return;
        }
        const char* busid_cstr = env->GetStringUTFChars(busid, nullptr);
        if (busid_cstr == nullptr) {
            // OOM：GetStringUTFChars 返回 null 并挂 OutOfMemoryError，
            // 直接构造 std::string 会崩溃，清异常后安全返回
            env->ExceptionClear();
            spdlog::error("Failed to get busid string");
            return;
        }
        // RAII 托管（同 bindUsbDeviceImpl 的 TempHandleGuard）：std::string
        // 构造可能抛 bad_alloc，走外层 catch 时不能漏 ReleaseStringUTFChars
        struct UtfCharsGuard {
            JNIEnv* env;
            jstring str;
            const char* cstr;
            ~UtfCharsGuard() { env->ReleaseStringUTFChars(str, cstr); }
        } utf_guard{env, busid, busid_cstr};

        std::string busid_str(busid_cstr);

        spdlog::info("Notifying device removed: {}", busid_str);

        std::lock_guard<std::mutex> lock(g_server_mutex);

        if (!g_server) {
            spdlog::warn("Server not created, ignoring device removal notification");
            return;
        }

        auto notify_result = g_server->notify_device_removed(busid_str);
        if (notify_result != usbipdcpp::DeviceOperationResult::Success) {
            // 查无此 busid 属正常情形（设备已被其他路径清理），debug 级即可；
            // 其它结果属库内部异常，升到 warn 免得被 debug 日志淹没
            if (notify_result == usbipdcpp::DeviceOperationResult::DeviceNotFound) {
                spdlog::debug("notify_device_removed: busid not found (already cleaned): {}", busid_str);
            } else {
                spdlog::warn("notify_device_removed failed: {} for busid {}",
                             static_cast<int>(notify_result), busid_str);
            }
        }
    } catch (const std::exception& e) {
        spdlog::error("notifyDeviceRemoved threw exception: {}", e.what());
    } catch (...) {
        spdlog::error("notifyDeviceRemoved threw unknown exception");
    }
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_releaseImpl(JNIEnv* env, jobject thiz) {
    // 整体兜底：spdlog 的分配等可能抛，而 Kotlin 侧的 try/catch 抓不到 C++ 异常
    try {
        spdlog::info("Releasing native resources");

        // 直接调用同一翻译单元内的 JNI 导出函数：停止逻辑只有一份，
        // 无需经 env->CallStaticVoidMethod 反射或复制实现
        Java_com_yunsmall_usbipdcpp_UsbIpNative_stopServerImpl(env, thiz);

        // 状态清零与 libusb_exit 在同一临界区：exit 若放锁外，另一线程走
        // try_init_locked 见到 g_initialized=false 会重新 libusb_init，与 exit
        // 交错。放锁内则 JNI 层自闭环，不依赖"调用方全部串行"这条外部纪律。
        // 持锁等待的代价可控：stopServerImpl 已 join 库线程并释放全部设备，
        // exit 只剩资源释放；且此期间状态恒 false，其他调用本就只会快速失败
        bool was_initialized = false;
        {
            std::lock_guard<std::mutex> lock(g_server_mutex);
            was_initialized = g_initialized;
            g_initialized = false;
            if (was_initialized) {
                libusb_exit(nullptr);
            }
        }

        // 不在这里清 jni_log 回调：回调的 set/清完全由 UI 层配对管理（组合时
        // set、onDispose 清）。Service 可能先于 Activity 销毁（分屏、被系统
        // 回收），在此清掉会让仍存活 UI 的日志通路莫名静默；引用该释放时
        // UI 会释放，进程退出则由系统回收
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "release threw: %s", e.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "release threw unknown exception");
    }
}

} // extern "C"
