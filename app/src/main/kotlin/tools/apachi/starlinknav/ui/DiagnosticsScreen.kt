package tools.apachi.starlinknav.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.AttitudeState
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.core.dish.MobilityClass
import tools.apachi.starlinknav.core.dish.fix
import tools.apachi.starlinknav.core.dish.ClassOfService
import tools.apachi.starlinknav.core.dish.Disablement
import tools.apachi.starlinknav.core.dish.DishFix
import tools.apachi.starlinknav.core.dish.attitude
import tools.apachi.starlinknav.core.nav.DishReboot
import tools.apachi.starlinknav.core.nav.InertialState
import tools.apachi.starlinknav.service.CutoffUi
import tools.apachi.starlinknav.service.DishUi
import tools.apachi.starlinknav.service.Live
import tools.apachi.starlinknav.service.inertialServesApps
import tools.apachi.starlinknav.service.PhoneSensors
import tools.apachi.starlinknav.service.PhoneUi
import tools.apachi.starlinknav.service.RebootUi

/**
 * The Diagnostics sub-tab of the Starlink page (was Details): what the location service sees of the dish, the cut-offs
 * and restarts it followed, and the phone's own GNSS and sensors. The values come from the service, so they are live
 * while it runs (mock on, a recording, or the map watching).
 */
@Composable
fun DiagnosticsScreen() {
    val dish by Live.dish.collectAsStateWithLifecycle()
    val phone by Live.phone.collectAsStateWithLifecycle()
    val mock by Live.mock.collectAsStateWithLifecycle()
    val cutoff by Live.cutoff.collectAsStateWithLifecycle()
    val reboot by Live.reboot.collectAsStateWithLifecycle()
    val inertial by Live.inertial.collectAsStateWithLifecycle()
    val service by Live.service.collectAsStateWithLifecycle()
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    val now = rememberMonoNow()
    val inertialServing = inertialServesApps(inertial, mock, settings)

    LazyColumn(contentPadding = PaddingValues(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        if (!service.running) item { Hint(stringResource(R.string.diag_service_off)) }
        if (cutoff.active != null) item { CutoffCard(cutoff, now, continues = inertialServing && inertial.state != InertialState.NO_OUTPUT) }
        if (cutoff.active == null && cutoff.count > 0) item { CutoffCard(cutoff, now) }
        reboot.last?.let { last -> item { RebootCard(last, reboot.count, now) } }
        item { DishStatusCard(dish, reboot, now) }
        item { DishLocationsCard(dish, now) }
        item { PhoneCard(phone, now) }
        item { SensorsCard(phone) }
    }
}

/** The dish refusing GetLocation (UT disablement, on this plan: moving too fast for the plan). */
@Composable
fun CutoffCard(c: CutoffUi, now: Long, continues: Boolean = false) {
    val active = c.active
    Section(stringResource(if (active != null) R.string.status_cutoff_active_title else R.string.status_cutoff_session_title), ledger = true) {
        if (active != null) {
            KeyValueRow(stringResource(R.string.status_cutoff_reason), disablementReason(active.code), status = Status.BAD)
            KeyValueRow(stringResource(R.string.status_cutoff_for), stringResource(R.string.unit_s, "%.0f".format(java.util.Locale.ROOT, active.durationS(now))), status = Status.BAD)
            KeyValueRow(stringResource(R.string.status_cutoff_speed_before), kmh(active.speedBeforeMps))
            KeyValueRow(stringResource(R.string.status_cutoff_max_before), kmh(active.maxSpeed60sMps))
            Hint(stringResource(if (continues) R.string.status_cutoff_hint_inertial else R.string.status_cutoff_hint))
        }
        val last = c.last
        if (last != null) {
            val end = last.endNs ?: now
            KeyValueRow(
                stringResource(R.string.status_cutoff_last),
                stringResource(
                    R.string.status_cutoff_last_value,
                    "%.0f".format(java.util.Locale.ROOT, last.durationS(now)),
                    duration((now - end) / 1_000_000),
                ),
            )
            KeyValueRow(stringResource(R.string.status_cutoff_last_speeds), "${kmh(last.speedBeforeMps)} / ${kmh(last.speedAfterMps)}")
        }
        KeyValueRow(stringResource(R.string.status_cutoff_this_session), "${c.count} · " + stringResource(R.string.unit_s, "%.0f".format(java.util.Locale.ROOT, c.finishedS + (active?.durationS(now) ?: 0.0))))
    }
}

@Composable
private fun kmh(mps: Double?) = mps?.let { stringResource(R.string.unit_kmh, (it * 3.6).f(0)) } ?: "-"

/** The last dish restart: when it came back, how long it was gone, and why. */
@Composable
fun RebootCard(r: DishReboot, count: Int, now: Long) {
    Section(stringResource(R.string.reboot_title), ledger = true) {
        KeyValueRow(stringResource(R.string.reboot_back), stringResource(R.string.reboot_ago, duration((now - r.backNs) / 1_000_000)))
        r.offlineS?.let { KeyValueRow(stringResource(R.string.reboot_offline), duration((it * 1000).toLong()), status = Status.WARN) }
        KeyValueRow(stringResource(R.string.reboot_why), rebootCause(r))
        rebootCodes(r)?.let { KeyValueRow(stringResource(R.string.reboot_codes), it) }
        if (r.updated) {
            KeyValueRow(stringResource(R.string.reboot_software), "${r.softwareBefore} → ${r.softwareAfter}")
        } else if (r.updateWaiting) {
            Hint(stringResource(R.string.reboot_update_not_installed))
        }
        if (count > 1) KeyValueRow(stringResource(R.string.reboot_count), count.toString())
    }
}

/** What the dish says about its software update, e.g. "waiting for a restart, planned Thu 03:56". */
@Composable
fun updateText(ui: RebootUi): String {
    val ctx = LocalContext.current
    return when {
        ui.updateWaiting -> ui.updateRestartAtS?.let { stringResource(R.string.dish_update_waiting_at, restartTime(ctx, it)) }
            ?: stringResource(R.string.dish_update_waiting)
        ui.updateState == 1 -> stringResource(R.string.dish_update_none)
        ui.updateState == 2 -> stringResource(R.string.dish_update_fetching)
        ui.updateState in 3..5 -> stringResource(R.string.dish_update_installing)
        ui.updateState == 7 -> stringResource(R.string.dish_update_disabled)
        ui.updateState == 8 -> stringResource(R.string.dish_update_failed)
        else -> "-"
    }
}

/** Local weekday and time of a Unix-seconds instant. */
fun restartTime(ctx: android.content.Context, unixS: Long): String =
    android.text.format.DateUtils.formatDateTime(
        ctx, unixS * 1000,
        android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY,
    )

@Composable
private fun DishStatusCard(dish: DishUi, reboot: RebootUi, now: Long) {
    Section(stringResource(R.string.dish_title), ledger = true) {
        KeyValueRow(stringResource(R.string.dish_network), dish.route?.let { stringResource(it.label) } ?: "-")
        KeyValueRow(stringResource(R.string.dish_transport), dish.transport)
        KeyValueRow(stringResource(R.string.dish_endpoint), dish.endpoint)
        val sample = dish.last[DishCallKind.STATUS]
        if (sample == null) {
            StateView(UiState.Empty(stringResource(R.string.dish_no_answer)))
            return@Section
        }
        val r = sample.result
        KeyValueRow(stringResource(R.string.dish_status_call), "${r.statusText} · ${age(now, sample.atMonoNs)}", status = if (r.ok) Status.OK else Status.BAD)
        val s = r.response?.dish_get_status ?: return@Section
        val g = s.gps_stats
        KeyValueRow(
            stringResource(R.string.dish_gps),
            pluralStringResource(if (g?.gps_valid == true) R.plurals.dish_gps_valid else R.plurals.dish_gps_invalid, g?.gps_sats ?: 0, g?.gps_sats ?: 0), status = if (g?.gps_valid == true) Status.OK else Status.BAD)
        // Absent before firmware 2026.09, where it reads as 0 (FILTER_RESET): show only real values.
        g?.pnt_filter_convergence_state?.takeIf { it != 0 }?.let { KeyValueRow(stringResource(R.string.dish_pnt_filter), AttitudeState.name(it)) }
        if (g?.inhibit_gps == true) KeyValueRow("inhibit_gps", "true", status = Status.WARN)
        s.alignment_stats?.let { a ->
            KeyValueRow(stringResource(R.string.dish_attitude), "${AttitudeState.name(a.attitude_estimation_state)} ±${a.attitude_uncertainty_deg.f()}°", status = if (a.attitude_estimation_state == AttitudeState.CONVERGED) Status.OK else Status.WARN)
            KeyValueRow(stringResource(R.string.dish_tilt), "${a.tilt_angle_deg.f()}°")
        }
        s.ned2dish_quaternion?.let { q ->
            val at = q.attitude()
            KeyValueRow(stringResource(R.string.dish_heading), "${at.headingDeg.f()}°")
            KeyValueRow(stringResource(R.string.dish_normal), "${at.normalAzimuthDeg.f()}/${at.normalElevationDeg.f()} · ${s.boresight_azimuth_deg.f()}/${s.boresight_elevation_deg.f()}°")
        } ?: KeyValueRow(stringResource(R.string.dish_quaternion), stringResource(R.string.dish_quaternion_absent), status = Status.WARN)
        KeyValueRow(stringResource(R.string.dish_class_of_service), ClassOfService.name(s.class_of_service))
        // e.g. MOVING_TOO_FAST_FOR_POLICY: the dish refuses GetLocation while disabled.
        KeyValueRow(stringResource(R.string.dish_disablement), Disablement.name(s.disablement_code).replace('_', ' '), status = if (s.disablement_code == Disablement.OKAY) null else Status.BAD)
        KeyValueRow(stringResource(R.string.dish_moving_fast), s.is_moving_fast_persisted.toString())
        KeyValueRow(stringResource(R.string.dish_mobility), MobilityClass.name(s.mobility_class))
        KeyValueRow(stringResource(R.string.dish_uptime), s.device_state?.uptime_s?.let { duration(it * 1000) } ?: "-")
        s.device_info?.software_version?.takeIf { it.isNotEmpty() }?.let { KeyValueRow(stringResource(R.string.dish_firmware), it) }
        KeyValueRow(stringResource(R.string.dish_update), updateText(reboot), status = if (reboot.updateWaiting) Status.WARN else null)
        KeyValueRow(
            stringResource(R.string.dish_restarts),
            reboot.last?.let { stringResource(R.string.dish_restarts_value, reboot.count.toString(), duration((now - it.backNs) / 1_000_000), rebootCause(it)) }
                ?: stringResource(R.string.dish_restarts_none),
        )
    }
}

@Composable
private fun DishLocationsCard(dish: DishUi, now: Long) {
    Section(stringResource(R.string.dish_locations_title), ledger = true) {
        // Before the first answer the card would be a title with nothing under it.
        if (DishCallKind.RECORDED_LOCATIONS.none { dish.last[it] != null }) StateView(UiState.Empty(stringResource(R.string.dish_no_answer)))
        val auto = dish.last[DishCallKind.LOCATION_AUTO]?.result?.takeIf { it.ok }?.response?.fix()?.takeIf { !it.looksLikePlaceholder }
        val fixes = mutableMapOf<DishCallKind, DishFix>()
        val unsupported = mutableListOf<String>()
        val refused = mutableListOf<String>()
        for (kind in DishCallKind.RECORDED_LOCATIONS) {
            val sample = dish.last[kind] ?: continue
            val r = sample.result
            val f = r.response?.fix()
            val source = kind.locationSource!!
            val name = source.name
            if (!r.ok) {
                if (r.unsupported) unsupported += name else refused += name
                continue
            }
            if (f == null || f.looksLikePlaceholder) {
                KeyValueRow("$name (${age(now, sample.atMonoNs)})", stringResource(R.string.dish_no_fix), status = Status.WARN)
                continue
            }
            fixes[kind] = f
            val via = if (f.source != source.code) "→${f.sourceName} " else ""
            val where = if (kind == DishCallKind.LOCATION_AUTO || auto == null) {
                "${f.lat.f(6)},${f.lon.f(6)}"
            } else {
                "Δ" + stringResource(R.string.live_unit_m, Geo.distanceM(auto.lat, auto.lon, f.lat, f.lon).f(1))
            }
            KeyValueRow("$name (${age(now, sample.atMonoNs)})", "${via}σ" + stringResource(R.string.live_unit_m, f.sigmaM.f(1)) + " · $where", status = Status.OK)
        }
        if (refused.isNotEmpty()) KeyValueRow(stringResource(R.string.dish_refused_now), refused.joinToString(" "), status = Status.BAD)
        if (unsupported.isNotEmpty()) KeyValueRow(stringResource(R.string.dish_not_supported), unsupported.joinToString(" "), status = Status.NEUTRAL)
        val gps = fixes[DishCallKind.LOCATION_GPS]
        val sl = fixes[DishCallKind.LOCATION_STARLINK]
        if (gps != null && sl != null) {
            val d = Geo.distanceM(gps.lat, gps.lon, sl.lat, sl.lon)
            val limit = 50.0 + 3 * (gps.sigmaM + sl.sigmaM)
            KeyValueRow("|GPS − STARLINK|", stringResource(R.string.live_unit_m, d.f(1)), status = if (d <= limit) Status.OK else Status.BAD)
        }
        dish.last[DishCallKind.DIAGNOSTICS]?.let { sample ->
            val gpsTime = sample.result.response?.dish_get_diagnostics?.location?.gps_time_s?.takeIf { it > 0 } ?: return@let
            val receivedWall = System.currentTimeMillis() - (now - sample.atMonoNs) / 1_000_000
            KeyValueRow(stringResource(R.string.dish_fix_age), stringResource(R.string.unit_s, ((receivedWall - Geo.gpsSecondsToUnixMs(gpsTime)) / 1000.0).f(2)))
        }
    }
}

@Composable
private fun PhoneCard(phone: PhoneUi, now: Long) {
    Section(stringResource(R.string.phone_title), ledger = true) {
        val p = phone.lastGps
        if (p == null) {
            StateView(UiState.Empty(stringResource(R.string.phone_no_fix)))
        } else {
            KeyValueRow(stringResource(R.string.phone_fix), "${p.lat.f(6)},${p.lon.f(6)}")
            KeyValueRow(stringResource(R.string.phone_accuracy), p.h_acc?.let { stringResource(R.string.live_unit_m, it.f()) } ?: "-")
            KeyValueRow(stringResource(R.string.phone_speed_bearing), (p.speed?.let { stringResource(R.string.unit_mps, it.f()) } ?: "-") + " / ${p.bearing?.f(0) ?: "-"}°")
            KeyValueRow(stringResource(R.string.phone_age), age(now, phone.lastGpsAtMonoNs))
        }
        KeyValueRow(stringResource(R.string.phone_satellites), stringResource(R.string.phone_satellites_value, phone.satsUsed, phone.satsVisible))
        KeyValueRow(stringResource(R.string.phone_cn0), phone.meanCn0Used?.let { stringResource(R.string.pos_gps_signal_value, it.f()) } ?: "-")
        // Negative AGC means strong in-band power: on drives it came with jamming (dish satellites
        // collapsing, its AUTO fix lagging tens of metres).
        if (phone.agcDb.isNotEmpty()) {
            val interference = phone.agcDb.any { it < 0 }
            KeyValueRow(stringResource(if (interference) R.string.phone_agc_interference else R.string.phone_agc), stringResource(R.string.unit_db, phone.agcDb.joinToString(" ") { it.f() }), status = if (interference) Status.WARN else null)
        }
        phone.gnssModel?.let { KeyValueRow(stringResource(R.string.phone_chipset), it) }
        val r = phone.rates
        KeyValueRow(stringResource(R.string.phone_rates_gps), stringResource(R.string.unit_hz, "${rate(r, "gps_fix")} / ${rate(r, "gps_fix_mock")} / ${rate(r, "network_fix")}"))
        KeyValueRow(stringResource(R.string.phone_rates_gnss), stringResource(R.string.unit_hz, "${rate(r, "gnss_status")} / ${rate(r, "gnss_meas")} / ${rate(r, "nmea")} / ${rate(r, "nav_msg")}"))
    }
}

@Composable
private fun SensorsCard(phone: PhoneUi) {
    Section(stringResource(R.string.phone_sensors_title)) {
        val sensors = phone.rates.filterKeys { it.startsWith("sensor_") }
        if (sensors.isEmpty()) StateView(UiState.Empty(stringResource(R.string.phone_sensors_not_recording)))
        for ((key, hz) in sensors) {
            KeyValueRow(PhoneSensors.label(key.removePrefix("sensor_").toInt()), stringResource(R.string.unit_hz, hz.f(0)))
        }
    }
}

private fun rate(r: Map<String, Double>, key: String) = (r[key] ?: 0.0).f(1)

