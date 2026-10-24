package tools.apachi.starlinknav.core.dish

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.Protocol
import okio.Buffer
import tools.apachi.starlinknav.core.dish.proto.DeviceRequest
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.GetLocationResponse
import tools.apachi.starlinknav.core.dish.proto.LLAPosition
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportTest {
    private val servers = mutableListOf<MockWebServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private fun server(h2c: Boolean = false) = MockWebServer().also {
        if (h2c) it.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        it.start()
        servers += it
    }

    private fun deadPort(): Int = MockWebServer().run { start(); val p = port; close(); p }

    private val starlinkFix = DeviceResponse(
        id = 7,
        get_location = GetLocationResponse(
            lla = LLAPosition(lat = 50.45, lon = 30.52, alt = 180.0),
            source = PositionSource.STARLINK.code,
            sigma_m = 0.8,
            horizontal_speed_mps = 12.5,
        ),
    )

    private fun webFrames(message: ByteArray?, trailers: String): Buffer {
        val buf = Buffer()
        if (message != null) buf.write(GrpcFrames.encode(message))
        val t = trailers.encodeToByteArray()
        buf.writeByte(0x80).writeInt(t.size).write(t)
        return buf
    }

    private fun client(webPort: Int, h2cPort: Int, mode: TransportMode) =
        DishClient.create(DishClient.defaultHttpClient(), "127.0.0.1", mode, webPort, h2cPort)

    @Test
    fun grpcWebSuccessDecodesFixAndSendsFramedRequest() {
        val web = server()
        web.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/grpc-web+proto")
                .body(webFrames(DeviceResponse.ADAPTER.encode(starlinkFix), "grpc-status:0\r\ngrpc-message:\r\n"))
                .build(),
        )
        val result = client(web.port, deadPort(), TransportMode.WEB).call(DishCallKind.LOCATION_STARLINK)

        assertTrue(result.ok, result.statusText)
        assertEquals("grpc-web", result.transport)
        val fix = assertNotNull(result.response?.fix())
        assertEquals(50.45, fix.lat)
        assertEquals("STARLINK", fix.sourceName)
        assertFalse(fix.looksLikePlaceholder)

        val req = web.takeRequest()
        assertEquals("/SpaceX.API.Device.Device/Handle", req.target)
        assertEquals("application/grpc-web+proto", req.headers["Content-Type"])
        val frames = GrpcFrames.decodeAll(assertNotNull(req.body).toByteArray())
        val sent = DeviceRequest.ADAPTER.decode(frames.single().payload)
        assertEquals(PositionSource.STARLINK.code, sent.get_location?.source)
    }

    @Test
    fun grpcWebTrailersOnlyErrorIsReportedNotThrown() {
        val web = server()
        web.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/grpc-web+proto")
                .addHeader("grpc-status", "12")
                .addHeader("grpc-message", "GetLocation%20is%20disabled%3A%20plan")
                .build(),
        )
        val result = client(web.port, deadPort(), TransportMode.WEB).call(DishCallKind.LOCATION_GPS)
        assertFalse(result.ok)
        assertEquals("UNIMPLEMENTED", result.statusText)
        assertEquals("GetLocation is disabled: plan", result.grpcMessage)
        assertNull(result.response)
    }

    @Test
    fun grpcWebStatusInTrailerFrame() {
        val web = server()
        web.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/grpc-web+proto")
                .body(webFrames(null, "grpc-status: 7\r\ngrpc-message: denied\r\n"))
                .build(),
        )
        val result = client(web.port, deadPort(), TransportMode.WEB).call(DishCallKind.LOCATION_AUTO)
        assertEquals("PERMISSION_DENIED", result.statusText)
        assertEquals("denied", result.grpcMessage)
    }

    @Test
    fun httpErrorWithoutGrpcStatus() {
        val web = server()
        web.enqueue(MockResponse.Builder().code(404).body("nope").build())
        val result = client(web.port, deadPort(), TransportMode.WEB).call(DishCallKind.STATUS)
        assertEquals("HTTP_404", result.statusText)
    }

    @Test
    fun unreachableDishIsTransportError() {
        val result = client(deadPort(), deadPort(), TransportMode.AUTO).call(DishCallKind.STATUS)
        assertEquals("TRANSPORT_ERROR", result.statusText)
        assertEquals(0, result.httpStatus)
        assertNotNull(result.error)
    }

    @Test
    fun h2cSuccessReadsStatusFromTrailers() {
        val h2c = server(h2c = true)
        h2c.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/grpc")
                .body(Buffer().write(GrpcFrames.encode(DeviceResponse.ADAPTER.encode(starlinkFix))))
                .trailers(headersOf("grpc-status", "0"))
                .build(),
        )
        val result = client(deadPort(), h2c.port, TransportMode.H2C).call(DishCallKind.LOCATION_STARLINK)
        assertTrue(result.ok, result.statusText + " " + result.error)
        assertEquals("grpc-h2c", result.transport)
        assertEquals(12.5, result.response?.fix()?.horizontalSpeedMps)
        assertEquals("trailers", h2c.takeRequest().headers["TE"])
    }

    @Test
    fun autoFallsBackToH2cAndSticks() {
        val h2c = server(h2c = true)
        repeat(2) {
            h2c.enqueue(
                MockResponse.Builder().code(200)
                    .body(Buffer().write(GrpcFrames.encode(DeviceResponse.ADAPTER.encode(starlinkFix))))
                    .trailers(headersOf("grpc-status", "0"))
                    .build(),
            )
        }
        val client = client(deadPort(), h2c.port, TransportMode.AUTO)
        assertTrue(client.call(DishCallKind.LOCATION_STARLINK).ok)
        assertEquals("grpc-h2c", client.transportName)
        assertTrue(client.call(DishCallKind.LOCATION_STARLINK).ok)
        assertEquals(2, h2c.requestCount)
    }

    private fun okReply(response: DeviceResponse) = MockResponse.Builder().code(200)
        .body(Buffer().write(GrpcFrames.encode(DeviceResponse.ADAPTER.encode(response))))
        .trailers(headersOf("grpc-status", "0"))
        .build()

    /** A gRPC-Web answer cut short after the request went out: the frame header is incomplete. */
    private fun truncatedReply() = MockResponse.Builder().code(200).body(Buffer().writeByte(0).writeByte(0)).build()

    @Test
    fun aCommandIsNotSentAgainWhenTheFirstEndpointMayHaveGotIt() {
        val web = server()
        val h2c = server(h2c = true)
        web.enqueue(truncatedReply())
        h2c.enqueue(okReply(DeviceResponse(id = 1)))
        val client = client(web.port, h2c.port, TransportMode.AUTO)
        val result = client.call(DishCallKind.REBOOT)
        assertEquals(1, web.requestCount)
        assertEquals(0, h2c.requestCount, "the reboot was sent to the second port as well")
        assertFalse(result.ok)
        assertTrue(result.sentNoAnswer)
        assertEquals(CallOutcome.SENT_NO_ANSWER, result.outcome)
        assertTrue(DishCallResult.SENT_NO_ANSWER_NOTE in result.toRecord().error)
        assertTrue(DishCallResult.fromRecord(result.toRecord())!!.sentNoAnswer)
    }

    @Test
    fun aReadStillFallsBackAfterAnyTransportFailure() {
        val web = server()
        val h2c = server(h2c = true)
        web.enqueue(truncatedReply())
        h2c.enqueue(okReply(DeviceResponse(id = 1)))
        val client = client(web.port, h2c.port, TransportMode.AUTO)
        assertTrue(client.call(DishCallKind.TIME).ok)
        assertEquals(1, h2c.requestCount)
    }

    @Test
    fun aCommandFallsBackWhenNothingWasConnectedTo() {
        val h2c = server(h2c = true)
        h2c.enqueue(okReply(DeviceResponse(id = 1)))
        val client = client(deadPort(), h2c.port, TransportMode.AUTO)
        val result = client.call(DishCallKind.REBOOT)
        assertTrue(result.ok, result.error)
        assertEquals(1, h2c.requestCount)
        // Neither port there: nothing was sent, so it is plain unreachable, not "sent, no answer".
        val none = client(deadPort(), deadPort(), TransportMode.AUTO).call(DishCallKind.REBOOT)
        assertFalse(none.sentNoAnswer)
        assertEquals(CallOutcome.UNREACHABLE, none.outcome)
    }

    @Test
    fun onlyFailuresBeforeAConnectionCountAsNeverReached() {
        assertTrue(GrpcErrors.neverReachedDevice(java.net.ConnectException("Failed to connect to /192.168.1.1:9001")))
        assertTrue(GrpcErrors.neverReachedDevice(java.net.NoRouteToHostException("no route")))
        assertTrue(GrpcErrors.neverReachedDevice(java.net.UnknownHostException("x")))
        assertTrue(GrpcErrors.neverReachedDevice(java.net.SocketTimeoutException("Connect timed out")))
        assertTrue(GrpcErrors.neverReachedDevice(java.io.IOException("outer", java.net.ConnectException("refused"))))
        assertTrue(GrpcErrors.neverReachedDevice(IllegalArgumentException("bad address"))) // nothing was sent
        assertFalse(GrpcErrors.neverReachedDevice(java.net.SocketTimeoutException("timeout")))
        assertFalse(GrpcErrors.neverReachedDevice(java.net.SocketTimeoutException("Read timed out")))
        assertFalse(GrpcErrors.neverReachedDevice(java.net.SocketException("Connection reset")))
        assertFalse(GrpcErrors.neverReachedDevice(java.io.EOFException("\\n not found: limit=0")))
        assertFalse(GrpcErrors.neverReachedDevice(GrpcProtocolException("truncated frame header")))
    }

    @Test
    fun fallbackTransportPassesTheRepeatFlagOn() {
        class Fake(override val name: String, val failure: java.io.IOException?) : GrpcTransport {
            override val endpoint = name
            val calls = mutableListOf<Boolean>()
            override fun unary(path: String, request: ByteArray, safeToRepeat: Boolean): GrpcReply {
                calls += safeToRepeat
                failure?.let { throw it }
                return GrpcReply(name, 200, 0, null, null)
            }
        }
        val dropped = Fake("a", java.net.SocketException("Connection reset"))
        val other = Fake("b", null)
        val fallback = FallbackTransport(dropped, other)
        assertFailsWith<java.io.IOException> { fallback.unary("/x", ByteArray(0), safeToRepeat = false) }
        assertEquals(emptyList(), other.calls)
        assertEquals("b", fallback.unary("/x", ByteArray(0), safeToRepeat = true).transport)
        assertEquals(listOf(true), other.calls)
    }

    @Test
    fun percentDecodingKeepsPlusAndUtf8() {
        assertEquals("a+b c", GrpcFrames.percentDecode("a+b%20c"))
        assertEquals("ü!", GrpcFrames.percentDecode("%C3%BC!"))
        assertEquals("100%", GrpcFrames.percentDecode("100%"))
        assertEquals("%zz", GrpcFrames.percentDecode("%zz"))
    }

    @Test
    fun onlyImpossibleCoordinatesMeanNoFix() {
        // A real Mini answers GPS with a nominal 15 m and Starlink-only with ~100 m: both are fixes.
        val nominal = DeviceResponse(get_location = GetLocationResponse(lla = LLAPosition(48.5, 35.1, 110.8), sigma_m = 15.0))
        assertFalse(assertNotNull(nominal.fix()).looksLikePlaceholder)
        val zero = DeviceResponse(get_location = GetLocationResponse(lla = LLAPosition(0.0, 0.0, 0.0), sigma_m = 15.0))
        assertTrue(assertNotNull(zero.fix()).looksLikePlaceholder)
    }
}
