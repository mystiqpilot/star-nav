package tools.apachi.starlinknav.core.report

import okio.ByteString.Companion.toByteString
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.core.dish.GrpcStatus
import tools.apachi.starlinknav.core.dish.PositionSource
import tools.apachi.starlinknav.core.dish.proto.DeviceInfo
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DeviceState
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.proto.GetLocationResponse
import tools.apachi.starlinknav.core.dish.proto.LLAPosition
import tools.apachi.starlinknav.core.log.SlogWriter
import tools.apachi.starlinknav.core.log.proto.DishCall
import tools.apachi.starlinknav.core.log.proto.GnssAgc
import tools.apachi.starlinknav.core.log.proto.GnssMeasurementBatch
import tools.apachi.starlinknav.core.log.proto.PhoneLocation
import tools.apachi.starlinknav.core.log.proto.Record
import tools.apachi.starlinknav.core.log.proto.SessionHeader
import tools.apachi.starlinknav.core.nav.RebootCause
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A synthetic drive east at 20 m/s: every dish fix describes where the car was 0.45 s before it
 * arrived, the phone sits 3 m north of the truth, and the dish is cut off from 60 s to 90 s.
 */
class ReportTest {
    private val dir: File = Files.createTempDirectory("report-test").toFile()
    private val sec = 1_000_000_000L

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun truth(tS: Double): Pair<Double, Double> = Geo.offset(50.0, 30.0, 90.0, 20.0 * (tS - 1.0))

    private fun rec(tS: Double, build: (Record) -> Record) = build(Record(t_mono_ns = (tS * sec).toLong(), t_wall_ms = (tS * 1000).toLong()))

    private fun dish(tS: Double, kind: DishCallKind, response: DeviceResponse?, grpc: Int = GrpcStatus.OK, message: String = "") = rec(tS) {
        it.copy(
            dish = DishCall(
                kind = kind.code, http_status = 200, grpc_status = grpc, grpc_message = message,
                response = response?.let { r -> DeviceResponse.ADAPTER.encode(r).toByteString() } ?: okio.ByteString.EMPTY,
            ),
        )
    }

    private fun drive(phoneInLima: IntRange = IntRange.EMPTY): List<Record> = buildList {
        add(rec(0.5) { it.copy(header_ = SessionHeader(session_id = "rep1", app_version = "test")) })
        for (k in 1..120) {
            val cut = k in 60..89
            add(dish(k.toDouble(), DishCallKind.STATUS, DeviceResponse(dish_get_status = DishGetStatusResponse(disablement_code = if (cut) 14 else 1))))
            val tr = k + 0.5
            if (cut) {
                add(dish(tr, DishCallKind.LOCATION_AUTO, null, GrpcStatus.PERMISSION_DENIED, "GetLocation requests disabled due to UT disablement"))
            } else {
                val (lat, lon) = truth(tr - 0.45)
                add(
                    dish(
                        tr, DishCallKind.LOCATION_AUTO,
                        DeviceResponse(get_location = GetLocationResponse(LLAPosition(lat, lon, 100.0), source = PositionSource.STARLINK.code, sigma_m = 1.6, horizontal_speed_mps = 20.0)),
                    ),
                )
            }
            val (plat, plon) = if (k in phoneInLima) -12.0464 to -77.0428 else truth(k.toDouble()).let { (la, lo) -> Geo.offset(la, lo, 0.0, 3.0) }
            add(rec(k + 0.2) { it.copy(phone_location = PhoneLocation(provider = "gps", lat = plat, lon = plon, h_acc = 4f, speed = 20f, elapsed_ns = k * sec)) })
            val agc = if (k in 30..40) -3.0 else 2.0
            add(rec(k + 0.3) { it.copy(gnss_measurements = GnssMeasurementBatch(agc = listOf(GnssAgc(level_db = agc, constellation = 1, carrier_hz = 1_575_420_000L)))) })
        }
    }

    @Test
    fun theReplayStartsFromTheMountTheHeaderNames() {
        // up to 0.3: one value, 0 = never set
        assertNull(SessionReports.mountFrom("""{"dishYawOffsetDeg":0.0}"""))
        assertEquals(-177.0, SessionReports.mountFrom("""{"dishYawOffsetDeg":-177.0}"""))
        // 0.4: the mode says which field; a manual 0 is a real value, a manual without a value is none
        assertEquals(0.0, SessionReports.mountFrom("""{"dishYawOffsetDeg":0.0,"mountMode":"MANUAL","mountManualDeg":0.0}"""))
        assertNull(SessionReports.mountFrom("""{"dishYawOffsetDeg":0.0,"mountMode":"MANUAL"}"""))
        assertEquals(-176.9, SessionReports.mountFrom("""{"dishYawOffsetDeg":-176.9,"mountMode":"AUTO","mountLearnedDeg":-176.9}"""))
        assertNull(SessionReports.mountFrom("""{"dishYawOffsetDeg":0.0,"mountMode":"AUTO"}"""))
        assertNull(SessionReports.mountFrom("not json"))
    }

    @Test
    fun measuresTheServedPositionAgainstThePhone() {
        val r = SessionReports.build(drive().asSequence(), bytes = 1)
        val s = r.summary
        assertEquals("rep1", r.sessionId)
        // Moved by its 0.45 s age the fix lands on the truth; only the phone's own 3 m remain.
        assertEquals(3.0, assertNotNull(s.errP50M), 0.05)
        // Unmoved it trails by 9 m: hypot(9, 3).
        assertEquals(9.49, assertNotNull(s.rawP50M), 0.05)
        assertTrue(s.errMaxM!! < 9.6)
        assertNull(s.latencyS) // no diagnostics: the default age was used
        assertTrue(s.classS[0] > 85, "moving seconds under 5 m: ${s.classS}")
        assertEquals(0.0, s.classS.drop(2).sum())
        assertEquals(1.0, s.noReferenceS) // the last fix comes after the phone's last one

        val c = r.cutoffs.single()
        assertEquals(14, c.code)
        assertEquals(60.0, (c.startNs - r.startNs) / 1e9 + 1.0, 0.6) // status at 60 s (the log starts at 1 s)
        assertEquals(30.0, c.durationS, 0.6)
        assertEquals(20.0, assertNotNull(c.speedBeforeMps))
        assertEquals(20.0, assertNotNull(c.phoneSpeedStartMps))
        assertEquals(600.0, assertNotNull(c.distanceM), 25.0)
        assertEquals(c.durationS, s.cutoffS, 1e-9)

        val intf = r.spans.single { it.kind == ReportSpan.INTERFERENCE }
        assertEquals(10.0, intf.durationS, 0.01)
        assertTrue(r.spans.none { it.kind == ReportSpan.MOCK })
        assertEquals(20.0, assertNotNull(s.maxSpeedMps), 1e-6)
        assertEquals(90, s.dishFixes)
    }

    @Test
    fun aPhoneTeleportedToLimaIsAConflictNotADishError() {
        val r = SessionReports.build(drive(phoneInLima = 100..110).asSequence())
        val s = r.summary
        assertEquals(3.0, assertNotNull(s.errP50M), 0.05)
        assertTrue(s.errMaxM!! < 10, "conflicts stay out of the error statistics")
        // 11 phone fixes in Lima touch 12 dish fixes (each one interpolates between two phone fixes).
        assertEquals(12.0, s.conflictS, 1e-9)
        assertEquals(11.0, r.spans.single { it.kind == ReportSpan.CONFLICT }.durationS, 1e-9)
        assertEquals(0.0, s.classS[4])
    }

    @Test
    fun countsWhatAppsReceivedWhileMocking() {
        val records = buildList {
            add(rec(0.5) { it.copy(header_ = SessionHeader(session_id = "mock1")) })
            for (k in 1..20) {
                val (lat, lon) = truth(k.toDouble())
                add(rec(k.toDouble()) { it.copy(mock_output = tools.apachi.starlinknav.core.log.proto.MockOutput(provider = "gps", lat = lat, lon = lon)) })
                add(rec(k + 0.01) { it.copy(phone_location = PhoneLocation(provider = "fused", lat = lat, lon = lon, is_mock = true, elapsed_ns = k * sec)) })
                // Google's fused location ignores the mock for the last 5 s and reports a Wi-Fi fix 700 m away.
                val (glat, glon) = if (k > 15) Geo.offset(lat, lon, 45.0, 700.0) else lat to lon
                add(rec(k + 0.2) { it.copy(phone_location = PhoneLocation(provider = "gms_fused", lat = glat, lon = glon, is_mock = k <= 15, elapsed_ns = k * sec + 150_000_000L)) })
            }
        }
        val d = SessionReports.build(records.asSequence()).summary.delivery
        assertEquals(DeliveryStats(20, 20, 0, 0.0), d["fused"]!!.copy(maxOffsetM = 0.0))
        val g = d.getValue("gms_fused")
        assertEquals(20, g.received)
        assertEquals(15, g.followed)
        assertEquals(5, g.notMock)
        assertEquals(700.0, g.maxOffsetM!!, 5.0)
    }

    @Test
    fun cachesNextToTheLogUntilTheLogChanges() {
        val log = File(dir, "20261014-120000.slog")
        SlogWriter.create(log).use { w -> drive().forEach(w::write) }
        val built = SessionReports.loadOrBuild(log)
        assertTrue(SessionReports.fileFor(log).isFile)
        assertEquals(built, SessionReports.read(log))
        log.appendBytes(byteArrayOf(0))
        assertNull(SessionReports.read(log))
    }

    @Test
    fun aDishRestartIsListedAndNotCountedAsUnreachable() {
        fun status(tS: Double, uptimeS: Long, software: String) = dish(
            tS, DishCallKind.STATUS,
            DeviceResponse(
                dish_get_status = DishGetStatusResponse(
                    device_info = DeviceInfo(software_version = software, bootcount = if (software.startsWith("2026.05")) 6 else 7),
                    device_state = DeviceState(uptime_s = uptimeS), disablement_code = 1, software_update_state = if (software.startsWith("2026.05")) 6 else 1,
                ),
            ),
        )
        val records = buildList {
            add(rec(0.5) { it.copy(header_ = SessionHeader(session_id = "reboot1", app_version = "test")) })
            for (k in 1..49) add(status(k.toDouble(), 600L + k, "2026.05.13.mr80201"))
            // Down from 50 s: only timeouts, every 3 s, until it answers again at 140 s, 40 s after booting.
            for (k in 50..139 step 3) add(rec(k.toDouble()) { it.copy(dish = DishCall(kind = DishCallKind.STATUS.code, error = "connect timed out")) })
            for (k in 140..200) add(status(k.toDouble(), 40L + k - 140, "2026.09.18.mr87170.1"))
        }
        val r = SessionReports.build(records.asSequence(), bytes = 1)
        val reboot = r.reboots.single()
        assertEquals(RebootCause.UPDATE_INSTALLED, reboot.cause)
        assertEquals(91.0, reboot.offlineS!!, 1e-6)
        assertTrue(reboot.updateWaiting)
        assertEquals(listOf(ReportSpan(ReportSpan.REBOOT, 49 * sec, 140 * sec)), r.spans.filter { it.kind == ReportSpan.REBOOT })
        assertTrue(r.spans.none { it.kind == ReportSpan.UNREACHABLE })
        assertEquals(-600 * sec, r.dishBootNs)
    }

    @Test
    fun accuracyClasses() {
        assertEquals(listOf(0, 0, 1, 2, 3, 4, 4), listOf(0.0, 4.99, 5.0, 19.9, 20.0, 50.0, 500.0).map(AccuracyClasses::of))
    }

    @Test
    fun theInertialFilterRunsInTheReport() {
        val r = SessionReports.build(drive().asSequence())
        assertEquals(6, r.version)
        val f = assertNotNull(r.summary.inertial)
        // the 60-90 s cut-off is dead-reckoned (from 2.5 s after the last fix until AUTO returns)
        assertTrue(r.spans.any { it.kind == ReportSpan.DEAD_RECKONING && it.fromNs < 65 * sec && it.toNs > 85 * sec }, "${r.spans}")
        assertTrue(f.deadReckonedS in 20.0..32.0, "${f.deadReckonedS}")
        val p50 = assertNotNull(f.errP50M)
        assertTrue(kotlin.math.abs(p50 - f.todayP50M!!) < 2.0, "filter $p50 vs today ${f.todayP50M}")
        assertTrue(r.points.count { it.ferr != null } > 80)
        assertEquals(0, f.sidewaysOpenings)
    }
}
