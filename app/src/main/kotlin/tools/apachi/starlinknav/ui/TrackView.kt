package tools.apachi.starlinknav.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.apachi.starlinknav.MapLayer
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.log.TrackPoint
import kotlin.math.abs
import kotlin.math.cos

/** Window edges as monotonic ns; points inside are drawn bright, the rest faded. */
@Composable
fun TrackView(
    dish: List<TrackPoint>, phone: List<TrackPoint>, from: Long, to: Long, layer: MapLayer,
    modifier: Modifier = Modifier, interactive: Boolean = false,
) {
    val projection = remember(dish, phone) { Projection.of(dish.ifEmpty { phone }, dish + phone) }
    val fit = remember(dish, phone, projection) { projection?.let { pr -> (dish + phone).filter(pr::inView).map { it.lat to it.lon } }.orEmpty() }
    val tm = rememberTextMeasurer()
    // Resolved here: the draw lambda below is not composable.
    val colors = StarTheme.colors
    val paint = mapPaint(layer)
    Column(modifier) {
        if (projection == null) {
            Hint(stringResource(R.string.sessions_no_positions))
            return@Column
        }
        val mapModifier = if (interactive) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(1f)
        GeoMap(layer, fit, mapModifier, interactive) { view ->
            val toScreen = { p: TrackPoint -> view.toScreen(p.lat, p.lon) }
            if (paint.casing) {
                drawTrack(phone, projection, toScreen, from, to, Casing, 2.dp.toPx() + 3.dp.toPx())
                drawTrack(dish, projection, toScreen, from, to, Casing, 3.dp.toPx() + 3.dp.toPx())
            }
            drawTrack(phone, projection, toScreen, from, to, colors.phone, 2.dp.toPx())
            drawTrack(dish, projection, toScreen, from, to, colors.dish, 3.dp.toPx())
            val ref = dish.ifEmpty { phone }
            listOf(from to colors.good, to to colors.bad).forEach { (t, color) ->
                ref.minByOrNull { abs(it.t - t) }?.takeIf { projection.inView(it) }?.let { p ->
                    val c = toScreen(p)
                    drawCircle(color, radius = 9f, center = c)
                    drawCircle(paint.inkInverse, radius = 9f, center = c, style = Stroke(2f))
                }
            }
            scaleBar(tm, view.metresPerPx(), paint)
        }
        if (projection.hidden > 0) {
            Text(pluralStringResource(R.plurals.sessions_positions_hidden, projection.hidden, projection.hidden), color = colors.warn, fontSize = 12.sp)
        }
    }
}

private fun DrawScope.drawTrack(
    pts: List<TrackPoint>, proj: Projection, toScreen: (TrackPoint) -> Offset,
    from: Long, to: Long, color: Color, width: Float,
) {
    if (pts.size < 2) return
    for (inside in listOf(false, true)) {
        val path = Path()
        var penDown = false
        for (p in pts) {
            val visible = proj.inView(p) && ((p.t in from..to) == inside)
            if (!visible) {
                penDown = false
                continue
            }
            val o = toScreen(p)
            if (penDown) path.lineTo(o.x, o.y) else path.moveTo(o.x, o.y)
            penDown = true
        }
        drawPath(path, if (inside) color else color.copy(alpha = 0.25f), style = Stroke(width))
    }
}

/** Keeps fixes far from the median (a spoofed jump to another continent) out of the picture. */
internal class Projection(val lat0: Double, val lon0: Double, val hidden: Int) {
    fun inView(p: TrackPoint) = abs(p.lat - lat0) < MAX_DEG && abs(p.lon - lon0) < MAX_DEG / cos(Math.toRadians(lat0))

    companion object {
        const val MAX_DEG = 0.45

        fun of(reference: List<TrackPoint>, all: List<TrackPoint>): Projection? {
            if (reference.isEmpty()) return null
            val lat0 = reference.map { it.lat }.sorted()[reference.size / 2]
            val lon0 = reference.map { it.lon }.sorted()[reference.size / 2]
            val probe = Projection(lat0, lon0, 0)
            val shown = all.count(probe::inView)
            if (shown == 0) return null
            return Projection(lat0, lon0, all.size - shown)
        }
    }
}
