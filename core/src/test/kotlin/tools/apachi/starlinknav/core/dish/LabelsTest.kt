package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.nav.RebootCause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelsTest {
    @Test
    fun everyEventReasonOfTheDumpIsNamed() {
        assertEquals((0..53).toSet(), EventReason.codes)
        assertEquals("UNKNOWN", EventReason.name(0))
        assertEquals("OUTAGE_INHIBIT_RF", EventReason.name(50))
        assertEquals("UT_ALERT_USER_REPORTED_ISSUE", EventReason.name(52))
        assertEquals("ROUTER_WAN_ETH_DOWN_FDIR", EventReason.name(53))
        assertEquals("ROUTER_REBOOT", EventReason.name(14))
        // Unknown codes (newer firmware) stay printable.
        assertEquals("EVENT_REASON_99", EventReason.name(99))
        assertEquals("EVENT_REASON_-1", EventReason.name(-1))
        assertEquals("SEVERITY_9", EventSeverity.name(9))
        assertEquals("WARNING", EventSeverity.name(1))
    }

    @Test
    fun eventReasonsKnowWhoseEventItIs() {
        assertEquals(EventReason.Scope.DISH, EventReason.scope(7))
        assertEquals(EventReason.Scope.DISH, EventReason.scope(50))
        assertEquals(EventReason.Scope.DISH, EventReason.scope(22))
        assertEquals(EventReason.Scope.ROUTER, EventReason.scope(14))
        assertEquals(EventReason.Scope.ROUTER, EventReason.scope(46))
        assertEquals(EventReason.Scope.CLIENT, EventReason.scope(42))
        assertEquals(EventReason.Scope.UNKNOWN, EventReason.scope(99))
        assertTrue(EventReason.isOutage(7))
        assertFalse(EventReason.isOutage(14))
        assertEquals(6, EventReason.outageCause(7))
        assertEquals(14, EventReason.outageCause(50))
        assertEquals(11, EventReason.outageCause(10))
        assertNull(EventReason.outageCause(14))
        // MOVING_WHILE_NOT_ALLOWED (11) has no DishOutage cause; every other OUTAGE_* reason maps to a named one.
        assertNull(EventReason.outageCause(11))
        for (code in EventReason.codes.filter(EventReason::isOutage).filter { it != 11 }) {
            val cause = EventReason.outageCause(code)
            assertTrue(cause != null && !OutageCause.name(cause).startsWith("CAUSE_"), "reason $code")
        }
    }

    @Test
    fun outageCausesAndDisablementIncludeTheNewestCodes() {
        assertEquals("INHIBIT_RF", OutageCause.name(14))
        assertEquals("SKY_SEARCH", OutageCause.name(13))
        assertEquals("CAUSE_99", OutageCause.name(99))
        assertEquals("OUTSIDE_HOME_REGION", Disablement.name(17))
        assertTrue(Disablement.isDisabled(17))
        assertEquals("outside the home region", Disablement.reason(17))
        assertEquals("CODE_99", Disablement.name(99))
        assertEquals("MOVING_TOO_FAST_FOR_POLICY", Disablement.name(14))
    }

    @Test
    fun rebootReasonsOfNewFirmwareAreNamedAndMapped() {
        assertEquals("MINI2_AUTO_OFF", RebootReason.name(18))
        assertEquals("BATTERY_AUTO_OFF", RebootReason.name(19))
        assertEquals("HARD_POWER_DOWN", RebootReason.name(100))
        assertEquals(listOf("ERROR_FILE_MISSING", "ERROR_CRC_MISMATCH", "ERROR_EDR_TIMEOUT", "ERROR_UNSUPPORTED_VERSION", "ERROR_BOOT_COUNT_MISMATCH"), (200..204).map(RebootReason::name))
        assertEquals("REASON_77", RebootReason.name(77))
        // The reboot tracker has words for them.
        assertEquals(RebootCause.AUTO_OFF, RebootCause.ofRebootReason(18))
        assertEquals(RebootCause.AUTO_OFF, RebootCause.ofRebootReason(19))
        assertEquals(RebootCause.POWER, RebootCause.ofRebootReason(100))
        for (c in 200..204) assertEquals(RebootCause.FAULT, RebootCause.ofRebootReason(c))
        assertEquals(RebootCause.OTHER, RebootCause.ofRebootReason(77))
    }

    @Test
    fun smallEnumsHaveCodeNamesAndPrintUnknowns() {
        assertEquals("YES", HasActuators.name(1))
        assertEquals("NO", HasActuators.name(2))
        assertEquals("ACTUATORS_7", HasActuators.name(7))
        assertEquals("RF_5GHZ_HIGH", WifiBand.name(6))
        assertEquals("BAND_3", WifiBand.name(3))
        assertEquals("RF_2GHZ", WifiBand.wirelessName(1))
        assertEquals("RF_5GHZ_HIGH", WifiBand.clientInterfaceName(4))
        assertEquals("BYPASSED", RouterRole.name(3))
        assertEquals("ROLE_9", RouterRole.name(9))
        assertEquals("REQUIRED_PAUSED", SetupRequirement.name(2))
        assertTrue(SetupRequirement.isRequired(1))
        assertFalse(SetupRequirement.isRequired(0))
        assertEquals("REBOOT_PENDING", WifiSoftwareUpdate.name(5))
        assertTrue(WifiSoftwareUpdate.isFailure(12))
        assertFalse(WifiSoftwareUpdate.isFailure(4))
        assertEquals("REBOOT_REQUIRED", SoftwareUpdate.name(6))
        assertEquals("STATE_42", SoftwareUpdate.name(42))
        assertEquals("PASSED", HardwareSelfTest.resultName(1))
        assertEquals("GPS", HardwareSelfTest.codeName(14))
        assertEquals("IMU", HardwareSelfTest.codeName(15))
        assertEquals("TEST_CODE_55", HardwareSelfTest.codeName(55))
        assertEquals(20, (0..19).count { !HardwareSelfTest.codeName(it).startsWith("TEST_CODE") })
        assertEquals("USBC_AND_BATTERY", PowerSource.name(3))
        assertEquals("OVERAGE_LIMIT", RateLimitReason.name(5))
        assertTrue(RateLimitReason.isRestricted(2))
        assertFalse(RateLimitReason.isRestricted(RateLimitReason.NO_LIMIT))
        assertEquals("FULL", Duplex.name(2))
        assertEquals("CLIENT", ClientRole.name(1))
        assertEquals("SPEEDTEST_ERROR_8", SpeedtestError.name(8))
        assertEquals("DEFAULT", AccountShard.name(1))
        assertEquals("ENABLED", NatFlag.name(2))
    }
}
