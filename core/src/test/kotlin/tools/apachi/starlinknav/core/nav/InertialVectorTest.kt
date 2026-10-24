package tools.apachi.starlinknav.core.nav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The synthetic test vectors of the prototype (`testvec2.py`, PORTING2.md §13): C1 = turn + 10 m sideways step,
 * C2 = step with OPEN → WALK → RETURN_WALK, a 40 s AUTO cut-off, a stop with ZUPT and a handling burst. The port
 * reproduces the served positions to 1e-6 m and the event list exactly.
 */
class InertialVectorTest {
    private class Rec(val t: Double, val out: InertialCore.Out?, val bMode: Int, val x: DoubleArray?)
    private class Run(val served: List<Rec>, val events: List<Triple<Double, String, String>>, val expected: JsonArray, val expEvents: JsonArray)

    private fun run(name: String, config: InertialConfig): Run {
        val text = javaClass.getResource("/inertial/$name.json")!!.readText()
        val doc = Json.parseToJsonElement(text).jsonObject
        val events = ArrayList<Triple<Double, String, String>>()
        val core = InertialCore(config, doc["mount"]!!.jsonPrimitive.double, useImu = true) { t, k, m -> events += Triple(t, k, m) }
        val out = ArrayList<Rec>()
        fun rec(t: Double) = Rec(t, core.served(t), core.bMode, if (core.ekf.ok) core.ekf.x.copyOf() else null)
        for (el in doc["inputs"]!!.jsonArray) {
            val e = el.jsonObject
            val t = e.d("t")
            when (e["kind"]!!.jsonPrimitive.content) {
                "tick" -> core.onTick(t, e.arr("acc"), e.arr("gyr"))
                "diag" -> core.onDiag(e.d("age"))
                "status" -> core.onStatus(t, e.d("heading_x"), e["att_state"]!!.jsonPrimitive.int, e.d("att_unc"), e["gps_valid"]!!.jsonPrimitive.int == 1, e["sats"]!!.jsonPrimitive.int)
                "auto" -> {
                    core.onAuto(t, e.d("e"), e.d("n"), e.d("hspeed"))
                    out += rec(t)
                }
                "serve" -> out += rec(t)
            }
        }
        return Run(out, events, doc["expected"]!!.jsonArray, doc["events"]!!.jsonArray)
    }

    private fun JsonObject.d(k: String) = this[k]!!.jsonPrimitive.double
    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()

    private val pyState = mapOf("tracking" to InertialState.TRACKING, "tracking_raw" to InertialState.TRACKING, "dish_rejected" to InertialState.SIDEWAYS_OPEN,
        "dead_reckoning" to InertialState.DEAD_RECKONING, "no_position" to InertialState.NO_OUTPUT)

    private fun check(name: String, config: InertialConfig = InertialConfig.CFG2, compareAcc: Boolean = true) {
        val r = run(name, config)
        assertEquals(r.expected.size, r.served.size, "$name serves")
        var maxDiff = 0.0
        for ((i, ex) in r.expected.withIndex()) {
            val o = ex.jsonObject
            val rr = r.served[i]
            val t = rr.t
            val got = rr.out
            val bMode = rr.bMode
            assertEquals(o.d("t"), t, "$name t[$i]")
            val se = o["served_e"]
            if (se == null || se is JsonNull) {
                assertTrue(got == null, "$name: nothing expected at $t")
                continue
            }
            got ?: fail("$name: nothing served at $t")
            maxDiff = maxOf(maxDiff, abs(got.e - o.d("served_e")), abs(got.n - o.d("served_n")))
            assertEquals(o.d("served_e"), got.e, 1e-6, "$name served e at $t")
            assertEquals(o.d("served_n"), got.n, 1e-6, "$name served n at $t")
            assertEquals(pyState[o["state"]!!.jsonPrimitive.content], got.state, "$name state at $t")
            assertEquals(o["bmode"]!!.jsonPrimitive.int, bMode, "$name bmode at $t")
            if (compareAcc) assertEquals(o.d("acc68"), got.acc68, 1e-6, "$name acc68 at $t")
            val x = o["x"]!!.jsonArray.map { it.jsonPrimitive.double }
            val mx = rr.x ?: fail("$name: filter not running at $t")
            x.forEachIndexed { k, v -> assertEquals(v, mx[k], 1e-6 * maxOf(1.0, abs(v)), "$name x[$k] at $t") }
        }
        val pyKinds = r.expEvents.map { it.jsonObject["kind"]!!.jsonPrimitive.content }.toSet()
        val mine = r.events.filter { it.second in pyKinds }
        assertEquals(r.expEvents.size, mine.size, "$name events: ${mine.map { "%.2f %s".format(it.first, it.second) }}")
        for ((i, ev) in r.expEvents.withIndex()) {
            val o = ev.jsonObject
            assertEquals(o["kind"]!!.jsonPrimitive.content, mine[i].second, "$name event $i")
            assertEquals(o.d("t"), mine[i].first, 1e-9, "$name event $i time")
            if (mine[i].second == "state") {
                assertEquals("to=${pyState[o["msg"]!!.jsonPrimitive.content]!!.name}", mine[i].third.substringAfter(' '), "$name state event $i")
            }
        }
        println("$name: ${r.served.size} serves, max |served − expected| = $maxDiff m")
    }

    @Test
    fun turnAndSidewaysStepC1() = check("testvec2_C1")

    @Test
    fun stepCutoffStopAndHandlingC2() = check("testvec2_C2")

    @Test
    fun portSideChangesDoNotMoveTheVectorTracks() {
        // every port-side change except the exit policy of the sideways hold, which intentionally changes how the
        // vectors' faults end (next test)
        val c = InertialConfig.FIXED_HOLD.copy(cusumH = 6.0, holdMax = 15.0)
        check("testvec2_C1", c, compareAcc = false)
        check("testvec2_C2", c, compareAcc = false)
    }

    /**
     * The exit policy of the sideways hold changes the vectors only where their faults end (the prototype's expected
     * values cannot be regenerated, so the differences are pinned here):
     * - C1 (+10 m step on fix times 40-50 s): the prototype closed 6 s after the dish came back (dish_ok at 56.45);
     *   the reference takes AUTO back at once at 50.45 (dish_returning cause=reference), the track differs by < 0.5 m.
     * - C2 (+10 m step on fix times 30-60 s): the prototype walked back onto the faulty dish at 46.45 (hold 15 s) and
     *   was up to 5 m off the road when the dish returned; the hold now goes on while the offset is significant, stays
     *   on the road, and takes AUTO back at 60.45-61.45; from there on the track is the prototype's within 0.5 m.
     */
    @Test
    fun theExitPolicyChangesOnlyHowTheVectorFaultsEnd() {
        val c = InertialConfig.SHIPPED.copy(cusumH = 6.0, holdMax = 15.0)
        for ((name, faultEnd) in listOf("testvec2_C1" to 50.45, "testvec2_C2" to 60.45)) {
            val text = javaClass.getResource("/inertial/$name.json")!!.readText()
            val autos = Json.parseToJsonElement(text).jsonObject["inputs"]!!.jsonArray.map { it.jsonObject }
                .filter { it["kind"]!!.jsonPrimitive.content == "auto" && it.d("t") in 5.0..19.5 }
            // the fault-free road before the fault (C1 turns at 20-30 s, so its line is only used before the turn)
            val a = autos.first(); val b = autos.last()
            val (ue, un) = (b.d("e") - a.d("e")) to (b.d("n") - a.d("n"))
            val len = hypot(ue, un)
            fun cross(e: Double, n: Double) = abs((e - a.d("e")) * un / len - (n - a.d("n")) * ue / len)
            val r = run(name, c)
            val opened = r.events.first { it.second == "dish_degraded" }.first
            for ((i, ex) in r.expected.withIndex()) {
                val o = ex.jsonObject
                val rr = r.served[i]
                val got = rr.out ?: continue
                val se = o["served_e"]
                if (se == null || se is JsonNull) continue
                val d = hypot(got.e - o.d("served_e"), got.n - o.d("served_n"))
                when {
                    rr.t < minOf(opened + 15.0 + 0.5, faultEnd - 0.01) -> assertTrue(d < 1e-6, "$name identical before the walk back / return at ${rr.t}: $d")
                    rr.t < faultEnd - 0.1 -> {
                        assertEquals(B_OPEN, rr.bMode, "$name still holding at ${rr.t}")
                        if (name == "testvec2_C2") assertTrue(cross(got.e, got.n) < 1.0, "$name on the road at ${rr.t}: ${cross(got.e, got.n)}")
                    }
                    rr.t > faultEnd + 1.5 -> assertTrue(d < 0.5, "$name back on the prototype's track at ${rr.t}: $d")
                }
            }
            val ret = r.events.single { it.second == "dish_returning" }
            assertTrue(ret.third.startsWith("cause=reference") && ret.first in faultEnd - 0.01..faultEnd + 1.01, "$name: $ret")
            assertTrue(r.events.none { it.second == "dish_degraded" && it.first > opened }, "$name: no second hold")
            assertTrue(r.events.none { it.second == "dish_recovering" }, "$name: no walk back onto the faulty dish")
        }
    }

    @Test
    fun portSideAccuracyIsNeverBelowTheFloorNorTheOpenOffset() {
        val c = InertialConfig.SHIPPED.copy(cusumH = 6.0, holdMax = 15.0)
        val r = run("testvec2_C2", c)
        for (rr in r.served) {
            val o = rr.out ?: continue
            assertTrue(o.accuracyM >= 5.0 - 1e-9, "floor at ${rr.t}")
            if (rr.bMode != B_TIGHT) assertTrue(o.accuracyM >= abs(o.bCross) - 1e-9, "open offset at ${rr.t}")
        }
    }
}
