package tools.apachi.starlinknav.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.nav.DishGnssRegime
import tools.apachi.starlinknav.core.nav.InertialState
import tools.apachi.starlinknav.core.nav.LocationRefusal
import tools.apachi.starlinknav.service.CutoffUi
import tools.apachi.starlinknav.service.InertialLine
import tools.apachi.starlinknav.service.InertialUi
import tools.apachi.starlinknav.service.inertialLine
import tools.apachi.starlinknav.service.Live
import tools.apachi.starlinknav.service.inertialServesApps
import tools.apachi.starlinknav.service.MockUi
import tools.apachi.starlinknav.service.NavService

/**
 * The mock GPS card: the switch and one line about what apps get right now (with the delivery check under it unless
 * [details] is off). Reads the service's live state itself, so any page can show it.
 */
@Composable
fun MockHeroCard(details: Boolean = true) {
    val ctx = LocalContext.current
    val mock by Live.mock.collectAsStateWithLifecycle()
    val cutoff by Live.cutoff.collectAsStateWithLifecycle()
    val dishLive by Live.dish.collectAsStateWithLifecycle()
    val inertial by Live.inertial.collectAsStateWithLifecycle()
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    val now = rememberMonoNow()
    val hasLocation = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    // A transport failure on the last status: the dish (or its Wi-Fi) is gone, e.g. restarting.
    val dishDown = dishLive.last[DishCallKind.STATUS]?.result?.httpStatus == 0
    // Auto with the correction on: apps get the filter's position (its state is the hero line).
    val inertialServing = inertialServesApps(inertial, mock, settings)
    MockHero(mock, cutoff, dishDown, now, enabled = hasLocation, inertial = inertial.takeIf { inertialServing }, details = details, refusal = dishLive.refusal)
}

/**
 * The one-line version for the Position page: the switch beside what apps get right now. Reads the service's live state
 * itself, like [MockHeroCard].
 */
@Composable
fun MockStatusRow() {
    val ctx = LocalContext.current
    val mock by Live.mock.collectAsStateWithLifecycle()
    val cutoff by Live.cutoff.collectAsStateWithLifecycle()
    val dishLive by Live.dish.collectAsStateWithLifecycle()
    val inertial by Live.inertial.collectAsStateWithLifecycle()
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    val now = rememberMonoNow()
    val hasLocation = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val dishDown = dishLive.last[DishCallKind.STATUS]?.result?.httpStatus == 0
    val inertialServing = inertialServesApps(inertial, mock, settings)
    val (line, status) = mockStatusLine(mock, cutoff, dishDown, now, inertial.takeIf { inertialServing }, dishLive.refusal)
    Section(stringResource(R.string.mock_switch_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                StatusLine(status, line)
                mock.error?.let { StateView(UiState.Error(Status.BAD, it)) }
                if (!hasLocation) Hint(stringResource(R.string.home_needs_location))
            }
            Switch(
                checked = mock.enabled,
                enabled = hasLocation,
                onCheckedChange = { NavService.mock(ctx, it) },
                modifier = Modifier.padding(start = Space.m),
            )
        }
    }
}

@Composable
private fun MockHero(mock: MockUi, cutoff: CutoffUi, dishDown: Boolean, now: Long, enabled: Boolean, inertial: InertialUi?, details: Boolean, refusal: LocationRefusal? = null) {
    val ctx = LocalContext.current
    Section(stringResource(R.string.mock_switch_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_mock_subtitle), style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
                checked = mock.enabled,
                enabled = enabled,
                onCheckedChange = { NavService.mock(ctx, it) },
                modifier = Modifier.scale(1.25f).padding(start = Space.m, end = Space.s),
            )
        }
        val (line, status) = mockStatusLine(mock, cutoff, dishDown, now, inertial, refusal)
        StatusLine(status, line)
        // `details` off where the page shows them in a card of their own (Mock GPS).
        if (mock.enabled && details) {
            KeyValueTable {
                DeliveryRow(mock.delivery, now)
                mock.googleFused?.let {
                    KeyValueRow(stringResource(R.string.mock_google_fused), it, status = if (it == stringResource(R.string.mock_google_fused_on)) Status.OK else Status.WARN)
                }
            }
        }
        mock.error?.let { StateView(UiState.Error(Status.BAD, it)) }
        if (!enabled) Hint(stringResource(R.string.home_needs_location))
    }
}

/**
 * One line about what apps get right now, with its [Status]: the inertial filter's line while it serves ([inertial] set),
 * else the plain one (off, waiting, cut off, fallback, no fix, serving).
 */
@Composable
fun mockStatusLine(mock: MockUi, cutoff: CutoffUi, dishDown: Boolean, now: Long, inertial: InertialUi?, refusal: LocationRefusal? = null): Pair<String, Status> =
    inertial?.let { inertialHeroLine(mock, it, cutoff, now, refusal) } ?: heroLine(mock, cutoff, dishDown, now, refusal)

@Composable
private fun heroLine(mock: MockUi, cutoff: CutoffUi, dishDown: Boolean, now: Long, refusal: LocationRefusal?): Pair<String, Status> {
    if (!mock.enabled) return stringResource(R.string.home_line_off) to Status.NEUTRAL
    val active = cutoff.active
    if (active != null) {
        return stringResource(R.string.home_line_cutoff, disablementReason(active.code), active.durationS(now).f(0)) to Status.BAD
    }
    // get_location refused and nothing standing in for it (no fresh push): the reason, not "waiting" or a bare age
    if (refusal != null && (mock.lastPushMonoNs == 0L || (now - mock.lastPushMonoNs) / 1e9 > 5)) {
        return stringResource(R.string.home_line_refused, stringResource(refusalWordsRes(refusal))) to Status.BAD
    }
    if (mock.fallbackSinceNs != 0L) {
        val silentS = ((now - (mock.lastPushMonoNs.takeIf { it != 0L } ?: mock.fallbackSinceNs)) / 1e9).coerceAtLeast(0.0).f(0)
        return when {
            mock.fallbackLastNs != 0L -> stringResource(R.string.home_line_fallback, (mock.fallbackAccuracyM ?: 0f).f(0), silentS) to Status.WARN
            mock.networkEnabled == false -> stringResource(R.string.home_line_fallback_off, silentS) to Status.BAD
            else -> stringResource(R.string.home_line_fallback_waiting, silentS) to Status.WARN
        }
    }
    if (mock.lastPushMonoNs == 0L) return stringResource(R.string.home_line_waiting) to Status.WARN
    // The screen's clock ticks every half second, so a fresh push can look slightly in the future.
    val age = ((now - mock.lastPushMonoNs) / 1e9).coerceAtLeast(0.0)
    if (age > 5) return stringResource(if (dishDown) R.string.home_line_dish_down else R.string.home_line_no_fix, age.f(0)) to Status.BAD
    mock.frozenS?.let { return stringResource(R.string.home_line_frozen, it.f(0)) to Status.WARN }
    return stringResource(R.string.home_line_serving, stringResource(NavService.modeLabel(mock.mode)), age.f(1), (mock.accuracyM ?: 0f).f(0)) to Status.OK
}

/**
 * Auto with the inertial correction: what the filter serves. Null until something was pushed (the plain lines say
 * "waiting"), and when a network fix went to apps as it came (the plain fallback lines).
 */
@Composable
private fun inertialHeroLine(mock: MockUi, i: InertialUi, cutoff: CutoffUi, now: Long, refusal: LocationRefusal?): Pair<String, Status>? {
    if (mock.lastPushMonoNs == 0L) return null
    val acc = i.accuracyM?.f(0) ?: "-"
    val sinceFix = (if (i.lastFixNs != 0L) ((now - i.lastFixNs) / 1e9).coerceAtLeast(0.0) else 0.0).f(0)
    val active = cutoff.active
    // While the filter follows the dish: what the dish's position is made from, when that is not its own GNSS.
    val tag = regimeTagRes(i.dishRegime, short = false)?.let { stringResource(it) }
    val followed = if (tag != null) Status.WARN else Status.OK
    // Why the dish gives no position, when get_location is refused (a cut-off has its own lines).
    val why = refusal?.let { stringResource(refusalWordsRes(it)) }
    return when (inertialLine(i, mock, now)) {
        InertialLine.PLAIN -> null
        InertialLine.STARTING -> (mock.accuracyM ?: 0f).f(0).let { served ->
            if (tag == null) stringResource(R.string.home_line_inertial_starting, served) else stringResource(R.string.home_line_inertial_starting_tag, tag, served)
        } to followed
        InertialLine.NO_MOUNT ->
            (if (tag == null) stringResource(R.string.home_line_inertial_no_mount, acc) else stringResource(R.string.home_line_inertial_no_mount_tag, tag, acc)) to followed
        InertialLine.TRACKING ->
            (if (tag == null) stringResource(R.string.home_line_inertial_tracking, acc) else stringResource(R.string.home_line_inertial_tracking_tag, tag, acc)) to followed
        InertialLine.SIDEWAYS -> kotlin.math.abs(i.sidewaysM ?: 0.0).f(0).let { m ->
            if (tag == null) stringResource(R.string.home_line_inertial_sideways, m, acc) else stringResource(R.string.home_line_inertial_sideways_tag, m, tag, acc)
        } to Status.WARN
        InertialLine.DEAD_RECKONING, InertialLine.NETWORK -> when {
            active != null -> stringResource(R.string.home_line_cutoff_inertial, disablementReason(active.code), sinceFix, acc)
            i.state == InertialState.NETWORK_AIDED -> stringResource(R.string.home_line_inertial_network, sinceFix, acc)
            why != null -> stringResource(R.string.home_line_inertial_dr_refused, sinceFix, why, acc)
            else -> stringResource(R.string.home_line_inertial_dr, sinceFix, acc)
        } to Status.WARN
        InertialLine.STOPPED -> when {
            active != null -> stringResource(R.string.home_line_cutoff_inertial_stopped, disablementReason(active.code), active.durationS(now).f(0))
            why != null -> stringResource(R.string.home_line_inertial_stopped_refused, sinceFix, why)
            else -> stringResource(R.string.home_line_inertial_stopped, sinceFix)
        } to Status.BAD
    }
}

/**
 * The words for the dish's GNSS regime in a status line ([short] for the notification), null while the dish's own GNSS
 * is trusted or its state is not known: Starlink-only (no dish GNSS, ≈ 85 m), or the phone's own GPS distrusted.
 */
@StringRes
fun regimeTagRes(r: DishGnssRegime, short: Boolean): Int? = when (r) {
    DishGnssRegime.STARLINK_ONLY -> if (short) R.string.regime_tag_starlink_only_short else R.string.regime_tag_starlink_only
    DishGnssRegime.SUSPECT -> R.string.regime_tag_suspect
    DishGnssRegime.TRUSTED, DishGnssRegime.UNKNOWN -> null
}
