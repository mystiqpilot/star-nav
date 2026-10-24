package tools.apachi.starlinknav.core.dish

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.Protocol
import okio.Buffer
import tools.apachi.starlinknav.core.dish.proto.DeviceRequest
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DeviceState
import tools.apachi.starlinknav.core.dish.proto.WifiGetStatusResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterClientTest {
    private val servers = mutableListOf<MockWebServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private fun server(h2c: Boolean = false) = MockWebServer().also {
        if (h2c) it.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        it.start()
        servers += it
    }

    private fun deadPort(): Int = MockWebServer().run { start(); val p = port; close(); p }

    private val routerStatus = DeviceResponse(
        id = 1,
        wifi_get_status = WifiGetStatusResponse(device_state = DeviceState(uptime_s = 1234), public_ipv4 = "203.0.113.7", ping_latency_ms = 33f),
    )

    private fun webReply(message: ByteArray): Buffer {
        val buf = Buffer()
        buf.write(GrpcFrames.encode(message))
        val t = "grpc-status:0\r\ngrpc-message:\r\n".encodeToByteArray()
        buf.writeByte(0x80).writeInt(t.size).write(t)
        return buf
    }

    private fun client(webPort: Int, h2cPort: Int, mode: TransportMode) =
        RouterClient.create(DishClient.defaultHttpClient(), "127.0.0.1", mode, webPort, h2cPort)

    @Test
    fun defaultsAreTheRoutersPortsNotTheDishs() {
        assertEquals(9001, RouterClient.WEB_PORT)
        assertEquals(9000, RouterClient.H2C_PORT)
        assertEquals("192.168.1.1" to 9001, RouterClient.parseAddress("192.168.1.1"))
        assertEquals("10.2.0.1" to 19001, RouterClient.parseAddress("10.2.0.1:19001"))
        assertEquals("192.168.1.1" to 9001, RouterClient.parseAddress(" 192.168.1.1 "))
        assertTrue(RouterClient.isValidAddress("192.168.1.1"))
        assertTrue(RouterClient.isValidAddress("10.0.0.1:9001"))
        assertFalse(RouterClient.isValidAddress(""))
        assertTrue(RouterClient.PRESET_ADDRESSES.all(RouterClient::isValidAddress))
        // The dish keeps its own defaults.
        assertEquals("192.168.100.1" to 9201, DishClient.parseAddress("192.168.100.1"))
    }

    @Test
    fun statusGoesOverGrpcWebFirstAndComesBackAsAWifiStatus() {
        val web = server()
        web.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/grpc-web+proto").body(webReply(DeviceResponse.ADAPTER.encode(routerStatus))).build())
        val result = client(web.port, deadPort(), TransportMode.AUTO).call(DishCallKind.ROUTER_STATUS)

        assertTrue(result.ok, result.statusText)
        assertEquals("grpc-web", result.transport)
        assertEquals(CallOutcome.OK, result.outcome)
        val status = assertNotNull(result.response?.wifi_get_status)
        assertEquals(1234L, status.device_state?.uptime_s)
        assertNotNull(result.response).let { assertEquals(null, it.dish_get_status) }

        val req = web.takeRequest()
        assertEquals("/SpaceX.API.Device.Device/Handle", req.target)
        val sent = DeviceRequest.ADAPTER.decode(GrpcFrames.decodeAll(assertNotNull(req.body).toByteArray()).single().payload)
        assertNotNull(sent.get_status)
    }

    @Test
    fun theExtraPortSurveyTellsWhatAnswersOnPort9005() {
        val web = server()
        web.enqueue(MockResponse.Builder().code(200).body(webReply(DeviceResponse.ADAPTER.encode(routerStatus))).build())
        val lines = DishProbe.surveyRouterPort(DishClient.defaultHttpClient(), "127.0.0.1", web.port)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("grpc-web: OK, wifi_get_status (a router)"), lines[0])
        assertTrue(lines[1].startsWith("router port ${web.port} grpc-h2c: "), lines[1]) // an HTTP/1 server does not speak h2c
        val none = DishProbe.surveyRouterPort(DishClient.defaultHttpClient(), "127.0.0.1", deadPort())
        assertTrue(none.all { "UNREACHABLE" in it }, none.toString())
        assertEquals(9005, DishProbe.EXTRA_ROUTER_PORT)
    }

    @Test
    fun aPositionFromTheRouterNeverSurvivesRedaction() {
        val located = DeviceResponse(
            get_location = tools.apachi.starlinknav.core.dish.proto.GetLocationResponse(
                lla = tools.apachi.starlinknav.core.dish.proto.LLAPosition(lat = 50.4501, lon = 30.5234, alt = 180.0), source = 4, sigma_m = 2.0,
            ),
        )
        val result = DishCallResult(DishCallKind.ROUTER_LOCATION, 0, 0, "t", 200, GrpcStatus.OK, null, DeviceResponse.ADAPTER.encode(located), located, null)
        assertEquals(DishCallKind.ROUTER_LOCATION.request(1).get_location?.source, PositionSource.AUTO.code)
        val record = result.toRecord()
        assertNull(DeviceResponse.ADAPTER.decode(record.response).get_location?.lla)
        assertNull(result.redacted().response?.get_location?.lla)
        assertEquals(CallOutcome.OK, result.redacted().outcome)
        assertFalse(DishCallKind.ROUTER_LOCATION in DishCallKind.RECORDED_LOCATIONS || DishCallKind.ROUTER_LOCATION in DishCallKind.LOCATIONS)
    }

    @Test
    fun fallsBackToPlainGrpcOnPort9000AndSticks() {
        val h2c = server(h2c = true)
        repeat(2) {
            h2c.enqueue(
                MockResponse.Builder().code(200)
                    .body(Buffer().write(GrpcFrames.encode(DeviceResponse.ADAPTER.encode(routerStatus))))
                    .trailers(headersOf("grpc-status", "0"))
                    .build(),
            )
        }
        val client = client(deadPort(), h2c.port, TransportMode.AUTO)
        val first = client.call(DishCallKind.ROUTER_STATUS)
        assertTrue(first.ok, first.statusText + " " + first.error)
        assertEquals("grpc-h2c", client.transportName)
        assertTrue(client.call(DishCallKind.ROUTER_STATUS).ok)
        assertEquals(2, h2c.requestCount)
    }

    @Test
    fun aRouterThatDeniesAWriteIsPermissionDeniedNotUnreachable() {
        val web = server()
        web.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/grpc-web+proto")
                .addHeader("grpc-status", "7").addHeader("grpc-message", "Permission%20denied").build(),
        )
        val result = client(web.port, deadPort(), TransportMode.WEB).call(DishCallKind.ROUTER_REBOOT)
        assertEquals(CallOutcome.PERMISSION_DENIED, result.outcome)
        assertEquals("Permission denied", result.grpcMessage)
    }

    @Test
    fun nothingListeningIsUnreachableAndSaysWhyOnBothPorts() {
        val result = client(deadPort(), deadPort(), TransportMode.AUTO).call(DishCallKind.ROUTER_STATUS)
        assertEquals(CallOutcome.UNREACHABLE, result.outcome)
        assertEquals("TRANSPORT_ERROR", result.statusText)
        assertTrue(assertNotNull(result.error).contains("then"), result.error)
    }

    @Test
    fun routerClientRefusesDishKindsAndAnswersItsOwnCommands() {
        val client = RouterClient(FakeTransport(FakeRouterSimulation()))
        kotlin.test.assertFailsWith<IllegalArgumentException> { client.call(DishCallKind.STATUS) }
        assertTrue(client.call(DishCallKind.ROUTER_STATUS).ok)
        assertTrue(client.send(RouterCommand.StartSpeedtest(10)).ok)
    }
}
