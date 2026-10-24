package tools.apachi.starlinknav.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import tools.apachi.starlinknav.BuildConfig
import tools.apachi.starlinknav.MapLayer
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * What the screen shows of the earth: Web Mercator, north up, no tilt. Overlays draw with it in
 * screen pixels, over plain ground or over MapLibre tiles alike.
 */
class GeoView(
    /** Mercator x and y of the point at ([sx], [sy]), radians. */
    private val cx: Double,
    private val cy: Double,
    /** Screen pixels per radian of Mercator. */
    val k: Double,
    private val sx: Float,
    private val sy: Float,
) {
    fun toScreen(lat: Double, lon: Double): Offset =
        Offset((sx + (mx(lon) - cx) * k).toFloat(), (sy - (my(lat) - cy) * k).toFloat())

    val centerLat: Double get() = latOf(cy)

    fun metresPerPx(): Float = (EARTH_R * cos(Math.toRadians(centerLat)) / k).toFloat()

    /** The same view moved by [pan] pixels and zoomed by [zoom] around [focus]. */
    fun transformed(pan: Offset, zoom: Float, focus: Offset): GeoView {
        val nk = (k * zoom).coerceIn(MIN_K, MAX_K)
        // Keep the Mercator point under the focus fixed, then shift by the pan.
        val fx = cx + (focus.x - sx) / k
        val fy = cy - (focus.y - sy) / k
        val ncx = fx - (focus.x - sx) / nk - pan.x / nk
        val ncy = fy + (focus.y - sy) / nk + pan.y / nk
        return GeoView(ncx, ncy.coerceIn(-MAX_Y, MAX_Y), nk, sx, sy)
    }

    fun centredOn(lat: Double, lon: Double): GeoView = GeoView(mx(lon), my(lat), k, sx, sy)

    companion object {
        const val EARTH_R = 6_371_008.8
        private const val MAX_Y = 3.0
        /** About 400 km and 0.5 m across a phone screen. */
        private const val MIN_K = 2_000.0
        private const val MAX_K = 2e7

        fun mx(lon: Double) = Math.toRadians(lon)
        fun my(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat.coerceIn(-85.0, 85.0)) / 2))
        fun latOf(y: Double) = Math.toDegrees(2 * atan(exp(y)) - PI / 2)

        /** Shows [points] in a [w] x [h] canvas with [margin] of it left free; null without points. */
        fun fit(points: List<Pair<Double, Double>>, w: Float, h: Float, margin: Float = 0.06f, minSpanM: Double = 50.0): GeoView? {
            if (points.isEmpty() || w <= 0f || h <= 0f) return null
            val xs = points.map { mx(it.second) }
            val ys = points.map { my(it.first) }
            val midY = (ys.min() + ys.max()) / 2
            val minSpan = minSpanM / (EARTH_R * cos(Math.toRadians(latOf(midY))))
            val spanX = max(xs.max() - xs.min(), minSpan)
            val spanY = max(ys.max() - ys.min(), minSpan)
            val k = min(w * (1 - 2 * margin) / spanX, h * (1 - 2 * margin) / spanY).coerceIn(MIN_K, MAX_K)
            return GeoView((xs.min() + xs.max()) / 2, midY, k, w / 2, h / 2)
        }

        /** The view a MapLibre map shows right now, read off its own projection. */
        fun of(map: MapLibreMap): GeoView? {
            val target = map.cameraPosition.target ?: return null
            val d = 0.01
            val p0 = map.projection.toScreenLocation(target)
            val p1 = map.projection.toScreenLocation(LatLng(target.latitude, target.longitude + d))
            val k = (p1.x - p0.x) / Math.toRadians(d)
            if (!k.isFinite() || k <= 0) return null
            return GeoView(mx(target.longitude), my(target.latitude), k, p0.x, p0.y)
        }
    }
}

/**
 * Tile styles per layer: OpenFreeMap streets (dark style in the dark theme, positron in the
 * light one, as the HTML report does), EOX Sentinel-2 imagery (the same in both themes).
 */
object MapStyles {
    private const val REGULAR_DARK = "https://tiles.openfreemap.org/styles/dark"
    private const val REGULAR_LIGHT = "https://tiles.openfreemap.org/styles/positron"
    private const val EOX = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2017_3857/default/g/{z}/{y}/{x}.jpg"
    private const val EOX_ATTRIBUTION =
        "Sentinel-2 cloudless – https://s2maps.eu by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2017)"
    // Native up to zoom 14 (10 m per pixel); MapLibre enlarges beyond. The background layer shows
    // before the first tile and where the imagery has none: the theme's map ground.
    private fun satellite(ground: Color) = """{"version":8,"sources":{"s2":{"type":"raster","tiles":["$EOX"],"tileSize":256,"maxzoom":14,""" +
        """"attribution":"$EOX_ATTRIBUTION"}},"layers":[{"id":"bg","type":"background","paint":{"background-color":"${ground.css()}"}},""" +
        """{"id":"s2","type":"raster","source":"s2"}]}"""

    /** Shown on the map itself, as OpenStreetMap and EOX ask (full texts in the README). */
    fun credit(layer: MapLayer): String? = when (layer) {
        MapLayer.OFF -> null
        MapLayer.REGULAR -> "© OpenMapTiles © OpenStreetMap contributors"
        MapLayer.SATELLITE -> "Sentinel-2 cloudless 2017 by EOX (modified Copernicus data)"
    }

    fun builder(layer: MapLayer, colors: StarColors): Style.Builder? = when (layer) {
        MapLayer.OFF -> null
        MapLayer.REGULAR -> Style.Builder().fromUri(if (colors.dark) REGULAR_DARK else REGULAR_LIGHT)
        MapLayer.SATELLITE -> Style.Builder().fromJson(satellite(colors.mapGround))
    }

    private var initialised = false

    /** MapLibre comes up only when a map with tiles is shown: with every layer off it never touches the network. */
    fun init(ctx: Context) {
        if (initialised) return
        MapLibre.getInstance(ctx.applicationContext)
        // Tile servers ask apps to identify themselves rather than send a library's default.
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
                .build(),
        )
        initialised = true
    }

    private val USER_AGENT = "StarNav/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID})"
}

/**
 * A map whose [overlay] draws in screen space. With [layer] OFF it is a plain background and no
 * network is used; otherwise MapLibre tiles lie underneath. The first view shows [fit]
 * (lat, lon). [interactive] allows pan and pinch-zoom (never rotation or tilt, so north stays
 * up); [follow], when not null, keeps that point in the middle until the user moves the map,
 * which [onUserMoved] reports.
 */
@Composable
fun GeoMap(
    layer: MapLayer,
    fit: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    follow: Pair<Double, Double>? = null,
    onUserMoved: () -> Unit = {},
    overlay: DrawScope.(GeoView) -> Unit,
) {
    if (layer == MapLayer.OFF) {
        PlainMap(fit, modifier, interactive, follow, onUserMoved, overlay)
    } else {
        TileMap(layer, fit, modifier, interactive, follow, onUserMoved, overlay)
    }
}

@Composable
private fun PlainMap(
    fit: List<Pair<Double, Double>>,
    modifier: Modifier,
    interactive: Boolean,
    follow: Pair<Double, Double>?,
    onUserMoved: () -> Unit,
    overlay: DrawScope.(GeoView) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Null until the user pans or zooms; until then the view fits [fit].
    var userView by remember { mutableStateOf<GeoView?>(null) }
    val moved by rememberUpdatedState(onUserMoved)
    val fitted = remember(fit, size) { GeoView.fit(fit, size.width.toFloat(), size.height.toFloat()) }
    val base = userView ?: fitted
    val shown = follow?.let { f -> base?.centredOn(f.first, f.second) } ?: base
    val current by rememberUpdatedState(shown)
    val gestures = if (interactive) {
        Modifier.pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                current?.let { userView = it.transformed(pan, zoom, centroid) }
                moved()
            }
        }
    } else {
        Modifier
    }
    // Overlays may reach far outside (an accuracy circle of kilometres): keep them inside the map.
    Canvas(modifier.clipToBounds().background(StarTheme.colors.mapGround).mapRing().onSizeChanged { size = it }.then(gestures)) {
        shown?.let { overlay(it) }
    }
}

@Composable
private fun TileMap(
    layer: MapLayer,
    fit: List<Pair<Double, Double>>,
    modifier: Modifier,
    interactive: Boolean,
    follow: Pair<Double, Double>?,
    onUserMoved: () -> Unit,
    overlay: DrawScope.(GeoView) -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var view by remember { mutableStateOf<GeoView?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val moved by rememberUpdatedState(onUserMoved)
    val colors = StarTheme.colors
    val paint = mapPaint(layer)
    // A change of theme recreates the activity, so the ground colour shown while tiles load is
    // read once here. TextureView: plays well inside scrolling layouts and under Compose overlays.
    val mapView = remember {
        MapStyles.init(ctx)
        val options = MapLibreMapOptions.createFromAttributes(ctx).textureMode(true).foregroundLoadColor(paint.ground.toArgb())
        MapView(ctx, options).also { it.onCreate(null) }
    }
    DisposableEffect(lifecycle, mapView) {
        var state = Lifecycle.State.CREATED
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
            state = e.targetState
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                setRotateGesturesEnabled(false)
                setTiltGesturesEnabled(false)
                setCompassEnabled(false)
                if (!interactive) setAllGesturesEnabled(false)
                // The credit is drawn as text below; the logo would sit on the scale bar.
                setLogoEnabled(false)
                setAttributionEnabled(false)
            }
            m.addOnCameraMoveListener { view = GeoView.of(m) }
            m.addOnCameraIdleListener { view = GeoView.of(m) }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) moved()
            }
            map = m
        }
    }
    // The street style follows the theme, so a change of either restyles the map (the camera stays).
    LaunchedEffect(map, layer, colors.dark) {
        val m = map ?: return@LaunchedEffect
        MapStyles.builder(layer, colors)?.let { m.setStyle(it) { view = GeoView.of(m) } }
    }
    // Fit once the map has a size; a later change of points (another session) fits again.
    LaunchedEffect(map, fit.isEmpty()) {
        val m = map ?: return@LaunchedEffect
        if (fit.isEmpty()) return@LaunchedEffect
        mapView.post {
            val camera = if (fit.size == 1) {
                CameraPosition.Builder().target(LatLng(fit[0].first, fit[0].second)).zoom(15.0).build()
            } else {
                val bounds = LatLngBounds.Builder().includes(fit.map { LatLng(it.first, it.second) }).build()
                val pad = (min(mapView.width, mapView.height) * 0.06f).toInt()
                m.getCameraForLatLngBounds(bounds, intArrayOf(pad, pad, pad, pad))
            }
            camera?.let { m.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
            view = GeoView.of(m)
        }
    }
    LaunchedEffect(map, follow) {
        val m = map ?: return@LaunchedEffect
        val f = follow ?: return@LaunchedEffect
        m.moveCamera(CameraUpdateFactory.newLatLng(LatLng(f.first, f.second)))
        view = GeoView.of(m)
    }
    val tm = rememberTextMeasurer()
    val credit = MapStyles.credit(layer)
    Box(modifier.clipToBounds().mapRing()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // No pointer input: touches go through to the map.
        Canvas(Modifier.fillMaxSize()) {
            view?.let { overlay(it) }
            credit?.let { text ->
                val layout = tm.measure(text, TextStyle(color = paint.creditInk, fontSize = 9.sp, shadow = Shadow(paint.creditHalo, Offset.Zero, 5f)))
                drawText(layout, topLeft = Offset(size.width - layout.size.width - 6.dp.toPx(), size.height - layout.size.height - 3.dp.toPx()))
            }
        }
    }
}

private fun layerLabel(l: MapLayer) = when (l) {
    MapLayer.OFF -> R.string.map_layer_off
    MapLayer.REGULAR -> R.string.map_layer_regular
    MapLayer.SATELLITE -> R.string.map_layer_satellite
}

/**
 * Plain / Map / Satellite for every map at once, with who serves the tiles when there are any: the shared [SegmentedRow].
 * With [onExpand] (the Sessions track map, not moved to the shared look yet) the choices are still chips beside the
 * "Full screen" button, which would not leave the segments room.
 */
@Composable
fun MapLayerBar(layer: MapLayer, onExpand: (() -> Unit)? = null) {
    val choose = { l: MapLayer -> App.instance.settings.update { it.copy(mapLayer = l) } }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (onExpand == null) {
            SegmentedRow(MapLayer.entries, layer, { choose(it) }) { stringResource(layerLabel(it)) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (l in MapLayer.entries) {
                    FilterChip(
                        selected = l == layer,
                        onClick = { choose(l) },
                        label = { Text(stringResource(layerLabel(l))) },
                    )
                }
                Spacer(Modifier.weight(1f))
                LowButton(stringResource(R.string.map_full_screen), onExpand)
            }
        }
        when (layer) {
            MapLayer.OFF -> Unit
            MapLayer.REGULAR -> Hint(stringResource(R.string.map_tiles_hint, stringResource(R.string.map_provider_regular)))
            MapLayer.SATELLITE -> Hint(stringResource(R.string.map_tiles_hint, stringResource(R.string.map_provider_satellite)))
        }
    }
}

/** A map over the whole screen, where it can be moved and zoomed. */
@Composable
fun FullScreenMap(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Edge to edge, so the window covers the bar strips too (else the activity shows there).
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LowButton(stringResource(R.string.map_close), onClose)
                }
                content()
            }
        }
    }
}
