package tools.apachi.starlinknav.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Dns
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.SettingsSnapshot
import tools.apachi.starlinknav.core.dish.DishClient
import tools.apachi.starlinknav.core.dish.TransportMode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.SocketFactory

/**
 * Tracks the Wi-Fi network. Android routes app traffic over cellular when Wi-Fi has no
 * validated internet (Starlink offline, or a captive check failing), but the dish is
 * only reachable over the Mini's Wi-Fi, so dish sockets are bound to this network.
 * A VPN network also carries the Wi-Fi transport while it runs over Wi-Fi (`WIFI|VPN`); it is never taken for the Wi-Fi.
 */
class WifiTracker(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = state
    private var registered = false

    /** Every Wi-Fi network the callback reported, oldest first: the newest one wins, the next takes over when it goes. */
    private val available = LinkedHashSet<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(available) {
                available.remove(network)
                available.add(network)
                state.value = network
            }
        }

        override fun onLost(network: Network) {
            synchronized(available) {
                available.remove(network)
                if (state.value == network) state.value = available.lastOrNull()
            }
        }
    }

    fun start() {
        if (registered) return
        state.value = currentWifi(cm)
        cm.registerNetworkCallback(wifiRequest(), callback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        synchronized(available) { available.clear() }
        state.value = null
    }

    companion object {
        private fun wifiRequest() = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        /** The Wi-Fi network itself, not a VPN running over it. */
        @Suppress("DEPRECATION")
        fun currentWifi(cm: ConnectivityManager): Network? = cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }
}

/**
 * Binds sockets to the Wi-Fi network where Android allows it. A VPN that apps may not bypass (Tailscale, most company
 * VPNs) lets an app use only the VPN: binding a socket to Wi-Fi then fails with EPERM. Such sockets stay unbound, on the
 * default network, which still reaches the dish while the VPN leaves local addresses alone (no exit node, or one with LAN
 * access allowed). [refusedByVpn] tells the screens; it follows the last binding attempt.
 */
object WifiBinding {
    private val refused = MutableStateFlow(false)
    val refusedByVpn: StateFlow<Boolean> = refused

    /** Binds [socket] to [net]; false, with the socket left unbound, when a VPN forbids it. Other failures throw. */
    fun bind(net: Network, socket: Socket): Boolean {
        try {
            net.bindSocket(socket)
        } catch (e: SocketException) {
            if (!e.isEperm()) throw e
            refused.value = true
            return false
        }
        refused.value = false
        return true
    }

    /** Whether sockets may be bound to [net] now (tries it on a throwaway socket). */
    fun mayBind(net: Network): Boolean = Socket().use { bind(net, it) }

    /** Sockets bound to [net], or on the default network where a VPN refuses the binding. */
    fun socketFactory(net: Network): SocketFactory = BoundSocketFactory(net)

    /** Names through [net]'s DNS; through the default network's when that fails and a VPN refuses the binding. */
    fun dns(net: Network): Dns = Dns { host ->
        try {
            net.getAllByName(host).toList()
        } catch (e: UnknownHostException) {
            if (mayBind(net)) throw e
            Dns.SYSTEM.lookup(host)
        }
    }

    // ErrnoException.rethrowAsSocketException keeps the ErrnoException as the cause; the message says "EPERM" as well.
    private fun SocketException.isEperm(): Boolean =
        (cause as? ErrnoException)?.errno == OsConstants.EPERM || message?.contains("EPERM") == true

    private class BoundSocketFactory(private val net: Network) : SocketFactory() {
        override fun createSocket(): Socket = Socket().also { s ->
            try {
                bind(net, s)
            } catch (e: Exception) {
                s.close()
                throw e
            }
        }

        override fun createSocket(host: String, port: Int): Socket =
            connected(InetSocketAddress(dns(net).lookup(host).first(), port), null)

        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            connected(InetSocketAddress(dns(net).lookup(host).first(), port), InetSocketAddress(localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket = connected(InetSocketAddress(host, port), null)

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            connected(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))

        private fun connected(remote: InetSocketAddress, local: InetSocketAddress?): Socket = createSocket().also { s ->
            try {
                if (local != null) s.bind(local)
                s.connect(remote)
            } catch (e: Exception) {
                s.close()
                throw e
            }
        }
    }
}

/** How dish traffic is routed: the UI shows [label], the probe report keeps the English [english]. */
enum class DishRoute(val english: String, @StringRes val label: Int) {
    DEFAULT_NETWORK("default network", R.string.dish_network_default),
    WIFI_BOUND("Wi-Fi (bound)", R.string.dish_network_wifi),
    NO_WIFI("no Wi-Fi: default network", R.string.dish_network_no_wifi),
    VPN_REFUSED("a VPN refuses the Wi-Fi binding: default network", R.string.dish_network_vpn_refused),
}

/** Builds (and caches) a dish client for the current settings and network. */
class DishClients {
    private data class Key(val host: String, val mode: TransportMode, val network: Network?)

    private var cached: Pair<Key, DishClient>? = null

    @Synchronized
    fun get(s: SettingsSnapshot, wifi: Network?): DishClient {
        val net = if (s.bindToWifi) wifi else null
        val key = Key(s.dishHost, s.transport, net)
        cached?.let { (k, c) -> if (k == key) return c }
        return build(s, net).also { cached = key to it }
    }

    companion object {
        fun build(s: SettingsSnapshot, net: Network?): DishClient {
            var http = DishClient.defaultHttpClient()
            if (net != null) {
                http = http.newBuilder()
                    .socketFactory(WifiBinding.socketFactory(net))
                    .dns(WifiBinding.dns(net))
                    .build()
            }
            val (host, webPort) = DishClient.parseAddress(s.dishHost)
            return DishClient.create(http, host, s.transport, webPort = webPort, monoNanos = SystemClock::elapsedRealtimeNanos)
        }

        fun route(s: SettingsSnapshot, net: Network?): DishRoute = when {
            !s.bindToWifi -> DishRoute.DEFAULT_NETWORK
            net != null && WifiBinding.refusedByVpn.value -> DishRoute.VPN_REFUSED
            net != null -> DishRoute.WIFI_BOUND
            else -> DishRoute.NO_WIFI
        }

        /** English, for the probe report. */
        fun describe(s: SettingsSnapshot, net: Network?): String = route(s, net).english
    }
}
