package tools.apachi.starlinknav.core.nav

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * The phone's wall clock against GNSS time, so that the dish's fix time (`gps_time_s`, GNSS time) can be compared
 * with the moment the fix arrived without trusting `System.currentTimeMillis()`: on the 2026-10-26 drive one phone's
 * clock was 0.67 s ahead of GNSS time and the other's 0.39 s behind, which made one app move every served position
 * 0.7 s × speed ahead of the car and the other ignore the fix age altogether.
 *
 * Two sources, each wall-clock-free in the direction that matters:
 * - a real (not mocked) GPS fix: its UTC time is the receiver's, its elapsedRealtime epoch pins it to the monotonic
 *   clock, so `(receipt wall − fix UTC) − (receipt mono − fix mono)` is the offset exactly (median of the last 21);
 * - an NMEA `RMC` / `GGA` sentence: its time of day is the receiver's; the sentence arrives some tens of ms after that
 *   epoch, so the smallest `receipt wall − sentence time` over the last two minutes is the offset, a little high.
 *   NMEA keeps flowing while this app mocks the `gps` provider (the fixes then do not), so this source is the one
 *   the mock runs on.
 *
 * Thread-safe: fed from the GNSS thread, read from the inertial thread and the service.
 */
class ReceiverClock {
    private val gpsSamples = ArrayDeque<Double>()
    private val nmeaSamples = ArrayDeque<Pair<Long, Double>>()

    @Volatile private var gpsOffset: Double? = null
    @Volatile private var nmeaOffset: Double? = null

    /** Wall clock minus GNSS time, ms; null until a source has reported. */
    val offsetMs: Double? get() = gpsOffset ?: nmeaOffset

    /** Where [offsetMs] comes from: "gps", "nmea" or null. */
    val source: String? get() = if (gpsOffset != null) "gps" else if (nmeaOffset != null) "nmea" else null

    /** A real GPS fix ([fixTimeMs] its UTC time, [fixElapsedNs] its elapsedRealtime epoch) received at the given clocks. */
    @Synchronized
    fun onGpsFix(fixTimeMs: Long, fixElapsedNs: Long, receiptWallMs: Long, receiptMonoNs: Long) {
        if (fixTimeMs <= 0 || fixElapsedNs <= 0) return
        val off = (receiptWallMs - fixTimeMs) - (receiptMonoNs - fixElapsedNs) / 1e6
        if (abs(off) > MAX_OFFSET_MS) return
        gpsSamples.addLast(off)
        while (gpsSamples.size > GPS_KEEP) gpsSamples.removeFirst()
        gpsOffset = MountCalibrator.median(gpsSamples.toList())
    }

    /** An NMEA sentence received at [receiptWallMs]; only `RMC` / `GGA` with a time of day count. */
    @Synchronized
    fun onNmea(sentence: String, receiptWallMs: Long) {
        val utc = nmeaUtcMs(sentence, receiptWallMs) ?: return
        val off = (receiptWallMs - utc).toDouble()
        if (abs(off) > MAX_OFFSET_MS) return
        nmeaSamples.addLast(receiptWallMs to off)
        while (nmeaSamples.isNotEmpty() && receiptWallMs - nmeaSamples.first().first > NMEA_WINDOW_MS) nmeaSamples.removeFirst()
        nmeaOffset = nmeaSamples.minOf { it.second }
    }

    /** [receiptWallMs] on the GNSS time line, ms; null until the offset is known. */
    fun gnssMs(receiptWallMs: Long): Double? = offsetMs?.let { receiptWallMs - it }

    /** Age of a diagnostics fix ([gpsTimeS], GNSS seconds) at its receipt, s; null while the clock offset is unknown. */
    fun fixAgeS(gpsTimeS: Double, receiptWallMs: Long): Double? = gnssMs(receiptWallMs)?.let { FixExtrapolator.fixAgeS(gpsTimeS, it) }

    companion object {
        private const val GPS_KEEP = 21
        private const val NMEA_WINDOW_MS = 120_000L
        /** A clock further off than this is not the clock being wrong but the data. */
        private const val MAX_OFFSET_MS = 3_600_000.0
        private const val DAY_MS = 86_400_000L

        /**
         * The UTC epoch (ms) of an `RMC` (valid, with its date) or `GGA` (with a fix) sentence's time of day; the date
         * of a `GGA` is the one that puts it nearest [nearWallMs]. Null for every other sentence or an unusable field.
         */
        fun nmeaUtcMs(sentence: String, nearWallMs: Long): Long? {
            if (!sentence.startsWith("$")) return null
            val star = sentence.indexOf('*')
            val f = (if (star > 0) sentence.substring(0, star) else sentence).split(',')
            if (f.size < 7 || f[0].length < 6) return null
            val type = f[0].substring(f[0].length - 3)
            val tod = timeOfDayMs(f[1]) ?: return null
            when (type) {
                "RMC" -> {
                    if (f[2] != "A") return null
                    val date = f.getOrNull(9)?.takeIf { it.length == 6 } ?: return null
                    val day = runCatching {
                        LocalDate.of(2000 + date.substring(4, 6).toInt(), date.substring(2, 4).toInt(), date.substring(0, 2).toInt())
                    }.getOrNull() ?: return null
                    return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + tod
                }
                "GGA" -> {
                    if (f[6] == "0") return null
                    val dayStart = Math.floorDiv(nearWallMs, DAY_MS) * DAY_MS
                    return listOf(dayStart - DAY_MS, dayStart, dayStart + DAY_MS).map { it + tod }.minByOrNull { abs(it - nearWallMs) }
                }
                else -> return null
            }
        }

        /** `hhmmss` or `hhmmss.sss` to ms of the day. */
        private fun timeOfDayMs(field: String): Long? {
            if (field.length < 6) return null
            val h = field.substring(0, 2).toIntOrNull() ?: return null
            val m = field.substring(2, 4).toIntOrNull() ?: return null
            val s = field.substring(4).toDoubleOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59 || s < 0 || s >= 61) return null
            return ((h * 3600 + m * 60) * 1000L) + Math.round(s * 1000)
        }
    }
}
