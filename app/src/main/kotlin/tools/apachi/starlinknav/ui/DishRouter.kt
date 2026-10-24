package tools.apachi.starlinknav.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.AlertList
import tools.apachi.starlinknav.core.dish.AlertSeverity
import tools.apachi.starlinknav.core.dish.ClientList
import tools.apachi.starlinknav.core.dish.Disablement
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.RouterUpdateView
import tools.apachi.starlinknav.core.dish.SetupRequirement
import tools.apachi.starlinknav.core.dish.proto.WifiClient
import tools.apachi.starlinknav.core.dish.proto.WifiConfig
import tools.apachi.starlinknav.core.dish.proto.WifiGetStatusResponse
import tools.apachi.starlinknav.service.Devices
import tools.apachi.starlinknav.service.DishFeed
import tools.apachi.starlinknav.service.FeedSample
import tools.apachi.starlinknav.service.RouterSource
import tools.apachi.starlinknav.service.RouterTarget

/** The parts of the Router sub-tab, and the reads each one wants while it is on screen. */
enum class RouterSection(@StringRes val label: Int, val kinds: Set<DishCallKind>) {
    STATUS(R.string.rsec_status, setOf(DishCallKind.ROUTER_STATUS)),

    // The status carries the clients and the config too: the dedicated reads are used when they answer, the status when not.
    DEVICES(R.string.rsec_devices, setOf(DishCallKind.ROUTER_CLIENTS, DishCallKind.ROUTER_STATUS)),
    WIFI(R.string.rsec_wifi, setOf(DishCallKind.ROUTER_CONFIG, DishCallKind.ROUTER_STATUS)),
    HISTORY(R.string.rsec_history, setOf(DishCallKind.ROUTER_HISTORY)),
    CONTROLS(R.string.rsec_controls, setOf(DishCallKind.ROUTER_STATUS, DishCallKind.ROUTER_CONFIG)),
}

/** Where the router is, and which of the router's clients is this phone (by the phone's addresses on the Wi-Fi). */
data class RouterEnv(val target: RouterTarget, val ownAddresses: Set<String>)

/**
 * Where the router is, looked up again every 3 s while the app is in front (the Wi-Fi can change under the screen); null
 * until the first lookup is done, so the screen does not claim "no router address" for the moment it takes.
 */
@Composable
fun rememberRouterEnv(): RouterEnv? {
    val context = LocalContext.current
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val env by produceState<RouterEnv?>(null, settings.routerHost, lifecycle) {
        val devices = Devices.shared(context)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = withContext(Dispatchers.IO) {
                    val wifi = devices.wifi()
                    RouterEnv(devices.routerTarget(settings, wifi), devices.ownAddresses(wifi))
                }
                delay(3_000)
            }
        }
    }
    return env
}

/**
 * The latest router answers in the shapes the cards use. The status carries the clients and the config as well, so a router
 * that does not answer the dedicated calls still fills the lists. Everything here is unredacted and in memory only.
 */
@Immutable
class RouterData(val samples: Map<DishCallKind, FeedSample>) {
    val statusSample: FeedSample? = samples[DishCallKind.ROUTER_STATUS]
    val status: WifiGetStatusResponse? = statusSample.okResponse()?.wifi_get_status
    val clientsSample: FeedSample? = samples[DishCallKind.ROUTER_CLIENTS]
    val clients: List<WifiClient>? = clientsSample.okResponse()?.wifi_get_clients?.clients?.takeIf { it.isNotEmpty() }
        ?: status?.clients?.takeIf { it.isNotEmpty() }
        ?: clientsSample.okResponse()?.wifi_get_clients?.clients
    val configSample: FeedSample? = samples[DishCallKind.ROUTER_CONFIG]
    val config: WifiConfig? = configSample.okResponse()?.wifi_get_config?.wifi_config ?: status?.config

    /** The sample to explain why a section is empty: the dedicated read, else the status. */
    fun problemFor(sample: FeedSample?): FeedSample? = if (sample != null && !sample.result.ok) sample else statusSample.takeIf { it != null && !it.result.ok } ?: sample
}

/**
 * The Router sub-tab: the router built into the Mini (the Wi-Fi network's gateway, or the address typed in Settings).
 * Status, the devices on its Wi-Fi, the Wi-Fi networks, its history and event log, and its controls. Reads are polled only
 * while the section is on screen; every change is sent only after a confirmation that says what will happen, and shows the
 * router's answer in plain words. The router's answers hold the Wi-Fi password: they stay in memory while this screen is
 * open and are never logged, stored or exported.
 */
@Composable
fun DishRouterTab(feed: DishFeed, section: RouterSection, onSection: (RouterSection) -> Unit) {
    val samples by feed.samples.collectAsStateWithLifecycle()
    val data = remember(samples) { RouterData(samples) }
    val env = rememberRouterEnv()
    val actions = rememberRouterActions(feed)
    // The router's answers hold the Wi-Fi password: they do not outlive the screen, and in the background the feed keeps
    // only redacted copies (the cards and an open edit dialog stay; the polls bring the real answers back at ON_START).
    // This screen's own last copy is released at the first recomposition after that, when the app is in front again.
    DisposableEffect(feed) { onDispose { feed.forgetRouterAnswers() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { feed.redactRouterAnswers() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.m, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            for (s in RouterSection.entries) {
                FilterChip(selected = s == section, onClick = { onSection(s) }, label = { Text(stringResource(s.label), maxLines = 1) })
            }
        }
        key(section) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                if (env == null) {
                    Hint(stringResource(R.string.router_looking))
                } else if (env.target.address == null) {
                    RouterAddressCard(env.target)
                } else {
                    when (section) {
                        RouterSection.STATUS -> {
                            RouterAddressCard(env.target)
                            RouterStatusSection(data, env)
                        }
                        RouterSection.DEVICES -> RouterDevicesSection(data, env, actions)
                        RouterSection.WIFI -> RouterWifiSection(data, env, actions)
                        RouterSection.HISTORY -> RouterHistorySection(feed, data, env)
                        RouterSection.CONTROLS -> RouterControlsSection(feed, data, env, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouterAddressCard(target: RouterTarget) {
    Section(stringResource(R.string.router_address_title), ledger = true) {
        KeyValueRow(
            stringResource(R.string.router_address),
            target.address ?: stringResource(R.string.router_address_none), status = if (target.address == null) Status.WARN else null)
        KeyValueRow(
            stringResource(R.string.router_address_source),
            stringResource(
                when (target.source) {
                    RouterSource.MANUAL -> R.string.router_source_manual
                    RouterSource.GATEWAY -> R.string.router_source_gateway
                    RouterSource.NONE -> R.string.router_source_none
                },
            ),
        )
        if (target.address == null) Hint(stringResource(R.string.router_address_hint))
    }
}

// ---- Status

@Composable
private fun RouterStatusSection(data: RouterData, env: RouterEnv) {
    val status = data.status
    if (status == null) {
        FeedProblem(data.statusSample, Device.ROUTER, env.target.address)
        return
    }
    RouterStateCard(status, data)
    RouterPingsCard(status)
    RouterBandsCard(status)
    RouterAlertsCard(status)
    RouterUpdateCard(status)
}

@Composable
private fun RouterStateCard(s: WifiGetStatusResponse, data: RouterData) {
    val colors = StarTheme.colors
    Section(stringResource(R.string.rs_state_title), ledger = true) {
        KeyValueRow(
            stringResource(R.string.rs_wan), stringResource(if (s.no_wan_link) R.string.rs_wan_down else R.string.rs_wan_up), status = if (s.no_wan_link) Status.BAD else Status.OK)
        KeyValueRow(stringResource(R.string.rs_wan_v4), s.ipv4_wan_address.ifEmpty { "-" })
        KeyValueRow(stringResource(R.string.rs_wan_v6), s.ipv6_wan_addresses.firstOrNull() ?: "-")
        KeyValueRow(stringResource(R.string.rs_public_ip), s.public_ipv4.ifEmpty { "-" })
        if (s.secs_since_last_public_ipv4_change > 0f && s.secs_since_last_public_ipv4_change.isFinite()) {
            KeyValueRow(stringResource(R.string.rs_ip_changed), stringResource(R.string.dish_ago, duration((s.secs_since_last_public_ipv4_change * 1000f).toLong())))
        }
        KeyValueRow(stringResource(R.string.dish_uptime), s.device_state?.uptime_s?.let { duration(it * 1000) } ?: "-")
        s.device_info?.hardware_version?.takeIf { it.isNotEmpty() }?.let { KeyValueRow(stringResource(R.string.ov_hardware), it) }
        s.device_info?.software_version?.takeIf { it.isNotEmpty() }?.let { KeyValueRow(stringResource(R.string.dish_firmware), it) }
        if (s.device_info?.dish_cohoused == true) KeyValueRow(stringResource(R.string.rs_built_in), stringResource(R.string.common_yes))
        data.clients?.let { list ->
            val online = list.count { ClientList.state(it) == ClientList.State.ONLINE }
            KeyValueRow(stringResource(R.string.rs_devices), online.toString())
        }
        if (SetupRequirement.isRequired(s.setup_requirement?.state ?: 0)) KeyValueRow(stringResource(R.string.rs_setup), stringResource(R.string.rs_setup_pending), status = Status.WARN)
        if (data.config?.bypass_mode == true) KeyValueRow(stringResource(R.string.rs_bypass), stringResource(R.string.rs_bypass_on), status = Status.BAD)
        if (s.dhcp_servers.any { it.ip_exhausted }) KeyValueRow(stringResource(R.string.rs_dhcp), stringResource(R.string.rs_dhcp_full), status = Status.WARN)
        if (Disablement.isDisabled(s.dish_disablement_code)) KeyValueRow(stringResource(R.string.dish_disablement), disablementReason(s.dish_disablement_code), status = Status.BAD)
    }
}

@Composable
private fun PingKV(label: String, latencyMs: Float, drop: Float, drop5m: Float) {
    val latency = if (latencyMs > 0f && latencyMs.isFinite()) stringResource(R.string.unit_ms, latencyMs.f(1)) else "-"
    KeyValueRow(
        label,
        stringResource(
            R.string.rs_ping_value, latency, stringResource(R.string.unit_percent, (drop * 100f).f(1)),
            stringResource(R.string.unit_percent, (drop5m * 100f).f(1)),
        ), status = if (drop > 0.05f) Status.WARN else null)
}

@Composable
private fun RouterPingsCard(s: WifiGetStatusResponse) {
    Section(stringResource(R.string.rs_pings_title)) {
        PingKV(stringResource(R.string.rs_ping_internet), s.ping_latency_ms, s.ping_drop_rate, s.ping_drop_rate_5m)
        PingKV(stringResource(R.string.rs_ping_pop), s.pop_ping_latency_ms, s.pop_ping_drop_rate, s.pop_ping_drop_rate_5m)
        if (s.pop_ipv6_ping_latency_ms > 0f) PingKV(stringResource(R.string.rs_ping_pop_v6), s.pop_ipv6_ping_latency_ms, s.pop_ipv6_ping_drop_rate, s.pop_ipv6_ping_drop_rate_5m)
        PingKV(stringResource(R.string.rs_ping_dish), s.dish_ping_latency_ms, s.dish_ping_drop_rate, s.dish_ping_drop_rate_5m)
        Hint(stringResource(R.string.rs_pings_hint))
    }
}

@Composable
private fun RouterBandsCard(s: WifiGetStatusResponse) {
    val bands = listOfNotNull(
        s.rf_2ghz_status?.let { R.string.rband_2 to it },
        s.rf_5ghz_status?.let { R.string.rband_5 to it },
    )
    if (bands.isEmpty()) return
    Section(stringResource(R.string.rs_bands_title)) {
        for ((label, b) in bands) {
            KeyValueRow(
                stringResource(label),
                stringResource(
                    R.string.rs_band_value, stringResource(R.string.unit_percent, (b.chan_busy_time_fraction * 100f).f(0)),
                    stringResource(R.string.unit_percent, (b.obss_air_time_fraction * 100f).f(0)),
                ), status = if (b.chan_busy_time_fraction > 0.7f) Status.WARN else null)
        }
        Hint(stringResource(R.string.rs_bands_hint))
    }
}

@Composable
private fun RouterAlertsCard(s: WifiGetStatusResponse) {
    val colors = StarTheme.colors
    val keys = remember(s) { AlertList.router(s.alerts) }
    Section(stringResource(R.string.rs_alerts_title)) {
        if (keys.isEmpty()) StatusPill(Status.OK, stringResource(R.string.rs_alerts_none))
        for (key in keys) {
            val severity = routerAlertSeverity(key)
            val status = when (severity) {
                AlertSeverity.WARNING -> Status.BAD
                AlertSeverity.CAUTION -> Status.WARN
                AlertSeverity.ADVISORY -> Status.NEUTRAL
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                StatusPill(status, stringResource(severityRes(severity)))
                Text(routerAlertText(key), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RouterUpdateCard(s: WifiGetStatusResponse) {
    val colors = StarTheme.colors
    val u = remember(s) { RouterUpdateView.of(s.software_update_stats) }
    Section(stringResource(R.string.rs_update_title), ledger = true) {
        if (u == null) {
            Hint(stringResource(R.string.ov_not_reported))
            return@Section
        }
        KeyValueRow(
            stringResource(R.string.ov_update_state), routerUpdateText(u.state), status = when {
                u.failed -> Status.BAD
                u.rebootPending || u.busy -> Status.WARN
                u.upToDate -> Status.OK
                else -> null
            })
        u.progress?.takeIf { u.busy }?.let { KeyValueRow(stringResource(R.string.ov_update_progress), stringResource(R.string.unit_percent, (it * 100f).f(0))) }
        if (u.runningVersion.isNotEmpty()) KeyValueRow(stringResource(R.string.rs_update_running), u.runningVersion)
        if (u.versionInProgress.isNotEmpty() && u.versionInProgress != u.runningVersion) KeyValueRow(stringResource(R.string.rs_update_new), u.versionInProgress)
        if (u.rebootPending) Hint(stringResource(R.string.rs_update_restart_hint))
        Hint(stringResource(R.string.rs_update_hint))
    }
}
