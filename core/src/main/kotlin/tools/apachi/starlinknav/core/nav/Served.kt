package tools.apachi.starlinknav.core.nav

import tools.apachi.starlinknav.core.dish.DishFix
import tools.apachi.starlinknav.core.dish.Geo
import kotlin.math.min

/**
 * Turns dish fixes into the position the mock GPS serves: the fix moved forward along the
 * course by its age. A Gen 3 Mini fix is ~0.45 s old when it arrives (diagnostics gps_time_s
 * against the phone clock), and the dish computes a new one every ~1.08 s while the app asks
 * every second, so a fix seen twice is a second older the second time; at 77 km/h a second is
 * 21 m. On drives 1-3 (2026-10-14) moving the fix by its age cut the error of the served
 * position against the phone's GNSS at the same moment by 0.6-1.5 m median and 3-4 m p95.
 * The same code runs in the app and in the session report, so the report shows what the app
 * serves.
 */
class FixExtrapolator(
    private val defaultLatencyS: Double = DEFAULT_LATENCY_S,
    private val maxShiftS: Double = 2.0,
    private val minSpeedMps: Double = 2.0,
) {
    data class Served(
        val lat: Double,
        val lon: Double,
        /** Course over ground from successive fixes, degrees; null until the dish has moved. */
        val courseDeg: Double?,
        /** Age of the fix at the moment it is served, s. */
        val ageS: Double,
        /** How far the fix was moved forward, m. */
        val shiftM: Double,
    )

    private val latency = FixLatency(defaultLatencyS, PLAUSIBLE_AGE_S)
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var firstSeenNs = 0L
    private var lastSeenNs = Long.MIN_VALUE
    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var course: Double? = null

    /**
     * Median of the recent fix ages at receipt; the default before any were measured or when the
     * median is implausible (the age is measured against the phone's wall clock, so a clock that
     * is off would otherwise push the served position metres ahead or behind).
     */
    val latencyS: Double get() = latency.value

    /** Age of the dish's current solution when it arrived: diagnostics gps_time_s against the receiver clock. */
    fun onFixAge(ageS: Double) = latency.add(ageS)

    fun serve(fix: DishFix, atNs: Long): Served {
        if (lastSeenNs != Long.MIN_VALUE && atNs - lastSeenNs > GAP_NS) {
            // After a gap (cut-off, unreachable dish) the chord to the old anchor is not a course.
            course = null
            anchorLat = Double.NaN
        }
        lastSeenNs = atNs
        if (fix.lat != lastLat || fix.lon != lastLon) {
            lastLat = fix.lat
            lastLon = fix.lon
            firstSeenNs = atNs
            if (anchorLat.isNaN()) {
                anchorLat = fix.lat
                anchorLon = fix.lon
            } else if (Geo.distanceM(anchorLat, anchorLon, fix.lat, fix.lon) >= MIN_CHORD_M) {
                course = Geo.bearingDeg(anchorLat, anchorLon, fix.lat, fix.lon)
                anchorLat = fix.lat
                anchorLon = fix.lon
            }
        }
        val age = latencyS + (atNs - firstSeenNs) / 1e9
        val c = course
        if (c == null || fix.horizontalSpeedMps < minSpeedMps) return Served(fix.lat, fix.lon, c, age, 0.0)
        val shift = fix.horizontalSpeedMps * min(age, maxShiftS)
        val (lat, lon) = Geo.offset(fix.lat, fix.lon, c, shift)
        return Served(lat, lon, c, age, shift)
    }

    companion object {
        const val DEFAULT_LATENCY_S = 0.45
        /** 0.44-0.46 s on every 2026-10-14 drive. */
        private val PLAUSIBLE_AGE_S = 0.1..1.2
        private const val GAP_NS = 3_000_000_000L
        private const val MIN_CHORD_M = 2.0

        /** Age of a diagnostics fix at receipt, s; null when the dish gave no fix time. */
        fun fixAgeS(gpsTimeS: Double, receivedWallMs: Long): Double? =
            gpsTimeS.takeIf { it > 0 }?.let { receivedWallMs / 1000.0 - (it + GPS_EPOCH_UNIX_S - GPS_LEAP_S) }

        private const val GPS_EPOCH_UNIX_S = 315_964_800.0
        /** GPS − UTC since 2017 (as [Geo.gpsSecondsToUnixMs]). */
        private const val GPS_LEAP_S = 18.0
    }
}

/**
 * Latency of dish fixes at receipt: the median of the last 21 diagnostics fix ages, or [defaultS] before any
 * were measured or when the median is implausible (the age is measured against the phone's wall clock, so a
 * clock that is off would otherwise push the served position metres ahead or behind). Used by
 * [FixExtrapolator] and the inertial filter.
 */
class FixLatency(private val defaultS: Double = FixExtrapolator.DEFAULT_LATENCY_S, private val plausible: ClosedFloatingPointRange<Double> = 0.1..1.2) {
    private val ages = ArrayDeque<Double>()

    fun add(ageS: Double) {
        if (ageS !in 0.0..3.0) return
        ages.addLast(ageS)
        while (ages.size > 21) ages.removeFirst()
    }

    val value: Double
        get() = if (ages.isEmpty()) defaultS else ages.sorted()[ages.size / 2].takeIf { it in plausible } ?: defaultS
}
