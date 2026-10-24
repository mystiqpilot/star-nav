package tools.apachi.starlinknav.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.core.dish.attitude
import tools.apachi.starlinknav.core.nav.ChosenPosition
import tools.apachi.starlinknav.core.nav.ChosenSource
import tools.apachi.starlinknav.core.nav.GpsTrustState
import tools.apachi.starlinknav.core.nav.PositionFormat
import tools.apachi.starlinknav.core.nav.TrustFix
import tools.apachi.starlinknav.core.nav.UnknownWhy
import tools.apachi.starlinknav.service.Live
import tools.apachi.starlinknav.service.NavService
import tools.apachi.starlinknav.service.TrailPoint
import tools.apachi.starlinknav.service.TrustUi
import tools.apachi.starlinknav.service.inertialServesApps
import tools.apachi.starlinknav.setup.CheckState
import tools.apachi.starlinknav.setup.SetupChecks

/**
 * The start page: where the car is. A map with the Starlink and GPS positions (and what apps get while the mock is on),
 * the mock GPS switch with its status, a card each for the dish and the phone's GPS, and two buttons: "Center" and
 * "Open in Google Maps", both on the position [tools.apachi.starlinknav.core.nav.GpsTrust.choose] picks (GPS while
 * nothing says it is spoofed, else Starlink). "All sources" shows what the old live map showed.
 *
 * The page keeps the location service in watch mode while it is shown, so positions update without the mock.
 */
@Composable
fun PositionScreen(onOpenMock: () -> Unit) {
    val ctx = LocalContext.current
    val hasLocation = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    WatchWhileVisible(hasLocation)

    val trust by Live.trust.collectAsStateWithLifecycle()
    val trails by Live.trails.collectAsStateWithLifecycle()
    val dish by Live.dish.collectAsStateWithLifecycle()
    val phone by Live.phone.collectAsStateWithLifecycle()
    val mock by Live.mock.collectAsStateWithLifecycle()
    val cutoff by Live.cutoff.collectAsStateWithLifecycle()
    val reboot by Live.reboot.collectAsStateWithLifecycle()
    val inertial by Live.inertial.collectAsStateWithLifecycle()
    val service by Live.service.collectAsStateWithLifecycle()
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    val setupPhone by SetupState.phone.collectAsStateWithLifecycle()
    val setupDish by SetupState.dish.collectAsStateWithLifecycle()
    val googleAccuracy by SetupState.googleAccuracy.collectAsStateWithLifecycle()
    val now = rememberMonoNow()
    val missing = SetupChecks.combine(ctx, setupPhone, setupDish, googleAccuracy).count { it.item.required && it.state == CheckState.NEEDED }
    val inertialServing = inertialServesApps(inertial, mock, settings)

    var allSources by rememberSaveable { mutableStateOf(false) }
    // The map keeps the chosen position in the middle until the user moves it; "Center" brings it back.
    var following by remember { mutableStateOf(true) }
    val chosen = trust.chosen

    val status = dish.last[DishCallKind.STATUS]?.result?.response?.dish_get_status
    // The car's heading from the dish attitude, corrected by the mount offset: the one the filter uses, else the one in Settings.
    val mountDeg = inertial.mountDeg?.takeIf { inertial.running } ?: settings.effectiveMountDeg
    val dishHeading = status?.ned2dish_quaternion?.attitude()?.headingDeg?.let { ((it - (mountDeg ?: 0.0)) % 360 + 360) % 360 }

    // The first positions frame the view; after that it follows or stays where the user put it. Only fixes near the
    // chosen one frame it: a spoofed fix on another continent must not zoom the world out.
    var fit by remember { mutableStateOf(emptyList<Pair<Double, Double>>()) }
    val servedPoint = if (mock.enabled) trails[SERVED]?.lastOrNull()?.takeIf { now - it.tNs < STALE_NS } else null
    val known = buildList {
        trust.auto?.let { add(it.lat to it.lon) }
        if (trust.verdict.state != GpsTrustState.MOCKED) trust.gps?.let { add(it.lat to it.lon) }
        servedPoint?.let { add(it.lat to it.lon) }
    }
    val anchor = chosen?.let { it.lat to it.lon } ?: known.firstOrNull()
    LaunchedEffect(anchor != null) {
        val a = anchor ?: return@LaunchedEffect
        if (fit.isEmpty()) fit = known.filter { Geo.distanceM(a.first, a.second, it.first, it.second) < FIT_WITHIN_M }
    }

    val layer = settings.mapLayer
    val paint = mapPaint(layer)
    val tm = rememberTextMeasurer()
    val unitM = stringResource(R.string.live_unit_m)
    val unitKm = stringResource(R.string.live_unit_km)
    val servedName = if (inertialServing) {
        stringResource(R.string.live_short_served_inertial, stringResource(inertialShortLabel(inertial.state)))
    } else {
        stringResource(R.string.live_short_served)
    }
    val liveNames = SOURCES.associate { it.key to stringResource(it.short) } + (SERVED to servedName)
    val marks = positionMarks(trust, mock.enabled, servedPoint, servedName, paint, now)

    val onCenter = { following = true }
    val map: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
            MapLayerBar(layer)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (fit.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StateView(UiState.Empty(stringResource(R.string.live_waiting))) }
                } else {
                    GeoMap(
                        layer, fit, Modifier.fillMaxSize(), interactive = true,
                        follow = if (following) anchor else null,
                        onUserMoved = { following = false },
                    ) { view ->
                        if (allSources) drawLive(view, trails, now, dishHeading, liveNames, unitM, unitKm, tm, paint)
                        else drawPosition(view, marks, tm, paint)
                    }
                }
                FilterChip(
                    selected = allSources,
                    onClick = { allSources = !allSources },
                    label = { Text(stringResource(R.string.pos_all_sources)) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(Space.s),
                )
            }
        }
    }
    val list: @Composable (Modifier, Boolean) -> Unit = { modifier, withActions ->
        // The screen padding is the page gutter; the cards are one gap apart.
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(Space.m)) {
            if (withActions) item { PositionActions(chosen, trust, mock.enabled, hasLocation, onCenter) }
            if (missing > 0) {
                item {
                    Section(pluralStringResource(R.plurals.home_setup_missing, missing, missing)) {
                        Hint(stringResource(R.string.home_setup_missing_hint))
                        ActionRow { PrimaryButton(stringResource(R.string.home_open_setup), onClick = onOpenMock) }
                    }
                }
            }
            item { MockStatusRow() }
            item {
                StarlinkCard(
                    trust, dish, cutoff, reboot, inertial, inertialServing, service.running, dishHeading,
                    mountKnown = mountDeg != null, now = now,
                )
            }
            item { GpsCard(trust, phone, mock.enabled, hasLocation, now) }
            if (allSources) {
                item {
                    Section(stringResource(R.string.pos_all_sources)) {
                        val latest = trails.mapValues { it.value.lastOrNull() }.filterValues { it != null }.mapValues { it.value!! }
                        LiveLegend(latest, now, paint)
                        KeyValueTable {
                            dishHeading?.let { LiveHeadingRow(it, mountDeg) }
                            if (inertialServing) InertialLegend(inertial)
                            Health(status, phone, cutoff.active?.durationS(now))
                        }
                        if (mock.enabled) Hint(stringResource(R.string.live_mock_hides))
                    }
                }
            }
            service.error?.let { e -> item { StateView(UiState.Error(Status.BAD, e)) } }
            item { Hint(stringResource(R.string.pos_copy_hint)) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(Space.m)) {
        if (maxWidth > maxHeight) {
            // Landscape: the map beside the cards.
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                map(Modifier.weight(1f).fillMaxHeight())
                list(Modifier.weight(1f).fillMaxHeight(), true)
            }
        } else {
            // Portrait: the map and the two buttons stay put, the cards scroll underneath.
            val mapHeight: Dp = (maxHeight * 0.4f).coerceIn(220.dp, 340.dp)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    map(Modifier.fillMaxWidth().height(mapHeight))
                    PositionActions(chosen, trust, mock.enabled, hasLocation, onCenter)
                }
                list(Modifier.weight(1f), false)
            }
        }
    }
}

/** The marks of the simple map: what apps get while the mock is on, the dish position and the phone's real GPS. */
@Composable
private fun positionMarks(
    trust: TrustUi,
    mockOn: Boolean,
    served: TrailPoint?,
    servedName: String,
    paint: MapPaint,
    now: Long,
): List<PositionMark> {
    val out = ArrayList<PositionMark>(3)
    trust.auto?.let {
        out += PositionMark(stringResource(R.string.pos_mark_starlink), it, PositionMark.Kind.DISH, paint.source("dish_auto"), stale = now - it.atNs > STALE_NS)
    }
    val v = trust.verdict
    // With the mock on the phone's GPS fixes are the mock's own: not a position of the receiver.
    if (v.state != GpsTrustState.MOCKED && !(mockOn && v.unknown == UnknownWhy.NO_GPS_FIX)) {
        trust.gps?.takeIf { now - it.atNs <= STALE_NS }?.let {
            val distrusted = v.state == GpsTrustState.DISTRUSTED
            out += PositionMark(
                stringResource(if (distrusted) R.string.pos_mark_gps_distrusted else R.string.pos_mark_gps), it, PositionMark.Kind.PHONE,
                paint.source("phone_gps"), stale = false, dashed = distrusted,
            )
        }
    }
    served?.let {
        out += PositionMark(servedName, TrustFix(it.lat, it.lon, it.accM?.toDouble() ?: 0.0, it.tNs), PositionMark.Kind.SERVED, paint.ink, stale = false)
    }
    return out
}

/**
 * "Center" and "Open in Google Maps" on the chosen position, and a line saying which one that is and why. Both are
 * disabled until there is a position. The maps button hands the position to the maps app on this tap only.
 */
@Composable
private fun PositionActions(chosen: ChosenPosition?, trust: TrustUi, mockOn: Boolean, hasLocation: Boolean, onCenter: () -> Unit) {
    val ctx = LocalContext.current
    val label = stringResource(if (chosen?.source == ChosenSource.GPS) R.string.pos_label_gps else R.string.pos_label_starlink)
    val noMaps = stringResource(R.string.pos_maps_none)
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        // Two equal halves: "Center" is the one primary action (it only moves the map); the maps hand-off is the secondary.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PrimaryButton(
                stringResource(
                    when (chosen?.source) {
                        ChosenSource.GPS -> R.string.pos_center_gps
                        ChosenSource.STARLINK, ChosenSource.SERVED -> R.string.pos_center_starlink
                        null -> R.string.pos_center
                    },
                ),
                onClick = onCenter,
                enabled = chosen != null,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
            )
            SecondaryButton(
                stringResource(R.string.pos_open_maps),
                onClick = { chosen?.let { openInMaps(ctx, it, label, noMaps) } },
                enabled = chosen != null,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
            )
        }
        val acc = chosen?.accuracyM?.f(0)
        Hint(
            when {
                // Nothing is offered when the only fix is one the app has judged spoofed (or a mock fix): say why.
                chosen == null -> stringResource(
                    when (trust.verdict.state) {
                        GpsTrustState.DISTRUSTED -> R.string.pos_using_none_distrusted
                        GpsTrustState.MOCKED -> R.string.pos_using_none_mocked
                        else -> R.string.pos_using_none
                    },
                )
                chosen.source == ChosenSource.SERVED -> stringResource(R.string.pos_using_served, acc!!)
                chosen.source == ChosenSource.GPS && chosen.unverified -> stringResource(R.string.pos_using_gps_unverified, acc!!)
                chosen.source == ChosenSource.GPS -> stringResource(R.string.pos_using_gps, acc!!)
                trust.verdict.state == GpsTrustState.DISTRUSTED -> stringResource(R.string.pos_using_starlink_distrusted, acc!!)
                // GPS held back: over the limit from the dish, not yet for the 5 s that make it distrusted.
                trust.verdict.state == GpsTrustState.TRUSTED -> stringResource(R.string.pos_using_starlink_pending, acc!!)
                trust.gps == null || trust.verdict.unknown == UnknownWhy.NO_GPS_FIX -> stringResource(R.string.pos_using_starlink_nogps, acc!!)
                else -> stringResource(R.string.pos_using_starlink, acc!!)
            },
        )
    }
}

/**
 * Shows the position in Google Maps when it is installed, else in whatever maps app the phone has (Android's own "open
 * with" list when there are several, none when there is one default). A `geo:` link with a labelled pin; the app itself
 * sends nothing anywhere. A plain VIEW intent rather than a chooser: `createChooser` never reports "no app", and a
 * phone with no maps app gets a toast instead of an empty list.
 */
private fun openInMaps(ctx: Context, p: ChosenPosition, label: String, none: String) {
    val uri = PositionFormat.geoUri(p.lat, p.lon, label).toUri()
    val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(Intent(view).setPackage(GOOGLE_MAPS))
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, none, Toast.LENGTH_SHORT).show()
        }
    }
}

private const val GOOGLE_MAPS = "com.google.android.apps.maps"

/**
 * The location service runs in watch mode while the page is on screen (dish and phone positions come in without the
 * mock) and goes back to what it was when the page goes. An Activity recreated for a configuration change (theme,
 * rotation, font scale) stops and starts again at once: its "off" is not sent, or it can reach the service just before
 * the new Activity's "on" and take the watch-only service down with it. Without the location permission the service
 * cannot start, so nothing is asked of it.
 */
@Composable
private fun WatchWhileVisible(allowed: Boolean) {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, allowed) {
        if (!allowed) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> NavService.watch(ctx, true)
                Lifecycle.Event.ON_STOP -> if (activity?.isChangingConfigurations != true) NavService.watch(ctx, false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (activity?.isChangingConfigurations != true) NavService.watch(ctx, false)
        }
    }
}
