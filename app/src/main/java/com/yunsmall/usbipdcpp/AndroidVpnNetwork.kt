package com.yunsmall.usbipdcpp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.Socket

/**
 * The real provider: a [Network] whose [NetworkCapabilities] has TRANSPORT_VPN
 * (Tailscale's VPN service). Needs ACCESS_NETWORK_STATE, a normal permission.
 * [ConnectivityManager.getAllNetworks] is deprecated since API 31 but is the
 * only way to list networks without a long-lived callback; the active network
 * is asked first, and with the tailnet up it is the VPN.
 */
class AndroidVpnNetworkProvider(context: Context) : VpnNetworkProvider {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private fun isVpn(network: Network): Boolean =
        manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    @Suppress("DEPRECATION")
    override fun find(): VpnNetwork? {
        val network = pickVpnNetwork(manager.activeNetwork, manager.allNetworks.toList(), ::isVpn) ?: return null
        return object : VpnNetwork {
            override fun resolve(host: String): List<InetAddress> = network.getAllByName(host).toList()
            override fun bind(socket: Socket) = network.bindSocket(socket)
        }
    }
}
