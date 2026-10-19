package com.yunsmall.usbipdcpp

import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Every key in the tests is a fixed, obviously fake value.
val TEST_KEY: ByteArray = ByteArray(32) { (0xA0 + it).toByte() }
val OTHER_KEY: ByteArray = ByteArray(32) { (0x10 + it).toByte() }

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

fun softwareMac(key: ByteArray): Mac =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

/** The Keystore's stand-in: a software HMAC key. [failWith] makes the key unusable, like a closed window. */
class SoftwareAuthenticator(
    private val key: ByteArray = TEST_KEY,
    @Volatile var failWith: KeyUnavailableException.Reason? = null
) : Authenticator {
    val calls = AtomicInteger()
    override fun mac(): Mac {
        calls.incrementAndGet()
        failWith?.let { throw KeyUnavailableException(it, "test: key unavailable") }
        return softwareMac(key)
    }
}

/**
 * The box's side of auth v1, a Kotlin copy of `box_auth` in usbip_dialin.py,
 * with misbehaviours. [serve] returns true when the phone's tag verified and
 * the box's tag was sent.
 */
class FakeBox(
    private val key: ByteArray = TEST_KEY,
    private val mode: Mode = Mode.GOOD,
    private val nonceB: () -> ByteArray = AuthV1::randomNonce,
    private val timeoutMs: Long = 5_000,
    private val log: (String) -> Unit = {}
) {
    enum class Mode {
        GOOD,
        /** "UDIALIN2" instead of the magic. */
        BAD_MAGIC,
        /** The magic and ten bytes of nonce, then a close. */
        TRUNCATED_HELLO,
        /** Accepts and says nothing. */
        STALL_HELLO,
        /** A good hello and a good check of the phone, then no tag. */
        STALL_TAG,
        /** The right tag with one bit flipped. */
        BAD_BOX_TAG,
        /** The phone's own tag sent back as the box's. */
        REFLECT_PHONE_TAG,
        /** The box tag computed under a different key. */
        WRONG_BOX_KEY
    }

    /** Bytes the phone sent after the hello. */
    val phoneBytes = AtomicInteger()

    fun serve(s: Socket): Boolean {
        val mac = softwareMac(key)
        val nb = nonceB()
        val out = s.getOutputStream()
        try {
            when (mode) {
                Mode.STALL_HELLO -> {
                    stall(s)
                    return false
                }
                Mode.TRUNCATED_HELLO -> {
                    out.write(AuthV1.MAGIC + nb.copyOf(10))
                    out.flush()
                    s.close()
                    return false
                }
                Mode.BAD_MAGIC -> out.write("UDIALIN2".toByteArray() + nb)
                else -> out.write(AuthV1.MAGIC + nb)
            }
            out.flush()
            val got = readUpTo(s, AuthV1.NONCE_LEN + AuthV1.TAG_LEN)
            phoneBytes.addAndGet(got.size)
            if (got.size < AuthV1.NONCE_LEN + AuthV1.TAG_LEN) {
                s.close()
                return false
            }
            val np = got.copyOfRange(0, AuthV1.NONCE_LEN)
            val tagP = got.copyOfRange(AuthV1.NONCE_LEN, got.size)
            val good = MessageDigest.isEqual(tagP, AuthV1.tag(mac, AuthV1.LABEL_PHONE, nb, np)) &&
                !MessageDigest.isEqual(np, nb)
            if (!good || mode == Mode.BAD_MAGIC) {
                s.close() // refuse: nothing more is sent
                return false
            }
            if (mode == Mode.STALL_TAG) {
                stall(s)
                return false
            }
            var tagB = AuthV1.tag(mac, AuthV1.LABEL_BOX, nb, np)
            when (mode) {
                Mode.BAD_BOX_TAG -> tagB = tagB.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                Mode.REFLECT_PHONE_TAG -> tagB = tagP
                Mode.WRONG_BOX_KEY -> tagB = AuthV1.tag(softwareMac(OTHER_KEY), AuthV1.LABEL_BOX, nb, np)
                else -> Unit
            }
            log("box:tag_sent")
            out.write(tagB)
            out.flush()
            return mode == Mode.GOOD
        } catch (e: IOException) {
            return false
        }
    }

    /** Hold the connection open and silent until the peer closes it. */
    private fun stall(s: Socket) {
        s.soTimeout = 30_000
        try {
            while (s.getInputStream().read() >= 0) Unit
        } catch (e: IOException) {
            // closed or reset
        }
    }

    private fun readUpTo(s: Socket, n: Int): ByteArray {
        s.soTimeout = timeoutMs.toInt()
        val buf = ByteArray(n)
        var got = 0
        try {
            while (got < n) {
                val r = s.getInputStream().read(buf, got, n - got)
                if (r < 0) break
                got += r
            }
        } catch (e: SocketTimeoutException) {
            // short
        } catch (e: IOException) {
            // reset
        }
        s.soTimeout = 0
        return buf.copyOf(got)
    }
}
