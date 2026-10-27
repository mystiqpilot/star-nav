package tools.apachi.starlinknav.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.nav.DishPositionState
import tools.apachi.starlinknav.core.nav.DistrustReason
import tools.apachi.starlinknav.core.nav.GpsTrustState
import tools.apachi.starlinknav.core.nav.InertialState
import tools.apachi.starlinknav.core.nav.PositionFormat
import tools.apachi.starlinknav.core.nav.UnknownWhy
import tools.apachi.starlinknav.core.nav.dishPositionState
import tools.apachi.starlinknav.core.nav.isRefusal
import tools.apachi.starlinknav.service.CutoffUi
import tools.apachi.starlinknav.service.DishUi
import tools.apachi.starlinknav.service.InertialUi
import tools.apachi.starlinknav.service.PhoneUi
import tools.apachi.starlinknav.service.RebootUi
import tools.apachi.starlinknav.service.TrustUi

/** A distance in metres or kilometres, in the app's language. */
@Composable
internal fun distanceLabel(m: Double): String = distanceText(m, stringResource(R.string.live_unit_m), stringResource(R.string.live_unit_km))

/** "±7 m" */
@Composable
internal fun accuracyLabel(m: Double): String = "±" + stringResource(R.string.live_unit_m, m.f(0))

/** Latitude and longitude to six decimals in one line; a tap copies the same text. */
@Composable
internal fun CoordinatesLine(lat: Double, lon: Double, dim: Boolean = false) {
    val ctx = LocalContext.current
    val text = PositionFormat.latLon(lat, lon)
    val label = stringResource(R.string.pos_copy_label)
    Box(Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(onClickLabel = stringResource(R.string.pos_copy_action)) { copyToClipboard(ctx, label, text) }, contentAlignment = Alignment.CenterStart) {
        CoordinateText(text, color = if (dim) StarTheme.colors.muted else Color.Unspecified)
    }
}

/** The position text goes to the clipboard on the user's tap (Android shows its own "copied" confirmation). */
internal fun copyToClipboard(ctx: Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * The dish's own position (AUTO) and how it is doing, in plain words: OK, cut off with the reason, no fix, the dish does
 * not answer, restarting, or corrected by the inertial filter while it serves.
 */
@Composable
internal fun StarlinkCard(
    trust: TrustUi,
    dish: DishUi,
    cutoff: CutoffUi,
    reboot: RebootUi,
    inertial: InertialUi,
    inertialServing: Boolean,
    serviceRunning: Boolean,
    headingDeg: Double?,
    mountKnown: Boolean,
    now: Long,
) {
    val colors = StarTheme.colors
    val statusCall = dish.last[DishCallKind.STATUS]
    // The last status failed in transport, or none came for a few seconds: the dish (or its Wi-Fi) is gone.
    val unreachable = statusCall != null && (statusCall.result.httpStatus == 0 || (now - statusCall.atMonoNs) / 1e9 > UNREACHABLE_AFTER_S)
    val wallS = System.currentTimeMillis() / 1000
    val restarting = (reboot.commandedNs != 0L && (now - reboot.commandedNs) / 1e9 < RESTART_WINDOW_S) ||
        (reboot.updateWaiting && reboot.updateRestartAtS?.let { wallS - it in -30..RESTART_WINDOW_S.toLong() } == true)
    val auto = trust.auto
    val fixAgeS = auto?.let { ((now - it.atNs) / 1e9).coerceAtLeast(0.0) }
    val state = dishPositionState(unreachable, restarting, cutoff.active != null, dish.refusal, fixAgeS)
    // get_location refused, but the dish's diagnostics give the position (the fallback chain): apps and this page get that one.
    val viaDiagnostics = state.isRefusal && fixAgeS != null && fixAgeS <= DIAGNOSTICS_FRESH_S
    val waiting = !serviceRunning || (statusCall == null && auto == null)

    Section(stringResource(R.string.pos_starlink_title)) {
        val (text, status) = when {
            waiting -> stringResource(R.string.pos_dish_waiting) to Status.NEUTRAL
            else -> when (state) {
                DishPositionState.OK ->
                    if (inertialServing && inertial.state == InertialState.SIDEWAYS_OPEN && inertial.sidewaysM != null) {
                        stringResource(R.string.pos_dish_corrected, kotlin.math.abs(inertial.sidewaysM).f(0)) to Status.WARN
                    } else {
                        stringResource(R.string.pos_dish_ok) to Status.OK
                    }
                DishPositionState.CUT_OFF -> {
                    val active = cutoff.active
                    stringResource(R.string.home_line_cutoff, active?.let { disablementReason(it.code) } ?: "-", (active?.durationS(now) ?: 0.0).f(0)) to Status.BAD
                }
                DishPositionState.POLICY_DENIED ->
                    stringResource(if (viaDiagnostics) R.string.pos_dish_policy_diag else R.string.pos_dish_policy) to (if (viaDiagnostics) Status.WARN else Status.BAD)
                DishPositionState.UNIMPLEMENTED ->
                    stringResource(if (viaDiagnostics) R.string.pos_dish_unimplemented_diag else R.string.pos_dish_unimplemented) to (if (viaDiagnostics) Status.WARN else Status.BAD)
                DishPositionState.NOT_ENABLED ->
                    stringResource(if (viaDiagnostics) R.string.pos_dish_not_enabled_diag else R.string.pos_dish_not_enabled) to (if (viaDiagnostics) Status.WARN else Status.BAD)
                DishPositionState.NO_FIX ->
                    (fixAgeS?.let { stringResource(R.string.pos_dish_no_fix, it.f(0)) } ?: stringResource(R.string.pos_dish_no_fix_yet)) to Status.WARN
                DishPositionState.UNREACHABLE -> stringResource(R.string.pos_dish_unreachable) to Status.BAD
                DishPositionState.RESTARTING -> stringResource(R.string.pos_dish_restarting) to Status.WARN
            }
        }
        // The dish's own words under a refusal (its gRPC message), as the Starlink page shows them for other refused calls.
        StatusLine(status, text, detail = if (!waiting && state.isRefusal) dish.refusalMessage?.takeIf { it.isNotBlank() } else null)
        if (!waiting && (state == DishPositionState.CUT_OFF || (state.isRefusal && !viaDiagnostics)) && inertialServing && inertial.state != InertialState.NO_OUTPUT) {
            Hint(stringResource(R.string.pos_dish_cutoff_continues))
        }
        val dim = state != DishPositionState.OK && !viaDiagnostics
        if (auto != null) CoordinatesLine(auto.lat, auto.lon, dim)
        val showStatus = !waiting && state != DishPositionState.UNREACHABLE && state != DishPositionState.RESTARTING
        val gps = if (showStatus) statusCall?.result?.response?.dish_get_status?.gps_stats else null
        val heading = if (showStatus) headingDeg else null
        if (auto != null || gps != null || heading != null) {
            KeyValueTable {
                if (auto != null) {
                    KeyValueRow(stringResource(R.string.pos_field_accuracy), accuracyLabel(auto.accuracyM))
                    KeyValueRow(stringResource(R.string.pos_field_age), stringResource(R.string.live_age, (fixAgeS ?: 0.0).f(1)), valueColor = if (dim) colors.muted else Color.Unspecified)
                    KeyValueRow(stringResource(R.string.pos_field_source), stringResource(if (viaDiagnostics) R.string.pos_src_dish_diagnostics else R.string.live_src_dish_auto))
                }
                gps?.let { g ->
                    KeyValueRow(
                        stringResource(R.string.pos_field_satellites),
                        pluralStringResource(if (g.gps_valid) R.plurals.dish_gps_valid else R.plurals.dish_gps_invalid, g.gps_sats, g.gps_sats),
                        status = if (g.gps_valid) Status.OK else Status.BAD,
                    )
                }
                heading?.let {
                    KeyValueRow(
                        stringResource(R.string.pos_field_heading),
                        if (mountKnown) "${it.f(0)}°" else stringResource(R.string.pos_heading_raw, it.f(0)),
                    )
                }
            }
        }
        reboot.last?.takeIf { now - it.backNs < RECENT_RESTART_NS }?.let { last ->
            Hint(stringResource(R.string.pos_dish_restarted, duration((now - last.backNs) / 1_000_000), rebootCause(last)))
        }
        restartSoon(reboot)?.let { at -> Hint(stringResource(R.string.home_update_soon, at)) }
    }
}

/** A dish restart stays on the main page this long. */
private const val RECENT_RESTART_NS = 30 * 60 * 1_000_000_000L

/** No status answer for this long: the dish is unreachable. */
private const val UNREACHABLE_AFTER_S = 5.0

/** Under a refusal of get_location a position this fresh is the diagnostics' standing in. */
private const val DIAGNOSTICS_FRESH_S = 5.0

/** A restart command, or the planned update restart, counts as the cause of a silent dish for this long. */
private const val RESTART_WINDOW_S = 600.0

/** The planned restart for a waiting update, when it is less than an hour away. */
@Composable
private fun restartSoon(ui: RebootUi): String? {
    val at = ui.updateRestartAtS?.takeIf { ui.updateWaiting } ?: return null
    val inS = at - System.currentTimeMillis() / 1000
    return if (inS in 0..3600) restartTime(LocalContext.current, at) else null
}

/**
 * The phone's own GPS: whether the automatic checks trust it (and why not), its position when it has a real fix, the
 * satellites it uses, their signal and the interference level.
 */
@Composable
internal fun GpsCard(trust: TrustUi, phone: PhoneUi, mockOn: Boolean, hasLocation: Boolean, now: Long) {
    val v = trust.verdict
    // With the app's own mock on, the receiver's fixes are not visible: apps (and this app's listener) get the mock's.
    val replaced = mockOn && (v.state == GpsTrustState.MOCKED || (v.state == GpsTrustState.UNKNOWN && v.unknown == UnknownWhy.NO_GPS_FIX))
    val realFix = !replaced && (v.state == GpsTrustState.TRUSTED || v.state == GpsTrustState.DISTRUSTED || v.unknown == UnknownWhy.NOTHING_TO_COMPARE)
    Section(stringResource(R.string.pos_gps_title)) {
        val (text, status) = when {
            replaced -> stringResource(R.string.pos_gps_replaced) to Status.NEUTRAL
            v.state == GpsTrustState.TRUSTED -> stringResource(R.string.pos_gps_trusted) to Status.OK
            v.state == GpsTrustState.DISTRUSTED -> stringResource(R.string.pos_gps_distrusted) to Status.BAD
            v.state == GpsTrustState.MOCKED -> stringResource(R.string.pos_gps_other_mock) to Status.WARN
            v.unknown == UnknownWhy.NOTHING_TO_COMPARE -> stringResource(R.string.pos_gps_not_checked) to Status.WARN
            !hasLocation -> stringResource(R.string.pos_gps_no_permission) to Status.BAD
            else -> stringResource(R.string.pos_gps_no_fix) to Status.NEUTRAL
        }
        // The reasons a GPS is distrusted explain the status line above them, one per line.
        val reasons = if (v.state == GpsTrustState.DISTRUSTED) v.reasons.map { reasonText(it) }.joinToString("\n").ifEmpty { null } else null
        StatusLine(status, text, detail = reasons)
        if (!hasLocation && !realFix) Hint(stringResource(R.string.home_needs_location))
        val gps = trust.gps?.takeIf { realFix }
        if (gps != null) CoordinatesLine(gps.lat, gps.lon, dim = v.state == GpsTrustState.DISTRUSTED)
        // Satellites, signal and AGC come from the chip's own status, which keeps flowing under a mock (about half the signals).
        val gnssFresh = phone.gnssAtMonoNs != 0L && (now - phone.gnssAtMonoNs) / 1e9 < GNSS_FRESH_S
        if (gps != null || gnssFresh) {
            KeyValueTable {
                if (gps != null) {
                    val ageS = ((now - gps.atNs) / 1e9).coerceAtLeast(0.0)
                    KeyValueRow(stringResource(R.string.pos_field_accuracy), accuracyLabel(gps.accuracyM))
                    KeyValueRow(stringResource(R.string.pos_field_age), stringResource(R.string.live_age, ageS.f(1)))
                    trust.comparison?.takeIf { (now - it.atNs) / 1e9 <= COMPARISON_FRESH_S }?.let { c ->
                        KeyValueRow(
                            stringResource(R.string.pos_gps_vs),
                            stringResource(R.string.pos_gps_vs_value, distanceLabel(c.distanceM), distanceLabel(c.limitM)),
                            status = if (c.distanceM > c.limitM) Status.BAD else Status.OK,
                        )
                    }
                }
                if (gnssFresh) {
                    KeyValueRow(stringResource(R.string.pos_gps_satellites), stringResource(R.string.live_phone_sats, phone.satsUsed.toString(), phone.satsVisible.toString()))
                    phone.meanCn0Used?.let { KeyValueRow(stringResource(R.string.pos_gps_signal), stringResource(R.string.pos_gps_signal_value, it.f(0))) }
                    val agc = phone.agcDb.minOrNull()
                    if (agc != null) {
                        val jammed = agc < 0.0
                        KeyValueRow(
                            stringResource(R.string.pos_gps_interference),
                            stringResource(if (jammed) R.string.live_agc_jammed else R.string.pos_gps_agc_ok, agc.f(1)),
                            status = if (jammed) Status.WARN else null,
                        )
                    }
                }
            }
        }
        if (replaced) Hint(stringResource(R.string.pos_gps_replaced_hint))
    }
}

/** Satellite counts and AGC older than this are not shown. */
private const val GNSS_FRESH_S = 5.0

/** A GPS-against-Starlink comparison older than this is not shown (it is made when both fixes are current). */
private const val COMPARISON_FRESH_S = 10.0

@Composable
private fun reasonText(r: DistrustReason): String = when (r) {
    is DistrustReason.FarFromStarlink ->
        stringResource(R.string.pos_gps_why_far, distanceLabel(r.distanceM), distanceLabel(r.limitM), ((r.lastNs - r.sinceNs) / 1e9).coerceAtLeast(0.0).f(0))
    is DistrustReason.ImpossibleJump ->
        stringResource(R.string.pos_gps_why_jump, distanceLabel(r.jumpM), r.dtS.f(1))
    is DistrustReason.FarFromStarlinkOnly ->
        stringResource(R.string.pos_gps_why_frozen, distanceLabel(r.limitM), distanceLabel(r.gpsM), distanceLabel(r.dishM))
}
