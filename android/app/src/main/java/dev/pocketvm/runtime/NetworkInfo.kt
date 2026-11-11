package dev.pocketvm.runtime

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Local IPv4 address, used to tell the user what to type into a VNC client.
 *
 * 首选 ConnectivityManager（官方）：读当前默认网络的 LinkAddresses。
 * 拿不到（无默认网络、或系统没给地址）时回退到枚举网卡（旧实现）——
 * 不直接拿 Ping/Socket 做连通性检测，那是 P2 之前明确不用的手写检测。
 *
 * [pickSiteLocalIpv4] 是纯函数（可单元测试）：只要站点本地地址
 *（192.168.x.x / 10.x.x.x / 172.16–31.x.x），回环与公网地址不要。
 */
object NetworkInfo {

    fun pickSiteLocalIpv4(addresses: List<InetAddress>): String? =
        addresses.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress

    fun localIpv4(context: Context): String? {
        val viaConnectivityManager = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return@runCatching null
            val props = cm.getLinkProperties(network) ?: return@runCatching null
            pickSiteLocalIpv4(props.linkAddresses.map { it.address })
        }.getOrNull()
        if (viaConnectivityManager != null) return viaConnectivityManager
        return localIpv4FromInterfaces()
    }

    /** 旧实现保留为兜底：直接枚举网卡，不依赖默认网络。 */
    fun localIpv4FromInterfaces(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.let { pickSiteLocalIpv4(it) }
    } catch (t: Throwable) {
        null
    }
}
