package com.yunsmall.usbipdcpp

import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac

/*
 * usbip-dialin auth v1, the phone's side (linux/usbip-dialin/README.md,
 * "Authentication"; reference implementation: phone_auth in
 * linux/usbip-dialin/usbip_dialin.py). "Box" here and in the protocol's labels
 * is the Linux host. No Android types here, so the JVM tests run it against
 * fake boxes and, when python3 and the reference are on the machine, the real
 * box_auth.
 *
 * Nothing in this file logs, and no message it builds contains a byte of key,
 * nonce or tag: failures carry a fixed string.
 */

/** Why the key cannot be used right now. */
class KeyUnavailableException(val reason: Reason, message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    enum class Reason {
        /** The 1-hour window after the fingerprint is over, or the device is locked. Fixable by opening the app. */
        LOCKED,

        /** No key has been entered. */
        NO_KEY,

        /** A new fingerprint was enrolled: the key is gone for good, enter it again. */
        INVALIDATED,

        /** Anything else the Keystore refused. */
        OTHER
    }
}

/**
 * Where the pre-shared key K lives. The app's implementation hands out a
 * Keystore HMAC-SHA256 [Mac] that works only inside the window after the
 * user's fingerprint; the tests use a software key. K itself is never visible
 * through this interface.
 */
interface Authenticator {
    /**
     * A fresh, initialised HMAC-SHA256 under K. The Keystore refuses here
     * (not later) when the window is over. One handshake uses one instance
     * for both tags; `doFinal` resets it.
     */
    @Throws(KeyUnavailableException::class)
    fun mac(): Mac
}

/** The handshake failed: a bad peer, not a bad key store. The message is a fixed string. */
class AuthFailedException(message: String) : Exception(message)

object AuthV1 {
    val MAGIC: ByteArray = "UDIALIN1".toByteArray(Charsets.US_ASCII)
    val LABEL_PHONE: ByteArray = "usbip-dialin v1 phone".toByteArray(Charsets.US_ASCII)
    val LABEL_BOX: ByteArray = "usbip-dialin v1 box".toByteArray(Charsets.US_ASCII)
    const val NONCE_LEN = 32
    const val TAG_LEN = 32

    private val random = SecureRandom()

    fun randomNonce(): ByteArray = ByteArray(NONCE_LEN).also { random.nextBytes(it) }

    /** `HMAC-SHA256(K, label || nonce_b || nonce_p)`: the reference's `_tag`. */
    fun tag(mac: Mac, label: ByteArray, nonceB: ByteArray, nonceP: ByteArray): ByteArray {
        mac.update(label)
        mac.update(nonceB)
        mac.update(nonceP)
        return mac.doFinal()
    }

    /**
     * The phone's side, on a freshly connected [socket]; the box speaks first.
     *
     * 1. read `UDIALIN1` and nonce_b;
     * 2. send nonce_p and the phone tag;
     * 3. read the box's tag and compare it, in constant time, with the one
     *    this side computes.
     *
     * Returns only when the box proved the key. Every read shares one
     * deadline of [timeoutMs] from the call, enforced with the socket's read
     * timeout, which is put back to 0 (none) before returning, so the
     * forwarding that follows is not timed. A reflected nonce (nonce_p equal
     * to nonce_b) aborts before anything is sent: a peer that echoes what it
     * was sent must never get a tag. [AuthFailedException] on every protocol
     * failure; an [IOException] from the socket propagates.
     */
    @Throws(AuthFailedException::class, IOException::class)
    fun phoneAuth(
        socket: Socket,
        mac: Mac,
        timeoutMs: Long,
        newNonce: () -> ByteArray = ::randomNonce
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            val hello = readExact(socket, MAGIC.size + NONCE_LEN, deadline)
            if (!MessageDigest.isEqual(hello.copyOfRange(0, MAGIC.size), MAGIC)) {
                throw AuthFailedException("bad hello from the host")
            }
            val nonceB = hello.copyOfRange(MAGIC.size, hello.size)

            val nonceP = newNonce()
            check(nonceP.size == NONCE_LEN) { "nonce source returned ${nonceP.size} bytes" }
            if (MessageDigest.isEqual(nonceP, nonceB)) throw AuthFailedException("reflected nonce")

            // Both Keystore operations back to back, not across the box's round trip
            val phoneTag = tag(mac, LABEL_PHONE, nonceB, nonceP)
            val expected = tag(mac, LABEL_BOX, nonceB, nonceP)
            val out = socket.getOutputStream()
            out.write(nonceP + phoneTag)
            out.flush()

            val tagB = readExact(socket, TAG_LEN, deadline)
            if (!MessageDigest.isEqual(tagB, expected)) throw AuthFailedException("host tag does not verify")
        } catch (e: java.security.ProviderException) {
            // The Keystore can refuse a second operation on the same Mac; the
            // reason is the Keystore's, not the peer's, and carries no key
            throw AuthFailedException("key operation failed (${e.javaClass.simpleName})")
        } finally {
            try {
                socket.soTimeout = 0
            } catch (e: IOException) {
                // the socket is closed: the caller is already ending
            }
        }
    }

    private fun readExact(socket: Socket, n: Int, deadlineNs: Long): ByteArray {
        val input = socket.getInputStream()
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val leftMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
            if (leftMs <= 0) throw AuthFailedException("timed out")
            socket.soTimeout = minOf(leftMs, Int.MAX_VALUE.toLong()).toInt()
            val r = try {
                input.read(buf, got, n - got)
            } catch (e: SocketTimeoutException) {
                throw AuthFailedException("timed out")
            }
            if (r < 0) throw AuthFailedException("host closed during authentication")
            got += r
        }
        return buf
    }
}

/** Entry-field rules for K: exactly 64 hex characters (surrounding whitespace is ignored). */
object KeyHex {
    const val LENGTH = 64

    /** The 32 key bytes, or null if [text] is not exactly 64 hex characters. */
    fun parse(text: String): ByteArray? {
        val t = text.trim()
        if (t.length != LENGTH) return null
        val out = ByteArray(LENGTH / 2)
        for (i in out.indices) {
            val hi = Character.digit(t[2 * i], 16)
            val lo = Character.digit(t[2 * i + 1], 16)
            // Character.digit also accepts non-ASCII digits (fullwidth, Arabic-Indic)
            if (hi < 0 || lo < 0 || t[2 * i].code > 0x7f || t[2 * i + 1].code > 0x7f) {
                out.fill(0)
                return null
            }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun isValid(text: String): Boolean = parse(text)?.also { it.fill(0) } != null
}
