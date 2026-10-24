package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DishConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dish's "location on the local network" setting (`DishConfig.location_request_mode`): how its state is read (status config
 * first, `dish_get_config` as the fallback, a position answer when neither is there), and the enable command end to end against
 * the fake dish, accepted and refused.
 */
class LocalLocationTest {
    private fun dish(local: Boolean, deny: Boolean = false, unimplemented: Set<String> = emptySet()) =
        FakeDishSimulation(spoofEverySec = 0, locationLocal = local, denySetConfig = deny, unimplemented = unimplemented, startNs = 0, clock = { 0L })

    private fun client(sim: FakeDevice) = DishClient(FakeTransport(sim))

    private fun statusConfig(c: DishClient) = c.call(DishCallKind.STATUS).response!!.dish_get_status!!.config

    private fun refused(code: Int, message: String?) =
        DishCallResult(DishCallKind.LOCATION_AUTO, 0, 1_000_000, "grpc-web", 200, code, message, null, null, null)

    private val localConfig = DishConfig(location_request_mode = DishCallKind.LOCATION_MODE_LOCAL)
    private val offConfig = DishConfig(location_request_mode = 0)

    // ---- the fake models the setting where the real dish shows it

    @Test
    fun theFakeShowsTheSettingInTheConfigAndInTheStatusConfigAndRefusesLocationWhileItIsOff() {
        val sim = dish(local = false, deny = true)
        val c = client(sim)
        assertEquals(0, c.call(DishCallKind.CONFIG).response!!.dish_get_config!!.dish_config!!.location_request_mode)
        assertEquals(0, statusConfig(c)!!.location_request_mode)
        for (kind in listOf(DishCallKind.LOCATION_AUTO, DishCallKind.LOCATION_GPS, DishCallKind.LOCATION_STARLINK)) {
            val r = c.call(kind)
            // A refusal, but not a UT disablement and not a plan restriction: those mean something else.
            assertEquals(CallOutcome.PERMISSION_DENIED, r.outcome, "$kind")
            assertEquals("GetLocation requests are not enabled on this device", r.grpcMessage)
            assertFalse(r.refusedByDisablement)
        }
        val on = client(dish(local = true))
        assertEquals(DishCallKind.LOCATION_MODE_LOCAL, on.call(DishCallKind.CONFIG).response!!.dish_get_config!!.dish_config!!.location_request_mode)
        assertEquals(DishCallKind.LOCATION_MODE_LOCAL, statusConfig(on)!!.location_request_mode)
        assertNotNull(on.call(DishCallKind.LOCATION_AUTO).response?.fix())
    }

    @Test
    fun anAcceptedWriteSwitchesItOnAndGetLocationThenAnswers() {
        val sim = dish(local = false)
        val c = client(sim)
        assertEquals(LocalLocationState.OFF, LocalLocation.read(statusConfig(c), null, c.call(DishCallKind.LOCATION_AUTO)).state)
        val result = c.call(DishCallKind.ALLOW_LOCAL_LOCATION)
        assertTrue(result.ok, "${result.statusText} ${result.grpcMessage}")
        assertEquals(listOf("dish_set_config"), sim.accepted)
        // The change shows in both places and the dish now answers.
        assertEquals(DishCallKind.LOCATION_MODE_LOCAL, c.call(DishCallKind.CONFIG).response!!.dish_get_config!!.dish_config!!.location_request_mode)
        val auto = c.call(DishCallKind.LOCATION_AUTO)
        assertTrue(auto.ok)
        val reading = LocalLocation.read(statusConfig(c), null, auto)
        assertEquals(LocalLocationState.ON, reading.state)
        assertEquals(LocalLocationBasis.STATUS_CONFIG, reading.basis)
        // Switching it off again (the Controls card's "Stop allowing") refuses location again.
        assertTrue(c.send(DishCommand.SetConfig(locationRequestMode = LocationRequestMode.NONE)).ok)
        assertEquals(CallOutcome.PERMISSION_DENIED, c.call(DishCallKind.LOCATION_AUTO).outcome)
        assertEquals(LocalLocationState.OFF, LocalLocation.read(statusConfig(c), null, null).state)
    }

    @Test
    fun aDeniedWriteChangesNothingAndTheBookRemembersTheRefusal() {
        val sim = dish(local = false, deny = true)
        val c = client(sim)
        val r = c.call(DishCallKind.ALLOW_LOCAL_LOCATION)
        assertEquals(CallOutcome.PERMISSION_DENIED, r.outcome)
        assertTrue(sim.accepted.isEmpty())
        assertEquals(LocalLocationState.OFF, LocalLocation.read(statusConfig(c), null, c.call(DishCallKind.LOCATION_AUTO)).state)
        assertEquals(CallOutcome.PERMISSION_DENIED, c.call(DishCallKind.LOCATION_AUTO).outcome)
        // What the capability book (and so the greyed-out button and its remembered sentence) is built from.
        val book = CapabilityBook().record("dish", r, nowMs = 1_000)
        assertTrue(book.isUnavailable("dish", DishCallKind.ALLOW_LOCAL_LOCATION))
        assertEquals(CallOutcome.PERMISSION_DENIED, book.last("dish", DishCallKind.ALLOW_LOCAL_LOCATION)!!.outcome)
        // A success is remembered too, and does not grey the control.
        val accepted = client(dish(local = false)).call(DishCallKind.ALLOW_LOCAL_LOCATION)
        val book2 = book.record("dish", accepted, nowMs = 2_000)
        assertFalse(book2.isUnavailable("dish", DishCallKind.ALLOW_LOCAL_LOCATION))
        assertEquals(CallOutcome.OK, book2.last("dish", DishCallKind.ALLOW_LOCAL_LOCATION)!!.outcome)
    }

    @Test
    fun aFactoryResetPutsTheSettingBackOff() {
        var now = 0L
        val sim = FakeDishSimulation(spoofEverySec = 0, locationLocal = true, startNs = 0, clock = { now }, rebootDownSec = 40)
        val c = client(sim)
        assertEquals(DishCallKind.LOCATION_MODE_LOCAL, c.call(DishCallKind.CONFIG).response!!.dish_get_config!!.dish_config!!.location_request_mode)
        assertTrue(c.call(DishCallKind.DISH_FACTORY_RESET).ok)
        // The dish restarts after a reset and stays silent for a while.
        now += 60_000_000_000L
        assertEquals(0, c.call(DishCallKind.CONFIG).response!!.dish_get_config!!.dish_config!!.location_request_mode)
        assertEquals(CallOutcome.PERMISSION_DENIED, c.call(DishCallKind.LOCATION_AUTO).outcome)
    }

    // ---- the state: status config first, dish_get_config as the fallback

    @Test
    fun theStateIsKnownFromTheStatusConfigWhileDishGetConfigIsRefused() {
        for (local in listOf(false, true)) {
            val c = client(dish(local = local, unimplemented = setOf("dish_get_config")))
            val config = c.call(DishCallKind.CONFIG)
            assertEquals(CallOutcome.UNIMPLEMENTED, config.outcome, "2011 is refused")
            val reading = LocalLocation.read(statusConfig(c), config.takeIf { it.ok }?.response?.dish_get_config?.dish_config, c.call(DishCallKind.LOCATION_AUTO))
            assertEquals(if (local) LocalLocationState.ON else LocalLocationState.OFF, reading.state)
            assertEquals(LocalLocationBasis.STATUS_CONFIG, reading.basis)
            assertTrue(reading.settingRead)
        }
    }

    @Test
    fun theStatusConfigWinsAndDishGetConfigIsTheFallback() {
        assertEquals(LocalLocationState.ON, LocalLocation.read(localConfig, offConfig, null).state, "status says on")
        assertEquals(LocalLocationState.OFF, LocalLocation.read(offConfig, localConfig, null).state, "status says off")
        val fallback = LocalLocation.read(null, localConfig, null)
        assertEquals(LocalLocationState.ON, fallback.state)
        assertEquals(LocalLocationBasis.GET_CONFIG, fallback.basis)
        assertEquals(LocalLocationState.OFF, LocalLocation.read(null, offConfig, null).state)
    }

    @Test
    fun aDishThatAnswersPositionRequestsMakesLocationAvailableWhateverTheSettingSays() {
        val answer = client(dish(local = true)).call(DishCallKind.LOCATION_AUTO)
        assertTrue(answer.ok)
        // The setting reads off (or could not be read) but AUTO answers: available, and not "on".
        val readsOff = LocalLocation.read(offConfig, null, answer)
        assertEquals(LocalLocationState.ANSWERS_ANYWAY, readsOff.state)
        assertTrue(readsOff.available && readsOff.settingRead)
        val unread = LocalLocation.read(null, null, answer)
        assertEquals(LocalLocationState.ANSWERS_ANYWAY, unread.state)
        assertEquals(LocalLocationBasis.AUTO_FIX, unread.basis)
        assertFalse(unread.settingRead)
        // A setting that says LOCAL is ON, not "answers anyway".
        assertEquals(LocalLocationState.ON, LocalLocation.read(localConfig, null, answer).state)
    }

    @Test
    fun withNoSettingToReadARefusalMeansProbablyOffAndOtherAnswersMeanNothing() {
        // Plain refusal ("not enabled"): probably off, and marked as inferred.
        val denied = LocalLocation.read(null, null, refused(GrpcStatus.PERMISSION_DENIED, "GetLocation requests are not enabled on this device"))
        assertEquals(LocalLocationState.OFF, denied.state)
        assertEquals(LocalLocationBasis.AUTO_REFUSED, denied.basis)
        assertFalse(denied.settingRead)
        // Disabled right now (moving too fast): says nothing about the setting.
        val disabled = LocalLocation.read(null, null, refused(GrpcStatus.PERMISSION_DENIED, "GetLocation requests disabled due to UT disablement"))
        assertEquals(LocalLocationState.UNKNOWN, disabled.state)
        assertTrue(disabled.disabledNow)
        // The plan refuses positions: enabling the setting would not help, and it is not "off".
        val plan = LocalLocation.read(null, null, refused(GrpcStatus.PERMISSION_DENIED, "Failed to get location: Disabled due to policy"))
        assertEquals(LocalLocationState.UNKNOWN, plan.state)
        assertTrue(plan.planRestricted)
        // The call is not offered at all, the dish is silent, or nothing was asked.
        assertEquals(LocalLocationState.UNKNOWN, LocalLocation.read(null, null, refused(GrpcStatus.UNIMPLEMENTED, "Unimplemented: *device.Request_GetLocation")).state)
        assertEquals(LocalLocationState.UNKNOWN, LocalLocation.read(null, null, DishCallResult.failure(DishCallKind.LOCATION_AUTO, 0, 0, "-", "connect timed out")).state)
        assertEquals(LocalLocationState.UNKNOWN, LocalLocation.read(null, null, null).state)
        assertEquals(LocalLocationState.UNKNOWN, LocalLocationReading.UNKNOWN.state)
        assertNull(LocalLocationReading.UNKNOWN.mode)
    }

    @Test
    fun aSettingReadFromTheDishStaysTheAnswerWhileTheDishIsDisabledOrTheAnswerIsAPlanRefusal() {
        val disabledNow = LocalLocation.read(offConfig, null, refused(GrpcStatus.PERMISSION_DENIED, "GetLocation requests disabled due to UT disablement"))
        assertEquals(LocalLocationState.OFF, disabledNow.state)
        assertTrue(disabledNow.disabledNow)
        val onButPlan = LocalLocation.read(localConfig, null, refused(GrpcStatus.PERMISSION_DENIED, "Failed to get location: Disabled due to policy"))
        assertEquals(LocalLocationState.ON, onButPlan.state)
        assertTrue(onButPlan.planRestricted, "the screens say the plan refuses, not that the setting is wrong")
    }

    // ---- the official diagnostics' location.enabled as a read-back where no config is readable

    @Test
    fun theDiagnosticsFlagReadsTheSettingBackWhenNeitherConfigIsReadable() {
        for (local in listOf(false, true)) {
            val sim = dish(local = local, unimplemented = setOf("dish_get_config"))
            val c = client(sim)
            val enabled = c.call(DishCallKind.DIAGNOSTICS).response!!.dish_get_diagnostics!!.location!!.enabled
            assertEquals(local, enabled, "the fake reports the setting in the official flag")
            // No status config, no get_config: the flag is all there is, and it beats what a position request implies.
            val reading = LocalLocation.read(null, null, c.call(DishCallKind.LOCATION_AUTO), enabled)
            assertEquals(if (local) LocalLocationState.ON else LocalLocationState.OFF, reading.state)
            assertEquals(LocalLocationBasis.DIAGNOSTICS, reading.basis)
            assertFalse(reading.settingRead, "the raw mode was not read")
        }
    }

    @Test
    fun aConfigWinsOverTheDiagnosticsFlagAndAFixOverAFalseFlag() {
        assertEquals(LocalLocationBasis.STATUS_CONFIG, LocalLocation.read(localConfig, null, null, diagnosticsEnabled = false).basis)
        assertEquals(LocalLocationState.OFF, LocalLocation.read(offConfig, null, null, diagnosticsEnabled = true).state, "the config is the setting itself")
        val answer = client(dish(local = true)).call(DishCallKind.LOCATION_AUTO)
        assertEquals(LocalLocationState.ANSWERS_ANYWAY, LocalLocation.read(null, null, answer, diagnosticsEnabled = false).state)
        // Disabled right now: a false flag is not trusted over the unknown; no flag asked changes nothing.
        val disabled = refused(GrpcStatus.PERMISSION_DENIED, "GetLocation requests disabled due to UT disablement")
        assertEquals(LocalLocationState.UNKNOWN, LocalLocation.read(null, null, disabled, diagnosticsEnabled = false).state)
        assertEquals(LocalLocationState.ON, LocalLocation.read(null, null, disabled, diagnosticsEnabled = true).state)
        assertEquals(LocalLocationState.UNKNOWN, LocalLocation.read(null, null, null, null).state)
    }
}
