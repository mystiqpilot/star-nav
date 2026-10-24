package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.AuthWpa2Wpa3
import tools.apachi.starlinknav.core.dish.proto.BootInfo
import tools.apachi.starlinknav.core.dish.proto.ClientConfig
import tools.apachi.starlinknav.core.dish.proto.ClientName
import tools.apachi.starlinknav.core.dish.proto.ClientReconnectingOftenMetadata
import tools.apachi.starlinknav.core.dish.proto.DeviceInfo
import tools.apachi.starlinknav.core.dish.proto.DeviceRequest
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DeviceState
import tools.apachi.starlinknav.core.dish.proto.DhcpLease
import tools.apachi.starlinknav.core.dish.proto.DhcpServer
import tools.apachi.starlinknav.core.dish.proto.DnsResolverDropRateEntry
import tools.apachi.starlinknav.core.dish.proto.DnsResolverHistory
import tools.apachi.starlinknav.core.dish.proto.EthernetNetworkInterface
import tools.apachi.starlinknav.core.dish.proto.EventLog
import tools.apachi.starlinknav.core.dish.proto.FactoryResetResponse
import tools.apachi.starlinknav.core.dish.proto.GetConnectionsResponse
import tools.apachi.starlinknav.core.dish.proto.GetDeviceInfoResponse
import tools.apachi.starlinknav.core.dish.proto.GetNetworkInterfacesResponse
import tools.apachi.starlinknav.core.dish.proto.GetPingResponse
import tools.apachi.starlinknav.core.dish.proto.GetRadioStatsResponse
import tools.apachi.starlinknav.core.dish.proto.GetSpeedtestStatusResponse
import tools.apachi.starlinknav.core.dish.proto.GetTimeResponse
import tools.apachi.starlinknav.core.dish.proto.NetworkInterface
import tools.apachi.starlinknav.core.dish.proto.PingHostResponse
import tools.apachi.starlinknav.core.dish.proto.PingMetrics
import tools.apachi.starlinknav.core.dish.proto.PingResult
import tools.apachi.starlinknav.core.dish.proto.PingResultEntry
import tools.apachi.starlinknav.core.dish.proto.PingTarget
import tools.apachi.starlinknav.core.dish.proto.RadioStats
import tools.apachi.starlinknav.core.dish.proto.RebootResponse
import tools.apachi.starlinknav.core.dish.proto.ServiceConnection
import tools.apachi.starlinknav.core.dish.proto.ServiceConnectionEntry
import tools.apachi.starlinknav.core.dish.proto.SpeedtestStatus
import tools.apachi.starlinknav.core.dish.proto.StartSpeedtestResponse
import tools.apachi.starlinknav.core.dish.proto.UXEvent
import tools.apachi.starlinknav.core.dish.proto.UpdateResponse
import tools.apachi.starlinknav.core.dish.proto.WifiAlerts
import tools.apachi.starlinknav.core.dish.proto.WifiBandStatus
import tools.apachi.starlinknav.core.dish.proto.WifiClient
import tools.apachi.starlinknav.core.dish.proto.WifiConfig
import tools.apachi.starlinknav.core.dish.proto.WifiGetClientsResponse
import tools.apachi.starlinknav.core.dish.proto.WifiGetConfigResponse
import tools.apachi.starlinknav.core.dish.proto.WifiGetDiagnosticsResponse
import tools.apachi.starlinknav.core.dish.proto.WifiGetHistoryResponse
import tools.apachi.starlinknav.core.dish.proto.WifiGetPersistentStatsResponse
import tools.apachi.starlinknav.core.dish.proto.WifiGetPingMetricsResponse
import tools.apachi.starlinknav.core.dish.proto.WifiGetStatusResponse
import tools.apachi.starlinknav.core.dish.proto.WifiGuestInfoResponse
import tools.apachi.starlinknav.core.dish.proto.WifiNetworkInterface
import tools.apachi.starlinknav.core.dish.proto.WifiPersistentStats
import tools.apachi.starlinknav.core.dish.proto.WifiSetConfigResponse
import tools.apachi.starlinknav.core.dish.proto.WifiSetupRequirement
import tools.apachi.starlinknav.core.dish.proto.WifiSoftwareUpdateStats

/**
 * Synthetic Mini router for development without hardware: the server on the Wi-Fi gateway. It answers
 * `get_status` with `wifi_get_status` (clients with fake MACs and IPs, a config with SSID "STARLINK" and
 * the fake password [FAKE_PASSWORD], in clear like the real thing), a history with an event log, the
 * config, ping metrics and the diagnostics calls, and takes commands: reboot and factory reset (it goes
 * silent for [rebootDownSec]), update, wifi_set_config (accepted, or PERMISSION_DENIED with
 * [denySetConfig]), client rename, client pausing through client_configs, and a speed test that runs for
 * [speedtestSec] before it has a result. The dish arms answer UNIMPLEMENTED, as on the router server.
 */
class FakeRouterSimulation(
    /** wifi_set_config and wifi_set_client_given_name answer PERMISSION_DENIED (the Starlink app, logged in, does these). */
    @Volatile var denySetConfig: Boolean = false,
    unimplemented: Set<String> = emptySet(),
    private val rebootDownSec: Long = 30,
    private val speedtestSec: Long = 6,
    private val startNs: Long = System.nanoTime(),
    private val clock: () -> Long = System::nanoTime,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) : FakeDevice {
    private val unimplementedArms: Set<String> = unimplemented.toSet()
    @Volatile private var bootNs = startNs - 3_600_000_000_000L
    @Volatile private var downUntilNs = Long.MIN_VALUE
    @Volatile private var bootcount = 3
    @Volatile private var config: WifiConfig = defaultConfig()
    @Volatile private var clientNames = mapOf<String, String>()
    @Volatile private var speedStartedNs = Long.MIN_VALUE
    @Volatile private var speedId = 0
    @Volatile private var updateStartedNs = Long.MIN_VALUE
    @Volatile private var wifiOffByBypass = false
    private val events = java.util.Collections.synchronizedList(ArrayList<UXEvent>())

    /** The request arms this simulation has accepted, in order (for tests). */
    val accepted: List<String> get() = acceptedLog.toList()
    private val acceptedLog = java.util.Collections.synchronizedList(ArrayList<String>())

    /** The config as the router holds it now (unredacted: with the password). */
    fun currentConfig(): WifiConfig = config

    init {
        events += UXEvent(severity = 3, reason = 14, start_timestamp_ns = wallClockMs() * 1_000_000L - 3_600_000_000_000L) // ROUTER_REBOOT, an hour ago
        events += UXEvent(severity = 3, reason = 21, start_timestamp_ns = wallClockMs() * 1_000_000L - 1_800_000_000_000L) // ROUTER_PUBLIC_IPV4_CHANGE
        events += UXEvent(
            severity = 2, reason = 42, start_timestamp_ns = wallClockMs() * 1_000_000L - 900_000_000_000L,
            client_reconnecting_often_metadata = ClientReconnectingOftenMetadata(client_id = 2),
        )
    }

    override fun isDown(): Boolean = clock() < downUntilNs

    private fun uptimeS(): Long = ((clock() - bootNs) / 1_000_000_000L).coerceAtLeast(0)

    private fun event(reason: Int, severity: Int = 3) {
        events += UXEvent(severity = severity, reason = reason, start_timestamp_ns = wallClockMs() * 1_000_000L)
    }

    private fun restart(reason: Int) {
        val now = clock()
        downUntilNs = now + rebootDownSec * 1_000_000_000L
        bootNs = now + 2_000_000_000L
        bootcount++
        event(reason)
    }

    override fun handle(req: DeviceRequest): Pair<DeviceResponse?, Pair<Int, String>> {
        if (isDown()) return null to (GrpcStatus.UNAVAILABLE to "fake router: restarting")
        val arm = FakeSupport.armName(req)
        if (arm != null && (arm in unimplementedArms || arm.startsWith("dish_") || arm in DISH_ONLY)) {
            return null to FakeSupport.unimplemented(arm)
        }
        val ok = GrpcStatus.OK to ""
        fun accepted() { arm?.let(acceptedLog::add) }
        return when {
            req.get_status != null -> DeviceResponse(id = req.id, wifi_get_status = status()) to ok
            req.get_device_info != null -> DeviceResponse(id = req.id, get_device_info = GetDeviceInfoResponse(deviceInfo())) to ok
            req.get_history != null -> DeviceResponse(id = req.id, wifi_get_history = history()) to ok
            req.wifi_get_clients != null -> DeviceResponse(id = req.id, wifi_get_clients = WifiGetClientsResponse(clients = clients())) to ok
            req.wifi_get_config != null -> DeviceResponse(id = req.id, wifi_get_config = WifiGetConfigResponse(config)) to ok
            req.wifi_get_ping_metrics != null -> DeviceResponse(id = req.id, wifi_get_ping_metrics = WifiGetPingMetricsResponse(pingMetrics())) to ok
            req.wifi_guest_info != null -> DeviceResponse(
                id = req.id,
                wifi_guest_info = WifiGuestInfoResponse(is_online = true, router_hardware_version = HARDWARE, dish_hardware_version = "mini1_fake"),
            ) to ok
            req.get_persistent_stats != null -> DeviceResponse(
                id = req.id,
                wifi_get_persistent_stats = WifiGetPersistentStatsResponse(
                    WifiPersistentStats(factory_reset_button = WifiPersistentStats.Event(count = 1, last_occurred_timestamp = wallClockMs() / 1000 - 86_400L * 30)),
                ),
            ) to ok
            req.get_diagnostics != null -> DeviceResponse(id = req.id, wifi_get_diagnostics = diagnostics()) to ok
            req.time != null -> DeviceResponse(id = req.id, time = GetTimeResponse(unix_nano = wallClockMs() * 1_000_000L)) to ok
            req.get_network_interfaces != null -> DeviceResponse(id = req.id, get_network_interfaces = networkInterfaces()) to ok
            req.get_radio_stats != null -> DeviceResponse(id = req.id, get_radio_stats = radioStats()) to ok
            req.get_connections != null -> DeviceResponse(
                id = req.id,
                get_connections = GetConnectionsResponse(listOf(ServiceConnectionEntry("ground", ServiceConnection("ground.fake.example:443", 3)))),
            ) to ok
            req.get_ping != null -> DeviceResponse(
                id = req.id,
                get_ping = GetPingResponse(
                    listOf(
                        PingResultEntry("pop", PingResult(PingTarget(service = "pop", location = "fake", address = "100.64.0.1"), 0.004f, 31.2f)),
                        PingResultEntry("google", PingResult(PingTarget(service = "google", location = "fake", address = "8.8.8.8"), 0.005f, 36.8f)),
                        PingResultEntry("cloudflare", PingResult(PingTarget(service = "cloudflare", location = "fake", address = "1.1.1.1"), 0.004f, 35.1f)),
                    ),
                ),
            ) to ok
            req.ping_host != null -> {
                accepted()
                DeviceResponse(
                    id = req.id,
                    ping_host = PingHostResponse(PingResult(PingTarget(service = "host", address = req.ping_host!!.address), 0f, 36f)),
                ) to ok
            }
            req.get_speedtest_status != null -> DeviceResponse(id = req.id, get_speedtest_status = GetSpeedtestStatusResponse(speedtest())) to ok
            req.start_speedtest != null -> {
                accepted()
                speedStartedNs = clock()
                speedId++
                DeviceResponse(id = req.id, start_speedtest = StartSpeedtestResponse()) to ok
            }
            req.reboot != null -> {
                accepted()
                restart(14)
                DeviceResponse(id = req.id, reboot = RebootResponse()) to ok
            }
            req.factory_reset != null -> {
                accepted()
                config = defaultConfig()
                clientNames = emptyMap()
                wifiOffByBypass = false
                restart(13)
                DeviceResponse(id = req.id, factory_reset = FactoryResetResponse()) to ok
            }
            req.update != null -> {
                accepted()
                updateStartedNs = clock()
                DeviceResponse(id = req.id, update = UpdateResponse()) to ok
            }
            req.wifi_set_config != null -> {
                if (denySetConfig) {
                    null to (GrpcStatus.PERMISSION_DENIED to "Permission denied")
                } else {
                    accepted()
                    applyConfig(req.wifi_set_config!!.wifi_config ?: WifiConfig())
                    DeviceResponse(id = req.id, wifi_set_config = WifiSetConfigResponse(config)) to ok
                }
            }
            req.wifi_set_client_given_name != null -> {
                if (denySetConfig) {
                    null to (GrpcStatus.PERMISSION_DENIED to "Permission denied")
                } else {
                    accepted()
                    val r = req.wifi_set_client_given_name!!
                    val n = r.client_name ?: ClientName()
                    val mac = n.mac_address.ifEmpty { r.client_config?.mac_address.orEmpty() }
                    val name = n.given_name.ifEmpty { r.client_config?.given_name.orEmpty() }
                    if (mac.isNotEmpty()) clientNames = clientNames + (mac.lowercase() to name)
                    DeviceResponse(id = req.id) to ok
                }
            }
            else -> null to FakeSupport.unimplemented(arm ?: "unknown")
        }
    }

    /** What the real router does with a write: only the flagged settings change. */
    private fun applyConfig(c: WifiConfig) {
        var next = config
        if (c.apply_networks) {
            // The router assigns BSSIDs to the bands that arrive without one.
            var n = 0
            next = next.copy(
                networks = c.networks.map { net ->
                    net.copy(
                        basic_service_sets = net.basic_service_sets.map { bss ->
                            if (bss.bssid.isEmpty()) bss.copy(bssid = "02:00:00:00:%02x:%02x".format(0x10 + (++n), bss.band)) else bss
                        },
                    )
                },
            )
        }
        if (c.apply_nameservers) next = next.copy(nameservers = c.nameservers)
        if (c.apply_secure_dns) next = next.copy(secure_dns = c.secure_dns)
        if (c.apply_custom_dns_disabled) next = next.copy(custom_dns_disabled = c.custom_dns_disabled)
        if (c.apply_bypass_mode) {
            next = next.copy(bypass_mode = c.bypass_mode)
            wifiOffByBypass = c.bypass_mode
        }
        if (c.apply_disable_2ghz) next = next.copy(disable_2ghz = c.disable_2ghz)
        if (c.apply_disable_5ghz) next = next.copy(disable_5ghz = c.disable_5ghz)
        if (c.apply_disable_5ghz_high) next = next.copy(disable_5ghz_high = c.disable_5ghz_high)
        if (c.apply_client_configs) next = next.copy(client_configs = c.client_configs)
        if (c.apply_setup_complete) next = next.copy(setup_complete = c.setup_complete)
        config = next
        event(16)
    }

    private fun defaultConfig() = WifiConfig(
        country_code = "UA", setup_complete = true, version = 12, mac_wan = "02:00:00:00:00:01", mac_lan = "02:00:00:00:00:02",
        incarnation = 77,
        networks = listOf(
            WifiConfig.Network(
                ipv4 = "192.168.1.1", dhcpv4_start = 10, dhcpv4_end = 250, domain = "lan",
                basic_service_sets = listOf(
                    WifiConfig.BasicServiceSet(bssid = "02:00:00:00:00:0a", ssid = "STARLINK", band = WifiBand.RF_2GHZ, iface_name = "wlan0", auth_wpa2_wpa3 = AuthWpa2Wpa3(FAKE_PASSWORD)),
                    WifiConfig.BasicServiceSet(bssid = "02:00:00:00:00:0b", ssid = "STARLINK", band = WifiBand.RF_5GHZ, iface_name = "wlan1", auth_wpa2_wpa3 = AuthWpa2Wpa3(FAKE_PASSWORD)),
                ),
            ),
        ),
    )

    private fun deviceInfo() = DeviceInfo(
        id = "Router-fake-0001", hardware_version = HARDWARE, hardware_index = 1021, software_version = "fake-router-2026.09",
        country_code = "UA", bootcount = bootcount, dish_cohoused = true,
        boot = BootInfo(last_reason = 2, last_count = bootcount),
    )

    private fun clients(): List<WifiClient> {
        if (isDown() || wifiOffByBypass) return emptyList()
        val up = uptimeS()
        val blocked = config.client_configs.filter { c -> c.weekly_block_schedules.any { it.block_ranges.isNotEmpty() } }.map { it.mac_address.lowercase() }.toSet()
        fun client(id: Int, name: String, mac: String, ip: String, iface: Int, signal: Float, snr: Float, rate: Int) = WifiClient(
            name = name, given_name = clientNames[mac.lowercase()] ?: config.client_configs.firstOrNull { it.mac_address.equals(mac, true) }?.given_name.orEmpty(),
            mac_address = mac, ip_address = ip, signal_strength = signal, snr = snr, iface = iface, iface_name = if (iface == 1) "eth1" else "wlan${iface - 2}",
            role = 1, client_id = id, associated_time_s = (up - 60L * id).coerceAtLeast(5).toInt(),
            rx_stats = WifiClient.RxStats(bytes = 10_000_000L * id + up * 1_000, rate_mbps = rate, mcs = 9, nss = 2, bandwidth = 80, phy_mode = 5, throughput_mbps_last_15s_avg = 1.5f * id),
            tx_stats = WifiClient.TxStats(bytes = 90_000_000L * id + up * 5_000, rate_mbps = rate, mcs = 9, nss = 2, bandwidth = 80, phy_mode = 5, throughput_mbps_last_15s_avg = 4.5f * id),
            blocked = mac.lowercase() in blocked, active = mac.lowercase() !in blocked,
            upload_mb = 120 * id, download_mb = 900 * id,
            dhcp_lease_found = true, dhcp_lease_active = true, seconds_until_dhcp_lease_expires = 40_000f,
            ping_metrics = WifiClient.PingMetrics(drop_rate_5m = 0.002f, latency_5m = 34f),
        )
        return listOf(
            client(1, "pixel-fake", "02:00:00:00:01:01", "192.168.1.10", 3, -52f, 38f, 866),
            client(2, "laptop-fake", "02:00:00:00:01:02", "192.168.1.11", 2, -61f, 29f, 286),
            client(3, "camera-fake", "02:00:00:00:01:03", "192.168.1.12", 1, 0f, 0f, 1000),
        )
    }

    private fun status(): WifiGetStatusResponse {
        val up = uptimeS()
        val cl = clients()
        val updating = updateProgress()
        return WifiGetStatusResponse(
            device_info = deviceInfo(), device_state = DeviceState(uptime_s = up),
            ipv4_wan_address = "100.64.1.2", ipv6_wan_addresses = listOf("2001:db8:fa4e::1"), public_ipv4 = "203.0.113.7",
            secs_since_last_public_ipv4_change = (up % 100_000).toFloat(),
            ping_drop_rate = 0.004f, ping_drop_rate_5m = 0.005f, ping_latency_ms = 34f,
            dish_ping_drop_rate = 0f, dish_ping_drop_rate_5m = 0f, dish_ping_latency_ms = 0.8f,
            pop_ping_drop_rate = 0.004f, pop_ping_drop_rate_5m = 0.006f, pop_ping_latency_ms = 31f,
            pop_ipv6_ping_drop_rate = 0.01f, pop_ipv6_ping_drop_rate_5m = 0.01f, pop_ipv6_ping_latency_ms = 33f,
            rf_2ghz_status = WifiBandStatus(chan_busy_time_fraction = 0.41f, tx_air_time_fraction = 0.05f, rx_air_time_fraction = 0.08f, obss_air_time_fraction = 0.25f, edcca_air_time_fraction = 0.03f),
            rf_5ghz_status = WifiBandStatus(chan_busy_time_fraction = 0.12f, tx_air_time_fraction = 0.04f, rx_air_time_fraction = 0.03f, obss_air_time_fraction = 0.04f, edcca_air_time_fraction = 0.01f),
            alerts = WifiAlerts(install_pending = updating != null),
            dish_id = "ut-fake-0000-0001", dish_disablement_code = 1, utc_ns = wallClockMs() * 1_000_000L,
            software_update_stats = updating ?: WifiSoftwareUpdateStats(state = WifiSoftwareUpdate.NO_UPDATE_REQUIRED, running_version = "fake-router-2026.09"),
            setup_requirement = WifiSetupRequirement(state = SetupRequirement.NOT_REQUIRED),
            no_wan_link = false,
            dhcp_servers = listOf(
                DhcpServer(
                    domain = "lan", subnet = "192.168.1.0/24",
                    leases = cl.map { DhcpLease(ip_address = it.ip_address, mac_address = it.mac_address, hostname = it.name, expires_time = "2026-10-16T00:00:00Z", active = true, client_id = it.client_id) },
                ),
            ),
            config = config, clients = cl,
        )
    }

    /** The update shows as downloading for 10 s after the command, then it is done. */
    private fun updateProgress(): WifiSoftwareUpdateStats? {
        if (updateStartedNs == Long.MIN_VALUE) return null
        val s = (clock() - updateStartedNs) / 1e9
        if (s > 10) return null
        return WifiSoftwareUpdateStats(
            state = WifiSoftwareUpdate.REBOOT_PENDING.takeIf { s > 8 } ?: 2, software_download_progress = (s / 8).coerceAtMost(1.0).toFloat(),
            running_version = "fake-router-2026.09", version_in_progress = "fake-router-2026.10",
        )
    }

    private fun history(): WifiGetHistoryResponse {
        val current = uptimeS()
        val current15 = current / 15
        val nowNs = wallClockMs() * 1_000_000L
        return WifiGetHistoryResponse(
            current = current, current_index_15s = current15,
            ping_latency_ms = FakeSupport.ring(current) { k -> 30f + 12f * FakeSupport.unit(k, 11) },
            ping_drop_rate = FakeSupport.ring(current) { k -> if (FakeSupport.unit(k, 12) > 0.99f) 1f else 0f },
            pop_ipv4_ping_drop_rate_last_15s = FakeSupport.ring(current15, 60) { k -> 0.01f * FakeSupport.unit(k, 13) },
            pop_ipv6_ping_drop_rate_last_15s = FakeSupport.ring(current15, 60) { k -> 0.012f * FakeSupport.unit(k, 14) },
            google_ipv4_ping_drop_rate_last_15s = FakeSupport.ring(current15, 60) { k -> 0.01f * FakeSupport.unit(k, 15) },
            cloudflare_ipv4_ping_drop_rate_last_15s = FakeSupport.ring(current15, 60) { k -> 0.01f * FakeSupport.unit(k, 16) },
            dns_resolver_drop_rate = listOf("1.1.1.1", "8.8.8.8").mapIndexed { i, ip ->
                DnsResolverDropRateEntry(ip, DnsResolverHistory(drop_rate_last_15s = FakeSupport.ring(current15, 60) { k -> 0.02f * FakeSupport.unit(k, 20 + i) }))
            },
            event_log = EventLog(events = events.toList(), start_timestamp_ns = nowNs - current * 1_000_000_000L, current_timestamp_ns = nowNs),
        )
    }

    private fun pingMetrics() = PingMetrics(
        latency_mean_ms = 34f, latency_stddev_ms = 6f, latency_mean_ms_5m = 35f, latency_mean_ms_1h = 36f, latency_mean_ms_1d = 38f,
        drop_rate = 0.004f, drop_rate_5m = 0.005f, drop_rate_1h = 0.006f, drop_rate_1d = 0.008f, seconds_since_last_success = 0f,
        seconds_since_last_1s_outage = 640f, seconds_since_last_5s_outage = 3_600f, happy_hours_1s_1d = 22.5f, happy_hours_5s_1d = 23.5f,
    )

    private fun diagnostics() = WifiGetDiagnosticsResponse(
        id = "Router-fake-0001", hardware_version = HARDWARE, software_version = "fake-router-2026.09",
        networks = listOf(WifiGetDiagnosticsResponse.Network(domain = "lan", ipv4 = "192.168.1.1", clients_ethernet = 1, clients_2ghz = 1, clients_5ghz = 1)),
    )

    private fun networkInterfaces() = GetNetworkInterfacesResponse(
        listOf(
            NetworkInterface(name = "wan", up = true, mac_address = "02:00:00:00:00:01", ipv4_addresses = listOf("100.64.1.2"), ethernet = EthernetNetworkInterface(link_detected = true, speed_mbps = 1000, autonegotiation_on = true, duplex = 2)),
            NetworkInterface(
                name = "wlan0", up = true, mac_address = "02:00:00:00:00:0a",
                wifi = WifiNetworkInterface(channel = 6, link_quality = 70.0, signal_level = -48.0, noise_level = -92.0, links = listOf(WifiNetworkInterface.WirelessLink(band = 1, channel = 6, bandwidth = 20))),
            ),
            NetworkInterface(
                name = "wlan1", up = true, mac_address = "02:00:00:00:00:0b",
                wifi = WifiNetworkInterface(channel = 100, link_quality = 70.0, signal_level = -50.0, noise_level = -94.0, links = listOf(WifiNetworkInterface.WirelessLink(band = 2, channel = 100, bandwidth = 80))),
            ),
        ),
    )

    private fun radioStats() = GetRadioStatsResponse(
        listOf(
            RadioStats(band = WifiBand.RF_2GHZ, rx_stats = NetworkInterface.RxStats(bytes = 5_000_000, packets = 40_000), tx_stats = NetworkInterface.TxStats(bytes = 9_000_000, packets = 60_000), thermal_status = RadioStats.ThermalStatus(level = 0, temp = 52, duty_cycle = 100), antenna_status = RadioStats.AntennaStatus(rssi1 = -48f, rssi2 = -50f)),
            RadioStats(band = WifiBand.RF_5GHZ, rx_stats = NetworkInterface.RxStats(bytes = 25_000_000, packets = 90_000), tx_stats = NetworkInterface.TxStats(bytes = 90_000_000, packets = 120_000), thermal_status = RadioStats.ThermalStatus(level = 0, temp = 55, duty_cycle = 100), antenna_status = RadioStats.AntennaStatus(rssi1 = -51f, rssi2 = -52f)),
        ),
    )

    /** Running for [speedtestSec] s after the start command, then the result. */
    private fun speedtest(): SpeedtestStatus {
        if (speedStartedNs == Long.MIN_VALUE) return SpeedtestStatus(running = false, up = SpeedtestStatus.Direction(), down = SpeedtestStatus.Direction())
        val s = (clock() - speedStartedNs) / 1e9
        val steps = (s.coerceAtMost(speedtestSec.toDouble())).toInt()
        val down = List(steps) { 180f + 12f * FakeSupport.unit(it.toLong(), 30) }
        val up = List(steps) { 22f + 3f * FakeSupport.unit(it.toLong(), 31) }
        return SpeedtestStatus(
            running = s < speedtestSec, id = speedId,
            up = SpeedtestStatus.Direction(throughputs_mbps = up), down = SpeedtestStatus.Direction(throughputs_mbps = down),
        )
    }

    companion object {
        const val FAKE_PASSWORD = "SLNAV-FAKE-PASSWORD-7391"
        private const val HARDWARE = "mini1_router_fake"
        private val DISH_ONLY = setOf("get_location", "get_gnss_measurement", "dish_stow")
    }
}
