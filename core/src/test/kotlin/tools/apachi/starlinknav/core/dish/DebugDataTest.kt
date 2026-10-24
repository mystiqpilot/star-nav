package tools.apachi.starlinknav.core.dish

import okio.ByteString.Companion.decodeBase64
import tools.apachi.starlinknav.core.log.Redactor
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebugDataTest {
    private val dishSim = FakeDishSimulation(spoofEverySec = 0)
    private val routerSim = FakeRouterSimulation()

    private fun probe(): Pair<List<DishCallResult>, List<DishCallResult>> =
        DishProbe.run(DishClient(FakeTransport(dishSim))) to DishProbe.runRouter(RouterClient(FakeTransport(routerSim)))

    @Test
    fun thePageHasTheDocumentedShape() {
        val (dish, router) = probe()
        val text = DebugDataBuilder.toJson(dish, router, DebugDataBuilder.Options(appVersion = "0.4.0"), nowWallMs = 1_700_000_000_000L)
        val doc = DebugDataBuilder.decode(text)
        assertEquals("starnav-debugdata-1", doc.format)
        assertEquals("2023-11-14T22:13:20Z", doc.capturedAt)
        assertEquals("0.4.0", doc.app.version)
        assertTrue(doc.identifiersRemoved)
        assertTrue("\"format\": \"starnav-debugdata-1\"" in text)
        // Dish: a summary and the raw answers of the documented calls.
        val d = assertNotNull(doc.dish)
        assertTrue(d.reachable)
        assertEquals("mini1_fake", d.summary["hardware"])
        assertEquals("NO", d.summary["has_actuators"])
        assertEquals("CONNECTED", d.summary["state"])
        assertTrue(d.summary["initialization"]!!.contains("gps_valid=14s"))
        assertEquals(setOf("status", "history", "obstruction_map", "device_info", "diagnostics", "config"), d.raw.keys.intersect(setOf("status", "history", "obstruction_map", "device_info", "diagnostics", "config")))
        assertEquals("OK", d.outcomes["status"])
        assertEquals("UNIMPLEMENTED", d.outcomes["gnss_measurement"])
        assertFalse(d.raw.keys.any { it.startsWith("location") }, "no coordinates in a snapshot")
        assertFalse("gnss_measurement" in d.raw)
        // Router: summary and redacted raw answers.
        val r = assertNotNull(doc.router)
        assertEquals("3", r.summary["clients"])
        assertEquals("NONE", r.summary["dns"])
        assertTrue("router:status" in r.raw && "router:config" in r.raw && "router:clients" in r.raw)
        // Every raw answer is the bytes of a DeviceResponse again.
        for ((label, b64) in d.raw + r.raw) {
            val bytes = assertNotNull(b64.decodeBase64(), label).toByteArray()
            assertNotNull(DeviceResponse.ADAPTER.decode(bytes), label)
        }
        val results = DebugDataBuilder.results(r)
        assertEquals(r.raw.size, results.size)
        assertTrue(results.all { it.ok })
        assertNotNull(results.first { it.kind == DishCallKind.ROUTER_STATUS }.response?.wifi_get_status)
    }

    @Test
    fun noPasswordSecretOrKeyIsEverInTheDocument() {
        val (dish, router) = probe()
        for (remove in listOf(true, false)) {
            val text = DebugDataBuilder.toJson(dish, router, DebugDataBuilder.Options(removeIdentifiers = remove))
            assertFalse(FakeRouterSimulation.FAKE_PASSWORD in text, "password in the text (removeIdentifiers=$remove)")
            val doc = DebugDataBuilder.decode(text)
            for (device in listOfNotNull(doc.dish, doc.router)) {
                for ((label, b64) in device.raw) {
                    val bytes = b64.decodeBase64()!!.toByteArray()
                    assertFalse(TestWire.contains(bytes, FakeRouterSimulation.FAKE_PASSWORD), "password in $label")
                    assertTrue(Redact.secretsIn(DeviceResponse.ADAPTER.decode(bytes)).isEmpty(), label)
                }
            }
        }
        // Even results that were never redacted (not from runRouter) come out clean.
        val rawRouter = DishCallKind.ROUTER_QUERIES.map { RouterClient(FakeTransport(routerSim)).call(it) }
        assertTrue(rawRouter.flatMap { Redact.secretsIn(it.response) }.isNotEmpty(), "the unredacted results hold the password")
        val text = DebugDataBuilder.toJson(dish, rawRouter)
        assertFalse(FakeRouterSimulation.FAKE_PASSWORD in text)
        DebugDataBuilder.decode(text).router!!.raw.values.forEach { assertFalse(TestWire.contains(it.decodeBase64()!!.toByteArray(), FakeRouterSimulation.FAKE_PASSWORD)) }
        // A fixture with a secret in every place and an unknown field.
        val secretStatus = DishCallResult(
            DishCallKind.ROUTER_STATUS, 0, 0, "t", 200, GrpcStatus.OK, null, DeviceResponse.ADAPTER.encode(secretStatusResponse()), secretStatusResponse(), null,
        )
        val doc = DebugDataBuilder.toJson(emptyList(), listOf(secretStatus))
        for (secret in ALL_SECRETS) {
            assertFalse(secret in doc, secret)
            DebugDataBuilder.decode(doc).router!!.raw.values.forEach { assertFalse(TestWire.contains(it.decodeBase64()!!.toByteArray(), secret), secret) }
        }
    }

    @Test
    fun identifiersAreRemovedOnRequestAndKeptOtherwise() {
        val (dish, router) = probe()
        val removed = DebugDataBuilder.toJson(dish, router, DebugDataBuilder.Options(removeIdentifiers = true))
        val kept = DebugDataBuilder.toJson(dish, router, DebugDataBuilder.Options(removeIdentifiers = false))
        fun allRaw(text: String): List<ByteArray> = DebugDataBuilder.decode(text).let { d ->
            listOfNotNull(d.dish, d.router).flatMap { it.raw.values.map { b -> b.decodeBase64()!!.toByteArray() } }
        }
        val ids = listOf("ut-fake-0000-0001", "Router-fake-0001", "02:00:00:00:01:01", "pixel-fake", "192.168.1.10", "203.0.113.7", "02:00:00:aa:bb:01", "02:00:00:00:00:0a", "100.64.1.2")
        for (id in ids) {
            assertFalse(allRaw(removed).any { TestWire.contains(it, id) } || id in removed, "$id survived removal")
            assertTrue(allRaw(kept).any { TestWire.contains(it, id) } || id in kept, "$id missing when identifiers are kept")
        }
        // The SSID is an identifier of the network; coordinates in the diagnostics are removed too.
        assertFalse(allRaw(removed).any { TestWire.contains(it, "STARLINK") })
        val diag = DebugDataBuilder.results(DebugDataBuilder.decode(removed).dish).first { it.kind == DishCallKind.DIAGNOSTICS }.response!!.dish_get_diagnostics!!
        assertEquals(0.0, diag.location!!.latitude)
        assertEquals(0.0, diag.location!!.longitude)
        assertTrue(diag.location!!.enabled)
        // A position is never in a snapshot, not even when identifiers are kept (the diagnostics' id is).
        val keptDiag = DebugDataBuilder.results(DebugDataBuilder.decode(kept).dish).first { it.kind == DishCallKind.DIAGNOSTICS }.response!!.dish_get_diagnostics!!
        assertEquals(0.0, keptDiag.location!!.latitude)
        assertEquals(0.0, keptDiag.location!!.longitude)
        assertEquals(0.0, keptDiag.location!!.altitude_meters)
        assertTrue(keptDiag.location!!.enabled)
        assertNotEquals("redacted", keptDiag.id)
        // The summary names no id when they are removed.
        assertNull(DebugDataBuilder.decode(removed).dish!!.summary["id"])
        assertEquals("ut-fake-0000-0001", DebugDataBuilder.decode(kept).dish!!.summary["id"])
        assertNull(DebugDataBuilder.decode(removed).router!!.summary["ssids"])
        assertEquals("STARLINK", DebugDataBuilder.decode(kept).router!!.summary["ssids"])
        // The counters and states survive removal.
        assertEquals("3", DebugDataBuilder.decode(removed).router!!.summary["clients"])
    }

    @Test
    fun noPositionIsEverInASnapshotWhateverTheOptions() {
        val (dish, router) = probe()
        // The fake dish's diagnostics hold a real-looking position: find its digits anywhere in the document.
        val live = dish.first { it.kind == DishCallKind.DIAGNOSTICS }.response!!.dish_get_diagnostics!!.location!!
        assertTrue(live.latitude > 40 && live.longitude > 20, "the fixture has a position")
        // Also a position planted where a location answer or an unknown field could carry one.
        val planted = dish.map { r ->
            if (r.kind != DishCallKind.DIAGNOSTICS) r else {
                val d = r.response!!
                val withUnknown = d.copy(
                    dish_get_diagnostics = d.dish_get_diagnostics!!.copy(
                        location = d.dish_get_diagnostics!!.location!!.copy(unknownFields = okio.ByteString.of(0x79, 1, 2, 3, 4, 5, 6, 7, 8)),
                    ),
                )
                DishCallResult(r.kind, r.sentMonoNs, r.rttNs, r.transport, r.httpStatus, r.grpcStatus, r.grpcMessage, DeviceResponse.ADAPTER.encode(withUnknown), withUnknown, null)
            }
        }
        for (remove in listOf(true, false)) {
            val text = DebugDataBuilder.toJson(planted, router, DebugDataBuilder.Options(removeIdentifiers = remove))
            val doc = DebugDataBuilder.decode(text)
            for (device in listOfNotNull(doc.dish, doc.router)) {
                assertFalse(device.raw.keys.any { it.startsWith("location") }, "no location answer (remove=$remove)")
                for ((label, b64) in device.raw) {
                    val response = DeviceResponse.ADAPTER.decode(b64.decodeBase64()!!.toByteArray())
                    val location = response.dish_get_diagnostics?.location
                    if (location != null) {
                        assertEquals(0.0, location.latitude, "$label latitude (remove=$remove)")
                        assertEquals(0.0, location.longitude, "$label longitude")
                        assertEquals(0.0, location.altitude_meters, "$label altitude")
                        assertEquals(0.0, location.gps_time_s, "$label gps time")
                        assertEquals(okio.ByteString.EMPTY, location.unknownFields, "$label location unknown fields")
                    }
                    assertNull(response.get_location?.lla)
                }
            }
        }
        // The location call itself: its coordinates leave a stray answer too.
        val loc = DishCallKind.LOCATION_AUTO.let { k -> DishClient(FakeTransport(dishSim)).call(k) }
        assertNull(Redactor.withoutCoordinates(loc.response!!).get_location!!.lla)
        assertEquals(loc.response!!.get_location!!.source, Redactor.withoutCoordinates(loc.response!!).get_location!!.source)
    }

    @Test
    fun theProbeCanStopAtTheFirstCallThatReachedNoDevice() {
        val down = DishClient(object : GrpcTransport {
            override val name = "down"
            override val endpoint = "x"
            override fun unary(path: String, request: ByteArray, safeToRepeat: Boolean): GrpcReply {
                throw java.io.IOException("no route to host")
            }
        })
        val all = DishProbe.run(down)
        assertEquals(DishProbe.KINDS.size, all.size)
        val stopped = DishProbe.run(down, stopWhenUnreachable = true)
        assertEquals(1, stopped.size)
        assertEquals(DishProbe.KINDS.first(), stopped.single().kind)
        // A device that answers is probed to the end either way.
        assertEquals(DishProbe.KINDS.size, DishProbe.run(DishClient(FakeTransport(dishSim)), stopWhenUnreachable = true).size)
    }

    @Test
    fun theCheckSeesSecretsOfTheUnredactedAnswersAndLooksInsideTheBlobs() {
        // Fed the router's answers as they came (this is what the CLI does), the check has real secrets to look for.
        val dishResults = DishProbe.run(DishClient(FakeTransport(dishSim)))
        val unredacted = DishProbe.runRouterUnredacted(RouterClient(FakeTransport(routerSim)))
        assertTrue(unredacted.none { it.payloadRedacted })
        assertTrue(unredacted.flatMap { Redact.secretsIn(it.response) }.contains(FakeRouterSimulation.FAKE_PASSWORD))
        val redacted = unredacted.map { it.redacted() }
        assertTrue(redacted.all { it.payloadRedacted })
        assertEquals(DebugDataBuilder.toJson(dishResults, redacted, nowWallMs = 1), DebugDataBuilder.toJson(dishResults, unredacted, nowWallMs = 1))

        // A secret that sits at any byte offset of a blob is found: every alignment of the base64.
        val secret = "s3cret-pass-XYZ"
        val data = DebugDataBuilder.build(dishResults, unredacted)
        for (pad in 0..3) {
            val blob = ByteArray(pad) { 7 } + secret.encodeToByteArray() + byteArrayOf(1, 2, 3)
            val tainted = data.copy(router = data.router!!.copy(raw = data.router!!.raw + ("planted" to okio.ByteString.of(*blob).base64())))
            val failure = assertFailsWith<IllegalStateException>("offset $pad") { DebugDataBuilder.checkNoSecrets(tainted, DebugDataBuilder.encode(tainted), listOf(secret)) }
            assertTrue("planted" in failure.message!!, failure.message)
        }
        DebugDataBuilder.checkNoSecrets(data, DebugDataBuilder.encode(data), listOf(secret)) // clean
    }

    @Test
    fun aSnapshotOfAnUnreachableDeviceStillDecodes() {
        val dish = DishProbe.KINDS.map { DishCallResult.failure(it, 0, 0, "-", "no route to host") }
        val text = DebugDataBuilder.toJson(dish)
        val doc = DebugDataBuilder.decode(text)
        assertFalse(doc.dish!!.reachable)
        assertTrue(doc.dish!!.raw.isEmpty())
        assertEquals("UNREACHABLE", doc.dish!!.outcomes["status"])
        assertNull(doc.router)
        assertEquals(emptyList(), DebugDataBuilder.results(null))
    }

    @Test
    fun otherDocumentsAreRefused() {
        assertFailsWith<IllegalArgumentException> { DebugDataBuilder.decode("""{"format":"something-else","capturedAt":"x","app":{"version":"1"},"identifiersRemoved":true}""") }
        assertFailsWith<Exception> { DebugDataBuilder.decode("not json") }
    }
}
