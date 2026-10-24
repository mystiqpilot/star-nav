package tools.apachi.starlinknav.core.nav

import tools.apachi.starlinknav.core.dish.Geo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the checks say about the phone's own GPS. */
enum class GpsTrustState {
    /** The phone's GPS agrees with the dish and nothing looks wrong. */
    TRUSTED,

    /** One or more checks failed; [GpsTrustVerdict.reasons] says which, with numbers. */
    DISTRUSTED,

    /** The latest GPS fix is a mock fix (the app's own mock while it is on): the phone's real GPS is not visible. */
    MOCKED,

    /** No GPS fix, or nothing to compare it with; [GpsTrustVerdict.unknown] says which. */
    UNKNOWN,
}

enum class UnknownWhy {
    /** No GPS fix yet, or the latest one is older than [GpsTrustConfig.staleS]. */
    NO_GPS_FIX,

    /** A GPS fix, but no dish AUTO fix close enough in time to compare it with (dish unreachable, cut off, not polled). */
    NOTHING_TO_COMPARE,
}

/** Why the GPS is distrusted. [code] is stable (logs, UI); the numbers are metres and seconds on the recorder clock. */
sealed interface DistrustReason {
    val code: String

    /**
     * Rule 1: GPS and the dish's AUTO position, compared at the same moment, are farther apart than
     * [limitM] (100 m + both accuracies + the dish's travel across the time difference of the two
     * fixes) and stayed so for [GpsTrustConfig.farSustainS]. Cleared after [GpsTrustConfig.farClearS]
     * back within. The numbers are those of the latest comparison that was over the limit.
     */
    data class FarFromStarlink(
        val distanceM: Double,
        val limitM: Double,
        /** Start (recorder-clock ns) of the stretch over the limit. */
        val sinceNs: Long,
        /** Time (ns) of the latest comparison over the limit. */
        val lastNs: Long,
    ) : DistrustReason {
        override val code get() = "far_from_starlink"
    }

    /**
     * Rule 2: two consecutive real GPS fixes imply [speedMps] > [GpsTrustConfig.jumpSpeedMps], or a
     * jump of more than [GpsTrustConfig.jumpDistM] in under [GpsTrustConfig.jumpWithinS]. Latched for
     * [GpsTrustConfig.jumpLatchS] from [atNs], the time of the second fix. [jumpM] is the distance
     * between the two fixes; the speed (and the 1 km test) use it less both fixes' accuracies, so a
     * re-acquisition that moves the fix within its own error is no jump.
     */
    data class ImpossibleJump(
        val jumpM: Double,
        val dtS: Double,
        val speedMps: Double,
        val atNs: Long,
    ) : DistrustReason {
        override val code get() = "impossible_jump"
    }

    /**
     * Rule 3: GPS and the dish AUTO position are both farther from the Starlink-only fix than
     * [limitM] = 5 km + [baselineM] + [travelledM] (the path the dish has driven since the fix last
     * changed, AUTO speed integrated; [baselineM] is the allowance for a fix that was already old
     * when first seen, see [GpsTrust]).
     */
    data class FarFromStarlinkOnly(
        val gpsM: Double,
        val dishM: Double,
        val limitM: Double,
        val baselineM: Double,
        val travelledM: Double,
    ) : DistrustReason {
        override val code get() = "far_from_starlink_only"
    }
}

data class GpsTrustVerdict(
    val state: GpsTrustState,
    /** Non-empty only for [GpsTrustState.DISTRUSTED]; the most acute first (jump, far from Starlink, far from the Starlink-only fix). */
    val reasons: List<DistrustReason> = emptyList(),
    /** Set only for [GpsTrustState.UNKNOWN]. */
    val unknown: UnknownWhy? = null,
) {
    val reason: DistrustReason? get() = reasons.firstOrNull()
}

/** A position with its accuracy on the recorder clock (monotonic ns), as the chooser takes and gives it. */
data class TrustFix(val lat: Double, val lon: Double, val accuracyM: Double, val atNs: Long)

/** The result of the latest GPS-against-AUTO comparison, for showing "GPS is 12 m from Starlink". */
data class TrustComparison(val distanceM: Double, val limitM: Double, val atNs: Long)

enum class ChosenSource {
    /** The phone's GPS fix. */
    GPS,

    /** The dish's AUTO fix as it came. */
    STARLINK,

    /** What the mock serves (AUTO moved forward by its age, inertially corrected): only while the mock is on. */
    SERVED,
}

/**
 * The position "Center" and "Open in Google Maps" use. [unverified] is set when it is the phone's
 * GPS although the checks did not clear it because there was nothing to compare it with (verdict
 * UNKNOWN: no dish fix) and no Starlink position exists; the UI shows a warning then. A GPS the
 * checks distrust, or that is a mock fix, is never chosen.
 */
data class ChosenPosition(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val source: ChosenSource,
    val unverified: Boolean,
)

/** Thresholds of [GpsTrust]; the defaults are the user's choice (2026-10-15), tests shorten the times. */
data class GpsTrustConfig(
    /** Rule 1: slack over both accuracies, m. */
    val farMarginM: Double = 100.0,
    /** Rule 1: how long GPS must stay over the limit before it is distrusted, s. */
    val farSustainS: Double = 5.0,
    /** Rule 1: how long it must stay back within before the distrust clears, s. */
    val farClearS: Double = 5.0,
    /** Rule 2: implied speed between consecutive GPS fixes above which the jump is impossible, m/s. */
    val jumpSpeedMps: Double = 100.0,
    /** Rule 2: a jump farther than this ... */
    val jumpDistM: Double = 1000.0,
    /** ... in less than this is impossible whatever the clock says, s. */
    val jumpWithinS: Double = 2.0,
    /** Rule 2: how long a detected jump keeps the GPS distrusted, s. */
    val jumpLatchS: Double = 30.0,
    /** Rule 3: slack over the distance the dish has travelled, m. */
    val frozenMarginM: Double = 5000.0,
    /** A Starlink-only fix that moves less than this from the anchor is the same fix (its jitter), m. */
    val frozenChangeM: Double = 10.0,
    /** At or below this AUTO speed the dish counts as standing, m/s. */
    val stationaryMps: Double = 2.0,
    /** Largest time difference between a GPS fix and an AUTO fix that are still compared, s. */
    val maxSkewS: Double = 3.0,
    /** A fix, or a comparison, older than this no longer counts at [GpsTrust.verdict], s. */
    val staleS: Double = 10.0,
    /** A pause in the comparisons longer than this restarts the unfinished 5 s timers of rule 1, s. */
    val compareGapS: Double = 10.0,
    /** A pause in the comparisons longer than this forgets even an established rule 1 distrust, s. */
    val forgetS: Double = 60.0,
    /** An accuracy claimed above this is counted as this much, so a fix cannot widen its own limit without bound, m. */
    val maxAccuracyM: Double = 300.0,
    /** Dish speeds above this are counted as this much when the travelled path is integrated, m/s. */
    val maxSpeedMps: Double = 100.0,
)

/**
 * Decides whether the phone's own GPS can be believed, from what the dish says. Plain JVM, no
 * clock of its own: every fix carries the time it describes on one monotonic clock (the
 * recorder clock in ns, as [CutoffTracker] and [InertialNav] use; the dish's fix time is the
 * receipt time minus the fix age) and [verdict] takes "now" on the same clock. Methods are
 * synchronized, because the phone's location callback and the dish poller feed from different
 * threads; feed each stream in time order (an older fix than the previous of its stream is dropped).
 *
 * Inputs: [onGpsFix] (phone GPS; a fix with `isMock` is a mock fix), [onAutoFix] (dish AUTO),
 * [onStarlinkOnlyFix] (dish STARLINK source: often frozen for a whole drive, changes only between
 * drives). Rules (the user's choice, 2026-10-15):
 *
 *  1. GPS is [GpsTrustConfig.farMarginM] + accuracy(GPS) + accuracy(AUTO) away from AUTO at the
 *     same moment, sustained 5 s; clears after 5 s back within. The two fixes are never taken at
 *     exactly the same time, so the dish's speed times their time difference is added to the limit.
 *  2. Implied speed between consecutive real GPS fixes > 100 m/s, or a jump > 1 km in under 2 s
 *     (both on the distance less the two fixes' accuracies); latched 30 s. Fixes closer in time
 *     than 0.5 s are judged as if they were 0.5 s apart.
 *  3. GPS and AUTO both farther from the Starlink-only fix than 5 km + the distance the dish has
 *     travelled since that fix last changed (AUTO speed integrated; over a gap in the AUTO fixes
 *     the larger speed of the two sides, so a cut-off does not cause a false alarm; over a gap
 *     longer than [GpsTrustConfig.forgetS] the integral cannot know the path, so the bound starts
 *     again from the distance AUTO has then, as for a fix first seen). Not latched or timed: both
 *     must be kilometres off.
 *
 * Rule 3 and a fix first seen mid-drive: the app cannot know how long ago the Starlink-only fix
 * changed, so the first one it sees (and a change it sees while the dish is moving) gets the
 * distance between it and AUTO at that moment as a baseline, added to the limit. A change seen
 * while the dish stands has no baseline: the new fix is expected next to the dish. A Starlink-only
 * fix that moves less than [GpsTrustConfig.frozenChangeM] from its anchor is not a change.
 *
 * Mock fixes ([onGpsFix] `isMock`) make the verdict MOCKED and wipe the state of rules 1 and 2:
 * with the app's own mock on, the phone's GPS is the app's own output and says nothing about
 * spoofing (a mock provider of another app also reads MOCKED here; the caller knows whether the
 * app's own mock is on). When the app's own mock goes off the caller runs [forgetGps]: the last
 * mock fix would otherwise read MOCKED ("another app is mocking") until a real fix replaces it.
 */
class GpsTrust(private val cfg: GpsTrustConfig = GpsTrustConfig()) {
    private class GpsRec(val fix: TrustFix, val isMock: Boolean)
    private class AutoRec(val fix: TrustFix, val speedMps: Double)

    /** The Starlink-only fix the rule 3 bound is measured from. */
    private class Anchor(val lat: Double, val lon: Double, val observedChange: Boolean) {
        /** Null until an AUTO fix gave the allowance. */
        var baselineM: Double? = null
        var travelledM = 0.0
        var lastNs = 0L
        var lastSpeedMps = 0.0
    }

    private var gps: GpsRec? = null
    private var auto: AutoRec? = null
    private var anchor: Anchor? = null
    private var lastStarlinkOnlyNs = Long.MIN_VALUE

    /** Mock fixes made at or before this time are dropped (see [forgetGps]). */
    private var mockIgnoredUntilNs = Long.MIN_VALUE

    // Rule 2
    private var realGps: TrustFix? = null
    private var jump: DistrustReason.ImpossibleJump? = null
    private var jumpUntilNs = Long.MIN_VALUE

    // Rule 1
    private var farSinceNs: Long? = null
    private var nearSinceNs: Long? = null
    private var farActive = false
    private var farDistanceM = 0.0
    private var farLimitM = 0.0
    private var farLastNs = 0L

    // Rule 3 and the freshness of the comparisons
    private var frozen: DistrustReason.FarFromStarlinkOnly? = null
    private var comparison: TrustComparison? = null

    /** The latest GPS fix, mock or not; null before the first. */
    @get:Synchronized
    val latestGps: TrustFix? get() = gps?.fix

    /** The latest dish AUTO fix; null before the first. */
    @get:Synchronized
    val latestAuto: TrustFix? get() = auto?.fix

    /** The latest GPS-against-AUTO comparison (distance and its limit), whatever its outcome; null before the first. */
    @get:Synchronized
    val lastComparison: TrustComparison? get() = comparison

    @Synchronized
    fun reset() {
        gps = null
        auto = null
        anchor = null
        lastStarlinkOnlyNs = Long.MIN_VALUE
        mockIgnoredUntilNs = Long.MIN_VALUE
        resetGpsRules()
    }

    /**
     * The app's own mock went off at [nowNs]: forget the phone's GPS (its last fix is the mock's) and its rules, and drop
     * mock fixes still in flight that were made before [nowNs]. The next real fix starts fresh; a mock fix made later
     * (another app's mock) counts again.
     */
    @Synchronized
    fun forgetGps(nowNs: Long) {
        gps = null
        mockIgnoredUntilNs = nowNs
        resetGpsRules()
    }

    /** A phone GPS fix at [atNs]. [accuracyM] is `Location.accuracy`; NaN or negative counts as 0. */
    @Synchronized
    fun onGpsFix(lat: Double, lon: Double, accuracyM: Double, atNs: Long, isMock: Boolean = false) {
        if (!valid(lat, lon)) return
        if (isMock && atNs <= mockIgnoredUntilNs) return
        gps?.let { if (atNs < it.fix.atNs) return }
        val fix = TrustFix(lat, lon, accuracy(accuracyM), atNs)
        gps = GpsRec(fix, isMock)
        if (isMock) {
            resetGpsRules()
            return
        }
        checkJump(fix)
        compare()
    }

    /** A dish AUTO fix ([DishFix.sigmaM], [DishFix.horizontalSpeedMps]) at [atNs]. */
    @Synchronized
    fun onAutoFix(lat: Double, lon: Double, sigmaM: Double, horizontalSpeedMps: Double, atNs: Long) {
        if (!valid(lat, lon)) return
        auto?.let { if (atNs < it.fix.atNs) return }
        val speed = if (horizontalSpeedMps.isFinite() && horizontalSpeedMps > 0) min(horizontalSpeedMps, cfg.maxSpeedMps) else 0.0
        val rec = AutoRec(TrustFix(lat, lon, accuracy(sigmaM), atNs), speed)
        auto = rec
        anchor?.let { an ->
            if (an.baselineM == null) startAnchor(an, rec)
            else if (atNs > an.lastNs) {
                if ((atNs - an.lastNs) / 1e9 > cfg.forgetS) {
                    // After a long gap the integral cannot know the path (a stop, a drive and a stop again can hide in it,
                    // with speed 0 on both sides): the bound starts again from where AUTO is now.
                    startAnchor(an, rec, afterGap = true)
                } else {
                    an.travelledM += max(an.lastSpeedMps, speed) * (atNs - an.lastNs) / 1e9
                    an.lastNs = atNs
                    an.lastSpeedMps = speed
                }
            }
        }
        compare()
    }

    /** A dish STARLINK-only fix at [atNs]; call it with each one the dish gives, frozen or not. */
    @Synchronized
    fun onStarlinkOnlyFix(lat: Double, lon: Double, atNs: Long) {
        if (!valid(lat, lon) || atNs < lastStarlinkOnlyNs) return
        lastStarlinkOnlyNs = atNs
        val an = anchor
        if (an != null && Geo.distanceM(an.lat, an.lon, lat, lon) <= cfg.frozenChangeM) return
        val fresh = Anchor(lat, lon, observedChange = an != null)
        anchor = fresh
        auto?.let { if (abs(it.fix.atNs - atNs) / 1e9 <= cfg.maxSkewS) startAnchor(fresh, it) }
        compare()
    }

    @Synchronized
    fun verdict(nowNs: Long): GpsTrustVerdict {
        val g = gps ?: return GpsTrustVerdict(GpsTrustState.UNKNOWN, unknown = UnknownWhy.NO_GPS_FIX)
        if (age(nowNs, g.fix.atNs) > cfg.staleS) return GpsTrustVerdict(GpsTrustState.UNKNOWN, unknown = UnknownWhy.NO_GPS_FIX)
        if (g.isMock) return GpsTrustVerdict(GpsTrustState.MOCKED)
        val cmp = comparison
        val cmpAge = if (cmp == null) Double.MAX_VALUE else age(nowNs, cmp.atNs)
        val reasons = ArrayList<DistrustReason>(3)
        jump?.let { if (nowNs < jumpUntilNs) reasons += it }
        if (farActive && cmpAge <= cfg.forgetS) {
            reasons += DistrustReason.FarFromStarlink(farDistanceM, farLimitM, farSinceNs ?: farLastNs, farLastNs)
        }
        frozen?.let { if (cmpAge <= cfg.staleS) reasons += it }
        return when {
            reasons.isNotEmpty() -> GpsTrustVerdict(GpsTrustState.DISTRUSTED, reasons)
            cmpAge <= cfg.staleS -> GpsTrustVerdict(GpsTrustState.TRUSTED)
            else -> GpsTrustVerdict(GpsTrustState.UNKNOWN, unknown = UnknownWhy.NOTHING_TO_COMPARE)
        }
    }

    /**
     * The position to centre the map on and to open in Google Maps: GPS when [verdict] is TRUSTED,
     * else [served] (the position the mock serves, pass it only while the mock is on), else the dish
     * AUTO fix, else the GPS fix marked [ChosenPosition.unverified] when [verdict] is UNKNOWN (never a
     * distrusted or mock fix), else null. A GPS or AUTO fix older than [GpsTrustConfig.staleS] does not count, and
     * neither does a GPS fix that is over the rule 1 limit from the dish while the 5 s are still running.
     */
    @Synchronized
    fun choose(nowNs: Long, served: TrustFix? = null): ChosenPosition? {
        // A GPS that is over the limit from the dish but has not stayed there for 5 s yet (rule 1 is still timing it) is
        // not trusted for this purpose: the position the user is about to open in a maps app is not a possible spoof.
        val pendingFar = comparison?.let { it.distanceM > it.limitM && age(nowNs, it.atNs) <= cfg.staleS } == true
        val g = gps?.fix?.takeIf { age(nowNs, it.atNs) <= cfg.staleS && !pendingFar }
        val a = auto?.fix?.takeIf { age(nowNs, it.atNs) <= cfg.staleS }
        return choose(verdict(nowNs), g, a, served)
    }

    private fun age(nowNs: Long, atNs: Long): Double = (nowNs - atNs) / 1e9

    private fun accuracy(m: Double): Double = if (m.isFinite() && m > 0) min(m, cfg.maxAccuracyM) else 0.0

    private fun valid(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && abs(lat) <= 90 && abs(lon) <= 180 && !(lat == 0.0 && lon == 0.0)

    private fun resetGpsRules() {
        realGps = null
        jump = null
        jumpUntilNs = Long.MIN_VALUE
        farSinceNs = null
        nearSinceNs = null
        farActive = false
        frozen = null
        comparison = null
    }

    /** Rule 2. */
    private fun checkJump(fix: TrustFix) {
        val p = realGps
        realGps = fix
        if (p == null) return
        val distM = Geo.distanceM(p.lat, p.lon, fix.lat, fix.lon)
        // A re-acquisition moves the fix within its own error: only what lies beyond both accuracies is a jump.
        val netM = max(distM - p.accuracyM - fix.accuracyM, 0.0)
        val dtS = max((fix.atNs - p.atNs) / 1e9, MIN_DT_S)
        val speed = netM / dtS
        if (speed > cfg.jumpSpeedMps || (netM > cfg.jumpDistM && dtS < cfg.jumpWithinS)) {
            jump = DistrustReason.ImpossibleJump(distM, dtS, speed, fix.atNs)
            jumpUntilNs = fix.atNs + (cfg.jumpLatchS * 1e9).toLong()
        }
    }

    /** Sets the allowance of a new anchor from the AUTO fix [a] at hand. */
    private fun startAnchor(an: Anchor, a: AutoRec, afterGap: Boolean = false) {
        val d = Geo.distanceM(an.lat, an.lon, a.fix.lat, a.fix.lon)
        an.baselineM = if (!afterGap && an.observedChange && a.speedMps <= cfg.stationaryMps) 0.0 else d
        an.travelledM = 0.0
        an.lastNs = a.fix.atNs
        an.lastSpeedMps = a.speedMps
    }

    /** Rules 1 and 3 on the latest real GPS fix and the latest AUTO fix, if they are close enough in time. */
    private fun compare() {
        val g = gps?.takeIf { !it.isMock } ?: return
        val a = auto ?: return
        val skewS = abs(g.fix.atNs - a.fix.atNs) / 1e9
        if (skewS > cfg.maxSkewS) return
        val t = max(g.fix.atNs, a.fix.atNs)

        comparison?.let { prev ->
            val gapS = (t - prev.atNs) / 1e9
            if (gapS > cfg.forgetS) {
                farActive = false
                farSinceNs = null
                nearSinceNs = null
            } else if (gapS > cfg.compareGapS) {
                if (!farActive) farSinceNs = null
                nearSinceNs = null
            }
        }

        val distM = Geo.distanceM(g.fix.lat, g.fix.lon, a.fix.lat, a.fix.lon)
        val limitM = cfg.farMarginM + g.fix.accuracyM + a.fix.accuracyM + a.speedMps * skewS
        comparison = TrustComparison(distM, limitM, t)
        if (distM > limitM) {
            farDistanceM = distM
            farLimitM = limitM
            farLastNs = t
            nearSinceNs = null
            if (!farActive) {
                val since = farSinceNs ?: t
                farSinceNs = since
                if ((t - since) / 1e9 >= cfg.farSustainS) farActive = true
            }
        } else if (farActive) {
            val since = nearSinceNs ?: t
            nearSinceNs = since
            if ((t - since) / 1e9 >= cfg.farClearS) {
                farActive = false
                farSinceNs = null
                nearSinceNs = null
            }
        } else {
            farSinceNs = null
        }

        frozen = null
        val an = anchor
        val baseline = an?.baselineM
        if (an != null && baseline != null) {
            val limit = cfg.frozenMarginM + baseline + an.travelledM
            val dg = Geo.distanceM(g.fix.lat, g.fix.lon, an.lat, an.lon)
            val da = Geo.distanceM(a.fix.lat, a.fix.lon, an.lat, an.lon)
            if (dg > limit && da > limit) frozen = DistrustReason.FarFromStarlinkOnly(dg, da, limit, baseline, an.travelledM)
        }
    }

    companion object {
        private const val MIN_DT_S = 0.5

        /**
         * GPS when [verdict] is TRUSTED, else [served] (only while the mock is on), else [auto], else
         * [gps] marked unverified when [verdict] is UNKNOWN (nothing to compare it with), else null. A
         * distrusted GPS, and a mock fix (the app's own output or another app's), is never chosen: with no
         * Starlink position there is nothing to centre on. The fixes are the ones the caller considers current.
         */
        fun choose(verdict: GpsTrustVerdict, gps: TrustFix?, auto: TrustFix?, served: TrustFix? = null): ChosenPosition? = when {
            gps != null && verdict.state == GpsTrustState.TRUSTED -> ChosenPosition(gps.lat, gps.lon, gps.accuracyM, ChosenSource.GPS, unverified = false)
            served != null -> ChosenPosition(served.lat, served.lon, served.accuracyM, ChosenSource.SERVED, unverified = false)
            auto != null -> ChosenPosition(auto.lat, auto.lon, auto.accuracyM, ChosenSource.STARLINK, unverified = false)
            gps != null && verdict.state == GpsTrustState.UNKNOWN -> ChosenPosition(gps.lat, gps.lon, gps.accuracyM, ChosenSource.GPS, unverified = true)
            else -> null
        }
    }
}
