package tools.apachi.starlinknav.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.apachi.starlinknav.core.nav.TrustFix

/**
 * One mark of the Position page's own map: a position with its accuracy circle and name. Dish positions are squares and
 * phone positions circles, as on every other map of the app; what apps get is a ring.
 */
internal class PositionMark(
    val name: String,
    val fix: TrustFix,
    val kind: Kind,
    val color: Color,
    /** Drawn faded: the fix is older than [STALE_NS]. */
    val stale: Boolean,
    /** A distrusted GPS: a dashed ring instead of a filled disc. */
    val dashed: Boolean = false,
) {
    enum class Kind { SERVED, DISH, PHONE }
}

/** The simple view: what apps get (mock on), the dish position and the phone's GPS, each with its accuracy circle. */
internal fun DrawScope.drawPosition(view: GeoView, marks: List<PositionMark>, tm: TextMeasurer, paint: MapPaint) {
    val casing = paint.casing
    val mpp = view.metresPerPx()
    fun at(m: PositionMark) = view.toScreen(m.fix.lat, m.fix.lon)

    // Accuracy circles first, under everything.
    for (m in marks) {
        val r = (m.fix.accuracyM / mpp).toFloat()
        if (r < 4f || r > 3 * size.maxDimension) continue
        val alpha = if (m.stale) 0.4f else 1f
        val c = at(m)
        drawCircle(m.color.copy(alpha = 0.10f * alpha), r, c)
        drawCircle(m.color.copy(alpha = paint.ringAlpha * alpha), r, c, style = Stroke(1.dp.toPx()))
    }

    // A name must not overprint another name or another position's mark (a GPS 5 m from the dish sits a few pixels from
    // it): it takes the right of its own mark, else the next free line, else the left side.
    val placed = ArrayList<Rect>()
    val lineH = 14.dp.toPx()
    fun nameAt(text: String, own: PositionMark, bold: Boolean) {
        val w = tm.measure(text, TextStyle(fontSize = 11.sp)).size.width.toFloat()
        val c = at(own)
        val foreign = marks.filter { it !== own }.map {
            val p = at(it)
            val r = (if (it.kind == PositionMark.Kind.SERVED) 9.dp else 6.dp).toPx()
            Rect(p.x - r, p.y - r, p.x + r, p.y + r)
        }
        val gap = (if (bold) 13.dp else 9.dp).toPx()
        val top = c.y - 7.dp.toPx()
        for (right in listOf(true, false)) {
            val x = if (right) c.x + gap else c.x - gap - w
            for (line in 0 until 6) {
                val y = top + line * lineH
                val r = Rect(x, y, x + w, y + lineH)
                if (placed.none { it.overlaps(r) } && foreign.none { it.overlaps(r) }) {
                    placed += r
                    label(tm, text, Offset(if (right) x else c.x - gap, y), paint.ink, 11.sp, alignEnd = !right, bold = bold, halo = casing)
                    return
                }
            }
        }
        // Crowded everywhere: next to its own mark, as before.
        label(tm, text, Offset(c.x + gap, top), paint.ink, 11.sp, bold = bold, halo = casing)
    }

    // Marks first, the ring of what apps get over the dish and phone marks; then every name, so no mark covers a name.
    val half = 5.dp.toPx()
    for (m in marks.sortedBy { if (it.kind == PositionMark.Kind.SERVED) 1 else 0 }) {
        val c = at(m)
        val color = if (m.stale) m.color.copy(alpha = 0.4f) else m.color
        when (m.kind) {
            PositionMark.Kind.DISH -> {
                if (casing) drawRect(Casing, Offset(c.x - half - 1.5f, c.y - half - 1.5f), Size(2 * half + 3f, 2 * half + 3f))
                drawRect(color, Offset(c.x - half, c.y - half), Size(2 * half, 2 * half))
            }
            PositionMark.Kind.PHONE -> {
                if (casing) drawCircle(Casing, half + 1.5f, c)
                if (m.dashed) {
                    // A distrusted GPS: an open dashed ring, not a position to rely on.
                    drawCircle(color, half + 1.dp.toPx(), c, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
                } else {
                    drawCircle(color, half, c)
                }
            }
            PositionMark.Kind.SERVED -> {
                if (casing) drawCircle(Casing, 9.dp.toPx(), c)
                drawCircle(paint.ink, 7.dp.toPx(), c, style = Stroke(3.dp.toPx()))
            }
        }
    }
    // A name sits right of its own mark; names of marks that agree to a few metres take the next free line.
    for (m in marks.sortedBy { if (it.kind == PositionMark.Kind.SERVED) 0 else 1 }) {
        nameAt(m.name, m, bold = m.kind == PositionMark.Kind.SERVED)
    }
    scaleBar(tm, mpp, paint)
}
