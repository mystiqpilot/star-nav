package tools.apachi.starlinknav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import tools.apachi.starlinknav.R
import kotlin.math.max
import kotlin.math.min

/*
 * The shared component system: a tightened Material 3, in the colours of Theme.kt,
 * dense and data first. Screens compose these and do not size, space or colour things by hand:
 *  - spacing from [Space], text from MaterialTheme.typography (a role, never a literal size), colour from
 *    MaterialTheme.colorScheme / [StarTheme] (through [Status] for anything that means a state);
 *  - a state is never colour alone: [Status] always draws an icon, and the words are the label next to it;
 *  - display rows ([KeyValueRow]) are dense and have no minimum height; only what is tapped reserves [Space.touch].
 * Section, KeyValueRow (KV), Hint and the formatting helpers are in Widgets.kt.
 */

/** The spacing scale: a 4 dp grid with a 2 dp hairline step. */
object Space {
    /** Between the rows of a key-value table, so they read as one tight ledger. */
    val hair = 2.dp

    /** Between a label and its own sub-content. */
    val xs = 4.dp

    /** Between distinct blocks inside a card; between buttons of a row. */
    val s = 8.dp

    /** The screen gutter on all four sides, the gap between cards and the padding inside a card. */
    val m = 12.dp

    /** Reserved: groupings that really need air. Nothing in content is more than this. */
    val l = 16.dp

    /** Padding of a centred [StateView] only. */
    val xl = 24.dp

    /** The minimum height of anything tapped (switch rows, radio rows, [ListRow] with a click); display rows stay dense. */
    val touch = 48.dp
}

// Type: the M3 roles of MaterialTheme.typography. The few rules that are not a plain role are here.

/** The monospace look every value read as data has: coordinates, distances, ages, rates, counts, versions, addresses. */
fun TextStyle.mono(): TextStyle = copy(fontFamily = FontFamily.Monospace)

/** The coordinate line: titleMedium sized up a little, monospace, SemiBold. The only place a size is written out. */
val coordinateStyle: TextStyle
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)

/** Latitude / longitude read as data: monospace, large. */
@Composable
fun CoordinateText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) =
    Text(text, modifier, color, style = coordinateStyle)

// Status: icon + words, never colour alone.

/** The five states a screen can show. Colours come from [StarColors], the icon is the second cue. */
enum class Status { OK, WARN, BAD, NEUTRAL, INFO }

/** good / warn / bad; NEUTRAL and INFO are the muted text colour. */
@Composable
@ReadOnlyComposable
fun Status.color(): Color {
    val c = StarTheme.colors
    return when (this) {
        Status.OK -> c.good
        Status.WARN -> c.warn
        Status.BAD -> c.bad
        Status.NEUTRAL, Status.INFO -> c.muted
    }
}

/** check_circle / warning / error / remove_circle / info (Material icon outlines, drawn here: the icon library is not a dependency). */
val Status.icon: ImageVector
    get() = when (this) {
        Status.OK -> StatusIcons.ok
        Status.WARN -> StatusIcons.warn
        Status.BAD -> StatusIcons.bad
        Status.NEUTRAL -> StatusIcons.neutral
        Status.INFO -> StatusIcons.info
    }

private object StatusIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()

    val ok by lazy {
        icon("check_circle", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z")
    }
    val warn by lazy { icon("warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z") }
    val bad by lazy {
        icon("error", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-2h2v2zM13,13h-2L11,7h2v6z")
    }
    val neutral by lazy {
        icon("remove_circle", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM17,13H7v-2h10v2z")
    }
    val info by lazy {
        icon("info", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2L11,7h2v2z")
    }
    val chevron by lazy { icon("chevron_right", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z") }
}

@Composable
private fun Status.words(): String = stringResource(
    when (this) {
        Status.OK -> R.string.ui_status_ok
        Status.WARN -> R.string.ui_status_warn
        Status.BAD -> R.string.ui_status_bad
        Status.NEUTRAL -> R.string.ui_status_neutral
        Status.INFO -> R.string.ui_status_info
    },
)

/** The status icon on its own, [size] square, in the status colour (or [tint]). Read out as the status' name. */
@Composable
fun StatusIcon(status: Status, modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = status.color()) {
    Icon(status.icon, contentDescription = status.words(), modifier = modifier.size(size), tint = tint)
}

/**
 * The icon sized to a text style (a little over its font size) and centred on the style's first line, so it sits right
 * beside the text at any font scale, also when the text wraps below it.
 */
@Composable
private fun StatusMark(status: Status, style: TextStyle) {
    val density = LocalDensity.current
    val icon = with(density) { (style.fontSize.value * 1.15f).sp.toDp() }
    val line = if (style.lineHeight.isSpecified) with(density) { style.lineHeight.toDp() } else icon
    Box(Modifier.height(maxOf(line, icon)).width(icon), contentAlignment = Alignment.Center) { StatusIcon(status, size = icon) }
}

/**
 * The compact state chip: the status icon and its words in the status colour. For the state of one item (a setup step, a
 * probe outcome, the last attempt), as opposed to [StatusLine], which is a card's one primary line.
 */
@Composable
fun StatusPill(status: Status, label: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelMedium
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        StatusMark(status, style)
        Text(label, style = style, color = status.color())
    }
}

/**
 * A card's one-line primary status: the icon, then the words in titleSmall SemiBold in the status colour. [detail] is the
 * explanation under it (several reasons, one per line), in the body text colour and aligned under the words: the colour
 * and the icon belong to the status line only.
 */
@Composable
fun StatusLine(status: Status, text: String, modifier: Modifier = Modifier, detail: String? = null) {
    val style = MaterialTheme.typography.titleSmall
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        StatusMark(status, style)
        Column {
            Text(text, style = style, fontWeight = FontWeight.SemiBold, color = status.color())
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

// Rows

/**
 * One label and its value on a dense, aligned ledger row. The label is the muted proportional text; when the two do not fit
 * on one line the label wraps so that the value keeps one line, down to 35 % of the row (a longer value wraps instead, beside
 * a label of up to 55 %). The value is monospace (it is data; [mono] off for a sentence) and right-aligned. [status] puts its icon in front of the value and colours the words with it; [valueColor] colours the value
 * alone (a dimmed value, not a state). No minimum height: a display row stays 20 dp tall. Rows of one table go in a
 * [KeyValueTable].
 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    status: Status? = null,
    mono: Boolean = true,
    valueColor: Color = Color.Unspecified,
) {
    val muted = StarTheme.colors.muted
    val style = MaterialTheme.typography.bodyMedium
    val valueStyle = if (mono) style.mono() else style
    val color = if (valueColor != Color.Unspecified) valueColor else status?.color() ?: Color.Unspecified
    // A value that wraps reads better left-aligned in its column than right-aligned line by line.
    var wrapped by remember { mutableStateOf(false) }
    Layout(
        content = {
            Text(label, style = style, color = muted)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                if (status != null) StatusMark(status, style)
                Text(
                    value, style = valueStyle, color = color, textAlign = if (wrapped) TextAlign.Start else TextAlign.End,
                    onTextLayout = { wrapped = it.lineCount > 1 },
                )
            }
        },
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = Space.s.roundToPx()
        val (labelItem, valueItem) = measurables
        // Both on one line when they fit. Else the value keeps its one line and the label wraps, as long as the label
        // still gets 35 %; only a value longer than that wraps, next to a label of its own width up to 55 %. The value
        // sits at the right edge.
        val labelFull = labelItem.maxIntrinsicWidth(Constraints.Infinity)
        val valueFull = valueItem.maxIntrinsicWidth(Constraints.Infinity)
        val labelWidth = when {
            labelFull + gap + valueFull <= width -> labelFull
            width - gap - valueFull >= (width * LABEL_MIN_SHARE).toInt() -> width - gap - valueFull
            else -> min(labelFull, (width * LABEL_SHARE).toInt())
        }
        val valueWidth = min(valueFull, (width - labelWidth - gap).coerceAtLeast(0))
        val labelPlaced = labelItem.measure(Constraints(maxWidth = labelWidth))
        val valuePlaced = valueItem.measure(Constraints(maxWidth = valueWidth))
        layout(width, max(labelPlaced.height, valuePlaced.height)) {
            labelPlaced.placeRelative(0, 0)
            valuePlaced.placeRelative(width - valuePlaced.width, 0)
        }
    }
}

/** How much of the row the label may take before it wraps. */
private const val LABEL_SHARE = 0.55f

/** The least a label keeps when it wraps so that the value stays on one line. */
private const val LABEL_MIN_SHARE = 0.35f

/** The rows of one key-value table: a tight ledger, [Space.hair] apart. */
@Composable
fun KeyValueTable(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.hair), content = content)
}

/**
 * A dense list item: [leading] mark, the [content] lines and a [trailing] slot, at least [Space.touch] tall when it is
 * clickable (a display row stays as tall as its text). Several go in one [Section], separated by [ListDivider].
 */
@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = if (onClick != null) modifier.heightIn(min = Space.touch).clickable(role = Role.Button, onClick = onClick) else modifier
    Row(base.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        leading?.invoke()
        Column(Modifier.weight(1f), content = content)
        trailing?.invoke()
    }
}

/** Between the rows of a dense list inside a card; between cards use [Space.m] and no divider. */
@Composable
fun ListDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(vertical = Space.xs), color = MaterialTheme.colorScheme.outlineVariant)
}

/** The chevron that marks a card or row as a way into something: the trailing slot of a clickable [Section] or [ListRow]. */
@Composable
fun Chevron(modifier: Modifier = Modifier) {
    Icon(StatusIcons.chevron, contentDescription = null, modifier = modifier.size(Space.l + Space.xs), tint = StarTheme.colors.muted)
}

// Legends

/** A swatch of [color] (a square, or a circle for a phone-side source) and its words. The swatch is the non-colour cue. */
@Composable
fun LegendItem(color: Color, label: String, modifier: Modifier = Modifier, circle: Boolean = false) {
    LegendItem(label, modifier) {
        Box(Modifier.size(LEGEND_MARK).background(color, if (circle) CircleShape else RoundedCornerShape(2.dp)))
    }
}

/**
 * A legend row with its own [mark] (the map's ring, square or circle) and, under the [label], optional [detail] data
 * (monospace). Legends are small: labelSmall for a plain label, bodySmall for the data.
 */
@Composable
fun LegendItem(label: String, modifier: Modifier = Modifier, detail: String? = null, labelColor: Color = Color.Unspecified, mark: @Composable () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Box(Modifier.size(LEGEND_MARK + Space.xs), contentAlignment = Alignment.Center) { mark() }
        Column {
            if (detail == null) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor)
            } else {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = labelColor)
                Text(detail, style = MaterialTheme.typography.bodySmall.mono(), color = StarTheme.colors.muted)
            }
        }
    }
}

private val LEGEND_MARK = 10.dp

// Actions: the emphasis ladder. One primary per action group; destructive is a ConfirmDialog with a typed word.

/** The group's one main, affirmative action: a filled button. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    Button(onClick, modifier, enabled, contentPadding = contentPadding) { ButtonText(text, maxLines) }
}

/** Any other action of the group: an outlined button. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    /** [StarColors.bad] for the button that opens a destructive command's confirmation. */
    color: Color = Color.Unspecified,
) {
    OutlinedButton(
        onClick, modifier, enabled, contentPadding = contentPadding,
        colors = if (color.isSpecified) ButtonDefaults.outlinedButtonColors(contentColor = color) else ButtonDefaults.outlinedButtonColors(),
    ) { ButtonText(text, maxLines) }
}

/** The least emphasis: a text button (a nudge, "use the learned value", a dialog's dismiss). */
@Composable
fun LowButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Color.Unspecified) {
    TextButton(onClick, modifier, enabled, colors = if (color.isSpecified) ButtonDefaults.textButtonColors(contentColor = color) else ButtonDefaults.textButtonColors()) {
        ButtonText(text, Int.MAX_VALUE)
    }
}

@Composable
private fun RowScope.ButtonText(text: String, maxLines: Int) = Text(text, textAlign = TextAlign.Center, maxLines = maxLines)

/** A group of buttons, [Space.s] apart, wrapping to the next line when they do not fit. */
@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(top = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        content = content,
    )
}

// Single choice

/**
 * One choice out of a few, as segments with the same look everywhere: the selected one carries a check mark ([showCheck]).
 * It adapts to the room: with the check when the labels fit, without it when only that makes them fit, and as one
 * [Space.touch]-tall radio row per choice at a large font scale or a narrow screen, so a label is never cut.
 */
@Composable
fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    showCheck: Boolean = true,
    enabled: Boolean = true,
    label: @Composable (T) -> String,
) {
    val labels = options.map { label(it) }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val segment = maxWidth / options.size
        val widest = labels.maxOf { with(LocalDensity.current) { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width.toDp() } }
        // Segment padding is 12 dp each side; the check adds its 18 dp icon and the 8 dp before the label.
        val padding = Space.m * 2
        val check = 18.dp + Space.s
        val checked = showCheck && widest + padding + check <= segment
        if (widest + padding <= segment) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, option ->
                    SegmentedButton(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        enabled = enabled,
                        icon = if (checked) {
                            { SegmentedButtonDefaults.Icon(active = option == selected) }
                        } else {
                            {}
                        },
                    ) { Text(labels[i], maxLines = 1, softWrap = false) }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                options.forEachIndexed { i, option ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Space.touch)
                            .selectable(selected = option == selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null, enabled = enabled)
                        Text(labels[i], Modifier.padding(start = Space.m))
                    }
                }
            }
        }
    }
}

// States

/** What a view that has no data to show says: still loading, nothing there, or why it could not read. */
sealed interface UiState {
    /** A centred progress indicator and an optional [label]. */
    data class Loading(val label: String? = null) : UiState

    /** Nothing to show yet, with the words for it and an optional [action] (a way to change that). */
    data class Empty(val message: String, val action: (@Composable () -> Unit)? = null) : UiState

    /**
     * A failure or an unreachable device: [status] says how bad (BAD for an error, WARN for a device that does not
     * answer), [message] what happened, [detail] the technical text and [action] a retry or the way out.
     */
    data class Error(val status: Status, val message: String, val detail: String? = null, val action: (@Composable () -> Unit)? = null) : UiState
}

/**
 * The shared loading / empty / error view. Loading and empty are centred in [Space.xl] padding; an error is a left-aligned
 * status chip with its words, so it can sit inside a card as well as fill a page.
 */
@Composable
fun StateView(state: UiState, modifier: Modifier = Modifier) {
    when (state) {
        is UiState.Loading -> Column(
            modifier.fillMaxWidth().padding(Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            val words = stringResource(R.string.ui_loading)
            CircularProgressIndicator(Modifier.size(Space.xl + Space.s).semantics { contentDescription = words })
            state.label?.let { Hint(it) }
        }
        is UiState.Empty -> Column(
            modifier.fillMaxWidth().padding(Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(state.message, style = MaterialTheme.typography.bodyMedium, color = StarTheme.colors.muted, textAlign = TextAlign.Center)
            state.action?.invoke()
        }
        is UiState.Error -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            StatusPill(state.status, state.message)
            state.detail?.let { Hint(it) }
            state.action?.invoke()
        }
    }
}
