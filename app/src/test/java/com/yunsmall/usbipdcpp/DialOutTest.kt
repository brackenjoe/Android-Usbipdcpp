package com.yunsmall.usbipdcpp

import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * DialOut against real loopback sockets. Waits are a [TestSleeper], so the
 * backoff schedule is read off the delays the loop asks for and no test
 * sleeps 30 s. The remote is a [FakeBox]: the box's side of auth v1 comes
 * before anything else, as on the real box.
 */
class DialOutTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val servers = mutableListOf<FakeServer>()
    private val dialOuts = mutableListOf<DialOut>()

    @After
    fun tearDown() {
        try {
            dialOuts.forEach { it.stop() }
            dialOuts.forEach { it.awaitStopped(5_000) }
            // Whatever the test did, once every loop has ended nothing may stay
            // registered: each registration was withdrawn exactly once
            assertEquals(registrar.registered.sorted(), registrar.unregistered.sorted())
        } finally {
            servers.forEach { it.close() }
        }
    }

    // ---- the registrar and the local socket, recorded in one ordered log ----

    /** Every registrar call and every local-socket step, in the order they happened. */
    private val events = CopyOnWriteArrayList<String>()

    private val registrar = object : DialPortRegistrar {
        val registered = CopyOnWriteArrayList<Int>()
        val unregistered = CopyOnWriteArrayList<Int>()
        override fun register(port: Int) {
            events.add("register:$port")
            registered.add(port)
        }

        override fun unregister(port: Int) {
            events.add("unregister:$port")
            unregistered.add(port)
        }
    }

    /**
     * The dialer's local socket with its steps logged. It starts with reuseAddress
     * ON, so a dialer that does not turn it off shows in the log. [blockConnect]:
     * connect never completes by itself; it ends when the socket is closed.
     * [onBound] runs right after bind succeeds. [failBind] makes bind throw.
     */
    private inner class RecordingSocket(
        private val blockConnect: Boolean = false,
        private val failBind: Boolean = false,
        private val onBound: () -> Unit = {}
    ) : Socket() {
        @Volatile var boundPort = 0
        val connecting = CountDownLatch(1)
        private val closed = CountDownLatch(1)

        init {
            reuseAddress = true
        }

        override fun bind(bindpoint: SocketAddress?) {
            events.add("bind:reuse=$reuseAddress:${(bindpoint as InetSocketAddress).address.hostAddress}:${bindpoint.port}")
            if (failBind) throw BindException("test: bind refused")
            super.bind(bindpoint)
            boundPort = localPort
            events.add("bound:$boundPort")
            onBound()
        }

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            events.add("connect:${(endpoint as InetSocketAddress).port}")
            if (blockConnect) {
                connecting.countDown()
                closed.await()
                throw SocketException("test: closed while connecting")
            }
            super.connect(endpoint, timeout)
        }

        override fun close() {
            events.add("close:$boundPort")
            super.close()
            closed.countDown()
        }
    }

    /** Index of the first log entry [what], failing the test if there is none. */
    private fun at(what: String): Int {
        val i = events.indexOf(what)
        if (i < 0) throw AssertionError("no '$what' in $events")
        return i
    }

    // ---- helpers ----

    /** Accepts connections on a free loopback port; keeps every accepted socket. */
    private inner class FakeServer(
        private val box: FakeBox? = null,
        private val onAccept: (Socket) -> Unit = {}
    ) {
        val socket = ServerSocket(0, 50, loopback)
        val port get() = socket.localPort
        val accepted = LinkedBlockingQueue<Socket>()
        val acceptedAll = CopyOnWriteArrayList<Socket>()
        private val count = AtomicInteger()
        val acceptCount get() = count.get()

        init {
            servers.add(this)
            Thread({
                while (!socket.isClosed) {
                    val s = try {
                        socket.accept()
                    } catch (e: IOException) {
                        return@Thread
                    }
                    count.incrementAndGet()
                    acceptedAll.add(s)
                    Thread({
                        // A box speaks auth first; a connection is "accepted" for the
                        // test once the handshake is over, a plain server's at once
                        if (box != null && !box.serve(s)) return@Thread
                        accepted.add(s)
                        runCatching { onAccept(s) }
                    }, "fake-server-conn").apply { isDaemon = true }.start()
                }
            }, "fake-server-accept").apply { isDaemon = true }.start()
        }

        fun close() {
            runCatching { socket.close() }
            acceptedAll.forEach { runCatching { it.close() } }
        }

        fun nextAccepted(): Socket =
            accepted.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no connection accepted")
    }

    /** Echoes every byte back until the peer closes. */
    private val echo: (Socket) -> Unit = { s ->
        val buf = ByteArray(1024)
        val input = s.getInputStream()
        val output = s.getOutputStream()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
        }
    }

    /**
     * Lets the first [passThrough] waits return at once, then blocks (until
     * interrupted) on the next one. Every requested delay is recorded.
     */
    private class TestSleeper(private val passThrough: Int) : Sleeper {
        val delays = CopyOnWriteArrayList<Long>()
        override fun sleep(ms: Long) {
            val index = delays.size
            delays.add(ms)
            if (index >= passThrough) Thread.sleep(Long.MAX_VALUE)
        }

        fun awaitDelays(n: Int) = await("$n sleeps (have ${delays.size})") { delays.size >= n }
    }

    /** A remote that runs the box's side of auth v1 (the right key unless [box] says otherwise) before [onAccept]. */
    private fun remoteBox(box: FakeBox = FakeBox(log = { events.add(it) }), onAccept: (Socket) -> Unit = {}) =
        FakeServer(box, onAccept)

    private fun dialOut(
        remote: Int,
        local: Int,
        sleeper: Sleeper,
        localSocket: () -> Socket = { Socket() },
        authenticator: Authenticator = SoftwareAuthenticator(),
        authTimeoutMs: Long = 5_000,
        nonceSource: () -> ByteArray = AuthV1::randomNonce,
        vpn: VpnNetworkProvider = FakeVpnProvider.loopback(),
        host: String = "127.0.0.1"
    ): DialOut =
        DialOut(
            host, remote, local, registrar, authenticator, vpn,
            sleeper = sleeper, connectTimeoutMs = 2_000, localSocketFactory = localSocket,
            authTimeoutMs = authTimeoutMs, nonceSource = nonceSource
        ).also { dialOuts.add(it) }

    /** A port nothing listens on. */
    private fun refusedPort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    /** True once the peer has closed: EOF, or a reset (closing with unread data). */
    private fun closedByPeer(s: Socket): Boolean {
        s.soTimeout = 10_000
        return try {
            s.getInputStream().read() < 0
        } catch (e: java.net.SocketTimeoutException) {
            false
        } catch (e: IOException) {
            true
        }
    }

    private fun readFully(s: Socket, n: Int): ByteArray {
        s.soTimeout = 10_000
        val out = ByteArray(n)
        var got = 0
        val input = s.getInputStream()
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) throw AssertionError("EOF after $got of $n bytes")
            got += r
        }
        return out
    }

    private fun DialOut.awaitConnected(n: Int) = await("$n connected loops") {
        loopStatuses.value.count { it.phase == LoopStatus.Phase.CONNECTED } >= n
    }

    // ---- (a) bytes flow both ways; closing either side closes the other ----

    @Test
    fun bytesFlowBothWays() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val d = dialOut(remote.port, local.port, TestSleeper(0))
        d.setLoopCount(1)
        val r = remote.nextAccepted()
        // remote -> local, echoed back: local -> remote
        r.getOutputStream().write("hello".toByteArray())
        assertEquals("hello", String(readFully(r, 5)))
        r.getOutputStream().write("second".toByteArray())
        assertEquals("second", String(readFully(r, 6)))
        d.awaitConnected(1)
        await("byte counters") {
            d.loopStatuses.value.firstOrNull()?.let { it.bytesIn >= 11 && it.bytesOut >= 11 } == true
        }
    }

    @Test
    fun closingTheRemoteClosesTheLocalSide() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val d = dialOut(remote.port, local.port, TestSleeper(0))
        d.setLoopCount(1)
        val r = remote.nextAccepted()
        r.getOutputStream().write("x".toByteArray())
        assertEquals("x", String(readFully(r, 1)))
        val l = local.nextAccepted()
        r.close()
        assertTrue("local side not closed", closedByPeer(l))
    }

    @Test
    fun closingTheLocalSideClosesTheRemote() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = { s -> s.getInputStream().read(); s.close() })
        val d = dialOut(remote.port, local.port, TestSleeper(0))
        d.setLoopCount(1)
        val r = remote.nextAccepted()
        r.getOutputStream().write("x".toByteArray()) // makes the local server close
        assertTrue("remote side not closed", closedByPeer(r))
    }

    // ---- (b) accept and close with no byte: backoff grows ----

    @Test
    fun remoteThatClosesAtOnceBacksOffOneTwoFour() {
        val remote = FakeServer { it.close() }
        val local = FakeServer(onAccept = echo)
        val sleeper = TestSleeper(3)
        val d = dialOut(remote.port, local.port, sleeper)
        d.setLoopCount(1)
        sleeper.awaitDelays(4)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), sleeper.delays.take(4))
        assertEquals(LoopStatus.Phase.WAITING, d.loopStatuses.value.single().phase)
        assertNotNull(d.loopStatuses.value.single().lastError)
    }

    // ---- (c) a connection that authenticated: redial after 1 s, backoff reset ----

    @Test
    fun authenticatedConnectionRedialsAfterOneSecondAndResetsBackoff() {
        val n = AtomicInteger()
        // Connections 1, 2: close at once (no handshake). 3: the box proves the key, the
        // session runs, then it closes. 4, 5: close at once.
        val remote = FakeServer { s ->
            if (n.incrementAndGet() == 3) {
                FakeBox().serve(s)
                // let the local side connect before the close
                Thread.sleep(100)
            }
            s.close()
        }
        val local = FakeServer(onAccept = echo)
        val sleeper = TestSleeper(4)
        val d = dialOut(remote.port, local.port, sleeper)
        d.setLoopCount(1)
        sleeper.awaitDelays(5)
        // 1 s, 2 s (no auth); 1 s (auth: reset); 1 s again (first failure after the reset, not 4 s); 2 s
        assertEquals(listOf(1_000L, 2_000L, 1_000L, 1_000L, 2_000L), sleeper.delays.take(5))
        assertEquals(1, local.acceptCount)
    }

    @Test
    fun authenticatedButLocalDownBacksOffLikeAnyFailure() {
        // The box proved the key but there is no local server to forward to: not a
        // session, so the backoff is not reset by it
        val remote = remoteBox()
        val sleeper = TestSleeper(2)
        val d = dialOut(remote.port, refusedPort(), sleeper)
        d.setLoopCount(1)
        sleeper.awaitDelays(3)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), sleeper.delays.take(3))
    }

    // ---- auth v1: the local side does not exist until the box has proved the key ----

    private inner class Created {
        val count = AtomicInteger()
        val factory: () -> Socket = { count.incrementAndGet(); Socket() }
    }

    private fun assertAuthFailsWithoutLocal(
        box: FakeBox,
        authenticator: Authenticator = SoftwareAuthenticator(),
        authTimeoutMs: Long = 5_000,
        nonceSource: () -> ByteArray = AuthV1::randomNonce,
        expectedError: String
    ) {
        val remote = remoteBox(box)
        val local = FakeServer(onAccept = echo)
        val created = Created()
        val sleeper = TestSleeper(2)
        val d = dialOut(
            remote.port, local.port, sleeper, created.factory,
            authenticator = authenticator, authTimeoutMs = authTimeoutMs, nonceSource = nonceSource
        )
        d.setLoopCount(1)
        sleeper.awaitDelays(2)
        // Backoff, not the 1 s of a session
        assertEquals(listOf(1_000L, 2_000L), sleeper.delays.take(2))
        assertEquals("local socket created", 0, created.count.get())
        assertTrue("local registered: ${registrar.registered}", registrar.registered.isEmpty())
        assertEquals(0, local.acceptCount)
        assertTrue("local socket touched: $events", events.none { it.startsWith("bind:") || it.startsWith("connect:") })
        val err = d.loopStatuses.value.single().lastError
        assertNotNull(err)
        assertTrue("error was '$err'", err!!.contains(expectedError))
    }

    @Test
    fun rightKeyOpensTheLocalSideOnlyAfterTheBoxTag() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val created = Created()
        // the recording socket logs bind and connect in the same log as the box's "tag_sent"
        val d = dialOut(
            remote.port, local.port, TestSleeper(1_000_000),
            { created.factory().let { RecordingSocket() } }
        )
        d.setLoopCount(1)
        d.awaitConnected(1)
        assertEquals(1, created.count.get())
        assertTrue(at("box:tag_sent") < at(events.first { it.startsWith("bind:") }))
        assertTrue(at("box:tag_sent") < at(events.first { it.startsWith("register:") }))
        assertEquals(1, local.acceptCount)
        assertEquals(1, registrar.registered.size)
    }

    @Test
    fun wrongKeyOnThePhoneFailsAndNoLocalSocketIsCreated() =
        assertAuthFailsWithoutLocal(
            FakeBox(), authenticator = SoftwareAuthenticator(OTHER_KEY),
            expectedError = "host closed during authentication"
        )

    @Test
    fun wrongKeyOnTheBoxFailsAndNoLocalSocketIsCreated() =
        assertAuthFailsWithoutLocal(
            FakeBox(OTHER_KEY), expectedError = "host closed during authentication"
        )

    @Test
    fun wrongMagicFails() =
        assertAuthFailsWithoutLocal(FakeBox(mode = FakeBox.Mode.BAD_MAGIC), expectedError = "bad hello")

    @Test
    fun truncatedHelloFails() =
        assertAuthFailsWithoutLocal(
            FakeBox(mode = FakeBox.Mode.TRUNCATED_HELLO), expectedError = "host closed during authentication"
        )

    @Test
    fun boxTagMismatchFails() =
        assertAuthFailsWithoutLocal(
            FakeBox(mode = FakeBox.Mode.BAD_BOX_TAG), expectedError = "host tag does not verify"
        )

    @Test
    fun boxTagUnderAnotherKeyFails() =
        assertAuthFailsWithoutLocal(
            FakeBox(mode = FakeBox.Mode.WRONG_BOX_KEY), expectedError = "host tag does not verify"
        )

    @Test
    fun phoneTagReflectedBackAsTheBoxTagFails() =
        assertAuthFailsWithoutLocal(
            FakeBox(mode = FakeBox.Mode.REFLECT_PHONE_TAG), expectedError = "host tag does not verify"
        )

    @Test
    fun reflectedNonceAbortsBeforeAnythingIsSent() {
        // The box's nonce_b is the very value the phone is about to pick. The box would
        // accept a tag computed over it; the phone must not send one
        val fixed = ByteArray(32) { (0x30 + it).toByte() }
        val box = FakeBox(nonceB = { fixed.copyOf() })
        assertAuthFailsWithoutLocal(box, nonceSource = { fixed.copyOf() }, expectedError = "reflected nonce")
        assertEquals("the phone sent bytes to a reflecting box", 0, box.phoneBytes.get())
    }

    @Test
    fun silentBoxTimesOutAtTheHello() {
        val t0 = System.nanoTime()
        assertAuthFailsWithoutLocal(
            FakeBox(mode = FakeBox.Mode.STALL_HELLO), authTimeoutMs = 300, expectedError = "timed out"
        )
        // two attempts of 300 ms, nowhere near the default 10 s
        assertTrue(System.nanoTime() - t0 < 8_000_000_000L)
    }

    @Test
    fun boxThatNeverSendsItsTagTimesOut() =
        assertAuthFailsWithoutLocal(
            FakeBox(mode = FakeBox.Mode.STALL_TAG), authTimeoutMs = 300, expectedError = "timed out"
        )

    @Test
    fun idleConnectionOutlivesTheAuthTimeout() {
        // The read timeout of the handshake must not stay on the socket: a quiet
        // USB/IP connection is normal
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val d = dialOut(remote.port, local.port, TestSleeper(0), authTimeoutMs = 300)
        d.setLoopCount(1)
        val r = remote.nextAccepted()
        d.awaitConnected(1)
        Thread.sleep(900)
        assertEquals(LoopStatus.Phase.CONNECTED, d.loopStatuses.value.single().phase)
        r.getOutputStream().write("late".toByteArray())
        assertEquals("late", String(readFully(r, 4)))
    }

    // ---- the key's window ----

    /** Every wait blocks until [release]: the test steps the loop. Delays are recorded. */
    private class SteppedSleeper : Sleeper {
        val delays = CopyOnWriteArrayList<Long>()
        private val permits = java.util.concurrent.Semaphore(0)
        override fun sleep(ms: Long) {
            delays.add(ms)
            permits.acquire()
        }

        fun release() = permits.release()
    }

    @Test
    fun lockedKeyDialsNothingAndResumesWhenTheKeyWorks() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val auth = SoftwareAuthenticator(failWith = KeyUnavailableException.Reason.LOCKED)
        val sleeper = SteppedSleeper()
        val d = dialOut(remote.port, local.port, sleeper, authenticator = auth)
        d.setLoopCount(1)
        await("a locked loop") { d.loopStatuses.value.singleOrNull()?.phase == LoopStatus.Phase.LOCKED }
        sleeper.release() // one more probe, still locked
        await("a second probe") { sleeper.delays.size >= 2 }
        assertEquals(listOf(DialOut.LOCKED_PROBE_MS, DialOut.LOCKED_PROBE_MS), sleeper.delays.take(2))
        assertEquals("the box was dialed while locked", 0, remote.acceptCount)
        assertEquals(0, local.acceptCount)
        assertNull(d.loopStatuses.value.single().lastError)

        // the fingerprint opens the window: the next probe works, and nothing else is needed
        auth.failWith = null
        sleeper.release()
        d.awaitConnected(1)
        assertEquals(1, remote.acceptCount)
        assertEquals(1, local.acceptCount)
    }

    @Test
    fun lockedLoopIsRetiredAtOnce() {
        val auth = SoftwareAuthenticator(failWith = KeyUnavailableException.Reason.LOCKED)
        val d = dialOut(refusedPort(), refusedPort(), SteppedSleeper(), authenticator = auth)
        d.setLoopCount(1)
        await("a locked loop") { d.loopStatuses.value.singleOrNull()?.phase == LoopStatus.Phase.LOCKED }
        d.setLoopCount(0)
        await("the loop to end") { d.loopStatuses.value.isEmpty() }
    }

    @Test
    fun connectionThatIsUpSurvivesTheWindowEnding() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val auth = SoftwareAuthenticator()
        val d = dialOut(remote.port, local.port, TestSleeper(1_000_000), authenticator = auth)
        d.setLoopCount(1)
        val r = remote.nextAccepted()
        d.awaitConnected(1)
        val calls = auth.calls.get()
        auth.failWith = KeyUnavailableException.Reason.LOCKED // the window is over
        r.getOutputStream().write("still here".toByteArray())
        assertEquals("still here", String(readFully(r, 10)))
        assertEquals(LoopStatus.Phase.CONNECTED, d.loopStatuses.value.single().phase)
        assertEquals("the key was used after the handshake", calls, auth.calls.get())
    }

    @Test
    fun missingOrInvalidatedKeyIsAnErrorWithBackoffAndDialsNothing() {
        for (reason in listOf(KeyUnavailableException.Reason.NO_KEY, KeyUnavailableException.Reason.INVALIDATED)) {
            val remote = remoteBox()
            val sleeper = TestSleeper(1)
            val d = dialOut(
                remote.port, refusedPort(), sleeper, authenticator = SoftwareAuthenticator(failWith = reason)
            )
            d.setLoopCount(1)
            sleeper.awaitDelays(2)
            assertEquals(listOf(1_000L, 2_000L), sleeper.delays.take(2))
            assertEquals(0, remote.acceptCount)
            assertNotNull(d.loopStatuses.value.single().lastError)
        }
    }

    @Test
    fun noKeyNoDialing() {
        assertTrue(dialTargetUsable(true, true, "box", 3241, keyPresent = true))
        assertFalse(dialTargetUsable(true, true, "box", 3241, keyPresent = false))
        assertFalse(dialTargetUsable(false, true, "box", 3241, keyPresent = true))
        assertFalse(dialTargetUsable(true, false, "box", 3241, keyPresent = true))
        assertFalse(dialTargetUsable(true, true, " ", 3241, keyPresent = true))
        assertFalse(dialTargetUsable(true, true, "box", 0, keyPresent = true))
    }

    // ---- (d) remote connect refused ----

    @Test
    fun refusedRemoteBacksOffToTheThirtySecondCap() {
        val local = FakeServer(onAccept = echo)
        val sleeper = TestSleeper(8)
        val d = dialOut(refusedPort(), local.port, sleeper)
        d.setLoopCount(1)
        sleeper.awaitDelays(9)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            sleeper.delays.take(8)
        )
        assertEquals(0, local.acceptCount) // never dials local when the remote is down
        assertNotNull(d.loopStatuses.value.single().lastError)
    }

    // ---- (e) local connect refused: remote closed, backoff ----

    @Test
    fun refusedLocalClosesTheRemoteAndBacksOff() {
        val remote = remoteBox()
        val sleeper = TestSleeper(2)
        val d = dialOut(remote.port, refusedPort(), sleeper)
        d.setLoopCount(1)
        // the remote side was connected, then closed by us
        assertTrue("remote not closed", closedByPeer(remote.nextAccepted()))
        sleeper.awaitDelays(3)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), sleeper.delays.take(3))
        assertTrue(remote.acceptCount >= 3) // and it dialed again after each wait
    }

    // ---- (f) the loop count follows the device count ----

    @Test
    fun loopCountFollowsDeviceCountUpAndDown() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        // waits return at once: a loop whose connection ended redials
        val d = dialOut(remote.port, local.port, TestSleeper(1_000_000))

        d.setLoopCount(2)
        d.awaitConnected(2)
        assertEquals(2, d.loopStatuses.value.size)
        d.setLoopCount(3)
        d.awaitConnected(3)
        assertEquals(3, remote.acceptCount)

        // Down to 1: all three hold connections, so two are ending but live
        d.setLoopCount(1)
        assertEquals(1, d.activeLoopCount)
        assertEquals(3, d.loopStatuses.value.size)
        assertEquals(2, d.loopStatuses.value.count { it.ending })
        // closing every remote connection ends the retired loops; the kept one redials
        val before = remote.acceptCount
        remote.acceptedAll.toList().forEach { it.close() }
        await("two loops to end") { d.loopStatuses.value.size == 1 }
        await("the kept loop to redial") {
            remote.acceptCount > before && d.loopStatuses.value.single().phase == LoopStatus.Phase.CONNECTED
        }
        assertFalse(d.loopStatuses.value.single().ending)

        // Down to 0: the live connection holds the loop until it closes
        d.setLoopCount(0)
        assertEquals(1, d.loopStatuses.value.size)
        assertTrue(d.loopStatuses.value.single().ending)
        remote.acceptedAll.toList().forEach { it.close() }
        await("the last loop to end") { d.loopStatuses.value.isEmpty() }
        assertTrue(d.awaitStopped(5_000))
    }

    @Test
    fun loopsThatAreWaitingEndAtOnceWhenRemoved() {
        val local = FakeServer(onAccept = echo)
        val sleeper = TestSleeper(0) // every wait blocks
        val d = dialOut(refusedPort(), local.port, sleeper)
        d.setLoopCount(2)
        await("both loops waiting") {
            d.loopStatuses.value.count { it.phase == LoopStatus.Phase.WAITING } == 2
        }
        d.setLoopCount(1)
        await("one loop to end") { d.loopStatuses.value.size == 1 }
        d.setLoopCount(0)
        await("the other loop to end") { d.loopStatuses.value.isEmpty() }
    }

    // ---- (h) the local connection is the registered one ----

    @Test
    fun registersTheBoundPortAfterBindAndBeforeConnect() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val sock = RecordingSocket()
        val d = dialOut(remote.port, local.port, TestSleeper(1_000_000), { sock })
        d.setLoopCount(1)
        d.awaitConnected(1)
        val port = sock.boundPort
        assertTrue("no local port bound", port > 0)
        // reuseAddress was switched off before bind (the socket starts with it on),
        // and the bind is to 127.0.0.1 on a port the system picks
        val bind = events.first { it.startsWith("bind:") }
        assertEquals("bind:reuse=false:127.0.0.1:0", bind)
        assertTrue(at(bind) < at("bound:$port"))
        assertTrue(at("bound:$port") < at("register:$port"))
        assertTrue(at("register:$port") < at("connect:${local.port}"))
        // and the server really sees the registered port as the peer's source
        val accepted = local.nextAccepted()
        assertEquals(port, accepted.port)
        assertEquals("127.0.0.1", (accepted.remoteSocketAddress as InetSocketAddress).address.hostAddress)
        assertEquals(listOf(port), registrar.registered.toList())
    }

    @Test
    fun registersOncePerDial() {
        val remote = remoteBox { it.close() }
        val local = FakeServer(onAccept = echo)
        val sleeper = TestSleeper(2)
        val d = dialOut(remote.port, local.port, sleeper)
        d.setLoopCount(1)
        sleeper.awaitDelays(3)
        // each dial registered and withdrew its own port, and 3 dials made 3 registrations
        assertEquals(3, registrar.registered.size)
        await("three withdrawals") { registrar.unregistered.size == 3 }
    }

    // ---- (i) every path that does not end in an accept withdraws the port first ----

    @Test
    fun remoteConnectFailureNeverBindsOrRegisters() {
        val local = FakeServer(onAccept = echo)
        val sleeper = TestSleeper(1)
        val d = dialOut(refusedPort(), local.port, sleeper, { RecordingSocket() })
        d.setLoopCount(1)
        sleeper.awaitDelays(2)
        assertTrue(registrar.registered.isEmpty())
        assertTrue("local socket bound with the remote down: $events", events.none { it.startsWith("bind:") })
    }

    @Test
    fun localBindFailureDoesNotRegister() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val sleeper = TestSleeper(1)
        val d = dialOut(remote.port, local.port, sleeper, { RecordingSocket(failBind = true) })
        d.setLoopCount(1)
        sleeper.awaitDelays(2)
        assertTrue(registrar.registered.isEmpty())
        assertTrue(events.none { it.startsWith("connect:") })
        assertNotNull(d.loopStatuses.value.single().lastError)
        assertEquals(0, local.acceptCount)
    }

    @Test
    fun failedLocalConnectWithdrawsBeforeClosing() {
        val remote = remoteBox()
        val sleeper = TestSleeper(1)
        val d = dialOut(remote.port, refusedPort(), sleeper, { RecordingSocket() })
        d.setLoopCount(1)
        sleeper.awaitDelays(2)
        val port = registrar.registered.first()
        assertTrue(at("register:$port") < at("unregister:$port"))
        assertTrue("closed before the port was withdrawn: $events", at("unregister:$port") < at("close:$port"))
        // every dial that registered also withdrew
        assertEquals(registrar.registered.size, registrar.unregistered.size)
    }

    @Test
    fun retireWhileConnectingWithdrawsBeforeClosing() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val sock = RecordingSocket(blockConnect = true)
        val d = dialOut(remote.port, local.port, TestSleeper(1_000_000), { sock })
        d.setLoopCount(1)
        assertTrue("never reached the local connect", sock.connecting.await(10, TimeUnit.SECONDS))
        val port = sock.boundPort
        assertEquals(LoopStatus.Phase.CONNECTING, d.loopStatuses.value.single().phase)

        d.setLoopCount(0) // retire: closes the sockets from this thread
        await("the loop to end") { d.loopStatuses.value.isEmpty() }
        assertEquals(listOf(port), registrar.unregistered.toList()) // once, not again by the loop's own close
        assertTrue("closed before the port was withdrawn: $events", at("unregister:$port") < at("close:$port"))
    }

    @Test
    fun stopWhileConnectingWithdrawsBeforeClosing() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val sock = RecordingSocket(blockConnect = true)
        val d = dialOut(remote.port, local.port, TestSleeper(1_000_000), { sock })
        d.setLoopCount(1)
        assertTrue("never reached the local connect", sock.connecting.await(10, TimeUnit.SECONDS))
        val port = sock.boundPort

        d.stop()
        assertTrue(d.awaitStopped(10_000))
        assertEquals(listOf(port), registrar.unregistered.toList())
        assertTrue("closed before the port was withdrawn: $events", at("unregister:$port") < at("close:$port"))
    }

    @Test
    fun stopWhileConnectedWithdrawsBeforeClosing() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val sock = RecordingSocket()
        val d = dialOut(remote.port, local.port, TestSleeper(1_000_000), { sock })
        d.setLoopCount(1)
        d.awaitConnected(1)
        val port = sock.boundPort

        d.stop() // after the accept the server has consumed the port: the withdrawal is the no-op kind
        assertTrue(d.awaitStopped(10_000))
        assertTrue("closed before the port was withdrawn: $events", at("unregister:$port") < at("close:$port"))
    }

    @Test
    fun endOfAConnectionWithdrawsBeforeClosing() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val sock = RecordingSocket()
        val sleeper = TestSleeper(0)
        val d = dialOut(remote.port, local.port, sleeper, { sock })
        d.setLoopCount(1)
        val r = remote.nextAccepted()
        r.getOutputStream().write(1)
        d.awaitConnected(1)
        r.close()
        sleeper.awaitDelays(1)
        val port = sock.boundPort
        assertTrue("closed before the port was withdrawn: $events", at("unregister:$port") < at("close:$port"))
    }

    // A retire or stop that closes the socket from another thread between its
    // bind and its registration must not leave a registration for a free port
    @Test
    fun stopBetweenBindAndRegisterRegistersNothing() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        lateinit var d: DialOut
        d = dialOut(remote.port, local.port, TestSleeper(1_000_000), { RecordingSocket(onBound = { d.stop() }) })
        d.setLoopCount(1)
        assertTrue(d.awaitStopped(10_000))
        assertTrue("registered after the close: $events", registrar.registered.isEmpty())
        assertTrue(events.none { it.startsWith("connect:") })
        assertEquals(0, local.acceptCount)
    }

    @Test
    fun retireBetweenBindAndRegisterRegistersNothing() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        lateinit var d: DialOut
        d = dialOut(remote.port, local.port, TestSleeper(1_000_000), { RecordingSocket(onBound = { d.setLoopCount(0) }) })
        d.setLoopCount(1)
        await("the loop to end") { d.loopStatuses.value.isEmpty() }
        assertTrue("registered after the close: $events", registrar.registered.isEmpty())
        assertEquals(0, local.acceptCount)
    }

    // ---- (g) stop ends every loop and closes every socket ----

    @Test
    fun stopEndsAllLoopsAndClosesAllSockets() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val d = dialOut(remote.port, local.port, TestSleeper(1_000_000))
        d.setLoopCount(3)
        d.awaitConnected(3)
        await("three local connections") { local.acceptCount == 3 }

        d.stop()
        assertTrue("loops did not end", d.awaitStopped(10_000))
        assertTrue(d.loopStatuses.value.isEmpty())
        remote.acceptedAll.forEach { assertTrue("remote socket still open", closedByPeer(it)) }
        local.acceptedAll.forEach { assertTrue("local socket still open", closedByPeer(it)) }

        // and it stays stopped
        d.setLoopCount(2)
        Thread.sleep(200)
        assertEquals(3, remote.acceptCount)
        assertTrue(d.loopStatuses.value.isEmpty())
    }

    @Test
    fun stopInterruptsALoopThatIsWaitingToDial() {
        val local = FakeServer(onAccept = echo)
        val d = dialOut(refusedPort(), local.port, TestSleeper(0))
        d.setLoopCount(1)
        await("loop waiting") { d.loopStatuses.value.singleOrNull()?.phase == LoopStatus.Phase.WAITING }
        d.stop()
        assertTrue(d.awaitStopped(5_000))
    }

    // ---- the VPN: dial over it and nothing else ----

    @Test
    fun noVpnMeansNoDialAndTheNoVpnStatusAndBackoff() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val sleeper = TestSleeper(3)
        val vpn = FakeVpnProvider(null)
        val created = AtomicInteger()
        val d = dialOut(remote.port, local.port, sleeper, localSocket = { created.incrementAndGet(); Socket() }, vpn = vpn)
        d.setLoopCount(1)
        sleeper.awaitDelays(4)
        // backs off like a failed connect
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), sleeper.delays.take(4))
        val status = d.loopStatuses.value.single()
        assertEquals(LoopStatus.Phase.NO_VPN, status.phase)
        assertEquals("no VPN", status.lastError)
        assertTrue(status.waitSecondsLeft(DialOut.defaultClockMs()) > 0)
        // nothing was dialed, locally or remotely
        assertEquals(0, remote.acceptCount)
        assertEquals(0, local.acceptCount)
        assertEquals(0, created.get())
        assertTrue(registrar.registered.isEmpty())
        assertTrue(vpn.finds.get() >= 4)
    }

    @Test
    fun theLoopDialsAsSoonAsAVpnAppears() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val vpn = FakeVpnProvider(null)
        val d = dialOut(remote.port, local.port, TestSleeper(1), vpn = vpn)
        d.setLoopCount(1)
        await("no VPN") { d.loopStatuses.value.singleOrNull()?.phase == LoopStatus.Phase.NO_VPN }
        assertEquals(0, remote.acceptCount)
        // the sleeper blocks after one wait, so give it the VPN and a fresh loop
        vpn.network = FakeVpnNetwork()
        d.setLoopCount(0)
        d.setLoopCount(1)
        d.awaitConnected(1)
        assertEquals(1, remote.acceptCount)
    }

    @Test
    fun withAVpnTheSocketIsBoundToItBeforeItConnects() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val network = FakeVpnNetwork(log = { events.add(it) })
        val d = dialOut(remote.port, local.port, TestSleeper(0), vpn = FakeVpnProvider(network))
        d.setLoopCount(1)
        d.awaitConnected(1)
        assertEquals(listOf(false), network.connectedAtBind.toList())
        assertEquals(1, remote.acceptCount)
        assertTrue(at("vpn-resolve:127.0.0.1") < at("vpn-bind"))
        // the box's side of the handshake and the local connection come after
        assertTrue(at("vpn-bind") < at("register:${registrar.registered.single()}"))
    }

    @Test
    fun theHostIsResolvedThroughTheVpnAndNotTheSystemResolver() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        // a name no system resolver knows (RFC 6761 .invalid)
        val name = "box.tailnet.invalid"
        val network = FakeVpnNetwork(names = mapOf(name to listOf(loopback)))
        val d = dialOut(remote.port, local.port, TestSleeper(0), vpn = FakeVpnProvider(network), host = name)
        d.setLoopCount(1)
        d.awaitConnected(1)
        assertEquals(listOf(name), network.resolved.toList())
        assertEquals(1, remote.acceptCount)
    }

    @Test
    fun aSocketThatCannotBeBoundToTheVpnIsNeverConnected() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val network = FakeVpnNetwork(bindFails = true)
        val sleeper = TestSleeper(0)
        val created = AtomicInteger()
        val d = dialOut(
            remote.port, local.port, sleeper, localSocket = { created.incrementAndGet(); Socket() },
            vpn = FakeVpnProvider(network)
        )
        d.setLoopCount(1)
        sleeper.awaitDelays(1)
        assertEquals(listOf(1_000L), sleeper.delays.take(1))
        assertEquals(LoopStatus.Phase.WAITING, d.loopStatuses.value.single().phase)
        assertTrue(d.loopStatuses.value.single().lastError!!.contains("cannot bind"))
        assertEquals(0, remote.acceptCount)
        assertEquals(0, created.get())
    }

    @Test
    fun aHostThatDoesNotResolveThroughTheVpnFailsWithoutDialing() {
        val remote = remoteBox()
        val local = FakeServer(onAccept = echo)
        val network = FakeVpnNetwork(names = mapOf("gone.invalid" to emptyList()))
        val sleeper = TestSleeper(0)
        val d = dialOut(remote.port, local.port, sleeper, vpn = FakeVpnProvider(network), host = "gone.invalid")
        d.setLoopCount(1)
        sleeper.awaitDelays(1)
        assertTrue(d.loopStatuses.value.single().lastError!!.contains("no address"))
        assertEquals(0, remote.acceptCount)
    }

    @Test
    fun pickVpnNetworkPrefersTheActiveVpnThenAnyVpnThenNone() {
        val isVpn = { n: String -> n.startsWith("vpn") }
        assertEquals("vpn0", pickVpnNetwork("vpn0", listOf("wifi", "vpn1"), isVpn))
        assertEquals("vpn1", pickVpnNetwork("wifi", listOf("wifi", "vpn1"), isVpn))
        assertEquals("vpn1", pickVpnNetwork(null, listOf("cell", "vpn1"), isVpn))
        assertNull(pickVpnNetwork("wifi", listOf("wifi", "cell"), isVpn))
        assertNull(pickVpnNetwork<String>(null, emptyList(), isVpn))
    }
}

private fun await(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!cond()) {
        if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
        Thread.sleep(5)
    }
}
