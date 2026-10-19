package com.yunsmall.usbipdcpp

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The usbip-dialin pre-shared key K, in the Android Keystore
 * (linux/usbip-dialin/README.md, "Authentication").
 *
 * K is imported once, as an HMAC-SHA256 key that works only for
 * [KEY_AUTH_WINDOW_S] seconds after a strong-biometric authentication
 * (BiometricPrompt, BIOMETRIC_STRONG) and only while the device is unlocked.
 * The raw bytes are zeroed after the import and are kept nowhere else: not in
 * SharedPreferences, not in a log. Nothing here can read K back; the app gets
 * an initialised [Mac] and nothing more.
 *
 * Needs API 30 (setUserAuthenticationParameters, the only way to say
 * "biometric only" for a timed key). On API 28 and 29 the equivalent,
 * setUserAuthenticationValidityDurationSeconds, also opens the window on the
 * lock-screen PIN, so the fingerprint-only protection cannot be had: the
 * import is refused there ([State.UNSUPPORTED]) instead of weakening it.
 */
object DialInKey {
    const val ALIAS = "usbip-dialin-v1"

    /**
     * How long the key works after the fingerprint: 1 hour.
     * Android fixes the window in the key when it is imported, so changing
     * this needs the key entered again: a key already in the Keystore keeps
     * the window it was imported with.
     */
    const val KEY_AUTH_WINDOW_S = 3600

    private const val PROVIDER = "AndroidKeyStore"

    enum class State {
        /** No key: dial-out does not dial. */
        NONE,
        PRESENT,

        /** A new fingerprint was enrolled (or the key vanished): enter the key again. */
        INVALIDATED,

        /** API below 30: no import, dial-out cannot be used. */
        UNSUPPORTED
    }

    /** Where the Keystore says the key lives. API 31 can tell StrongBox from the TEE; API 30 only "hardware". */
    enum class Protection { STRONGBOX, HARDWARE, SOFTWARE, UNKNOWN }

    sealed interface ImportResult {
        /** [strongBoxFellBack]: the device has StrongBox but refused the key; it was imported without. */
        data class Ok(val protection: Protection, val strongBoxFellBack: Boolean) : ImportResult
        object BadKey : ImportResult
        object Unsupported : ImportResult

        /** [reason] is an exception class name, never key material. */
        data class Failed(val reason: String) : ImportResult
    }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _protection = MutableStateFlow(Protection.UNKNOWN)
    val protection: StateFlow<Protection> = _protection.asStateFlow()

    init {
        refresh()
    }

    private fun initialState() = if (Build.VERSION.SDK_INT < 30) State.UNSUPPORTED else State.NONE

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** Re-read whether a key is present. Cheap; the UI calls it when it comes to the front. */
    fun refresh() {
        if (Build.VERSION.SDK_INT < 30) {
            _state.value = State.UNSUPPORTED
            return
        }
        val present = try {
            keyStore().containsAlias(ALIAS)
        } catch (e: Exception) {
            false
        }
        // An invalidation noticed this session stays until a key is entered or forgotten
        if (present) {
            _state.value = State.PRESENT
            _protection.value = readProtection()
        } else if (_state.value != State.INVALIDATED) {
            _state.value = State.NONE
            _protection.value = Protection.UNKNOWN
        }
    }

    /**
     * Import K from the 64 hex characters in [text], replacing any key under
     * the alias. StrongBox is asked for when the device has it; if that
     * import fails the key is imported without it, and the result says so.
     */
    @Synchronized
    fun importKey(context: Context, text: String): ImportResult {
        if (Build.VERSION.SDK_INT < 30) return ImportResult.Unsupported
        val raw = KeyHex.parse(text) ?: return ImportResult.BadKey
        try {
            val ks = keyStore()
            val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            var fellBack = false
            try {
                setEntry(ks, raw, strongBox = hasStrongBox)
            } catch (e: Exception) {
                if (!hasStrongBox) throw e
                fellBack = true
                setEntry(ks, raw, strongBox = false)
            }
            _state.value = State.PRESENT
            val protection = readProtection()
            _protection.value = protection
            return ImportResult.Ok(protection, fellBack)
        } catch (e: Exception) {
            return ImportResult.Failed(e.javaClass.simpleName)
        } finally {
            raw.fill(0)
        }
    }

    private fun setEntry(ks: KeyStore, raw: ByteArray, strongBox: Boolean) {
        // API 30: setUserAuthenticationParameters
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(KEY_AUTH_WINDOW_S, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setUnlockedDeviceRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        // SecretKeySpec keeps its own copy of the bytes, which the JVM gives no way to zero
        ks.setEntry(ALIAS, KeyStore.SecretKeyEntry(SecretKeySpec(raw, KeyProperties.KEY_ALGORITHM_HMAC_SHA256)), protection)
    }

    /** Delete the key. Dial-out stops dialing. */
    @Synchronized
    fun forget() {
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: Exception) {
            // nothing to delete, or the Keystore is unavailable: the state below is read back
        }
        _state.value = initialState()
        refresh()
    }

    private fun readProtection(): Protection = try {
        val key = keyStore().getKey(ALIAS, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> Protection.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Protection.HARDWARE
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> Protection.SOFTWARE
                else -> Protection.UNKNOWN
            }
        } else if (info.isInsideSecureHardware) {
            Protection.HARDWARE
        } else {
            Protection.SOFTWARE
        }
    } catch (e: Exception) {
        Protection.UNKNOWN
    }

    @Synchronized
    private fun markInvalidated() {
        runCatching { keyStore().deleteEntry(ALIAS) } // a dead key is no use to anyone
        _state.value = State.INVALIDATED
        _protection.value = Protection.UNKNOWN
    }

    /** The [Authenticator] the dial loops use: a Keystore [Mac] per handshake. */
    val authenticator: Authenticator = object : Authenticator {
        override fun mac(): Mac {
            if (Build.VERSION.SDK_INT < 30) {
                throw KeyUnavailableException(KeyUnavailableException.Reason.NO_KEY, "no key")
            }
            try {
                val key = keyStore().getKey(ALIAS, null) as? SecretKey
                    ?: throw KeyUnavailableException(KeyUnavailableException.Reason.NO_KEY, "no key")
                // Init is where the Keystore checks the window: it throws
                // UserNotAuthenticatedException once the fingerprint's time is over
                // (also when the device is locked, UNLOCKED_DEVICE_REQUIRED)
                return Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).apply { init(key) }
            } catch (e: KeyUnavailableException) {
                throw e
            } catch (e: Exception) {
                throw translate(e)
            }
        }
    }

    private fun translate(e: Exception): KeyUnavailableException {
        var t: Throwable? = e
        while (t != null) {
            if (t is KeyPermanentlyInvalidatedException) {
                markInvalidated()
                return KeyUnavailableException(
                    KeyUnavailableException.Reason.INVALIDATED, "key invalidated: re-enter the key", e
                )
            }
            if (t is UserNotAuthenticatedException) {
                return KeyUnavailableException(KeyUnavailableException.Reason.LOCKED, "fingerprint needed", e)
            }
            t = t.cause
        }
        return KeyUnavailableException(
            KeyUnavailableException.Reason.OTHER, "key unusable (${e.javaClass.simpleName})", e
        )
    }
}
