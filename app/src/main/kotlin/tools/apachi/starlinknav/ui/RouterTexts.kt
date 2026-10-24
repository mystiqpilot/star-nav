package tools.apachi.starlinknav.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.AlertSeverity
import tools.apachi.starlinknav.core.dish.ClientList
import tools.apachi.starlinknav.core.dish.WifiAuth
import tools.apachi.starlinknav.core.dish.WifiBand
import tools.apachi.starlinknav.core.dish.WifiSoftwareUpdate

// Plain words for the codes the router sends. Explicit `when`s, never lookups by resource name (resource shrinking), and
// a code without words shows its code name: nothing here throws on a code from newer firmware.

/** A router alert key of [tools.apachi.starlinknav.core.dish.AlertList.router] in plain words; null for a key without words. */
@StringRes
fun routerAlertRes(key: String): Int? = when (key) {
    "thermal_throttle" -> R.string.ralert_thermal_throttle
    "install_pending" -> R.string.ralert_install_pending
    "freshly_fused" -> R.string.ralert_freshly_fused
    "lan_eth_slow_link_10" -> R.string.ralert_lan_slow_10
    "lan_eth_slow_link_100" -> R.string.ralert_lan_slow_100
    "wan_eth_poor_connection" -> R.string.ralert_wan_poor
    "mesh_topology_changing_often" -> R.string.ralert_mesh_topology
    "mesh_unreliable_backhaul" -> R.string.ralert_mesh_backhaul
    "radius_missing_process" -> R.string.ralert_radius
    "eth_switch_error" -> R.string.ralert_switch_error
    "poe_on_dish_unreachable" -> R.string.ralert_poe_unreachable
    "poe_fuse_blown" -> R.string.ralert_poe_fuse
    "poe_router_overcurrent" -> R.string.ralert_poe_overcurrent
    "poe_off_current_nominal" -> R.string.ralert_poe_off_current
    "poe_vin_overvoltage" -> R.string.ralert_poe_overvoltage
    "poe_vin_undervoltage" -> R.string.ralert_poe_undervoltage
    "high_cable_ping_drop_rate" -> R.string.ralert_cable_drops
    "sandbox_disabled" -> R.string.ralert_sandbox
    "only_overflight_blocked" -> R.string.ralert_overflight
    "offline_networks_disabled" -> R.string.ralert_offline_networks
    "wired_mesh_not_using_wan_iface" -> R.string.ralert_wired_mesh
    else -> null
}

@Composable
fun routerAlertText(key: String): String = routerAlertRes(key)?.let { stringResource(it) } ?: key.replace('_', ' ')

/** Power and cable faults and overheating are warnings; the rest are cautions or advisories. */
fun routerAlertSeverity(key: String): AlertSeverity = when (key) {
    "thermal_throttle", "poe_fuse_blown", "poe_router_overcurrent", "poe_vin_overvoltage", "poe_vin_undervoltage", "eth_switch_error", "poe_on_dish_unreachable" -> AlertSeverity.WARNING
    "install_pending", "freshly_fused", "sandbox_disabled", "offline_networks_disabled", "only_overflight_blocked" -> AlertSeverity.ADVISORY
    else -> AlertSeverity.CAUTION
}

/** WifiSoftwareUpdateState of the router, in plain words (a failure shows its code name through [routerUpdateText]). */
@StringRes
fun routerUpdateRes(state: Int): Int? = when (state) {
    WifiSoftwareUpdate.NOT_RUN -> R.string.rupdate_not_run
    1 -> R.string.rupdate_looking
    2 -> R.string.rupdate_downloading
    3 -> R.string.rupdate_installing
    WifiSoftwareUpdate.NO_UPDATE_REQUIRED -> R.string.rupdate_current
    WifiSoftwareUpdate.REBOOT_PENDING -> R.string.rupdate_restart_pending
    else -> null
}

@Composable
fun routerUpdateText(state: Int): String =
    routerUpdateRes(state)?.let { stringResource(it) }
        ?: stringResource(if (WifiSoftwareUpdate.isFailure(state)) R.string.rupdate_failed else R.string.rupdate_other, WifiSoftwareUpdate.name(state))

/** Router and Wi-Fi client events the app has words for; the others show their code name in a generic sentence. */
@StringRes
fun routerEventRes(code: Int): Int? = when (code) {
    13 -> R.string.revent_factory_reset
    14 -> R.string.revent_reboot
    15 -> R.string.revent_power_cycle
    16 -> R.string.revent_config_update
    17 -> R.string.revent_software_update
    18 -> R.string.revent_thermal
    20 -> R.string.revent_reboot_config
    21 -> R.string.revent_public_ip_change
    26 -> R.string.revent_pop_v4_drop
    27 -> R.string.revent_pop_v6_drop
    28 -> R.string.revent_lan_loss
    29 -> R.string.revent_wan_loss
    31 -> R.string.revent_dhcp_exhausted
    36 -> R.string.revent_max_clients
    40 -> R.string.revent_dish_ping_drop
    42 -> R.string.revent_client_reconnecting
    43 -> R.string.revent_client_band
    53 -> R.string.revent_wan_down
    else -> null
}

/** The sentence for an event of the router's own log (or of the dish, when the router logs those too); [client] names the device a client event is about. */
@Composable
fun routerEventText(code: Int, client: String?): String {
    val res = routerEventRes(code)
    return when {
        res != null && (code == 42 || code == 43) -> stringResource(res, client ?: stringResource(R.string.revent_unknown_client))
        res != null -> stringResource(res)
        else -> eventReasonText(code)
    }
}

/** How a client is connected: [tools.apachi.starlinknav.core.dish.WifiClient.iface] 1 = cable, 2 = 2.4 GHz, 3 = 5 GHz, 4 = upper 5 GHz. */
@StringRes
fun clientLinkRes(iface: Int): Int? = when (iface) {
    1 -> R.string.rclient_link_cable
    2 -> R.string.rband_2
    3 -> R.string.rband_5
    4 -> R.string.rband_5_high
    else -> null
}

@Composable
fun clientLinkText(iface: Int): String = clientLinkRes(iface)?.let { stringResource(it) } ?: "-"

/** [WifiBand] code of a configured band: 2, 5 or 6. */
@StringRes
fun wifiBandRes(band: Int): Int? = when (band) {
    WifiBand.RF_2GHZ -> R.string.rband_2
    WifiBand.RF_5GHZ -> R.string.rband_5
    WifiBand.RF_5GHZ_HIGH -> R.string.rband_5_high
    else -> null
}

@Composable
fun wifiBandText(band: Int): String = wifiBandRes(band)?.let { stringResource(it) } ?: WifiBand.name(band)

@StringRes
fun wifiAuthRes(auth: WifiAuth): Int = when (auth) {
    WifiAuth.OPEN -> R.string.rauth_open
    WifiAuth.WPA2 -> R.string.rauth_wpa2
    WifiAuth.WPA3 -> R.string.rauth_wpa3
    WifiAuth.WPA2_WPA3 -> R.string.rauth_wpa2_wpa3
    WifiAuth.RADIUS -> R.string.rauth_radius
    WifiAuth.OPEN_ENCRYPTED -> R.string.rauth_open_encrypted
    WifiAuth.ONBOARD_RADIUS -> R.string.rauth_onboard_radius
    WifiAuth.UNKNOWN -> R.string.rauth_unknown
}

@StringRes
fun clientStateRes(state: ClientList.State): Int = when (state) {
    ClientList.State.ONLINE -> R.string.rclient_online
    ClientList.State.PAUSED -> R.string.rclient_paused
    ClientList.State.OFFLINE -> R.string.rclient_offline
}
