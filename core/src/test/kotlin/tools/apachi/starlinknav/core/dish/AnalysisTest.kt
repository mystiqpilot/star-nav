package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DeviceInfo
import tools.apachi.starlinknav.core.dish.proto.DishAlerts
import tools.apachi.starlinknav.core.dish.proto.DishConfig
import tools.apachi.starlinknav.core.dish.proto.DishGetDiagnosticsResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetHistoryResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetObstructionMapResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.proto.DishOutage
import tools.apachi.starlinknav.core.dish.proto.DishReadyStates
import tools.apachi.starlinknav.core.dish.proto.InitializationDurationSeconds
import tools.apachi.starlinknav.core.dish.proto.Quaternion
import tools.apachi.starlinknav.core.dish.proto.AlignmentStats
import tools.apachi.starlinknav.core.dish.proto.WifiAlerts
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisTest {
    // ---- ring buffers

    @Test
    fun ringBufferBeforeAndAfterWrapping() {
        val ring = listOf(10, 11, 12, 13, 14)
        assertEquals(emptyList(), RingBuffer.unroll(ring, 0))
        assertEquals(listOf(10, 11, 12), RingBuffer.unroll(ring, 3)) // not wrapped yet
        assertEquals(ring, RingBuffer.unroll(ring, 5)) // exactly full: oldest at slot 0
        // 7 samples written into 5 slots: slots hold [5, 6, 2, 3, 4] (sample numbers).
        val wrapped = listOf(5, 6, 2, 3, 4)
        assertEquals(listOf(2, 3, 4, 5, 6), RingBuffer.unroll(wrapped, 7))
        assertEquals(6, wrapped[(7 - 1) % 5]) // newest sits at (current - 1) % size
        assertEquals(6, RingBuffer.unroll(wrapped, 7).last())
        assertEquals(emptyList(), RingBuffer.unroll(emptyList<Int>(), 100))
        assertEquals(listOf(2, 3, 4, 5, 6), RingBuffer.unroll(wrapped, 7 + 5L * 1_000_000_000))
    }

    @Test
    fun statisticsSkipNaN() {
        val xs = listOf(1f, Float.NaN, 3f, Float.POSITIVE_INFINITY, 5f)
        assertEquals(3f, Series.mean(xs))
        assertEquals(5f, Series.max(xs))
        assertEquals(1f, Series.min(xs))
        assertEquals(5f, Series.lastFinite(xs + Float.NaN))
        assertNull(Series.mean(listOf(Float.NaN, Float.NaN)))
        assertNull(Series.max(emptyList()))
        assertEquals(3f, Series.percentile(xs, 50.0))
        assertEquals(5f, Series.percentile(xs, 100.0))
        assertEquals(2f / 3f, Series.shareAbove(xs, 2f)!!, 1e-6f)
    }

    @Test
    fun dishHistoryUnrollsWithNanAndOutages() {
        val n = 900
        val slots = List(n) { i -> if (i % 100 == 0) Float.NaN else i.toFloat() }
        val h = DishGetHistoryResponse(
            current = 1234, pop_ping_latency_ms = slots, pop_ping_drop_rate = slots, downlink_throughput_bps = slots, uplink_throughput_bps = slots,
            power_in = slots, outages = listOf(DishOutage(cause = 6, start_timestamp_ns = 20), DishOutage(cause = 5, start_timestamp_ns = 10)),
        )
        val history = DishHistory.of(h)
        assertEquals(900, history.size)
        // 1234 written: the newest sample is in slot 333, the oldest in slot 334.
        assertEquals(slots[333], history.latencyMs.last())
        assertEquals(slots[334], history.latencyMs.first())
        assertEquals(1234, history.current)
        assertEquals(listOf(10L, 20L), history.outages.map { it.start_timestamp_ns })
        assertEquals(0, history.ageS(899))
        assertEquals(899, history.ageS(0))
        assertTrue(Series.mean(history.powerInW)!! > 0f)
    }

    @Test
    fun sameSecondReadsTheSameOnEveryPoll() {
        var now = 0L
        val sim = FakeDishSimulation(startNs = 0, clock = { now })
        fun hist(): DishHistory {
            val r = sim.handle(DishCallKind.HISTORY.request(1)).first!!.dish_get_history!!
            return DishHistory.of(r)
        }
        now = 1_000_000_000_000L // 1000 s on top of the 656 s the fake starts with
        val a = hist()
        now += 5_000_000_000L
        val b = hist()
        assertEquals(a.current + 5, b.current)
        assertEquals(900, a.size)
        assertContentEquals(a.latencyMs.drop(5), b.latencyMs.dropLast(5))
        assertContentEquals(a.powerInW.drop(5), b.powerInW.dropLast(5))
        assertContentEquals(a.dropRate.drop(5), b.dropRate.dropLast(5))
        // 25-40 W while awake.
        assertTrue(a.powerInW.all { it in 25f..40f }, "power ${a.powerInW.minOrNull()}..${a.powerInW.maxOrNull()}")
        // Early in the uptime the ring is only partly written.
        now = -650_000_000_000L
        val young = hist()
        assertEquals(6, young.size)
    }

    @Test
    fun routerHistoryUnrollsBothRates() {
        val router = FakeRouterSimulation()
        val raw = router.handle(DishCallKind.ROUTER_HISTORY.request(1)).first!!.wifi_get_history!!
        val h = RouterHistory.of(raw)
        assertEquals(900, h.pingLatencyMs.size)
        assertEquals(60, h.popIpv4Drop15s.size)
        assertEquals(2, h.dnsResolverDrop15s.size)
        assertTrue(h.events.size >= 3)
        // newest first
        assertTrue(h.events.zipWithNext().all { (a, b) -> a.start_timestamp_ns >= b.start_timestamp_ns })
        assertTrue(Series.mean(h.pingLatencyMs)!! in 30f..42f)
        assertEquals(raw.current, h.current)
    }

    // ---- obstruction map

    private fun map(rows: Int, cols: Int, frame: Int, cell: (Int, Int) -> Float) = DishGetObstructionMapResponse(
        num_rows = rows, num_cols = cols, snr = List(rows * cols) { cell(it / cols, it % cols) }, map_reference_frame = frame,
    )

    @Test
    fun blockedShareIsOfObservedCellsAndNegativeMeansNoData() {
        val m = map(10, 10, Obstruction.FRAME_EARTH) { r, c ->
            when {
                r < 2 -> -1f // no data
                r == 2 && c < 5 -> 0f // blocked
                r == 3 && c < 3 -> 0.5f // reduced
                c == 9 && r == 9 -> Float.NaN // no data
                else -> 1f
            }
        }
        val a = assertNotNull(Obstruction.analyze(m))
        assertEquals(100 - 20 - 1, a.observed)
        assertEquals(5, a.blocked)
        assertEquals(3, a.reduced)
        assertEquals(a.observed - 8, a.clear)
        assertEquals(5.0 / 79, a.blockedFraction!!, 1e-9)
        assertFalse(a.approximate)
        assertEquals(0.0, a.rotationDeg)
        assertEquals(8, a.sectors.size)
        assertEquals(a.observed, a.sectors.sumOf { it.observed })
        assertEquals(5, a.sectors.sumOf { it.blocked })
    }

    @Test
    fun largestPatchIsFourConnectedNotDiagonal() {
        val blocked = setOf(1 to 1, 1 to 2, 2 to 2, 2 to 3, 6 to 6, 7 to 7, 8 to 8) // 4-cell patch, then three diagonal singles
        val m = map(10, 10, Obstruction.FRAME_EARTH) { r, c -> if (r to c in blocked) 0f else 1f }
        val a = assertNotNull(Obstruction.analyze(m))
        assertEquals(7, a.blocked)
        assertEquals(4, a.largestBlockedPatch)
        assertEquals(4.0 / 100, a.largestPatchFraction!!, 1e-9)
        // Two patches: the bigger one wins.
        val two = map(10, 10, Obstruction.FRAME_EARTH) { r, c -> if (r == 0 && c < 3 || r == 9 && c < 6) 0f else 1f }
        assertEquals(6, Obstruction.analyze(two)!!.largestBlockedPatch)
        // Nothing observed: no shares, no crash.
        val none = Obstruction.analyze(map(4, 4, 1) { _, _ -> -1f })!!
        assertNull(none.blockedFraction)
        assertEquals(0, none.largestBlockedPatch)
        assertNull(Obstruction.analyze(DishGetObstructionMapResponse()))
        assertNull(Obstruction.analyze(DishGetObstructionMapResponse(num_rows = 10, num_cols = 10, snr = listOf(1f))))
    }

    @Test
    fun sectorsAreWedgesOfFortyFiveDegreesCentredOnCompassPoints() {
        val n = 41
        // Block the wedge around north-east (az 45): cells right of and above the centre, |az - 45| < 15.
        val m = map(n, n, Obstruction.FRAME_EARTH) { r, c ->
            val az = Obstruction.cellAzimuthDeg(n, n, r, c)
            val dist = kotlin.math.hypot(c - 20.0, 20.0 - r)
            if (dist in 6.0..19.0 && kotlin.math.abs(az - 45) < 15) 0f else 1f
        }
        val a = Obstruction.analyze(m)!!
        assertEquals(1, a.sectors.indices.maxByOrNull { a.sectors[it].blocked })
        assertEquals(a.blocked, a.sectors[1].blocked)
        assertEquals(45.0, a.sectors[1].centreDeg)
        assertTrue(a.sectors[1].blockedFraction!! > 0.3)
        assertEquals(0.0, a.sectors[5].blockedFraction!!)
        // Cell azimuth: row 0 is up (north), the right edge is east.
        assertEquals(0.0, Obstruction.cellAzimuthDeg(41, 41, 0, 20), 1e-9)
        assertEquals(90.0, Obstruction.cellAzimuthDeg(41, 41, 20, 40), 1e-9)
        assertEquals(180.0, Obstruction.cellAzimuthDeg(41, 41, 40, 20), 1e-9)
        assertEquals(270.0, Obstruction.cellAzimuthDeg(41, 41, 20, 0), 1e-9)
    }

    @Test
    fun dishFixedMapsAreTurnedByThePanelTopAzimuthAndFlaggedApproximate() {
        val n = 41
        val north = { r: Int, c: Int -> if (Obstruction.cellAzimuthDeg(n, n, r, c).let { it > 340 || it < 20 } && kotlin.math.hypot(c - 20.0, 20.0 - r) > 8) 0f else 1f }
        val earth = Obstruction.analyze(map(n, n, Obstruction.FRAME_EARTH, north), panelUpAzimuthDeg = 90.0)!!
        assertEquals(0, earth.sectors.indices.maxByOrNull { earth.sectors[it].blocked }) // north-up: the turn is ignored
        assertFalse(earth.approximate)
        val ut = Obstruction.analyze(map(n, n, Obstruction.FRAME_UT, north), panelUpAzimuthDeg = 90.0)!!
        assertEquals(2, ut.sectors.indices.maxByOrNull { ut.sectors[it].blocked }) // the panel top points east: so does the top of the picture
        assertTrue(ut.approximate)
        assertEquals(90.0, ut.rotationDeg)
        val noTurn = Obstruction.analyze(map(n, n, Obstruction.FRAME_UT, north))!!
        assertEquals(0, noTurn.sectors.indices.maxByOrNull { noTurn.sectors[it].blocked })
        assertTrue(noTurn.approximate)
        assertTrue(Obstruction.analyze(map(n, n, Obstruction.FRAME_UNKNOWN, north))!!.approximate)
    }

    @Test
    fun theTurnIsTheAzimuthOfThePanelTopNotTheHeadingOfBodyX() {
        // A dish flat on a roof, normal up: body +x north, +y west (a right-handed frame with +z up).
        // R(q) maps body to NED, so it is diag(1, -1, -1), a half turn about x: q = (w 0, x 1, y 0, z 0).
        val level = Quaternion(q_scalar = 0f, q_x = 1f, q_y = 0f, q_z = 0f).attitude()
        assertEquals(0.0, level.headingDeg, 1e-6)
        assertEquals(270.0, level.panelUpAzimuthDeg, 1e-6)
        assertEquals(90.0, level.normalElevationDeg, 1e-6)
        // Turned by 30 degrees clockwise about the vertical (yaw, a NED z rotation): both axes follow, 90 degrees apart.
        val yaw = Math.toRadians(30.0) / 2
        val turned = Quaternion(q_scalar = kotlin.math.cos(yaw).toFloat(), q_x = 0f, q_y = 0f, q_z = kotlin.math.sin(yaw).toFloat())
        val q = quaternionMultiply(turned, Quaternion(q_scalar = 0f, q_x = 1f, q_y = 0f, q_z = 0f)).attitude()
        assertEquals(30.0, q.headingDeg, 1e-4)
        assertEquals(300.0, q.panelUpAzimuthDeg, 1e-4)
        // The sectors of a FRAME_UT map put a patch at the top of the picture where the panel top points.
        val n = 41
        val top = { r: Int, c: Int -> if (r < 12 && kotlin.math.abs(c - 20) < 4) 0f else 1f }
        val a = Obstruction.analyze(map(n, n, Obstruction.FRAME_UT, top), q.panelUpAzimuthDeg)!!
        assertEquals(7, a.sectors.indices.maxByOrNull { a.sectors[it].blocked }) // 300 deg: north-west, not the heading's north-east
    }

    private fun quaternionMultiply(a: Quaternion, b: Quaternion) = Quaternion(
        q_scalar = a.q_scalar * b.q_scalar - a.q_x * b.q_x - a.q_y * b.q_y - a.q_z * b.q_z,
        q_x = a.q_scalar * b.q_x + a.q_x * b.q_scalar + a.q_y * b.q_z - a.q_z * b.q_y,
        q_y = a.q_scalar * b.q_y - a.q_x * b.q_z + a.q_y * b.q_scalar + a.q_z * b.q_x,
        q_z = a.q_scalar * b.q_z + a.q_x * b.q_y - a.q_y * b.q_x + a.q_z * b.q_scalar,
    )

    @Test
    fun theZenithCellBelongsToNoSector() {
        val n = 5
        val allBlocked = { _: Int, _: Int -> 0f }
        val a = Obstruction.analyze(map(n, n, Obstruction.FRAME_EARTH, allBlocked))!!
        assertEquals(25, a.observed)
        assertEquals(25, a.blocked)
        assertEquals(24, a.sectors.sumOf { it.observed }) // the middle cell has no azimuth
        assertEquals(24, a.sectors.sumOf { it.blocked })
    }

    @Test
    fun theFakeDishMapHasABlockedPatchInTheNorthEast() {
        val m = FakeDishSimulation().handle(DishCallKind.OBSTRUCTION_MAP.request(1)).first!!.dish_get_obstruction_map!!
        val a = assertNotNull(Obstruction.analyze(m))
        assertTrue(a.blocked > 100 && a.blockedFraction!! in 0.05..0.3, "blocked ${a.blockedFraction}")
        assertTrue(a.largestBlockedPatch > 100)
        assertTrue(a.sectors[1].blockedFraction!! > a.sectors[2].blockedFraction!!)
        assertEquals(0.0, a.sectors[4].blockedFraction!!)
        assertEquals(DishGetObstructionMapResponse().blockedFraction(), null)
        assertEquals(a.blockedFraction!!, m.blockedFraction()!!, 1e-12)
    }

    // ---- state, alerts, init durations, actuators

    private val mini = DeviceInfo(hardware_version = "mini1_pez_proto1")
    private val readyAll = DishReadyStates(cady = false, scp = true, l1l2 = true, xphy = true, aap = true, rf = true)

    @Test
    fun stateFollowsOutageCauseAndReadyStates() {
        fun state(s: DishGetStatusResponse) = DishStates.of(s).state
        assertEquals(DishLinkState.CONNECTED, state(DishGetStatusResponse(device_info = mini, ready_states = readyAll)))
        assertEquals(DishLinkState.CONNECTED, state(DishGetStatusResponse())) // nothing known: not "booting"
        // A Mini never raises cady; other hardware does.
        assertEquals(emptyList(), DishStates.of(DishGetStatusResponse(device_info = mini, ready_states = readyAll)).notReady)
        assertEquals(listOf("cady"), DishStates.of(DishGetStatusResponse(device_info = DeviceInfo(hardware_version = "rev3_prod1"), ready_states = readyAll)).notReady)
        assertEquals(DishLinkState.BOOTING, state(DishGetStatusResponse(device_info = mini, ready_states = readyAll.copy(rf = false))))
        val causes = mapOf(
            1 to DishLinkState.BOOTING, 2 to DishLinkState.STOWED, 3 to DishLinkState.THERMAL_SHUTDOWN, 4 to DishLinkState.SEARCHING,
            5 to DishLinkState.SEARCHING, 6 to DishLinkState.OBSTRUCTED, 7 to DishLinkState.SEARCHING, 8 to DishLinkState.SEARCHING,
            9 to DishLinkState.BUSY, 10 to DishLinkState.BUSY, 11 to DishLinkState.SLEEPING, 13 to DishLinkState.SEARCHING,
            14 to DishLinkState.RF_INHIBITED, 0 to DishLinkState.SEARCHING, 99 to DishLinkState.UNKNOWN,
        )
        for ((cause, expected) in causes) {
            val s = DishGetStatusResponse(device_info = mini, outage = DishOutage(cause = cause), ready_states = readyAll)
            assertEquals(expected, state(s), "cause $cause")
            assertEquals(cause, DishStates.of(s).outageCause)
        }
        // A stow request means "stowed" only on a dish with motors.
        assertEquals(DishLinkState.CONNECTED, state(DishGetStatusResponse(device_info = mini, stow_requested = true, has_actuators = HasActuators.NO, ready_states = readyAll)))
        assertEquals(DishLinkState.STOWED, state(DishGetStatusResponse(device_info = mini, stow_requested = true, has_actuators = HasActuators.YES, ready_states = readyAll)))
    }

    @Test
    fun theFakeDishStateFollowsItsCommands() {
        var wall = 1_700_000_000_000L // 22:13 UTC
        val sim = FakeDishSimulation(clock = { 5_000_000_000_000L }, startNs = 0, wallClockMs = { wall })
        fun st() = DishStates.of(sim.handle(DishCallKind.STATUS.request(1)).first!!.dish_get_status!!)
        assertEquals(DishLinkState.CONNECTED, st().state)
        sim.handle(DishCallKind.INHIBIT_RF.request(1))
        assertEquals(DishLinkState.RF_INHIBITED, st().state)
        sim.handle(DishCallKind.ALLOW_RF.request(1))
        assertEquals(DishLinkState.CONNECTED, st().state)
        val minute = ((wall / 60_000L) % 1440).toInt()
        sim.handle(DishCommand.SleepSchedule(SleepWindow((minute - 10 + 1440) % 1440, 60, true)).request(1))
        assertEquals(DishLinkState.SLEEPING, st().state)
        sim.handle(DishCommand.SleepSchedule(SleepWindow((minute - 10 + 1440) % 1440, 60, false)).request(1))
        assertEquals(DishLinkState.CONNECTED, st().state)
    }

    @Test
    fun alertsMergeStatusAndDiagnosticsMostSevereFirst() {
        val status = DishGetStatusResponse(
            has_actuators = HasActuators.NO,
            alerts = DishAlerts(motors_stuck = true, install_pending = true, thermal_throttle = true, dish_water_detected = true, is_heating = true),
        )
        val diag = DishGetDiagnosticsResponse(
            alerts = DishGetDiagnosticsResponse.Alerts(obstructed = true, dish_thermal_throttle = true, software_install_pending = true),
            hardware_self_test = HardwareSelfTest.FAILED, overage_rate_limited = true,
        )
        val alerts = AlertList.of(status, diag)
        val keys = alerts.map { it.key }
        assertFalse("motors_stuck" in keys, "no motors on a Mini")
        assertEquals(keys.toSet().size, keys.size) // duplicates merged
        assertTrue(listOf("dish_water_detected", "hardware_self_test_failed").all { it in keys })
        assertTrue(listOf("thermal_throttle", "obstructed", "overage_rate_limited").all { it in keys })
        assertEquals(1, keys.count { it == "install_pending" })
        assertEquals(AlertSeverity.WARNING, alerts.first().severity)
        assertEquals(AlertSeverity.ADVISORY, alerts.last().severity)
        assertEquals(alerts.sortedBy { it.severity.ordinal }, alerts)
        // Motor alerts count on a dish with motors.
        val withMotors = AlertList.of(status.copy(has_actuators = HasActuators.YES))
        assertTrue("motors_stuck" in withMotors.map { it.key })
        assertEquals(emptyList(), AlertList.of(null, null))
        assertEquals(listOf("thermal_throttle", "wan_eth_poor_connection"), AlertList.router(WifiAlerts(thermal_throttle = true, wan_eth_poor_connection = true)))
        assertEquals(emptyList(), AlertList.router(null))
    }

    @Test
    fun initializationDurationsAreSortedByTimeAndSplitReachedFromNot() {
        val d = InitializationDurationSeconds(gps_valid = 14, rf_ready = 26, ekf_converged = 29, stable_connection = 39, first_cplane = 29)
        val v = InitializationView.of(d)
        assertEquals(listOf("gps_valid", "rf_ready", "ekf_converged", "first_cplane", "stable_connection"), v.stages.map { it.key })
        assertEquals(listOf(14, 26, 29, 29, 39), v.stages.map { it.seconds })
        assertEquals(39, v.totalS)
        assertEquals(14, v.secondsOf("gps_valid"))
        assertNull(v.secondsOf("burst_detected"))
        assertEquals(10, v.stages.size + v.notReached.size)
        assertTrue("attitude_initialization" in v.notReached)
        val none = InitializationView.of(null)
        assertEquals(emptyList(), none.stages)
        assertEquals(10, none.notReached.size)
        assertNull(none.totalS)
        // The fake fills the stages in as its uptime grows.
        var now = 0L
        val sim = FakeDishSimulation(startNs = 0, clock = { now })
        now = -640_000_000_000L // 16 s up
        val young = InitializationView.of(sim.handle(DishCallKind.STATUS.request(1)).first!!.dish_get_status!!.initialization_duration_seconds)
        assertEquals(listOf("attitude_initialization", "gps_valid"), young.stages.map { it.key })
        now = 0L
        val old = InitializationView.of(sim.handle(DishCallKind.STATUS.request(1)).first!!.dish_get_status!!.initialization_duration_seconds)
        assertEquals(10, old.stages.size)
        assertEquals(39, old.totalS)
    }

    @Test
    fun stowOnlyWhenTheDishSaysItHasMotors() {
        assertFalse(DishGetStatusResponse().canStow())
        assertFalse(DishGetStatusResponse(has_actuators = HasActuators.NO).canStow())
        assertTrue(DishGetStatusResponse(has_actuators = HasActuators.YES).canStow())
        // The alignment stats' copy counts when the top-level field is absent.
        assertTrue(DishGetStatusResponse(alignment_stats = AlignmentStats(has_actuators = HasActuators.YES)).canStow())
        assertEquals(HasActuators.NO, DishGetStatusResponse(has_actuators = HasActuators.NO, alignment_stats = AlignmentStats(has_actuators = HasActuators.YES)).hasActuators())
        // The fake is a Mini.
        assertFalse(FakeDishSimulation().handle(DishCallKind.STATUS.request(1)).first!!.dish_get_status!!.canStow())
    }

    // ---- sleep schedule

    @Test
    fun sleepScheduleConvertsBetweenUtcAndLocalMinutes() {
        val w = SleepWindow(startUtcMinutes = 23 * 60 + 30, durationMinutes = 360, enabled = true)
        assertEquals(5 * 60 + 30, w.endUtcMinutes) // wraps past midnight
        assertEquals(2 * 60 + 30, w.startLocalMinutes(180)) // Kyiv in summer
        assertEquals(8 * 60 + 30, w.endLocalMinutes(180))
        assertEquals(18 * 60 + 30, w.startLocalMinutes(-300)) // New York
        val back = SleepWindow.fromLocal(startLocalMinutes = 2 * 60 + 30, durationMinutes = 360, enabled = true, utcOffsetMinutes = 180)
        assertEquals(w, back)
        assertEquals(SleepWindow(0, 60, true), SleepWindow.fromLocal(180, 60, true, 180))
        assertEquals(SleepWindow(21 * 60, 60, true), SleepWindow.fromLocal(60 * 0, 60, true, 180)) // local midnight is 21:00 UTC
        assertTrue(w.contains(23 * 60 + 45))
        assertTrue(w.contains(0))
        assertTrue(w.contains(5 * 60 + 29))
        assertFalse(w.contains(5 * 60 + 30))
        assertFalse(w.contains(12 * 60))
        assertTrue(SleepWindow(0, 1440, true).contains(777))
        assertEquals("02:30", SleepWindow.clock(150))
        assertEquals("23:59", SleepWindow.clock(-1))
        assertEquals(SleepWindow.OFF, SleepWindow.OFF.withEnabled(false))
        assertTrue(w.withEnabled(false).durationMinutes == 360 && !w.withEnabled(false).enabled)
    }

    @Test
    fun sleepWindowComesFromTheDishConfig() {
        val c = DishConfig(power_save_start_minutes = 120, power_save_duration_minutes = 480, power_save_mode = true)
        assertEquals(SleepWindow(120, 480, true), SleepWindow.of(c))
        // Never configured: duration 0 reads as 1 minute, off.
        assertEquals(SleepWindow(0, 1, false), SleepWindow.of(DishConfig()))
        assertNull(SleepWindow.of(null))
    }

    @Test
    fun sleepScheduleFollowsDaylightSavingTime() {
        // Europe/Kyiv, 2026: summer time (EEST, UTC+3) ends on Sunday 25 October at 04:00 local = 01:00 UTC.
        val kyiv = java.time.ZoneId.of("Europe/Kyiv")
        fun at(iso: String) = java.time.Instant.parse(iso)
        // Set on Saturday 24 October at 22:00 local: the next 22:00 is still summer time (19:00 UTC).
        val set = SleepWindow.fromLocal(22 * 60, 480, true, kyiv, at("2026-10-24T10:00:00Z"))
        assertEquals(SleepWindow(19 * 60, 480, true), set)
        // Seen the same evening the window starts at 22:00 local; after the change the same UTC minutes read 21:00.
        assertEquals(22 * 60, set.startLocalMinutes(kyiv, at("2026-10-24T10:00:00Z")))
        assertEquals(21 * 60, set.startLocalMinutes(kyiv, at("2026-10-26T12:00:00Z")))
        assertEquals(5 * 60, set.endLocalMinutes(kyiv, at("2026-10-26T12:00:00Z")))
        // On the change night the next start (Sunday 19:00 UTC) is already winter time: 21:00, not the 22:00 of a fixed +3.
        assertEquals(21 * 60, set.startLocalMinutes(kyiv, at("2026-10-24T20:30:00Z")))
        assertEquals(at("2026-10-25T19:00:00Z"), set.nextStart(at("2026-10-24T20:30:00Z")))
        // Set after the change for 22:00 local: winter time, 20:00 UTC.
        assertEquals(SleepWindow(20 * 60, 480, true), SleepWindow.fromLocal(22 * 60, 480, true, kyiv, at("2026-10-27T10:00:00Z")))
        // Set the evening before the change for a start after midnight: 03:30 local on the 25th falls in the repeated hour.
        val night = SleepWindow.fromLocal(3 * 60 + 30, 60, true, kyiv, at("2026-10-24T20:30:00Z"))
        assertTrue(night.startUtcMinutes in listOf(30, 90)) // 00:30 UTC (EEST) or 01:30 UTC (EET): the first of the two readings
        assertEquals(30, night.startUtcMinutes)
        // The spring change too (28 March 2027, 03:00 local = 01:00 UTC): a start at 22:00 the day before is +2.
        assertEquals(SleepWindow(20 * 60, 60, true), SleepWindow.fromLocal(22 * 60, 60, true, kyiv, at("2027-03-24T10:00:00Z")))
        // A zone without daylight saving behaves like the fixed offset.
        val utc3 = java.time.ZoneId.of("Etc/GMT-3")
        assertEquals(SleepWindow.fromLocal(150, 360, true, 180), SleepWindow.fromLocal(150, 360, true, utc3, at("2026-07-01T00:00:00Z")))
        assertEquals(150, SleepWindow(23 * 60 + 30, 360, true).startLocalMinutes(utc3, at("2026-07-01T00:00:00Z")))
    }
}
