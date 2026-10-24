package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.AuthOpen
import tools.apachi.starlinknav.core.dish.proto.AuthWpa2
import tools.apachi.starlinknav.core.dish.proto.AuthWpa2Wpa3
import tools.apachi.starlinknav.core.dish.proto.ClientReconnectingOftenMetadata
import tools.apachi.starlinknav.core.dish.proto.GetSpeedtestStatusResponse
import tools.apachi.starlinknav.core.dish.proto.SpeedtestStatus
import tools.apachi.starlinknav.core.dish.proto.UXEvent
import tools.apachi.starlinknav.core.dish.proto.WifiAlerts
import tools.apachi.starlinknav.core.dish.proto.WifiClient
import tools.apachi.starlinknav.core.dish.proto.WifiConfig
import tools.apachi.starlinknav.core.dish.proto.WifiSoftwareUpdateStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterViewTest {
    private fun client(id: Int, name: String = "", given: String = "", ip: String = "", mac: String = "", active: Boolean = true, blocked: Boolean = false, v6: List<String> = emptyList()) =
        WifiClient(client_id = id, name = name, given_name = given, ip_address = ip, mac_address = mac, active = active, blocked = blocked, ipv6_addresses = v6)

    @Test
    fun aClientIsCalledWhatTheUserNamedItElseItsOwnNameElseItsAddress() {
        assertEquals("Dad's phone", ClientList.displayName(client(1, name = "android-123", given = "Dad's phone")))
        assertEquals("android-123", ClientList.displayName(client(1, name = "android-123")))
        assertEquals("192.168.1.10", ClientList.displayName(client(1, ip = "192.168.1.10", mac = "02:00:00:00:01:01")))
        assertEquals("02:00:00:00:01:01", ClientList.displayName(client(1, mac = "02:00:00:00:01:01")))
        assertEquals("#7", ClientList.displayName(client(7)))
    }

    @Test
    fun clientsAreListedOnlineThenPausedThenOfflineByName() {
        val list = listOf(
            client(1, name = "zeta", active = false),
            client(2, name = "Bravo"),
            client(3, name = "alpha", active = false, blocked = true),
            client(4, name = "alpha2"),
        )
        assertEquals(listOf(4, 2, 3, 1), ClientList.sorted(list).map { it.client_id })
        assertEquals(ClientList.State.PAUSED, ClientList.state(list[2]))
        assertEquals(ClientList.State.OFFLINE, ClientList.state(list[0]))
    }

    @Test
    fun thePhoneIsTheClientWithOneOfItsOwnAddresses() {
        val c = client(1, ip = "192.168.1.10", v6 = listOf("2001:db8::10", "FE80::1"))
        assertTrue(ClientList.isThisPhone(c, listOf("192.168.1.10")))
        assertTrue(ClientList.isThisPhone(c, listOf("fe80::1%wlan0")), "an IPv6 address with a scope id and other case")
        assertFalse(ClientList.isThisPhone(c, listOf("192.168.1.11")))
        assertFalse(ClientList.isThisPhone(c, emptyList()), "no addresses known: not identifiable")
        assertFalse(ClientList.isThisPhone(client(2), listOf("")), "an empty address matches nothing")
    }

    @Test
    fun aWifiConfigIsShownWithItsBandsAuthAndPasswordsButNeverPrintsThem() {
        val cfg = WifiConfig(
            bypass_mode = true, nameservers = listOf("1.1.1.2", "1.0.0.2"), secure_dns = true, country_code = "UA",
            networks = listOf(
                WifiConfig.Network(
                    ipv4 = "192.168.1.1",
                    basic_service_sets = listOf(
                        WifiConfig.BasicServiceSet(ssid = "Car", band = WifiBand.RF_2GHZ, auth_wpa2_wpa3 = AuthWpa2Wpa3("secret-pw-1")),
                        WifiConfig.BasicServiceSet(ssid = "Car 5G", band = WifiBand.RF_5GHZ, hidden = true, auth_wpa2 = AuthWpa2("secret-pw-2")),
                    ),
                ),
                WifiConfig.Network(
                    guest = true, client_isolation = true,
                    basic_service_sets = listOf(WifiConfig.BasicServiceSet(ssid = "Guest", band = WifiBand.RF_2GHZ, auth_open = AuthOpen())),
                ),
            ),
        )
        val o = WifiOverview.of(cfg)
        assertEquals("192.168.1.1", o.main?.ipv4)
        assertEquals("Guest", o.guest?.bands?.single()?.ssid)
        assertFalse(o.main!!.oneName)
        assertFalse(o.main!!.hidden, "only one of two bands hidden")
        assertEquals(WifiAuth.WPA2_WPA3, o.main!!.band(WifiBand.RF_2GHZ)?.auth)
        assertEquals("secret-pw-2", o.main!!.band(WifiBand.RF_5GHZ)?.password)
        assertNull(o.guest!!.bands.single().password)
        assertEquals(WifiAuth.OPEN, o.guest!!.bands.single().auth)
        assertTrue(o.bypass && o.secureDns)
        assertEquals(DnsPreset.MALWARE, o.dnsPreset)
        for (text in listOf(o.toString(), o.main!!.band(WifiBand.RF_2GHZ).toString(), o.main!!.band(WifiBand.RF_5GHZ).toString())) {
            assertFalse("secret-pw" in text, text)
        }
    }

    @Test
    fun anEmptyPasswordIsNoPassword() {
        val cfg = WifiConfig(networks = listOf(WifiConfig.Network(basic_service_sets = listOf(WifiConfig.BasicServiceSet(ssid = "x", band = 2, auth_wpa2 = AuthWpa2(""))))))
        assertNull(WifiOverview.of(cfg).main!!.bands.single().password)
    }

    @Test
    fun whatIsTypedIsCheckedBeforeItBecomesAnEdit() {
        assertTrue(WifiInput.nameValid("STARLINK"))
        assertFalse(WifiInput.nameValid(""))
        assertTrue(WifiInput.nameValid("a".repeat(32)))
        assertFalse(WifiInput.nameValid("a".repeat(33)))
        assertTrue(WifiInput.nameValid("Авто".repeat(4)), "16 Cyrillic letters are 32 bytes")
        assertFalse(WifiInput.nameValid("Авто".repeat(4) + "я"))
        assertFalse(WifiInput.passwordValid("1234567"))
        assertTrue(WifiInput.passwordValid("12345678"))
        assertFalse(WifiInput.passwordValid("x".repeat(64)))
        // WPA passphrases are printable ASCII: a Cyrillic one (40 letters, 80 bytes) would lock Android phones out.
        assertFalse(WifiInput.passwordValid("пароль".repeat(7)))
        assertFalse(WifiInput.passwordValid("abcdefgh\t"))
        assertTrue(WifiInput.passwordValid("Pass word ~!@#\$%^&*()"))
        assertEquals(emptyList(), WifiInput.parseServers("  "))
        assertEquals(listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111"), WifiInput.parseServers("1.1.1.1, 8.8.8.8\n2606:4700:4700::1111"))
        assertNull(WifiInput.parseServers("1.1.1.1 dns.google"))
        assertNull(WifiInput.parseServers("1.1.1.1 1.1.1.2 1.1.1.3 1.1.1.4 1.1.1.5"))
        assertTrue(WifiInput.isIpv4Address(" 192.168.2.1 "))
        assertFalse(WifiInput.isIpv4Address("192.168.2"))
        // The same checks hold for the edits built from the typed values.
        WifiConfigEdit.SetDns(WifiInput.parseServers("9.9.9.9")!!)
        WifiConfigEdit.SetLanSubnet("192.168.2.1")
    }

    @Test
    fun aSuffixedNameStillFitsAWifiName() {
        assertEquals("Car-5G", WifiInput.withSuffix("Car", "-5G"))
        val long = WifiInput.withSuffix("x".repeat(32), "-5G")
        assertEquals(32, long.length)
        assertTrue(long.endsWith("-5G"))
        val cyrillic = WifiInput.withSuffix("Авто".repeat(4), "-5G")
        assertTrue(WifiInput.nameValid(cyrillic), "cut by characters, still valid UTF-8 within 32 bytes")
        assertTrue(cyrillic.endsWith("-5G"))
    }

    @Test
    fun anEventNamesItsClient() {
        assertEquals(5, RouterEvents.clientId(UXEvent(reason = 42, client_reconnecting_often_metadata = ClientReconnectingOftenMetadata(client_id = 5))))
        assertNull(RouterEvents.clientId(UXEvent(reason = 14)))
        assertEquals(2, ClientList.byId(listOf(client(1), client(2, name = "b")), 2)?.client_id)
    }

    @Test
    fun dropRatesAreBucketedAndShownAsPercent() {
        assertEquals(listOf(0.5f, 1f), RouterSeries.bucketMean(listOf(0f, 1f, 1f), 2))
        assertTrue(RouterSeries.bucketMean(listOf(Float.NaN, Float.NaN, 2f), 2)[0].isNaN())
        val pct = RouterSeries.percent(listOf(0.5f, -1f, Float.NaN, 0f))
        assertEquals(50f, pct[0])
        assertTrue(pct[1].isNaN() && pct[2].isNaN())
        assertEquals(0f, pct[3])
    }

    @Test
    fun theUpdateStateIsClassified() {
        val busy = RouterUpdateView.of(WifiSoftwareUpdateStats(state = 2, software_download_progress = 0.4f, running_version = "a", version_in_progress = "b"))!!
        assertTrue(busy.busy && !busy.failed && !busy.upToDate)
        assertEquals(0.4f, busy.progress)
        assertTrue(RouterUpdateView.of(WifiSoftwareUpdateStats(state = WifiSoftwareUpdate.REBOOT_PENDING))!!.rebootPending)
        assertTrue(RouterUpdateView.of(WifiSoftwareUpdateStats(state = 12))!!.failed)
        assertTrue(RouterUpdateView.of(WifiSoftwareUpdateStats(state = WifiSoftwareUpdate.NO_UPDATE_REQUIRED))!!.upToDate)
        assertNull(RouterUpdateView.of(null))
    }

    @Test
    fun aSpeedTestIsSummedUpPerDirection() {
        val running = SpeedtestView.of(
            GetSpeedtestStatusResponse(SpeedtestStatus(running = true, id = 3, down = SpeedtestStatus.Direction(throughputs_mbps = listOf(100f, 200f)), up = SpeedtestStatus.Direction())),
        )!!
        assertTrue(running.running && running.hasData && !running.failed)
        assertEquals(150f, running.downAvg)
        assertEquals(200f, running.downMax)
        assertNull(running.upAvg)
        val failed = SpeedtestView.of(GetSpeedtestStatusResponse(SpeedtestStatus(running = false, down = SpeedtestStatus.Direction(err = 5), up = SpeedtestStatus.Direction())))!!
        assertTrue(failed.failed && !failed.hasData)
        assertNull(SpeedtestView.of(null))
    }

    @Test
    fun theFakeRouterShowsEveryRouterAlertAndTheViewsReadItsAnswers() {
        val all = WifiAlerts(
            thermal_throttle = true, install_pending = true, freshly_fused = true, lan_eth_slow_link_10 = true, lan_eth_slow_link_100 = true,
            wan_eth_poor_connection = true, mesh_topology_changing_often = true, mesh_unreliable_backhaul = true, radius_missing_process = true,
            eth_switch_error = true, poe_on_dish_unreachable = true, poe_fuse_blown = true, poe_router_overcurrent = true, poe_off_current_nominal = true,
            poe_vin_overvoltage = true, poe_vin_undervoltage = true, high_cable_ping_drop_rate = true, sandbox_disabled = true,
            only_overflight_blocked = true, offline_networks_disabled = true, wired_mesh_not_using_wan_iface = true,
        )
        assertEquals(21, AlertList.router(all).size, "every WifiAlerts flag is listed by AlertList.router")
        val sim = FakeRouterSimulation()
        val client = RouterClient(FakeTransport(sim))
        val cfg = assertNotNull(client.call(DishCallKind.ROUTER_CONFIG).response?.wifi_get_config?.wifi_config)
        val view = WifiOverview.of(cfg)
        assertEquals("STARLINK", view.main?.bands?.first()?.ssid)
        assertEquals(FakeRouterSimulation.FAKE_PASSWORD, view.main?.bands?.first()?.password)
        assertTrue(view.main!!.oneName)
        val clients = assertNotNull(client.call(DishCallKind.ROUTER_CLIENTS).response?.wifi_get_clients).clients
        assertEquals(3, ClientList.sorted(clients).size)
        assertTrue(ClientList.isThisPhone(clients.first { it.name == "pixel-fake" }, listOf("192.168.1.10")))
    }
}
