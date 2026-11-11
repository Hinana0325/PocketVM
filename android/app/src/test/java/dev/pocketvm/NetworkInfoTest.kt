package dev.pocketvm

import dev.pocketvm.runtime.NetworkInfo
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P2 数据层：本机局域网地址挑选的纯 JVM 测试。
 *
 * 只测纯函数 [NetworkInfo.pickSiteLocalIpv4]；ConnectivityManager 的读数
 * 要 Android 环境，真机验证覆盖。
 */
class NetworkInfoTest {

    private fun addr(host: String): InetAddress = InetAddress.getByName(host)

    @Test
    fun `picks the site-local ipv4 among mixed addresses`() {
        val addresses = listOf(
            addr("127.0.0.1"),
            addr("192.168.1.42"),
            addr("8.8.8.8"),
        )
        assertEquals("192.168.1.42", NetworkInfo.pickSiteLocalIpv4(addresses))
    }

    @Test
    fun `picks the first site-local address when several exist`() {
        val addresses = listOf(addr("10.0.0.5"), addr("172.16.0.9"))
        assertEquals("10.0.0.5", NetworkInfo.pickSiteLocalIpv4(addresses))
    }

    @Test
    fun `returns null when there is no site-local ipv4`() {
        assertNull(NetworkInfo.pickSiteLocalIpv4(listOf(addr("127.0.0.1"))))
        assertNull(NetworkInfo.pickSiteLocalIpv4(listOf(addr("8.8.8.8"))))
        assertNull(NetworkInfo.pickSiteLocalIpv4(emptyList()))
    }

    @Test
    fun `ignores ipv6 site-local addresses`() {
        val addresses = listOf(addr("fe80::1"), addr("::1"))
        assertNull(NetworkInfo.pickSiteLocalIpv4(addresses))
    }
}
