package tools.apachi.starlinknav.core.account

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import tools.apachi.starlinknav.core.dish.GrpcFrames
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishConfig
import tools.apachi.starlinknav.core.dish.proto.DishSetConfigResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The consent gate and the one-shot, wiped-afterwards session, against a local server (nothing leaves the machine). */
class AccountEnableFlowTest {
    private val servers = mutableListOf<MockWebServer>()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private val dish = "ut01000000-00000000-00ABCDEF"
    private val cookieText = "Starlink.Com.Sso=SSO123456; Starlink.Com.Access.V1=ACC123456; _ga=GA1"

    private fun okReply(): MockResponse {
        val payload = DeviceResponse.ADAPTER.encode(DeviceResponse(dish_set_config = DishSetConfigResponse(updated_dish_config = DishConfig(location_request_mode = 1))))
        val buf = Buffer().write(GrpcFrames.encode(payload))
        val t = "grpc-status:0\r\n".encodeToByteArray()
        buf.writeByte(0x80).writeInt(t.size).write(t)
        return MockResponse.Builder().code(200).body(buf).build()
    }

    /** A fake Starlink: [user] answers the account probe, [lines] the service lines, [handle] the write. */
    private fun starlink(
        user: () -> MockResponse = { MockResponse.Builder().code(200).body("""{"email":"me@example.org"}""").build() },
        lines: () -> MockResponse = {
            MockResponse.Builder().code(200).body("""{"content":{"results":[{"userTerminals":[{"userTerminalId":"01000000-00000000-00ABCDEF"}]}]}}""").build()
        },
        handle: () -> MockResponse = ::okReply,
    ): CloudEndpoints {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.target.orEmpty()
                return when {
                    path.startsWith("/auth-rp/auth/user") -> user()
                    path.startsWith("/api/webagg/") -> lines()
                    path.startsWith("/api/SpaceX.API.Device.Device/Handle") -> handle()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        s.start()
        servers += s
        return CloudEndpoints.at(s.url("/"))
    }

    private fun targets() = seen.map { it.target.orEmpty().substringBefore('?') }

    @Test
    fun noRequestIsSentBeforeTheSignInIsFinished() {
        val flow = AccountEnableFlow(starlink())
        assertEquals(AccountEnableFlow.Stage.IDLE, flow.stage)
        assertTrue(flow.begin(dish))
        assertEquals(AccountEnableFlow.Stage.CONSENT, flow.stage)
        assertTrue(flow.accept())
        assertEquals(AccountEnableFlow.Stage.SIGN_IN, flow.stage)
        assertTrue(seen.isEmpty(), "begin and accept contact nobody")
    }

    @Test
    fun theCloudCannotBeReachedWithoutTheConsent() {
        val flow = AccountEnableFlow(starlink())
        assertFailsWith<IllegalStateException> { flow.finishSignIn(cookieText) } // idle
        flow.begin(dish)
        assertFailsWith<IllegalStateException> { flow.finishSignIn(cookieText) } // consent screen still up
        flow.cancel()
        assertFalse(flow.accept(), "accept is only for the consent screen")
        assertFailsWith<IllegalStateException> { flow.finishSignIn(cookieText) } // cancelled
        assertTrue(seen.isEmpty(), "none of that reached the server")
        assertEquals(AccountEnableFlow.Stage.IDLE, flow.stage)
    }

    @Test
    fun anIdThatIsNotATerminalIdNeverGetsAConsentScreen() {
        val flow = AccountEnableFlow(starlink())
        assertFalse(flow.begin("not-a-terminal"))
        assertFalse(flow.begin(""))
        assertEquals(AccountEnableFlow.Stage.IDLE, flow.stage)
    }

    @Test
    fun theHappyPathChecksTheSessionThenTheDishThenWritesOnceAndWipes() {
        val flow = AccountEnableFlow(starlink())
        flow.begin(dish)
        flow.accept()
        val result = flow.finishSignIn(cookieText)

        assertEquals(CloudOutcome.Ok(1), result.outcome)
        assertEquals("me@example.org", result.email)
        assertEquals(Ownership.MATCH, result.ownership)
        assertEquals(listOf("/auth-rp/auth/user", "/api/webagg/v2/accounts/service-lines", "/api/SpaceX.API.Device.Device/Handle"), targets())
        assertTrue(seen.all { "Starlink.Com.Sso=SSO123456" in it.headers["Cookie"].orEmpty() })
        assertEquals(AccountEnableFlow.Stage.DONE, flow.stage)
        // One shot: the session is gone, a second finish is refused, and nothing more was sent.
        assertFailsWith<IllegalStateException> { flow.finishSignIn(cookieText) }
        assertEquals(3, seen.size)
        assertFalse(result.toString().contains("me@example.org"))
    }

    @Test
    fun aDishThatIsNotOnTheAccountWarnsButStillWrites() {
        val flow = AccountEnableFlow(
            starlink(lines = { MockResponse.Builder().code(200).body("""{"content":{"results":[{"userTerminals":[{"userTerminalId":"01000000-00000000-00999999"}]}]}}""").build() }),
        )
        flow.begin(dish)
        flow.accept()
        val result = flow.finishSignIn(cookieText)
        assertEquals(Ownership.MISMATCH, result.ownership)
        assertIs<CloudOutcome.Ok>(result.outcome)
        assertEquals("/api/SpaceX.API.Device.Device/Handle", targets().last())
    }

    @Test
    fun aListThatCannotBeReadIsUnknownNotAStop() {
        val flow = AccountEnableFlow(starlink(lines = { MockResponse.Builder().code(500).build() }))
        flow.begin(dish)
        flow.accept()
        val result = flow.finishSignIn(cookieText)
        assertEquals(Ownership.UNKNOWN, result.ownership)
        assertIs<CloudOutcome.Ok>(result.outcome)
    }

    @Test
    fun aDeadSessionStopsBeforeTheWrite() {
        val flow = AccountEnableFlow(starlink(user = { MockResponse.Builder().code(401).build() }))
        flow.begin(dish)
        flow.accept()
        val result = flow.finishSignIn(cookieText)
        assertIs<CloudOutcome.SessionExpired>(result.outcome)
        assertFalse(targets().any { it.endsWith("/Handle") }, "no write with a session Starlink does not accept")
        assertEquals(AccountEnableFlow.Stage.DONE, flow.stage)
    }

    @Test
    fun noSignInCookieMeansNoRequestAtAll() {
        val flow = AccountEnableFlow(starlink())
        flow.begin(dish)
        flow.accept()
        val result = flow.finishSignIn("_ga=GA1; other=1")
        assertIs<CloudOutcome.SessionExpired>(result.outcome)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun theWriteOutcomeComesBackWhateverItIs() {
        val flow = AccountEnableFlow(starlink(handle = { MockResponse.Builder().code(200).addHeader("grpc-status", "5").addHeader("grpc-message", "DEVICE_NOT_CONNECTED").build() }))
        flow.begin(dish)
        flow.accept()
        assertIs<CloudOutcome.DeviceOffline>(flow.finishSignIn(cookieText).outcome)
    }

    @Test
    fun cancelOnTheSignInPageWipesAndAnotherRunCanStart() {
        val flow = AccountEnableFlow(starlink())
        flow.begin(dish)
        flow.accept()
        flow.cancel()
        assertEquals(AccountEnableFlow.Stage.IDLE, flow.stage)
        assertFailsWith<IllegalStateException> { flow.finishSignIn(cookieText) }
        // A fresh run needs the consent again.
        assertTrue(flow.begin(dish))
        assertFailsWith<IllegalStateException> { flow.finishSignIn(cookieText) }
        assertTrue(flow.accept())
        assertIs<CloudOutcome.Ok>(flow.finishSignIn(cookieText).outcome)
        // And after DONE the next one starts from the consent as well.
        assertTrue(flow.begin(dish))
        assertEquals(AccountEnableFlow.Stage.CONSENT, flow.stage)
        assertFailsWith<IllegalStateException> { flow.finishSignIn(cookieText) }
    }
}
