package tools.apachi.starlinknav.core.dish

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * dish-features-2026-10-15.md: some requests are never in the app (they can brick the device or need
 * SpaceX keys). The proto subset must not declare them; this reads the .proto and fails by tag number.
 */
class NeverRequestsTest {
    private val proto = File("src/main/proto/spacex/device_subset.proto").readText()

    /**
     * Tag -> name of every arm of `message <name> { ... oneof <oneof> { ... } }`. Tolerant of field options
     * (`[deprecated = true]`), any spacing (`fuse=1021 ;`, a tag on the next line) and comments, so a
     * declaration cannot hide from the guard by its formatting.
     */
    private fun arms(message: String, oneof: String, text: String = proto): Map<Int, String> {
        val body = messageBody(message, text)
        val start = body.indexOf("oneof $oneof {")
        require(start >= 0) { "no oneof $oneof in $message" }
        val end = body.indexOf('}', start)
        return armDeclarations(body.substring(start, end))
    }

    private fun armDeclarations(oneofBody: String): Map<Int, String> =
        Regex("""([\w.]+)\s+(\w+)\s*=\s*(\d+)\s*(?:\[[^\]]*])?\s*;""").findAll(oneofBody).associate { it.groupValues[3].toInt() to it.groupValues[2] }

    private fun stripComments(text: String) = text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("""//[^\n]*"""), "")

    private fun messageBody(name: String, text: String = proto): String {
        val clean = stripComments(text)
        val start = Regex("""(?m)^message $name \{""").find(clean)?.range?.last ?: error("no message $name")
        var depth = 1
        var i = start + 1
        while (depth > 0) {
            when (clean[i]) { '{' -> depth++; '}' -> depth-- }
            i++
        }
        return clean.substring(start, i)
    }

    /** The NEVER list by request tag (names from the dump of firmware 2026.09.18.mr87170.1), and the neighbours that go with it. */
    private val never = mapOf(
        1010 to "set_trusted_keys", 1013 to "set_sku", 1021 to "fuse", 1030 to "initiate_remote_ssh", 1032 to "set_test_mode",
        1033 to "software_update", 2023 to "set_per_vehicle_config",
        2007 to "dish_set_emc", 2009 to "dish_get_emc",
        3019 to "wifi_calibration_mode", 3021 to "wifi_rf_test", 3026 to "wifi_factory_test_command", 3035 to "wifi_write_calibration",
        2018 to "dish_set_max_power_test_mode", 2024 to "dish_aviation_test", 4001 to "transceiver_if_loopback_test",
        5000 to "start_unlock", 5001 to "finish_unlock", 1035 to "iq_capture", 7001 to "toggle_mode",
        1012 to "get_log", 1019 to "get_heap_dump", 1041 to "get_goroutine_stack_traces", 3024 to "wifi_get_firewall",
        1038 to "run_iperf_server", 1039 to "tcp_connectivity_test", 1040 to "udp_connectivity_test",
        // Also kept out: signed channel, debug and reset-button emulation, mesh, traceroute.
        15 to "signed_request", 1078 to "sensitive_request", 1005 to "authenticate", 1020 to "restart_control", 1034 to "enable_debug_telem",
        2022 to "reset_button", 2028 to "dish_start_test_mode_server", 3012 to "wifi_set_mesh_device_trust", 3013 to "wifi_set_mesh_config",
        3025 to "wifi_toggle_poe_negotiation", 3030 to "wifi_toggle_umbilical_mode", 3033 to "wifi_reset_eth_phy", 3034 to "wifi_flush_hardware_nat",
        3032 to "wifi_run_debug_netsys", 3036 to "wifi_start_traceroute", 1031 to "self_test",
    )

    @Test
    fun noRequestFromTheNeverListIsDeclared() {
        val requests = arms("DeviceRequest", "request")
        assertTrue(requests.size > 20, "parsed ${requests.size} request arms")
        val found = requests.filterKeys { it in never }
        assertEquals(emptyMap(), found, "NEVER requests declared in device_subset.proto: $found")
        val byName = requests.values.filter { name -> never.values.any { it == name } }
        assertEquals(emptyList(), byName)
    }

    @Test
    fun theGeneratedRequestClassHasNoNeverArmEither() {
        // The compiled class, not the text: whatever the .proto looks like, these are the request arms the app can send.
        val every = tools.apachi.starlinknav.core.dish.proto.DeviceRequest::class.java.declaredFields
            .mapNotNull { f -> f.getAnnotation(com.squareup.wire.WireField::class.java)?.let { it to f.name } }
        // Any field of the message counts, in the oneof or not.
        assertEquals(emptyList(), every.filter { (a, _) -> a.tag in never }.map { it.second }, "NEVER requests compiled into DeviceRequest")
        val compiled = every.filter { (a, _) -> a.oneofName == "request" }.associate { (a, name) -> a.tag to name }
        assertTrue(compiled.size > 20, "reflected ${compiled.size} request arms")
        assertEquals(arms("DeviceRequest", "request"), compiled, "the parsed .proto text and the compiled class disagree")
    }

    @Test
    fun theArmParserSeesDeclarationsHoweverTheyAreFormatted() {
        // Simulated bypasses of the guard: options before the semicolon, odd spacing, a split line, a comment.
        val sample = """
            message DeviceRequest {
              uint64 id = 1;
              oneof request {
                FuseRequest fuse = 1021 [deprecated = true];
                FuseRequest fuse2=1022 ;
                SetSkuRequest set_sku
                    = 1013
                    ;
                GetLogRequest get_log = 1012 [json_name = "x", deprecated = false] ;
                // GetPingRequest commented_out = 1;
                GetPingRequest get_ping = 1009;
              }
            }
        """.trimIndent()
        assertEquals(
            mapOf(1021 to "fuse", 1022 to "fuse2", 1013 to "set_sku", 1012 to "get_log", 1009 to "get_ping"),
            arms("DeviceRequest", "request", sample),
        )
        val caught = arms("DeviceRequest", "request", sample).filterKeys { it in never }
        assertEquals(setOf(1021, 1013, 1012), caught.keys)
    }

    @Test
    fun theDeclaredRequestsAreTheOnesTheAppUses() {
        // A change here is deliberate: a new request needs a place in the feature list first.
        val expected = setOf(
            1001, 1004, 1007, 1008, 1009, 1011, 1014, 1015, 1016, 1017, 1022, 1023, 1027, 1028, 1036, 1037,
            2002, 2008, 2010, 2011, 2013, 2014, 2017, 2021, 2026, 3001, 3002, 3007, 3009, 3017, 3020, 6000, 7000,
        )
        assertEquals(expected, arms("DeviceRequest", "request").keys)
    }

    @Test
    fun theKnownTagsAreWhatTheDumpSays() {
        val requests = arms("DeviceRequest", "request")
        val responses = arms("DeviceResponse", "response")
        // Request tag -> response tag where they differ (firmware reflection 2026.09.18.mr87170.1).
        assertEquals("get_radio_stats", requests[1036])
        assertEquals("get_radio_stats", responses[1035])
        assertEquals("dish_inhibit_gps", requests[2014])
        assertEquals("dish_inhibit_gps", responses[2013])
        assertEquals("dish_power_save", requests[2013])
        assertEquals("dish_clear_obstruction_map", requests[2017])
        assertEquals("dish_clear_obstruction_map", responses[2015])
        assertEquals("get_status", requests[1004])
        assertEquals("dish_get_status", responses[2004])
        assertEquals("wifi_get_status", responses[3004])
        assertEquals("get_history", requests[1007])
        assertEquals("dish_get_history", responses[2006])
        assertEquals("wifi_get_history", responses[3006])
        assertEquals("get_device_info", requests[1008])
        assertEquals("get_device_info", responses[1004])
        assertEquals("get_persistent_stats", requests[1022])
        assertEquals("wifi_get_persistent_stats", responses[3022])
        assertEquals("get_diagnostics", requests[6000])
        assertEquals("dish_get_diagnostics", responses[6001])
        assertEquals("wifi_get_diagnostics", responses[6000])
        assertEquals("update", requests[1014])
        assertEquals("time", requests[1037])
        assertEquals("ping_host", requests[1016])
        assertEquals("get_ping", requests[1009])
        assertEquals("factory_reset", requests[1011])
        assertEquals("dish_factory_reset", requests[2021])
        assertEquals("wifi_set_client_given_name", requests[3017])
        assertEquals("wifi_set_config", requests[3001])
        assertEquals("wifi_get_config", responses[3009])
        assertEquals("wifi_get_clients", responses[3002])
        assertEquals("wifi_get_ping_metrics", responses[3007])
    }

    @Test
    fun everySecretLookingFieldIsRedacted() {
        // Every field whose name contains password, secret, key or credential carries [(redacted) = true].
        val declaration = Regex("""(?m)^\s*(?:optional\s+|repeated\s+)?[\w.]+\s+(\w+)\s*=\s*\d+\s*(\[[^\]]*\])?\s*;""")
        val secretName = Regex("password|secret|key|credential", RegexOption.IGNORE_CASE)
        var checked = 0
        for (m in declaration.findAll(proto)) {
            val name = m.groupValues[1]
            if (!secretName.containsMatchIn(name)) continue
            checked++
            assertTrue("(redacted) = true" in m.groupValues[2], "field $name must be marked (redacted) = true: ${m.value.trim()}")
        }
        assertTrue(checked >= 8, "found only $checked secret-looking fields")
    }
}
