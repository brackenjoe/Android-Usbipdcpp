package com.yunsmall.usbipdcpp

import java.io.IOException
import java.net.InetAddress
import java.net.Socket

/**
 * A VPN network the dialer may use: the host is resolved through it and the
 * socket is bound to it before it connects, so nothing leaves on another
 * network. An interface so the JVM tests need no Android.
 */
interface VpnNetwork {
    /** Resolve [host] through the VPN (Android: `Network.getAllByName`). */
    @Throws(IOException::class)
    fun resolve(host: String): List<InetAddress>

    /**
     * Bind the unconnected [socket] to the VPN (Android: `Network.bindSocket`).
     * Throws if it cannot: the caller must then not connect.
     */
    @Throws(IOException::class)
    fun bind(socket: Socket)
}

/** Finds the VPN network at dial time; null when there is none. Called once per dial. */
fun interface VpnNetworkProvider {
    fun find(): VpnNetwork?
}

/**
 * Which of the phone's networks is the VPN: the active one when it is, else any
 * other that is. Generic so the choice is tested without Android's Network.
 */
fun <N : Any> pickVpnNetwork(active: N?, all: List<N>, isVpn: (N) -> Boolean): N? {
    if (active != null && isVpn(active)) return active
    return all.firstOrNull { isVpn(it) }
}
