package tools.apachi.starlinknav.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishHistory
import tools.apachi.starlinknav.core.dish.Series
import tools.apachi.starlinknav.core.dish.proto.DishOutage
import tools.apachi.starlinknav.core.dish.proto.UXEvent
import tools.apachi.starlinknav.service.DishFeed
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val MAX_OUTAGES = 25
private const val MAX_EVENTS = 40

/**
 * The last 15 minutes of the dish in one-second samples: latency, drop rate, download, upload and power, with the dish's
 * outages shaded on every chart, then the outage list and the event log in plain words. The rings are unrolled by
 * [DishHistory.of]; a stat the dish could not compute (NaN) leaves a gap.
 */
@Composable
fun DishHistoryTab(feed: DishFeed) {
    val samples by feed.samples.collectAsStateWithLifecycle()
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    val sample = samples[DishCallKind.HISTORY]
    val response = sample.okResponse()?.dish_get_history
    val h = remember(sample) { response?.let(DishHistory::of) }
    val colors = StarTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (h == null || sample == null) {
            FeedProblem(sample, address = settings.dishHost)
            return@Column
        }
        // Outages and events carry the dish's own clock; its "now" is the event log's current time.
        val nowNs = response?.event_log?.current_timestamp_ns?.takeIf { it > 0 }
        val n = h.size
        val bands = remember(h, nowNs) { if (nowNs == null) emptyList() else h.outages.mapNotNull { sampleSpan(it, nowNs, n) } }
        Hint(stringResource(R.string.hist_hint))
        HistoryChart(
            stringResource(R.string.hist_latency), h.latencyMs.map { if (it > 0f) it else Float.NaN }, colors.dish, bands,
            minTop = 50f, unit = R.string.unit_ms, decimals = 1,
        )
        HistoryChart(
            stringResource(R.string.hist_drop), h.dropRate.map { it * 100f }, colors.bad, bands,
            minTop = 5f, unit = R.string.unit_percent, decimals = 1,
        )
        HistoryChart(
            stringResource(R.string.hist_download), h.downlinkBps.map { it / 1e6f }, colors.good, bands,
            minTop = 1f, unit = R.string.unit_mbps, decimals = 1,
        )
        HistoryChart(
            stringResource(R.string.hist_upload), h.uplinkBps.map { it / 1e6f }, colors.phone, bands,
            minTop = 0.5f, unit = R.string.unit_mbps, decimals = 2,
        )
        if (h.powerInW.any { it.isFinite() && it > 0f }) {
            HistoryChart(
                stringResource(R.string.hist_power), h.powerInW.map { if (it > 0f) it else Float.NaN }, colors.warn, bands,
                minTop = 10f, unit = R.string.unit_w, decimals = 1,
            )
        } else {
            Section(stringResource(R.string.hist_power)) { Hint(stringResource(R.string.hist_no_power)) }
        }
        OutagesCard(h.outages, nowNs, sample.wallMs)
        EventsCard(h.events, nowNs, sample.wallMs)
    }
}

/** The samples an outage covers, as a range of positions along the ring (0 = oldest .. n - 1 = newest); null when it is outside the ring. */
private fun sampleSpan(o: DishOutage, nowNs: Long, n: Int): ClosedFloatingPointRange<Float>? {
    if (n < 2) return null
    val startAge = (nowNs - o.start_timestamp_ns) / 1e9f
    val endAge = startAge - o.duration_ns / 1e9f
    if (endAge > n || startAge < -5f) return null
    val from = (n - 1 - startAge).coerceIn(0f, (n - 1).toFloat())
    val to = (n - 1 - endAge).coerceIn(0f, (n - 1).toFloat())
    return from..max(to, from)
}

/** The smallest 1, 2 or 5 times a power of ten that is at least [v]. */
internal fun niceCeil(v: Float): Float {
    if (!v.isFinite() || v <= 0f) return 1f
    val p = 10.0.pow(kotlin.math.floor(log10(v.toDouble())))
    val m = v / p
    val nice = when {
        m <= 1.0 -> 1.0
        m <= 2.0 -> 2.0
        m <= 5.0 -> 5.0
        else -> 10.0
    }
    return (nice * p).toFloat()
}

/**
 * One series over the last 15 minutes: now, average and highest value above the line, outages shaded in red behind it,
 * the value range on the left and the time ends below. [minTop] is the smallest top of the value axis, so a quiet
 * series does not stretch noise to the full height.
 */
@Composable
fun HistoryChart(
    title: String,
    values: List<Float>,
    color: Color,
    bands: List<ClosedFloatingPointRange<Float>>,
    minTop: Float,
    /** A string resource with one `%1$s` for the number (ms, percent, Mbit/s, W) and the decimals the readout shows. */
    @StringRes unit: Int,
    decimals: Int,
    /** Time between two samples (the router's drop-rate series come in 15 s steps). */
    stepMs: Long = 1000L,
    /** The time the chart spans, for its left-hand label; the samples' count times [stepMs] by default. */
    spanMs: Long = values.size * stepMs,
) {
    val colors = StarTheme.colors
    val tm = rememberTextMeasurer()
    val last = Series.lastFinite(values)
    val mean = Series.mean(values)
    val top = niceCeil(max(minTop, Series.max(values) ?: 0f))
    val none = stringResource(R.string.hist_no_data)
    fun number(v: Float) = v.f(decimals)
    // The axis writes round values short: "5 Mbit/s", "0.5 Mbit/s".
    fun axis(v: Float) = if (v == v.toInt().toFloat()) v.f(0) else v.f(1)
    val summary = if (last == null) none else stringResource(
        R.string.hist_readout, stringResource(unit, number(last)), mean?.let { stringResource(unit, number(it)) } ?: "-", stringResource(unit, number(Series.max(values) ?: 0f)),
    )
    val topLabel = stringResource(unit, axis(top))
    val zeroLabel = stringResource(unit, axis(0f))
    val ago = stringResource(R.string.dish_ago, duration(spanMs))
    val now = stringResource(R.string.chart_now)
    Section(title) {
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = if (last == null) colors.muted else Color.Unspecified)
        Canvas(
            Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = "$title: $summary" },
        ) {
            val left = 62.dp.toPx()
            val w = size.width - left - 4.dp.toPx()
            val h = size.height
            fun x(i: Float) = left + (if (values.size > 1) i / (values.size - 1) else 0f) * w
            fun y(v: Float) = h - (v / top).coerceIn(0f, 1f) * h
            // Grid at 0, half, top, with the range written at the left.
            for (g in listOf(0f, 0.5f, 1f)) drawLine(colors.grid, Offset(left, h - g * h), Offset(left + w, h - g * h), 1.dp.toPx())
            label(tm, topLabel, Offset(left - 4.dp.toPx(), 0f), colors.muted, 10.sp, alignEnd = true)
            label(tm, zeroLabel, Offset(left - 4.dp.toPx(), h - 12.dp.toPx()), colors.muted, 10.sp, alignEnd = true)
            for (b in bands) {
                val x0 = x(b.start)
                drawRect(colors.bad.copy(alpha = 0.18f), Offset(x0, 0f), Size(max(x(b.endInclusive) - x0, 2.dp.toPx()), h))
            }
            line(values, color, ::x, ::y)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(ago, Modifier.weight(1f).padding(start = 58.dp), style = MaterialTheme.typography.labelSmall, color = colors.muted)
            Text(now, style = MaterialTheme.typography.labelSmall, color = colors.muted)
        }
    }
}

/** The series as a line with a gap at every NaN; a lone sample is a dot. */
private fun DrawScope.line(values: List<Float>, color: Color, x: (Float) -> Float, y: (Float) -> Float) {
    val width = 1.8.dp.toPx()
    var path: Path? = null
    var count = 0
    var lone = Offset.Zero
    fun finish() {
        val p = path
        if (p != null && count >= 2) drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (p != null && count == 1) drawCircle(color, width * 0.8f, lone, style = Fill)
        path = null
        count = 0
    }
    for (i in values.indices) {
        val v = values[i]
        if (!v.isFinite()) {
            finish()
            continue
        }
        val px = x(i.toFloat())
        val py = y(v)
        if (path == null) path = Path().apply { moveTo(px, py) } else path!!.lineTo(px, py)
        count++
        lone = Offset(px, py)
    }
    finish()
}

@Composable
private fun OutagesCard(outages: List<DishOutage>, nowNs: Long?, receivedWallMs: Long) {
    val colors = StarTheme.colors
    Section(stringResource(R.string.hist_outages_title)) {
        if (outages.isEmpty()) {
            StatusPill(Status.OK, stringResource(R.string.hist_outages_none))
            return@Section
        }
        val shown = outages.sortedByDescending { it.start_timestamp_ns }.take(MAX_OUTAGES)
        for (o in shown) {
            val whenText = nowNs?.let { timeOfDay(receivedWallMs - (it - o.start_timestamp_ns) / 1_000_000) } ?: "-"
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(whenText, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                Text(stringResource(R.string.unit_s, (o.duration_ns / 1e9).f(1)), style = MaterialTheme.typography.bodyMedium)
                Text(outageCauseText(o.cause), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (outages.size > shown.size) Hint(stringResource(R.string.hist_showing_latest, shown.size.toString(), outages.size.toString()))
        val total = outages.sumOf { it.duration_ns } / 1e9
        KeyValueRow(stringResource(R.string.hist_outages_total), stringResource(R.string.unit_s, total.f(1)))
    }
}

@Composable
private fun EventsCard(events: List<UXEvent>, nowNs: Long?, receivedWallMs: Long) {
    val colors = StarTheme.colors
    Section(stringResource(R.string.hist_events_title)) {
        if (events.isEmpty()) {
            Text(stringResource(R.string.hist_events_none), color = colors.muted, style = MaterialTheme.typography.bodyMedium)
            return@Section
        }
        val shown = events.take(MAX_EVENTS)
        for (e in shown) {
            val whenText = nowNs?.let { timeOfDay(receivedWallMs - (it - e.start_timestamp_ns) / 1_000_000) } ?: "-"
            val status = when (e.severity) {
                1 -> Status.BAD
                2 -> Status.WARN
                else -> Status.NEUTRAL
            }
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(whenText, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                    StatusPill(status, stringResource(eventSeverityRes(e.severity)))
                    if (e.duration_ns > 0) Text(stringResource(R.string.unit_s, (e.duration_ns / 1e9).f(1)), style = MaterialTheme.typography.bodyMedium)
                }
                Text(eventReasonText(e.reason), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (events.size > shown.size) Hint(stringResource(R.string.hist_showing_latest, shown.size.toString(), events.size.toString()))
    }
}
