package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DishAlerts
import tools.apachi.starlinknav.core.dish.proto.DishGetDiagnosticsResponse
import tools.apachi.starlinknav.core.dish.proto.WifiAlerts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the app's text resources from the core build, where no Android lint runs: both languages hold the same
 * strings with the same placeholders, apostrophes are escaped, and every code the dish can send that the Dish tab
 * shows in plain words has words (the app maps codes to resources in `ui/DishTexts.kt` and `ui/Texts.kt`).
 */
class AppStringsTest {
    private val res = File("../app/src/main/res")
    private val ui = File("../app/src/main/kotlin/tools/apachi/starlinknav/ui")

    private class Entry(val name: String, val value: String, val translatable: Boolean)

    private fun strings(dir: String): Map<String, Entry> {
        val text = File(res, "$dir/strings.xml").readText()
        val out = LinkedHashMap<String, Entry>()
        for (m in Regex("""<string name="([a-z0-9_]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            out[m.groupValues[1]] = Entry(m.groupValues[1], m.groupValues[3], "translatable=\"false\"" !in m.groupValues[2])
        }
        return out
    }

    private val en get() = strings("values")
    private val uk get() = strings("values-uk")

    @Test
    fun bothLanguagesHoldTheSameStrings() {
        val english = en.filterValues { it.translatable }
        val ukrainian = uk
        assertTrue(english.isNotEmpty())
        assertEquals(emptyList(), english.keys.filter { it !in ukrainian }, "missing in values-uk")
        assertEquals(emptyList(), ukrainian.keys.filter { it !in en }, "only in values-uk")
    }

    @Test
    fun placeholdersMatchBetweenTheLanguages() {
        val placeholder = Regex("""%(\d+\$)?[sdf]""")
        val uk = uk
        for ((name, e) in en) {
            val u = uk[name] ?: continue
            assertEquals(
                placeholder.findAll(e.value).map { it.value }.sorted().toList(),
                placeholder.findAll(u.value).map { it.value }.sorted().toList(),
                "placeholders of $name",
            )
        }
    }

    @Test
    fun apostrophesAndQuotesAreEscaped() {
        for ((dir, all) in listOf("values" to en, "values-uk" to uk)) {
            for ((name, e) in all) {
                val bare = e.value.replace("\\'", "").replace("\\\"", "")
                assertTrue('\'' !in bare, "$dir/$name has an unescaped apostrophe")
                assertTrue('"' !in bare, "$dir/$name has an unescaped quote")
                assertTrue('<' !in bare.replace(Regex("""<[a-z/][^>]*>"""), ""), "$dir/$name has a bare <")
                // A string that takes arguments writes a literal percent sign as %%.
                if (Regex("""%(\d+\$)?[sdf]""").containsMatchIn(bare)) {
                    val rest = bare.replace("%%", "").replace(Regex("""%(\d+\$)?[sdf]"""), "")
                    assertTrue('%' !in rest, "$dir/$name has a lone %")
                }
            }
        }
    }

    private fun source(name: String) = File(ui, name).readText()

    /** The body of `fun <name>(` up to the next top-level function. */
    private fun body(src: String, name: String): String {
        val start = src.indexOf("fun $name(")
        assertTrue(start >= 0, "no function $name")
        val next = Regex("""\n(@\w+\s*\n)*(private |internal )?(fun|val) """).find(src, start + 10)
        return src.substring(start, next?.range?.first ?: src.length)
    }

    @Test
    fun everyAlertTheDishCanRaiseHasWords() {
        val allOn = DishAlerts(
            motors_stuck = true, thermal_shutdown = true, thermal_throttle = true, unexpected_location = true, mast_not_near_vertical = true,
            slow_ethernet_speeds = true, install_pending = true, is_heating = true, power_supply_thermal_throttle = true, is_power_save_idle = true,
            dbf_telem_stale = true, low_motor_current = true, lower_signal_than_predicted = true, slow_ethernet_speeds_100 = true,
            obstruction_map_reset = true, dish_water_detected = true, router_water_detected = true, upsu_router_port_slow = true, no_ethernet_link = true,
        )
        val keys = allOn.active().toMutableSet()
        assertEquals(19, keys.size, "every DishAlerts flag is listed by active()")
        val diag = DishGetDiagnosticsResponse(
            alerts = DishGetDiagnosticsResponse.Alerts(true, true, true, true, true, true, true, true, true),
            hardware_self_test = HardwareSelfTest.FAILED, overage_rate_limited = true,
        )
        keys += AlertList.of(null, diag).map { it.key }
        val text = body(source("DishTexts.kt"), "alertRes")
        for (key in keys) assertTrue("\"$key\" ->" in text, "no words for the alert $key")
    }

    @Test
    fun everyEventReasonTheDishSendsHasWords() {
        val text = body(source("DishTexts.kt"), "eventReasonRes")
        val covered = Regex("""(?m)^\s+(\d+) ->""").findAll(text).map { it.groupValues[1].toInt() }.toSet()
        val dishCodes = EventReason.codes.filter { EventReason.scope(it) == EventReason.Scope.DISH } + 0
        for (code in dishCodes) assertTrue(code in covered, "no words for event reason $code (${EventReason.name(code)})")
        // Router and client events are not mapped one by one: they show their code name in a generic sentence.
        assertTrue(EventReason.codes.any { EventReason.scope(it) == EventReason.Scope.ROUTER && it !in covered })
    }

    @Test
    fun everyOutageCauseStartStageAndDisablementHasWords() {
        val dishTexts = source("DishTexts.kt")
        val causes = Regex("""(?m)^\s+(\d+) ->""").findAll(body(dishTexts, "outageCauseRes")).map { it.groupValues[1].toInt() }.toSet()
        for (code in (0..14).filter { OutageCause.name(it) != "CAUSE_$it" }) assertTrue(code in causes, "no words for outage cause $code")
        for (key in InitializationView.KEYS) assertTrue("\"$key\" ->" in body(dishTexts, "initStageRes"), "no words for start-up stage $key")
        val disablement = body(source("Texts.kt"), "disablementReasonRes")
        for (code in listOf(0, 1, 2, 3, 4, 6, 7, 8, 10, 11, 12, 13, 14, 15, 16, 17)) {
            assertTrue(Regex("""(?m)^\s+$code ->""").containsMatchIn(disablement), "no words for disablement code $code")
            assertTrue(Disablement.name(code) != "CODE_$code")
        }
        val linkStates = body(dishTexts, "linkStateRes")
        for (state in DishLinkState.entries) assertTrue("DishLinkState.${state.name} ->" in linkStates, "no words for state $state")
    }

    @Test
    fun everyRouterAlertAndUpdateStateHasWords() {
        val allOn = WifiAlerts(
            thermal_throttle = true, install_pending = true, freshly_fused = true, lan_eth_slow_link_10 = true, lan_eth_slow_link_100 = true,
            wan_eth_poor_connection = true, mesh_topology_changing_often = true, mesh_unreliable_backhaul = true, radius_missing_process = true,
            eth_switch_error = true, poe_on_dish_unreachable = true, poe_fuse_blown = true, poe_router_overcurrent = true, poe_off_current_nominal = true,
            poe_vin_overvoltage = true, poe_vin_undervoltage = true, high_cable_ping_drop_rate = true, sandbox_disabled = true,
            only_overflight_blocked = true, offline_networks_disabled = true, wired_mesh_not_using_wan_iface = true,
        )
        val routerTexts = source("RouterTexts.kt")
        val alerts = body(routerTexts, "routerAlertRes")
        for (key in AlertList.router(allOn)) assertTrue("\"$key\" ->" in alerts, "no words for the router alert $key")
        val states = body(routerTexts, "routerUpdateRes")
        // Every state that is not a failure has words; failures show the generic sentence with the code name.
        for (state in (0..12).filterNot { WifiSoftwareUpdate.isFailure(it) }) {
            assertTrue(Regex("""(?m)^\s+(WifiSoftwareUpdate\.\w+|$state) ->""").containsMatchIn(states), "no words for router update state $state")
        }
        for (band in listOf(WifiBand.RF_2GHZ, WifiBand.RF_5GHZ, WifiBand.RF_5GHZ_HIGH)) {
            assertTrue("WifiBand.${WifiBand.name(band)} ->" in body(routerTexts, "wifiBandRes"), "no words for band $band")
        }
        for (auth in WifiAuth.entries) assertTrue("WifiAuth.${auth.name} ->" in body(routerTexts, "wifiAuthRes"), "no words for $auth")
    }

    @Test
    fun theRouterEventsWithWordsAreRealRouterOrClientEvents() {
        val covered = Regex("""(?m)^\s+(\d+) ->""").findAll(body(source("RouterTexts.kt"), "routerEventRes")).map { it.groupValues[1].toInt() }.toSet()
        assertTrue(covered.isNotEmpty())
        for (code in covered) {
            assertTrue(EventReason.scope(code) in setOf(EventReason.Scope.ROUTER, EventReason.Scope.CLIENT), "event $code is not a router or client event")
        }
        // The common ones: restart, settings change, address change, a device that keeps reconnecting.
        for (code in listOf(13, 14, 15, 16, 17, 21, 42)) assertTrue(code in covered, "no words for router event $code (${EventReason.name(code)})")
    }

    @Test
    fun theRouterTabsTypedWordsAndSectionsAreInBothLanguages() {
        for (name in listOf("confirm_word_change", "confirm_word_bypass", "confirm_word_reset")) {
            assertTrue(name in en && name in uk, name)
        }
        // A typed word is typed on a phone keyboard: one short word, no spaces.
        for ((dir, all) in listOf("values" to en, "values-uk" to uk)) {
            for (name in listOf("confirm_word_change", "confirm_word_bypass", "confirm_word_reset")) {
                val w = all.getValue(name).value
                assertTrue(w.isNotBlank() && ' ' !in w && w.length <= 10, "$dir/$name is not a short word")
            }
        }
    }
}
