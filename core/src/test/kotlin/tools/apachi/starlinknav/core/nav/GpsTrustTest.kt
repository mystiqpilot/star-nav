package tools.apachi.starlinknav.core.nav

import tools.apachi.starlinknav.core.dish.Geo
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val M_PER_DEG = 6_371_008.8 * Math.PI / 180.0
private const val LAT0 = 50.0
private const val LON0 = 30.0

private fun lat(northM: Double) = LAT0 + northM / M_PER_DEG
private fun lon(eastM: Double) = LON0 + eastM / (M_PER_DEG * cos(Math.toRadians(LAT0)))
private fun ns(s: Double) = (s * 1e9).toLong()

/**
 * A car driving north on a meridian at 1 Hz. Every step feeds the dish AUTO fix first (it describes the
 * moment 0.45 s before, as on the real dish) and the GPS fix at the step's time, and returns the verdict
 * at that time. [spoofN] is an offset both GPS and the dish AUTO share (a spoof that fooled both).
 */
private class Car(val trust: GpsTrust = GpsTrust(), var n: Double = 0.0, var t: Double = 100.0) {
    var spoofN = 0.0

    fun now() = ns(t)
    fun verdict() = trust.verdict(now())

    fun run(
        seconds: Int,
        speed: Double = 20.0,
        gpsN: Double = 0.0,
        gpsE: Double = 0.0,
        gpsAcc: Double = 5.0,
        mock: Boolean = false,
        gps: Boolean = true,
        dish: Boolean = true,
        dishN: Double = 0.0,
        dishSpeed: Double = speed,
        drift: Double = 0.0,
    ): List<GpsTrustVerdict> {
        val out = ArrayList<GpsTrustVerdict>()
        repeat(seconds) {
            n += speed
            t += 1.0
            spoofN += drift
            if (dish) trust.onAutoFix(lat(n - speed * 0.45 + dishN + spoofN), LON0, 1.6, dishSpeed, ns(t - 0.45))
            if (gps) trust.onGpsFix(lat(n + gpsN + spoofN), lon(gpsE), gpsAcc, ns(t), mock)
            out += verdict()
        }
        return out
    }

    /** A Starlink-only fix at [northM] (absolute), now. */
    fun starlinkOnly(northM: Double) = trust.onStarlinkOnlyFix(lat(northM), LON0, now())
}

private fun List<GpsTrustVerdict>.states() = map { it.state }

/** Rule 2 off, so a test can move GPS in one step and look at rule 1 or 3 alone. */
private fun noJumpRule() = GpsTrust(GpsTrustConfig(jumpSpeedMps = 1e12, jumpDistM = 1e12))

class GpsTrustTest {
    @Test
    fun unknownWithoutAGpsFixOrWithoutAnythingToCompare() {
        val tr = GpsTrust()
        assertEquals(UnknownWhy.NO_GPS_FIX, tr.verdict(0).unknown)
        tr.onAutoFix(lat(0.0), LON0, 1.6, 20.0, ns(100.0))
        assertEquals(UnknownWhy.NO_GPS_FIX, tr.verdict(ns(100.0)).unknown)

        val gpsOnly = Car()
        gpsOnly.run(30, dish = false)
        val v = gpsOnly.verdict()
        assertEquals(GpsTrustState.UNKNOWN, v.state)
        assertEquals(UnknownWhy.NOTHING_TO_COMPARE, v.unknown)
        assertTrue(v.reasons.isEmpty())
    }

    @Test
    fun trustedWhenGpsAgreesWithTheDish() {
        val car = Car()
        val vs = car.run(120)
        assertTrue(vs.all { it.state == GpsTrustState.TRUSTED }, vs.states().toString())
        val cmp = assertNotNull(car.trust.lastComparison)
        assertTrue(cmp.distanceM < 15 && cmp.distanceM < cmp.limitM)
        assertEquals(GpsTrustState.TRUSTED, car.verdict().state)
    }

    @Test
    fun staleFixesDoNotCount() {
        val car = Car()
        car.run(20)
        // The GPS goes quiet: no fix for 10 s is "no fix".
        assertEquals(GpsTrustState.TRUSTED, car.trust.verdict(car.now() + ns(9.0)).state)
        assertEquals(UnknownWhy.NO_GPS_FIX, car.trust.verdict(car.now() + ns(11.0)).unknown)

        // The dish goes quiet while GPS continues: nothing to compare with after 10 s.
        val car2 = Car()
        car2.run(20)
        car2.run(8, dish = false)
        assertEquals(GpsTrustState.TRUSTED, car2.verdict().state)
        car2.run(8, dish = false)
        assertEquals(UnknownWhy.NOTHING_TO_COMPARE, car2.verdict().unknown)
        // Back: trusted again at once.
        assertEquals(GpsTrustState.TRUSTED, car2.run(1).single().state)
    }

    @Test
    fun aDishFixTooFarInTimeFromTheGpsFixIsNotComparedWith() {
        val tr = GpsTrust()
        tr.onAutoFix(lat(0.0), LON0, 1.6, 0.0, ns(100.0))
        tr.onGpsFix(lat(5000.0), LON0, 5.0, ns(104.0)) // 4 s later: no comparison, however far apart
        val v = tr.verdict(ns(104.0))
        assertEquals(GpsTrustState.UNKNOWN, v.state)
        assertEquals(UnknownWhy.NOTHING_TO_COMPARE, v.unknown)
        assertNull(tr.lastComparison)
    }

    // ---- Rule 1: far from Starlink

    @Test
    fun farFromStarlinkNeedsFiveSecondsAndClearsAfterFive() {
        val car = Car(noJumpRule())
        car.run(20)
        val far = car.run(12, gpsE = 300.0)
        assertTrue(far.take(5).all { it.state == GpsTrustState.TRUSTED }, "under 5 s it is not yet distrusted: ${far.states()}")
        assertTrue(far.drop(5).all { it.state == GpsTrustState.DISTRUSTED }, far.states().toString())
        val r = assertIs<DistrustReason.FarFromStarlink>(far.last().reason)
        assertEquals("far_from_starlink", r.code)
        assertTrue(r.distanceM in 299.0..302.0, "distance ${r.distanceM}")
        assertTrue(r.limitM in 106.0..121.0, "limit ${r.limitM}") // 100 + 5 + 1.6 + the dish's travel across the time difference
        assertEquals(ns(101.0 + 20.0), r.sinceNs)

        val back = car.run(8)
        assertTrue(back.take(5).all { it.state == GpsTrustState.DISTRUSTED }, "clears only after 5 s within: ${back.states()}")
        assertTrue(back.drop(5).all { it.state == GpsTrustState.TRUSTED }, back.states().toString())
    }

    @Test
    fun aShortExcursionIsNotDistrusted() {
        val car = Car(noJumpRule())
        car.run(20)
        val vs = car.run(4, gpsE = 400.0) + car.run(4) + car.run(4, gpsE = 400.0) + car.run(10)
        assertTrue(vs.all { it.state == GpsTrustState.TRUSTED }, vs.states().toString())
    }

    @Test
    fun aBackAndForthDuringTheClearingKeepsTheDistrust() {
        val car = Car(noJumpRule())
        car.run(20)
        car.run(8, gpsE = 300.0)
        assertEquals(GpsTrustState.DISTRUSTED, car.verdict().state)
        val vs = car.run(3) + car.run(2, gpsE = 300.0) + car.run(3)
        // Three within, two over again: the 5 s start over, so still distrusted after three more.
        assertTrue(vs.all { it.state == GpsTrustState.DISTRUSTED }, vs.states().toString())
    }

    @Test
    fun theLimitGrowsWithTheClaimedAccuracies() {
        val bad = Car(noJumpRule())
        bad.run(10)
        // 150 m off with a 5 m claim: over 100 + 5 + 1.6 + ~10.
        assertEquals(GpsTrustState.DISTRUSTED, bad.run(10, gpsE = 150.0).last().state)

        val honestlyBad = Car(noJumpRule())
        honestlyBad.run(10)
        // The same 150 m with a claimed 60 m accuracy (an urban canyon) is within.
        assertEquals(GpsTrustState.TRUSTED, honestlyBad.run(10, gpsE = 150.0, gpsAcc = 60.0).last().state)

        // An absurd claim does not buy unbounded room: capped at 300 m.
        val liar = Car(noJumpRule())
        liar.run(10)
        assertEquals(GpsTrustState.TRUSTED, liar.run(10, gpsE = 350.0, gpsAcc = 1e6).last().state)
        assertEquals(GpsTrustState.DISTRUSTED, liar.run(10, gpsE = 600.0, gpsAcc = 1e6).last().state)
    }

    @Test
    fun theFixTimeDifferenceIsAllowedForAtDishSpeed() {
        // The AUTO fix is 2.5 s older than the GPS fix: at 40 m/s the car has moved 100 m since, so a GPS fix
        // 190 m ahead of it is 90 m off, not 190.
        val tr = GpsTrust()
        tr.onAutoFix(lat(0.0), LON0, 1.6, 40.0, ns(100.0))
        tr.onGpsFix(lat(190.0), LON0, 5.0, ns(102.5))
        val cmp = assertNotNull(tr.lastComparison)
        assertTrue(cmp.distanceM in 189.0..191.0)
        assertTrue(cmp.limitM in 205.0..208.0, "limit ${cmp.limitM}") // 100 + 5 + 1.6 + 40 * 2.5
        assertEquals(GpsTrustState.TRUSTED, tr.verdict(ns(102.5)).state)
    }

    @Test
    fun anEstablishedDistrustSurvivesAShortDishGapAndIsForgottenAfterALongOne() {
        // A 20 s cut-off: still distrusted when the dish returns and the GPS is still far.
        val short = Car(noJumpRule())
        short.run(20)
        short.run(8, gpsE = 300.0)
        assertEquals(GpsTrustState.DISTRUSTED, short.verdict().state)
        short.run(20, gpsE = 300.0, dish = false)
        // Not provable now (nothing to compare) but the distrust stands for a minute.
        assertEquals(GpsTrustState.DISTRUSTED, short.verdict().state)
        assertEquals(GpsTrustState.DISTRUSTED, short.run(1, gpsE = 300.0).single().state)
        assertEquals(GpsTrustState.TRUSTED, short.run(8).last().state)

        // After 70 s without the dish the old verdict is dropped: the 5 s start over.
        val long = Car(noJumpRule())
        long.run(20)
        long.run(8, gpsE = 300.0)
        long.run(70, gpsE = 300.0, dish = false)
        assertEquals(GpsTrustState.UNKNOWN, long.verdict().state)
        val back = long.run(10, gpsE = 300.0)
        assertTrue(back.take(4).all { it.state == GpsTrustState.TRUSTED }, back.states().toString())
        assertEquals(GpsTrustState.DISTRUSTED, back.last().state)
    }

    // ---- Rule 2: impossible jump

    @Test
    fun aJumpAtMoreThan100MetresPerSecondIsDistrustedForThirtySeconds() {
        val car = Car()
        car.run(20)
        // Only GPS jumps (the dish does not follow): 200 m in 1 s = 200 m/s.
        val j = car.run(1, gpsN = 200.0).single()
        assertEquals(GpsTrustState.DISTRUSTED, j.state)
        val r = assertIs<DistrustReason.ImpossibleJump>(j.reason)
        assertEquals("impossible_jump", r.code)
        assertTrue(r.jumpM in 215.0..225.0, "jump ${r.jumpM}") // 200 m on top of the 20 m of driving
        assertEquals(1.0, r.dtS, 1e-9)
        assertTrue(r.speedMps in 205.0..215.0, "speed ${r.speedMps}") // the same less both accuracies (5 m each)

        // Back on the road at once: the latch holds 30 s from the jump, whatever rule 1 says.
        val after = car.run(35)
        assertTrue(after.take(29).all { it.state == GpsTrustState.DISTRUSTED }, after.states().toString())
        assertTrue(after.drop(30).all { it.state == GpsTrustState.TRUSTED }, after.states().toString())
        // The way back is a jump as well (-200 m): the latch started again from it.
        assertEquals(GpsTrustState.DISTRUSTED, after.first().state)
    }

    @Test
    fun aJumpIsCaughtWithoutAnyDishFix() {
        val car = Car()
        car.run(20, dish = false)
        val j = car.run(1, dish = false, gpsN = 1500.0).single()
        assertEquals(GpsTrustState.DISTRUSTED, j.state)
        assertIs<DistrustReason.ImpossibleJump>(j.reason)
        // Both fixes moved: afterwards nothing to compare with but the latch ends after 30 s.
        val after = car.run(40, dish = false)
        assertEquals(GpsTrustState.DISTRUSTED, after[28].state)
        assertEquals(GpsTrustState.UNKNOWN, after[31].state)
    }

    @Test
    fun theSpeedLimitIsStrictlyAbove100OnTheDistanceBeyondBothAccuracies() {
        fun verdictAfterStep(metres: Double, acc: Double = 5.0): GpsTrustState {
            val tr = GpsTrust()
            tr.onGpsFix(lat(0.0), LON0, acc, ns(100.0))
            tr.onGpsFix(lat(metres), LON0, acc, ns(101.0))
            return tr.verdict(ns(101.0)).let { if (it.reason is DistrustReason.ImpossibleJump) GpsTrustState.DISTRUSTED else GpsTrustState.TRUSTED }
        }
        // 5 m each: 10 m of the step is within the fixes' own error.
        assertEquals(GpsTrustState.TRUSTED, verdictAfterStep(109.0))
        assertEquals(GpsTrustState.DISTRUSTED, verdictAfterStep(111.0))
        // The same 111 m with 20 m accuracies is a re-acquisition within the error.
        assertEquals(GpsTrustState.TRUSTED, verdictAfterStep(111.0, acc = 20.0))
        assertEquals(GpsTrustState.DISTRUSTED, verdictAfterStep(141.0, acc = 20.0))
    }

    @Test
    fun aReacquisitionJumpWithinTheFixesOwnErrorIsNoJump() {
        // A poor fix (150 m) is replaced by a good one 120 m away half a second later: 240 m/s on the raw distance, but
        // the fixes could not tell the two places apart.
        val tr = GpsTrust()
        tr.onGpsFix(lat(0.0), LON0, 150.0, ns(100.0))
        tr.onGpsFix(lat(120.0), LON0, 5.0, ns(100.5))
        assertEquals(emptyList(), tr.verdict(ns(100.5)).reasons)
        // A real jump of 1.4 km in half a second with good fixes on both sides still is one.
        val bad = GpsTrust()
        bad.onGpsFix(lat(0.0), LON0, 5.0, ns(100.0))
        bad.onGpsFix(lat(1400.0), LON0, 5.0, ns(100.5))
        assertIs<DistrustReason.ImpossibleJump>(bad.verdict(ns(100.5)).reason)
    }

    @Test
    fun aKilometreInUnderTwoSecondsIsImpossibleWhateverTheClock() {
        val tr = GpsTrust()
        tr.onGpsFix(lat(0.0), LON0, 5.0, ns(100.0))
        // Same timestamp, 1.2 km apart (a duplicate-time spoof): dt counts as 0.5 s.
        tr.onGpsFix(lat(1200.0), LON0, 5.0, ns(100.0))
        assertIs<DistrustReason.ImpossibleJump>(tr.verdict(ns(100.0)).reason)

        // The same step spread over 40 s (30 m/s) is a drive.
        val ok = GpsTrust()
        ok.onGpsFix(lat(0.0), LON0, 5.0, ns(100.0))
        ok.onGpsFix(lat(1200.0), LON0, 5.0, ns(140.0))
        assertEquals(emptyList(), ok.verdict(ns(140.0)).reasons)
    }

    @Test
    fun normalDrivingAndDuplicatesAreNoJump() {
        val car = Car()
        val vs = car.run(120, speed = 33.0)
        assertTrue(vs.all { it.state == GpsTrustState.TRUSTED })
        val tr = GpsTrust()
        tr.onGpsFix(lat(0.0), LON0, 5.0, ns(100.0))
        tr.onGpsFix(lat(5.0), LON0, 5.0, ns(100.0)) // same time, 5 m
        tr.onGpsFix(lat(6.0), LON0, 5.0, ns(100.2))
        assertEquals(emptyList(), tr.verdict(ns(100.2)).reasons)
    }

    // ---- Rule 3: both far from the Starlink-only fix

    @Test
    fun aFrozenStarlinkOnlyFixDuringALongDriveIsNoFalseAlarm() {
        val car = Car()
        car.run(3, speed = 0.0)
        car.starlinkOnly(0.0)
        // An hour at 20 m/s = 72 km, GPS and the dish agreeing; the Starlink-only fix never changes.
        var bad = 0
        repeat(36) { chunk ->
            car.run(100)
            // The dish keeps giving the frozen fix with its jitter.
            car.trust.onStarlinkOnlyFix(lat(0.0) + (if (chunk % 2 == 0) 3e-5 else -3e-5), LON0, car.now())
            bad += car.verdict().let { if (it.state == GpsTrustState.TRUSTED) 0 else 1 }
        }
        assertEquals(0, bad)
        assertEquals(GpsTrustState.TRUSTED, car.verdict().state)
        assertTrue(Geo.distanceM(LAT0, LON0, lat(car.n), LON0) > 70_000)
    }

    @Test
    fun aFixFirstSeenFarFromTheDishMidDriveIsNoFalseAlarmEither() {
        val car = Car(n = 300_000.0)
        car.run(30)
        car.starlinkOnly(0.0) // the app was started 300 km into the drive
        val vs = car.run(600)
        assertTrue(vs.all { it.state == GpsTrustState.TRUSTED }, vs.states().toString())
    }

    @Test
    fun bothFarFromTheStarlinkOnlyFixBeyondTheTravelledPathIsDistrusted() {
        val car = Car()
        car.run(3, speed = 0.0)
        car.starlinkOnly(0.0)
        car.run(60)
        // A spoof walks both GPS and the dish away at 70 m/s on top of the real 20 m/s (90 m/s: no
        // jump); the dish reports 20 m/s. Limit = 5 km + path driven (20 m/s), position = 90 m/s:
        // crossed at about 71 s after the spoof starts (the 60 s of honest driving before count too).
        val vs = car.run(100, drift = 70.0)
        val first = vs.indexOfFirst { it.state == GpsTrustState.DISTRUSTED }
        assertTrue(first in 68..75, "first distrust at step $first: ${vs.states()}")
        val r = assertIs<DistrustReason.FarFromStarlinkOnly>(vs[first].reason)
        assertEquals("far_from_starlink_only", r.code)
        assertTrue(r.gpsM > r.limitM && r.dishM > r.limitM)
        assertTrue(r.limitM in 5000.0 + r.travelledM - 1.0..5000.0 + r.travelledM + r.baselineM + 1.0)
        assertTrue(r.travelledM in 2_400.0..2_800.0, "travelled ${r.travelledM}") // the integrated AUTO speed: 20 m/s for ~131 s
        assertTrue(vs.drop(first).all { it.state == GpsTrustState.DISTRUSTED })
        // The honest rule 1 never fired: GPS and the dish agree.
        assertTrue(vs.none { r1 -> r1.reasons.any { it is DistrustReason.FarFromStarlink } })
    }

    @Test
    fun theJitterOfTheStarlinkOnlyFixDoesNotRestartThePathCount() {
        val car = Car()
        car.run(3, speed = 0.0)
        car.starlinkOnly(0.0)
        // A 3 m jitter every 5 s must not make a new anchor: seen while moving, a new anchor would take the
        // distance of that moment as its allowance and so cancel the rule for good.
        var driftStartS = Double.NaN
        var firstDistrustS = Double.NaN
        repeat(36) { i ->
            val drifting = i >= 12
            if (drifting && driftStartS.isNaN()) driftStartS = car.t
            car.run(5, drift = if (drifting) 70.0 else 0.0)
            car.trust.onStarlinkOnlyFix(lat(if (i % 2 == 0) 3.0 else -3.0), LON0, car.now())
            if (firstDistrustS.isNaN() && car.verdict().state == GpsTrustState.DISTRUSTED) firstDistrustS = car.t
        }
        assertTrue(firstDistrustS - driftStartS in 70.0..80.0, "distrust ${firstDistrustS - driftStartS} s into the spoof")
    }

    @Test
    fun aStarlinkOnlyFixThatChangesWhileTheDishStandsMustBeNextToIt() {
        val car = Car()
        car.run(3, speed = 0.0)
        car.starlinkOnly(0.0)
        car.run(60, speed = 0.0)
        // Parked: the dish and phone stay put while the Starlink-only fix is updated 20 km away.
        car.starlinkOnly(20_000.0)
        val v = car.run(2, speed = 0.0).last()
        assertEquals(GpsTrustState.DISTRUSTED, v.state)
        assertIs<DistrustReason.FarFromStarlinkOnly>(v.reason)
        // ... and updated next to it, it is fine.
        val ok = Car()
        ok.run(3, speed = 0.0)
        ok.starlinkOnly(0.0)
        ok.run(30, speed = 0.0)
        ok.starlinkOnly(40.0)
        assertTrue(ok.run(10, speed = 0.0).all { it.state == GpsTrustState.TRUSTED })
    }

    @Test
    fun aChangeSeenWhileMovingCountsFromTheDistanceThen() {
        val car = Car()
        car.run(3)
        car.starlinkOnly(0.0)
        car.run(300) // 6 km from the first fix, still driving
        car.starlinkOnly(-4000.0) // updated to an old place: 10 km behind -> baseline 10 km
        assertTrue(car.run(120).all { it.state == GpsTrustState.TRUSTED })
    }

    @Test
    fun theTravelledPathCountsOnlySinceTheFixLastChanged() {
        val car = Car(noJumpRule())
        car.run(3, speed = 0.0)
        car.starlinkOnly(0.0)
        car.run(1000) // 20 km driven
        car.run(5, speed = 0.0)
        car.starlinkOnly(car.n) // between drives it is renewed here; the dish stands: baseline 0
        car.run(5, speed = 0.0)
        // Now only the path since counts: both walk 7 km away while the dish reports standing still.
        val vs = car.run(10, speed = 0.0, dishSpeed = 0.0, drift = 700.0)
        assertTrue(vs.any { v -> v.reasons.any { it is DistrustReason.FarFromStarlinkOnly } }, vs.states().toString())
    }

    @Test
    fun aDishGapDoesNotShrinkTheTravelledPath() {
        // A 10 minute outage at 20 m/s is 12 km the integral cannot see between two AUTO fixes: it takes the larger
        // speed of the two sides, so the bound grows by 12 km and no false alarm follows.
        val car = Car()
        car.run(3, speed = 0.0)
        car.starlinkOnly(0.0)
        car.run(30)
        car.run(600, dish = false)
        val vs = car.run(120)
        assertTrue(vs.drop(1).all { it.state == GpsTrustState.TRUSTED }, vs.states().toString())
    }

    @Test
    fun aLongDishGapWithAStopAndADriveHiddenInItIsNoFalseAlarm() {
        // The dish is off for ten minutes: the car stands, drives 12 km and stands again, so AUTO speed is 0 on both
        // sides of the gap and the integral adds nothing. The bound starts again from where AUTO is when it comes back.
        val car = Car()
        car.run(3, speed = 0.0)
        car.starlinkOnly(0.0)
        car.run(30, speed = 0.0)
        car.run(600, dish = false) // drives 12 km with no AUTO fix
        car.run(5, dish = false, speed = 0.0)
        val vs = car.run(120, speed = 0.0)
        assertTrue(vs.drop(1).all { it.state == GpsTrustState.TRUSTED }, vs.states().toString())
        // A spoof that walks both away after that is still caught against the new bound.
        val spoof = car.run(150, speed = 0.0, dishSpeed = 0.0, drift = 100.0)
        assertTrue(spoof.any { v -> v.reasons.any { it is DistrustReason.FarFromStarlinkOnly } }, spoof.states().toString())
    }

    @Test
    fun oneSideNearTheStarlinkOnlyFixIsNotRule3() {
        // GPS far, the dish near: that is rule 1's case, not rule 3's.
        val car = Car()
        car.run(3, speed = 0.0)
        car.starlinkOnly(0.0)
        car.run(30, speed = 0.0)
        val vs = car.run(10, speed = 0.0, gpsN = 8000.0)
        assertTrue(vs.none { r -> r.reasons.any { it is DistrustReason.FarFromStarlinkOnly } })
        assertEquals(GpsTrustState.DISTRUSTED, vs.last().state)
    }

    // ---- Mock

    @Test
    fun aMockFixIsMockedWhateverTheNumbersSay() {
        val car = Car()
        car.run(20)
        val vs = car.run(10, gpsE = 5000.0, mock = true)
        assertTrue(vs.all { it.state == GpsTrustState.MOCKED }, vs.states().toString())
        assertTrue(vs.all { it.reasons.isEmpty() && it.unknown == null })
        // Mock fixes that stop coming are "no fix" after 10 s.
        assertEquals(UnknownWhy.NO_GPS_FIX, car.trust.verdict(car.now() + ns(12.0)).unknown)
    }

    @Test
    fun theMockWipesTheStateOfTheRules() {
        val car = Car()
        car.run(20)
        car.run(10, gpsE = 300.0)
        assertEquals(GpsTrustState.DISTRUSTED, car.verdict().state) // rule 1 established
        car.run(1, gpsN = 300.0) // and a jump latched
        car.run(5, mock = true)
        // The mock goes off with the real GPS 5 km from where it was: no jump from the old real fix.
        val first = car.run(1, gpsN = 5000.0).single()
        assertTrue(first.reasons.none { it is DistrustReason.ImpossibleJump }, first.toString())
        assertEquals(GpsTrustState.TRUSTED, first.state) // pending only
        // 5 s of it being 5 km from the dish is rule 1 again.
        assertEquals(GpsTrustState.DISTRUSTED, car.run(8, gpsN = 5000.0).last().state)
    }

    @Test
    fun theMockTurnedOnAndOffAgainCanBeTrustedAgain() {
        val car = Car()
        car.run(20)
        car.run(20, mock = true)
        val vs = car.run(10)
        assertTrue(vs.all { it.state == GpsTrustState.TRUSTED }, vs.states().toString())
    }

    @Test
    fun forgettingTheGpsWhenTheMockGoesOffShowsNoFixNotMocked() {
        val car = Car()
        car.run(20)
        car.run(10, mock = true)
        assertEquals(GpsTrustState.MOCKED, car.verdict().state)
        car.trust.forgetGps(car.now())
        // Until a real fix arrives there is no GPS fix: neither "mocked" nor a distrust.
        assertEquals(UnknownWhy.NO_GPS_FIX, car.verdict().unknown)
        assertNull(car.trust.latestGps)
        // A mock fix still in flight from before the switch-off is dropped ...
        car.trust.onGpsFix(lat(car.n), LON0, 5.0, car.now() - ns(0.2), isMock = true)
        assertNull(car.trust.latestGps)
        // ... a real fix is taken, and a mock fix made later (another app's mock) counts again.
        car.run(1)
        assertEquals(GpsTrustState.TRUSTED, car.verdict().state)
        car.run(1, mock = true)
        assertEquals(GpsTrustState.MOCKED, car.verdict().state)
    }

    // ---- Input hygiene

    @Test
    fun badAndOutOfOrderFixesAreDropped() {
        val tr = GpsTrust()
        tr.onGpsFix(Double.NaN, 30.0, 5.0, ns(100.0))
        tr.onGpsFix(0.0, 0.0, 5.0, ns(100.0))
        tr.onGpsFix(91.0, 30.0, 5.0, ns(100.0))
        assertNull(tr.latestGps)
        tr.onGpsFix(lat(0.0), LON0, 5.0, ns(110.0))
        tr.onGpsFix(lat(5000.0), LON0, 5.0, ns(105.0)) // older than the last: dropped, no jump
        assertEquals(lat(0.0), tr.latestGps?.lat)
        assertEquals(emptyList(), tr.verdict(ns(110.0)).reasons)
        tr.onAutoFix(0.0, 0.0, 1.6, 10.0, ns(110.0))
        assertNull(tr.latestAuto)
        tr.onAutoFix(lat(0.0), LON0, Double.NaN, Double.NaN, ns(110.0))
        assertEquals(0.0, tr.latestAuto?.accuracyM)
        assertEquals(GpsTrustState.TRUSTED, tr.verdict(ns(110.0)).state)
    }

    @Test
    fun resetForgetsEverything() {
        val car = Car()
        car.run(20)
        car.run(1, gpsN = 500.0)
        assertEquals(GpsTrustState.DISTRUSTED, car.verdict().state)
        car.trust.reset()
        assertEquals(UnknownWhy.NO_GPS_FIX, car.verdict().unknown)
        assertNull(car.trust.latestGps)
        assertNull(car.trust.lastComparison)
    }

    @Test
    fun severalReasonsAreListedMostAcuteFirst() {
        val car = Car()
        car.run(3, speed = 0.0)
        car.starlinkOnly(0.0)
        car.run(30, speed = 0.0)
        // GPS jumps 30 km away and stays: jump now, rule 1 after 5 s, and rule 3 once the dish is far too.
        val vs = car.run(8, speed = 0.0, gpsN = 30_000.0)
        val reasons = vs.last().reasons
        assertEquals(listOf("impossible_jump", "far_from_starlink"), reasons.map { it.code })
        assertEquals(reasons.first(), vs.last().reason)
    }

    // ---- Chooser

    private val gpsFix = TrustFix(50.0, 30.0, 4.0, 1)
    private val autoFix = TrustFix(50.001, 30.001, 1.6, 1)
    private val served = TrustFix(50.002, 30.002, 7.0, 1)

    @Test
    fun theChooserTakesGpsWhenTrustedThenTheServedPositionThenTheDishThenGpsWithAWarningOnlyWhenUnknown() {
        val trusted = GpsTrustVerdict(GpsTrustState.TRUSTED)
        val distrusted = GpsTrustVerdict(GpsTrustState.DISTRUSTED, listOf(DistrustReason.ImpossibleJump(2000.0, 1.0, 2000.0, 1)))
        val unknown = GpsTrustVerdict(GpsTrustState.UNKNOWN, unknown = UnknownWhy.NOTHING_TO_COMPARE)
        val mocked = GpsTrustVerdict(GpsTrustState.MOCKED)

        assertEquals(ChosenPosition(50.0, 30.0, 4.0, ChosenSource.GPS, false), GpsTrust.choose(trusted, gpsFix, autoFix, served))
        assertEquals(ChosenPosition(50.002, 30.002, 7.0, ChosenSource.SERVED, false), GpsTrust.choose(distrusted, gpsFix, autoFix, served))
        assertEquals(ChosenPosition(50.002, 30.002, 7.0, ChosenSource.SERVED, false), GpsTrust.choose(mocked, gpsFix, autoFix, served))
        assertEquals(ChosenPosition(50.001, 30.001, 1.6, ChosenSource.STARLINK, false), GpsTrust.choose(distrusted, gpsFix, autoFix, null))
        assertEquals(ChosenPosition(50.001, 30.001, 1.6, ChosenSource.STARLINK, false), GpsTrust.choose(unknown, gpsFix, autoFix))
        // No Starlink position: a GPS nothing could be compared with is offered with a warning ...
        assertEquals(ChosenPosition(50.0, 30.0, 4.0, ChosenSource.GPS, true), GpsTrust.choose(unknown, gpsFix, null))
        // ... but a GPS the checks distrust, or a mock fix (the app's own output or another app's), is never chosen.
        assertNull(GpsTrust.choose(distrusted, gpsFix, null))
        assertNull(GpsTrust.choose(mocked, gpsFix, null))
        assertNull(GpsTrust.choose(unknown, null, null))
        // A trusted verdict without a GPS fix to give (it just went stale) falls through.
        assertEquals(ChosenSource.STARLINK, GpsTrust.choose(trusted, null, autoFix)?.source)
        assertNull(GpsTrust.choose(trusted, null, null))
    }

    @Test
    fun theChooserDoesNotTakeAGpsThatIsOverTheLimitWhileRule1IsStillTiming() {
        val car = Car(noJumpRule())
        car.run(20)
        // 300 m off for 3 s: the verdict is still TRUSTED (5 s sustain), but the position to open in Maps is the dish's.
        val vs = car.run(3, gpsE = 300.0)
        assertTrue(vs.all { it.state == GpsTrustState.TRUSTED }, vs.states().toString())
        val c = assertNotNull(car.trust.choose(car.now()))
        assertEquals(ChosenSource.STARLINK, c.source)
        // Back within the limit: the GPS again.
        car.run(3)
        assertEquals(ChosenSource.GPS, car.trust.choose(car.now())?.source)
    }

    @Test
    fun theChooserOnALiveTracker() {
        val car = Car()
        car.run(20)
        val g = assertNotNull(car.trust.choose(car.now()))
        assertEquals(ChosenSource.GPS, g.source)
        assertEquals(false, g.unverified)
        assertEquals(car.trust.latestGps?.lat, g.lat)

        // GPS spoofed (jumps): the dish's position is chosen, unchanged.
        car.run(1, gpsN = 3000.0)
        val s = assertNotNull(car.trust.choose(car.now()))
        assertEquals(ChosenSource.STARLINK, s.source)
        assertEquals(car.trust.latestAuto?.lat, s.lat)

        // The mock is on: the served position wins over the dish's own fix.
        val sv = TrustFix(lat(car.n + 10.0), LON0, 6.0, car.now())
        assertEquals(ChosenSource.SERVED, car.trust.choose(car.now(), sv)?.source)

        // The dish goes dark for 15 s while GPS stays jumpy: the GPS is distrusted (the far rule stays up to 60 s) and
        // there is no Starlink position, so there is nothing to choose; the spoofed fix is not offered.
        car.run(15, dish = false, gpsN = 3000.0)
        assertEquals(GpsTrustState.DISTRUSTED, car.verdict().state)
        assertNull(car.trust.choose(car.now()))

        // The same dark dish with a quiet GPS (nothing says it is wrong, nothing to compare it with): GPS with a warning.
        val quiet = Car()
        quiet.run(20)
        quiet.run(15, dish = false)
        assertEquals(UnknownWhy.NOTHING_TO_COMPARE, quiet.verdict().unknown)
        val w = assertNotNull(quiet.trust.choose(quiet.now()))
        assertEquals(ChosenSource.GPS, w.source)
        assertEquals(true, w.unverified)

        // Everything quiet for a minute: nothing to choose.
        assertNull(car.trust.choose(car.now() + ns(60.0)))
    }
}
