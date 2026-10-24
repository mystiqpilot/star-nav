package tools.apachi.starlinknav.core.dish

import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * Talks to the router: the server on the Wi-Fi gateway (a Mini's integrated router, 192.168.1.1 unless
 * the LAN subnet was changed). Same service and request/response messages as the dish, other device: it
 * answers `get_status` with `wifi_get_status` and serves the wifi_* arms; only [CallTarget.ROUTER] kinds
 * go through here. Transports as for the dish: gRPC-Web on :9001 first, plain gRPC (h2c) on :9000 when
 * that does not answer, sticking to whichever worked. The host comes from the caller (the app passes the
 * Wi-Fi network's default gateway; a manual override takes any "host[:port]").
 *
 * Router replies carry Wi-Fi passwords: the results are logged redacted only ([DishCallResult.toRecord]).
 */
class RouterClient(transport: GrpcTransport, monoNanos: () -> Long = System::nanoTime) {
    private val client = DishClient(transport, monoNanos, CallTarget.ROUTER)
    val transportName: String get() = client.transportName
    val endpoint: String get() = client.endpoint

    /** A read ([DishCallKind.ROUTER_QUERIES]) or a parameterless command (reboot, factory reset). */
    fun call(kind: DishCallKind): DishCallResult = client.call(kind)

    /** A command with parameters ([RouterCommand]). */
    fun send(command: RouterCommand): DishCallResult = client.send(command)

    companion object {
        const val WEB_PORT = 9001
        const val H2C_PORT = 9000

        /** The router addresses the LAN subnet options of the official app lead to (a manual-override menu). */
        val PRESET_ADDRESSES = listOf(
            "192.168.1.1", "192.168.2.1", "192.168.3.1", "192.168.4.1", "10.0.0.1", "10.1.0.1", "10.2.0.1", "10.3.0.1",
        )

        /** "host" or "host:port" (the port is the gRPC-Web port; plain gRPC stays on 9000). */
        fun parseAddress(address: String): Pair<String, Int> = DishClient.parseAddress(address, WEB_PORT)

        fun isValidAddress(address: String): Boolean {
            val (host, port) = parseAddress(address)
            return host.isNotBlank() && runCatching { HttpUrl.Builder().scheme("http").host(host).port(port).build() }.isSuccess
        }

        fun create(
            http: OkHttpClient,
            host: String,
            mode: TransportMode = TransportMode.AUTO,
            webPort: Int = WEB_PORT,
            h2cPort: Int = H2C_PORT,
            monoNanos: () -> Long = System::nanoTime,
        ): RouterClient {
            fun url(port: Int) = HttpUrl.Builder().scheme("http").host(host).port(port).build()
            val web = GrpcWebTransport(http, url(webPort))
            val h2c = GrpcH2cTransport(http, url(h2cPort))
            val transport = when (mode) {
                TransportMode.AUTO -> FallbackTransport(web, h2c)
                TransportMode.WEB -> web
                TransportMode.H2C -> h2c
            }
            return RouterClient(transport, monoNanos)
        }
    }
}
