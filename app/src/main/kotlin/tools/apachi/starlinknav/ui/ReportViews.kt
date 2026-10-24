package tools.apachi.starlinknav.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.apachi.starlinknav.MapLayer
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.log.TrackPoint
import tools.apachi.starlinknav.core.report.AccuracyClasses
import tools.apachi.starlinknav.core.report.ReportSpan
import tools.apachi.starlinknav.core.report.SessionReport
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Colours (accuracy ramp, lanes, chart chrome, map ink) are in Theme.kt, per theme.
private const val GAP_NS = 3_000_000_000L

private fun classColor(c: StarColors, err: Float?): Color = when {
    err == null -> c.noReference
    AccuracyClasses.isConflict(err.toDouble()) -> c.conflict
    else -> c.accuracy[AccuracyClasses.of(err.toDouble())]
}

private fun isError(err: Float?) = err != null && !AccuracyClasses.isConflict(err.toDouble())

/** Index of the dish point nearest to [t], or -1. */
private fun SessionReport.nearest(t: Long): Int {
    if (points.isEmpty()) return -1
    var lo = 0
    var hi = points.size - 1
    while (hi - lo > 1) {
        val m = (lo + hi) / 2
        if (points[m].t < t) lo = m else hi = m
    }
    return if (abs(points[lo].t - t) <= abs(points[hi].t - t)) lo else hi
}

/**
 * The dish track coloured by the error of the served position; cut-offs dashed red along the
 * phone's track (or the chord when the phone had no fixes), other gaps dotted. [from]..[to] is
 * the export window: outside it everything is faded.
 */
@Composable
fun AccuracyMap(
    r: SessionReport, from: Long, to: Long, cursor: Long?, layer: MapLayer,
    modifier: Modifier = Modifier, interactive: Boolean = false,
) {
    val pts = remember(r) { r.points.map { TrackPoint(it.t, it.lat, it.lon) } }
    val refs = remember(r) { r.reference.map { TrackPoint(it.t, it.lat, it.lon) } }
    val cutRefs = remember(r) { r.cutoffs.map { c -> refs.filter { it.t in c.startNs..c.endNs } } }
    val projection = remember(r) { Projection.of(pts.ifEmpty { refs }, pts + cutRefs.flatten()) }
    val fit = remember(r, projection) { projection?.let { pr -> (pts + cutRefs.flatten()).filter(pr::inView).map { it.lat to it.lon } }.orEmpty() }
    val tm = rememberTextMeasurer()
    // Resolved here: the draw lambda below is not composable.
    val cutLabels = r.cutoffs.map { c ->
        stringResource(R.string.report_map_cutoff, "%.0f".format(Locale.ROOT, c.durationS), kmh(c.speedBeforeMps ?: c.phoneSpeedStartMps))
    }
    // Resolved here: the draw lambda below is not composable.
    val colors = StarTheme.colors
    // Over tiles a dark casing keeps the blue ramp readable; over the plain map there is none.
    val paint = mapPaint(layer)
    Column(modifier) {
        if (projection == null) {
            Hint(stringResource(R.string.sessions_no_positions))
            return@Column
        }
        val mapModifier = if (interactive) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(1f)
        GeoMap(layer, fit, mapModifier, interactive) { view ->
            fun o(p: TrackPoint) = view.toScreen(p.lat, p.lon)
            fun fade(c: Color, t: Long) = if (t in from..to) c else c.copy(alpha = 0.25f)
            val width = 3.dp.toPx()

            for (i in 1 until pts.size) {
                if (pts[i].t - pts[i - 1].t <= GAP_NS) continue
                val seg = refs.filter { it.t in pts[i - 1].t..pts[i].t && projection.inView(it) }
                polyline(seg.map(::o), fade(colors.muted, pts[i].t), 2.dp.toPx(), floatArrayOf(2.dp.toPx(), 5.dp.toPx()))
            }
            for (pass in if (paint.casing) listOf(true, false) else listOf(false)) {
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]
                    val b = pts[i]
                    if (b.t - a.t > GAP_NS || !projection.inView(a) || !projection.inView(b)) continue
                    if (pass) {
                        drawLine(fade(Casing, b.t), o(a), o(b), width + 3.dp.toPx(), StrokeCap.Round)
                    } else {
                        drawLine(fade(classColor(colors, r.points[i].err), b.t), o(a), o(b), width, StrokeCap.Round)
                    }
                }
            }
            r.cutoffs.forEachIndexed { i, c ->
                val line = cutRefs[i].takeIf { it.size >= 2 }
                    ?: listOfNotNull(pts.lastOrNull { it.t < c.startNs }, pts.firstOrNull { it.t > c.endNs && it.t - c.endNs <= 10_000_000_000L })
                polyline(line.filter(projection::inView).map(::o), fade(colors.cutoff, c.startNs), width, floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                val at = (pts.lastOrNull { it.t <= c.startNs } ?: line.firstOrNull())?.takeIf(projection::inView) ?: return@forEachIndexed
                val m = o(at)
                drawCircle(colors.cutoff, 9.dp.toPx(), m)
                drawCircle(paint.inkInverse, 9.dp.toPx(), m, style = Stroke(2.dp.toPx()))
                label(tm, "!", m, OnCutoff, 13.sp, center = true, bold = true)
                val text = cutLabels[i]
                val right = m.x < size.width * 0.55f
                label(tm, text, Offset(m.x + if (right) 13.dp.toPx() else -13.dp.toPx(), m.y - 18.dp.toPx()), paint.ink, 12.sp, alignEnd = !right, halo = paint.casing)
            }
            // Export window edges.
            listOf(from to true, to to false).forEach { (t, filled) ->
                pts.minByOrNull { abs(it.t - t) }?.takeIf { projection.inView(it) }?.let { p ->
                    val c = o(p)
                    drawCircle(if (filled) paint.ink else paint.inkInverse, 6.dp.toPx(), c)
                    drawCircle(if (filled) paint.inkInverse else paint.ink, 6.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                }
            }
            cursor?.let { t ->
                val i = r.nearest(t)
                if (i >= 0 && abs(r.points[i].t - t) <= GAP_NS && projection.inView(pts[i])) {
                    // White on light tiles would vanish: the casing sets the ring apart.
                    if (paint.casing) drawCircle(Casing, 8.dp.toPx(), o(pts[i]), style = Stroke(5.dp.toPx()))
                    drawCircle(paint.ink, 8.dp.toPx(), o(pts[i]), style = Stroke(2.5.dp.toPx()))
                }
            }
            scaleBar(tm, view.metresPerPx(), paint)
        }
        if (projection.hidden > 0) {
            StatusPill(Status.WARN, pluralStringResource(R.plurals.sessions_positions_hidden, projection.hidden, projection.hidden))
        }
    }
}

internal fun DrawScope.polyline(pts: List<Offset>, color: Color, width: Float, dash: FloatArray? = null) {
    if (pts.size < 2) return
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (p in pts.drop(1)) lineTo(p.x, p.y)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, pathEffect = dash?.let { PathEffect.dashPathEffect(it) }))
}

internal fun DrawScope.label(
    tm: TextMeasurer, text: String, at: Offset, color: Color, size: androidx.compose.ui.unit.TextUnit,
    center: Boolean = false, alignEnd: Boolean = false, bold: Boolean = false, halo: Boolean = false,
) {
    // One layout, painted twice with the colour and draw style given to drawText: TextMeasurer's
    // cache ignores paint-only attributes, so two measured styles that differ in them would share
    // one layout anyway.
    val layout = tm.measure(text, TextStyle(fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    val x = when {
        center -> at.x - layout.size.width / 2f
        alignEnd -> at.x - layout.size.width
        else -> at.x
    }
    val y = if (center) at.y - layout.size.height / 2f else at.y
    // Over map tiles light text gets a thin dark outline: the soft shadow it had was too weak on
    // pale tiles (positron).
    if (halo) {
        drawText(layout, Halo.copy(alpha = 0.9f), Offset(x, y), drawStyle = Stroke(width = 1.3.dp.toPx(), join = StrokeJoin.Round))
    }
    // Fill is explicit: a drawText without a draw style keeps the one the shared paint had last.
    drawText(layout, color, Offset(x, y), drawStyle = Fill)
}

internal fun DrawScope.scaleBar(tm: TextMeasurer, metresPerPx: Float, paint: MapPaint) {
    val target = size.width / 5 * metresPerPx
    val bar = SCALE_STEPS_M.lastOrNull { it <= target } ?: SCALE_STEPS_M.first()
    val len = bar / metresPerPx
    val x0 = 12.dp.toPx()
    val y = size.height - 14.dp.toPx()
    // Over map tiles the bar is white with a dark outline, like the labels.
    val halo = paint.casing
    val ink = paint.scaleInk
    val lines = listOf(
        Offset(x0, y) to Offset(x0 + len, y),
        Offset(x0, y - 4.dp.toPx()) to Offset(x0, y + 4.dp.toPx()),
        Offset(x0 + len, y - 4.dp.toPx()) to Offset(x0 + len, y + 4.dp.toPx()),
    )
    if (halo) lines.forEach { (a, b) -> drawLine(Casing, a, b, 3.5.dp.toPx()) }
    lines.forEach { (a, b) -> drawLine(ink, a, b, 1.5.dp.toPx()) }
    val text = if (bar >= 1000) String.format(Locale.ROOT, paint.unitKm, (bar / 1000).toInt().toString()) else String.format(Locale.ROOT, paint.unitM, bar.toInt().toString())
    label(tm, text, Offset(x0 + len + 6.dp.toPx(), y - 8.dp.toPx()), ink, 11.sp, halo = halo)
}

private val SCALE_STEPS_M = listOf(1f, 2f, 5f, 10f, 20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10_000f, 20_000f, 50_000f, 100_000f, 200_000f, 500_000f)

/** The share of moving time per class as one bar, which doubles as the map's legend. */
@Composable
fun AccuracyLegend(r: SessionReport) {
    val s = r.summary
    val total = max(s.movingS, 1e-9)
    val classLabels = accuracyClassLabels()
    val colors = StarTheme.colors
    val items = classLabels.indices.map { ClassShare(classLabels[it], colors.accuracy[it], s.classS[it]) } +
        ClassShare(stringResource(R.string.report_legend_no_reference), colors.noReference, s.noReferenceS, optional = true) +
        ClassShare(stringResource(R.string.report_legend_conflict), colors.conflict, s.conflictS, optional = true) +
        ClassShare(stringResource(R.string.report_cutoff), colors.cutoff, s.cutoffS)
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            var x = 0f
            val gap = 2.dp.toPx()
            for ((_, color, v) in items) {
                if (v <= 0) continue
                val w = (v / total * size.width).toFloat()
                drawRoundRect(color, Offset(x, 0f), Size(max(w - gap, 2f), size.height), CornerRadius(3.dp.toPx()))
                x += w
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.hair)) {
            for ((label, color, v, optional) in items) {
                // Rare classes show only when present (a flag: colours are not compared, they change with the theme).
                if (optional && v <= 0) continue
                LegendItem(color, "$label ${pct(v, total)}")
            }
        }
        Hint(
            stringResource(if (s.referencedMovingS > 0) R.string.report_legend_hint_reference else R.string.report_legend_hint_no_reference),
        )
    }
}

private data class ClassShare(val label: String, val color: Color, val shareS: Double, val optional: Boolean = false)

private fun pct(v: Double, total: Double) = when {
    v <= 0 -> "0 %"
    v / total < 0.005 -> "< 1 %"
    else -> "%.0f %%".format(Locale.ROOT, v / total * 100)
}

private class Lane(@StringRes val label: Int, val color: Color, val spans: List<Pair<Long, Long>>)

/**
 * Conditions, speed and error over the session on one time axis. Tap or drag to read a moment;
 * the map marks the same moment.
 */
@Composable
fun ReportCharts(r: SessionReport, cursor: Long?, onCursor: (Long?) -> Unit) {
    val tm = rememberTextMeasurer()
    val dur = max(r.endNs - r.startNs, 1L)
    // Resolved here: the draw lambdas below are not composable.
    val colors = StarTheme.colors
    val lanes = remember(r, colors) {
        fun spans(kind: String) = r.spans.filter { it.kind == kind }.map { it.fromNs to it.toNs }
        listOf(
            Lane(R.string.report_lane_cutoff, colors.cutoff, r.cutoffs.map { it.startNs to it.endNs }),
            Lane(R.string.report_lane_interference, colors.interference, spans(ReportSpan.INTERFERENCE)),
            Lane(R.string.report_lane_dish_gnss, colors.dishGnss, spans(ReportSpan.DISH_GNSS)),
            Lane(R.string.report_lane_mock, colors.mockLane, spans(ReportSpan.MOCK)),
        ) + listOfNotNull(
            spans(ReportSpan.UNREACHABLE).takeIf { it.isNotEmpty() }?.let { Lane(R.string.report_lane_unreachable, colors.muted, it) },
            spans(ReportSpan.REBOOT).takeIf { it.isNotEmpty() }?.let { Lane(R.string.report_lane_reboot, colors.rebootLane, it) },
            spans(ReportSpan.FALLBACK).takeIf { it.isNotEmpty() }?.let { Lane(R.string.report_lane_fallback, colors.fallbackLane, it) },
            // The inertial filter replayed over the session (report version 6): what Auto serves with the correction on.
            spans(ReportSpan.DEAD_RECKONING).takeIf { it.isNotEmpty() }?.let { Lane(R.string.report_lane_dead_reckoning, colors.deadReckoningLane, it) },
            spans(ReportSpan.SIDEWAYS_OPEN).takeIf { it.isNotEmpty() }?.let { Lane(R.string.report_lane_sideways, colors.sidewaysLane, it) },
            spans(ReportSpan.CONFLICT).takeIf { it.isNotEmpty() }?.let { Lane(R.string.report_lane_conflict, colors.conflict, it) },
        )
    }
    val laneLabels = lanes.map { stringResource(it.label) }
    val hasRef = r.summary.referencedMovingS > 0
    val left = 34.dp
    val right = 6.dp

    fun DrawScope.x(t: Long) = left.toPx() + (t - r.startNs).toFloat() / dur * (size.width - left.toPx() - right.toPx())
    val touch = Modifier
        .pointerInput(r) {
            fun tAt(px: Float): Long {
                val l = left.toPx()
                val w = size.width - l - right.toPx()
                return r.startNs + (((px - l) / w).coerceIn(0f, 1f) * dur).toLong()
            }
            detectTapGestures(onTap = { onCursor(tAt(it.x)) })
        }
        .pointerInput(r) {
            fun tAt(px: Float): Long {
                val l = left.toPx()
                val w = size.width - l - right.toPx()
                return r.startNs + (((px - l) / w).coerceIn(0f, 1f) * dur).toLong()
            }
            detectHorizontalDragGestures { change, _ -> onCursor(tAt(change.position.x)) }
        }
    fun DrawScope.cursorLine() {
        cursor?.let { drawLine(colors.cursor, Offset(x(it), 0f), Offset(x(it), size.height), 1.dp.toPx()) }
    }
    fun DrawScope.cutBands(height: Float) {
        for (c in r.cutoffs) drawRect(colors.cutoff.copy(alpha = 0.16f), Offset(x(c.startNs), 0f), Size(max(x(c.endNs) - x(c.startNs), 2f), height))
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        // The hint is a sentence (proportional), the readout is data (monospace); one size, so the charts do not move on a tap.
        val style = MaterialTheme.typography.bodyMedium
        Text(readout(r, cursor), style = if (cursor == null) style else style.mono(), color = if (cursor == null) colors.muted else Color.Unspecified)
        Canvas(Modifier.fillMaxWidth().height((lanes.size * 26).dp).then(touch)) {
            val rowH = 26.dp.toPx()
            lanes.forEachIndexed { i, lane ->
                val y = i * rowH
                label(tm, laneLabels[i], Offset(left.toPx(), y), colors.muted, 11.sp)
                val barY = y + 15.dp.toPx()
                drawLine(colors.laneLine, Offset(left.toPx(), barY + 3.5.dp.toPx()), Offset(size.width - right.toPx(), barY + 3.5.dp.toPx()), 1.dp.toPx())
                for ((a, b) in lane.spans) {
                    drawRoundRect(lane.color, Offset(x(a), barY), Size(max(x(b) - x(a), 3.dp.toPx()), 7.dp.toPx()), CornerRadius(2.dp.toPx()))
                }
            }
            cursorLine()
        }
        Text(stringResource(R.string.report_chart_speed), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            LegendItem(colors.dish, stringResource(R.string.common_legend_dish, "AUTO"))
            if (r.reference.any { it.v != null }) LegendItem(colors.phone, stringResource(R.string.report_legend_phone_gnss), circle = true)
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp).then(touch)) {
            val maxV = max(20f, ((r.points.maxOfOrNull { it.v } ?: 0f).coerceAtLeast(r.reference.maxOfOrNull { it.v ?: 0f } ?: 0f) * 3.6f / 20f).let { kotlin.math.ceil(it) } * 20f)
            val h = size.height
            fun y(kmh: Float) = h - kmh / maxV * h
            cutBands(h)
            var v = 0f
            while (v <= maxV) {
                drawLine(colors.grid, Offset(left.toPx(), y(v)), Offset(size.width - right.toPx(), y(v)), 1.dp.toPx())
                label(tm, v.toInt().toString(), Offset(left.toPx() - 4.dp.toPx(), y(v) - 7.dp.toPx()), colors.muted, 10.sp, alignEnd = true)
                v += if (maxV > 120) 40f else 20f
            }
            series(r.reference.mapNotNull { p -> p.v?.let { p.t to it * 3.6f } }, colors.phone, 1.5.dp.toPx(), { x(it) }, ::y)
            series(r.points.map { it.t to it.v * 3.6f }, colors.dish, 2.dp.toPx(), { x(it) }, ::y)
            cursorLine()
        }
        if (hasRef) {
            Text(stringResource(R.string.report_chart_error), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Canvas(Modifier.fillMaxWidth().height(120.dp).then(touch)) {
                val worst = r.points.filter { isError(it.err) }.maxOfOrNull { it.err!! } ?: 10f
                val maxE = listOf(10f, 20f, 40f, 60f, 100f).firstOrNull { worst <= it } ?: 100f
                val h = size.height
                fun y(e: Float) = h - min(e, maxE) / maxE * h
                cutBands(h)
                for (e in listOf(0f) + AccuracyClasses.UPPER_M.map { it.toFloat() }.filter { it <= maxE } + listOf(maxE).filter { it.toDouble() !in AccuracyClasses.UPPER_M }) {
                    drawLine(colors.grid, Offset(left.toPx(), y(e)), Offset(size.width - right.toPx(), y(e)), 1.dp.toPx())
                    label(tm, e.toInt().toString(), Offset(left.toPx() - 4.dp.toPx(), y(e) - 7.dp.toPx()), colors.muted, 10.sp, alignEnd = true)
                }
                for (p in r.points) {
                    val e = p.err?.takeIf { isError(it) } ?: continue
                    drawCircle(classColor(colors, e), 2.dp.toPx(), Offset(x(p.t), y(e)))
                }
                cursorLine()
            }
        }
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val totalS = dur / 1e9
            val step = listOf(30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0).firstOrNull { totalS / it <= 6 } ?: 7200.0
            var t = 0.0
            while (t <= totalS) {
                // A tick near the right edge is drawn ending at the edge, not cut off ("10:0").
                val text = clock(t)
                val at = x(r.startNs + (t * 1e9).toLong())
                val fits = at + tm.measure(text, TextStyle(fontSize = 10.sp)).size.width <= size.width
                label(tm, text, Offset(if (fits) at else size.width, 0f), colors.muted, 10.sp, alignEnd = !fits)
                t += step
            }
        }
    }
}

private fun DrawScope.series(pts: List<Pair<Long, Float>>, color: Color, width: Float, x: (Long) -> Float, y: (Float) -> Float) {
    val path = Path()
    var prev: Long? = null
    for ((t, v) in pts) {
        if (prev == null || t - prev > GAP_NS) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
        prev = t
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
}

@Composable
private fun readout(r: SessionReport, cursor: Long?): String {
    if (cursor == null) return stringResource(R.string.report_readout_hint)
    val parts = mutableListOf(clock((cursor - r.startNs) / 1e9))
    val i = r.nearest(cursor)
    val p = r.points.getOrNull(i)?.takeIf { abs(it.t - cursor) <= GAP_NS }
    if (p == null) parts += stringResource(R.string.report_readout_no_fix) else {
        parts += stringResource(R.string.report_readout_dish_speed, "%.0f".format(Locale.ROOT, p.v * 3.6))
        p.err?.let {
            parts += if (AccuracyClasses.isConflict(it.toDouble())) {
                stringResource(R.string.report_readout_conflict, "%.1f".format(Locale.ROOT, it / 1000))
            } else {
                stringResource(R.string.report_readout_error, "%.1f".format(Locale.ROOT, it), accuracyClassLabel(AccuracyClasses.of(it.toDouble())))
            }
        }
        p.ferr?.takeIf { !AccuracyClasses.isConflict(it.toDouble()) }?.let { parts += stringResource(R.string.report_readout_filter_error, "%.1f".format(Locale.ROOT, it)) }
    }
    if (r.cutoffs.any { cursor in it.startNs..it.endNs }) parts += stringResource(R.string.report_cutoff)
    if (r.spans.any { it.kind == ReportSpan.INTERFERENCE && cursor in it.fromNs..it.toNs }) parts += stringResource(R.string.report_readout_interference)
    if (r.spans.any { it.kind == ReportSpan.REBOOT && cursor in it.fromNs..it.toNs }) parts += stringResource(R.string.report_readout_reboot)
    if (r.spans.any { it.kind == ReportSpan.FALLBACK && cursor in it.fromNs..it.toNs }) parts += stringResource(R.string.report_readout_fallback)
    if (r.spans.any { it.kind == ReportSpan.DEAD_RECKONING && cursor in it.fromNs..it.toNs }) parts += stringResource(R.string.report_readout_dead_reckoning)
    if (r.spans.any { it.kind == ReportSpan.SIDEWAYS_OPEN && cursor in it.fromNs..it.toNs }) parts += stringResource(R.string.report_readout_sideways)
    return parts.joinToString(" · ")
}

/** Numbers behind the map and charts. */
@Composable
fun ReportSummarySection(r: SessionReport) {
    val s = r.summary
    Section(stringResource(R.string.report_accuracy_title)) {
        KeyValueTable {
            if (s.referencedMovingS > 0) {
                KeyValueRow(stringResource(R.string.report_typical_error), "${m(s.errP50M)} · p95 ${m(s.errP95M)}")
                // The filter at the same AUTO moments, against today's extrapolation at exactly the moments it served.
                s.inertial?.takeIf { it.errP50M != null }?.let { f ->
                    KeyValueRow(stringResource(R.string.report_typical_error_inertial), "${m(f.errP50M)} · p95 ${m(f.errP95M)}")
                }
                KeyValueRow(stringResource(R.string.report_within_10), pct(s.classS.take(2).sum(), s.referencedMovingS))
                KeyValueRow(stringResource(R.string.report_worst), m(s.errMaxM))
                KeyValueRow(stringResource(R.string.report_unmoved_fix), "${m(s.rawP50M)} · p95 ${m(s.rawP95M)}")
                KeyValueRow(stringResource(R.string.report_phone_ref_accuracy), m(s.phoneAccP50M))
            } else {
                KeyValueRow(stringResource(R.string.report_error), stringResource(R.string.report_not_measurable), valueColor = StarTheme.colors.muted, mono = false)
            }
            if (s.conflictS > 0) {
                KeyValueRow(stringResource(R.string.report_legend_conflict), stringResource(R.string.report_conflict_value, "%.0f".format(Locale.ROOT, s.conflictS)), status = Status.WARN)
            }
            KeyValueRow(stringResource(R.string.report_fix_age), s.latencyS?.let { "%.2f s".format(Locale.ROOT, it) } ?: "-")
            KeyValueRow(stringResource(R.string.report_sigma), m(s.sigmaP50M))
            KeyValueRow(
                stringResource(R.string.report_moving),
                stringResource(R.string.report_moving_value, duration((s.movingS * 1000).toLong()), "%.2f".format(Locale.ROOT, s.distanceM / 1000), kmh(s.maxSpeedMps)),
            )
            KeyValueRow(
                stringResource(R.string.report_cutoff),
                if (r.cutoffs.isEmpty()) stringResource(R.string.report_never) else "${r.cutoffs.size} × · ${"%.0f".format(Locale.ROOT, s.cutoffS)} s",
                status = if (r.cutoffs.isEmpty()) null else Status.BAD,
            )
            KeyValueRow(
                stringResource(R.string.report_reboots),
                if (r.reboots.isEmpty()) stringResource(R.string.report_never)
                else "${r.reboots.size} × · " + r.reboots.joinToString(" + ") { b -> b.offlineS?.let { duration((it * 1000).toLong()) } ?: "?" },
                status = if (r.reboots.isEmpty()) null else Status.WARN,
            )
            KeyValueRow(stringResource(R.string.report_interference_keys), "${"%.0f".format(Locale.ROOT, s.interferenceS)} s / ${"%.0f".format(Locale.ROOT, s.dishGnssDegradedS)} s")
            if (s.mockOnS > 0) KeyValueRow(stringResource(R.string.report_mock_on), duration((s.mockOnS * 1000).toLong()))
            if (s.fallbackS > 0) KeyValueRow(stringResource(R.string.report_fallback), duration((s.fallbackS * 1000).toLong()), status = Status.WARN)
        }
        if (s.delivery.isNotEmpty()) {
            Text(stringResource(R.string.report_delivery_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            val gms = stringResource(R.string.mock_source_gms_fused)
            KeyValueTable { for ((source, d) in s.delivery) {
                val name = if (source == "gms_fused") gms else source
                KeyValueRow(
                    name,
                    stringResource(R.string.report_delivery_value, (100.0 * d.followed / d.received).f(0), d.received, d.notMock),
                    status = if (d.followed == d.received) Status.OK else Status.WARN,
                )
            } }
        }
    }
}

/** The inertial filter replayed over the session: what Auto serves with the correction on. */
@Composable
fun InertialReportSection(r: SessionReport) {
    val f = r.summary.inertial ?: return
    Section(stringResource(R.string.report_inertial_title)) {
        KeyValueTable {
            if (f.errP50M != null) {
                KeyValueRow(stringResource(R.string.report_inertial_error), stringResource(R.string.report_error_p50_p95_max, m(f.errP50M), m(f.errP95M), m(f.errMaxM)))
                KeyValueRow(stringResource(R.string.report_inertial_today), "${m(f.todayP50M)} · p95 ${m(f.todayP95M)}")
            }
            KeyValueRow(stringResource(R.string.report_inertial_dr), duration((f.deadReckonedS * 1000).toLong()))
            if (f.pushStoppedS > 0) KeyValueRow(stringResource(R.string.report_inertial_push_stopped), duration((f.pushStoppedS * 1000).toLong()), status = Status.WARN)
            if (f.drErrP50M != null) {
                KeyValueRow(
                    stringResource(R.string.report_inertial_dr_error),
                    stringResource(R.string.report_inertial_dr_error_value, m(f.drErrP50M), m(f.drErrP95M), f.drWithinAccuracy?.let { pct(it, 1.0) } ?: "-"),
                )
            }
            KeyValueRow(
                stringResource(R.string.report_inertial_sideways),
                stringResource(R.string.report_inertial_sideways_value, f.sidewaysOpenings, duration((f.sidewaysOpenS * 1000).toLong()), m(f.maxSidewaysM)),
                status = if (f.sidewaysOpenings > 0) Status.WARN else null,
            )
            KeyValueRow(stringResource(R.string.report_inertial_suspects), f.watchSuspects.toString())
            KeyValueRow(stringResource(R.string.report_inertial_rejects), "${f.gateRejects} · ${f.events}")
            KeyValueRow(stringResource(R.string.report_inertial_mount), f.mountOffsetDeg?.let { "%+.1f°".format(Locale.ROOT, it) } ?: stringResource(R.string.report_inertial_mount_none))
        }
        Hint(stringResource(R.string.report_inertial_hint))
    }
}

@Composable
fun CutoffsSection(r: SessionReport) {
    if (r.cutoffs.isEmpty()) return
    Section(stringResource(R.string.report_cutoffs_title)) {
        for (c in r.cutoffs) {
            KeyValueRow(
                stringResource(R.string.report_cutoff_at, clock((c.startNs - r.startNs) / 1e9)),
                if (c.open) stringResource(R.string.report_cutoff_open_value, "%.1f".format(Locale.ROOT, c.durationS)) else "%.1f s".format(Locale.ROOT, c.durationS),
                status = Status.BAD,
            )
            Hint(
                stringResource(
                    R.string.report_cutoff_detail,
                    disablementReason(c.code), kmh(c.speedBeforeMps), kmh(c.maxSpeed60sMps), kmh(c.speedAfterMps),
                    kmh(c.phoneSpeedStartMps), kmh(c.phoneSpeedEndMps), c.distanceM?.let { "%.0f m".format(Locale.ROOT, it) } ?: "-",
                ),
            )
        }
    }
}

/** Each dish restart: when it came back, how long it was gone, why. */
@Composable
fun RebootsSection(r: SessionReport) {
    if (r.reboots.isEmpty()) return
    Section(stringResource(R.string.report_reboots_title)) {
        for (b in r.reboots) {
            KeyValueRow(
                stringResource(R.string.report_reboot_at, clock((b.backNs - r.startNs) / 1e9)),
                b.offlineS?.let { stringResource(R.string.report_reboot_offline, duration((it * 1000).toLong())) } ?: "-",
                status = Status.WARN,
            )
            Hint(listOfNotNull(rebootCause(b), rebootCodes(b), if (b.updated) "${b.softwareBefore} → ${b.softwareAfter}" else null).joinToString(" · "))
        }
        r.dishBootNs?.let { Hint(stringResource(R.string.report_dish_up_at_start, duration((r.startNs - it) / 1_000_000))) }
    }
}

@Composable
fun ReportPlaceholder(text: String) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(StarTheme.colors.mapGround).mapRing(), contentAlignment = Alignment.Center) {
        Hint(text)
    }
}

/** Metres with one decimal, the unit in the app's language. */
@Composable
private fun m(v: Double?) = v?.let { stringResource(R.string.live_unit_m, "%.1f".format(Locale.ROOT, it)) } ?: "-"
private fun kmh(mps: Double?) = mps?.let { "%.0f km/h".format(Locale.ROOT, it * 3.6) } ?: "-"
private fun clock(s: Double): String {
    val t = s.toLong().coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(Locale.ROOT, t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(Locale.ROOT, t / 60, t % 60)
}
