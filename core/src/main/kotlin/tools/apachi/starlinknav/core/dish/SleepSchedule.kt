package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DishConfig
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The dish's sleep schedule: one daily window in which it sleeps (no internet, no snow melt; Wi-Fi and
 * Ethernet stay up). The dish counts minutes past midnight UTC; people think in local time. The duration
 * is 1..1440: the dish rejects 0 even when disabling the schedule, so a disabled window keeps a duration.
 *
 * The dish's minutes never move, but a local clock does at a daylight-saving change: a window set at 22:00
 * in summer is 21:00 local in winter. The fixed-offset functions convert at one offset the caller picks; the
 * [ZoneId] ones use the offset in force at the window's next start ([nextStart]), which is what the person
 * will experience.
 */
data class SleepWindow(val startUtcMinutes: Int, val durationMinutes: Int, val enabled: Boolean) {
    init {
        require(startUtcMinutes in 0 until DAY) { "start $startUtcMinutes is not a minute of the day" }
        require(durationMinutes in 1..DAY) { "duration $durationMinutes is not 1..1440 minutes" }
    }

    val endUtcMinutes: Int get() = (startUtcMinutes + durationMinutes) % DAY

    fun startLocalMinutes(utcOffsetMinutes: Int): Int = floorMod(startUtcMinutes + utcOffsetMinutes)
    fun endLocalMinutes(utcOffsetMinutes: Int): Int = floorMod(endUtcMinutes + utcOffsetMinutes)

    /** Whether [utcMinuteOfDay] falls inside the window (the window may wrap past midnight). */
    fun contains(utcMinuteOfDay: Int): Boolean {
        val m = floorMod(utcMinuteOfDay)
        val since = floorMod(m - startUtcMinutes)
        return since < durationMinutes
    }

    /** The next moment the window opens, strictly after [now]; the dish counts UTC, so it does not depend on a zone. */
    fun nextStart(now: Instant): Instant {
        val dayStart = now.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
        val today = dayStart.plus(Duration.ofMinutes(startUtcMinutes.toLong()))
        return if (today.isAfter(now)) today else today.plus(Duration.ofDays(1))
    }

    /** Local clock minutes (0..1439) of the next start in [zone]: the offset in force then, DST included. */
    fun startLocalMinutes(zone: ZoneId, now: Instant): Int = localMinutes(nextStart(now), zone)

    /** Local clock minutes of the end of the window that opens at the next start in [zone]. */
    fun endLocalMinutes(zone: ZoneId, now: Instant): Int = localMinutes(nextStart(now).plus(Duration.ofMinutes(durationMinutes.toLong())), zone)

    fun withEnabled(on: Boolean) = copy(enabled = on)

    companion object {
        const val DAY = 24 * 60

        /** A window that was never configured reads as duration 0; this is what to send to switch it off. */
        val OFF = SleepWindow(0, 1, false)

        fun floorMod(minutes: Int): Int = ((minutes % DAY) + DAY) % DAY

        /** Window from local start minutes (0..1439) and the local UTC offset in minutes (e.g. +180 for Kyiv in summer). */
        fun fromLocal(startLocalMinutes: Int, durationMinutes: Int, enabled: Boolean, utcOffsetMinutes: Int) =
            SleepWindow(floorMod(startLocalMinutes - utcOffsetMinutes), durationMinutes, enabled)

        /**
         * Window from a local start time in [zone]: the next time that clock reads [startLocalMinutes] after [now],
         * converted with the offset in force then (so a start set across a daylight-saving change is right at
         * the moment it first applies). Later changes of the offset move the local time, not the window.
         */
        fun fromLocal(startLocalMinutes: Int, durationMinutes: Int, enabled: Boolean, zone: ZoneId, now: Instant): SleepWindow {
            val time = LocalTime.of(floorMod(startLocalMinutes) / 60, floorMod(startLocalMinutes) % 60)
            var date = now.atZone(zone).toLocalDate()
            var start = date.atTime(time).atZone(zone)
            if (!start.toInstant().isAfter(now)) {
                date = date.plusDays(1)
                start = date.atTime(time).atZone(zone)
            }
            val utc = start.withZoneSameInstant(ZoneOffset.UTC)
            return SleepWindow(utc.hour * 60 + utc.minute, durationMinutes, enabled)
        }

        private fun localMinutes(at: Instant, zone: ZoneId): Int {
            val t = at.atZone(zone)
            return t.hour * 60 + t.minute
        }

        /** The window in a dish config (status.config or dish_get_config); null without one. A zero duration is read as 1. */
        fun of(config: DishConfig?): SleepWindow? {
            config ?: return null
            return SleepWindow(
                startUtcMinutes = floorMod(config.power_save_start_minutes.toInt()),
                durationMinutes = config.power_save_duration_minutes.toInt().coerceIn(1, DAY),
                enabled = config.power_save_mode,
            )
        }

        /** "HH:MM" of a minute of the day. */
        fun clock(minutes: Int): String = "%02d:%02d".format(java.util.Locale.ROOT, floorMod(minutes) / 60, floorMod(minutes) % 60)
    }
}
