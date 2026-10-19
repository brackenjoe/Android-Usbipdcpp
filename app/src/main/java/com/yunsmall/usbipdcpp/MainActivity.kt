package com.yunsmall.usbipdcpp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbManager
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yunsmall.usbipdcpp.ui.LockScreen
import com.yunsmall.usbipdcpp.ui.MainScreen
import com.yunsmall.usbipdcpp.ui.theme.UsbipdcppTheme

// 日志标签沿用拆分前的 "MainActivity"：logcat 过滤习惯不受文件拆分影响；
// MainScreen.kt 与 NetworkUtils.kt 也各自保留同名标签
private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private val usbManager: UsbManager by lazy {
        getSystemService(USB_SERVICE) as UsbManager
    }

    // 用 lazy：onDestroy 里即使从未 registerReceiver 过也会调 unregisterReceiver，
    // 此时会触发这里的初始化，但构造本身无副作用（PendingIntent 也是 lazy），
    // 而未注册时 unregisterReceiver 抛的异常在其内部已被捕获
    private val permissionManager: UsbPermissionManager by lazy {
        UsbPermissionManager(this, usbManager)
    }

    // Compose 观察的 Service 引用：ServiceConnection 回调都在主线程，直接写
    // State 让组合自动重组，不必再手工挂"状态变了"的回调并记住到处置空
    private val usbServiceHolder = mutableStateOf<UsbService?>(null)

    // bindService 的返回值：true 表示绑定请求已受理（onServiceConnected 只是
    // 随后异步到达），此后必须 unbindService——回调没来过也合法；
    // false（系统拒绝绑定）时调用 unbindService 会抛 IllegalArgumentException
    private var bindRequested = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // 安全转换：系统只会回传本 Service onBind 返回的 Binder，但将来若
            // 引入第二个 Binder（多 Action）或别的 IBinder 类型，硬转会在系统
            // 回调里抛 ClassCastException 崩溃；转换失败按"未连接"处理即可
            val binder = service as? UsbService.UsbBinder ?: run {
                Log.e(TAG, "onServiceConnected with unexpected binder: $service")
                return
            }
            usbServiceHolder.value = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            usbServiceHolder.value = null
        }
    }

    // Fork: the fingerprint gate. The main UI is not composed until a strong
    // biometric passes, at launch and again after every trip to the background
    // (onStop sets it, onStart asks). No device-credential fallback and no
    // CryptoObject: the passing prompt opens the dial key's time window
    // (DialInKey.KEY_AUTH_WINDOW_S), which is what the key needs. Not saved
    // across process death on purpose: a restart is locked
    private var locked by mutableStateOf(true)
    private var lockMessage by mutableStateOf<String?>(null)
    private var promptActive = false
    private var resumed = false
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    // Fork: POST_NOTIFICATIONS is a runtime permission from API 33. Without it the
    // "Fingerprint needed to reconnect" notification is silently dropped. Registered
    // as a field, so before the activity is STARTED; asked at most once per process,
    // and only from the unlocked state (see askForNotificationPermission)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        }
    private var notificationPermissionAsked = false

    private fun askForNotificationPermission() {
        if (locked || notificationPermissionAsked) return
        if (Build.VERSION.SDK_INT < 33 || notificationPermissionGranted(this)) return
        if (!AppSettings(this).dialOutEnabled) return
        notificationPermissionAsked = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // FLAG_SECURE while locked and whenever the activity is not in the foreground
    // (so the recents thumbnail never shows the main UI)
    private fun applySecureFlag() {
        if (locked || !resumed) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun authenticate() {
        if (promptActive || !locked) return
        val can = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            // Fail closed: nothing to unlock with means the app stays locked
            lockMessage = getString(R.string.lock_unavailable)
            return
        }
        promptActive = true
        lockMessage = null
        biometricPrompt.authenticate(promptInfo)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super and before any content: the first frame is already secure
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.biometric_cancel)) // required without a credential fallback
            .setConfirmationRequired(false)
            .build()
        biometricPrompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptActive = false
                    lockMessage = null
                    locked = false
                    DialInKey.refresh()
                    applySecureFlag()
                    askForNotificationPermission()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Always reopen the gate to a retry: the system also cancels a prompt by
                    // itself (sensor busy), and a stuck flag would leave Unlock dead
                    promptActive = false
                    // Our own cancel in onStop is not a result worth showing
                    if (errorCode == BiometricPrompt.ERROR_CANCELED) return
                    lockMessage = getString(R.string.lock_not_unlocked, errString)
                }

                // One bad read: the prompt stays up and lets the user try again
                override fun onAuthenticationFailed() = Unit
            }
        )

        permissionManager.registerReceiver()

        // 启动并绑定 Service
        val serviceIntent = Intent(this, UsbService::class.java)
        startForegroundService(serviceIntent)
        bindRequested = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            UsbipdcppTheme {
                if (locked) {
                    LockScreen(message = lockMessage, onUnlock = ::authenticate)
                } else {
                    MainScreen(
                        usbManager = usbManager,
                        permissionManager = permissionManager,
                        usbService = usbServiceHolder.value
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (locked) authenticate()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        applySecureFlag()
    }

    override fun onPause() {
        resumed = false
        applySecureFlag()
        super.onPause()
    }

    override fun onStop() {
        // Back from the background means the fingerprint again
        locked = true
        if (promptActive) {
            biometricPrompt.cancelAuthentication()
            promptActive = false
        }
        applySecureFlag()
        super.onStop()
    }

    override fun onDestroy() {
        permissionManager.unregisterReceiver()
        // bindRequested（bindService 的返回值）为 true 才解绑：它表示绑定已受理，
        // onServiceConnected 到没到都算；false 表示未被受理，此时调用
        // unbindService 会抛 IllegalArgumentException。
        // 注：onServiceDisconnected（服务进程崩溃）不回撤此标志——绑定仍然有效，
        // 系统会在服务重启后重新回调 onServiceConnected，解绑仍需执行。
        // usbServiceHolder 不用清：它与本 Activity 同生命周期，整条引用链一起回收
        if (bindRequested) {
            unbindService(serviceConnection)
            bindRequested = false
        }
        // 不停止 Service，让它继续运行
        // super 最后调用：销毁类回调的通行顺序是先清理自己持有的资源、再让基类
        // 收尾（创建类回调如 onCreate 相反，super 最先）。后续新增的清理代码
        // 直接排在上面即可
        super.onDestroy()
    }
}
