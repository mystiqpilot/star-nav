package tools.apachi.starlinknav.core.nav

import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.FakeDishSimulation
import tools.apachi.starlinknav.core.dish.GrpcStatus
import tools.apachi.starlinknav.core.dish.proto.BootInfo
import tools.apachi.starlinknav.core.dish.proto.DeviceInfo
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DeviceState
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.proto.RebootResponse
import tools.apachi.starlinknav.core.dish.proto.SoftwareUpdateStats
import tools.apachi.starlinknav.core.dish.updateWaiting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SEC = 1_000_000_000L

/** A status as the user's Mini sent it on 2026-10-14: an update waiting, restart planned at night. */
private fun dishStatus(
    uptimeS: Long,
    software: String = "2026.05.13.mr80201",
    bootcount: Int = 6,
    reason: Int = 0,
    updateState: Int = 6,
    bootReason: Int? = null,
) = DishCallResult(
    DishCallKind.STATUS, 0, 0, "test", 200, GrpcStatus.OK, null, ByteArray(0),
    DeviceResponse(
        dish_get_status = DishGetStatusResponse(
            device_info = DeviceInfo(software_version = software, bootcount = bootcount, boot = bootReason?.let { BootInfo(last_reason = it) }),
            device_state = DeviceState(uptime_s = uptimeS),
            disablement_code = 1,
            reboot_reason = reason,
            software_update_state = updateState,
            software_update_stats = SoftwareUpdateStats(software_update_state = updateState, update_requires_reboot = updateState == 6, reboot_scheduled_utc_time = 1_792_025_768L),
        ),
    ),
    null,
)

private fun unreachable(kind: DishCallKind = DishCallKind.STATUS) = DishCallResult.failure(kind, 0, 0, "-", "connect timed out")

class RebootTrackerTest {
    @Test
    fun steadyUptimeWithJitterAndDriftIsNoRestart() {
        val tracker = RebootTracker()
        // 656 s up at the start, one status a second for an hour; receipt jitter up to 0.9 s and 50 ppm drift.
        for (i in 0..3600) {
            val t = i * SEC + (i % 7) * 130_000_000L
            tracker.onCall(dishStatus(656L + (i * 1.00005).toLong()), t)
        }
        assertTrue(tracker.reboots.isEmpty())
        assertEquals(-656 * SEC, tracker.firstBootNs)
        assertTrue(tracker.updateWaiting)
        assertEquals(1_792_025_768L, tracker.updateRestartAtS)
    }

    @Test
    fun restartToInstallAnUpdate() {
        val seen = mutableListOf<DishReboot>()
        val tracker = RebootTracker { seen += it }
        for (i in 0..100) tracker.onCall(dishStatus(1000L + i), i * SEC)
        // Down for 95 s: the poller only gets timeouts, every 3 s.
        for (i in 103..196 step 3) tracker.onCall(unreachable(), i * SEC)
        tracker.onCall(dishStatus(40, software = "2026.09.18.mr87170.1", bootcount = 7, reason = 3, updateState = 1), 196 * SEC)
        for (i in 197..230) tracker.onCall(dishStatus(40L + i - 196, software = "2026.09.18.mr87170.1", bootcount = 7, updateState = 1), i * SEC)

        val r = seen.single()
        assertEquals(tracker.reboots, seen)
        assertEquals(156 * SEC, r.bootNs)
        assertEquals(100 * SEC, r.lastAnswerNs)
        assertEquals(196 * SEC, r.backNs)
        assertEquals(96.0, r.offlineS!!, 1e-9)
        assertEquals(3, r.reason)
        assertTrue(r.updated)
        assertTrue(r.updateWaiting)
        assertEquals(RebootCause.UPDATE_INSTALLED, r.cause)
        assertEquals(6, r.bootcountBefore)
        assertEquals(7, r.bootcountAfter)
        assertTrue(!tracker.updateWaiting)
    }

    @Test
    fun reasonFromStatusOrBootInfoWhenTheVersionStays() {
        val tracker = RebootTracker()
        for (i in 0..10) tracker.onCall(dishStatus(5000L + i), i * SEC)
        tracker.onCall(dishStatus(30, bootcount = 7, reason = 9, updateState = 1), 90 * SEC)
        assertEquals(RebootCause.OVERHEAT, tracker.reboots.single().cause)

        val boot = RebootTracker()
        for (i in 0..10) boot.onCall(dishStatus(5000L + i), i * SEC)
        boot.onCall(dishStatus(30, bootcount = 7, bootReason = 2), 90 * SEC)
        assertEquals(RebootCause.POWER, boot.reboots.single().cause)

        val none = RebootTracker()
        for (i in 0..10) none.onCall(dishStatus(5000L + i), i * SEC)
        none.onCall(dishStatus(30, bootcount = 7), 90 * SEC)
        assertEquals(RebootCause.NOT_REPORTED, none.reboots.single().cause)
    }

    @Test
    fun aRestartCommandThatNeverReachedTheDishOrWasRefusedIsNotTheCause() {
        // The car's 12 V cut the dish's power: the reboot tapped meanwhile found no connection, so the restart that
        // follows when power comes back is the dish's own (POWER_CYCLE), not Star Nav's.
        val unreached = RebootTracker()
        for (i in 0..10) unreached.onCall(dishStatus(5000L + i), i * SEC)
        unreached.onCall(unreachable(DishCallKind.REBOOT), 30 * SEC)
        unreached.onCall(dishStatus(45, bootcount = 7, bootReason = 2), 200 * SEC)
        assertTrue(!unreached.reboots.single().commanded)
        assertTrue(unreached.reboots.single().cause != RebootCause.STAR_NAV, "${unreached.reboots.single().cause}")

        // A factory reset the dish refused, then a restart within the 10 min for another reason.
        val refused = RebootTracker()
        for (i in 0..10) refused.onCall(dishStatus(5000L + i), i * SEC)
        refused.onCall(
            DishCallResult(DishCallKind.DISH_FACTORY_RESET, 0, 0, "test", 200, GrpcStatus.PERMISSION_DENIED, "denied", ByteArray(0), null, null),
            11 * SEC,
        )
        refused.onCall(dishStatus(45, bootcount = 7, reason = 5), 80 * SEC)
        assertTrue(!refused.reboots.single().commanded)
    }

    @Test
    fun aRebootCommandFromTheAppIsTheCause() {
        val tracker = RebootTracker()
        for (i in 0..10) tracker.onCall(dishStatus(5000L + i), i * SEC)
        // The dish may drop the connection instead of answering the command.
        tracker.onCall(DishCallResult.failure(DishCallKind.REBOOT, 0, 0, "-", "unexpected end of stream", sentNoAnswer = true), 11 * SEC)
        tracker.onCall(dishStatus(45, bootcount = 7, reason = 5), 80 * SEC)
        assertEquals(RebootCause.STAR_NAV, tracker.reboots.single().cause)
        assertTrue(tracker.reboots.single().commanded)

        val ok = RebootTracker()
        for (i in 0..10) ok.onCall(dishStatus(5000L + i), i * SEC)
        ok.onCall(DishCallResult(DishCallKind.REBOOT, 0, 0, "test", 200, GrpcStatus.OK, null, ByteArray(0), DeviceResponse(reboot = RebootResponse()), null), 11 * SEC)
        ok.onCall(dishStatus(45, bootcount = 7), 80 * SEC)
        assertEquals(RebootCause.STAR_NAV, ok.reboots.single().cause)
    }

    @Test
    fun anUptimeGlitchWithoutAGapIsNoRestart() {
        val tracker = RebootTracker()
        for (i in 0..10) tracker.onCall(dishStatus(5000L + i), i * SEC)
        tracker.onCall(dishStatus(12), 11 * SEC)
        for (i in 12..20) tracker.onCall(dishStatus(5000L + i), i * SEC)
        assertTrue(tracker.reboots.isEmpty())
    }

    @Test
    fun statusesWithoutUptimeDuringBootDoNotHideTheRestart() {
        val tracker = RebootTracker()
        for (i in 0..10) tracker.onCall(dishStatus(5000L + i), i * SEC)
        // First answers after the boot leave the uptime out (proto3 zero), then it shows.
        tracker.onCall(dishStatus(0, bootcount = 0), 70 * SEC)
        tracker.onCall(dishStatus(0, bootcount = 0), 71 * SEC)
        tracker.onCall(dishStatus(32, bootcount = 7), 72 * SEC)
        val r = tracker.reboots.single()
        assertEquals(10 * SEC, r.lastAnswerNs)
        assertEquals(70 * SEC, r.backNs)
        assertEquals("2026.05.13.mr80201", r.softwareBefore)
    }

    @Test
    fun theFakeDishRestartsTheWayTheTrackerExpects() {
        var now = 0L
        val dish = FakeDishSimulation(spoofEverySec = 0, startNs = 0, clock = { now })
        val tracker = RebootTracker()
        fun call(kind: DishCallKind) {
            val (resp, status) = dish.handle(kind.request(1))
            val result = if (resp != null) {
                DishCallResult(kind, now, 0, "fake", 200, GrpcStatus.OK, null, ByteArray(0), resp, null)
            } else {
                DishCallResult.failure(kind, now, 0, "fake", status.second)
            }
            tracker.onCall(result, now)
        }
        repeat(10) { call(DishCallKind.STATUS); now += SEC }
        assertTrue(dish.handle(DishCallKind.STATUS.request(1)).first!!.dish_get_status!!.updateWaiting())
        assertEquals(656L, tracker.uptimeS!! - 9)
        call(DishCallKind.REBOOT)
        repeat(60) { call(DishCallKind.STATUS); now += SEC }
        val first = tracker.reboots.single()
        assertEquals(RebootCause.UPDATE_INSTALLED, first.cause)
        assertEquals("fake-2026.09", first.softwareAfter)
        assertEquals(40.0, first.offlineS!!, 1e-9)
        assertEquals(7, first.bootcountAfter)
        assertTrue(!tracker.updateWaiting)

        call(DishCallKind.REBOOT)
        repeat(60) { call(DishCallKind.STATUS); now += SEC }
        assertEquals(RebootCause.STAR_NAV, tracker.reboots.last().cause)

        // Restarting by itself, as `slnav fake-dish --reboot-every` does.
        now = 0L
        val auto = FakeDishSimulation(spoofEverySec = 0, rebootEverySec = 30, rebootReason = 9, startNs = 0, clock = { now })
        val seen = RebootTracker()
        repeat(80) {
            val (resp, status) = auto.handle(DishCallKind.STATUS.request(1))
            seen.onCall(
                if (resp != null) DishCallResult(DishCallKind.STATUS, now, 0, "fake", 200, GrpcStatus.OK, null, ByteArray(0), resp, null)
                else DishCallResult.failure(DishCallKind.STATUS, now, 0, "fake", status.second),
                now,
            )
            now += SEC
        }
        assertEquals(RebootCause.UPDATE_INSTALLED, seen.reboots.single().cause)
        assertEquals(9, seen.reboots.single().reason)
    }

    @Test
    fun aSessionThatStartsAfterARestartOnlyKnowsTheBootTime() {
        val tracker = RebootTracker()
        tracker.onCall(unreachable(), 0)
        tracker.onCall(dishStatus(20, bootcount = 7), 9 * SEC)
        for (i in 10..20) tracker.onCall(dishStatus(20L + i - 9, bootcount = 7), i * SEC)
        assertTrue(tracker.reboots.isEmpty())
        assertEquals(-11 * SEC, tracker.firstBootNs)
        assertNull(RebootTracker().firstBootNs)
    }
}
