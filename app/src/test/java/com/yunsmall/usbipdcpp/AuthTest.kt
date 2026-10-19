package com.yunsmall.usbipdcpp

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** [AuthV1.phoneAuth] against the Kotlin [FakeBox], one socket pair, no DialOut. */
class AuthTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() = closeables.forEach { runCatching { it.close() } }

    /** A phone-side socket connected to a box running [box]. */
    private fun pair(box: FakeBox): Socket {
        val server = ServerSocket(0, 1, loopback).also { closeables.add(it) }
        Thread({
            runCatching {
                val s = server.accept().also { closeables.add(it) }
                box.serve(s)
            }
        }, "auth-test-box").apply { isDaemon = true }.start()
        return Socket(loopback, server.localPort).also { closeables.add(it) }
    }

    private fun failure(box: FakeBox, key: ByteArray = TEST_KEY, ms: Long = 5_000, nonce: () -> ByteArray = AuthV1::randomNonce): String {
        val s = pair(box)
        try {
            AuthV1.phoneAuth(s, softwareMac(key), ms, nonce)
        } catch (e: AuthFailedException) {
            return e.message!!
        }
        fail("handshake passed")
        return ""
    }

    @Test
    fun rightKeyPassesAndLeavesTheSocketUntimed() {
        val s = pair(FakeBox())
        AuthV1.phoneAuth(s, softwareMac(TEST_KEY), 5_000)
        assertEquals(0, s.soTimeout)
    }

    @Test
    fun wrongKeyFails() {
        assertEquals("host closed during authentication", failure(FakeBox(), key = OTHER_KEY))
    }

    @Test
    fun wrongMagicFails() {
        assertEquals("bad hello from the host", failure(FakeBox(mode = FakeBox.Mode.BAD_MAGIC)))
    }

    @Test
    fun truncatedHelloFails() {
        assertEquals("host closed during authentication", failure(FakeBox(mode = FakeBox.Mode.TRUNCATED_HELLO)))
    }

    @Test
    fun boxTagMismatchFails() {
        for (m in listOf(FakeBox.Mode.BAD_BOX_TAG, FakeBox.Mode.REFLECT_PHONE_TAG, FakeBox.Mode.WRONG_BOX_KEY)) {
            assertEquals(m.name, "host tag does not verify", failure(FakeBox(mode = m)))
        }
    }

    @Test
    fun reflectedNonceSendsNothing() {
        val fixed = ByteArray(32) { 7 }
        val box = FakeBox(nonceB = { fixed.copyOf() })
        assertEquals("reflected nonce", failure(box, nonce = { fixed.copyOf() }))
        Thread.sleep(200)
        assertEquals(0, box.phoneBytes.get())
    }

    @Test
    fun timeoutsFail() {
        val t0 = System.nanoTime()
        assertEquals("timed out", failure(FakeBox(mode = FakeBox.Mode.STALL_HELLO), ms = 250))
        assertEquals("timed out", failure(FakeBox(mode = FakeBox.Mode.STALL_TAG), ms = 250))
        assertTrue(System.nanoTime() - t0 < 5_000_000_000L)
    }

    @Test
    fun failureMessagesCarryNoKeyMaterial() {
        val keyHex = TEST_KEY.joinToString("") { "%02x".format(it) }
        val msgs = listOf(
            failure(FakeBox(), key = OTHER_KEY),
            failure(FakeBox(mode = FakeBox.Mode.BAD_BOX_TAG)),
            failure(FakeBox(mode = FakeBox.Mode.BAD_MAGIC))
        )
        msgs.forEach { assertFalse(it, it.contains(keyHex)); assertTrue(it.length < 60) }
    }

    @Test
    fun bytesAfterTheBoxTagAreLeftForTheForwarding() {
        // The box's USB/IP request follows its tag at once; the handshake reads exactly 32 bytes of it
        val server = ServerSocket(0, 1, loopback).also { closeables.add(it) }
        Thread({
            val s = server.accept().also { closeables.add(it) }
            FakeBox().serve(s)
            s.getOutputStream().write("REQUEST".toByteArray())
        }).apply { isDaemon = true }.start()
        val s = Socket(loopback, server.localPort).also { closeables.add(it) }
        AuthV1.phoneAuth(s, softwareMac(TEST_KEY), 5_000)
        val buf = ByteArray(7)
        var got = 0
        while (got < 7) got += s.getInputStream().read(buf, got, 7 - got)
        assertEquals("REQUEST", String(buf))
    }

    @Test
    fun keyHexTakesExactly64HexCharacters() {
        val good = "00112233445566778899aabbccddeeff00112233445566778899AABBCCDDEEFF"
        assertEquals(32, KeyHex.parse(good)!!.size)
        assertEquals(0xAA.toByte(), KeyHex.parse(good)!![10])
        assertNotNull(KeyHex.parse("  $good\n"))
        assertNull(KeyHex.parse(good.dropLast(1)))
        assertNull(KeyHex.parse(good + "0"))
        assertNull(KeyHex.parse(good.replaceRange(5, 6, "g")))
        assertNull(KeyHex.parse(good.replaceRange(5, 6, "１"))) // a fullwidth digit
        assertNull(KeyHex.parse(good.replaceRange(5, 6, " ")))
        assertNull(KeyHex.parse(""))
        assertTrue(KeyHex.isValid(good))
        assertFalse(KeyHex.isValid("abc"))
    }
}
