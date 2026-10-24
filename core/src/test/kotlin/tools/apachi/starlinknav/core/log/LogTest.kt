package tools.apachi.starlinknav.core.log

import okio.ByteString.Companion.toByteString
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.PositionSource
import tools.apachi.starlinknav.core.dish.proto.DeviceInfo
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.proto.GetLocationResponse
import tools.apachi.starlinknav.core.dish.proto.LLAPosition
import tools.apachi.starlinknav.core.log.proto.DishCall
import tools.apachi.starlinknav.core.log.proto.ImuBatch
import tools.apachi.starlinknav.core.log.proto.ImuSample
import tools.apachi.starlinknav.core.log.proto.PhoneLocation
import tools.apachi.starlinknav.core.log.proto.Record
import tools.apachi.starlinknav.core.log.proto.SessionHeader
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogTest {
    private val dir: File = Files.createTempDirectory("slog-test").toFile()

    @Test
    fun anExportedHeaderDoesNotSayWhenTheMountWasLearned() {
        // Headers of an earlier build carried the wall-clock time of an earlier drive; the header is exported whatever the window.
        val json = """{"mountMode":"AUTO","mountLearnedDeg":-177.0,"mountLearnedAtMs":1792025768000,"inertialCorrection":true}"""
        val rec = Record(t_mono_ns = 1, header_ = SessionHeader(session_id = "s1", config_json = json))
        val out = Redactor.redact(rec).header_!!.config_json
        assertFalse("mountLearnedAtMs" in out, out)
        assertTrue("\"mountLearnedDeg\":-177.0" in out && "\"mountMode\":\"AUTO\"" in out, out)
        // A header without it is left exactly as it was.
        val clean = Record(t_mono_ns = 1, header_ = SessionHeader(session_id = "s1", config_json = """{"mountMode":"AUTO"}"""))
        assertTrue(Redactor.redact(clean) === clean)
    }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val sec = 1_000_000_000L

    private fun header() = Record(
        t_mono_ns = 1 * sec, t_wall_ms = 1_000,
        header_ = SessionHeader(session_id = "s1", app_version = "0.1.0", device_model = "Pixel", sdk_int = 35),
    )

    private fun dishRecord(t: Long, kind: DishCallKind, response: DeviceResponse) = Record(
        t_mono_ns = t, t_wall_ms = t / 1_000_000,
        dish = DishCall(kind = kind.code, grpc_status = 0, http_status = 200, response = DeviceResponse.ADAPTER.encode(response).toByteString()),
    )

    private fun dishFix(t: Long, lat: Double, lon: Double, sigma: Double = 0.7, kind: DishCallKind = DishCallKind.LOCATION_STARLINK) =
        dishRecord(t, kind, DeviceResponse(get_location = GetLocationResponse(LLAPosition(lat, lon, 150.0), source = PositionSource.STARLINK.code, sigma_m = sigma)))

    private fun phoneFix(t: Long, lat: Double, lon: Double, mock: Boolean = false) =
        Record(t_mono_ns = t, t_wall_ms = t / 1_000_000, phone_location = PhoneLocation(provider = "gps", lat = lat, lon = lon, h_acc = 4f, is_mock = mock))

    private fun imu(t: Long) = Record(
        t_mono_ns = t, t_wall_ms = t / 1_000_000,
        imu = ImuBatch(listOf(ImuSample(sensor_type = 35, t_ns = t, v = listOf(0.1f, 0.2f, 9.8f, 0f, 0f, 0f)))),
    )

    private fun writeLog(name: String, records: List<Record>): File {
        val f = File(dir, "$name.slog")
        SlogWriter.create(f).use { w -> records.forEach(w::write) }
        return f
    }

    @Test
    fun roundTrip() {
        val records = listOf(header(), imu(2 * sec), dishFix(3 * sec, 50.0, 30.0), phoneFix(4 * sec, 50.0, 30.0))
        val f = writeLog("rt", records)
        SlogReader.open(f).use { r ->
            assertEquals(records, r.records().toList())
            assertFalse(r.truncatedTail)
        }
    }

    @Test
    fun truncatedTailKeepsCompleteRecords() {
        val records = (1..50).map { imu(it * sec) }
        val f = writeLog("trunc", records)
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 3) }
        SlogReader.open(f).use { r ->
            assertEquals(records.dropLast(1), r.records().toList())
            assertTrue(r.truncatedTail)
        }
    }

    @Test
    fun rejectsForeignFiles() {
        val f = File(dir, "x.slog").apply { writeText("not a log at all") }
        assertFailsWith<IOException> { SlogReader.open(f) }
    }

    @Test
    fun scanBuildsTracksFromTheMostPreciseSource() {
        val records = buildList {
            add(header())
            for (i in 0 until 10) {
                val t = (10 + i) * sec
                add(dishFix(t, 50.0 + i * 1e-4 + 3e-4, 30.0, sigma = 99.0, kind = DishCallKind.LOCATION_STARLINK))
                add(dishFix(t, 50.0 + i * 1e-4, 30.0, sigma = 1.65, kind = DishCallKind.LOCATION_AUTO))
                add(phoneFix(t, 50.0 + i * 1e-4, 30.0))
            }
            add(dishFix(30 * sec, 0.0, 0.0, kind = DishCallKind.LOCATION_AUTO)) // impossible coordinate: no track point
            add(phoneFix(31 * sec, -12.05, -77.04, mock = true)) // our own mock output: not a track point
            add(phoneFix(32 * sec, -12.05, -77.04)) // spoofed jump: in the track, excluded from distance
        }
        val index = Sessions.scan(writeLog("scan", records))
        assertEquals("s1", index.id)
        assertEquals(DishCallKind.LOCATION_AUTO.label, index.dishTrackKind)
        assertEquals(10, index.dishTrack.size)
        assertEquals(11, index.phoneTrack.size)
        assertEquals(mapOf("dish" to 21L, "header" to 1L, "phone_location" to 12L), index.records)
        val d = index.distanceM!!
        assertTrue(d in 95.0..105.0, "distance $d")
    }

    @Test
    fun exportKeepsWindowOnlyAndRedactsIdentifiers() {
        val status = DeviceResponse(
            dish_get_status = DishGetStatusResponse(
                device_info = DeviceInfo(id = "ut01000000-00000000-00abcdef", software_version = "2026.09.18.mr87170.1", hardware_version = "mini1_prod2"),
                connected_routers = listOf("Router-010000000000000000ABCDEF"),
            ),
        )
        val records = listOf(
            header(),
            phoneFix(5 * sec, 50.10, 30.10), // home: before the window
            dishRecord(10 * sec, DishCallKind.STATUS, status),
            dishFix(11 * sec, 50.20, 30.20),
            imu(12 * sec),
            phoneFix(30 * sec, 50.30, 30.30), // destination: after the window
        )
        val log = writeLog("exp", records)
        val zip = File(dir, "out/export.zip")
        val manifest = SessionExporter.export(log, 10 * sec, 20 * sec, zip, "test")

        assertEquals(mapOf("dish" to 2L, "header" to 1L, "imu" to 1L), manifest.records)
        assertEquals("2026.09.18.mr87170.1", manifest.dishSoftwareVersion)
        ZipFile(zip).use { z ->
            val exported = File(dir, "exported.slog")
            z.getInputStream(z.getEntry(SessionExporter.LOG_ENTRY)).use { input -> exported.outputStream().use { input.copyTo(it) } }
            val out = SlogReader.open(exported).use { it.records().toList() }
            assertTrue(out.none { it.phone_location != null }, "phone fixes outside the window leaked")
            assertTrue(out.all { it.header_ != null || it.t_mono_ns in 10 * sec..20 * sec })
            val exportedStatus = out.mapNotNull { it.dish }.map { DeviceResponse.ADAPTER.decode(it.response) }
                .firstNotNullOf { it.dish_get_status }
            assertEquals(Redactor.REDACTED, exportedStatus.device_info?.id)
            assertEquals(emptyList(), exportedStatus.connected_routers)
            assertEquals("2026.09.18.mr87170.1", exportedStatus.device_info?.software_version)
            val bytes = exported.readBytes().decodeToString(throwOnInvalidSequence = false)
            assertFalse("00abcdef" in bytes || "ABCDEF" in bytes, "identifier bytes survived redaction")
            assertTrue(z.getEntry(SessionExporter.MANIFEST_ENTRY) != null)
        }
    }
}
