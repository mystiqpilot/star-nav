package tools.apachi.starlinknav.core.nav

import kotlin.math.abs
import kotlin.math.max

/** The position an NMEA `RMC` / `GGA` sentence carries: degrees, the sentence's time of day (ms) and, from a `GGA`, its HDOP. */
data class NmeaPosition(val lat: Double, val lon: Double, val todMs: Long, val hdop: Double?)

/**
 * The phone's own position from its NMEA sentences, for the GPS check ([GpsTrust]) while this app mocks the `gps`
 * provider: the provider's fixes are then the app's own output (the check would read MOCKED), but the chipset's
 * sentences keep flowing on the phones seen so far (device-specific; the NMEA rate on the Position page says so), and
 * `RMC` / `GGA` carry the receiver's latitude and longitude. Plain JVM; the time field is read as [ReceiverClock.nmeaUtcMs]
 * reads it. Not thread-safe: fed from the GNSS thread only.
 *
 * Accuracy: a sentence has no accuracy field. The fix is told [FLOOR_M] (15 m: three times the 3-5 m a phone's own
 * `Location.accuracy` reads in the open on the recorded drives, since the sentence says nothing about multipath or a
 * degraded solution), widened to HDOP × [UERE_M] (a 5 m range error) when the latest HDOP (a `GGA` or `GSA` within
 * [HDOP_FRESH_S]) says the geometry is worse than that. The check only adds the figure to its 100 m margin, so the
 * conservative side costs nothing there, while a too-small one would make a good phone look spoofed.
 */
class NmeaFixes {
    private var lastTodMs = -1L
    private var hdop: Double? = null
    private var hdopAtNs = Long.MIN_VALUE

    /**
     * [sentence] received at [atNs] (the recorder clock, ns): the fix it carries, once per epoch (a `GGA` and an `RMC`
     * with the same time of day are one fix: the first of them counts, the second is dropped), or null for any other
     * sentence or one without a fix. The fix is stamped [atNs]: a sentence arrives some tens of ms after its epoch,
     * which the check's limit (the dish's speed times the time difference) covers.
     */
    fun onNmea(sentence: String, atNs: Long): TrustFix? {
        hdopOf(sentence)?.let {
            hdop = it
            hdopAtNs = atNs
        }
        val p = positionOf(sentence) ?: return null
        if (p.todMs == lastTodMs) return null
        lastTodMs = p.todMs
        val h = hdop?.takeIf { atNs - hdopAtNs <= HDOP_FRESH_S * 1e9 }
        return TrustFix(p.lat, p.lon, accuracyM(h), atNs)
    }

    companion object {
        /** The accuracy told for an NMEA position without a worse HDOP, m. */
        const val FLOOR_M = 15.0
        /** The range error the HDOP is multiplied with, m. */
        const val UERE_M = 5.0
        /** An HDOP older than this no longer describes the fix, s. */
        const val HDOP_FRESH_S = 5.0

        /** [FLOOR_M], or HDOP × [UERE_M] when that is larger; [hdop] null = unknown. */
        fun accuracyM(hdop: Double?): Double = max(FLOOR_M, (hdop ?: 0.0) * UERE_M)

        /**
         * The position of an `RMC` (status `A`) or `GGA` (fix quality 1-5: GPS, differential, PPS, RTK fixed / float;
         * not 0 = none or 6 = estimated) sentence; null for every other sentence or an unusable field.
         */
        fun positionOf(sentence: String): NmeaPosition? {
            val f = fields(sentence) ?: return null
            val tod = ReceiverClock.timeOfDayMs(f.getOrNull(1) ?: return null) ?: return null
            return when (type(f)) {
                "RMC" -> {
                    if (f.size < 7 || f[2] != "A") return null
                    coordinates(f[3], f[4], f[5], f[6])?.let { (la, lo) -> NmeaPosition(la, lo, tod, null) }
                }
                "GGA" -> {
                    if (f.size < 9) return null
                    val quality = f[6].toIntOrNull() ?: return null
                    if (quality !in 1..5) return null
                    coordinates(f[2], f[3], f[4], f[5])?.let { (la, lo) -> NmeaPosition(la, lo, tod, dop(f[8])) }
                }
                else -> null
            }
        }

        /** The HDOP of a `GGA` (field 8) or `GSA` (field 16) sentence; null for other sentences or an empty field. */
        fun hdopOf(sentence: String): Double? {
            val f = fields(sentence) ?: return null
            return when (type(f)) {
                "GGA" -> f.getOrNull(8)
                "GSA" -> f.getOrNull(16)
                else -> null
            }?.let(::dop)
        }

        private fun dop(field: String): Double? = field.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

        /** `$TTSSS,...` split at the commas, the checksum and a line ending dropped; null unless it looks like a sentence. */
        private fun fields(sentence: String): List<String>? {
            if (!sentence.startsWith("$")) return null
            val star = sentence.indexOf('*')
            val f = (if (star > 0) sentence.substring(0, star) else sentence).trimEnd().split(',')
            return if (f[0].length < 6) null else f
        }

        private fun type(f: List<String>): String = f[0].substring(f[0].length - 3)

        /** `ddmm.mmmm,N,dddmm.mmmm,E` to degrees; null for an empty or malformed field or a position off the globe. */
        private fun coordinates(latField: String, ns: String, lonField: String, ew: String): Pair<Double, Double>? {
            val lat = degrees(latField, 2) ?: return null
            val lon = degrees(lonField, 3) ?: return null
            val la = when (ns) {
                "N" -> lat
                "S" -> -lat
                else -> return null
            }
            val lo = when (ew) {
                "E" -> lon
                "W" -> -lon
                else -> return null
            }
            if (abs(la) > 90.0 || abs(lo) > 180.0 || (la == 0.0 && lo == 0.0)) return null
            return la to lo
        }

        /** `dd(d)mm.mmmm` with [degreeDigits] degree digits to degrees. */
        private fun degrees(field: String, degreeDigits: Int): Double? {
            val dot = field.indexOf('.')
            val whole = if (dot < 0) field.length else dot
            if (whole < degreeDigits + 2) return null
            val d = field.substring(0, degreeDigits).toIntOrNull() ?: return null
            val m = field.substring(degreeDigits).toDoubleOrNull() ?: return null
            if (m < 0.0 || m >= 60.0) return null
            return d + m / 60.0
        }
    }
}
