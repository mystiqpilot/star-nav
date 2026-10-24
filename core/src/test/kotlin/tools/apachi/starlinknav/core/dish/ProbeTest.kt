package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.proto.GetLocationResponse
import tools.apachi.starlinknav.core.dish.proto.LLAPosition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProbeTest {
    private fun ok(kind: DishCallKind, resp: DeviceResponse) =
        DishCallResult(kind, 0, 1_000_000, "grpc-web", 200, 0, null, DeviceResponse.ADAPTER.encode(resp), resp, null)

    private fun loc(kind: DishCallKind, lat: Double, lon: Double, sigma: Double, source: PositionSource) =
        ok(kind, DeviceResponse(get_location = GetLocationResponse(LLAPosition(lat, lon, 100.0), source = source.code, sigma_m = sigma)))

    private fun failed(kind: DishCallKind, code: Int, message: String) =
        DishCallResult(kind, 0, 1_000_000, "grpc-web", 200, code, message, null, null, null)

    @Test
    fun staleStarlinkFixIsANoteNotASpoofingWarning() {
        // Real case 2026-10-14: the frozen Starlink-only fix was 1.6 km away after driving.
        val r = DishProbe.report(
            listOf(
                loc(DishCallKind.LOCATION_AUTO, 50.0, 30.0, 1.6, PositionSource.STARLINK),
                loc(DishCallKind.LOCATION_STARLINK, 50.014, 30.0, 49.9, PositionSource.STARLINK),
            ),
            "test", includeCoordinates = false,
        )
        assertFalse("WARNING" in r, r)
        assertContains(r, "note: STARLINK fix is 1.56 km from AUTO")
    }

    @Test
    fun continentScaleGapIsASpoofingWarning() {
        val r = DishProbe.report(
            listOf(
                loc(DishCallKind.LOCATION_AUTO, -12.05, -77.04, 1.6, PositionSource.STARLINK),
                loc(DishCallKind.LOCATION_STARLINK, 50.0, 30.0, 49.9, PositionSource.STARLINK),
            ),
            "test", includeCoordinates = false,
        )
        assertContains(r, "WARNING: dish AUTO is")
    }

    @Test
    fun disablementIsNamedInTheVerdict() {
        val status = ok(DishCallKind.STATUS, DeviceResponse(dish_get_status = DishGetStatusResponse(disablement_code = 14)))
        val refused = failed(DishCallKind.LOCATION_AUTO, GrpcStatus.PERMISSION_DENIED, "GetLocation requests disabled due to UT disablement")
        val r = DishProbe.report(listOf(status, refused), "test", includeCoordinates = false)
        assertContains(r, "MOVING_TOO_FAST_FOR_POLICY")
    }

    @Test
    fun onlyMissingFeaturesCountAsUnsupported() {
        assertTrue(failed(DishCallKind.LOCATION_GNC_FUSED, 2, "Failed to get location: Requested position source is not supported: 6").unsupported)
        assertTrue(failed(DishCallKind.GNSS_MEASUREMENT, GrpcStatus.UNIMPLEMENTED, "Unimplemented").unsupported)
        assertFalse(failed(DishCallKind.LOCATION_AUTO, GrpcStatus.PERMISSION_DENIED, "GetLocation requests disabled due to UT disablement").unsupported)
    }

    // ---- the full probe, dish and router

    private fun fullProbe(dish: FakeDishSimulation = FakeDishSimulation(spoofEverySec = 0), router: FakeRouterSimulation = FakeRouterSimulation()): String {
        val dishResults = DishProbe.run(DishClient(FakeTransport(dish)))
        val routerResults = DishProbe.runRouter(RouterClient(FakeTransport(router)))
        return DishProbe.report(dishResults, "fake", includeCoordinates = false, routerResults = routerResults, routerEndpoint = "fake-router")
    }

    @Test
    fun coordinatesAndIdentifiersAreIncludedSeparately() {
        val dishResults = DishProbe.run(DishClient(FakeTransport(FakeDishSimulation(spoofEverySec = 0))))
        val routerResults = DishProbe.runRouter(RouterClient(FakeTransport(FakeRouterSimulation())))
        fun report(coords: Boolean, ids: Boolean) =
            DishProbe.report(dishResults, "fake", coords, routerResults = routerResults, routerEndpoint = "fake-router", includeIdentifiers = ids)
        val identifiers = listOf("203.0.113.7", "100.64.1.2", "02:00:00:00:00:01", "Router-fake-0001", "ut-fake-0000-0001", "[STARLINK]")
        // Coordinates only: what the "include coordinates" switch says, and no public IP, MAC, id or network name.
        val coordsOnly = report(coords = true, ids = false)
        assertTrue(Regex("lat=-?\\d").containsMatchIn(coordsOnly), coordsOnly)
        for (id in identifiers) assertFalse(id in coordsOnly, "$id with coordinates only")
        // Identifiers only: no position.
        val idsOnly = report(coords = false, ids = true)
        assertFalse(Regex("lat=-?\\d").containsMatchIn(idsOnly), idsOnly)
        for (id in identifiers) assertContains(idsOnly, id)
        // Neither, and the CLI's --coords (both).
        val neither = report(coords = false, ids = false)
        for (id in identifiers) assertFalse(id in neither, id)
        val both = DishProbe.report(dishResults, "fake", true, routerResults = routerResults, routerEndpoint = "fake-router")
        for (id in identifiers) assertContains(both, id)
    }

    @Test
    fun theFullProbeListsEveryReadOnlyRpcWithItsOutcome() {
        val report = fullProbe()
        for (kind in DishCallKind.QUERIES) {
            if (kind.isSensitive) continue
            assertContains(report, kind.label)
        }
        // Each line says which class the answer fell into.
        val lines = report.lines()
        assertTrue(lines.any { it.startsWith("status ") && " OK " in it })
        assertTrue(lines.any { it.startsWith("gnss_measurement") && "UNIMPLEMENTED" in it })
        assertTrue(lines.any { it.startsWith("location:GNC_FUSED") && "UNIMPLEMENTED" in it })
        assertTrue(lines.any { it.startsWith("router:clients") && " OK " in it })
        assertContains(report, "dish calls: ")
        assertContains(report, "router calls: ")
        assertContains(report, "ROUTER VERDICT: 15 of 16 calls answered; get_status came back as wifi_get_status")
        // New fields are decoded in the lines.
        assertContains(report, "state=CONNECTED")
        assertContains(report, "actuators=NO")
        assertContains(report, "init=")
        assertContains(report, "clients=3")
        assertContains(report, "interfaces=[eth0:up:1000Mbps")
        assertContains(report, "minus_phone=")
        assertContains(report, "self_test=PASSED")
        assertContains(report, "wifi_secrets=hidden")
        assertContains(report, "secrets: Wi-Fi passwords")
        // Nothing secret or identifying.
        assertFalse(FakeRouterSimulation.FAKE_PASSWORD in report)
        assertFalse("ut-fake-0000-0001" in report)
        assertFalse("Router-fake-0001" in report)
        assertFalse("02:00:00:00:01:01" in report)
        assertFalse("203.0.113.7" in report)
    }

    @Test
    fun anOutcomeAndTheDevicesMessageAreRecordedPerRpc() {
        val report = fullProbe(
            dish = FakeDishSimulation(spoofEverySec = 0, unimplemented = setOf("time", "get_ping")),
            router = FakeRouterSimulation(unimplemented = setOf("wifi_get_clients", "get_radio_stats")),
        )
        val lines = report.lines()
        assertTrue(lines.any { it.startsWith("time ") && "UNIMPLEMENTED" in it && "Unimplemented: *device.Request_Time" in it }, report)
        assertTrue(lines.any { it.startsWith("ping ") && "UNIMPLEMENTED" in it })
        assertTrue(lines.any { it.startsWith("router:clients") && "UNIMPLEMENTED" in it && "Request_WifiGetClients" in it })
        assertTrue(lines.any { it.startsWith("router:radio_stats") && "UNIMPLEMENTED" in it })
        assertContains(report, "ROUTER VERDICT: 13 of 16 calls answered")
        assertContains(report, "not available to this client: clients, radio_stats, location")
    }

    @Test
    fun aRouterAddressThatIsTheDishIsSaidSo() {
        // Asking a dish for the router's status gives a dish status: the verdict notices.
        val dishAsRouter = DishClient(FakeTransport(FakeDishSimulation()), target = CallTarget.ROUTER)
        val results = listOf(dishAsRouter.call(DishCallKind.ROUTER_STATUS), dishAsRouter.call(DishCallKind.ROUTER_CLIENTS))
        val report = DishProbe.report(emptyList(), "none", false, routerResults = results, routerEndpoint = "x")
        assertContains(report, "this address is the dish, not the router")
    }

    @Test
    fun anUnreachableRouterIsReportedAsSuch() {
        val results = DishCallKind.ROUTER_QUERIES.map { DishCallResult.failure(it, 0, 0, "-", "connect timed out") }
        val report = DishProbe.report(emptyList(), "none", false, routerResults = results, routerEndpoint = "x")
        assertContains(report, "ROUTER VERDICT: router not reachable")
        assertContains(report, "router calls: 16 UNREACHABLE")
    }

    @Test
    fun theOldReportSignatureStillWorks() {
        val r = DishProbe.report(listOf(ok(DishCallKind.STATUS, DeviceResponse(dish_get_status = DishGetStatusResponse()))), "e", false, 0L, 0L)
        assertContains(r, "starlink-nav dish probe")
        assertFalse("router:" in r)
    }

    @Test
    fun describeNeedsNoReceiptTimeButUsesItWhenGiven() {
        val sim = FakeDishSimulation(spoofEverySec = 0)
        val time = DishClient(FakeTransport(sim)).call(DishCallKind.TIME)
        val diag = DishClient(FakeTransport(sim)).call(DishCallKind.DIAGNOSTICS)
        assertFalse("minus_phone" in DishProbe.describe(time, false))
        assertFalse("age at receipt" in DishProbe.describe(diag, false))
        assertContains(DishProbe.describe(time, false, System.currentTimeMillis()), "minus_phone=")
        assertContains(DishProbe.describe(diag, false, System.currentTimeMillis()), "age at receipt")
    }
}
