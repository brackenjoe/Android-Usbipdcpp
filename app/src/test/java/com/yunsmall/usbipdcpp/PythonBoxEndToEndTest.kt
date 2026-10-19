package com.yunsmall.usbipdcpp

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * DialOut against the real `box_auth` of usbip_dialin.py, run by python3 in a
 * child process (box_auth_server.py). Skipped, and reported as skipped, when
 * python3 or the reference (env USBIP_DIALIN_REF, default this repository's
 * linux/usbip-dialin/usbip_dialin.py) is not on the machine.
 */
class PythonBoxEndToEndTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private var py: Process? = null
    private val lines = LinkedBlockingQueue<String>()
    private val dialOuts = mutableListOf<DialOut>()
    private val sockets = CopyOnWriteArrayList<AutoCloseable>()

    private val registrar = object : DialPortRegistrar {
        override fun register(port: Int) {}
        override fun unregister(port: Int) {}
    }

    private fun startBox(keyHex: String): Int {
        val ref = PythonReference.path()
        assumeTrue("reference not found: ${PythonReference.REF_ENV}", ref != null)
        val script = PythonReference.resource("box_auth_server.py")
        val p = try {
            ProcessBuilder("python3", "-I", script.path, ref!!.path, keyHex)
                .redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            assumeTrue("python3 not runnable", false)
            return 0
        }
        py = p
        Thread({
            p.inputStream.bufferedReader().forEachLine { lines.add(it) }
        }, "py-out").apply { isDaemon = true }.start()
        val first = lines.poll(20, TimeUnit.SECONDS) ?: error("python box printed nothing")
        check(first.startsWith("PORT ")) { "python box said: $first" }
        return first.removePrefix("PORT ").trim().toInt()
    }

    @After
    fun tearDown() {
        dialOuts.forEach { it.stop() }
        dialOuts.forEach { it.awaitStopped(5_000) }
        sockets.forEach { runCatching { it.close() } }
        py?.destroyForcibly()
    }

    private val hexKey = TEST_KEY.joinToString("") { "%02x".format(it) }

    /** A local USB/IP stand-in: counts accepts and collects what arrives on the first one. */
    private class LocalServer(val loopback: InetAddress, val sockets: MutableList<AutoCloseable>) {
        val server = ServerSocket(0, 5, loopback).also { sockets.add(it) }
        val accepts = AtomicInteger()
        val received = StringBuffer()

        init {
            Thread({
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (e: Exception) { return@Thread }
                    sockets.add(s)
                    accepts.incrementAndGet()
                    Thread({
                        runCatching {
                            val buf = ByteArray(64)
                            while (true) {
                                val n = s.getInputStream().read(buf)
                                if (n < 0) break
                                received.append(String(buf, 0, n))
                            }
                        }
                    }).apply { isDaemon = true }.start()
                }
            }).apply { isDaemon = true }.start()
        }
    }

    private fun await(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!cond()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun rightKeyIsAcceptedByTheRealBoxAndForwardingStarts() {
        val boxPort = startBox(hexKey)
        val local = LocalServer(loopback, sockets)
        val created = AtomicInteger()
        val d = DialOut(
            "127.0.0.1", boxPort, local.server.localPort, registrar, SoftwareAuthenticator(TEST_KEY), FakeVpnProvider.loopback(),
            sleeper = { Thread.sleep(Long.MAX_VALUE) }, localSocketFactory = { created.incrementAndGet(); Socket() }
        ).also { dialOuts.add(it) }
        d.setLoopCount(1)
        assertEquals("RESULT ok", lines.poll(20, TimeUnit.SECONDS))
        await("the local connection") { local.accepts.get() == 1 }
        // what the real box sent after its tag crossed the forwarding
        await("BOXOK at the local server") { local.received.toString() == "BOXOK" }
        assertEquals(1, created.get())
    }

    @Test
    fun wrongKeyIsRefusedByTheRealBoxAndTheLocalSideNeverExists() {
        val boxPort = startBox(hexKey)
        val local = LocalServer(loopback, sockets)
        val created = AtomicInteger()
        val d = DialOut(
            "127.0.0.1", boxPort, local.server.localPort, registrar, SoftwareAuthenticator(OTHER_KEY), FakeVpnProvider.loopback(),
            sleeper = { Thread.sleep(Long.MAX_VALUE) }, localSocketFactory = { created.incrementAndGet(); Socket() }
        ).also { dialOuts.add(it) }
        d.setLoopCount(1)
        val result = lines.poll(20, TimeUnit.SECONDS)
        assertEquals("RESULT refuse AuthError", result)
        await("the loop to give up") { d.loopStatuses.value.singleOrNull()?.lastError != null }
        assertTrue(d.loopStatuses.value.single().lastError!!.contains("authentication failed"))
        assertEquals(0, created.get())
        assertEquals(0, local.accepts.get())
    }
}
