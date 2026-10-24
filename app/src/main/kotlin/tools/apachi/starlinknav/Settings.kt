package tools.apachi.starlinknav

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishClient
import tools.apachi.starlinknav.core.dish.TransportMode

/** What the mock GPS serves. */
enum class MockMode(val source: DishCallKind) {
    /**
     * The app's best position: the dish's AUTO fix through the inertial filter (with
     * [SettingsSnapshot.inertialCorrection]), else moved forward by its age.
     */
    AUTO(DishCallKind.LOCATION_AUTO),
    /** The dish's AUTO fix exactly as the dish reports it. */
    RAW(DishCallKind.LOCATION_AUTO),
    /** The dish's Starlink-only source: no GNSS, so spoofing cannot move it; it may update rarely. */
    STARLINK_ONLY(DishCallKind.LOCATION_STARLINK),
}

/** What is drawn under tracks and the live map. Anything but OFF downloads map tiles. */
enum class MapLayer {
    /** No tiles, no network: tracks on a plain background. */
    OFF,
    /** OpenFreeMap street map (vector tiles, OpenStreetMap data). */
    REGULAR,
    /** EOX Sentinel-2 cloudless 2017 imagery, 10 m per pixel (CC BY 4.0). */
    SATELLITE,
}

/** Where the dish mount offset (dish yaw − car heading) comes from. */
enum class MountMode {
    /** Learned by the inertial correction while driving, persisted with the time it was learned. */
    AUTO,
    /** The value entered in Settings (the filter still replaces it when driving shows one more than 5° away). */
    MANUAL,
}

/** Which look the app has: the system's light/dark setting, or its own choice. */
enum class ThemeMode {
    /** Follow Android's dark theme setting. */
    SYSTEM,
    LIGHT,
    DARK,
}

data class SettingsSnapshot(
    val dishHost: String = DishClient.DEFAULT_HOST,
    /**
     * The router's address (host or host:port); empty = the Wi-Fi network's default gateway. Only a typed address is
     * stored: the gateway is read from the network each time, as the LAN subnet can change.
     */
    val routerHost: String = "",
    val transport: TransportMode = TransportMode.AUTO,
    /** Send dish traffic over the Wi-Fi network even when Android prefers cellular. */
    val bindToWifi: Boolean = true,
    /** Fixed mount: dish yaw − car heading, degrees; learned (AUTO) or entered (MANUAL). See [effectiveMountDeg]. */
    val mountMode: MountMode = MountMode.AUTO,
    /** The value entered for MANUAL; NaN = none entered yet (0 is a real offset: a dish facing forward). */
    val mountManualDeg: Float = Float.NaN,
    /** The value the inertial correction learned last (NaN = never), and when (wall clock ms, 0 = never). */
    val mountLearnedDeg: Float = Float.NaN,
    val mountLearnedAtMs: Long = 0,
    val imuRateHz: Int = 100,
    val retentionDays: Int = 7,
    val maxStorageMb: Int = 2048,
    /**
     * Master switch of session logging (on by default). Off = the app writes no session log at all: no recording
     * starts (not from Sessions, the notification, the mock, the Quick Settings tile) and [recordWithMock] is ignored.
     * Alerts, the mock and the delivery check work as before. Not in [toJson]: a session only exists when it is on.
     */
    val loggingEnabled: Boolean = true,
    val mockMode: MockMode = MockMode.AUTO,
    /** Record a session whenever the mock is on (the logs are what improves the app). */
    val recordWithMock: Boolean = true,
    /** Off until the user picks a layer: tiles tell their provider which area is on screen. */
    val mapLayer: MapLayer = MapLayer.OFF,
    /**
     * Auto mode: while the dish gives no position, serve Android's own network location (cell
     * towers and Wi-Fi).
     */
    val networkFallback: Boolean = true,
    /**
     * Auto mode serves the dish position through the inertial filter (phone IMU + dish heading): sideways
     * correction and dead reckoning through outages. Off = the dish fix moved forward by its age, as before
     * (for A/B tests).
     */
    val inertialCorrection: Boolean = true,
    /** Light, dark or the system's; a look only, so it is not in [toJson] (session headers). */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    val mountLearned: Double? get() = mountLearnedDeg.toDouble().takeIf { it.isFinite() }

    val mountManual: Double? get() = mountManualDeg.toDouble().takeIf { it.isFinite() }

    /** The mount offset everything uses: the manual value, or the learned one (null while there is none). */
    val effectiveMountDeg: Double?
        get() = when (mountMode) {
            MountMode.MANUAL -> mountManual
            MountMode.AUTO -> mountLearned
        }

    /**
     * The session header's config (coordinate-free). `dishYawOffsetDeg` is the effective mount offset (0 = none, as
     * read up to 0.3); the session report's inertial replay starts from `mountMode` with `mountManualDeg` or
     * `mountLearnedDeg` (each only when there is one), as the live filter does.
     */
    fun toJson(): String = JSONObject()
        .put("dishHost", dishHost)
        .put("transport", transport.name)
        .put("bindToWifi", bindToWifi)
        .put("dishYawOffsetDeg", effectiveMountDeg ?: 0.0)
        .put("mountMode", mountMode.name)
        .apply { mountManual?.let { put("mountManualDeg", it) } }
        // Not the time it was learned: a wall-clock moment of an earlier drive, and the header goes out with every export.
        .apply { mountLearned?.let { put("mountLearnedDeg", it) } }
        .put("imuRateHz", imuRateHz)
        .put("retentionDays", retentionDays)
        .put("maxStorageMb", maxStorageMb)
        .put("mockMode", mockMode.name)
        .put("recordWithMock", recordWithMock)
        .put("mapLayer", mapLayer.name)
        .put("networkFallback", networkFallback)
        .put("inertialCorrection", inertialCorrection)
        .toString()
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val flow: StateFlow<SettingsSnapshot> = state
    val value: SettingsSnapshot get() = state.value

    /** From any thread (the UI, and the service when the inertial filter learned the mount offset). */
    @Synchronized
    fun update(change: (SettingsSnapshot) -> SettingsSnapshot) {
        val s = change(state.value)
        prefs.edit()
            .putString("dishHost", s.dishHost)
            .putString("routerHost", s.routerHost)
            .putString("transport", s.transport.name)
            .putBoolean("bindToWifi", s.bindToWifi)
            .remove("dishYawOffsetDeg")
            .putString("mountMode", s.mountMode.name)
            .putFloat("mountManualDeg", s.mountManualDeg)
            .putFloat("mountLearnedDeg", s.mountLearnedDeg)
            .putLong("mountLearnedAtMs", s.mountLearnedAtMs)
            .putInt("imuRateHz", s.imuRateHz)
            .putInt("retentionDays", s.retentionDays)
            .putInt("maxStorageMb", s.maxStorageMb)
            .putBoolean("loggingEnabled", s.loggingEnabled)
            .putString("mockMode", s.mockMode.name)
            .putBoolean("recordWithMock", s.recordWithMock)
            .putString("mapLayer", s.mapLayer.name)
            .putBoolean("networkFallback", s.networkFallback)
            .putBoolean("inertialCorrection", s.inertialCorrection)
            .putString("themeMode", s.themeMode.name)
            .apply()
        state.value = s
    }

    private fun load(): SettingsSnapshot {
        val d = SettingsSnapshot()
        // Up to 0.3: one manual value, 0 = not set. A value someone entered stays theirs (Manual).
        val legacy = prefs.getFloat("dishYawOffsetDeg", 0f).takeIf { it != 0f && it.isFinite() }
        return SettingsSnapshot(
            dishHost = prefs.getString("dishHost", null) ?: d.dishHost,
            routerHost = prefs.getString("routerHost", null) ?: d.routerHost,
            transport = prefs.getString("transport", null)?.let { runCatching { TransportMode.valueOf(it) }.getOrNull() } ?: d.transport,
            bindToWifi = prefs.getBoolean("bindToWifi", d.bindToWifi),
            mountMode = prefs.getString("mountMode", null)?.let { runCatching { MountMode.valueOf(it) }.getOrNull() }
                ?: if (legacy != null) MountMode.MANUAL else d.mountMode,
            mountManualDeg = if (prefs.contains("mountManualDeg")) prefs.getFloat("mountManualDeg", d.mountManualDeg) else legacy ?: d.mountManualDeg,
            mountLearnedDeg = prefs.getFloat("mountLearnedDeg", d.mountLearnedDeg),
            mountLearnedAtMs = prefs.getLong("mountLearnedAtMs", d.mountLearnedAtMs),
            imuRateHz = prefs.getInt("imuRateHz", d.imuRateHz),
            retentionDays = prefs.getInt("retentionDays", d.retentionDays),
            maxStorageMb = prefs.getInt("maxStorageMb", d.maxStorageMb),
            loggingEnabled = prefs.getBoolean("loggingEnabled", d.loggingEnabled),
            mockMode = prefs.getString("mockMode", null)?.let { runCatching { MockMode.valueOf(it) }.getOrNull() } ?: d.mockMode,
            recordWithMock = prefs.getBoolean("recordWithMock", d.recordWithMock),
            mapLayer = prefs.getString("mapLayer", null)?.let { runCatching { MapLayer.valueOf(it) }.getOrNull() } ?: d.mapLayer,
            networkFallback = prefs.getBoolean("networkFallback", d.networkFallback),
            inertialCorrection = prefs.getBoolean("inertialCorrection", d.inertialCorrection),
            themeMode = prefs.getString("themeMode", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
        )
    }
}
