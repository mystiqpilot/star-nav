package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutcomeTest {
    @Test
    fun errorTextsNameOnlyClassesWhoseNamesSurviveARelease() {
        // R8 renamed GrpcProtocolException to "gs0" in the release build: it is named explicitly, the platform's stay.
        assertEquals("GrpcProtocolException", GrpcProtocolException("bad frame length").readableName())
        assertEquals("ConnectException", java.net.ConnectException("refused").readableName())
        val e = GrpcProtocolException("bad frame length 1752460652")
        e.addSuppressed(java.net.ConnectException("Failed to connect"))
        assertEquals("ConnectException: Failed to connect | then GrpcProtocolException: bad frame length 1752460652", e.describe())
    }

    private fun ok(kind: DishCallKind = DishCallKind.STATUS) =
        DishCallResult(kind, 0, 1_000_000, "grpc-web", 200, GrpcStatus.OK, null, ByteArray(0), DeviceResponse(), null)

    private fun refused(code: Int, message: String?, kind: DishCallKind = DishCallKind.LOCATION_AUTO) =
        DishCallResult(kind, 0, 1_000_000, "grpc-web", 200, code, message, null, null, null)

    @Test
    fun classifiesFromStatusMessageAndTransport() {
        assertEquals(CallOutcome.OK, ok().outcome)
        assertEquals(CallOutcome.UNREACHABLE, DishCallResult.failure(DishCallKind.STATUS, 0, 0, "-", "connect timed out").outcome)
        assertEquals(CallOutcome.UNIMPLEMENTED, refused(GrpcStatus.UNIMPLEMENTED, "Unimplemented: *device.Request_GetLocation").outcome)
        assertEquals(CallOutcome.UNIMPLEMENTED, refused(2, "Failed to get location: Requested position source is not supported: 6").outcome)
        assertEquals(CallOutcome.DISABLED_BY_POLICY, refused(GrpcStatus.PERMISSION_DENIED, "GetLocation requests disabled due to UT disablement").outcome)
        // The plan wording is persistent (non-Priority plans), not a disablement.
        assertEquals(CallOutcome.PLAN_RESTRICTED, refused(GrpcStatus.PERMISSION_DENIED, "Failed to get location: Disabled due to policy").outcome)
        assertEquals(CallOutcome.PERMISSION_DENIED, refused(GrpcStatus.PERMISSION_DENIED, "Permission denied").outcome)
        assertEquals(CallOutcome.PERMISSION_DENIED, refused(GrpcStatus.PERMISSION_DENIED, "GetLocation requests are not enabled on this device").outcome)
        assertEquals(CallOutcome.PERMISSION_DENIED, refused(GrpcStatus.PERMISSION_DENIED, null).outcome)
        assertEquals(CallOutcome.UNAUTHENTICATED, refused(GrpcStatus.UNAUTHENTICATED, "not logged in").outcome)
        assertEquals(CallOutcome.UNREACHABLE, refused(GrpcStatus.UNAVAILABLE, "fake dish: restarting").outcome)
        assertEquals(CallOutcome.OTHER, refused(13, "internal error").outcome)
        assertEquals(CallOutcome.OTHER, refused(3, "Invalid power save schedule").outcome)
        // An HTTP error without any gRPC status, and a reply that does not decode.
        assertEquals(CallOutcome.OTHER, DishCallResult(DishCallKind.STATUS, 0, 0, "grpc-web", 404, null, null, null, null, null).outcome)
        assertEquals(CallOutcome.OTHER, DishCallResult(DishCallKind.STATUS, 0, 0, "grpc-web", 200, GrpcStatus.OK, null, byteArrayOf(1), null, "decode: bad").outcome)
    }

    @Test
    fun onlyUnavailableOutcomesGreyAFeatureOut() {
        assertTrue(CallOutcome.UNIMPLEMENTED.isUnavailable)
        assertTrue(CallOutcome.PERMISSION_DENIED.isUnavailable)
        assertTrue(CallOutcome.UNAUTHENTICATED.isUnavailable)
        assertTrue(CallOutcome.PLAN_RESTRICTED.isUnavailable) // lasts until the plan changes
        for (o in listOf(CallOutcome.OK, CallOutcome.DISABLED_BY_POLICY, CallOutcome.UNREACHABLE, CallOutcome.SENT_NO_ANSWER, CallOutcome.OTHER)) assertFalse(o.isUnavailable, "$o")
    }

    @Test
    fun capabilityBookRemembersPerDeviceAndCall() {
        val dish = CapabilityBook.keyOf("ut-fake-0000-0001")
        val router = CapabilityBook.keyOf("Router-fake-0001")
        assertNotEquals(dish, router)
        assertEquals(16, dish.length)
        assertFalse("ut-fake" in dish)
        var book = CapabilityBook()
        book = book.record(dish, refused(GrpcStatus.UNIMPLEMENTED, "Unimplemented: *device.Request_GetRadioStats", DishCallKind.RADIO_STATS), 1_000)
        book = book.record(dish, refused(GrpcStatus.PERMISSION_DENIED, "Permission denied", DishCallKind.DISH_SET_CONFIG), 2_000)
        book = book.record(dish, ok(DishCallKind.STATUS), 3_000)
        assertTrue(book.isUnavailable(dish, DishCallKind.RADIO_STATS))
        assertTrue(book.isUnavailable(dish, DishCallKind.DISH_SET_CONFIG))
        assertFalse(book.isUnavailable(dish, DishCallKind.STATUS))
        assertFalse(book.isUnavailable(dish, DishCallKind.TIME)) // never tried
        assertFalse(book.isUnavailable(router, DishCallKind.RADIO_STATS)) // another device
        assertEquals("UNIMPLEMENTED: Unimplemented: *device.Request_GetRadioStats", book.reason(dish, DishCallKind.RADIO_STATS))
        assertNull(book.reason(dish, DishCallKind.STATUS))

        // An unreachable device says nothing about what it implements.
        val still = book.record(dish, DishCallResult.failure(DishCallKind.RADIO_STATS, 0, 0, "-", "timeout"), 4_000)
        assertTrue(still.isUnavailable(dish, DishCallKind.RADIO_STATS))
        // A command that went out without an answer says nothing either.
        val sent = DishCallResult.failure(DishCallKind.RADIO_STATS, 0, 0, "-", "reset", sentNoAnswer = true)
        assertEquals(CallOutcome.SENT_NO_ANSWER, sent.outcome)
        assertEquals(book, book.record(dish, sent, 4_500))
        // A later answer replaces the earlier one (firmware update, account change).
        val fixed = book.record(dish, ok(DishCallKind.RADIO_STATS), 5_000)
        assertFalse(fixed.isUnavailable(dish, DishCallKind.RADIO_STATS))
        // The same outcome again counts up.
        val again = book.record(dish, refused(GrpcStatus.UNIMPLEMENTED, "x", DishCallKind.RADIO_STATS), 6_000)
        assertEquals(2, again.last(dish, DishCallKind.RADIO_STATS)?.count)
        // No device id yet: nothing recorded.
        assertEquals(book, book.record("", ok(), 7_000))
    }

    @Test
    fun onlyAChangedAnswerIsNewsForTheBook() {
        val key = CapabilityBook.keyOf("ut-fake-0000-0001")
        var book = CapabilityBook()
        // A call never seen is news; so is a device without a key not (nothing is recorded for it).
        assertTrue(book.hasNews(key, ok(), 1_000))
        assertFalse(book.hasNews("", ok(), 1_000))
        book = book.record(key, ok(), 1_000)
        // The same answer again, a second later or a minute later, is not.
        assertFalse(book.hasNews(key, ok(), 2_000))
        assertFalse(book.hasNews(key, ok(), 60_000))
        // Another outcome class, or the same class with other words, is.
        assertTrue(book.hasNews(key, refused(GrpcStatus.UNIMPLEMENTED, "nope", DishCallKind.STATUS), 3_000))
        book = book.record(key, refused(GrpcStatus.UNIMPLEMENTED, "nope", DishCallKind.STATUS), 3_000)
        assertFalse(book.hasNews(key, refused(GrpcStatus.UNIMPLEMENTED, "nope", DishCallKind.STATUS), 4_000))
        assertTrue(book.hasNews(key, refused(GrpcStatus.UNIMPLEMENTED, "other words", DishCallKind.STATUS), 4_000))
        // Another call of the same device is its own entry.
        assertTrue(book.hasNews(key, ok(DishCallKind.TIME), 4_000))
        // A device that did not answer says nothing, so it is never news.
        assertFalse(book.hasNews(key, DishCallResult.failure(DishCallKind.STATUS, 0, 0, "-", "timeout"), 5_000))
        // An unchanged entry is refreshed once it is old, so a device polled all day keeps its place among the few remembered.
        assertTrue(book.hasNews(key, refused(GrpcStatus.UNIMPLEMENTED, "nope", DishCallKind.STATUS), 3_000 + CapabilityBook.STALE_MS + 1))
    }

    @Test
    fun capabilityBookSurvivesJsonAndGarbage() {
        val key = CapabilityBook.keyOf("ut-fake-0000-0001")
        val book = CapabilityBook().record(key, refused(GrpcStatus.UNIMPLEMENTED, "nope", DishCallKind.TIME), 99)
        val back = CapabilityBook.fromJson(book.toJson())
        assertEquals(book, back)
        assertNotNull(back.last(key, DishCallKind.TIME))
        assertEquals(CapabilityBook(), CapabilityBook.fromJson("not json"))
        assertEquals(CapabilityBook(), CapabilityBook.fromJson(null))
    }

    @Test
    fun theLocationPlanRestrictionGreysOutAndTheDisablementDoesNot() {
        val key = CapabilityBook.keyOf("ut-fake-0000-0001")
        val plan = CapabilityBook().record(key, refused(GrpcStatus.PERMISSION_DENIED, "Failed to get location: Disabled due to policy"), 1)
        assertTrue(plan.isUnavailable(key, DishCallKind.LOCATION_AUTO))
        assertEquals("PLAN_RESTRICTED: Failed to get location: Disabled due to policy", plan.reason(key, DishCallKind.LOCATION_AUTO))
        val moving = CapabilityBook().record(key, refused(GrpcStatus.PERMISSION_DENIED, "GetLocation requests disabled due to UT disablement"), 1)
        assertFalse(moving.isUnavailable(key, DishCallKind.LOCATION_AUTO))
    }

    @Test
    fun capabilityBookSurvivesStoredDevicesWithoutEntries() {
        // A device with an empty map in stored JSON (hand-edited or from a partial write) must not crash the eviction.
        var book = CapabilityBook.fromJson("""{"devices":{"aaaaaaaaaaaaaaaa":{}}}""")
        assertEquals(1, book.devices.size)
        for (i in 1..CapabilityBook.MAX_DEVICES) book = book.record(CapabilityBook.keyOf("device-$i"), ok(), i * 1000L)
        assertEquals(CapabilityBook.MAX_DEVICES, book.devices.size)
        assertFalse("aaaaaaaaaaaaaaaa" in book.devices) // the empty one is the oldest
    }

    @Test
    fun capabilityBookKeepsAFewDevices() {
        var book = CapabilityBook()
        for (i in 1..12) book = book.record(CapabilityBook.keyOf("device-$i"), ok(), i * 1000L)
        assertEquals(CapabilityBook.MAX_DEVICES, book.devices.size)
        assertTrue(CapabilityBook.keyOf("device-12") in book.devices)
        assertFalse(CapabilityBook.keyOf("device-1") in book.devices)
    }

    @Test
    fun resultsOfAProbeFillTheBook() {
        val dish = FakeDishSimulation(unimplemented = setOf("get_radio_stats"))
        val client = DishClient(FakeTransport(dish))
        val results = DishProbe.run(client)
        val key = CapabilityBook.keyOf("ut-fake-0000-0001")
        val book = CapabilityBook().recordAll(key, results, 1)
        assertTrue(book.isUnavailable(key, DishCallKind.RADIO_STATS))
        assertTrue(book.isUnavailable(key, DishCallKind.GNSS_MEASUREMENT))
        assertFalse(book.isUnavailable(key, DishCallKind.STATUS))
    }
}
