package tools.apachi.starlinknav.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import tools.apachi.starlinknav.MapLayer
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.ThemeMode
import java.util.Locale

/*
 * Every colour of the app lives in this file. Two looks, both written out in full:
 *  - dark: the original blue-grey look (background #101418, cards #182029);
 *  - light: neutral and warm (page #F9F9F7, cards #FCFCFB
 *    with a 1 dp ring, ink #0B0B0B).
 * Text colours keep >= 4.5:1 on the surfaces they sit on in both. Marks and lines follow the
 * dataviz rules (>= 3:1, or a label next to them). The categorical set of the live map was run
 * through the dataviz validator: see [StarColors.sources].
 * Draw code (DrawScope lambdas, label, scaleBar...) cannot read the composition: resolve
 * [StarTheme.colors] and [mapPaint] in the composable body and pass them in.
 */

/** Colours the Material scheme has no role for: status, series, report, chart chrome and maps. */
@Immutable
class StarColors(
    val dark: Boolean,
    // Status and text. They are text colours too (>= 4.5:1 on background and cards).
    val good: Color,
    val warn: Color,
    val bad: Color,
    /** Label on a [bad] button. */
    val onBad: Color,
    /** Hints, secondary text, axis labels, "unknown". */
    val muted: Color,
    /** The dish's series and the app's brand colour (Material primary). */
    val dish: Color,
    /** The phone's series (Material secondary). */
    val phone: Color,
    /** Error classes of the accuracy report, index 0 = under 5 m (best), 4 = 50 m and more. */
    val accuracy: List<Color>,
    val noReference: Color,
    val cutoff: Color,
    /** Phone and dish more than a kilometre apart: one of them spoofed. */
    val conflict: Color,
    val interference: Color,
    val dishGnss: Color,
    val mockLane: Color,
    val rebootLane: Color,
    val fallbackLane: Color,
    /** Report lanes of the inertial filter (as the HTML report's `.lane.dr` blue and `.lane.side` violet). */
    val deadReckoningLane: Color,
    val sidewaysLane: Color,
    // Chart chrome.
    val grid: Color,
    val laneLine: Color,
    val cursor: Color,
    /** 1 dp outline of the cards; null where the card colour already stands out. */
    val ring: Color?,
    // Maps.
    /** The plain map's ground, the colour tiles load over, the satellite style's background. */
    val mapGround: Color,
    /** Marks, labels and lines on the plain map. */
    val mapInk: Color,
    /** Ring or fill that sets a [mapInk] mark apart from the ground. */
    val mapInkInverse: Color,
    /**
     * The live map's position sources by key: one hue per source, so a colour follows the
     * source whatever the order of the list. Dish sources are drawn as squares and phone
     * sources as circles, and every mark carries its name.
     */
    val sources: Map<String, Color>,
) {
    fun source(key: String): Color = sources.getValue(key)
}

/*
 * The ten accuracy-ramp values: dark steps are 600/450/350/250/100 of the validated blue
 * ramp (the most accurate class darkest, so worse stretches stand out against the dark
 * ground); light steps are 250/350/450/550/700 (the most accurate lightest).
 */
private val DarkAccuracy = listOf(Color(0xFF184F95), Color(0xFF2A78D6), Color(0xFF5598E7), Color(0xFF86B6EF), Color(0xFFCDE2FB))
private val LightAccuracy = listOf(Color(0xFF86B6EF), Color(0xFF5598E7), Color(0xFF2A78D6), Color(0xFF1C5CAB), Color(0xFF0D366B))

/*
 * Live-map sources, dataviz categorical palette, validated with
 * `validate_palette.js "<hex,...>" --mode dark|light` in this order (adjacent pairs), dark
 * against the map ground #0B0F13 (and the card #1a1a19), light against #FCFCFB:
 *  - light: all hard gates pass (worst adjacent CVD dE 9.1, normal 16.3); aqua, yellow and
 *    magenta are under 3:1, which the relief rule allows because every mark carries its name.
 *  - dark: all gates pass (worst adjacent CVD dE 8.4, normal 15.8). Two values differ from the
 *    palette's dark column: the violet (#9085E9 was 1.9 dE from dish Auto's blue under
 *    protanopia) is a darker, slightly redder violet; and gms_fused is the palette's own dark
 *    green #008300, because the lighter #2FA82F sat only 8.5 dE (normal vision) from phone_gps
 *    and the phone sources are all circles at one spot. With `--pairs all` the dark set still
 *    fails on aqua vs magenta under deuteranopia (the palette's hues cause it): names and the
 *    square/circle shapes carry those.
 * Over satellite imagery (dark) the live map uses the dark set in the light theme too.
 */
private val DarkSources = mapOf(
    "dish_auto" to Color(0xFF3987E5),
    "dish_starlink" to Color(0xFF7F4CC4),
    "dish_gps" to Color(0xFFD95926),
    "phone_gps" to Color(0xFF199E70),
    "phone_network" to Color(0xFFC98500),
    "phone_fused" to Color(0xFFD55181),
    "gms_fused" to Color(0xFF008300),
)
private val LightSources = mapOf(
    "dish_auto" to Color(0xFF2A78D6),
    "dish_starlink" to Color(0xFF4A3AA7),
    "dish_gps" to Color(0xFFEB6834),
    "phone_gps" to Color(0xFF1BAF7A),
    "phone_network" to Color(0xFFEDA100),
    "phone_fused" to Color(0xFFE87BA4),
    "gms_fused" to Color(0xFF008300),
)

internal val DarkStarColors = StarColors(
    dark = true,
    good = Color(0xFF7BD88F),
    warn = Color(0xFFFFB454),
    bad = Color(0xFFFF6B6B),
    onBad = Color(0xFF2B0A0A),
    muted = Color(0xFF8A96A3),
    dish = Color(0xFF5BC0EB),
    phone = Color(0xFFFFB454),
    accuracy = DarkAccuracy,
    noReference = Color(0xFF6B6A64),
    cutoff = Color(0xFFD03B3B),
    conflict = Color(0xFFEC835A),
    interference = Color(0xFFEC835A),
    dishGnss = Color(0xFFFAB219),
    mockLane = Color(0xFF8C8A93),
    rebootLane = Color(0xFFC3C2B7),
    fallbackLane = Color(0xFFC98500),
    deadReckoningLane = Color(0xFF3987E5),
    sidewaysLane = Color(0xFF9D7BEA),
    grid = Color(0xFF222B35),
    laneLine = Color(0xFF2A3440),
    cursor = Color(0xB3FFFFFF),
    ring = null,
    mapGround = Color(0xFF0B0F13),
    mapInk = Color(0xFFFFFFFF),
    mapInkInverse = Color(0xFF0B0F13),
    sources = DarkSources,
)

internal val LightStarColors = StarColors(
    dark = false,
    good = Color(0xFF006300),
    warn = Color(0xFF946200),
    bad = Color(0xFFC42B2B),
    onBad = Color(0xFFFFFFFF),
    muted = Color(0xFF5F6B77),
    dish = Color(0xFF0A6FA3),
    phone = Color(0xFFA8620A),
    accuracy = LightAccuracy,
    noReference = Color(0xFFA3A19A),
    cutoff = Color(0xFFD03B3B),
    conflict = Color(0xFFEC835A),
    interference = Color(0xFFEC835A),
    dishGnss = Color(0xFFFAB219),
    mockLane = Color(0xFF9A98A0),
    rebootLane = Color(0xFF52514E),
    fallbackLane = Color(0xFFA86F00),
    deadReckoningLane = Color(0xFF2A78D6),
    sidewaysLane = Color(0xFF7C4DDB),
    grid = Color(0xFFE1E0D9),
    laneLine = Color(0xFFE1E0D9),
    cursor = Color(0xFF52514E),
    ring = Color(0x1A0B0B0B),
    mapGround = Color(0xFFFCFCFB),
    mapInk = Color(0xFF0B0B0B),
    mapInkInverse = Color(0xFFFCFCFB),
    sources = LightSources,
)

val LocalStarColors = staticCompositionLocalOf { DarkStarColors }

object StarTheme {
    val colors: StarColors
        @Composable @ReadOnlyComposable get() = LocalStarColors.current
}

// Constants that do not depend on the theme: ink and casing over map tiles, which are readable
// on dark, light (positron) and satellite tiles alike. The HTML report does the same.

/** Dark outline under tracks, arrows and marks drawn over map tiles (the report's `.casing`). */
internal val Casing = Color(0xB3000000)
/** Shadow behind text over map tiles. */
internal val Halo = Color.Black
/** The "!" on the red cut-off disc, in both themes (4.7:1). */
internal val OnCutoff = Color.White
/** Marks, labels and lines over map tiles, whatever the theme. */
internal val TileInk = Color.White
/** The ring or fill that sets a [TileInk] mark apart. */
internal val TileInkInverse = Color(0xFF0B0F13)
private val CreditOnDark = Color(0xFFDDE3EA)
private val CreditOnLight = Color(0xFF333333)

/**
 * What a map overlay draws with, a function of (layer, theme). Over the plain map (layer OFF)
 * it is the theme's ground and ink, without casing. Over tiles (REGULAR, SATELLITE) the ink is
 * constant white with a black casing and halo, which reads on any tile.
 */
@Immutable
class MapPaint(
    val ground: Color,
    val ink: Color,
    /** Ring or fill that sets an [ink] mark apart (window-edge markers, start and end discs). */
    val inkInverse: Color,
    /** Scale bar over the plain map; over tiles it is [ink]. */
    val scaleInk: Color,
    /** True over tiles: draw the casing under lines and marks and a halo behind text. */
    val casing: Boolean,
    val creditInk: Color,
    val creditHalo: Color,
    /** Live-map source colours: the dark set over satellite imagery, the theme's otherwise. */
    val sources: Map<String, Color>,
    /** Opacity of trails and accuracy-circle outlines (supplementary marks: stronger on light grounds). */
    val trailAlpha: Float,
    val ringAlpha: Float,
    /** "%1$s m" and "%1$s km" in the app's language (the scale bar's label; draw code cannot read the composition). */
    val unitM: String,
    val unitKm: String,
) {
    fun source(key: String): Color = sources.getValue(key)
}

fun StarColors.mapPaint(layer: MapLayer, unitM: String, unitKm: String): MapPaint =
    if (layer == MapLayer.OFF) {
        MapPaint(
            mapGround, mapInk, mapInkInverse, muted, casing = false, creditInk = mapInk, creditHalo = mapGround,
            sources = sources, trailAlpha = trailAlpha(), ringAlpha = ringAlpha(), unitM = unitM, unitKm = unitKm,
        )
    } else {
        // Positron is light: dark credit text with a white halo there; dark and satellite tiles
        // take light text with a black halo.
        val onLightTiles = !dark && layer == MapLayer.REGULAR
        MapPaint(
            mapGround, TileInk, TileInkInverse, TileInk, casing = true,
            creditInk = if (onLightTiles) CreditOnLight else CreditOnDark,
            creditHalo = if (onLightTiles) Color.White else Halo,
            // Satellite imagery is dark whatever the theme: marks made for a dark ground.
            sources = if (layer == MapLayer.SATELLITE) DarkSources else sources,
            trailAlpha = trailAlpha(), ringAlpha = ringAlpha(), unitM = unitM, unitKm = unitKm,
        )
    }

// Trails and accuracy rings are supplementary marks. On the light ground the yellow, aqua and
// magenta sources reach only 1.6-1.8:1 at the dark theme's opacity, so they are drawn stronger.
private fun StarColors.trailAlpha() = if (dark) 0.8f else 0.9f
private fun StarColors.ringAlpha() = if (dark) 0.65f else 0.8f

@Composable
fun mapPaint(layer: MapLayer): MapPaint {
    val colors = StarTheme.colors
    val unitM = stringResource(R.string.live_unit_m)
    val unitKm = stringResource(R.string.live_unit_km)
    return remember(colors, layer, unitM, unitKm) { colors.mapPaint(layer, unitM, unitKm) }
}

/** The card ring (light theme only) around a map, which would otherwise melt into the page. */
@Composable
fun Modifier.mapRing(): Modifier = StarTheme.colors.ring?.let { border(1.dp, it) } ?: this

/** A 1 dp hairline along the top edge, in the card ring colour (light theme only). */
@Composable
fun Modifier.topRing(): Modifier {
    val ring = StarTheme.colors.ring ?: return this
    return drawWithContent {
        drawContent()
        drawLine(ring, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
    }
}

/** "#RRGGBB" for a MapLibre style. */
fun Color.css(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)

// ---- Theme mode -------------------------------------------------------------------------

fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun rememberDarkTheme(mode: ThemeMode): Boolean = mode.isDark(isSystemInDarkTheme())

/** Whether the configuration is in night mode (`Configuration.isNightModeActive` is API 30). */
val Configuration.nightModeOn: Boolean
    get() = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** The window background (before the first frame, and behind the bars) for the look, from the same `background` role as the Compose scheme. */
fun windowBackgroundColor(dark: Boolean): Int = (if (dark) DarkScheme else LightScheme).background.toArgb()

/**
 * Tells Android the app's night mode, so the window background, the splash screen,
 * `values-night` resources and `isSystemInDarkTheme()` all follow the choice. The system
 * remembers it across restarts and recreates the activity when it changes; the app also calls
 * this at start in case the system lost it.
 *
 * `UiModeManager.setApplicationNightMode` is Android 12 (API 31). Before it the choice lives in the app only:
 * [rememberDarkTheme] takes the explicit Light / Dark from the setting, and `MainActivity` sets the window background
 * and the system bar icons from the composed look. What stays with the system there: the cold-start window (before the
 * first frame) and Android's own dialogs.
 */
fun ThemeMode.applyToApp(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val ui = context.getSystemService(UiModeManager::class.java) ?: return
    ui.setApplicationNightMode(
        when (this) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        },
    )
}

/**
 * A Compose Dialog is a window of its own, which `enableEdgeToEdge` does not style: put this in
 * its content so the status and navigation bar icons suit the theme.
 */
@Composable
fun DialogSystemBars() {
    val view = LocalView.current
    val dark = StarTheme.colors.dark
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        // A full-screen opaque dialog needs no dim: it would darken what shows in the bar strips.
        window.setDimAmount(0f)
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

// ---- Material schemes: every role written out, none left to the (purple-grey) defaults -------

// Fixed roles are the same in both schemes.
private val PrimaryFixed = Color(0xFFC6E8F8)
private val PrimaryFixedDim = Color(0xFF7DCDEE)
private val OnPrimaryFixed = Color(0xFF001E2C)
private val OnPrimaryFixedVariant = Color(0xFF004B6B)
private val SecondaryFixed = Color(0xFFFFDDB8)
private val SecondaryFixedDim = Color(0xFFFFB454)
private val OnSecondaryFixed = Color(0xFF2B1700)
private val OnSecondaryFixedVariant = Color(0xFF653C00)
private val TertiaryFixed = Color(0xFFBCF2C8)
private val TertiaryFixedDim = Color(0xFF7BD88F)
private val OnTertiaryFixed = Color(0xFF002108)
private val OnTertiaryFixedVariant = Color(0xFF005320)

internal val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF5BC0EB),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF0F4C6A),
    onPrimaryContainer = Color(0xFFC6E8F8),
    inversePrimary = Color(0xFF0A6FA3),
    secondary = Color(0xFFFFB454),
    onSecondary = Color(0xFF3F2600),
    secondaryContainer = Color(0xFF2B4257),
    onSecondaryContainer = Color(0xFFD6E6F3),
    tertiary = Color(0xFF7BD88F),
    onTertiary = Color(0xFF00390F),
    tertiaryContainer = Color(0xFF1F5130),
    onTertiaryContainer = Color(0xFFBCF2C8),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE1E6EB),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE1E6EB),
    surfaceVariant = Color(0xFF2A333D),
    onSurfaceVariant = Color(0xFFB4BFCA),
    surfaceTint = Color(0xFF5BC0EB),
    inverseSurface = Color(0xFFE1E6EB),
    inverseOnSurface = Color(0xFF1B2128),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0A0A),
    errorContainer = Color(0xFF5C1F1F),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF7C8996),
    outlineVariant = Color(0xFF35414D),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2B3540),
    surfaceDim = Color(0xFF101418),
    surfaceContainerLowest = Color(0xFF0B0F13),
    surfaceContainerLow = Color(0xFF141A20),
    surfaceContainer = Color(0xFF182029),
    surfaceContainerHigh = Color(0xFF1E2731),
    surfaceContainerHighest = Color(0xFF26313C),
    primaryFixed = PrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    onPrimaryFixed = OnPrimaryFixed,
    onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    onSecondaryFixed = OnSecondaryFixed,
    onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim,
    onTertiaryFixed = OnTertiaryFixed,
    onTertiaryFixedVariant = OnTertiaryFixedVariant,
)

internal val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0A6FA3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDE6F5),
    onPrimaryContainer = Color(0xFF00344D),
    inversePrimary = Color(0xFF5BC0EB),
    secondary = Color(0xFFA8620A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E3F0),
    onSecondaryContainer = Color(0xFF0B0B0B),
    tertiary = Color(0xFF006300),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDEBCB),
    onTertiaryContainer = Color(0xFF00210A),
    background = Color(0xFFF9F9F7),
    onBackground = Color(0xFF0B0B0B),
    surface = Color(0xFFF9F9F7),
    onSurface = Color(0xFF0B0B0B),
    surfaceVariant = Color(0xFFE9E8E3),
    onSurfaceVariant = Color(0xFF52514E),
    surfaceTint = Color(0xFF0A6FA3),
    inverseSurface = Color(0xFF1A1A19),
    inverseOnSurface = Color(0xFFF9F9F7),
    error = Color(0xFFC42B2B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF5A0E0E),
    outline = Color(0xFF898781),
    outlineVariant = Color(0xFFC3C2B7),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCFCFB),
    surfaceDim = Color(0xFFE3E2DC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F6F3),
    surfaceContainer = Color(0xFFFCFCFB),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE1E0D9),
    primaryFixed = PrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    onPrimaryFixed = OnPrimaryFixed,
    onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    onSecondaryFixed = OnSecondaryFixed,
    onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim,
    onTertiaryFixed = OnTertiaryFixed,
    onTertiaryFixedVariant = OnTertiaryFixedVariant,
)
