package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DeviceRequest
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishPowerSaveRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeDeviceTest {
    private val sec = 1_000_000_000L

    /** A dish whose clocks the test moves. */
    private class Clocked {
        var nano = 0L
        var wall = 1_700_000_000_000L
    }

    private fun dish(c: Clocked = Clocked(), denySet: Boolean = false, unimplemented: Set<String> = emptySet(), update: Boolean = true, hasActuators: Boolean = false) =
        FakeDishSimulation(
            spoofEverySec = 0, denySetConfig = denySet, unimplemented = unimplemented, updateWaiting = update, startNs = 0,
            clock = { c.nano }, wallClockMs = { c.wall }, rebootDownSec = 40, hasActuators = hasActuators,
        )

    private fun client(sim: FakeDevice) = DishClient(FakeTransport(sim))

    @Test
    fun aRouteIsDrivenToItsEndAndBack() {
        // 110.54 m north, then 78.72 m east (0.001° of longitude at 45°): 189.26 m at 10 m/s.
        val sim = FakeDishSimulation(route = listOf(45.0 to 34.0, 45.001 to 34.0, 45.001 to 34.001), speedMps = 10.0, spoofEverySec = 0, startNs = 0, clock = { 0L })
        sim.truthAt(0.0).let { assertEquals(45.0, it.lat, 1e-9); assertEquals(34.0, it.lon, 1e-9); assertEquals(0.0, it.headingDeg, 1e-6) }
        sim.truthAt(5.527).let { assertEquals(45.0005, it.lat, 1e-7); assertEquals(34.0, it.lon, 1e-9) }
        sim.truthAt(15.0).let { assertEquals(45.001, it.lat, 1e-9); assertEquals(34.0005, it.lon, 1e-5); assertEquals(90.0, it.headingDeg, 1e-6) }
        sim.truthAt(18.92).let { assertEquals(45.001, it.lat, 1e-9); assertEquals(34.001, it.lon, 1e-6) }
        sim.truthAt(25.0).let { assertEquals(45.001, it.lat, 1e-9); assertEquals(270.0, it.headingDeg, 1e-6) }
        sim.truthAt(37.8).let { assertEquals(45.0, it.lat, 1e-5); assertEquals(34.0, it.lon, 1e-9); assertEquals(180.0, it.headingDeg, 1e-6) }
    }

    // ---- every new query answers on the dish, with the response tag the dump gives

    @Test
    fun everyDishQueryAnswersAndTheResponseArmHasTheDumpsTag() {
        val sim = dish()
        val dishClient = client(sim)
        val expectedTags = mapOf(
            DishCallKind.STATUS to 2004, DishCallKind.DEVICE_INFO to 1004, DishCallKind.DIAGNOSTICS to 6001, DishCallKind.CONFIG to 2011,
            DishCallKind.HISTORY to 2006, DishCallKind.OBSTRUCTION_MAP to 2008, DishCallKind.NETWORK_INTERFACES to 1015,
            DishCallKind.PING to 1009, DishCallKind.RADIO_STATS to 1035, DishCallKind.CONNECTIONS to 1023, DishCallKind.TIME to 1037,
            DishCallKind.SPEEDTEST_STATUS to 1028, DishCallKind.LOCATION_AUTO to 1017, DishCallKind.LOCATION_GPS to 1017, DishCallKind.LOCATION_STARLINK to 1017,
        )
        for (kind in DishCallKind.DISH_QUERIES) {
            val r = dishClient.call(kind)
            val tag = expectedTags[kind]
            if (tag == null) {
                // The sources a Mini does not have and the GNSS measurements: not supported.
                assertEquals(CallOutcome.UNIMPLEMENTED, r.outcome, "$kind ${r.statusText}")
                continue
            }
            assertTrue(r.ok, "$kind: ${r.statusText} ${r.grpcMessage}")
            assertEquals(tag, TestWire.armTag(r.raw!!), "$kind response arm")
        }
        assertEquals(DishCallKind.DISH_QUERIES.size - expectedTags.size, DishCallKind.DISH_QUERIES.count { it !in expectedTags })
    }

    @Test
    fun theDishAnswersTheRoutersCallsUnimplemented() {
        val d = client(dish())
        val r = DishClient(FakeTransport(dish()), target = CallTarget.ROUTER).call(DishCallKind.ROUTER_CLIENTS)
        assertEquals(CallOutcome.UNIMPLEMENTED, r.outcome)
        assertEquals("Unimplemented: *device.Request_WifiGetClients", r.grpcMessage)
        assertEquals(CallOutcome.UNIMPLEMENTED, DishClient(FakeTransport(dish()), target = CallTarget.ROUTER).call(DishCallKind.ROUTER_CONFIG).outcome)
        assertEquals(CallOutcome.UNIMPLEMENTED, DishClient(FakeTransport(dish()), target = CallTarget.ROUTER).call(DishCallKind.ROUTER_PERSISTENT_STATS).outcome)
        assertNotNull(d.call(DishCallKind.STATUS).response?.dish_get_status)
    }

    @Test
    fun commandTagsAreTheDumpsAndTheResponseTagsToo() {
        val sim = dish(denySet = false)
        val c = client(sim)
        fun arm(r: DishCallResult) = TestWire.armTag(r.raw!!)
        assertEquals(1001, arm(c.call(DishCallKind.REBOOT)))
        sim.handle(DishCallKind.STATUS.request(1))
        val c2 = client(dish())
        assertEquals(2013, arm(c2.call(DishCallKind.INHIBIT_GPS))) // request 2014 answers with 2013
        assertEquals(2013, arm(c2.call(DishCallKind.ALLOW_GPS)))
        assertEquals(2026, arm(c2.call(DishCallKind.INHIBIT_RF)))
        assertEquals(2015, arm(c2.call(DishCallKind.CLEAR_OBSTRUCTION_MAP))) // request 2017 answers with 2015
        assertEquals(2002, arm(client(dish(hasActuators = true)).call(DishCallKind.STOW)))
        assertEquals(2010, arm(c2.send(DishCommand.SetConfig(snowMeltMode = 1))))
        assertNull(TestWire.armTag(c2.send(DishCommand.SleepSchedule(SleepWindow(60, 60, true))).raw!!)) // no response arm
        assertEquals(1014, arm(c2.send(DishCommand.Update())))
        assertEquals(1016, arm(c2.send(DishCommand.PingHost("1.1.1.1"))))
        assertEquals(2021, arm(c2.call(DishCallKind.DISH_FACTORY_RESET)))
    }

    // ---- writes like a Mini probably does

    @Test
    fun denySetConfigAnswersPermissionDeniedAndChangesNothing() {
        val sim = dish(denySet = true)
        val c = client(sim)
        val r = c.call(DishCallKind.ALLOW_LOCAL_LOCATION)
        assertEquals(CallOutcome.PERMISSION_DENIED, r.outcome)
        assertEquals("Permission denied", r.grpcMessage)
        assertEquals(CallOutcome.PERMISSION_DENIED, c.send(DishCommand.SetConfig(snowMeltMode = SnowMeltMode.ALWAYS_ON)).outcome)
        assertEquals(0, c.call(DishCallKind.STATUS).response!!.dish_get_status!!.config!!.snow_melt_mode)
        // Reads and the other writes are not affected.
        assertTrue(c.call(DishCallKind.CONFIG).ok)
        assertTrue(c.call(DishCallKind.INHIBIT_GPS).ok)
        assertTrue(c.send(DishCommand.SleepSchedule(SleepWindow(0, 60, false))).ok)
        // Allowed: it changes.
        val open = dish(denySet = false)
        val oc = client(open)
        assertTrue(oc.send(DishCommand.SetConfig(snowMeltMode = SnowMeltMode.ALWAYS_ON, swupdateRebootHour = 5, swupdateThreeDayDeferral = true, levelDishMode = 1)).ok)
        val cfg = oc.call(DishCallKind.CONFIG).response!!.dish_get_config!!.dish_config!!
        assertEquals(1, cfg.snow_melt_mode)
        assertEquals(5, cfg.swupdate_reboot_hour)
        assertTrue(cfg.swupdate_three_day_deferral_enabled)
        assertEquals(1, cfg.level_dish_mode)
        assertEquals(5, oc.call(DishCallKind.STATUS).response!!.dish_get_status!!.config!!.swupdate_reboot_hour, "the status config carries the update hour")
    }

    @Test
    fun unimplementedListsSwitchCallsOff() {
        val c = client(dish(unimplemented = setOf("time", "get_radio_stats", "dish_power_save")))
        val t = c.call(DishCallKind.TIME)
        assertEquals(CallOutcome.UNIMPLEMENTED, t.outcome)
        assertEquals("Unimplemented: *device.Request_Time", t.grpcMessage)
        assertEquals("Unimplemented: *device.Request_GetRadioStats", c.call(DishCallKind.RADIO_STATS).grpcMessage)
        assertEquals(CallOutcome.UNIMPLEMENTED, c.send(DishCommand.SleepSchedule(SleepWindow(0, 60, true))).outcome)
        assertTrue(c.call(DishCallKind.PING).ok)
        // The names the command line accepts.
        assertEquals(setOf("time"), FakeArms.forToken("time"))
        assertEquals(setOf("get_radio_stats"), FakeArms.forToken("radio_stats"))
        assertEquals(setOf("get_radio_stats"), FakeArms.forToken("RADIO_STATS"))
        assertEquals(setOf("get_ping"), FakeArms.forToken("get_ping"))
        assertEquals(setOf("get_status"), FakeArms.forToken("router:status").intersect(setOf("get_status")))
        assertEquals(setOf("get_network_interfaces"), FakeArms.forToken("network_interfaces"))
        assertEquals(setOf("wifi_get_clients"), FakeArms.forToken("clients")) // the router label without its prefix
        assertEquals(setOf("wifi_get_clients"), FakeArms.forToken("router:clients"))
        assertEquals(emptySet(), FakeArms.forToken(" "))
    }

    // ---- state the commands change

    @Test
    fun sleepScheduleIsStoredInTheConfigAndPutsTheDishToSleep() {
        val clocks = Clocked()
        val sim = dish(clocks)
        val c = client(sim)
        clocks.nano = 100 * sec
        val minute = ((clocks.wall / 60_000L) % 1440).toInt()
        val window = SleepWindow((minute + 1440 - 5) % 1440, 120, true)
        assertTrue(c.send(DishCommand.SleepSchedule(window)).ok)
        val status = c.call(DishCallKind.STATUS).response!!.dish_get_status!!
        assertEquals(window, SleepWindow.of(status.config))
        assertEquals(DishLinkState.SLEEPING, DishStates.of(status).state)
        assertTrue(status.alerts!!.is_power_save_idle)
        assertEquals(0f, status.downlink_throughput_bps)
        // Power drops while asleep and the history shows it.
        val hist = DishHistory.of(c.call(DishCallKind.HISTORY).response!!.dish_get_history!!)
        assertTrue(hist.powerInW.last() < 10f)
        // Switched off: back to normal, window kept.
        assertTrue(c.send(DishCommand.SleepSchedule(window.withEnabled(false))).ok)
        val after = c.call(DishCallKind.STATUS).response!!.dish_get_status!!
        assertEquals(DishLinkState.CONNECTED, DishStates.of(after).state)
        assertEquals(window.withEnabled(false), SleepWindow.of(after.config))
        // The dish rejects a zero duration even when disabling (the command cannot send one; a raw request can).
        val raw = sim.handle(DeviceRequest(id = 9, dish_power_save = DishPowerSaveRequest(power_save_start_minutes = 0, power_save_duration_minutes = 0, enable_power_save = false)))
        assertEquals(3, raw.second.first)
        assertEquals(CallOutcome.OTHER, DishCallResult(DishCallKind.SLEEP_SCHEDULE, 0, 0, "t", 200, raw.second.first, raw.second.second, null, null, null).outcome)
    }

    @Test
    fun inhibitRfAndGpsAreReflectedInStatusHistoryAndEvents() {
        val clocks = Clocked()
        val sim = dish(clocks)
        val c = client(sim)
        clocks.nano = 1000 * sec
        c.call(DishCallKind.INHIBIT_GPS)
        c.call(DishCallKind.INHIBIT_RF)
        clocks.nano += 10 * sec
        clocks.wall += 10_000
        val s = c.call(DishCallKind.STATUS).response!!.dish_get_status!!
        assertTrue(s.gps_stats!!.inhibit_gps)
        assertFalse(s.gps_stats!!.gps_valid)
        assertEquals(14, s.outage?.cause)
        assertEquals(DishLinkState.RF_INHIBITED, DishStates.of(s).state)
        assertEquals(0f, s.downlink_throughput_bps)
        val hist = DishHistory.of(c.call(DishCallKind.HISTORY).response!!.dish_get_history!!)
        assertTrue(hist.outages.any { it.cause == 14 })
        assertTrue(hist.events.any { it.reason == 50 }, "OUTAGE_INHIBIT_RF in the event log")
        assertEquals("OUTAGE_INHIBIT_RF", EventReason.name(hist.events.first { it.reason == 50 }.reason))
        c.call(DishCallKind.ALLOW_RF)
        c.call(DishCallKind.ALLOW_GPS)
        val back = c.call(DishCallKind.STATUS).response!!.dish_get_status!!
        assertNull(back.outage)
        assertFalse(back.gps_stats!!.inhibit_gps)
    }

    @Test
    fun rebootAndUpdateRestartTheDishAndInstallTheWaitingUpdate() {
        val clocks = Clocked()
        val sim = dish(clocks)
        val c = client(sim)
        clocks.nano = 100 * sec
        val before = c.call(DishCallKind.STATUS).response!!.dish_get_status!!
        assertTrue(before.updateWaiting())
        assertTrue(before.alerts!!.install_pending)
        // update without schedule_reboot only asks.
        assertTrue(c.send(DishCommand.Update()).ok)
        assertFalse(sim.isDown())
        // With it the dish restarts for rebootDownSec and installs the update.
        assertTrue(c.send(DishCommand.Update(scheduleReboot = true)).ok)
        assertTrue(sim.isDown())
        assertEquals(CallOutcome.UNREACHABLE, c.call(DishCallKind.STATUS).outcome)
        clocks.nano += 41 * sec
        assertFalse(sim.isDown())
        val after = c.call(DishCallKind.STATUS).response!!.dish_get_status!!
        assertFalse(after.updateWaiting())
        assertEquals("fake-2026.09", after.device_info!!.software_version)
        assertEquals(3, after.reboot_reason) // SWUPDATE_NOW
        assertEquals(7, after.device_info!!.bootcount)
        assertEquals(4, after.device_info!!.boot!!.last_reason) // BootReason SOFTWARE_UPDATE
        // A plain reboot reports the app as the reason.
        assertTrue(c.call(DishCallKind.REBOOT).ok)
        clocks.nano += 41 * sec
        val again = c.call(DishCallKind.STATUS).response!!.dish_get_status!!
        assertEquals(5, again.reboot_reason)
        assertEquals(3, again.device_info!!.boot!!.last_reason)
        assertEquals(listOf("update", "update", "reboot"), sim.accepted.filter { it == "update" || it == "reboot" })
    }

    @Test
    fun factoryResetWipesTheSettingsAndRestarts() {
        val clocks = Clocked()
        val sim = dish(clocks)
        val c = client(sim)
        clocks.nano = 100 * sec
        c.send(DishCommand.SleepSchedule(SleepWindow(30, 30, true)))
        c.send(DishCommand.SetConfig(snowMeltMode = 1, swupdateRebootHour = 9))
        c.call(DishCallKind.INHIBIT_RF)
        assertTrue(c.call(DishCallKind.DISH_FACTORY_RESET).ok)
        assertTrue(sim.isDown())
        clocks.nano += 45 * sec
        val s = c.call(DishCallKind.STATUS).response!!.dish_get_status!!
        assertEquals(0, s.config!!.snow_melt_mode)
        assertEquals(3, s.config!!.swupdate_reboot_hour)
        assertFalse(s.config!!.power_save_mode)
        assertNull(s.outage?.takeIf { it.cause == 14 })
        assertEquals(7, s.reboot_reason)
    }

    @Test
    fun theDishStatusCarriesTheFieldsOfTheNewerFirmware() {
        val clocks = Clocked()
        val sim = dish(clocks)
        clocks.nano = 100 * sec
        val s = client(sim).call(DishCallKind.STATUS).response!!.dish_get_status!!
        assertEquals(HasActuators.NO, s.has_actuators)
        assertTrue(s.signal_quality > 0f)
        assertEquals(1, s.account_shard)
        assertEquals(2, s.nat_flag)
        assertFalse(s.treat_as_metered)
        assertTrue(s.has_signed_cals)
        assertNull(s.battery_stats)
        assertTrue(s.upsu_stats!!.dish_power in 25f..40f)
        assertEquals(s.upsu_stats!!.dish_power, s.aps_stats!!.dish_power)
        assertEquals(RateLimitReason.NO_LIMIT, s.dl_bandwidth_restricted_reason)
        assertEquals(listOf(RouterRole.CONTROLLER), s.downstream_routers.map { it.value_!!.role })
        assertFalse(s.ready_states!!.cady) // a Mini never raises it
        assertTrue(s.device_info!!.software_partitions_equal.not())
        // Diagnostics: self test, alert for the waiting update, alignment.
        val d = client(sim).call(DishCallKind.DIAGNOSTICS).response!!.dish_get_diagnostics!!
        assertEquals(HardwareSelfTest.PASSED, d.hardware_self_test)
        assertTrue(d.alerts!!.software_install_pending)
        assertNotNull(d.alignment_stats)
        assertEquals(3 * 3600, d.utc_offset_s)
        assertTrue(d.location!!.enabled)
        // time is the dish clock; interfaces, ping and connections have content.
        assertEquals(clocks.wall * 1_000_000L, client(sim).call(DishCallKind.TIME).response!!.time!!.unix_nano)
        val ifs = client(sim).call(DishCallKind.NETWORK_INTERFACES).response!!.get_network_interfaces!!.network_interfaces
        assertEquals(1000, ifs.first { it.name == "eth0" }.ethernet!!.speed_mbps)
        assertEquals(setOf("pop", "dns"), client(sim).call(DishCallKind.PING).response!!.get_ping!!.results.map { it.target_name }.toSet())
        assertEquals(3, client(sim).call(DishCallKind.CONNECTIONS).response!!.get_connections!!.services.size)
        assertEquals(0, client(sim).call(DishCallKind.RADIO_STATS).response!!.get_radio_stats!!.radio_stats.size)
        // The history carries an obstruction a few minutes back, in the outages and the event log.
        val h = DishHistory.of(client(sim).call(DishCallKind.HISTORY).response!!.dish_get_history!!)
        assertTrue(h.outages.any { it.cause == 6 })
        assertTrue(h.events.any { it.reason == 7 })
    }

    @Test
    fun clearingTheObstructionMapLeavesLittleObserved() {
        val c = client(dish())
        val full = Obstruction.analyze(c.call(DishCallKind.OBSTRUCTION_MAP).response!!.dish_get_obstruction_map!!)!!
        assertTrue(c.call(DishCallKind.CLEAR_OBSTRUCTION_MAP).ok)
        val cleared = Obstruction.analyze(c.call(DishCallKind.OBSTRUCTION_MAP).response!!.dish_get_obstruction_map!!)!!
        assertTrue(cleared.observed < full.observed / 4)
        assertEquals(0, cleared.blocked)
    }

    // ---- the fake router

    @Test
    fun everyRouterQueryAnswersWithTheDumpsTag() {
        val sim = FakeRouterSimulation()
        val c = RouterClient(FakeTransport(sim))
        val tags = mapOf(
            DishCallKind.ROUTER_STATUS to 3004, DishCallKind.ROUTER_HISTORY to 3006, DishCallKind.ROUTER_DEVICE_INFO to 1004,
            DishCallKind.ROUTER_DIAGNOSTICS to 6000, DishCallKind.ROUTER_CLIENTS to 3002, DishCallKind.ROUTER_CONFIG to 3009,
            DishCallKind.ROUTER_PING_METRICS to 3007, DishCallKind.ROUTER_NETWORK_INTERFACES to 1015, DishCallKind.ROUTER_PING to 1009,
            DishCallKind.ROUTER_RADIO_STATS to 1035, DishCallKind.ROUTER_CONNECTIONS to 1023, DishCallKind.ROUTER_TIME to 1037,
            DishCallKind.ROUTER_SPEEDTEST_STATUS to 1028, DishCallKind.ROUTER_PERSISTENT_STATS to 3022, DishCallKind.ROUTER_GUEST_INFO to 3020,
        )
        // The router has no position to give: get_location is UNIMPLEMENTED there, with no coordinates in any case.
        assertEquals(DishCallKind.ROUTER_QUERIES.toSet() - DishCallKind.ROUTER_LOCATION, tags.keys)
        assertEquals(CallOutcome.UNIMPLEMENTED, c.call(DishCallKind.ROUTER_LOCATION).outcome)
        for ((kind, tag) in tags) {
            val r = c.call(kind)
            assertTrue(r.ok, "$kind: ${r.statusText} ${r.grpcMessage}")
            assertEquals(tag, TestWire.armTag(r.raw!!), "$kind")
        }
    }

    @Test
    fun theRouterStatusHasClientsConfigAndTheFieldsOfTheResearch() {
        val c = RouterClient(FakeTransport(FakeRouterSimulation()))
        val s = c.call(DishCallKind.ROUTER_STATUS).response!!.wifi_get_status!!
        assertEquals(3, s.clients.size)
        assertTrue(s.clients.all { it.mac_address.startsWith("02:") && it.ip_address.startsWith("192.168.1.") })
        assertEquals(setOf(1, 2, 3), s.clients.map { it.iface }.toSet())
        assertEquals("STARLINK", s.config!!.networks[0].basic_service_sets[0].ssid)
        assertEquals(FakeRouterSimulation.FAKE_PASSWORD, s.config!!.networks[0].basic_service_sets[0].auth_wpa2_wpa3!!.password)
        assertEquals("203.0.113.7", s.public_ipv4)
        assertEquals(1, s.dish_disablement_code)
        assertTrue(s.utc_ns > 0)
        assertEquals(WifiSoftwareUpdate.NO_UPDATE_REQUIRED, s.software_update_stats!!.state)
        assertEquals(SetupRequirement.NOT_REQUIRED, s.setup_requirement!!.state)
        assertEquals(1, s.dhcp_servers.size)
        assertEquals(3, s.dhcp_servers[0].leases.size)
        assertTrue(s.rf_2ghz_status!!.chan_busy_time_fraction > 0f)
        assertTrue(s.device_info!!.dish_cohoused)
        assertEquals(1021, s.device_info!!.hardware_index)
        val diag = c.call(DishCallKind.ROUTER_DIAGNOSTICS).response!!.wifi_get_diagnostics!!
        assertEquals(1, diag.networks[0].clients_2ghz)
        val m = c.call(DishCallKind.ROUTER_PING_METRICS).response!!.wifi_get_ping_metrics!!.internet!!
        assertTrue(m.latency_mean_ms > 0f)
        assertEquals(2, c.call(DishCallKind.ROUTER_RADIO_STATS).response!!.get_radio_stats!!.radio_stats.size)
        // The router has no dish calls.
        val dishCall = RouterClient(FakeTransport(FakeRouterSimulation()))
        assertTrue(dishCall.call(DishCallKind.ROUTER_TIME).ok)
    }

    @Test
    fun theRouterGoesSilentWhileItRebootsAndComesBackWithTheEvent() {
        val clocks = Clocked()
        val sim = FakeRouterSimulation(startNs = 0, clock = { clocks.nano }, wallClockMs = { clocks.wall }, rebootDownSec = 30)
        val c = RouterClient(FakeTransport(sim))
        clocks.nano = 100 * sec
        val up = c.call(DishCallKind.ROUTER_STATUS).response!!.wifi_get_status!!.device_state!!.uptime_s
        assertTrue(c.call(DishCallKind.ROUTER_REBOOT).ok)
        assertTrue(sim.isDown())
        assertEquals(CallOutcome.UNREACHABLE, c.call(DishCallKind.ROUTER_STATUS).outcome)
        clocks.nano += 31 * sec
        clocks.wall += 31_000
        val back = c.call(DishCallKind.ROUTER_STATUS).response!!.wifi_get_status!!
        assertTrue(back.device_state!!.uptime_s < up)
        assertEquals(4, back.device_info!!.bootcount)
        assertEquals(14, RouterHistory.of(c.call(DishCallKind.ROUTER_HISTORY).response!!.wifi_get_history!!).events.first().reason)
    }

    @Test
    fun theSpeedTestRunsThenHasAResult() {
        val clocks = Clocked()
        val sim = FakeRouterSimulation(startNs = 0, clock = { clocks.nano }, speedtestSec = 6)
        val c = RouterClient(FakeTransport(sim))
        fun status() = c.call(DishCallKind.ROUTER_SPEEDTEST_STATUS).response!!.get_speedtest_status!!.status!!
        assertFalse(status().running)
        assertEquals(0, status().down!!.throughputs_mbps.size)
        clocks.nano = 100 * sec
        assertTrue(c.send(RouterCommand.StartSpeedtest(6)).ok)
        clocks.nano += 3 * sec
        assertTrue(status().running)
        assertEquals(3, status().down!!.throughputs_mbps.size)
        clocks.nano += 4 * sec
        val done = status()
        assertFalse(done.running)
        assertEquals(6, done.down!!.throughputs_mbps.size)
        assertTrue(done.down!!.throughputs_mbps.all { it > 100f })
        assertEquals(1, done.id)
        assertEquals(0, done.down!!.err)
    }

    @Test
    fun factoryResetBringsTheRouterBackToItsDefaults() {
        val clocks = Clocked()
        val sim = FakeRouterSimulation(startNs = 0, clock = { clocks.nano }, wallClockMs = { clocks.wall })
        val c = RouterClient(FakeTransport(sim))
        clocks.nano = 100 * sec
        val base = RouterCommand.configOf(c.call(DishCallKind.ROUTER_CONFIG).response)!!
        c.send(RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.SetNetworkName("Car"), WifiConfigEdit.SetBypassMode(true))))
        assertEquals("Car", sim.currentConfig().networks[0].basic_service_sets[0].ssid)
        assertTrue(c.call(DishCallKind.ROUTER_STATUS).response!!.wifi_get_status!!.clients.isEmpty(), "bypass mode turns the Wi-Fi off")
        assertTrue(c.call(DishCallKind.ROUTER_FACTORY_RESET).ok)
        clocks.nano += 31 * sec
        assertEquals("STARLINK", sim.currentConfig().networks[0].basic_service_sets[0].ssid)
        assertFalse(sim.currentConfig().bypass_mode)
        assertEquals(3, c.call(DishCallKind.ROUTER_CLIENTS).response!!.wifi_get_clients!!.clients.size)
    }

    @Test
    fun theRouterUnimplementedListAndDishArms() {
        val c = RouterClient(FakeTransport(FakeRouterSimulation(unimplemented = setOf("get_radio_stats"))))
        assertEquals(CallOutcome.UNIMPLEMENTED, c.call(DishCallKind.ROUTER_RADIO_STATS).outcome)
        assertTrue(c.call(DishCallKind.ROUTER_PING).ok)
        // A dish request sent to the router address.
        val r = FakeRouterSimulation().handle(DishCallKind.LOCATION_AUTO.request(1))
        assertEquals(GrpcStatus.UNIMPLEMENTED, r.second.first)
        assertEquals(GrpcStatus.UNIMPLEMENTED, FakeRouterSimulation().handle(DishCallKind.OBSTRUCTION_MAP.request(1)).second.first)
        assertNull(r.first)
        assertTrue(FakeRouterSimulation().handle(DishCallKind.REBOOT.request(1)).first is DeviceResponse)
    }

    @Test
    fun theRouterUpdateShowsAsDownloading() {
        val clocks = Clocked()
        val sim = FakeRouterSimulation(startNs = 0, clock = { clocks.nano })
        val c = RouterClient(FakeTransport(sim))
        clocks.nano = 50 * sec
        assertTrue(c.send(RouterCommand.Update()).ok)
        clocks.nano += 4 * sec
        val s = c.call(DishCallKind.ROUTER_STATUS).response!!.wifi_get_status!!
        assertEquals(WifiSoftwareUpdate.name(2), WifiSoftwareUpdate.name(s.software_update_stats!!.state))
        assertTrue(s.alerts!!.install_pending)
        clocks.nano += 10 * sec
        assertEquals(WifiSoftwareUpdate.NO_UPDATE_REQUIRED, c.call(DishCallKind.ROUTER_STATUS).response!!.wifi_get_status!!.software_update_stats!!.state)
    }
}
