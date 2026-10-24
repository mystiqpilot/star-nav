package tools.apachi.starlinknav.core.account

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.Buffer
import tools.apachi.starlinknav.core.dish.GrpcFrames
import tools.apachi.starlinknav.core.dish.proto.DeviceRequest
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishConfig
import tools.apachi.starlinknav.core.dish.proto.DishSetConfigResponse
import tools.apachi.starlinknav.core.dish.proto.RpcStatus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cloud write against a local server only: no request in these tests leaves the machine. The session cookies and the
 * token below are made-up strings; the tests check they appear in no outcome or text.
 */
class StarlinkCloudTest {
    private val servers = mutableListOf<MockWebServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private fun server() = MockWebServer().also {
        it.start()
        servers += it
    }

    private val sso = "SSO-COOKIE-VALUE-0123456789"
    private val access = "ACCESS-COOKIE-VALUE-ABCDEFGH"
    private val token = "ACCESS-TOKEN-VALUE-ZYXWVUTS"
    private val dish = "ut01000000-00000000-00ABCDEF"

    private fun jar(): SessionCookies = SessionCookies().also {
        it.importCookieHeader("Starlink.Com.Sso=$sso; Starlink.Com.Access.V1=$access", domain = "localhost", secure = false)
    }

    private fun cloud(s: MockWebServer, jar: SessionCookies = jar()) = StarlinkCloud(jar, CloudEndpoints.at(s.url("/")))

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun grpcWeb(message: DeviceResponse?, trailers: String = "grpc-status:0\r\ngrpc-message:\r\n"): MockResponse {
        val buf = Buffer()
        if (message != null) buf.write(GrpcFrames.encode(DeviceResponse.ADAPTER.encode(message)))
        val t = trailers.encodeToByteArray()
        buf.writeByte(0x80).writeInt(t.size).write(t)
        return MockResponse.Builder().code(200).addHeader("Content-Type", "application/grpc-web+proto").body(buf).build()
    }

    private fun trailersOnly(status: Int, message: String) = MockResponse.Builder().code(200)
        .addHeader("Content-Type", "application/grpc-web+proto")
        .addHeader("grpc-status", status.toString())
        .addHeader("grpc-message", message)
        .build()

    private fun applied(mode: Int?) = DeviceResponse(
        dish_set_config = DishSetConfigResponse(updated_dish_config = mode?.let { DishConfig(location_request_mode = it) }),
    )

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    // ---- the request

    @Test
    fun theFrameIsByteForByteTheOneInTheDesignNote() {
        // The request frame: 00 | 00000028 | 6a 1c <id> | d2 7d 07 | 0a 05 | 10 01 | 88 7d 01
        val expected = hex("00000000286a1c757430313030303030302d30303030303030302d3030414243444546d27d070a051001887d01")
        assertContentEquals(expected, StarlinkCloud.localLocationFrame(dish))
        // And what it holds: target id, the one config field and its apply flag, nothing else.
        val sent = DeviceRequest.ADAPTER.decode(GrpcFrames.decodeAll(expected).single().payload)
        assertEquals(dish, sent.target_id)
        val config = assertNotNull(sent.dish_set_config?.dish_config)
        assertEquals(1, config.location_request_mode)
        assertTrue(config.apply_location_request_mode)
        assertEquals(0L, sent.id)
    }

    @Test
    fun successSendsTheExactFrameWithCookiesAndParsesOk() {
        val s = server()
        s.enqueue(grpcWeb(applied(1)))
        val outcome = cloud(s).setLocalLocation(dish)

        val ok = assertIs<CloudOutcome.Ok>(outcome)
        assertEquals(1, ok.updatedMode)
        assertTrue(ok.confirmedLocal)

        val req = s.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/SpaceX.API.Device.Device/Handle", req.target)
        assertEquals("application/grpc-web+proto", req.headers["Content-Type"])
        assertEquals("1", req.headers["X-Grpc-Web"])
        assertEquals(s.url("/").toString().trimEnd('/'), req.headers["Origin"])
        assertEquals("StarNav (own-dish interop)", req.headers["User-Agent"])
        val cookie = req.headers["Cookie"].orEmpty()
        assertTrue("Starlink.Com.Sso=$sso" in cookie && "Starlink.Com.Access.V1=$access" in cookie, "the jar's cookies go out")
        assertNull(req.headers["Authorization"], "cookies alone first")
        assertContentEquals(StarlinkCloud.localLocationFrame(dish), assertNotNull(req.body).toByteArray())
        assertEquals(1, s.requestCount)
    }

    @Test
    fun acceptedWithoutAModeIsOkButNotConfirmed() {
        val s = server()
        s.enqueue(grpcWeb(applied(null)))
        val ok = assertIs<CloudOutcome.Ok>(cloud(s).setLocalLocation(dish))
        assertNull(ok.updatedMode)
        assertFalse(ok.confirmedLocal)
    }

    @Test
    fun aTargetThatIsNotAnUtIdSendsNothing() {
        val s = server()
        val c = cloud(s)
        for (bad in listOf("", "01000000-00000000-00ABCDEF", "ut", "ut01000000-00000000-00ABCDEF\r\nX: y", "ut" + "1".repeat(80))) {
            assertEquals(CloudOutcome.InvalidTarget, c.setLocalLocation(bad), "'$bad'")
        }
        assertEquals(0, s.requestCount)
    }

    // ---- the answers

    @Test
    fun aTrailersOnlyPermissionDeniedIsRefused() {
        val s = server()
        s.enqueue(trailersOnly(7, "Permission%20denied"))
        val refused = assertIs<CloudOutcome.Refused>(cloud(s).setLocalLocation(dish))
        assertEquals(7, refused.grpcStatus)
        assertEquals("Permission denied", refused.message)
        assertEquals(1, s.requestCount, "a refusal is not retried and starts no token refresh")
    }

    @Test
    fun aTrailerFrameWithAStatusIsReadToo() {
        val s = server()
        s.enqueue(grpcWeb(null, "grpc-status:9\r\ngrpc-message:precondition\r\n"))
        val refused = assertIs<CloudOutcome.Refused>(cloud(s).setLocalLocation(dish))
        assertEquals(9, refused.grpcStatus)
        assertEquals("precondition", refused.message)
    }

    @Test
    fun targetIdRequiredIsARefusalWithItsText() {
        val s = server()
        s.enqueue(trailersOnly(3, "TARGETID_IS_REQUIRED"))
        val refused = assertIs<CloudOutcome.Refused>(cloud(s).setLocalLocation(dish))
        assertEquals(3, refused.grpcStatus)
        assertEquals("TARGETID_IS_REQUIRED", refused.message)
    }

    @Test
    fun deviceNotConnectedIsTheDishBeingOffline() {
        val s = server()
        s.enqueue(trailersOnly(5, "DEVICE_NOT_CONNECTED"))
        s.enqueue(grpcWeb(null, "grpc-status:14\r\ngrpc-message:rpc error: DEVICE_CONNECTION_NOT_FOUND for ut1\r\n"))
        s.enqueue(grpcWeb(DeviceResponse(status = RpcStatus(code = 5, message = "DEVICE_NOT_CONNECTED"))))
        val c = cloud(s)
        assertIs<CloudOutcome.DeviceOffline>(c.setLocalLocation(dish))
        assertIs<CloudOutcome.DeviceOffline>(c.setLocalLocation(dish))
        assertIs<CloudOutcome.DeviceOffline>(c.setLocalLocation(dish), "also when the payload's own status says it")
    }

    @Test
    fun anErrorTextInTheSetConfigPayloadIsARefusal() {
        val s = server()
        s.enqueue(grpcWeb(DeviceResponse(dish_set_config = DishSetConfigResponse(error = "not allowed on this plan"))))
        val refused = assertIs<CloudOutcome.Refused>(cloud(s).setLocalLocation(dish))
        assertNull(refused.grpcStatus)
        assertEquals("not allowed on this plan", refused.message)
    }

    @Test
    fun anUnexpectedHttpStatusOrABodyThatIsNotGrpcIsOther() {
        val s = server()
        s.enqueue(MockResponse.Builder().code(503).body("<html>unavailable</html>").build())
        s.enqueue(MockResponse.Builder().code(200).body("<html>not grpc</html>").build())
        s.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/grpc-web+proto").build())
        val c = cloud(s)
        assertEquals("HTTP 503", assertIs<CloudOutcome.Other>(c.setLocalLocation(dish)).detail)
        assertIs<CloudOutcome.Other>(c.setLocalLocation(dish))
        assertIs<CloudOutcome.Other>(c.setLocalLocation(dish), "200 with no gRPC status at all")
    }

    // ---- an expired session: one token refresh, one retry

    @Test
    fun grpcUnauthenticatedRefreshesTheTokenOnceAndRetriesWithTheBearer() {
        val s = server()
        s.enqueue(trailersOnly(16, "unauthenticated"))
        s.enqueue(json(200, """{"accessToken":"$token","expiresIn":900,"tokenType":"Bearer"}"""))
        s.enqueue(grpcWeb(applied(1)))
        val jar = jar()
        val outcome = cloud(s, jar).setLocalLocation(dish)

        assertEquals(CloudOutcome.Ok(1), outcome)
        val first = s.takeRequest()
        val refresh = s.takeRequest()
        val retry = s.takeRequest()
        assertEquals("/api/SpaceX.API.Device.Device/Handle", first.target)
        assertNull(first.headers["Authorization"])
        assertEquals("GET", refresh.method)
        assertEquals("/api/auth/auth/refresh-token", refresh.target)
        assertEquals("/api/SpaceX.API.Device.Device/Handle", retry.target)
        assertEquals("Bearer $token", retry.headers["Authorization"])
        assertTrue("Starlink.Com.Access.V1=$token" in retry.headers["Cookie"].orEmpty(), "the token also replaces the access cookie")
        assertContentEquals(StarlinkCloud.localLocationFrame(dish), assertNotNull(retry.body).toByteArray())
        assertEquals(3, s.requestCount)
    }

    @Test
    fun aCookieOnly401IsTheSameStory() {
        val s = server()
        s.enqueue(MockResponse.Builder().code(401).build())
        s.enqueue(json(200, """{"accessToken":"$token"}"""))
        s.enqueue(grpcWeb(applied(1)))
        assertEquals(CloudOutcome.Ok(1), cloud(s).setLocalLocation(dish))
        assertEquals(3, s.requestCount)
    }

    @Test
    fun aRefusedRefreshEndsAsSessionExpiredAndIsNotAskedAgain() {
        val s = server()
        s.enqueue(MockResponse.Builder().code(401).build())
        s.enqueue(MockResponse.Builder().code(401).build()) // the refresh
        s.enqueue(MockResponse.Builder().code(403).build()) // a second try of the write, no refresh this time
        val c = cloud(s)
        assertEquals(CloudOutcome.SessionExpired("HTTP 401"), c.setLocalLocation(dish))
        assertEquals(2, s.requestCount, "the write, then one refresh")
        assertEquals(CloudOutcome.SessionExpired("HTTP 403"), c.setLocalLocation(dish))
        assertEquals(3, s.requestCount, "no second refresh")
    }

    @Test
    fun theRetryIsRefusedToo() {
        val s = server()
        s.enqueue(trailersOnly(16, "unauthenticated"))
        s.enqueue(json(200, """{"accessToken":"$token"}"""))
        s.enqueue(trailersOnly(16, "unauthenticated"))
        assertIs<CloudOutcome.SessionExpired>(cloud(s).setLocalLocation(dish))
        assertEquals(3, s.requestCount, "write, refresh, one retry: no more")
    }

    @Test
    fun aRedirectToTheLoginPageIsNotFollowed() {
        val s = server()
        s.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/api/auth-rp/auth/login").build())
        s.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/api/auth-rp/auth/login").build())
        assertEquals(CloudOutcome.SessionExpired("HTTP 302"), cloud(s).setLocalLocation(dish))
        assertEquals(2, s.requestCount, "the write and the refresh; the login page was never fetched")
        assertEquals("/api/SpaceX.API.Device.Device/Handle", s.takeRequest().target)
        assertEquals("/api/auth/auth/refresh-token", s.takeRequest().target)
    }

    // ---- a connection that ends

    @Test
    fun aConnectionThatEndsAfterTheRequestIsNoAnswerAndIsNotRepeated() {
        val s = server()
        s.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        s.enqueue(grpcWeb(applied(1)))
        val outcome = cloud(s).setLocalLocation(dish)
        assertIs<CloudOutcome.NoAnswer>(outcome)
        assertEquals(1, s.requestCount, "a write that may have arrived is never sent twice")
    }

    @Test
    fun noConnectionAtAllIsOther() {
        val dead = MockWebServer().run { start(); val url = url("/"); close(); url }
        val outcome = StarlinkCloud(jar(), CloudEndpoints.at(dead)).setLocalLocation(dish)
        assertIs<CloudOutcome.Other>(outcome)
    }

    // ---- the account probe and the list of dishes

    @Test
    fun checkSessionLiveGivesTheEmailInMemoryOnly() {
        val s = server()
        s.enqueue(json(200, """{"content":{"firstName":"A","email":"someone@example.org"}}"""))
        val live = assertIs<SessionCheck.Live>(cloud(s).checkSession())
        assertEquals("someone@example.org", live.account.email)
        assertEquals("/auth-rp/auth/user", s.takeRequest().target)
        assertFalse("someone" in live.toString() + live.account.toString())
    }

    @Test
    fun checkSessionDeadOn401And403AfterOneRefresh() {
        val s = server()
        s.enqueue(MockResponse.Builder().code(403).build())
        s.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(SessionCheck.Dead, cloud(s).checkSession())
        assertEquals(2, s.requestCount)
    }

    @Test
    fun checkSessionRefreshesOnceThenSendsTheBearerOnTheNextCalls() {
        val s = server()
        s.enqueue(MockResponse.Builder().code(401).build())
        s.enqueue(json(200, """{"accessToken":"$token"}"""))
        s.enqueue(json(200, """{"email":"someone@example.org"}"""))
        s.enqueue(grpcWeb(applied(1)))
        val c = cloud(s)
        assertIs<SessionCheck.Live>(c.checkSession())
        assertEquals(CloudOutcome.Ok(1), c.setLocalLocation(dish))
        s.takeRequest(); s.takeRequest(); s.takeRequest()
        assertEquals("Bearer $token", s.takeRequest().headers["Authorization"])
    }

    @Test
    fun listTerminalsReadsTheServiceLinesAndComparesIdsLoosely() {
        val s = server()
        s.enqueue(
            json(
                200,
                """{"content":{"results":[{"userTerminals":[{"userTerminalId":"01000000-00000000-00abcdef","serialNumber":"KIT123"},
                   {"userTerminalId":"01000000-00000000-00111111"}]},{"userTerminals":[]}]}}""",
            ),
        )
        val listed = assertIs<TerminalsResult.Listed>(cloud(s).listTerminals())
        assertEquals(2, listed.terminals.size)
        assertEquals("KIT123", listed.terminals[0].serialNumber)
        assertTrue(listed.terminals.any { TerminalIds.same(it.id, dish) }, "ut prefix and case do not matter")
        assertFalse(listed.terminals.any { TerminalIds.same(it.id, "ut01000000-00000000-00999999") })
        val req = s.takeRequest()
        assertTrue(req.target.orEmpty().startsWith("/api/webagg/v2/accounts/service-lines?limit=100&page=0"), req.target)
        assertFalse("KIT123" in listed.toString() + listed.terminals[0].toString())
    }

    @Test
    fun listTerminalsIsBestEffort() {
        val s = server()
        s.enqueue(json(200, """{"unexpected":true}"""))
        s.enqueue(MockResponse.Builder().code(500).build())
        s.enqueue(MockResponse.Builder().code(403).build())
        s.enqueue(MockResponse.Builder().code(403).build())
        val c = cloud(s)
        assertIs<TerminalsResult.Unavailable>(c.listTerminals())
        assertIs<TerminalsResult.Unavailable>(c.listTerminals())
        assertEquals(TerminalsResult.SessionExpired, c.listTerminals())
    }

    // ---- secrets

    @Test
    fun noCookieOrTokenEverShowsUpInAnOutcomeOrToString() {
        val s = server()
        // A server that echoes secrets into its own words, in every place a text can come from.
        s.enqueue(trailersOnly(7, "bad session $sso and $access, cookie Starlink.Com.Sso=other; Authorization: Bearer abcdef"))
        s.enqueue(trailersOnly(16, "unauthenticated"))
        s.enqueue(json(200, """{"accessToken":"$token"}"""))
        s.enqueue(trailersOnly(7, "again $token and Starlink.Com.Access.V1=$access"))
        s.enqueue(MockResponse.Builder().code(500).body("Set-Cookie: Starlink.Com.Sso=$sso").build())
        s.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        val jar = jar()
        val c = cloud(s, jar)
        val seen = mutableListOf<String>()
        seen += c.setLocalLocation(dish).toString() // refused, with the echo
        seen += c.setLocalLocation(dish).toString() // 16 -> refresh -> retry refused with the echo
        seen += c.setLocalLocation(dish).toString() // HTTP 500
        seen += c.setLocalLocation(dish).toString() // connection closed
        seen += c.toString() + jar.toString()
        seen += AccountResult(CloudOutcome.Ok(1), "someone@example.org", Ownership.MATCH).toString()
        val all = seen.joinToString("\n")
        for (secret in listOf(sso, access, token, "someone@example.org")) assertFalse(secret in all, "'$secret' leaked into: $all")
        assertTrue("[cookie]" in all && "[removed]" in all, "the echo was scrubbed, not just absent: $all")
    }

    @Test
    fun wipeDropsTheTokenAndTheJarIsEmptyAfterClear() {
        val s = server()
        s.enqueue(trailersOnly(16, "x"))
        s.enqueue(json(200, """{"accessToken":"$token"}"""))
        s.enqueue(grpcWeb(applied(1)))
        s.enqueue(grpcWeb(applied(1)))
        val jar = jar()
        val c = cloud(s, jar)
        c.setLocalLocation(dish)
        c.wipe()
        c.setLocalLocation(dish)
        s.takeRequest(); s.takeRequest(); s.takeRequest()
        assertNull(s.takeRequest().headers["Authorization"], "no bearer after wipe()")
        jar.clear()
        assertEquals(0, jar.size)
    }
}
