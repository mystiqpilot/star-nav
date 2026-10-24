package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.Quaternion
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class DishFix(
    val source: Int,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val sigmaM: Double,
    val horizontalSpeedMps: Double,
    val verticalSpeedMps: Double,
) {
    /**
     * Only an impossible coordinate means "no fix". Sigma is not a validity flag: on a Gen 3
     * Mini the GPS source reports a nominal 15.00 m and the Starlink-only source ~100 m while
     * both are live fixes.
     */
    val looksLikePlaceholder: Boolean
        get() = !lat.isFinite() || !lon.isFinite() || abs(lat) > 90 || abs(lon) > 180 || (lat == 0.0 && lon == 0.0)
    val sourceName: String get() = PositionSource.name(source)
}

fun DeviceResponse.fix(): DishFix? {
    val r = get_location ?: return null
    val lla = r.lla ?: return null
    return DishFix(r.source, lla.lat, lla.lon, lla.alt, r.sigma_m, r.horizontal_speed_mps, r.vertical_speed_mps)
}

/**
 * Dish orientation decoded from `ned2dish_quaternion`. Verified on a Gen 3 Mini (firmware
 * 2026.05.13, 2026-10-14): R(q) maps dish-body vectors to NED, and body +z is the array
 * normal, whose azimuth/elevation match the reported boresight to 0.1°.
 */
data class DishAttitude(
    /** Body +x axis projected on the horizontal plane, degrees clockwise from north. */
    val headingDeg: Double,
    val xElevationDeg: Double,
    val normalAzimuthDeg: Double,
    val normalElevationDeg: Double,
    /**
     * Body +y axis projected on the horizontal plane, degrees clockwise from north: where the top of the
     * panel (the top of a FRAME_UT obstruction map) points. Not the heading: for a panel facing the sky +y is 90 degrees counter-clockwise from +x.
     */
    val panelUpAzimuthDeg: Double,
)

fun Quaternion.attitude(): DishAttitude {
    val n = sqrt(q_scalar * q_scalar + q_x * q_x + q_y * q_y + q_z * q_z).toDouble().takeIf { it > 1e-9 } ?: 1.0
    val w = q_scalar / n
    val x = q_x / n
    val y = q_y / n
    val z = q_z / n
    val r11 = 1 - 2 * (y * y + z * z)
    val r21 = 2 * (x * y + w * z)
    val r31 = 2 * (x * z - w * y)
    val r12 = 2 * (x * y - w * z)
    val r22 = 1 - 2 * (x * x + z * z)
    val r13 = 2 * (x * z + w * y)
    val r23 = 2 * (y * z - w * x)
    val r33 = 1 - 2 * (x * x + y * y)
    return DishAttitude(
        headingDeg = (Math.toDegrees(atan2(r21, r11)) + 360.0) % 360.0,
        xElevationDeg = Math.toDegrees(asin((-r31).coerceIn(-1.0, 1.0))),
        normalAzimuthDeg = Math.toDegrees(atan2(r23, r13)),
        normalElevationDeg = Math.toDegrees(asin((-r33).coerceIn(-1.0, 1.0))),
        panelUpAzimuthDeg = (Math.toDegrees(atan2(r22, r12)) + 360.0) % 360.0,
    )
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial great-circle bearing from point 1 to point 2, degrees clockwise from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** The point [distanceM] away along [bearingDeg]; flat-earth, for the few metres a fix is moved. */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val b = Math.toRadians(bearingDeg)
        val dLat = distanceM * cos(b) / EARTH_RADIUS_M
        val dLon = distanceM * sin(b) / (EARTH_RADIUS_M * cos(Math.toRadians(lat)))
        return lat + Math.toDegrees(dLat) to lon + Math.toDegrees(dLon)
    }

    /** GPS seconds since 1980-01-06 to Unix ms, assuming the 18 s leap offset in force since 2017. */
    fun gpsSecondsToUnixMs(gpsSeconds: Double): Long = 315_964_800_000L + (gpsSeconds * 1000).toLong() - 18_000L
}
