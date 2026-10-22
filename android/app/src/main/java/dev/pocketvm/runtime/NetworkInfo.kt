package dev.pocketvm.runtime

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Local IPv4 address, used to tell the user what to type into a VNC client.
 *
 * Read from NetworkInterface rather than WifiManager on purpose: this also picks
 * up USB tethering and Ethernet, and needs no permission.
 */
object NetworkInfo {

    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (t: Throwable) {
        null
    }
}
