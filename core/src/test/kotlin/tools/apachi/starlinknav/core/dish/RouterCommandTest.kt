package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.ClientConfig
import tools.apachi.starlinknav.core.dish.proto.DeviceRequest
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.WeeklyBlockSchedule
import tools.apachi.starlinknav.core.dish.proto.WifiConfig
import tools.apachi.starlinknav.core.dish.proto.WifiGetConfigResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterCommandTest {
    /** The config as a router sent it: bytes in, decoded, with fields this build does not know. */
    private fun readFromRouter(): WifiConfig {
        val bytes = DeviceResponse.ADAPTER.encode(DeviceResponse(wifi_get_config = WifiGetConfigResponse(secretConfig())))
        return RouterCommand.configOf(DeviceResponse.ADAPTER.decode(bytes))!!
    }

    private fun requestBytes(c: RouterCommand) = DeviceRequest.ADAPTER.encode(c.request(1))

    private fun network(c: WifiConfig, i: Int = 0) = c.networks[i]

    @Test
    fun wireKeepsUnknownFieldsThroughAReadAndWriteBack() {
        val original = DeviceResponse.ADAPTER.encode(DeviceResponse(wifi_get_config = WifiGetConfigResponse(secretConfig())))
        val read = DeviceResponse.ADAPTER.decode(original)
        val config = read.wifi_get_config!!.wifi_config!!
        // Unknown fields are held at every level of the message...
        assertTrue(config.unknownFields.size > 0, "top level")
        assertTrue(config.networks[0].unknownFields.size > 0, "network")
        assertTrue(config.networks[0].basic_service_sets[0].unknownFields.size > 0, "basic service set")
        // ...and come out again byte for byte.
        val again = DeviceResponse.ADAPTER.encode(read)
        assertEquals(original.toList(), again.toList())
        assertTrue(TestWire.contains(again, "net-unknown"))
        assertTrue(TestWire.contains(again, "bss-unknown"))
        assertTrue(TestWire.contains(again, IN_UNKNOWN))
    }

    @Test
    fun theRiskAndWhatTheUserDoesAfterwardsComeFromTheEdits() {
        val guest = WifiConfigEdit.AddGuestNetwork("Guests", "guest-pass-1")
        // Known before the config is read, so the confirmation can use it.
        assertEquals(CommandRisk.DISRUPTIVE, RouterCommand.SetConfig.riskOf(listOf(guest)))
        assertEquals(CommandRisk.DISRUPTIVE, RouterCommand.SetConfig.riskOf(emptyList()))
        assertEquals(CommandRisk.DISRUPTIVE, RouterCommand.SetConfig.riskOf(listOf(WifiConfigEdit.SetBypassMode(false))))
        assertEquals(CommandRisk.DESTRUCTIVE, RouterCommand.SetConfig.riskOf(listOf(WifiConfigEdit.SetHidden(true), WifiConfigEdit.SetBypassMode(true))))
        val command = RouterCommand.SetConfig(readFromRouter().read(), listOf(WifiConfigEdit.SetBypassMode(true)))
        assertEquals(command.risk, RouterCommand.SetConfig.riskOf(command.edits))

        fun after(vararg edits: WifiConfigEdit) = RouterCommand.SetConfig.afterwardsOf(edits.toList())
        assertEquals(WifiAfterwards.JOIN_AGAIN_NEW_CREDENTIALS, after(WifiConfigEdit.SetNetworkName("New")))
        assertEquals(WifiAfterwards.JOIN_AGAIN_NEW_CREDENTIALS, after(WifiConfigEdit.SetPassword("new-pass-123")))
        assertEquals(WifiAfterwards.MAY_DROP, after(WifiConfigEdit.SetDns(listOf("1.1.1.3"))))
        assertEquals(WifiAfterwards.MAY_DROP, after(WifiConfigEdit.SetLanSubnet("192.168.2.1")))
        assertEquals(WifiAfterwards.MAY_DROP, after(WifiConfigEdit.SetHidden(true)))
        assertEquals(WifiAfterwards.WIFI_OFF, after(WifiConfigEdit.SetBypassMode(true)))
        // Name edits of the guest network, showing a hidden name again, adding a guest network: the phone is not affected.
        assertEquals(WifiAfterwards.NOTHING, after(WifiConfigEdit.SetNetworkName("G", which = WifiConfigEdit.Which.GUEST)))
        assertEquals(WifiAfterwards.NOTHING, after(WifiConfigEdit.SetHidden(false)))
        assertEquals(WifiAfterwards.NOTHING, after(guest))
        // A name and a DNS change together: the name is what the phone has to act on.
        assertEquals(WifiAfterwards.JOIN_AGAIN_NEW_CREDENTIALS, after(WifiConfigEdit.SetDns(emptyList()), WifiConfigEdit.SetNetworkName("New")))
        // Bypass wins over everything: the Wi-Fi is gone.
        assertEquals(WifiAfterwards.WIFI_OFF, after(WifiConfigEdit.SetNetworkName("New"), WifiConfigEdit.SetBypassMode(true)))
    }

    @Test
    fun aRenameStartsFromTheRoutersOwnConfigAndKeepsWhatItDoesNotUnderstand() {
        val base = readFromRouter()
        val command = RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.SetNetworkName("My Car")))
        val sent = DeviceRequest.ADAPTER.decode(requestBytes(command))
        val config = assertNotNull(sent.wifi_set_config?.wifi_config)
        assertTrue(config.apply_networks)
        assertEquals(listOf("My Car", "My Car"), network(config).basic_service_sets.map { it.ssid })
        // The guest network is not touched.
        assertEquals(listOf("STARLINK-GUEST", "corp"), network(config, 1).basic_service_sets.map { it.ssid })
        // Passwords, BSSIDs and the nested fields of the router's own messages travel back unchanged.
        assertEquals(PW, network(config).basic_service_sets[0].auth_wpa2_wpa3?.password)
        assertEquals("02:00:00:00:00:0a", network(config).basic_service_sets[0].bssid)
        val bytes = requestBytes(command)
        assertTrue(TestWire.contains(bytes, "net-unknown"), "unknown field of the network was dropped")
        assertTrue(TestWire.contains(bytes, "bss-unknown"), "unknown field of the BSS was dropped")
        // Only the edited setting is in the request: no other apply flag, no stray top-level fields.
        assertFalse(config.apply_nameservers || config.apply_bypass_mode || config.apply_disable_2ghz || config.apply_client_configs || config.apply_setup_complete)
        assertEquals("", config.country_code)
        assertEquals(0, config.unknownFields.size)
        assertFalse(TestWire.contains(bytes, IN_UNKNOWN))
        // The base itself is not modified.
        assertEquals("STARLINK", network(base).basic_service_sets[0].ssid)
    }

    @Test
    fun aBandNameChangesOnlyThatBand() {
        val base = readFromRouter()
        val config = RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.SetNetworkName("Car 5G", band = WifiBand.RF_5GHZ))).config()
        assertEquals(listOf("STARLINK", "Car 5G"), network(config).basic_service_sets.map { it.ssid })
    }

    @Test
    fun aPasswordKeepsTheAuthKindOpensUpOpenBandsAndClearsTheBssid() {
        val base = readFromRouter()
        val config = RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.SetPassword("new-password-123"))).config()
        val (b0, b1) = network(config).basic_service_sets
        assertEquals("new-password-123", b0.auth_wpa2_wpa3?.password)
        assertEquals("new-password-123", b1.auth_wpa2?.password)
        assertNull(b1.auth_wpa2_wpa3)
        assertEquals("", b0.bssid)
        assertEquals("", b1.bssid)
        assertEquals("STARLINK", b0.ssid)
        // A band that was open becomes WPA2/WPA3 with the password.
        val open = WifiConfig(networks = listOf(WifiConfig.Network(basic_service_sets = listOf(WifiConfig.BasicServiceSet(ssid = "open", band = 2, auth_open = tools.apachi.starlinknav.core.dish.proto.AuthOpen())))))
        val secured = RouterCommand.SetConfig(open.read(), listOf(WifiConfigEdit.SetPassword("12345678"))).config().networks[0].basic_service_sets[0]
        assertEquals("12345678", secured.auth_wpa2_wpa3?.password)
        assertNull(secured.auth_open)
        assertFailsWith<IllegalArgumentException> { WifiConfigEdit.SetPassword("short") }
        assertFailsWith<IllegalArgumentException> { WifiConfigEdit.SetPassword("x".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { WifiConfigEdit.SetPassword("парольпароль") }
        assertFailsWith<IllegalArgumentException> { WifiConfigEdit.AddGuestNetwork("Guests", "гостьовийпароль") }
        assertFailsWith<IllegalArgumentException> { WifiConfigEdit.SetNetworkName("") }
        assertFailsWith<IllegalArgumentException> { WifiConfigEdit.SetNetworkName("x".repeat(33)) }
    }

    @Test
    fun editsComposeOnTheSameNetwork() {
        val base = readFromRouter()
        val command = RouterCommand.SetConfig(
            base.read(),
            listOf(WifiConfigEdit.SetNetworkName("Car"), WifiConfigEdit.SetPassword("another-pass-1"), WifiConfigEdit.SetHidden(true, band = WifiBand.RF_2GHZ)),
        )
        val config = command.config()
        val bss = network(config).basic_service_sets
        assertEquals(listOf("Car", "Car"), bss.map { it.ssid })
        assertEquals(listOf(true, false), bss.map { it.hidden })
        assertEquals("another-pass-1", bss[0].auth_wpa2_wpa3?.password)
        assertTrue(command.dropsWifiClients)
        assertEquals(CommandRisk.DISRUPTIVE, command.risk)
    }

    @Test
    fun topLevelSettingsCarryTheirOwnApplyFlagOnly() {
        val base = readFromRouter()
        fun cfg(vararg e: WifiConfigEdit) = RouterCommand.SetConfig(base.read(), e.toList()).config()

        val band = cfg(WifiConfigEdit.SetBandDisabled(WifiBand.RF_5GHZ_HIGH, true))
        assertTrue(band.disable_5ghz_high && band.apply_disable_5ghz_high)
        assertFalse(band.apply_disable_2ghz || band.apply_disable_5ghz || band.apply_networks)
        val off2g = cfg(WifiConfigEdit.SetBandDisabled(WifiBand.RF_2GHZ, false))
        assertTrue(off2g.apply_disable_2ghz && !off2g.disable_2ghz)
        assertFailsWith<IllegalArgumentException> { WifiConfigEdit.SetBandDisabled(3, true) }

        val dns = cfg(DnsPreset.MALWARE_AND_ADULT.edit(secureDns = true))
        assertEquals(listOf("1.1.1.3", "1.0.0.3"), dns.nameservers)
        assertTrue(dns.apply_nameservers && dns.secure_dns && dns.apply_secure_dns)
        assertEquals(DnsPreset.MALWARE_AND_ADULT, DnsPreset.of(dns.nameservers))
        assertEquals(DnsPreset.NONE, DnsPreset.of(cfg(DnsPreset.NONE.edit()).nameservers))
        assertNull(DnsPreset.of(listOf("9.9.9.9")))
        assertEquals(listOf("2606:4700:4700::1111"), cfg(WifiConfigEdit.SetDns(listOf("2606:4700:4700::1111"))).nameservers)
        assertFailsWith<IllegalArgumentException> { WifiConfigEdit.SetDns(listOf("not-an-ip")) }
        assertFailsWith<IllegalArgumentException> { WifiConfigEdit.SetDns(List(5) { "1.1.1.1" }) }

        val lan = cfg(WifiConfigEdit.SetLanSubnet("192.168.3.1"))
        assertEquals("192.168.3.1", lan.networks[0].ipv4)
        assertEquals("", base.networks[0].ipv4.takeIf { it != "192.168.1.1" }.orEmpty())
        assertFailsWith<IllegalArgumentException> { WifiConfigEdit.SetLanSubnet("192.168.300.1") }
        assertFailsWith<IllegalArgumentException> { WifiConfigEdit.SetLanSubnet("router") }
    }

    @Test
    fun bypassModeIsDestructiveOnlyWhenSwitchedOn() {
        val base = readFromRouter()
        val on = RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.SetBypassMode(true)))
        assertEquals(CommandRisk.DESTRUCTIVE, on.risk)
        assertTrue(on.dropsWifiClients)
        assertTrue(on.config().bypass_mode && on.config().apply_bypass_mode)
        val off = RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.SetBypassMode(false)))
        assertEquals(CommandRisk.DISRUPTIVE, off.risk)
        assertFalse(off.dropsWifiClients)
        // A hidden guest network does not drop anyone.
        assertFalse(RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.SetHidden(true, which = WifiConfigEdit.Which.GUEST))).dropsWifiClients)
        assertFailsWith<IllegalArgumentException> { RouterCommand.SetConfig(base.read(), emptyList()) }
    }

    @Test
    fun guestNetworksAreAddedAndRemovedWithoutTouchingTheMainOne() {
        val base = readFromRouter()
        val renamed = RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.SetNetworkName("Guests", which = WifiConfigEdit.Which.GUEST))).config()
        assertEquals(listOf("STARLINK", "STARLINK"), renamed.networks[0].basic_service_sets.map { it.ssid })
        assertEquals(listOf("Guests", "Guests"), renamed.networks[1].basic_service_sets.map { it.ssid })
        val removed = RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.RemoveGuestNetwork)).config()
        assertEquals(1, removed.networks.size)
        assertTrue(removed.apply_networks)
        val added = RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.RemoveGuestNetwork, WifiConfigEdit.AddGuestNetwork("Visitors", "visitor-pass-1"))).config()
        assertEquals(2, added.networks.size)
        assertTrue(added.networks[1].guest && added.networks[1].client_isolation)
        assertEquals(listOf(WifiBand.RF_2GHZ, WifiBand.RF_5GHZ), added.networks[1].basic_service_sets.map { it.band })
        assertEquals("visitor-pass-1", added.networks[1].basic_service_sets[0].auth_wpa2_wpa3?.password)
        // Editing a guest network that is not there is an error, not a silent no-op.
        val noGuest = WifiConfig(networks = listOf(base.networks[0]))
        assertFailsWith<IllegalArgumentException> { RouterCommand.SetConfig(noGuest.read(), listOf(WifiConfigEdit.SetNetworkName("x", which = WifiConfigEdit.Which.GUEST))).config() }
        assertFailsWith<IllegalArgumentException> { RouterCommand.SetConfig(WifiConfig().read(), listOf(WifiConfigEdit.SetNetworkName("x"))).config() }
    }

    @Test
    fun pausingAClientKeepsTheOtherEntriesAndRefusesThePhoneItself() {
        val other = ClientConfig(client_id = 9, mac_address = "02:00:00:00:09:09", given_name = "tv")
        val base = WifiConfig(client_configs = listOf(other))
        val block = RouterCommand.BlockClient(base.read(), "02:00:00:00:01:02", block = true, clientId = 2, ownMac = "02:00:00:00:01:01")
        val config = block.config()
        assertTrue(config.apply_client_configs)
        assertEquals(listOf("02:00:00:00:09:09", "02:00:00:00:01:02"), config.client_configs.map { it.mac_address })
        val entry = config.client_configs.last()
        assertEquals(2, entry.client_id)
        assertEquals(1, entry.weekly_block_schedules.size)
        assertEquals(listOf(WeeklyBlockSchedule.BlockRange(0, RouterCommand.BlockClient.WEEK_MINUTES)), entry.weekly_block_schedules[0].block_ranges)
        assertEquals(CommandRisk.DISRUPTIVE, block.risk)
        // Resuming keeps the entry (its name) and empties the schedule.
        val paused = WifiConfig(client_configs = listOf(entry.copy(given_name = "laptop")))
        val resume = RouterCommand.BlockClient(paused.read(), "02:00:00:00:01:02".uppercase(), block = false).config()
        assertEquals(1, resume.client_configs.size)
        assertEquals("laptop", resume.client_configs[0].given_name)
        assertEquals(emptyList(), resume.client_configs[0].weekly_block_schedules)
        // The phone itself.
        assertFailsWith<IllegalArgumentException> { RouterCommand.BlockClient(base.read(), "02:00:00:00:01:01", block = true, ownMac = "02:00:00:00:01:01") }
        assertFailsWith<IllegalArgumentException> { RouterCommand.BlockClient(base.read(), "02:00:00:00:01:01".uppercase(), block = true, ownMac = "02:00:00:00:01:01") }
        RouterCommand.BlockClient(base.read(), "02:00:00:00:01:01", block = false, ownMac = "02:00:00:00:01:01") // resuming is fine
        assertFailsWith<IllegalArgumentException> { RouterCommand.BlockClient(base.read(), "nonsense", block = true) }
    }

    @Test
    fun renamingAPausedClientKeepsItPaused() {
        val paused = ClientConfig(
            client_id = 2, mac_address = "02:00:00:00:01:02", given_name = "laptop", group_id = "kids",
            weekly_block_schedules = listOf(WeeklyBlockSchedule(block_ranges = listOf(WeeklyBlockSchedule.BlockRange(0, 10080)))),
        )
        val base = WifiConfig(client_configs = listOf(paused)).read()
        val rename = RouterCommand.SetClientGivenName(base, "02:00:00:00:01:02".uppercase(), "Dad's laptop", clientId = 2)
        val sent = rename.request(5).wifi_set_client_given_name!!
        assertEquals("Dad's laptop", sent.client_name?.given_name)
        assertEquals("Dad's laptop", sent.client_config?.given_name)
        assertEquals(paused.weekly_block_schedules, sent.client_config?.weekly_block_schedules)
        assertEquals("kids", sent.client_config?.group_id)
        // A client with no entry yet gets a plain one.
        val fresh = RouterCommand.SetClientGivenName(base, "02:00:00:00:01:09", "tv", clientId = 9).request(6).wifi_set_client_given_name!!
        assertEquals(emptyList(), fresh.client_config?.weekly_block_schedules)
        assertEquals(9, fresh.client_config?.client_id)
    }

    @Test
    fun aConfigWriteStartsOnlyFromAnUnredactedRead() {
        val sim = FakeRouterSimulation()
        val client = RouterClient(FakeTransport(sim))
        val live = client.call(DishCallKind.ROUTER_CONFIG)
        val base = assertNotNull(RouterConfigRead.of(live), "the read that came from the router")
        assertEquals(PW_OF_FAKE, base.config.networks[0].basic_service_sets[0].auth_wpa2_wpa3?.password)
        assertFalse(PW_OF_FAKE in base.toString())
        assertNotNull(RouterConfigRead.of(client.call(DishCallKind.ROUTER_STATUS))) // wifi_get_status.config counts too

        // Every redacted route to a config is refused: the copies hold empty passwords and no unknown fields.
        assertNull(RouterConfigRead.of(live.redacted()))
        assertNull(RouterConfigRead.of(DishCallResult.fromRecord(live.toRecord())!!))
        val snapshot = DebugDataBuilder.results(DebugDataBuilder.decode(DebugDataBuilder.toJson(emptyList(), listOf(live), DebugDataBuilder.Options(removeIdentifiers = false))).router)
        assertNull(RouterConfigRead.of(snapshot.first { it.kind == DishCallKind.ROUTER_CONFIG }))
        assertNull(RouterConfigRead.of(Redact.config(base.config)), "a config with passwords cleared")
        assertNull(RouterConfigRead.of(Redact.response(live.response!!).let { DishCallResult(DishCallKind.ROUTER_CONFIG, 0, 0, "t", 200, 0, null, null, it, null) }) )
        // A reply that failed, or a dish reply, is not a router config.
        assertNull(RouterConfigRead.of(DishCallResult.failure(DishCallKind.ROUTER_CONFIG, 0, 0, "t", "x")))
        assertNull(RouterConfigRead.of(DishClient(FakeTransport(FakeDishSimulation())).call(DishCallKind.CONFIG)))

        // And a redacted base never reaches a request: starting a write from it is impossible by construction.
        val edited = RouterCommand.SetConfig(base, listOf(WifiConfigEdit.SetNetworkName("Car"))).request(1)
        assertEquals(PW_OF_FAKE, edited.wifi_set_config!!.wifi_config!!.networks[0].basic_service_sets[0].auth_wpa2_wpa3?.password)
    }

    private val PW_OF_FAKE = FakeRouterSimulation.FAKE_PASSWORD

    @Test
    fun renamingAClientNeedsAMacAndSendsBothMessages() {
        val req = RouterCommand.SetClientGivenName(EMPTY_BASE, "02:00:00:00:01:02", "Laptop", clientId = 2).request(4).wifi_set_client_given_name!!
        assertEquals("02:00:00:00:01:02", req.client_name?.mac_address)
        assertEquals("Laptop", req.client_name?.given_name)
        assertEquals(2, req.client_config?.client_id)
        assertEquals("Laptop", req.client_config?.given_name)
        assertFailsWith<IllegalArgumentException> { RouterCommand.SetClientGivenName(EMPTY_BASE, "laptop", "x") }
        assertFailsWith<IllegalArgumentException> { RouterCommand.SetClientGivenName(EMPTY_BASE, "02:00:00:00:01:02", "x".repeat(65)) }
    }

    @Test
    fun configOfFindsTheConfigInAnyRouterReply() {
        val config = WifiConfig(country_code = "UA")
        assertEquals(config, RouterCommand.configOf(DeviceResponse(wifi_get_config = WifiGetConfigResponse(config))))
        assertEquals(config, RouterCommand.configOf(DeviceResponse(wifi_get_status = tools.apachi.starlinknav.core.dish.proto.WifiGetStatusResponse(config = config))))
        assertEquals(config, RouterCommand.configOf(DeviceResponse(wifi_set_config = tools.apachi.starlinknav.core.dish.proto.WifiSetConfigResponse(config))))
        assertNull(RouterCommand.configOf(DeviceResponse()))
        assertNull(RouterCommand.configOf(null))
    }

    // ---- against the fake router

    @Test
    fun theFakeRouterTakesAReadModifyWriteAndKeepsThePassword() {
        val sim = FakeRouterSimulation()
        val client = RouterClient(FakeTransport(sim))
        val base = RouterCommand.configOf(client.call(DishCallKind.ROUTER_CONFIG).response)!!
        assertEquals(FakeRouterSimulation.FAKE_PASSWORD, base.networks[0].basic_service_sets[0].auth_wpa2_wpa3?.password) // the read is unredacted
        val result = client.send(RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.SetNetworkName("Car"), WifiConfigEdit.SetHidden(true))))
        assertTrue(result.ok, result.statusText)
        val now = sim.currentConfig()
        assertEquals(listOf("Car", "Car"), now.networks[0].basic_service_sets.map { it.ssid })
        assertEquals(listOf(true, true), now.networks[0].basic_service_sets.map { it.hidden })
        assertEquals(FakeRouterSimulation.FAKE_PASSWORD, now.networks[0].basic_service_sets[1].auth_wpa2_wpa3?.password)
        assertEquals("UA", now.country_code)
        // The router logged the change and the answer to the write is the new config.
        val updated = result.response?.wifi_set_config?.updated_wifi_config
        assertEquals("Car", updated?.networks?.get(0)?.basic_service_sets?.get(0)?.ssid)
        val events = RouterHistory.of(client.call(DishCallKind.ROUTER_HISTORY).response!!.wifi_get_history!!).events
        assertEquals(EventReason.name(16), EventReason.name(events.first().reason))
        assertEquals("wifi_set_config", sim.accepted.last())
    }

    @Test
    fun aPasswordChangeGetsFreshBssidsFromTheRouter() {
        val sim = FakeRouterSimulation()
        val client = RouterClient(FakeTransport(sim))
        val base = RouterCommand.configOf(client.call(DishCallKind.ROUTER_CONFIG).response)!!
        client.send(RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.SetPassword("brand-new-pass-9"))))
        val bss = sim.currentConfig().networks[0].basic_service_sets
        assertEquals(listOf("brand-new-pass-9", "brand-new-pass-9"), bss.map { it.auth_wpa2_wpa3?.password })
        assertTrue(bss.all { it.bssid.isNotEmpty() })
        assertNotEquals(bss[0].bssid, bss[1].bssid)
    }

    @Test
    fun aRouterThatDeniesWritesAnswersPermissionDeniedAndChangesNothing() {
        val sim = FakeRouterSimulation(denySetConfig = true)
        val client = RouterClient(FakeTransport(sim))
        val base = RouterCommand.configOf(client.call(DishCallKind.ROUTER_CONFIG).response)!!
        val r = client.send(RouterCommand.SetConfig(base.read(), listOf(WifiConfigEdit.SetNetworkName("Nope"))))
        assertEquals(CallOutcome.PERMISSION_DENIED, r.outcome)
        assertEquals("STARLINK", sim.currentConfig().networks[0].basic_service_sets[0].ssid)
        assertEquals(CallOutcome.PERMISSION_DENIED, client.send(RouterCommand.SetClientGivenName(EMPTY_BASE, "02:00:00:00:01:01", "me")).outcome)
        // Reads still work.
        assertTrue(client.call(DishCallKind.ROUTER_STATUS).ok)
    }

    @Test
    fun clientsAreRenamedAndPausedOnTheFakeRouter() {
        val sim = FakeRouterSimulation()
        val client = RouterClient(FakeTransport(sim))
        fun clients() = client.call(DishCallKind.ROUTER_CLIENTS).response!!.wifi_get_clients!!.clients
        assertEquals(3, clients().size)
        assertTrue(client.send(RouterCommand.SetClientGivenName(EMPTY_BASE, "02:00:00:00:01:02", "Dad's laptop", 2)).ok)
        assertEquals("Dad's laptop", clients().first { it.mac_address == "02:00:00:00:01:02" }.given_name)
        val base = RouterCommand.configOf(client.call(DishCallKind.ROUTER_CONFIG).response)!!
        assertTrue(client.send(RouterCommand.BlockClient(base.read(), "02:00:00:00:01:03", block = true, clientId = 3, ownMac = "02:00:00:00:01:01")).ok)
        val cam = clients().first { it.mac_address == "02:00:00:00:01:03" }
        assertTrue(cam.blocked)
        assertFalse(cam.active)
        assertFalse(clients().first { it.mac_address == "02:00:00:00:01:01" }.blocked)
        val base2 = RouterCommand.configOf(client.call(DishCallKind.ROUTER_CONFIG).response)!!
        client.send(RouterCommand.BlockClient(base2.read(), "02:00:00:00:01:03", block = false, clientId = 3))
        assertFalse(clients().first { it.mac_address == "02:00:00:00:01:03" }.blocked)
    }
}
