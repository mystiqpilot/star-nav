package tools.apachi.starlinknav.core.dish

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import tools.apachi.starlinknav.core.dish.proto.AuthRadius
import tools.apachi.starlinknav.core.dish.proto.AuthWpa2
import tools.apachi.starlinknav.core.dish.proto.AuthWpa2Wpa3
import tools.apachi.starlinknav.core.dish.proto.AuthWpa3
import tools.apachi.starlinknav.core.dish.proto.DeviceInfo
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.proto.HttpServer
import tools.apachi.starlinknav.core.dish.proto.PublicKey
import tools.apachi.starlinknav.core.dish.proto.TlsConfig
import tools.apachi.starlinknav.core.dish.proto.WifiClient
import tools.apachi.starlinknav.core.dish.proto.WifiConfig
import tools.apachi.starlinknav.core.dish.proto.WifiGetConfigResponse
import tools.apachi.starlinknav.core.dish.proto.WifiGetStatusResponse
import tools.apachi.starlinknav.core.log.Redactor
import tools.apachi.starlinknav.core.log.Slog
import tools.apachi.starlinknav.core.log.SlogReader
import tools.apachi.starlinknav.core.log.SlogWriter
import tools.apachi.starlinknav.core.log.proto.Record
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal const val PW = "hunter2-WPA-PASSWORD-1"
internal const val PW3 = "wpa3-secret-PASSWORD-2"
internal const val RADIUS = "RADIUS-SECRET-SHARED-3"
internal const val TLS_KEY = "TLS-PRIVATE-KEY-BYTES-4"
internal const val PUB_KEY = "DYNAMIC-KEY-MATERIAL-5"
internal const val CLIENT_KEY = "CLIENT-KEY-BLOB-6"
internal const val IN_UNKNOWN = "UNKNOWN-FIELD-SECRET-7"
internal val ALL_SECRETS = listOf(PW, PW3, RADIUS, TLS_KEY, PUB_KEY, CLIENT_KEY, IN_UNKNOWN)

/** A length-delimited field this build knows nothing about, as bytes (how newer firmware would add one). */
internal fun unknownField(tag: Int, text: String): ByteString {
    val b = Buffer()
    var key = (tag.toLong() shl 3) or 2
    while (key >= 0x80) { b.writeByte(((key and 0x7f) or 0x80).toInt()); key = key ushr 7 }
    b.writeByte(key.toInt())
    b.writeByte(text.length)
    b.writeUtf8(text)
    return b.readByteString()
}

/** A router config with a secret in every place the schema has one, and unknown fields at every level. */
internal fun secretConfig() = WifiConfig(
    country_code = "UA", setup_complete = true, mac_wan = "02:00:00:00:00:01",
    networks = listOf(
        WifiConfig.Network(
            ipv4 = "192.168.1.1",
            basic_service_sets = listOf(
                WifiConfig.BasicServiceSet(bssid = "02:00:00:00:00:0a", ssid = "STARLINK", band = WifiBand.RF_2GHZ, auth_wpa2_wpa3 = AuthWpa2Wpa3(PW), unknownFields = unknownField(4998, "bss-unknown")),
                WifiConfig.BasicServiceSet(bssid = "02:00:00:00:00:0b", ssid = "STARLINK", band = WifiBand.RF_5GHZ, auth_wpa2 = AuthWpa2(PW)),
            ),
            onboard_radius_tls_config = TlsConfig(key = TLS_KEY, cert = "CERT-PUBLIC"),
            unknownFields = unknownField(4999, "net-unknown"),
        ),
        WifiConfig.Network(
            guest = true, client_isolation = true,
            basic_service_sets = listOf(
                WifiConfig.BasicServiceSet(ssid = "STARLINK-GUEST", band = WifiBand.RF_5GHZ, auth_wpa3 = AuthWpa3(PW3)),
                WifiConfig.BasicServiceSet(ssid = "corp", band = WifiBand.RF_2GHZ, auth_radius = AuthRadius(server = "10.0.0.5", password = RADIUS)),
            ),
        ),
    ),
    dynamic_keys = listOf(PublicKey(PUB_KEY)),
    client_key = CLIENT_KEY.encodeUtf8(),
    http_server = HttpServer(domain_name = "router.local", tls = TlsConfig(key = TLS_KEY, cert = "CERT-PUBLIC")),
    unknownFields = unknownField(5000, IN_UNKNOWN),
)

internal fun secretStatusResponse(): DeviceResponse = DeviceResponse(
    id = 5,
    wifi_get_status = WifiGetStatusResponse(
        device_info = DeviceInfo(id = "Router-secret-id", software_version = "r1"), dish_id = "ut-secret-id",
        public_ipv4 = "203.0.113.9", ipv4_wan_address = "100.64.1.2",
        config = secretConfig(),
        clients = listOf(WifiClient(name = "alice-phone", given_name = "Alice", mac_address = "02:aa:bb:cc:dd:01", ip_address = "192.168.1.50", rx_stats = WifiClient.RxStats(bytes = 5))),
    ),
)

class RedactTest {
    private fun bytesOf(r: DeviceResponse) = DeviceResponse.ADAPTER.encode(r)

    @Test
    fun theFixtureReallyHoldsEverySecretInClear() {
        val bytes = bytesOf(secretStatusResponse())
        for (secret in ALL_SECRETS) assertTrue(TestWire.contains(bytes, secret), secret)
        assertEquals(ALL_SECRETS.dropLast(1).toSet(), Redact.secretsIn(secretStatusResponse()).toSet()) // all but the one in an unknown field
    }

    @Test
    fun noSecretReachesToStringEvenOnTheUnredactedMessage() {
        val texts = listOf(
            secretConfig().toString(), secretStatusResponse().toString(),
            DeviceResponse(wifi_get_config = WifiGetConfigResponse(secretConfig())).toString(),
            secretConfig().networks[0].basic_service_sets[0].toString(), AuthWpa2(PW).toString(), TlsConfig(key = TLS_KEY).toString(),
        )
        for (t in texts) for (secret in ALL_SECRETS) assertFalse(secret in t, "$secret leaked into toString: ${t.take(200)}")
        assertTrue("██" in AuthWpa2(PW).toString())
        // Ordinary fields still print.
        assertTrue("STARLINK" in secretConfig().toString())
    }

    @Test
    fun redactClearsEveryPasswordSecretAndKeyAndUnknownFields() {
        val redacted = Redact.response(secretStatusResponse())
        val bytes = bytesOf(redacted)
        for (secret in ALL_SECRETS) assertFalse(TestWire.contains(bytes, secret), "$secret survived")
        assertTrue(Redact.secretsIn(redacted).isEmpty())
        // What is not a secret stays: names, bands, counts.
        val cfg = assertNotNull(redacted.wifi_get_status?.config)
        assertEquals(listOf("STARLINK", "STARLINK"), cfg.networks[0].basic_service_sets.map { it.ssid })
        assertEquals("CERT-PUBLIC", cfg.networks[0].onboard_radius_tls_config?.cert)
        assertEquals("10.0.0.5", cfg.networks[1].basic_service_sets[1].auth_radius?.server)
        assertEquals(1, redacted.wifi_get_status?.clients?.size)
        // Idempotent.
        assertContentEquals(bytes, bytesOf(Redact.response(redacted)))
        // The helper also works on the pieces.
        assertTrue(Redact.secretsIn(Redact.config(secretConfig())).isEmpty())
        assertTrue(Redact.secretsIn(Redact.wifiStatus(secretStatusResponse().wifi_get_status!!).config).isEmpty())
    }

    @Test
    fun callResultsOfRouterKindsAreLoggedRedactedOnly() {
        val raw = bytesOf(secretStatusResponse())
        val result = DishCallResult(DishCallKind.ROUTER_STATUS, 1, 2, "grpc-web", 200, GrpcStatus.OK, null, raw, secretStatusResponse(), null)
        val record = result.toRecord()
        for (secret in ALL_SECRETS) assertFalse(TestWire.contains(record.response.toByteArray(), secret), secret)
        assertTrue(record.response.size > 0)
        // The same for a dish kind that was sent to the router's address by mistake.
        val wrongHost = DishCallResult(DishCallKind.STATUS, 1, 2, "grpc-web", 200, GrpcStatus.OK, null, raw, secretStatusResponse(), null)
        for (secret in ALL_SECRETS) assertFalse(TestWire.contains(wrongHost.toRecord().response.toByteArray(), secret), secret)
        // An undecodable router payload is dropped, not stored blind.
        val garbled = DishCallResult(DishCallKind.ROUTER_CONFIG, 1, 2, "grpc-web", 200, GrpcStatus.OK, null, PW.encodeToByteArray(), null, "decode: bad")
        assertEquals(0, garbled.toRecord().response.size)
        assertTrue("dropped" in garbled.toRecord().error)
        // Reading a log that holds a raw router reply (older or foreign) gives it back redacted.
        val dirty = result.toRecord().copy(response = raw.toByteString())
        val read = assertNotNull(DishCallResult.fromRecord(dirty))
        for (secret in ALL_SECRETS) assertFalse(TestWire.contains(read.raw!!, secret), secret)
        assertTrue(Redact.secretsIn(read.response).isEmpty())
        // Dish replies are logged as they came, unknown fields included.
        val dishRaw = bytesOf(DeviceResponse(dish_get_status = DishGetStatusResponse(device_info = DeviceInfo(software_version = "s"), unknownFields = unknownField(9000, "kept"))))
        val dish = DishCallResult(DishCallKind.STATUS, 1, 2, "grpc-web", 200, GrpcStatus.OK, null, dishRaw, DeviceResponse.ADAPTER.decode(dishRaw), null)
        assertContentEquals(dishRaw, dish.toRecord().response.toByteArray())
    }

    @Test
    fun theLogWriterRechecksWhateverRecordIsHandedToIt() {
        val raw = bytesOf(secretStatusResponse())
        val sneaky = Record(
            t_mono_ns = 1,
            dish = tools.apachi.starlinknav.core.log.proto.DishCall(kind = DishCallKind.ROUTER_CONFIG.code, http_status = 200, response = raw.toByteString()),
        )
        val garbled = Record(t_mono_ns = 2, dish = tools.apachi.starlinknav.core.log.proto.DishCall(kind = DishCallKind.ROUTER_CLIENTS.code, response = PW.encodeToByteArray().toByteString()))
        val out = ByteArrayOutputStream()
        SlogWriter.into(out).use {
            it.write(sneaky)
            it.write(garbled)
        }
        val file = out.toByteArray()
        for (secret in ALL_SECRETS) assertFalse(TestWire.contains(file, secret), "$secret reached the log file")
        val back = SlogReader(Buffer().write(file)).use { r -> r.records().toList() }
        assertEquals(2, back.size)
        assertEquals(0, back[1].dish!!.response.size)
        assertEquals(sneaky.t_mono_ns, back[0].t_mono_ns)
    }

    @Test
    fun theExporterAlsoClearsTheRoutersIdentifiers() {
        val raw = bytesOf(Redact.response(secretStatusResponse()))
        val record = Record(t_mono_ns = 1, dish = tools.apachi.starlinknav.core.log.proto.DishCall(kind = DishCallKind.ROUTER_STATUS.code, response = raw.toByteString()))
        val exported = Redactor.redact(record)
        val bytes = exported.dish!!.response.toByteArray()
        for (id in listOf("Router-secret-id", "ut-secret-id", "203.0.113.9", "100.64.1.2", "alice-phone", "Alice", "02:aa:bb:cc:dd:01", "192.168.1.50", "02:00:00:00:00:0a", "02:00:00:00:00:01")) {
            assertFalse(TestWire.contains(bytes, id), "$id survived the export")
        }
        for (secret in ALL_SECRETS) assertFalse(TestWire.contains(bytes, secret))
        // The counters stay.
        assertEquals(5L, DeviceResponse.ADAPTER.decode(bytes).wifi_get_status!!.clients[0].rx_stats?.bytes)
        // A raw (unredacted) router record in an old log is cleaned of secrets on export too.
        val dirty = record.copy(dish = record.dish!!.copy(response = bytesOf(secretStatusResponse()).toByteString()))
        for (secret in ALL_SECRETS) assertFalse(TestWire.contains(Redactor.redact(dirty).dish!!.response.toByteArray(), secret))
        assertNotEquals(record.dish!!.response, exported.dish!!.response)
    }

    @Test
    fun theExporterClearsPublicAddressesAndMeshRepeaterIds() {
        val interfacesResponse = DeviceResponse(
            get_network_interfaces = tools.apachi.starlinknav.core.dish.proto.GetNetworkInterfacesResponse(
                listOf(
                    tools.apachi.starlinknav.core.dish.proto.NetworkInterface(name = "wan", ipv4_addresses = listOf("100.64.1.2", "203.0.113.9", "192.168.1.1", "10.2.0.1", "172.16.5.4", "169.254.1.1", "127.0.0.1")),
                    tools.apachi.starlinknav.core.dish.proto.NetworkInterface(name = "x", ipv4_addresses = listOf("172.32.0.1", "not-an-address", "1.2.3")),
                ),
            ),
        )
        val historyResponse = DeviceResponse(
            wifi_get_history = tools.apachi.starlinknav.core.dish.proto.WifiGetHistoryResponse(
                event_log = tools.apachi.starlinknav.core.dish.proto.EventLog(
                    listOf(
                        tools.apachi.starlinknav.core.dish.proto.UXEvent(
                            reason = 20, device_id = "Router-node-1",
                            mesh_connection_changing_metadata = tools.apachi.starlinknav.core.dish.proto.MeshConnectionChangingMetadata(repeater_id = "Repeater-ABC-123", change = 1),
                        ),
                        tools.apachi.starlinknav.core.dish.proto.UXEvent(
                            reason = 21,
                            mesh_backhaul_low_phy_metadata = tools.apachi.starlinknav.core.dish.proto.MeshBackhaulLowPhyMetadata(repeater_ids = listOf("Repeater-DEF-456", "Repeater-GHI-789")),
                        ),
                    ),
                ),
            ),
        )
        val interfaces = Redactor.response(interfacesResponse).get_network_interfaces!!.network_interfaces
        assertEquals(listOf("192.168.1.1", "10.2.0.1", "172.16.5.4", "169.254.1.1", "127.0.0.1"), interfaces[0].ipv4_addresses)
        assertEquals(emptyList(), interfaces[1].ipv4_addresses)
        val out = Redactor.response(historyResponse)
        val events = out.wifi_get_history!!.event_log!!.events
        assertEquals("", events[0].device_id)
        assertEquals("", events[0].mesh_connection_changing_metadata!!.repeater_id)
        assertEquals(1, events[0].mesh_connection_changing_metadata!!.change) // the kind of change stays
        assertEquals(emptyList(), events[1].mesh_backhaul_low_phy_metadata!!.repeater_ids)
        val bytes = DeviceResponse.ADAPTER.encode(out) + DeviceResponse.ADAPTER.encode(Redactor.response(interfacesResponse))
        for (id in listOf("100.64.1.2", "203.0.113.9", "Repeater-ABC-123", "Repeater-DEF-456", "Repeater-GHI-789", "Router-node-1")) assertFalse(TestWire.contains(bytes, id), id)
    }

    @Test
    fun probeReportsAndDumpsOfARouterNeverShowAPassword() {
        val routerSim = FakeRouterSimulation()
        val router = RouterClient(FakeTransport(routerSim))
        val results = DishProbe.runRouter(router)
        val config = results.first { it.kind == DishCallKind.ROUTER_CONFIG }
        // The probe's own results are redacted already.
        assertTrue(Redact.secretsIn(config.response).isEmpty())
        assertTrue(Redact.secretsIn(results.first { it.kind == DishCallKind.ROUTER_STATUS }.response).isEmpty())
        for (coords in listOf(false, true)) {
            val report = DishProbe.report(emptyList(), "none", coords, routerResults = results, routerEndpoint = "fake")
            assertFalse(FakeRouterSimulation.FAKE_PASSWORD in report, "password in report (coords=$coords)")
            assertTrue("router:status" in report)
            assertTrue("wifi_secrets=hidden" in report)
            if (!coords) assertFalse("203.0.113.7" in report)
        }
        // And what the log holds, read back through `dump`'s decoder.
        val file = ByteArrayOutputStream()
        SlogWriter.into(file).use { w -> results.forEach { w.write(Record(t_mono_ns = 1, dish = it.toRecord())) } }
        assertFalse(TestWire.contains(file.toByteArray(), FakeRouterSimulation.FAKE_PASSWORD))
        // Even the raw reply from the fake does carry it, so the checks above mean something.
        val rawReply = FakeTransport(routerSim).unary("/x", DeviceRequest0.config()).message!!
        assertTrue(TestWire.contains(rawReply, FakeRouterSimulation.FAKE_PASSWORD))
    }

    private object DeviceRequest0 {
        fun config(): ByteArray = tools.apachi.starlinknav.core.dish.proto.DeviceRequest.ADAPTER.encode(DishCallKind.ROUTER_CONFIG.request(1))
    }

    @Test
    fun slogPayloadTypeStillWorksForRouterRecords() {
        val r = Record(t_mono_ns = 1, dish = DishCallResult.failure(DishCallKind.ROUTER_STATUS, 0, 0, "-", "x").toRecord())
        assertEquals("dish", Slog.payloadType(r))
    }
}
