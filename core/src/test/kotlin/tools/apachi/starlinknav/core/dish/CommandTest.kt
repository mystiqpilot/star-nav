package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DeviceRequest
import tools.apachi.starlinknav.core.dish.proto.DishConfig
import tools.apachi.starlinknav.core.dish.proto.WifiConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandTest {
    @Test
    fun stoppingTheLocationFeedIsDisruptiveTheOtherSettingsAreNot() {
        assertEquals(CommandRisk.DISRUPTIVE, DishCommand.SetConfig(locationRequestMode = LocationRequestMode.NONE).risk)
        assertEquals(CommandRisk.REVERSIBLE, DishCommand.SetConfig(locationRequestMode = LocationRequestMode.LOCAL).risk)
        assertEquals(CommandRisk.REVERSIBLE, DishCommand.SetConfig(snowMeltMode = SnowMeltMode.ALWAYS_ON).risk)
        assertEquals(CommandRisk.DESTRUCTIVE, DishCommand.FactoryReset.risk)
    }

    @Test
    fun theProbeAndPollersNeverSendACommand() {
        assertTrue(DishProbe.KINDS.none { it.isCommand })
        assertTrue(DishProbe.ROUTER_KINDS.none { it.isCommand })
        assertTrue(DishCallKind.QUERIES.none { it.isCommand })
        assertTrue(DishCallKind.DISH_QUERIES.none { it.isCommand })
        assertTrue(DishCallKind.ROUTER_QUERIES.none { it.isCommand })
        assertTrue(DishCallKind.RECORDED_LOCATIONS.none { it.isCommand })
        assertTrue(DishProbe.KINDS.all { it.target == CallTarget.DISH })
        assertTrue(DishProbe.ROUTER_KINDS.all { it.target == CallTarget.ROUTER })
        // The probe covers every read of both devices.
        assertEquals(DishCallKind.QUERIES.toSet(), (DishProbe.KINDS + DishProbe.ROUTER_KINDS).toSet())
        val commands = DishCallKind.entries.filter { it.isCommand }.map { it.name }.toSet()
        assertEquals(
            setOf(
                "REBOOT", "STOW", "UNSTOW", "INHIBIT_GPS", "ALLOW_GPS", "INHIBIT_RF", "ALLOW_RF", "ALLOW_LOCAL_LOCATION", "CLEAR_OBSTRUCTION_MAP",
                "SLEEP_SCHEDULE", "DISH_SET_CONFIG", "DISH_UPDATE", "DISH_FACTORY_RESET", "PING_HOST", "DISH_UPDATE_REBOOT",
                "ROUTER_REBOOT", "ROUTER_FACTORY_RESET", "ROUTER_UPDATE", "ROUTER_SET_CONFIG", "ROUTER_SET_CLIENT_NAME",
                "ROUTER_BLOCK_CLIENT", "ROUTER_START_SPEEDTEST", "ROUTER_PING_HOST",
            ),
            commands,
        )
        // Commands say how much they can hurt; reads do not.
        assertTrue(DishCallKind.COMMANDS.all { it.risk != null })
        assertTrue(DishCallKind.QUERIES.all { it.risk == null })
        assertEquals(CommandRisk.DESTRUCTIVE, DishCallKind.DISH_FACTORY_RESET.risk)
        assertEquals(CommandRisk.DESTRUCTIVE, DishCallKind.ROUTER_FACTORY_RESET.risk)
    }

    @Test
    fun aClientTalksToOneDeviceOnly() {
        val dish = DishClient(FakeTransport(FakeDishSimulation()))
        assertFailsWith<IllegalArgumentException> { dish.call(DishCallKind.ROUTER_STATUS) }
        assertFailsWith<IllegalArgumentException> { dish.send(RouterCommand.Reboot) }
        val router = RouterClient(FakeTransport(FakeRouterSimulation()))
        assertFailsWith<IllegalArgumentException> { router.call(DishCallKind.STATUS) }
    }

    @Test
    fun persistedCodesNeverChange() {
        // Codes are stored in session logs: append only. This is the table as released and extended.
        val expected = listOf(
            "DEVICE_INFO", "STATUS", "LOCATION_AUTO", "LOCATION_GPS", "LOCATION_STARLINK", "LOCATION_GNC_FUSED", "DIAGNOSTICS",
            "GNSS_MEASUREMENT", "LOCATION_UT_INFO", "LOCATION_EXTERNAL", "LOCATION_GNC_BAD_SAT", "LOCATION_GNC_GPS", "LOCATION_GNC_PNT",
            "LOCATION_GNC_STATIC", "REBOOT", "STOW", "UNSTOW", "INHIBIT_GPS", "ALLOW_GPS", "INHIBIT_RF", "ALLOW_RF", "ALLOW_LOCAL_LOCATION",
            "CLEAR_OBSTRUCTION_MAP", "CONFIG", "HISTORY", "OBSTRUCTION_MAP",
            "NETWORK_INTERFACES", "PING", "RADIO_STATS", "CONNECTIONS", "TIME", "SPEEDTEST_STATUS", "SLEEP_SCHEDULE", "DISH_SET_CONFIG",
            "DISH_UPDATE", "DISH_FACTORY_RESET", "PING_HOST",
            "ROUTER_STATUS", "ROUTER_HISTORY", "ROUTER_DEVICE_INFO", "ROUTER_DIAGNOSTICS", "ROUTER_CLIENTS", "ROUTER_CONFIG",
            "ROUTER_PING_METRICS", "ROUTER_NETWORK_INTERFACES", "ROUTER_PING", "ROUTER_RADIO_STATS", "ROUTER_CONNECTIONS", "ROUTER_TIME",
            "ROUTER_SPEEDTEST_STATUS", "ROUTER_PERSISTENT_STATS", "ROUTER_GUEST_INFO",
            "ROUTER_REBOOT", "ROUTER_FACTORY_RESET", "ROUTER_UPDATE", "ROUTER_SET_CONFIG", "ROUTER_SET_CLIENT_NAME", "ROUTER_BLOCK_CLIENT",
            "ROUTER_START_SPEEDTEST", "ROUTER_PING_HOST", "DISH_UPDATE_REBOOT", "ROUTER_LOCATION",
        )
        expected.forEachIndexed { i, name -> assertEquals(name, DishCallKind.of(i + 1)?.name, "code ${i + 1}") }
        assertEquals(expected.size, DishCallKind.entries.size, "a new kind needs a line in this table")
        assertEquals(DishCallKind.entries.size, DishCallKind.entries.map { it.code }.toSet().size)
    }

    @Test
    fun everyKindBuildsOneRequestAndParametricOnesNeedACommand() {
        for (kind in DishCallKind.entries) {
            if (kind.parametric) {
                assertFailsWith<IllegalArgumentException>("$kind") { kind.request(7) }
                continue
            }
            val bytes = DeviceRequest.ADAPTER.encode(kind.request(7))
            val decoded = DeviceRequest.ADAPTER.decode(bytes)
            assertEquals(1, TestWire.topLevelTags(bytes).count { it >= 1000 }, "$kind sets one request arm")
            assertEquals(7L, decoded.id)
        }
    }

    @Test
    fun everyParametricKindHasACommandThatBuildsOneRequest() {
        val base = WifiConfig(
            networks = listOf(
                WifiConfig.Network(
                    ipv4 = "192.168.1.1",
                    basic_service_sets = listOf(WifiConfig.BasicServiceSet(ssid = "STARLINK", band = WifiBand.RF_2GHZ)),
                ),
            ),
        )
        val commands = listOf<DeviceCommand>(
            DishCommand.SleepSchedule(SleepWindow(60, 120, true)),
            DishCommand.SetConfig(snowMeltMode = SnowMeltMode.ALWAYS_ON),
            DishCommand.Update(),
            DishCommand.Update(scheduleReboot = true),
            DishCommand.FactoryReset,
            DishCommand.PingHost("1.1.1.1"),
            RouterCommand.Reboot,
            RouterCommand.FactoryReset,
            RouterCommand.Update(),
            RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.SetNetworkName("Car"))),
            RouterCommand.SetClientGivenName(EMPTY_BASE, "02:00:00:00:01:01", "phone"),
            RouterCommand.BlockClient(base.read(), "02:00:00:00:01:02", block = true),
            RouterCommand.StartSpeedtest(),
            RouterCommand.PingHost("8.8.8.8"),
        )
        // Every parametric kind has a command; the others are sent by kind.
        for (kind in DishCallKind.COMMANDS.filter { it.parametric }) assertTrue(commands.any { it.kind == kind }, "$kind has no command")
        for (c in commands) {
            val bytes = DeviceRequest.ADAPTER.encode(c.request(3))
            assertEquals(1, TestWire.topLevelTags(bytes).count { it >= 1000 }, "${c.kind} sets one request arm")
            assertNotNull(c.kind.risk)
        }
    }

    @Test
    fun sleepScheduleNeverSendsADurationOfZero() {
        val off = DishCommand.SleepSchedule(SleepWindow.OFF).request(1).dish_power_save!!
        assertEquals(1, off.power_save_duration_minutes)
        assertFalse(off.enable_power_save)
        assertFailsWith<IllegalArgumentException> { SleepWindow(0, 0, false) }
        assertFailsWith<IllegalArgumentException> { SleepWindow(0, 1441, true) }
        assertFailsWith<IllegalArgumentException> { SleepWindow(1440, 10, true) }
        val on = DishCommand.SleepSchedule(SleepWindow(22 * 60, 480, true)).request(1).dish_power_save!!
        assertEquals(1320, on.power_save_start_minutes)
        assertEquals(480, on.power_save_duration_minutes)
        assertTrue(on.enable_power_save)
    }

    @Test
    fun setConfigFlagsOnlyWhatChanges() {
        val c: DishConfig = DishCommand.SetConfig(snowMeltMode = SnowMeltMode.ALWAYS_OFF, swupdateRebootHour = 4, swupdateThreeDayDeferral = true).config()
        assertTrue(c.apply_snow_melt_mode && c.snow_melt_mode == 2)
        assertTrue(c.apply_swupdate_reboot_hour && c.swupdate_reboot_hour == 4)
        assertTrue(c.apply_swupdate_three_day_deferral_enabled && c.swupdate_three_day_deferral_enabled)
        assertFalse(c.apply_level_dish_mode)
        assertFalse(c.apply_location_request_mode)
        assertFalse(c.apply_power_save_mode)
        assertFailsWith<IllegalArgumentException> { DishCommand.SetConfig() }
        assertFailsWith<IllegalArgumentException> { DishCommand.SetConfig(swupdateRebootHour = 24) }
        val local = DishCommand.SetConfig(locationRequestMode = LocationRequestMode.LOCAL).config()
        assertTrue(local.apply_location_request_mode && local.location_request_mode == DishCallKind.LOCATION_MODE_LOCAL)
    }

    @Test
    fun aDishWithoutMotorsRefusesStowLikeAMiniDoes() {
        val mini = FakeDishSimulation(spoofEverySec = 0)
        val (response, status) = mini.handle(DishCallKind.STOW.request(1))
        assertNull(response)
        assertEquals("HAS_ACTUATORS_NO", status.second)
        assertFalse(mini.handle(DishCallKind.STATUS.request(2)).first!!.dish_get_status!!.stow_requested)
        assertTrue(mini.accepted.none { it == "dish_stow" })
    }

    @Test
    fun onlyAnUpdateThatRestartsTheDishHasTheRestartingKind() {
        assertEquals(DishCallKind.DISH_UPDATE, DishCommand.Update().kind)
        assertEquals(DishCallKind.DISH_UPDATE, DishCommand.Update(false).kind)
        assertEquals(DishCallKind.DISH_UPDATE_REBOOT, DishCommand.Update(true).kind)
        assertFalse(DishCallKind.DISH_UPDATE.restartsDish)
        assertTrue(DishCallKind.DISH_UPDATE_REBOOT.restartsDish)
        assertFalse(DishCallKind.ROUTER_UPDATE.restartsDish)
    }

    @Test
    fun updateSendsScheduleRebootOnlyWhenAsked() {
        val none = DeviceRequest.ADAPTER.encode(DishCommand.Update().request(1))
        val yes = DeviceRequest.ADAPTER.encode(DishCommand.Update(true).request(1))
        val no = DeviceRequest.ADAPTER.encode(DishCommand.Update(false).request(1))
        assertNull(DeviceRequest.ADAPTER.decode(none).update!!.schedule_reboot)
        assertEquals(true, DeviceRequest.ADAPTER.decode(yes).update!!.schedule_reboot)
        assertEquals(false, DeviceRequest.ADAPTER.decode(no).update!!.schedule_reboot)
    }

    @Test
    fun theSpeedTestNeverReportsToStarlink() {
        val req = RouterCommand.StartSpeedtest(15).request(1).start_speedtest!!
        assertEquals(15, req.duration_s)
        assertFalse(req.send_telemetry)
        assertFailsWith<IllegalArgumentException> { RouterCommand.StartSpeedtest(0) }
    }

    @Test
    fun fakeDishFollowsCommands() {
        val dish = FakeDishSimulation(spoofEverySec = 0, hasActuators = true)
        fun call(kind: DishCallKind) = dish.handle(kind.request(1))

        assertFalse(call(DishCallKind.STATUS).first!!.dish_get_status!!.stow_requested)
        call(DishCallKind.STOW)
        assertTrue(call(DishCallKind.STATUS).first!!.dish_get_status!!.stow_requested)
        call(DishCallKind.UNSTOW)
        assertFalse(call(DishCallKind.STATUS).first!!.dish_get_status!!.stow_requested)

        call(DishCallKind.INHIBIT_GPS)
        assertTrue(call(DishCallKind.STATUS).first!!.dish_get_status!!.gps_stats!!.inhibit_gps)
        call(DishCallKind.ALLOW_GPS)
        assertFalse(call(DishCallKind.STATUS).first!!.dish_get_status!!.gps_stats!!.inhibit_gps)

        // Local location access off: GetLocation refused, not as a UT disablement.
        val off = tools.apachi.starlinknav.core.dish.proto.DeviceRequest(
            id = 2,
            dish_set_config = tools.apachi.starlinknav.core.dish.proto.DishSetConfigRequest(
                tools.apachi.starlinknav.core.dish.proto.DishConfig(location_request_mode = 0, apply_location_request_mode = true),
            ),
        )
        dish.handle(off)
        val (resp, status) = call(DishCallKind.LOCATION_AUTO)
        assertEquals(null, resp)
        assertEquals(GrpcStatus.PERMISSION_DENIED, status.first)
        assertFalse(status.second.contains("disablement"))
        call(DishCallKind.ALLOW_LOCAL_LOCATION)
        assertEquals(DishCallKind.LOCATION_MODE_LOCAL, call(DishCallKind.CONFIG).first!!.dish_get_config!!.dish_config!!.location_request_mode)
        assertNotNull(call(DishCallKind.LOCATION_AUTO).first?.get_location)

        val history = assertNotNull(call(DishCallKind.HISTORY).first?.dish_get_history)
        assertEquals(900, history.pop_ping_latency_ms.size)
        val map = assertNotNull(call(DishCallKind.OBSTRUCTION_MAP).first?.dish_get_obstruction_map)
        assertEquals(map.num_rows * map.num_cols, map.snr.size)
    }
}
