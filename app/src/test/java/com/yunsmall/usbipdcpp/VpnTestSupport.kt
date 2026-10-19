package com.yunsmall.usbipdcpp

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A VPN network for the JVM tests. [names] maps a host name to what the VPN's
 * resolver returns for it; anything else resolves as the system would (the
 * tests use IP literals). Every call is recorded.
 */
class FakeVpnNetwork(
    private val names: Map<String, List<InetAddress>> = emptyMap(),
    private val bindFails: Boolean = false,
    private val log: (String) -> Unit = {}
) : VpnNetwork {
    val resolved = CopyOnWriteArrayList<String>()

    /** For every socket handed to [bind]: was it already connected at that moment? */
    val connectedAtBind = CopyOnWriteArrayList<Boolean>()

    override fun resolve(host: String): List<InetAddress> {
        resolved.add(host)
        log("vpn-resolve:$host")
        return names[host] ?: InetAddress.getAllByName(host).toList()
    }

    override fun bind(socket: Socket) {
        connectedAtBind.add(socket.isConnected)
        log("vpn-bind")
        if (bindFails) throw IOException("test: cannot bind to the VPN")
    }
}

/** What [DialOut] asks once per dial; [network] can be changed while it runs. */
class FakeVpnProvider(@Volatile var network: VpnNetwork?) : VpnNetworkProvider {
    val finds = AtomicInteger()
    override fun find(): VpnNetwork? {
        finds.incrementAndGet()
        return network
    }

    companion object {
        /** A VPN that resolves like the system does and binds to nothing: for tests of other things. */
        fun loopback() = FakeVpnProvider(FakeVpnNetwork())
    }
}
