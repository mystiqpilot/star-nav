package tools.apachi.starlinknav.ui

import android.content.res.Resources
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tools.apachi.starlinknav.R
import java.util.Locale

/** Both looks come from Theme.kt: a full Material scheme plus the app's own [StarColors]. */
@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStarColors provides if (dark) DarkStarColors else LightStarColors) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

/**
 * The one card: [surfaceContainer] with a hairline ring in the light theme, flat. A header (the title in titleSmall
 * SemiBold, an optional muted [subtitle] and a [trailing] slot at the right: a state chip, a switch, a [Chevron]), then
 * the [content], whose blocks are [Space.s] apart. A table of values is one block: put its [KeyValueRow]s in a
 * [KeyValueTable], which keeps them [Space.hair] apart. [ledger] is for a card that is only a table of rows ([KeyValueRow]s, a hint or two): its blocks then sit [Space.hair] apart, like
 * a [KeyValueTable]. [onClick] makes the whole card a way into something.
 */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    ledger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    // The light card is nearly the page's colour: a hairline ring sets it apart, as in the HTML report.
    val border = StarTheme.colors.ring?.let { BorderStroke(1.dp, it) }
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (title.isNotEmpty() || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        subtitle?.let { Hint(it) }
                    }
                    trailing?.invoke()
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (ledger) Space.hair else Space.s), content = content)
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, border = border, content = body)
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors, border = border, content = body)
    }
}

/** Compatible name of [KeyValueRow] for the screens not yet moved to it. */
@Composable
fun KV(key: String, value: String, color: Color = Color.Unspecified) = KeyValueRow(key, value, valueColor = color)

/** Secondary text: bodySmall in the muted colour. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) = Text(text, modifier, color = StarTheme.colors.muted, style = MaterialTheme.typography.bodySmall)

/** elapsedRealtimeNanos, refreshed periodically so ages tick on screen. */
@Composable
fun rememberMonoNow(periodMs: Long = 500): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = SystemClock.elapsedRealtimeNanos()
        }
    }
    return now
}

/** How long ago, in seconds with the unit in the app's language; "-" when there was none. */
fun age(res: Resources, nowNs: Long, atNs: Long): String =
    if (atNs == 0L) "-" else res.getString(R.string.unit_s, "%.1f".format(Locale.ROOT, ((nowNs - atNs) / 1e9).coerceAtLeast(0.0)))

@Composable
fun age(nowNs: Long, atNs: Long): String = age(LocalResources.current, nowNs, atNs)

fun Double.f(d: Int = 1): String = "%.${d}f".format(Locale.ROOT, this)
fun Float.f(d: Int = 1): String = toDouble().f(d)

/** A size with its unit in the app's language ("2.64 GB", "2.64 ГБ"); the number is formatted with Locale.ROOT. */
fun bytes(res: Resources, n: Long): String = when {
    n >= 1L shl 30 -> res.getString(R.string.unit_gb, "%.2f".format(Locale.ROOT, n / (1L shl 30).toDouble()))
    n >= 1L shl 20 -> res.getString(R.string.unit_mb, "%.1f".format(Locale.ROOT, n / (1L shl 20).toDouble()))
    n >= 1L shl 10 -> res.getString(R.string.unit_kb, "%.0f".format(Locale.ROOT, n / (1L shl 10).toDouble()))
    else -> res.getString(R.string.unit_b, n.toString())
}

@Composable
fun bytes(n: Long): String = bytes(LocalResources.current, n)

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
}
