package tools.apachi.starlinknav.core.nav

import tools.apachi.starlinknav.core.dish.CallOutcome
import tools.apachi.starlinknav.core.dish.DiagnosticsFix
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.FakeDishSimulation
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.core.dish.GrpcStatus
import tools.apachi.starlinknav.core.dish.PositionSource
import tools.apachi.starlinknav.core.dish.diagnosticsFix
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishConfig
import tools.apachi.starlinknav.core.dish.proto.DishGetDiagnosticsResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three refusal states of `get_location` (policy / unimplemented / not enabled), the tracker that follows them from
 * the poller's calls, and the fallback chain that serves the diagnostics' position while the dish refuses.
 */
class LocationRefusalTest {
    private val sec = 1_000_000_000L
    private val local = DishConfig(location_request_mode = DishCallKind.LOCATION_MODE_LOCAL)
    private val none = DishConfig(location_request_mode = 0)

    private fun refused(code: Int, message: String?, kind: DishCallKind = DishCallKind.LOCATION_AUTO) =
        DishCallResult(kind, 0, 1_000_000, "grpc-web", 200, code, message, null, null, null)

    private fun status(config: DishConfig?) = DishCallResult(
        DishCallKind.STATUS, 0, 1_000_000, "grpc-web", 200, GrpcStatus.OK, null, ByteArray(0),
        DeviceResponse(dish_get_status = DishGetStatusResponse(config = config)), null,
    )

    private fun diagnostics(enabled: Boolean) = DishCallResult(
        DishCallKind.DIAGNOSTICS, 0, 1_000_000, "grpc-web", 200, GrpcStatus.OK, null, ByteArray(0),
        DeviceResponse(dish_get_diagnostics = DishGetDiagnosticsResponse(location = DishGetDiagnosticsResponse.Location(enabled = enabled))), null,
    )

    private val policy = "Failed to get location: Disabled due to policy"
    private val policy2026 = "GetLocation requests disabled due to policy"
    private val disablement = "GetLocation requests disabled due to UT disablement"
    private val notEnabled = "GetLocation requests are not enabled on this device"

    // ---- classification

    @Test
    fun theDishesOwnWordsWinOverTheSetting() {
        for (words in listOf(policy, policy2026)) {
            for (config in listOf(local, none, null)) {
                assertEquals(LocationRefusal.POLICY_DENIED, LocationRefusal.classify(refused(GrpcStatus.PERMISSION_DENIED, words), config), "$words / $config")
            }
        }
        assertEquals(LocationRefusal.UNIMPLEMENTED, LocationRefusal.classify(refused(GrpcStatus.UNIMPLEMENTED, "Unimplemented: *device.Request_GetLocation"), none))
        assertEquals(LocationRefusal.UNIMPLEMENTED, LocationRefusal.classify(refused(GrpcStatus.UNIMPLEMENTED, "Unimplemented: *device.Request_GetLocation"), local))
        assertEquals(LocationRefusal.UNIMPLEMENTED, LocationRefusal.classify(refused(2, "Failed to get location: Requested position source is not supported: 6"), local))
    }

    @Test
    fun aPlainPermissionDeniedIsNotEnabledWhileTheSettingReadsOffAndPolicyWhileItReadsOn() {
        val plain = refused(GrpcStatus.PERMISSION_DENIED, notEnabled)
        assertEquals(LocationRefusal.NOT_ENABLED, LocationRefusal.classify(plain, none))
        assertEquals(LocationRefusal.NOT_ENABLED, LocationRefusal.classify(plain, null, getConfig = none))
        // nothing readable: a refusal means the setting is probably off (LocalLocation's inference)
        assertEquals(LocationRefusal.NOT_ENABLED, LocationRefusal.classify(plain, null))
        assertEquals(LocationRefusal.NOT_ENABLED, LocationRefusal.classify(plain, null, diagnosticsEnabled = false))
        // the setting is on (or the diagnostics say so) and the dish still refuses: its policy, not the user's setting
        assertEquals(LocationRefusal.POLICY_DENIED, LocationRefusal.classify(plain, local))
        assertEquals(LocationRefusal.POLICY_DENIED, LocationRefusal.classify(plain, null, diagnosticsEnabled = true))
        assertEquals(LocationRefusal.POLICY_DENIED, LocationRefusal.classify(refused(GrpcStatus.PERMISSION_DENIED, null), local))
        assertEquals(LocationRefusal.POLICY_DENIED, LocationRefusal.classify(refused(GrpcStatus.UNAUTHENTICATED, "not logged in"), local))
    }

    @Test
    fun answersCutOffsAndTransportFailuresAreNoRefusalOfThisKind() {
        val sim = FakeDishSimulation(spoofEverySec = 0, startNs = 0, clock = { 0L })
        val ok = call(sim, DishCallKind.LOCATION_AUTO)
        assertTrue(ok.ok)
        assertNull(LocationRefusal.classify(ok, none), "an answer is no refusal whatever the setting reads")
        assertNull(LocationRefusal.classify(refused(GrpcStatus.PERMISSION_DENIED, disablement), none), "a cut-off is the CutoffTracker's")
        assertNull(LocationRefusal.classify(DishCallResult.failure(DishCallKind.LOCATION_AUTO, 0, 0, "-", "connect timed out"), none))
        // another failure is a refusal only when the setting itself reads as off
        assertEquals(LocationRefusal.NOT_ENABLED, LocationRefusal.classify(refused(13, "internal error"), none))
        assertNull(LocationRefusal.classify(refused(13, "internal error"), local))
        assertNull(LocationRefusal.classify(refused(13, "internal error"), null))
    }

    // ---- the tracker

    @Test
    fun theTrackerFollowsTheCallsAndReportsEveryChange() {
        val changes = ArrayList<Pair<LocationRefusal?, Long>>()
        val tracker = LocationRefusalTracker { r, _, at -> changes += r to at }
        tracker.onCall(status(local), 1 * sec)
        assertNull(tracker.current)
        tracker.onCall(refused(GrpcStatus.PERMISSION_DENIED, policy), 2 * sec)
        assertEquals(LocationRefusal.POLICY_DENIED, tracker.current)
        assertEquals(2 * sec, tracker.sinceNs)
        assertEquals(policy, tracker.message)
        // the same refusal again, a cut-off, an unreachable dish: no change, the start stays
        tracker.onCall(refused(GrpcStatus.PERMISSION_DENIED, policy), 3 * sec)
        tracker.onCall(refused(GrpcStatus.PERMISSION_DENIED, disablement), 4 * sec)
        tracker.onCall(DishCallResult.failure(DishCallKind.LOCATION_AUTO, 0, 0, "-", "connect timed out"), 5 * sec)
        assertEquals(LocationRefusal.POLICY_DENIED, tracker.current)
        assertEquals(2 * sec, tracker.sinceNs)
        assertEquals(listOf<Pair<LocationRefusal?, Long>>(LocationRefusal.POLICY_DENIED to 2 * sec), changes)
        // the dish answers again
        val sim = FakeDishSimulation(spoofEverySec = 0, startNs = 0, clock = { 0L })
        tracker.onCall(call(sim, DishCallKind.LOCATION_AUTO), 6 * sec)
        assertNull(tracker.current)
        assertEquals(0L, tracker.sinceNs)
        assertNull(tracker.message)
        // the setting read as off in the status turns a plain refusal into NOT_ENABLED; read as on, into POLICY_DENIED
        tracker.onCall(status(none), 7 * sec)
        tracker.onCall(refused(GrpcStatus.PERMISSION_DENIED, notEnabled), 8 * sec)
        assertEquals(LocationRefusal.NOT_ENABLED, tracker.current)
        tracker.onCall(status(local), 9 * sec)
        tracker.onCall(refused(GrpcStatus.PERMISSION_DENIED, notEnabled), 10 * sec)
        assertEquals(LocationRefusal.POLICY_DENIED, tracker.current)
        // with no config in the status, the diagnostics' `enabled` is the setting's third source
        tracker.onCall(status(null), 11 * sec)
        tracker.onCall(diagnostics(false), 11 * sec)
        tracker.onCall(refused(GrpcStatus.PERMISSION_DENIED, notEnabled), 12 * sec)
        assertEquals(LocationRefusal.NOT_ENABLED, tracker.current)
        assertEquals(
            listOf(LocationRefusal.POLICY_DENIED, null, LocationRefusal.NOT_ENABLED, LocationRefusal.POLICY_DENIED, LocationRefusal.NOT_ENABLED),
            changes.map { it.first },
        )
    }

    // ---- the fake dish models the three refusals and the diagnostics' location

    private fun call(sim: FakeDishSimulation, kind: DishCallKind): DishCallResult {
        val (resp, st) = sim.handle(kind.request(1))
        return DishCallResult(kind, 0, 0, "fake", 200, st.first, st.second.ifEmpty { null }, ByteArray(0), resp, null)
    }

    private fun fake(policy: Boolean = false, local: Boolean = true, unimplemented: Boolean = false, diagnostics: Boolean = true, disableEvery: Long = 0, clock: () -> Long) =
        FakeDishSimulation(
            spoofEverySec = 0, radiusM = 2000.0, locationLocal = local, locationPolicyDenied = policy, diagnosticsLocation = diagnostics,
            unimplemented = if (unimplemented) setOf("get_location") else emptySet(), disableEverySec = disableEvery, disableForSec = if (disableEvery > 0) 10 else 0,
            startNs = 0, clock = clock, wallClockMs = { 1_791_990_000_000L + clock() / 1_000_000L },
        )

    @Test
    fun theFakeDishAnswersEachRefusalWithTheRealWordsAndTheChainNamesIt() {
        var now = 1 * sec
        fun state(sim: FakeDishSimulation): Pair<LocationRefusal?, CallOutcome> {
            val chain = DishLocationChain()
            chain.onCall(call(sim, DishCallKind.STATUS), now)
            val auto = call(sim, DishCallKind.LOCATION_AUTO)
            chain.onCall(auto, now)
            return chain.refusal to auto.outcome
        }
        assertEquals(LocationRefusal.POLICY_DENIED to CallOutcome.PLAN_RESTRICTED, state(fake(policy = true) { now }))
        assertEquals(LocationRefusal.UNIMPLEMENTED to CallOutcome.UNIMPLEMENTED, state(fake(unimplemented = true) { now }))
        assertEquals(LocationRefusal.NOT_ENABLED to CallOutcome.PERMISSION_DENIED, state(fake(local = false) { now }))
        assertEquals(null to CallOutcome.OK, state(fake { now }))
        // a cut-off is none of the three (and the fake withholds the diagnostics' location meanwhile, as the Mini does)
        val cut = fake(disableEvery = 20) { now }
        now = 15 * sec
        val chain = DishLocationChain()
        chain.onCall(call(cut, DishCallKind.STATUS), now)
        val auto = call(cut, DishCallKind.LOCATION_AUTO)
        assertTrue(auto.refusedByDisablement)
        chain.onCall(auto, now)
        assertNull(chain.refusal)
        val d = call(cut, DishCallKind.DIAGNOSTICS).response!!.diagnosticsFix()!!
        assertFalse(d.enabled)
        assertTrue(d.looksLikePlaceholder)
        assertNull(d.uncertaintyM)
        assertEquals(0.0, d.gpsTimeS)
    }

    @Test
    fun theChainHandsTheDiagnosticsPositionOnlyWhileGetLocationIsRefused() {
        var now = 1 * sec
        val sim = fake(policy = true) { now }
        val changes = ArrayList<LocationRefusal?>()
        val chain = DishLocationChain { r, _, _ -> changes += r }
        // before any AUTO call the diagnostics is not a stand-in (nothing is refused yet)
        assertNull(chain.onCall(call(sim, DishCallKind.DIAGNOSTICS), now))
        chain.onCall(call(sim, DishCallKind.STATUS), now)
        assertNull(chain.onCall(call(sim, DishCallKind.LOCATION_AUTO), now))
        assertEquals(LocationRefusal.POLICY_DENIED, chain.refusal)
        val first = chain.onCall(call(sim, DishCallKind.DIAGNOSTICS), now)
        assertNotNull(first)
        assertTrue(first.fromDiagnostics)
        assertEquals(PositionSource.AUTO.code, first.fix.source)
        assertEquals(1.59, first.fix.sigmaM, 1e-9)
        val truth = sim.truthAt(now / 1e9 - 0.45) // the fake's fixes are 0.45 s old, like a Mini's
        assertTrue(Geo.distanceM(first.fix.lat, first.fix.lon, truth.lat, truth.lon) < 1.0)
        // the speed is the chord between distinct positions over their fix times: known from the second position on
        assertTrue(first.fix.horizontalSpeedMps.isNaN() || first.fix.horizontalSpeedMps in 11.0..13.0, "${first.fix.horizontalSpeedMps}")
        now = 2 * sec
        chain.onCall(call(sim, DishCallKind.LOCATION_AUTO), now)
        val secondCall = call(sim, DishCallKind.DIAGNOSTICS)
        val second = chain.onCall(secondCall, now)!!
        assertEquals(12.0, second.fix.horizontalSpeedMps, 0.5)
        // the same solution seen again (the dish computes one every ~1.08 s, the app asks every second) keeps its speed
        val again = chain.onCall(secondCall, now + sec / 2)!!
        assertEquals(second.fix.lat, again.fix.lat)
        assertEquals(second.fix.horizontalSpeedMps, again.fix.horizontalSpeedMps)
        // the dish answers again: the AUTO answer is the position, the diagnostics stands in no more
        sim.locationPolicyDenied = false
        now = 3 * sec
        val auto = chain.onCall(call(sim, DishCallKind.LOCATION_AUTO), now)
        assertNotNull(auto)
        assertFalse(auto.fromDiagnostics)
        assertNull(chain.refusal)
        assertNull(chain.onCall(call(sim, DishCallKind.DIAGNOSTICS), now))
        assertEquals(listOf(LocationRefusal.POLICY_DENIED, null), changes)
    }

    @Test
    fun aDishThatWithholdsTheDiagnosticsLocationLeavesNothingToStandIn() {
        var now = 1 * sec
        for (sim in listOf(fake(policy = true, diagnostics = false) { now }, fake(local = false) { now }, fake(unimplemented = true, diagnostics = false) { now })) {
            val chain = DishLocationChain()
            chain.onCall(call(sim, DishCallKind.STATUS), now)
            chain.onCall(call(sim, DishCallKind.LOCATION_AUTO), now)
            assertNotNull(chain.refusal)
            assertNull(chain.onCall(call(sim, DishCallKind.DIAGNOSTICS), now), "withheld: ${chain.refusal}")
            now += sec
        }
        // unimplemented with the diagnostics answering: the stand-in works for that refusal too
        val sim = fake(unimplemented = true) { now }
        val chain = DishLocationChain()
        chain.onCall(call(sim, DishCallKind.STATUS), now)
        chain.onCall(call(sim, DishCallKind.LOCATION_AUTO), now)
        assertEquals(LocationRefusal.UNIMPLEMENTED, chain.refusal)
        assertNotNull(chain.onCall(call(sim, DishCallKind.DIAGNOSTICS), now))
    }

    @Test
    fun theChordNeedsTwoPositionsCloseInTime() {
        var now = 1 * sec
        val sim = fake(policy = true) { now }
        val chain = DishLocationChain()
        chain.onCall(call(sim, DishCallKind.STATUS), now)
        chain.onCall(call(sim, DishCallKind.LOCATION_AUTO), now)
        chain.onCall(call(sim, DishCallKind.DIAGNOSTICS), now)
        now += 2 * sec
        assertEquals(12.0, chain.onCall(call(sim, DishCallKind.DIAGNOSTICS), now)!!.fix.horizontalSpeedMps, 0.5, "2 s apart: a chord")
        now += 6 * sec
        assertTrue(chain.onCall(call(sim, DishCallKind.DIAGNOSTICS), now)!!.fix.horizontalSpeedMps.isNaN(), "6 s apart: a gap, no speed")
        now += sec
        assertEquals(12.0, chain.onCall(call(sim, DishCallKind.DIAGNOSTICS), now)!!.fix.horizontalSpeedMps, 0.5)
    }

    // ---- the diagnostics fix value

    @Test
    fun theDiagnosticsFixReadsTheProtoAndTellsAnHonestSigma() {
        val valid = DeviceResponse(dish_get_diagnostics = DishGetDiagnosticsResponse(location = DishGetDiagnosticsResponse.Location(
            enabled = true, latitude = 50.4501, longitude = 30.5234, altitude_meters = 115.0, uncertainty_meters_valid = true, uncertainty_meters = 2.5, gps_time_s = 1.4e9,
        ))).diagnosticsFix()!!
        assertEquals(2.5, valid.sigmaM)
        assertFalse(valid.looksLikePlaceholder)
        val fix = valid.toDishFix(7.0)
        assertEquals(50.4501, fix.lat)
        assertEquals(115.0, fix.alt)
        assertEquals(2.5, fix.sigmaM)
        assertEquals(7.0, fix.horizontalSpeedMps)
        assertEquals(0.0, fix.verticalSpeedMps)
        assertFalse(fix.looksLikePlaceholder)
        val invalid = DeviceResponse(dish_get_diagnostics = DishGetDiagnosticsResponse(location = DishGetDiagnosticsResponse.Location(
            enabled = true, latitude = 50.4501, longitude = 30.5234, uncertainty_meters_valid = false, uncertainty_meters = 2.5,
        ))).diagnosticsFix()!!
        assertNull(invalid.uncertaintyM)
        assertEquals(DiagnosticsFix.UNKNOWN_SIGMA_M, invalid.sigmaM)
        assertTrue(DeviceResponse(dish_get_diagnostics = DishGetDiagnosticsResponse(location = DishGetDiagnosticsResponse.Location(enabled = true))).diagnosticsFix()!!.looksLikePlaceholder)
        assertNull(DeviceResponse(dish_get_diagnostics = DishGetDiagnosticsResponse()).diagnosticsFix())
        assertNull(DeviceResponse().diagnosticsFix())
    }
}
