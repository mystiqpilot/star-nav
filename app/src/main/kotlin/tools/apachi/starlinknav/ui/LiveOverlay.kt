package tools.apachi.starlinknav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.service.InertialUi
import tools.apachi.starlinknav.service.TrailPoint
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The drawing and legends of "every position source", shown on the Position page behind its "All sources" switch (they
 * were the Live map page). The map itself, its framing and following are the Position page's.
 */

/**
 * One position source on the live map. Its colour comes from the theme by [key]
 * ([StarColors.sources], the validated categorical palette); more than three cannot all be
 * told apart by colour, so dish sources are squares, the phone's are circles, and every mark
 * carries its name.
 */
internal class Source(val key: String, val name: Int, val short: Int, val dish: Boolean)

internal val SOURCES = listOf(
    Source("dish_auto", R.string.live_src_dish_auto, R.string.live_short_dish_auto, dish = true),
    Source("dish_starlink", R.string.live_src_dish_starlink, R.string.live_short_dish_starlink, dish = true),
    Source("dish_gps", R.string.live_src_dish_gps, R.string.live_short_dish_gps, dish = true),
    Source("phone_gps", R.string.live_src_phone_gps, R.string.live_short_phone_gps, dish = false),
    Source("phone_network", R.string.live_src_phone_network, R.string.live_short_phone_network, dish = false),
    Source("phone_fused", R.string.live_src_phone_fused, R.string.live_short_phone_fused, dish = false),
    Source("gms_fused", R.string.live_src_gms_fused, R.string.live_short_gms_fused, dish = false),
)

internal const val SERVED = "served"
/** Older than this, a source is drawn faded and its age is shown. */
internal const val STALE_NS = 15_000_000_000L
/** The first view frames the sources this close to the main one. */
internal const val FIT_WITHIN_M = 20_000.0
/** How much of each track is drawn behind its position. */
internal const val TAIL_NS = 120_000_000_000L

internal fun distanceText(m: Double, unitM: String, unitKm: String): String =
    if (m >= 1000) unitKm.format(String.format(Locale.ROOT, if (m >= 10_000) "%.0f" else "%.1f", m / 1000))
    else unitM.format(String.format(Locale.ROOT, "%.0f", m))

internal fun DrawScope.drawLive(
    view: GeoView, trails: Map<String, List<TrailPoint>>, now: Long, dishHeading: Double?,
    names: Map<String, String>, unitM: String, unitKm: String, tm: TextMeasurer, paint: MapPaint,
) {
    val casing = paint.casing
    val mpp = view.metresPerPx()
    fun o(p: TrailPoint) = view.toScreen(p.lat, p.lon)
    fun fresh(p: TrailPoint) = now - p.tNs < STALE_NS
    val served = trails[SERVED]?.lastOrNull()?.takeIf(::fresh)
    val keys = SOURCES.map { it.key }.filter { trails[it]?.isNotEmpty() == true }

    // Tails, then accuracy circles, under everything else.
    for ((key, color) in keys.map { k -> k to paint.source(k) } + listOf(SERVED to paint.ink)) {
        val pts = trails[key]?.filter { now - it.tNs < TAIL_NS } ?: continue
        if (pts.size < 2) continue
        val path = Path().apply {
            moveTo(o(pts[0]).x, o(pts[0]).y)
            for (p in pts.drop(1)) o(p).let { lineTo(it.x, it.y) }
        }
        if (casing) drawPath(path, Casing, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, color.copy(alpha = paint.trailAlpha), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
    for (key in keys + SERVED) {
        val p = trails[key]?.lastOrNull() ?: continue
        val acc = p.accM ?: continue
        val r = acc / mpp
        if (r < 4f || r > 3 * size.maxDimension) continue
        val color = if (key == SERVED) paint.ink else paint.source(key)
        val alpha = if (fresh(p)) 1f else 0.4f
        drawCircle(color.copy(alpha = 0.10f * alpha), r, o(p))
        drawCircle(color.copy(alpha = paint.ringAlpha * alpha), r, o(p), style = Stroke(1.dp.toPx()))
    }
    // Distances from what apps get to every other source.
    if (served != null) {
        val s = o(served)
        for (key in keys) {
            val p = trails[key]?.lastOrNull()?.takeIf(::fresh) ?: continue
            val d = Geo.distanceM(served.lat, served.lon, p.lat, p.lon)
            val e = o(p)
            val len = hypot(e.x - s.x, e.y - s.y)
            if (len < 24.dp.toPx()) continue
            drawLine(paint.ink.copy(alpha = 0.55f), s, e, 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
            // On a short line the label would sit under the two marks (the legend has the distance).
            if (len >= 56.dp.toPx()) {
                label(tm, distanceText(d, unitM, unitKm), Offset((s.x + e.x) / 2, (s.y + e.y) / 2), paint.ink, 11.sp, center = true, halo = casing)
            }
        }
    }
    // Course arrows (length = 10 s of travel) and the dish's own heading.
    fun arrow(from: Offset, deg: Double, len: Float, color: Color, dashed: Boolean) {
        val rad = Math.toRadians(deg)
        val tip = Offset(from.x + (sin(rad) * len).toFloat(), from.y - (cos(rad) * len).toFloat())
        val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
        if (casing) drawLine(Casing, from, tip, 4.5.dp.toPx(), StrokeCap.Round)
        drawLine(color, from, tip, 2.dp.toPx(), StrokeCap.Round, pathEffect = effect)
        for (side in listOf(-1, 1)) {
            val back = rad + Math.PI + side * 0.45
            val wing = Offset(tip.x + (sin(back) * 9.dp.toPx()).toFloat(), tip.y - (cos(back) * 9.dp.toPx()).toFloat())
            drawLine(color, tip, wing, 2.dp.toPx(), StrokeCap.Round)
        }
    }
    fun travel(speed: Float?) = ((speed ?: 0f) * 10f / mpp).coerceIn(28.dp.toPx(), 110.dp.toPx())
    served?.let { p -> if ((p.speedMps ?: 0f) > 1f) p.bearingDeg?.let { arrow(o(p), it.toDouble(), travel(p.speedMps), paint.ink, dashed = false) } }
    trails["phone_gps"]?.lastOrNull()?.takeIf(::fresh)?.let { p ->
        if ((p.speedMps ?: 0f) > 1f) p.bearingDeg?.let { arrow(o(p), it.toDouble(), travel(p.speedMps), paint.source("phone_gps"), dashed = false) }
    }
    val dishAt = trails["dish_auto"]?.lastOrNull()?.takeIf(::fresh) ?: served
    if (dishHeading != null && dishAt != null) arrow(o(dishAt), dishHeading, 44.dp.toPx(), paint.source("dish_auto"), dashed = true)

    // Marks with their names: dish sources square, phone sources round, what apps get a white ring.
    // Names of sources that agree to a few metres would overprint: each takes the next free line.
    val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
    val lineH = 14.dp.toPx()
    fun nameAt(text: String, at: Offset, bold: Boolean = false) {
        val w = tm.measure(text, androidx.compose.ui.text.TextStyle(fontSize = 11.sp)).size.width.toFloat()
        var y = at.y
        repeat(6) {
            val r = androidx.compose.ui.geometry.Rect(at.x, y, at.x + w, y + lineH)
            if (placed.none { it.overlaps(r) }) {
                placed += r
                label(tm, text, Offset(at.x, y), paint.ink, 11.sp, bold = bold, halo = casing)
                return
            }
            y += lineH
        }
    }
    for (key in keys) {
        val p = trails[key]?.lastOrNull() ?: continue
        val src = SOURCES.first { it.key == key }
        val c = o(p)
        val base = paint.source(key)
        val color = if (fresh(p)) base else base.copy(alpha = 0.4f)
        val half = 5.dp.toPx()
        if (src.dish) {
            if (casing) drawRect(Casing, Offset(c.x - half - 1.5f, c.y - half - 1.5f), Size(2 * half + 3f, 2 * half + 3f))
            drawRect(color, Offset(c.x - half, c.y - half), Size(2 * half, 2 * half))
        } else {
            if (casing) drawCircle(Casing, half + 1.5f, c)
            drawCircle(color, half, c)
        }
        nameAt(names.getValue(key), Offset(c.x + 8.dp.toPx(), c.y - 18.dp.toPx()))
    }
    served?.let { p ->
        val c = o(p)
        if (casing) drawCircle(Casing, 9.dp.toPx(), c)
        drawCircle(paint.ink, 7.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        nameAt(names.getValue(SERVED), Offset(c.x + 10.dp.toPx(), c.y + 4.dp.toPx()), bold = true)
    }
    scaleBar(tm, mpp, paint)
}

/** One row per source: its mark on the map, name, accuracy, speed, course, age and how far it is from what apps get. */
@Composable
internal fun LiveLegend(latest: Map<String, TrailPoint>, now: Long, paint: MapPaint) {
    val served = latest[SERVED]?.takeIf { now - it.tNs < STALE_NS }
    val unitM = stringResource(R.string.live_unit_m)
    val unitKm = stringResource(R.string.live_unit_km)
    val rows = listOf<Triple<String, Color?, Boolean>>(Triple(SERVED, null, false)) + SOURCES.map { Triple(it.key, paint.source(it.key), it.dish) }
    for ((key, color, isDish) in rows) {
        val p = latest[key] ?: continue
        val name = if (key == SERVED) stringResource(R.string.live_src_served) else stringResource(SOURCES.first { it.key == key }.name)
        val parts = buildList {
            p.accM?.let { add("±" + distanceText(it.toDouble(), unitM, unitKm)) }
            p.speedMps?.let { add(stringResource(R.string.live_speed, (it * 3.6).f(0))) }
            p.bearingDeg?.let { add("${it.toDouble().f(0)}°") }
            add(stringResource(R.string.live_age, ((now - p.tNs) / 1e9).coerceAtLeast(0.0).f(1)))
            if (served != null && key != SERVED) {
                add(stringResource(R.string.live_delta, distanceText(Geo.distanceM(served.lat, served.lon, p.lat, p.lon), unitM, unitKm)))
            }
        }
        LegendItem(name, detail = parts.joinToString(" · "), labelColor = if (now - p.tNs < STALE_NS) Color.Unspecified else StarTheme.colors.muted) {
            Canvas(Modifier.fillMaxSize()) {
                when {
                    // As the map draws what apps get: a ring in the map's ink, on the casing over tiles.
                    color == null -> {
                        val ring = size.minDimension / 2.6f
                        if (paint.casing) drawCircle(Casing, ring + 1.5.dp.toPx())
                        drawCircle(paint.ink, ring, style = Stroke(2.5.dp.toPx()))
                    }
                    isDish -> drawRect(color)
                    else -> drawCircle(color)
                }
            }
        }
    }
}

/** The dish's heading (a row of a [KeyValueTable]): corrected by the mount offset when there is one. */
@Composable
internal fun LiveHeadingRow(dishHeading: Double, offsetDeg: Double?) {
    KeyValueRow(
        stringResource(R.string.live_dish_heading),
        offsetDeg?.let { o -> stringResource(R.string.live_dish_heading_value, dishHeading.f(0), o.f(0)) }
            ?: stringResource(R.string.live_dish_heading_no_mount, dishHeading.f(0)),
    )
}

/** What the inertial filter serves (the ring "apps" on the map): its state, accuracy, sideways offset and IMU. One table row. */
@Composable
internal fun InertialLegend(i: InertialUi) {
    val parts = buildList {
        add(stringResource(inertialStateLabel(i.state)))
        i.accuracyM?.let { add("±" + stringResource(R.string.live_unit_m, it.f(0))) }
        i.sidewaysM?.takeIf { kotlin.math.abs(it) >= 1.0 }?.let {
            add(stringResource(if (it >= 0) R.string.inertial_sideways_right else R.string.inertial_sideways_left, kotlin.math.abs(it).f(0)))
        }
        add(stringResource(R.string.live_inertial_imu, stringResource(imuStateLabel(i.imu))))
    }
    KeyValueRow(
        stringResource(R.string.live_inertial),
        parts.joinToString(" · "),
        status = when (i.state) {
            tools.apachi.starlinknav.core.nav.InertialState.TRACKING -> null
            tools.apachi.starlinknav.core.nav.InertialState.NO_OUTPUT -> Status.BAD
            else -> Status.WARN
        },
    )
}

internal fun inertialShortLabel(s: tools.apachi.starlinknav.core.nav.InertialState) = when (s) {
    tools.apachi.starlinknav.core.nav.InertialState.TRACKING -> R.string.live_inertial_short_tracking
    tools.apachi.starlinknav.core.nav.InertialState.SIDEWAYS_OPEN -> R.string.live_inertial_short_sideways
    tools.apachi.starlinknav.core.nav.InertialState.DEAD_RECKONING -> R.string.live_inertial_short_dr
    tools.apachi.starlinknav.core.nav.InertialState.NETWORK_AIDED -> R.string.live_inertial_short_network
    tools.apachi.starlinknav.core.nav.InertialState.NO_OUTPUT -> R.string.live_inertial_short_none
}

/** Satellites and jamming on both receivers, and whether the dish position is cut off: rows of a [KeyValueTable]. */
@Composable
internal fun Health(status: tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse?, phone: tools.apachi.starlinknav.service.PhoneUi, cutoffS: Double?) {
    status?.gps_stats?.let { g ->
        KeyValueRow(
            stringResource(R.string.live_dish_gnss),
            pluralStringResource(if (g.gps_valid) R.plurals.dish_gps_valid else R.plurals.dish_gps_invalid, g.gps_sats, g.gps_sats),
            status = if (g.gps_valid) Status.OK else Status.BAD,
        )
    }
    val agc = phone.agcDb.minOrNull()
    val jammed = agc != null && agc < 0.0
    KeyValueRow(
        stringResource(R.string.live_phone_gnss),
        listOfNotNull(
            stringResource(R.string.live_phone_sats, phone.satsUsed.toString(), phone.satsVisible.toString()),
            phone.meanCn0Used?.let { "C/N0 ${it.f(0)} dB-Hz" },
            agc?.let { stringResource(if (jammed) R.string.live_agc_jammed else R.string.live_agc, it.f(1)) },
        ).joinToString(" · "),
        status = if (jammed) Status.WARN else null,
    )
    cutoffS?.let { KeyValueRow(stringResource(R.string.live_cutoff), stringResource(R.string.live_cutoff_value, it.f(0)), status = Status.BAD) }
}
