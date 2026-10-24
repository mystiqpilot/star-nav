package tools.apachi.starlinknav.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.ClientList
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.RouterEvents
import tools.apachi.starlinknav.core.dish.RouterHistory
import tools.apachi.starlinknav.core.dish.RouterSeries
import tools.apachi.starlinknav.core.dish.proto.UXEvent
import tools.apachi.starlinknav.core.dish.proto.WifiClient
import tools.apachi.starlinknav.service.DishFeed

private const val MAX_EVENTS = 40
private const val STEP_15S = 15_000L

/**
 * The router's last 15 minutes: the ping latency to the internet, how many of those pings were lost (as the mean of 15
 * seconds), the 15-second drop rates to the satellite network (POP), Google and Cloudflare, and the DNS resolvers; then the
 * router's own event log. A sample the router could not take (no data) leaves a gap.
 */
@Composable
fun RouterHistorySection(feed: DishFeed, data: RouterData, env: RouterEnv) {
    val sample = data.samples[DishCallKind.ROUTER_HISTORY]
    val response = sample.okResponse()?.wifi_get_history
    val h = remember(sample) { response?.let(RouterHistory::of) }
    if (h == null || sample == null) {
        FeedProblem(sample, Device.ROUTER, env.target.address)
        return
    }
    val colors = StarTheme.colors
    val nowNs = response?.event_log?.current_timestamp_ns?.takeIf { it > 0 }
    // One span for every chart: they are slices of one ring read at one moment, whose 15 s buckets would otherwise each round
    // the time at their left edge differently while the ring is still filling.
    val spanMs = h.pingLatencyMs.size * 1000L
    Hint(stringResource(R.string.rh_hint))
    HistoryChart(
        stringResource(R.string.rh_latency), h.pingLatencyMs.map { if (it > 0f) it else Float.NaN }, colors.dish, emptyList(),
        minTop = 50f, unit = R.string.unit_ms, decimals = 1, spanMs = spanMs,
    )
    val dropEvery15 = remember(h) { RouterSeries.percent(RouterSeries.bucketMean(h.pingDropRate, 15)) }
    HistoryChart(
        stringResource(R.string.rh_drop), dropEvery15, colors.bad, emptyList(),
        minTop = 5f, unit = R.string.unit_percent, decimals = 1, stepMs = STEP_15S, spanMs = spanMs,
    )
    val series = listOf(
        R.string.rh_pop_v4 to h.popIpv4Drop15s, R.string.rh_pop_v6 to h.popIpv6Drop15s,
        R.string.rh_google_v4 to h.googleIpv4Drop15s, R.string.rh_google_v6 to h.googleIpv6Drop15s,
        R.string.rh_cloudflare_v4 to h.cloudflareIpv4Drop15s, R.string.rh_cloudflare_v6 to h.cloudflareIpv6Drop15s,
    )
    for ((title, values) in series) {
        val pct = RouterSeries.percent(values)
        if (pct.any { it.isFinite() }) {
            HistoryChart(stringResource(title), pct, colors.bad, emptyList(), minTop = 5f, unit = R.string.unit_percent, decimals = 1, stepMs = STEP_15S, spanMs = spanMs)
        }
    }
    for ((resolver, values) in h.dnsResolverDrop15s) {
        val pct = RouterSeries.percent(values)
        if (pct.any { it.isFinite() }) {
            HistoryChart(stringResource(R.string.rh_dns, resolver), pct, colors.warn, emptyList(), minTop = 5f, unit = R.string.unit_percent, decimals = 1, stepMs = STEP_15S, spanMs = spanMs)
        }
    }
    RouterEventsCard(h.events, nowNs, sample.wallMs, data.clients)
}

@Composable
private fun RouterEventsCard(events: List<UXEvent>, nowNs: Long?, receivedWallMs: Long, clients: List<WifiClient>?) {
    val colors = StarTheme.colors
    Section(stringResource(R.string.rh_events_title)) {
        if (events.isEmpty()) {
            Text(stringResource(R.string.rh_events_none), color = colors.muted, style = MaterialTheme.typography.bodyMedium)
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
            val client = RouterEvents.clientId(e)?.let { id -> clients?.let { ClientList.byId(it, id) }?.let(ClientList::displayName) }
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(whenText, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                    StatusPill(status, stringResource(eventSeverityRes(e.severity)))
                    if (e.duration_ns > 0) Text(stringResource(R.string.unit_s, (e.duration_ns / 1e9).f(1)), style = MaterialTheme.typography.bodyMedium)
                }
                Text(routerEventText(e.reason, client), style = MaterialTheme.typography.bodyMedium, color = Color.Unspecified)
            }
        }
        if (events.size > shown.size) Hint(stringResource(R.string.hist_showing_latest, shown.size.toString(), events.size.toString()))
    }
}
