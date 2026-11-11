package dev.pocketvm.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import dev.pocketvm.diagnostics.AgentFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本机网络状态的唯一来源（P2 数据层）：ConnectivityManager + NetworkCallback。
 *
 * 替代之前「只在探环境那一刻读一次」的做法：网络一变（Wi-Fi 断开、切移动网、
 * 插 USB 共享）就推新状态，VNC 连接信息页直接订阅，不用用户手动刷新。
 * 不做 Ping/Socket 连通性检测——只要「有没有默认网络、是什么传输方式、
 * 本机局域网地址是多少」。
 *
 * 生命周期跟 App 走（P3：Hilt 模块 [dev.pocketvm.di.AppModule] 里创建并
 * start），不跟 ViewModel/Activity：网络状态与页面无关，旋转屏幕、切页面
 * 都不应重注册。回调注册失败只记 AgentFeed，不影响启动。
 */
class NetworkMonitor(context: Context) {

    enum class Transport(val label: String) {
        WIFI("Wi-Fi"),
        CELLULAR("移动网络"),
        ETHERNET("有线网络"),
        VPN("VPN"),
        OTHER("其他"),
        NONE("无网络"),
    }

    data class NetworkState(
        val connected: Boolean,
        val transport: Transport,
        val localIpv4: String?,
    )

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onLost(network: Network) {
            refresh()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            refresh()
        }

        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
            refresh()
        }
    }

    fun start() {
        refresh()
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { AgentFeed.event("network.callback_failed", "error" to it.toString()) }
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun refresh() {
        _state.value = readState()
    }

    private fun readState(): NetworkState {
        val network = runCatching { cm.activeNetwork }.getOrNull()
        val caps = network?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val connected = network != null && caps != null
        val transport = when {
            caps == null -> Transport.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
            else -> Transport.OTHER
        }
        return NetworkState(connected, transport, NetworkInfo.localIpv4(appContext))
    }
}
