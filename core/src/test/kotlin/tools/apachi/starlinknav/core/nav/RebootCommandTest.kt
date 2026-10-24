package tools.apachi.starlinknav.core.nav

import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.GrpcStatus
import tools.apachi.starlinknav.core.dish.proto.DeviceInfo
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DeviceState
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SEC_NS = 1_000_000_000L

class RebootCommandTest {
    private fun status(uptime: Long, bootcount: Int) = DishCallResult(
        DishCallKind.STATUS, 0, 0, "test", 200, GrpcStatus.OK, null, ByteArray(0),
        DeviceResponse(dish_get_status = DishGetStatusResponse(device_info = DeviceInfo(software_version = "x", bootcount = bootcount), device_state = DeviceState(uptime_s = uptime))),
        null,
    )

    /** Went out and the dish dropped the connection as it went down (what a restarting dish does). */
    private fun command(kind: DishCallKind) = DishCallResult.failure(kind, 0, 0, "test", "unexpected end of stream", sentNoAnswer = true)

    private fun restartAfter(kind: DishCallKind): DishReboot {
        val tracker = RebootTracker()
        for (i in 0..60) tracker.onCall(status(1000L + i, 6), i * SEC_NS)
        tracker.onCall(command(kind), 61 * SEC_NS)
        for (i in 64..120 step 3) tracker.onCall(DishCallResult.failure(DishCallKind.STATUS, 0, 0, "-", "timeout"), i * SEC_NS)
        for (i in 121..160) tracker.onCall(status(i - 118L, 7), i * SEC_NS)
        return tracker.reboots.single()
    }

    @Test
    fun commandsThatRestartTheDishAreReportedAsSentFromTheApp() {
        assertTrue(restartAfter(DishCallKind.REBOOT).commanded)
        assertTrue(restartAfter(DishCallKind.DISH_UPDATE_REBOOT).commanded)
        assertTrue(restartAfter(DishCallKind.DISH_FACTORY_RESET).commanded)
        // A router restart or a harmless command says nothing about a dish restart.
        assertFalse(restartAfter(DishCallKind.ROUTER_REBOOT).commanded)
        assertFalse(restartAfter(DishCallKind.INHIBIT_GPS).commanded)
        // An update without a scheduled restart does not restart the dish: a restart soon after is not "sent from Star Nav".
        assertFalse(restartAfter(DishCallKind.DISH_UPDATE).commanded)
    }
}
