package tools.apachi.starlinknav.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.AlertList
import tools.apachi.starlinknav.core.dish.AlertSeverity
import tools.apachi.starlinknav.core.dish.AttitudeState
import tools.apachi.starlinknav.core.dish.ClassOfService
import tools.apachi.starlinknav.core.dish.Disablement
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishHistory
import tools.apachi.starlinknav.core.dish.DishLinkState
import tools.apachi.starlinknav.core.dish.DishStates
import tools.apachi.starlinknav.core.dish.HardwareSelfTest
import tools.apachi.starlinknav.core.dish.HasActuators
import tools.apachi.starlinknav.core.dish.InitializationView
import tools.apachi.starlinknav.core.dish.RateLimitReason
import tools.apachi.starlinknav.core.dish.Series
import tools.apachi.starlinknav.core.dish.SoftwareUpdate
import tools.apachi.starlinknav.core.dish.attitude
import tools.apachi.starlinknav.core.dish.hasActuators
import tools.apachi.starlinknav.core.dish.proto.DishGetDiagnosticsResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.updateRestartAtS
import tools.apachi.starlinknav.core.dish.updateWaiting
import tools.apachi.starlinknav.core.dish.readableName
import tools.apachi.starlinknav.service.DebugExport
import tools.apachi.starlinknav.service.DeviceCall
import tools.apachi.starlinknav.service.DishFeed
import tools.apachi.starlinknav.service.Live
import java.io.File

/**
 * The state of the dish in plain words: what it is doing, its alerts, signal, power, obstructions, software and
 * update, GNSS, orientation, how long this power-on took to start, plan limits; and the Debug data export.
 * Everything here is read-only except "Install now" (a restart, behind a confirmation).
 */
@Composable
fun DishOverview(feed: DishFeed) {
    val samples by feed.samples.collectAsStateWithLifecycle()
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    val statusSample = samples[DishCallKind.STATUS]
    val status = statusSample.okResponse()?.dish_get_status
    val diagnostics = samples[DishCallKind.DIAGNOSTICS].okResponse()?.dish_get_diagnostics
    val historySample = samples[DishCallKind.HISTORY]
    val history = remember(historySample) { historySample.okResponse()?.dish_get_history?.let(DishHistory::of) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (status == null) {
            FeedProblem(statusSample, address = settings.dishHost)
        } else {
            StateCard(status)
            AlertsCard(status, diagnostics)
            ConnectionCard(status, history)
            UpdateCard(status)
            ObstructionCard(status)
            GnssCard(status)
            OrientationCard(status)
            StartupCard(status)
            PlanCard(status)
            BatteryCard(status)
        }
        DebugDataCard()
    }
}

@Composable
private fun StateCard(s: DishGetStatusResponse) {
    val colors = StarTheme.colors
    val st = remember(s) { DishStates.of(s) }
    Section(stringResource(R.string.ov_state_title), ledger = true) {
        KeyValueRow(
            stringResource(R.string.ov_state), stringResource(linkStateRes(st.state)), status = when (st.state) {
                DishLinkState.CONNECTED -> Status.OK
                DishLinkState.UNKNOWN -> Status.NEUTRAL
                else -> Status.WARN
            })
        KeyValueRow(stringResource(R.string.dish_uptime), s.device_state?.uptime_s?.let { duration(it * 1000) } ?: "-")
        s.device_info?.hardware_version?.takeIf { it.isNotEmpty() }?.let { KeyValueRow(stringResource(R.string.ov_hardware), it) }
        s.device_info?.software_version?.takeIf { it.isNotEmpty() }?.let { KeyValueRow(stringResource(R.string.dish_firmware), it) }
        s.device_info?.country_code?.takeIf { it.isNotEmpty() }?.let { KeyValueRow(stringResource(R.string.ov_country), it) }
        // The ready stages in the order the dish starts them; the cady clock stage does not exist on a Mini.
        s.ready_states?.let { rs ->
            val ready = listOfNotNull("scp".takeIf { rs.scp }, "l1l2".takeIf { rs.l1l2 }, "xphy".takeIf { rs.xphy }, "aap".takeIf { rs.aap }, "rf".takeIf { rs.rf })
            KeyValueRow(stringResource(R.string.ov_ready), ready.joinToString(" ").ifEmpty { "-" }, status = if (st.notReady.isEmpty()) Status.OK else null)
            if (st.notReady.isNotEmpty()) KeyValueRow(stringResource(R.string.ov_not_ready), st.notReady.joinToString(" "), status = Status.WARN)
            if (!DishStates.hasCady(s.device_info?.hardware_version.orEmpty())) Hint(stringResource(R.string.ov_cady_hint))
        }
        if (s.stow_requested && s.hasActuators() != HasActuators.NO) KeyValueRow(stringResource(R.string.ov_stow_requested), stringResource(R.string.common_yes))
    }
}

@Composable
private fun AlertsCard(s: DishGetStatusResponse, d: DishGetDiagnosticsResponse?) {
    val colors = StarTheme.colors
    val alerts = remember(s, d) { AlertList.of(s, d) }
    Section(stringResource(R.string.ov_alerts_title)) {
        if (alerts.isEmpty()) StatusPill(Status.OK, stringResource(R.string.ov_alerts_none))
        for (a in alerts) {
            val status = when (a.severity) {
                AlertSeverity.WARNING -> Status.BAD
                AlertSeverity.CAUTION -> Status.WARN
                AlertSeverity.ADVISORY -> Status.NEUTRAL
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                StatusPill(status, stringResource(severityRes(a.severity)))
                Text(alertText(a.key), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (d != null) {
            val test = d.hardware_self_test
            KeyValueRow(
                stringResource(R.string.ov_self_test), selfTestText(test), status = when (test) {
                    HardwareSelfTest.PASSED -> Status.OK
                    HardwareSelfTest.FAILED -> Status.BAD
                    else -> Status.NEUTRAL
                })
            if (test == HardwareSelfTest.FAILED && d.hardware_self_test_codes.isNotEmpty()) {
                KeyValueRow(stringResource(R.string.ov_self_test_codes), d.hardware_self_test_codes.joinToString(" ") { HardwareSelfTest.codeName(it) }, status = Status.BAD)
            }
        }
    }
}

@Composable
private fun ConnectionCard(s: DishGetStatusResponse, history: DishHistory?) {
    val colors = StarTheme.colors
    Section(stringResource(R.string.ov_connection_title), ledger = true) {
        val connected = s.pop_ping_latency_ms > 0f
        KeyValueRow(stringResource(R.string.ov_latency), if (connected) stringResource(R.string.unit_ms, s.pop_ping_latency_ms.f(1)) else "-")
        KeyValueRow(
            stringResource(R.string.ov_drop), stringResource(R.string.unit_percent, (s.pop_ping_drop_rate * 100f).f(1)), status = if (s.pop_ping_drop_rate > 0.05f) Status.WARN else null)
        KeyValueRow(stringResource(R.string.ov_download), stringResource(R.string.unit_mbps, (s.downlink_throughput_bps / 1e6f).f(2)))
        KeyValueRow(stringResource(R.string.ov_upload), stringResource(R.string.unit_mbps, (s.uplink_throughput_bps / 1e6f).f(2)))
        KeyValueRow(stringResource(R.string.ov_signal), s.signal_quality.f(2))
        KeyValueRow(
            stringResource(R.string.ov_snr), stringResource(if (s.is_snr_above_noise_floor) R.string.ov_snr_ok else R.string.ov_snr_low), status = if (s.is_snr_above_noise_floor && !s.is_snr_persistently_low) null else Status.WARN)
        // Power draw: the power supply's own figure when it reports one, else the newest sample of the history (a Mini).
        val upsu = s.upsu_stats?.dish_power?.takeIf { it > 0f }
        val aps = s.aps_stats?.dish_power?.takeIf { it > 0f }
        val watts = upsu ?: aps ?: history?.powerInW?.let(Series::lastFinite)?.takeIf { it > 0f }
        KeyValueRow(stringResource(R.string.ov_power), watts?.let { stringResource(R.string.unit_w, it.f(1)) } ?: "-")
        s.upsu_stats?.router_power?.takeIf { it > 0f }?.let { KeyValueRow(stringResource(R.string.ov_router_power), stringResource(R.string.unit_w, it.f(1))) }
        if (s.eth_speed_mbps > 0) KeyValueRow(stringResource(R.string.ov_ethernet), stringResource(R.string.unit_mbps, s.eth_speed_mbps.toString()))
    }
}

@Composable
private fun UpdateCard(s: DishGetStatusResponse) {
    val ctx = LocalContext.current
    val colors = StarTheme.colors
    val sender = rememberSender()
    val reboot by Live.reboot.collectAsStateWithLifecycle()
    val now = rememberMonoNow(1000)
    var confirm by remember { mutableStateOf(false) }
    val waiting = s.updateWaiting()
    Section(stringResource(R.string.ov_update_title)) {
        val state = s.software_update_state
        val restartAt = s.updateRestartAtS()
        KeyValueRow(
            stringResource(R.string.ov_update_state),
            when {
                waiting -> restartAt?.let { stringResource(R.string.dish_update_waiting_at, restartTime(ctx, it)) } ?: stringResource(R.string.dish_update_waiting)
                state == 1 -> stringResource(R.string.dish_update_none)
                state == 2 -> stringResource(R.string.dish_update_fetching)
                state in 3..5 -> stringResource(R.string.dish_update_installing)
                state == 7 -> stringResource(R.string.dish_update_disabled)
                state == 8 -> stringResource(R.string.dish_update_failed)
                else -> SoftwareUpdate.name(state).lowercase().replace('_', ' ')
            }, status = when {
                waiting -> Status.WARN
                state == 8 -> Status.BAD
                else -> null
            })
        s.software_update_stats?.software_update_progress?.takeIf { it > 0f && state in 2..5 }?.let {
            KeyValueRow(stringResource(R.string.ov_update_progress), stringResource(R.string.unit_percent, (it * 100f).coerceAtMost(100f).f(0)))
        }
        s.config?.let { c ->
            KeyValueRow(stringResource(R.string.ov_update_hour), stringResource(R.string.ov_update_hour_value, c.swupdate_reboot_hour.toString()))
        }
        if (waiting) {
            Hint(stringResource(R.string.ov_update_waiting_hint))
            PrimaryButton(stringResource(R.string.ov_install_now), { confirm = true }, enabled = !sender.busy)
            CommandOutcomeLine(TAG_INSTALL_NOW)
        }
        reboot.last?.let { r ->
            KeyValueRow(stringResource(R.string.dish_restarts), stringResource(R.string.dish_restarts_value, reboot.count.toString(), duration((now - r.backNs) / 1_000_000), rebootCause(r)))
        }
    }
    if (confirm) {
        val call = DeviceCall.of(DishCallKind.REBOOT)
        ConfirmDialog(
            title = stringResource(R.string.ov_install_title),
            text = stringResource(R.string.ov_install_text),
            confirmLabel = stringResource(R.string.ov_install_confirm),
            risk = call.risk,
            onConfirm = {
                confirm = false
                sender.send(call, TAG_INSTALL_NOW)
            },
            onDismiss = { confirm = false },
        )
    }
}

private const val TAG_INSTALL_NOW = "install_now"

@Composable
private fun ObstructionCard(s: DishGetStatusResponse) {
    val o = s.obstruction_stats
    Section(stringResource(R.string.ov_obstruction_title), ledger = true) {
        if (o == null) {
            Hint(stringResource(R.string.ov_not_reported))
            return@Section
        }
        KeyValueRow(stringResource(R.string.ov_obstructed_share), stringResource(R.string.unit_percent, (o.fraction_obstructed * 100f).f(1)))
        KeyValueRow(
            stringResource(R.string.ov_obstructed_now), stringResource(if (o.currently_obstructed) R.string.common_yes else R.string.common_no), status = if (o.currently_obstructed) Status.WARN else null)
        if (o.valid_s > 0f) KeyValueRow(stringResource(R.string.ov_obstruction_window), duration((o.valid_s * 1000).toLong()))
        if (o.avg_prolonged_obstruction_valid && o.avg_prolonged_obstruction_duration_s.isFinite() && o.avg_prolonged_obstruction_interval_s.isFinite()) {
            KeyValueRow(stringResource(R.string.ov_prolonged_duration), stringResource(R.string.unit_s, o.avg_prolonged_obstruction_duration_s.f(1)))
            KeyValueRow(stringResource(R.string.ov_prolonged_interval), stringResource(R.string.unit_s, o.avg_prolonged_obstruction_interval_s.f(0)))
        }
        if (o.patches_valid > 0) KeyValueRow(stringResource(R.string.ov_patches), o.patches_valid.toString())
        Hint(stringResource(R.string.ov_obstruction_hint))
    }
}

@Composable
private fun GnssCard(s: DishGetStatusResponse) {
    val colors = StarTheme.colors
    val g = s.gps_stats
    Section(stringResource(R.string.ov_gnss_title), ledger = true) {
        if (g == null) {
            Hint(stringResource(R.string.ov_not_reported))
            return@Section
        }
        KeyValueRow(
            stringResource(R.string.dish_gps),
            pluralStringResource(if (g.gps_valid) R.plurals.dish_gps_valid else R.plurals.dish_gps_invalid, g.gps_sats, g.gps_sats), status = if (g.gps_valid) Status.OK else Status.BAD)
        if (g.no_sats_after_ttff) KeyValueRow(stringResource(R.string.ov_no_sats_after_ttff), stringResource(R.string.common_yes), status = Status.WARN)
        KeyValueRow(stringResource(R.string.ov_gnss_inhibit), stringResource(if (g.inhibit_gps) R.string.ov_gnss_inhibited else R.string.ov_gnss_allowed), status = if (g.inhibit_gps) Status.WARN else null)
        // Absent before firmware 2026.09, where it reads as 0 (reset): show only a real state.
        g.pnt_filter_convergence_state.takeIf { it != 0 }?.let { KeyValueRow(stringResource(R.string.dish_pnt_filter), attitudeStateText(it)) }
    }
}

@Composable
private fun OrientationCard(s: DishGetStatusResponse) {
    val colors = StarTheme.colors
    Section(stringResource(R.string.ov_orientation_title), ledger = true) {
        val a = s.alignment_stats
        if (a != null) {
            KeyValueRow(
                stringResource(R.string.dish_attitude),
                stringResource(R.string.ov_attitude_value, attitudeStateText(a.attitude_estimation_state), a.attitude_uncertainty_deg.f(1)), status = if (a.attitude_estimation_state == AttitudeState.CONVERGED) Status.OK else Status.WARN)
            KeyValueRow(stringResource(R.string.dish_tilt), "${a.tilt_angle_deg.f(1)}°")
        }
        s.ned2dish_quaternion?.let { q -> KeyValueRow(stringResource(R.string.dish_heading), "${q.attitude().headingDeg.f(1)}°") }
        // Where the dish points, and where it should for the best view (the placement hint of the Starlink app).
        val az = a?.boresight_azimuth_deg?.takeIf { it != 0f || a.boresight_elevation_deg != 0f } ?: s.boresight_azimuth_deg
        val el = a?.boresight_elevation_deg?.takeIf { it != 0f || a.boresight_azimuth_deg != 0f } ?: s.boresight_elevation_deg
        KeyValueRow(stringResource(R.string.ov_boresight), "${az.f(1)}° / ${el.f(1)}°")
        if (a != null && (a.desired_boresight_azimuth_deg != 0f || a.desired_boresight_elevation_deg != 0f)) {
            KeyValueRow(stringResource(R.string.ov_boresight_wanted), "${a.desired_boresight_azimuth_deg.f(1)}° / ${a.desired_boresight_elevation_deg.f(1)}°")
        }
        Hint(stringResource(R.string.ov_orientation_hint))
    }
}

@Composable
private fun StartupCard(s: DishGetStatusResponse) {
    val init = remember(s) { InitializationView.of(s.initialization_duration_seconds) }
    Section(stringResource(R.string.ov_startup_title), ledger = true) {
        if (init.stages.isEmpty()) {
            Hint(stringResource(R.string.ov_startup_none))
            return@Section
        }
        for (stage in init.stages) KeyValueRow(initStageText(stage.key), duration(stage.seconds * 1000L))
        init.totalS?.let { KeyValueRow(stringResource(R.string.ov_startup_total), duration(it * 1000L), status = Status.OK) }
        if (init.notReached.isNotEmpty()) {
            val names = init.notReached.map { key -> initStageText(key) }
            Hint(stringResource(R.string.ov_startup_not_reached, names.joinToString(", ")))
        }
        Hint(stringResource(R.string.ov_startup_hint))
    }
}

@Composable
private fun PlanCard(s: DishGetStatusResponse) {
    val colors = StarTheme.colors
    Section(stringResource(R.string.ov_plan_title), ledger = true) {
        KeyValueRow(stringResource(R.string.dish_class_of_service), classOfServiceText(ClassOfService.name(s.class_of_service)))
        KeyValueRow(stringResource(R.string.dish_mobility), mobilityText(s.mobility_class))
        val disabled = Disablement.isDisabled(s.disablement_code)
        KeyValueRow(
            stringResource(R.string.dish_disablement),
            if (disabled) disablementReason(s.disablement_code) else stringResource(R.string.ov_not_disabled), status = if (disabled) Status.BAD else Status.OK)
        KeyValueRow(stringResource(R.string.dish_moving_fast), stringResource(if (s.is_moving_fast_persisted) R.string.common_yes else R.string.common_no))
        for ((label, code) in listOf(R.string.ov_limit_down to s.dl_bandwidth_restricted_reason, R.string.ov_limit_up to s.ul_bandwidth_restricted_reason)) {
            KeyValueRow(stringResource(label), rateLimitText(code), status = if (RateLimitReason.isRestricted(code)) Status.WARN else null)
        }
        if (s.treat_as_metered) KeyValueRow(stringResource(R.string.ov_metered), stringResource(R.string.common_yes), status = Status.WARN)
    }
}

@Composable
private fun BatteryCard(s: DishGetStatusResponse) {
    val b = s.battery_stats ?: return
    if (b.state_of_charge == 0 && b.power_source == 0 && !b.is_charging) return
    Section(stringResource(R.string.ov_battery_title), ledger = true) {
        KeyValueRow(stringResource(R.string.ov_battery_charge), stringResource(R.string.unit_percent, b.state_of_charge.toString()))
        KeyValueRow(stringResource(R.string.ov_battery_charging), stringResource(if (b.is_charging) R.string.common_yes else R.string.common_no))
        KeyValueRow(stringResource(R.string.ov_battery_source), powerSourceText(b.power_source))
    }
}

/** A snapshot of the dish and the router for support: JSON through the share sheet. Identifiers off by default; never passwords or a position. */
@Composable
private fun DebugDataCard() {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var includeIds by rememberSaveable { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Section(stringResource(R.string.dbg_title)) {
        Hint(stringResource(R.string.dbg_hint))
        Row(Modifier.heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dbg_include_ids), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = includeIds, onCheckedChange = { includeIds = it })
        }
        Hint(stringResource(if (includeIds) R.string.dbg_ids_on else R.string.dbg_ids_off))
        PrimaryButton(stringResource(if (running) R.string.dbg_running else R.string.dbg_export), {
            scope.launch {
                running = true
                message = null
                val result = withContext(Dispatchers.IO) { runCatching { DebugExport.run(ctx, includeIds) } }
                running = false
                result.onSuccess { file ->
                    shareDebugData(ctx, file, resources.getString(R.string.dbg_share_title))
                    message = resources.getString(R.string.dbg_done, bytes(resources, file.length()))
                }.onFailure { message = resources.getString(R.string.dbg_failed, it.message ?: it.readableName()) }
            }
        }, enabled = !running)
        message?.let { Hint(it) }
    }
}

private fun shareDebugData(ctx: android.content.Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.exports", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, title))
}
