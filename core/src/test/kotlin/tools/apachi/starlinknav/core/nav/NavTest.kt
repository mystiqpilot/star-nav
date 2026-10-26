package tools.apachi.starlinknav.core.nav

import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.DishFix
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.core.dish.GrpcStatus
import tools.apachi.starlinknav.core.dish.PositionSource
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.proto.GetLocationResponse
import tools.apachi.starlinknav.core.dish.proto.LLAPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SEC = 1_000_000_000L

private fun ok(kind: DishCallKind, response: DeviceResponse) =
    DishCallResult(kind, 0, 0, "test", 200, GrpcStatus.OK, null, ByteArray(0), response, null)

fun status(code: Int) = ok(DishCallKind.STATUS, DeviceResponse(dish_get_status = DishGetStatusResponse(disablement_code = code)))

fun auto(speedMps: Double, lat: Double = 50.0, lon: Double = 30.0) = ok(
    DishCallKind.LOCATION_AUTO,
    DeviceResponse(get_location = GetLocationResponse(LLAPosition(lat, lon, 100.0), source = PositionSource.STARLINK.code, sigma_m = 1.6, horizontal_speed_mps = speedMps)),
)

fun refused(kind: DishCallKind = DishCallKind.LOCATION_AUTO) =
    DishCallResult(kind, 0, 0, "test", 200, GrpcStatus.PERMISSION_DENIED, "GetLocation requests disabled due to UT disablement", null, null, null)

class CutoffTrackerTest {
    @Test
    fun followsTheSpeedPolicyDisablementLikeDrive2() {
        val changes = mutableListOf<Cutoff>()
        val tracker = CutoffTracker { changes += it }
        for (s in 0..60) {
            tracker.onCall(status(1), s * SEC)
            tracker.onCall(auto(if (s == 30) 23.0 else 21.0), s * SEC + SEC / 2)
        }
        // The refusal arrives before the status names the code, as on the real dish.
        tracker.onCall(refused(), 61 * SEC)
        assertEquals(0, tracker.active?.code)
        tracker.onCall(status(14), 61 * SEC + SEC / 2)
        for (s in 62..90) {
            tracker.onCall(refused(DishCallKind.LOCATION_GPS), s * SEC)
            tracker.onCall(status(14), s * SEC + SEC / 2)
        }
        tracker.onCall(status(1), 91 * SEC)
        tracker.onCall(auto(5.0), 91 * SEC + SEC / 2)

        assertNull(tracker.active)
        val c = tracker.finished.single()
        assertEquals(61 * SEC, c.startNs)
        assertEquals(91 * SEC, c.endNs)
        assertEquals(14, c.code)
        assertEquals(30.0, c.durationS(0), 1e-9)
        assertEquals(21.0, c.speedBeforeMps)
        assertEquals(23.0, c.maxSpeed60sMps)
        assertEquals(5.0, c.speedAfterMps)
        // start, code named, end, speed after
        assertEquals(listOf(null, null, 91 * SEC, 91 * SEC), changes.map { it.endNs })
        assertEquals(listOf(0, 14, 14, 14), changes.map { it.code })
        assertEquals(5.0, changes.last().speedAfterMps)
    }

    @Test
    fun aRefusalRightAfterTheEndReopensTheSameCutoff() {
        val tracker = CutoffTracker()
        tracker.onCall(auto(20.0), 0)
        tracker.onCall(status(14), 1 * SEC)
        tracker.onCall(auto(20.0), 10 * SEC)
        tracker.onCall(refused(), 12 * SEC)
        tracker.onCall(auto(19.0), 20 * SEC)
        val c = tracker.finished.single()
        assertEquals(1 * SEC, c.startNs)
        assertEquals(20 * SEC, c.endNs)
        assertEquals(14, c.code)
    }

    @Test
    fun unsupportedSourcesAndUnreachableDishAreNotCutoffs() {
        val tracker = CutoffTracker()
        tracker.onCall(DishCallResult(DishCallKind.LOCATION_GNC_FUSED, 0, 0, "test", 200, 2, "Requested position source is not supported: 6", null, null, null), 0)
        tracker.onCall(DishCallResult.failure(DishCallKind.LOCATION_AUTO, 0, 0, "web", "timeout"), SEC)
        tracker.onCall(status(0), 2 * SEC)
        assertNull(tracker.active)
        assertTrue(tracker.finished.isEmpty())
    }
}

class FixExtrapolatorTest {
    private fun fixAt(eastM: Double, speed: Double = 20.0): DishFix {
        val (lat, lon) = Geo.offset(50.0, 30.0, 90.0, eastM)
        return DishFix(PositionSource.STARLINK.code, lat, lon, 100.0, 1.6, speed, 0.0)
    }

    private fun eastOf(s: FixExtrapolator.Served) = Geo.distanceM(50.0, 30.0, s.lat, s.lon)

    @Test
    fun movesAFixForwardByItsAgeAlongTheCourse() {
        val x = FixExtrapolator()
        val first = x.serve(fixAt(0.0), 0)
        assertNull(first.courseDeg)
        assertEquals(0.0, first.shiftM)
        val second = x.serve(fixAt(20.0), SEC)
        assertEquals(90.0, assertNotNull(second.courseDeg), 0.01)
        assertEquals(20.0 + 20.0 * 0.45, eastOf(second), 0.05)
        // The dish had not updated: the same fix a second later is a second older.
        val repeated = x.serve(fixAt(20.0), 2 * SEC)
        assertEquals(1.45, repeated.ageS, 1e-9)
        assertEquals(20.0 + 20.0 * 1.45, eastOf(repeated), 0.05)
    }

    @Test
    fun usesTheMeasuredFixAgeAndCapsTheShift() {
        val x = FixExtrapolator()
        listOf(0.5, 0.6, 0.7).forEach(x::onFixAge)
        x.onFixAge(9.0) // implausible, ignored
        assertEquals(0.6, x.latencyS, 1e-9)
        x.serve(fixAt(0.0), 0)
        x.serve(fixAt(20.0), SEC)
        val stale = x.serve(fixAt(20.0), 4 * SEC)
        assertEquals(20.0 + 20.0 * 2.0, eastOf(stale), 0.05)
    }

    @Test
    fun anImplausibleAgeFallsBackToTheDefaultAndAFreshFixIsAccepted() {
        val x = FixExtrapolator()
        repeat(5) { x.onFixAge(2.4) } // older than a dish solution can be when it arrives
        assertEquals(FixExtrapolator.DEFAULT_LATENCY_S, x.latencyS)
        // firmware 2026.10.05: the solution is ~0.03 s old at receipt (measured against GNSS time)
        repeat(6) { x.onFixAge(0.03) }
        assertEquals(0.03, x.latencyS, 1e-9)
        repeat(6) { x.onFixAge(-0.4) } // negative: the clock, not the dish
        assertEquals(0.03, x.latencyS, 1e-9)
    }

    @Test
    fun noShiftWhenSlowOrRightAfterAGap() {
        val x = FixExtrapolator()
        x.serve(fixAt(0.0), 0)
        assertEquals(0.0, x.serve(fixAt(1.0, speed = 1.0), SEC).shiftM)
        x.serve(fixAt(20.0), 2 * SEC)
        // 30 s without fixes (a cut-off): the chord to the old anchor is not a course.
        val after = x.serve(fixAt(600.0), 32 * SEC)
        assertNull(after.courseDeg)
        assertEquals(0.0, after.shiftM)
    }

    @Test
    fun fixAgeFromDiagnosticsGpsTime() {
        val gpsSeconds = (1_791_990_000_000L - 315_964_800_000L + 18_000L) / 1000.0
        assertEquals(0.45, assertNotNull(FixExtrapolator.fixAgeS(gpsSeconds, 1_791_990_000_450L)), 1e-6)
        assertNull(FixExtrapolator.fixAgeS(0.0, 1_791_990_000_450L))
    }
}

class ReceiverClockTest {
    private val gpsSeconds = (1_791_990_000_000L - 315_964_800_000L + 18_000L) / 1000.0

    @Test
    fun noAgeUntilTheClockOffsetIsKnown() {
        val c = ReceiverClock()
        assertNull(c.offsetMs)
        assertNull(c.fixAgeS(gpsSeconds, 1_791_990_000_450L))
    }

    @Test
    fun realGpsFixesGiveTheOffsetExactly() {
        // the phone's clock is 674 ms ahead of GNSS time (one phone on 2026-10-26): a fix with UTC time T and
        // elapsedRealtime epoch E, received 150 ms later on both clocks
        val c = ReceiverClock()
        for (k in 0 until 5) {
            val fixUtc = 1_791_990_000_000L + k * 1000
            val fixMono = 500_000_000_000L + k * SEC
            c.onGpsFix(fixUtc, fixMono, fixUtc + 674 + 150, fixMono + 150 * 1_000_000)
        }
        assertEquals(674.0, assertNotNull(c.offsetMs), 1e-6)
        assertEquals("gps", c.source)
        // the dish's fix is 30 ms old at receipt; the raw wall clock would say 704 ms
        assertEquals(0.030, assertNotNull(c.fixAgeS(gpsSeconds, 1_791_990_000_030L + 674)), 1e-6)
        assertEquals(0.704, assertNotNull(FixExtrapolator.fixAgeS(gpsSeconds, 1_791_990_000_030L + 674)), 1e-6)
        // mocked fixes carry the mock's own time: the caller keeps them out; a fix without an elapsed epoch is ignored
        c.onGpsFix(1_791_990_000_000L, 0, 1_791_990_009_000L, 500_000_000_000L)
        assertEquals(674.0, assertNotNull(c.offsetMs), 1e-6)
    }

    @Test
    fun nmeaSentencesGiveTheOffsetFromTheirTimeOfDay() {
        // 2026-10-26 17:40:00.500 UTC = 1793036400500; the phone's clock is 386 ms behind GNSS time (the other phone),
        // sentences arrive 20-120 ms after their epoch: the smallest delay wins
        val c = ReceiverClock()
        val epoch = 1_793_036_400_500L
        c.onNmea("\$GNGGA,174000.50,5128.63000,N,00000.00000,E,1,12,0.8,130.0,M,30.0,M,,*5A", epoch - 386 + 120)
        assertEquals(-386.0 + 120, assertNotNull(c.offsetMs), 1e-6)
        c.onNmea("\$GNRMC,174001.50,A,5128.63000,N,00000.00000,E,20.0,45.0,261026,,,A*6B", epoch + 1000 - 386 + 20)
        assertEquals(-386.0 + 20, assertNotNull(c.offsetMs), 1e-6)
        assertEquals("nmea", c.source)
        // sentences without a fix, without a time, other kinds: ignored
        c.onNmea("\$GNGGA,174002.50,,,,,0,00,,,M,,M,,*xx", epoch + 2000 - 5000)
        c.onNmea("\$GNRMC,174002.50,V,,,,,,,261026,,,N*xx", epoch + 2000 - 5000)
        c.onNmea("\$GPGSV,3,1,12,01,40,083,46*xx", epoch + 2000 - 5000)
        c.onNmea("\$PGLOR,1,FIX,1.0,1.0*xx", epoch + 2000 - 5000)
        assertEquals(-386.0 + 20, assertNotNull(c.offsetMs), 1e-6)
        // a GGA just after midnight UTC received by a clock still before midnight takes the nearer day
        val midnight = 1_793_059_200_000L // 2026-10-27 00:00:00 UTC
        val d = ReceiverClock()
        d.onNmea("\$GPGGA,000000.20,5128.6,N,00000.0,E,1,08,1.0,100.0,M,30.0,M,,*xx", midnight + 200 - 386 + 30)
        assertEquals(-386.0 + 30, assertNotNull(d.offsetMs), 1e-6)
        // real GPS fixes take precedence over NMEA
        d.onGpsFix(midnight, 500_000_000_000L, midnight - 386 + 100, 500_000_000_000L + 100 * 1_000_000)
        assertEquals(-386.0, assertNotNull(d.offsetMs), 1e-6)
        assertEquals("gps", d.source)
    }

    @Test
    fun nmeaUtcParsing() {
        assertEquals(1_793_036_400_500L, ReceiverClock.nmeaUtcMs("\$GNRMC,174000.50,A,5128.6,N,00000.0,E,20.0,45.0,261026,,,A*6B", 0L))
        assertEquals(1_793_036_400_000L, ReceiverClock.nmeaUtcMs("\$GPGGA,174000,5128.6,N,00000.0,E,2,08,1.0,100.0,M,30.0,M,,*xx", 1_793_036_000_000L))
        assertNull(ReceiverClock.nmeaUtcMs("\$GPGGA,,,,,,0,00,,,M,,M,,*xx", 1_793_036_000_000L))
        assertNull(ReceiverClock.nmeaUtcMs("\$GNRMC,174000.50,A,5128.6,N,00000.0,E,20.0,45.0,,,,A*6B", 0L))
        assertNull(ReceiverClock.nmeaUtcMs("\$GPGSA,A,3,01,02,,,,,,,,,,,1.5,0.9,1.2*xx", 0L))
        assertNull(ReceiverClock.nmeaUtcMs("garbage", 0L))
    }
}
