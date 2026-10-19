package com.yunsmall.usbipdcpp

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Waits between dials. A seam for tests: one call per wait, with the whole duration. */
fun interface Sleeper {
    @Throws(InterruptedException::class)
    fun sleep(ms: Long)
}

/**
 * The server's connection filter admits only connections whose source port was
 * registered here: 127.0.0.1 and nothing else, one accept per registration
 * (usbipd_jni.cpp and dial_port_filter.h). An interface so the JVM tests need
 * no JNI. Both calls are synchronous and quick, and may come from any thread;
 * unregistering a port that is not registered (the filter consumed it) is a
 * harmless no-op.
 */
interface DialPortRegistrar {
    fun register(port: Int)
    fun unregister(port: Int)
}

/** What the UI shows for one dial loop. [bytesIn] and [bytesOut] are for the current connection. */
data class LoopStatus(
    val id: Int,
    val phase: Phase,
    /** Bytes read from the remote host (the box) on the current connection. */
    val bytesIn: Long = 0,
    /** Bytes read from the local USB/IP server on the current connection. */
    val bytesOut: Long = 0,
    /** WAITING: when the next dial starts, on [DialOut.defaultClockMs]'s clock. */
    val nextDialAtMs: Long = 0,
    val lastError: String? = null,
    /** Removed from the device count: ends when its current connection closes. */
    val ending: Boolean = false
) {
    /**
     * LOCKED: the key's window after the fingerprint is over (or the device is
     * locked), so nothing is dialed; the loop re-checks every [DialOut.LOCKED_PROBE_MS].
     * NO_VPN: the phone has no VPN network, so nothing is dialed; the loop backs
     * off as for a failed connect and looks again.
     */
    enum class Phase { CONNECTING, CONNECTED, WAITING, LOCKED, NO_VPN }

    /** Whole seconds until the next dial, rounded up; 0 when not waiting (WAITING or NO_VPN). */
    fun waitSecondsLeft(nowMs: Long): Long =
        if (phase == Phase.WAITING || phase == Phase.NO_VPN) maxOf(0L, (nextDialAtMs - nowMs + 999) / 1000) else 0L
}

/**
 * Dial-out: for each shared device, a loop that connects out to [host]:[port],
 * connects to the local USB/IP server at 127.0.0.1:[localPort], copies bytes
 * both ways, and when either side ends closes both and dials again. No
 * Android types, so the JVM unit tests drive it with real sockets.
 *
 * The local connection is the only one the server accepts. For each dial the
 * local socket is created with reuseAddress off, bound to 127.0.0.1 on a free
 * port, that port is registered with [registrar], and only then connected.
 * The registration is withdrawn before the socket is closed, on every path
 * (a failed connect, retire, stop, the end of the connection); after an
 * accept the server has already consumed it and the withdrawal does nothing.
 * Register and withdraw are serialised with the close under one lock per loop,
 * so a retire that closes the socket between its bind and its registration
 * cannot leave a registration behind for a port that is already free.
 *
 * Authentication (usbip-dialin auth v1, [AuthV1]): after the remote connect
 * and before the local socket is created, bound, registered or connected, the
 * phone's side of the handshake runs with a [Mac] from the [Authenticator]. A
 * failed handshake closes the remote and never touches the local side. The
 * key is asked for before even the remote connect: if the Keystore says the
 * window is over (LOCKED), nothing is dialed. A connection that is up is not
 * affected: the key is used only at the handshake.
 *
 * The VPN: the dial goes out over the VPN network and no other. After the key
 * and before any socket exists, [vpn] must find a VPN network; with none the
 * loop dials nothing (phase NO_VPN, backoff as for a failed connect). The host
 * is resolved through that network and the remote socket is bound to it before
 * it connects (so neither the lookup nor the connection can leave on the
 * carrier or WiFi when Tailscale is down). There is no setting to turn this off.
 *
 * Redial timing: if authentication completed on the connection and the
 * session then ran (the local side was connected), redial after 1 s and reset
 * the backoff; otherwise (connect failure, failed handshake, local connect
 * failure) back off 1, 2, 4, 8, 16, 30, 30... s. A LOCKED loop waits
 * [LOCKED_PROBE_MS] and asks the Keystore again, without touching the backoff.
 * The box sends its USB/IP request only after the handshake, so a completed
 * handshake is the proof that the box holds the key. Handshake bytes are not
 * counted in the byte counters.
 *
 * Threads: one per loop (it also copies remote to local) and one more per
 * live connection (local to remote). Sockets are blocking; closing one
 * unblocks its reader.
 */
class DialOut(
    val host: String,
    val port: Int,
    val localPort: Int,
    private val registrar: DialPortRegistrar,
    private val authenticator: Authenticator,
    /** The VPN network the dial must use; none, no dial. */
    private val vpn: VpnNetworkProvider,
    private val sleeper: Sleeper = Sleeper { Thread.sleep(it) },
    private val clock: () -> Long = ::defaultClockMs,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
    /** The local socket, made fresh for every dial, after authentication. A seam for tests. */
    private val localSocketFactory: () -> Socket = { Socket() },
    /** One deadline for both reads of the handshake. */
    private val authTimeoutMs: Long = AUTH_TIMEOUT_MS,
    /** The phone's nonce. A seam for tests (a reflected nonce cannot be made by chance). */
    private val nonceSource: () -> ByteArray = AuthV1::randomNonce
) {
    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val AUTH_TIMEOUT_MS = 10_000L
        const val REDIAL_AFTER_AUTH_MS = 1_000L

        /** How often a LOCKED loop asks the Keystore again: a local call, no network. */
        const val LOCKED_PROBE_MS = 5_000L

        /** Addresses tried for the host, in the order the VPN's resolver returned them. */
        const val MAX_ADDRESSES = 4

        /** Backoff after a connection that did not authenticate; the last step repeats. */
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)

        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        private const val COPY_BUFFER = 16 * 1024
        // Byte counters are atomics read at publish time; while a connection is
        // up a ticker republishes this often
        private const val PUBLISH_EVERY_MS = 500L

        /** The clock [LoopStatus.nextDialAtMs] is on: monotonic milliseconds. */
        fun defaultClockMs(): Long = System.nanoTime() / 1_000_000

        fun backoffMs(step: Int): Long = BACKOFF_MS[minOf(step, BACKOFF_MS.size - 1)]
    }

    private val lock = ReentrantLock()
    private val allEnded = lock.newCondition()
    private val loops = mutableListOf<Loop>() // guarded by lock
    private var nextId = 1                    // guarded by lock

    @Volatile
    private var stopped = false

    private var ticker: Thread? = null // guarded by lock

    /** True once [stop] has been called. */
    val isStopped: Boolean get() = stopped

    private val _loops = MutableStateFlow<List<LoopStatus>>(emptyList())

    /**
     * Every loop that still has a thread, including ones that are ending. Empty
     * once the last thread has exited: the service holds its wake lock until then.
     */
    val loopStatuses: StateFlow<List<LoopStatus>> = _loops.asStateFlow()

    /** Loops that count towards the device count (not ending). */
    val activeLoopCount: Int get() = lock.withLock { loops.count { !it.removed } }

    /**
     * Make the number of loops follow [count]. More: new loops start dialing
     * at once. Fewer: loops are retired, those not holding a connection first
     * (a retired loop that is waiting or dialing ends at once; one holding a
     * connection ends when that connection closes).
     */
    fun setLoopCount(count: Int) {
        require(count >= 0) { "count must not be negative" }
        lock.withLock {
            if (stopped) return
            var active = loops.count { !it.removed }
            while (active < count) {
                val loop = Loop(nextId++)
                loops.add(loop)
                loop.start()
                active++
            }
            if (loops.isNotEmpty()) startTickerLocked()
            while (active > count) {
                // WAITING, LOCKED, NO_VPN and CONNECTING loops hold no connection: retire them
                // first; among equals the newest
                val victim = loops.filter { !it.removed }
                    .sortedWith(compareBy<Loop> { it.phase.retireOrder() }.thenByDescending { it.id })
                    .first()
                victim.retire()
                active--
            }
            publishLocked()
        }
    }

    /** Stop every loop now: close all sockets, end all threads. Does not block; see [awaitStopped]. */
    fun stop() {
        lock.withLock {
            stopped = true
            loops.forEach { it.forceStop() }
            publishLocked()
        }
    }

    /** Wait until every loop thread has exited; false on timeout. */
    fun awaitStopped(timeoutMs: Long): Boolean {
        var leftNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.withLock {
            while (loops.isNotEmpty()) {
                if (leftNs <= 0) return false
                leftNs = allEnded.awaitNanos(leftNs)
            }
        }
        return true
    }

    private fun startTickerLocked() {
        if (ticker != null) return
        ticker = Thread({
            while (true) {
                try {
                    Thread.sleep(PUBLISH_EVERY_MS)
                } catch (e: InterruptedException) {
                    // nothing interrupts it; the loop below decides when to end
                }
                lock.withLock {
                    if (stopped || loops.isEmpty()) {
                        ticker = null
                        return@Thread
                    }
                    if (loops.any { it.phase == LoopStatus.Phase.CONNECTED }) publishLocked()
                }
            }
        }, "dialout-ticker").apply { isDaemon = true; start() }
    }

    private fun LoopStatus.Phase.retireOrder(): Int = when (this) {
        LoopStatus.Phase.WAITING, LoopStatus.Phase.LOCKED, LoopStatus.Phase.NO_VPN -> 0
        LoopStatus.Phase.CONNECTING -> 1
        LoopStatus.Phase.CONNECTED -> 2
    }

    private fun publishLocked() {
        _loops.value = loops.map { it.status() }
    }

    private fun publish() = lock.withLock { publishLocked() }

    private fun loopEnded(loop: Loop) {
        lock.withLock {
            loops.remove(loop)
            publishLocked()
            allEnded.signalAll()
        }
    }

    private enum class Outcome { SESSION, LOCKED, NO_VPN, FAILED }

    private inner class Loop(val id: Int) {
        // removed is written first and phase read second by retire(); the loop
        // writes WAITING/CONNECTING first and reads removed second: one of the
        // two always sees the other, so a retired loop is never left asleep
        @Volatile var removed = false
        @Volatile var phase = LoopStatus.Phase.CONNECTING
        private val bytesIn = AtomicLong()
        private val bytesOut = AtomicLong()
        @Volatile private var nextDialAt = 0L
        @Volatile private var lastError: String? = null
        // Set when this connection round recorded an error; lastError itself is
        // sticky (it keeps the previous round's until replaced)
        @Volatile private var roundError = false
        @Volatile private var remote: Socket? = null
        @Volatile private var local: Socket? = null
        // Serialises registering the local port with unregistering and closing
        // the local socket. Never held across a blocking socket call (connect,
        // read); close() and the registrar calls return at once.
        private val portLock = Any()
        private var registeredPort = 0 // guarded by portLock; 0 = none
        private val thread = Thread({ run() }, "dialout-loop-$id").apply { isDaemon = true }

        fun start() = thread.start()

        fun status() = LoopStatus(
            id = id,
            phase = phase,
            bytesIn = bytesIn.get(),
            bytesOut = bytesOut.get(),
            nextDialAtMs = nextDialAt,
            lastError = lastError,
            ending = removed
        )

        /** Leave the device count: end now unless a connection is live. */
        fun retire() {
            removed = true
            when (phase) {
                LoopStatus.Phase.WAITING, LoopStatus.Phase.LOCKED, LoopStatus.Phase.NO_VPN -> thread.interrupt()
                LoopStatus.Phase.CONNECTING -> closeSockets()
                LoopStatus.Phase.CONNECTED -> Unit
            }
        }

        fun forceStop() {
            removed = true
            closeSockets()
            thread.interrupt()
        }

        private fun closeSockets() {
            runCatching { remote?.close() }
            synchronized(portLock) {
                // Unregister first, then close: once the socket is closed the
                // port is free and another app may be handed it, and (from
                // another thread of ours) another loop may bind it
                unregisterLocked()
                runCatching { local?.close() }
            }
        }

        private fun unregisterLocked() {
            val port = registeredPort
            if (port == 0) return
            registeredPort = 0
            try {
                registrar.unregister(port)
            } catch (e: Exception) {
                log("loop $id: unregister $port: ${describe(e)}")
            }
        }

        /**
         * Register the bound local port, unless the socket was closed or the
         * loop ended meanwhile (retire and stop close it from other threads).
         * False: do not connect.
         */
        private fun registerLocal(l: Socket): Boolean = synchronized(portLock) {
            if (l.isClosed || removed || stopped) return false
            val port = l.localPort
            // Recorded before the call: if it throws, closeSockets still withdraws it
            registeredPort = port
            registrar.register(port)
            true
        }

        private fun run() {
            var step = 0
            try {
                while (!removed && !stopped) {
                    val outcome = dialOnce()
                    if (removed || stopped) break
                    val delay = when (outcome) {
                        Outcome.SESSION -> {
                            step = 0
                            REDIAL_AFTER_AUTH_MS
                        }
                        Outcome.LOCKED -> LOCKED_PROBE_MS
                        // No VPN backs off like a failed connect
                        Outcome.FAILED, Outcome.NO_VPN -> backoffMs(step++)
                    }
                    nextDialAt = clock() + delay
                    phase = when (outcome) {
                        Outcome.LOCKED -> LoopStatus.Phase.LOCKED
                        Outcome.NO_VPN -> LoopStatus.Phase.NO_VPN
                        else -> LoopStatus.Phase.WAITING
                    }
                    if (removed) break
                    publish()
                    try {
                        sleeper.sleep(delay)
                    } catch (e: InterruptedException) {
                        if (removed || stopped) break
                    }
                }
            } finally {
                closeSockets()
                loopEnded(this)
                log("loop $id ended")
            }
        }

        /**
         * One connection, start to end. SESSION: the box proved the key and the
         * local side was connected, so forwarding ran. LOCKED: the key is not
         * usable now, nothing was dialed. NO_VPN: no VPN network, nothing was
         * dialed. FAILED: everything else.
         */
        private fun dialOnce(): Outcome {
            phase = LoopStatus.Phase.CONNECTING
            roundError = false
            bytesIn.set(0)
            bytesOut.set(0)
            publish()
            // The key before the network: with the window over, the box is not
            // even connected to
            val mac = try {
                authenticator.mac()
            } catch (e: KeyUnavailableException) {
                if (e.reason == KeyUnavailableException.Reason.LOCKED) return Outcome.LOCKED
                fail(e.message ?: "key unavailable")
                return Outcome.FAILED
            } catch (e: Exception) {
                fail("key unavailable (${e.javaClass.simpleName})")
                return Outcome.FAILED
            }
            // The VPN before any socket: with none, nothing is resolved or connected
            val net = try {
                vpn.find()
            } catch (e: Exception) {
                fail("VPN lookup: ${describe(e)}")
                return Outcome.NO_VPN
            }
            if (net == null) {
                fail("no VPN")
                return Outcome.NO_VPN
            }
            if (removed) return Outcome.FAILED
            try {
                val r = connectRemote(net) ?: return Outcome.FAILED
                if (removed) return Outcome.FAILED
                // Authentication: the local socket does not exist until the box
                // has proved the key
                try {
                    AuthV1.phoneAuth(r, mac, authTimeoutMs, nonceSource)
                } catch (e: AuthFailedException) {
                    fail("authentication failed: ${e.message}")
                    return Outcome.FAILED
                } catch (e: IOException) {
                    fail("authentication: ${describe(e)}")
                    return Outcome.FAILED
                }
                if (removed || stopped) return Outcome.FAILED
                val l = localSocketFactory()
                local = l
                // The server accepts one connection per registered source port, so
                // the source port is chosen here: bind 127.0.0.1:0, register, then
                // connect. reuseAddress off, explicitly: the port must belong to
                // this socket alone until it is closed
                l.reuseAddress = false
                l.tcpNoDelay = true
                try {
                    l.bind(InetSocketAddress(LOOPBACK, 0))
                } catch (e: IOException) {
                    fail("local bind 127.0.0.1: ${describe(e)}")
                    return Outcome.FAILED
                }
                if (!registerLocal(l)) return Outcome.FAILED
                try {
                    l.connect(InetSocketAddress(LOOPBACK, localPort), connectTimeoutMs)
                } catch (e: IOException) {
                    fail("local connect 127.0.0.1:$localPort: ${describe(e)}")
                    return Outcome.FAILED
                }
                phase = LoopStatus.Phase.CONNECTED
                publish()
                log("loop $id: authenticated, connected $host:$port <-> 127.0.0.1:$localPort")
                pump(r, l)
                return Outcome.SESSION
            } catch (e: Exception) {
                fail("unexpected: ${describe(e)}")
                return Outcome.FAILED
            } finally {
                closeSockets()
            }
        }

        /**
         * Resolve [host] through the VPN and connect to it with a socket bound to
         * the VPN. One fresh socket per address (a socket that failed to connect
         * is not reused); a socket that cannot be bound is never connected.
         * Null: no connection (the failure is recorded).
         */
        private fun connectRemote(net: VpnNetwork): Socket? {
            val addresses = try {
                net.resolve(host)
            } catch (e: IOException) {
                fail("resolve $host via VPN: ${describe(e)}")
                return null
            }
            if (addresses.isEmpty()) {
                fail("resolve $host via VPN: no address")
                return null
            }
            var last: IOException? = null
            for (address in addresses.take(MAX_ADDRESSES)) {
                if (removed || stopped) return null
                val s = Socket()
                remote = s
                if (removed) return null
                try {
                    // Options first: both are valid on an unconnected socket
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    net.bind(s)
                    s.connect(InetSocketAddress(address, port), connectTimeoutMs)
                    return s
                } catch (e: IOException) {
                    runCatching { s.close() }
                    last = e
                }
            }
            fail("connect $host:$port: ${describe(last!!)}")
            return null
        }

        private fun fail(message: String) {
            // Our own close (retire, stop) surfaces as an IOException: not an error
            if (removed || stopped) return
            roundError = true
            lastError = message
            log("loop $id: $message")
        }

        /** Copy both ways until either side ends; then both sockets are closed. */
        private fun pump(r: Socket, l: Socket) {
            val ended = AtomicBoolean(false)
            // The first side to end names the cause; closing both unblocks the other
            fun end(cause: String?) {
                if (ended.compareAndSet(false, true)) {
                    if (cause != null && !removed && !stopped) {
                        roundError = true
                        lastError = cause
                        log("loop $id: $cause")
                    }
                    closeSockets()
                }
            }

            fun copy(src: Socket, dst: Socket, counter: AtomicLong, name: String) {
                try {
                    val input = src.getInputStream()
                    val output = dst.getOutputStream()
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break // a clean close is not an error
                        counter.addAndGet(n.toLong())
                        output.write(buf, 0, n)
                    }
                    end(null)
                } catch (e: IOException) {
                    end("$name: ${describe(e)}")
                } finally {
                    end(null)
                }
            }

            val up = Thread({ copy(l, r, bytesOut, "local to remote") }, "dialout-$id-up")
                .apply { isDaemon = true }
            up.start()
            copy(r, l, bytesIn, "remote to local")
            up.join(5_000)
        }
    }

    private fun describe(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}

/**
 * Whether dial-out may run: the server is up, the switch is on, the target is
 * valid, and a usable key is present. No key, no dialing.
 */
fun dialTargetUsable(serverRunning: Boolean, enabled: Boolean, host: String, port: Int, keyPresent: Boolean): Boolean =
    serverRunning && enabled && host.isNotBlank() && port in 1..65535 && keyPresent
