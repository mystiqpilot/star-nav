package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.WifiConfig
import tools.apachi.starlinknav.core.dish.proto.WifiGetStatusResponse

/**
 * Router replies carry Wi-Fi passwords, RADIUS secrets and TLS keys in clear (`wifi_get_config`, and
 * the `config` inside `wifi_get_status`). The proto subset marks every field whose name contains
 * password, secret, key or credential with `[(redacted) = true]`: Wire prints such a field as a
 * block in toString() and `ADAPTER.redact()` clears it. This helper is the one place that applies it:
 *
 *  - Everything that is stored, exported, printed or shown as text goes through [response] (or
 *    [config] / [wifiStatus]): the session log ([DishCallResult.toRecord]), probe reports, `slnav
 *    dump`, Debug data snapshots. Router replies are never logged raw.
 *  - `redact()` also drops unknown fields, which may hold secrets of newer firmware; redacted copies
 *    are for display and storage only, never for writing back to the router (a config write starts
 *    from the unredacted read held in memory, see [RouterConfigRead]).
 *  - Passwords are masked in the UI with a reveal button and never stored by the app.
 */
object Redact {
    /**
     * A copy of [r] with every password, secret and key (and every unknown field) cleared. A position in a
     * `get_location` arm goes too: this is applied to router data only, and the router has no business
     * answering with coordinates (the probe asks it once, see [DishCallKind.ROUTER_LOCATION]).
     */
    fun response(r: DeviceResponse): DeviceResponse {
        val red = DeviceResponse.ADAPTER.redact(r)
        return red.get_location?.let { red.copy(get_location = it.copy(lla = null)) } ?: red
    }

    fun config(c: WifiConfig): WifiConfig = WifiConfig.ADAPTER.redact(c)

    fun wifiStatus(s: WifiGetStatusResponse): WifiGetStatusResponse = WifiGetStatusResponse.ADAPTER.redact(s)

    /** The plaintext Wi-Fi passwords and keys in [c], for masking them in a UI and for tests that prove they never leak. */
    fun secretsIn(c: WifiConfig?): List<String> {
        if (c == null) return emptyList()
        val out = ArrayList<String>()
        for (n in c.networks) {
            for (b in n.basic_service_sets) {
                b.auth_wpa2?.password?.let(out::add)
                b.auth_wpa3?.password?.let(out::add)
                b.auth_wpa2_wpa3?.password?.let(out::add)
                b.auth_radius?.password?.let(out::add)
            }
            n.onboard_radius_tls_config?.key?.let(out::add)
        }
        c.http_server?.tls?.key?.let(out::add)
        c.dynamic_keys.forEach { out += it.key }
        if (c.client_key.size > 0) out += c.client_key.utf8()
        return out.filter { it.isNotEmpty() }
    }

    fun secretsIn(r: DeviceResponse?): List<String> =
        if (r == null) emptyList() else secretsIn(RouterCommand.configOf(r))
}

/** True when the reply is one of the router's (any of the wifi_* arms). */
fun DeviceResponse.hasRouterPayload(): Boolean =
    wifi_get_status != null || wifi_get_history != null || wifi_get_clients != null || wifi_get_config != null ||
        wifi_set_config != null || wifi_get_ping_metrics != null || wifi_guest_info != null ||
        wifi_get_persistent_stats != null || wifi_get_diagnostics != null
