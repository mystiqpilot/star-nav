package tools.apachi.starlinknav.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.Obstruction
import tools.apachi.starlinknav.core.dish.ObstructionAnalysis
import tools.apachi.starlinknav.core.dish.attitude
import tools.apachi.starlinknav.core.dish.proto.DishGetObstructionMapResponse
import tools.apachi.starlinknav.service.DeviceCall
import tools.apachi.starlinknav.service.DishFeed

/**
 * What blocks the sky: the dish's obstruction map drawn as a polar picture (the middle is straight up, the edge low over
 * the horizon), north up, with the blocked share, eight compass sectors, the largest blocked patch and how fresh the data
 * is. A map in the dish's own frame is turned by the direction its panel top points to and marked approximate.
 */
@Composable
fun DishSky(feed: DishFeed) {
    val samples by feed.samples.collectAsStateWithLifecycle()
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    val mapSample = samples[DishCallKind.OBSTRUCTION_MAP]
    val map = mapSample.okResponse()?.dish_get_obstruction_map
    val status = samples[DishCallKind.STATUS].okResponse()?.dish_get_status
    val panelUp = status?.ned2dish_quaternion?.attitude()?.panelUpAzimuthDeg
    val analysis = remember(map, panelUp) { map?.let { Obstruction.analyze(it, panelUp) } }
    val now = rememberMonoNow(1000)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (map == null || analysis == null || mapSample == null) {
            FeedProblem(mapSample, address = settings.dishHost)
        } else {
            SkyMapCard(map, analysis)
            SkyNumbersCard(analysis, mapSample.monoNs, now, status?.obstruction_stats?.valid_s)
            SectorsCard(analysis)
        }
        ClearMapCard()
    }
}

@Composable
private fun SkyMapCard(map: DishGetObstructionMapResponse, a: ObstructionAnalysis) {
    val colors = StarTheme.colors
    val image = remember(map, colors) { skyImage(map, colors) }
    val tm = rememberTextMeasurer()
    val north = stringResource(R.string.compass_n)
    val east = stringResource(R.string.compass_e)
    val south = stringResource(R.string.compass_s)
    val west = stringResource(R.string.compass_w)
    val description = stringResource(R.string.sky_map_description, a.blockedFraction?.let { (it * 100).f(1) } ?: "-")
    Section(stringResource(R.string.sky_map_title)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().semantics { contentDescription = description }) {
                val pad = 22.dp.toPx()
                val d = size.minDimension - 2 * pad
                val centre = Offset(size.width / 2, size.height / 2)
                // The disc of sky, then the picture on it (turned to put north up when the map is fixed to the dish).
                drawCircle(colors.grid, d / 2, centre, style = Fill)
                rotate(a.rotationDeg.toFloat(), pivot = centre) {
                    drawImage(
                        image, IntOffset.Zero, IntSize(image.width, image.height),
                        IntOffset((centre.x - d / 2).toInt(), (centre.y - d / 2).toInt()), IntSize(d.toInt(), d.toInt()),
                        filterQuality = FilterQuality.None,
                    )
                }
                drawCircle(colors.muted, d / 2, centre, style = Stroke(1.dp.toPx()))
                val gap = 12.dp.toPx()
                label(tm, north, Offset(centre.x, centre.y - d / 2 - gap), colors.muted, 12.sp, center = true, bold = true)
                label(tm, south, Offset(centre.x, centre.y + d / 2 + gap), colors.muted, 12.sp, center = true)
                label(tm, east, Offset(centre.x + d / 2 + gap, centre.y), colors.muted, 12.sp, center = true)
                label(tm, west, Offset(centre.x - d / 2 - gap, centre.y), colors.muted, 12.sp, center = true)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            Swatch(colors.dish.copy(alpha = CLEAR_ALPHA), stringResource(R.string.sky_clear))
            Swatch(colors.warn, stringResource(R.string.sky_reduced))
            Swatch(colors.bad, stringResource(R.string.sky_blocked))
        }
        Hint(stringResource(R.string.sky_map_hint))
        if (a.approximate) StatusPill(Status.WARN, stringResource(R.string.sky_approximate))
        Hint(stringResource(R.string.sky_moving_hint))
    }
}

private const val CLEAR_ALPHA = 0.7f

@Composable
private fun Swatch(color: Color, name: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp)) { drawRect(color, Offset.Zero, size, style = Fill) }
        Text(name, style = MaterialTheme.typography.bodySmall)
    }
}

/** The map as a picture, one pixel per cell: blocked, reduced and clear in colour, cells without data transparent. */
private fun skyImage(map: DishGetObstructionMapResponse, colors: StarColors): ImageBitmap {
    val rows = map.num_rows
    val cols = map.num_cols
    val blocked = colors.bad.toArgb()
    val reduced = colors.warn.toArgb()
    val clear = colors.dish.copy(alpha = CLEAR_ALPHA).toArgb()
    val pixels = IntArray(rows * cols) { i ->
        val v = map.snr[i]
        when {
            !v.isFinite() || v < 0f -> 0
            v == 0f -> blocked
            v < 1f -> reduced
            else -> clear
        }
    }
    return Bitmap.createBitmap(pixels, cols, rows, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
private fun SkyNumbersCard(a: ObstructionAnalysis, receivedMonoNs: Long, nowNs: Long, validS: Float?) {
    val colors = StarTheme.colors
    Section(stringResource(R.string.sky_numbers_title), ledger = true) {
        val blocked = a.blockedFraction
        KeyValueRow(
            stringResource(R.string.sky_blocked_share), blocked?.let { stringResource(R.string.unit_percent, (it * 100).f(1)) } ?: "-", status = if (blocked != null && blocked > 0.1) Status.WARN else null)
        a.observed.takeIf { it > 0 }?.let {
            KeyValueRow(stringResource(R.string.sky_reduced_share), stringResource(R.string.unit_percent, (a.reduced * 100.0 / it).f(1)))
            KeyValueRow(stringResource(R.string.sky_clear_share), stringResource(R.string.unit_percent, (a.clear * 100.0 / it).f(1)))
        }
        KeyValueRow(stringResource(R.string.sky_observed), stringResource(R.string.sky_observed_value, a.observed.toString(), (a.rows * a.cols).toString()))
        KeyValueRow(
            stringResource(R.string.sky_largest_patch),
            a.largestPatchFraction?.let { pluralStringResource(R.plurals.sky_largest_patch_value, a.largestBlockedPatch, a.largestBlockedPatch.toString(), (it * 100).f(1)) } ?: "-",
        )
        KeyValueRow(stringResource(R.string.sky_fetched), stringResource(R.string.dish_ago, duration(((nowNs - receivedMonoNs) / 1_000_000).coerceAtLeast(0))))
        validS?.takeIf { it > 0f }?.let { KeyValueRow(stringResource(R.string.sky_data_covers), duration((it * 1000).toLong())) }
        if (a.observed == 0) Hint(stringResource(R.string.sky_no_data))
    }
}

@Composable
private fun SectorsCard(a: ObstructionAnalysis) {
    val colors = StarTheme.colors
    val names = listOf(
        R.string.compass_n, R.string.compass_ne, R.string.compass_e, R.string.compass_se,
        R.string.compass_s, R.string.compass_sw, R.string.compass_w, R.string.compass_nw,
    ).map { stringResource(it) }
    Section(stringResource(R.string.sky_sectors_title)) {
        for (s in a.sectors) {
            val f = s.blockedFraction
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(names[s.index], Modifier.size(width = 34.dp, height = 18.dp), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                Canvas(Modifier.weight(1f).height(10.dp)) {
                    drawRect(colors.grid, Offset.Zero, size, style = Fill)
                    if (f != null) drawRect(colors.bad, Offset.Zero, Size((size.width * f.toFloat()).coerceAtLeast(if (f > 0) 2f else 0f), size.height), style = Fill)
                }
                Text(f?.let { stringResource(R.string.unit_percent, (it * 100).f(0)) } ?: "-", Modifier.size(width = 48.dp, height = 18.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Hint(stringResource(R.string.sky_sectors_hint))
    }
}

@Composable
private fun ClearMapCard() {
    val sender = rememberSender()
    var confirm by remember { mutableStateOf(false) }
    ControlCard(
        title = stringResource(R.string.sky_clear_title),
        description = stringResource(R.string.sky_clear_text),
        kinds = listOf(DishCallKind.CLEAR_OBSTRUCTION_MAP),
        tag = TAG_CLEAR,
    ) { enabled ->
        PrimaryButton(stringResource(R.string.sky_clear_button), { confirm = true }, enabled = enabled && !sender.busy)
    }
    if (confirm) {
        val call = DeviceCall.of(DishCallKind.CLEAR_OBSTRUCTION_MAP)
        ConfirmDialog(
            title = stringResource(R.string.sky_clear_title),
            text = stringResource(R.string.sky_clear_confirm),
            confirmLabel = stringResource(R.string.sky_clear_button),
            risk = call.risk,
            onConfirm = {
                confirm = false
                sender.send(call, TAG_CLEAR)
            },
            onDismiss = { confirm = false },
        )
    }
}

private const val TAG_CLEAR = "clear_obstruction_map"
