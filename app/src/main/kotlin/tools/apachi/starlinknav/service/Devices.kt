package tools.apachi.starlinknav.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import okhttp3.OkHttpClient
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.SettingsSnapshot
import tools.apachi.starlinknav.core.dish.DishClient
import tools.apachi.starlinknav.core.dish.RouterClient
import tools.apachi.starlinknav.core.dish.TransportMode
import java.net.Inet4Address

/** Where the router address came from. */
enum class RouterSource {
    /** Typed in Settings. */
    MANUAL,

    /** The default gateway of the Wi-Fi network (a Mini's integrated router, 192.168.1.1 unless its subnet was changed). */
    GATEWAY,

    /** Not typed and no Wi-Fi gateway to read. */
    NONE,
}

/** The router's address ("host" or "host:port") and where it came from; [address] is null for [RouterSource.NONE]. */
data class RouterTarget(val address: String?, val source: RouterSource)

object RouterAddress {
    /** The IPv4 default gateway of [wifi]; null without a network or a gateway (IPv6 link-local gateways need a scope id, which the client cannot use). */
    fun gateway(cm: ConnectivityManager, wifi: Network?): String? {
        val props = cm.getLinkProperties(wifi ?: return null) ?: return null
        return props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }

    /** A typed address wins; else the Wi-Fi gateway. */
    fun resolve(s: SettingsSnapshot, cm: ConnectivityManager, wifi: Network?): RouterTarget {
        val typed = s.routerHost.trim()
        if (typed.isNotEmpty()) return RouterTarget(typed, RouterSource.MANUAL)
        val gateway = gateway(cm, wifi)
        return if (gateway != null) RouterTarget(gateway, RouterSource.GATEWAY) else RouterTarget(null, RouterSource.NONE)
    }
}

/** A router client for [target]; only built when there is an address. */
class RouterConnection(val target: RouterTarget, val client: RouterClient)

/**
 * Builds (and caches) the router client for the current settings and network. The router is reached over the Wi-Fi
 * network: a gateway address only exists there, so the sockets are bound to it; a typed address follows the "Bind to
 * Wi-Fi network" setting like the dish does. Transport is always Auto (gRPC-Web :9001, then plain gRPC :9000).
 */
class RouterClients {
    private data class Key(val address: String, val network: Network?)

    private var cached: Pair<Key, RouterClient>? = null

    @Synchronized
    fun get(s: SettingsSnapshot, cm: ConnectivityManager, wifi: Network?): RouterConnection? {
        val target = RouterAddress.resolve(s, cm, wifi)
        val address = target.address ?: return null
        val net = if (target.source == RouterSource.GATEWAY || s.bindToWifi) wifi else null
        val key = Key(address, net)
        cached?.let { (k, c) -> if (k == key) return RouterConnection(target, c) }
        return RouterConnection(target, build(address, net)).also { cached = key to it.client }
    }

    companion object {
        /** An HTTP client for the router (and the probe's port survey), its sockets bound to [net] when given. */
        fun http(net: Network?): OkHttpClient {
            val http = DishClient.defaultHttpClient()
            if (net == null) return http
            return http.newBuilder()
                .socketFactory(WifiBinding.socketFactory(net))
                .dns(WifiBinding.dns(net))
                .build()
        }

        fun build(address: String, net: Network?): RouterClient {
            val (host, webPort) = RouterClient.parseAddress(address)
            return RouterClient.create(http(net), host, TransportMode.AUTO, webPort = webPort, monoNanos = SystemClock::elapsedRealtimeNanos)
        }
    }
}

/**
 * What the Dish screens, the command bus (when no service runs) and the Debug data export talk to: the dish and the
 * router built from the settings and the current Wi-Fi network. Cheap to create; keep one per screen.
 */
class Devices(context: Context) {
    private val cm: ConnectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val dishClients = DishClients()
    private val routerClients = RouterClients()

    private fun settings() = App.instance.settings.value

    fun wifi(): Network? = WifiTracker.currentWifi(cm)

    fun dish(s: SettingsSnapshot = settings(), wifi: Network? = wifi()): DishClient = dishClients.get(s, wifi)

    /** The Wi-Fi network's IPv4 default gateway (whatever is typed in Settings); null off Wi-Fi. */
    fun gateway(wifi: Network? = wifi()): String? = RouterAddress.gateway(cm, wifi)

    /**
     * The phone's own addresses on [wifi] (IPv4 and IPv6, no scope ids): how the Router sub-tab recognises this phone among
     * the router's clients. Android hides the Wi-Fi MAC address from apps, so the address is all there is.
     */
    fun ownAddresses(wifi: Network? = wifi()): Set<String> {
        val props = cm.getLinkProperties(wifi ?: return emptySet()) ?: return emptySet()
        return props.linkAddresses.mapNotNull { it.address.hostAddress?.substringBefore('%') }.toSet()
    }

    fun routerTarget(s: SettingsSnapshot = settings(), wifi: Network? = wifi()): RouterTarget = RouterAddress.resolve(s, cm, wifi)

    /** Null when there is no router address (no typed one, no Wi-Fi gateway). */
    fun router(s: SettingsSnapshot = settings(), wifi: Network? = wifi()): RouterConnection? = routerClients.get(s, cm, wifi)

    /** The network the router's sockets are bound to for [target] (see [RouterClients]). */
    fun routerNetwork(target: RouterTarget, s: SettingsSnapshot = settings(), wifi: Network? = wifi()): Network? =
        if (target.source == RouterSource.GATEWAY || s.bindToWifi) wifi else null

    companion object {
        @Volatile
        private var instance: Devices? = null

        /**
         * One process-wide instance for work that is not tied to a screen or the service (the command bus without the
         * service, the Debug data export, the Settings line): the clients it caches are reused, instead of an HTTP client
         * with its own connection pool and threads being built for every call.
         */
        fun shared(context: Context): Devices =
            instance ?: synchronized(this) { instance ?: Devices(context.applicationContext).also { instance = it } }
    }
}
