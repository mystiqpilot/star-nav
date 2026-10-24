package tools.apachi.starlinknav.core.nav

import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.FakeDishSimulation
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.core.dish.GrpcStatus
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic drives in the style of the prototype's test vectors (`testvec2.py`): phone up = +y, forward = +z,
 * AUTO received 0.45 s after its fix time, status at k + 0.2 s with heading = car heading + mount, diagnostics
 * age 0.45 s every 10 s, 1 Hz serves inside AUTO gaps.
 */
private class Synth(
    val tEnd: Double,
    val head: (Double) -> Double = { 30.0 },
    /** Signed: negative = reversing. */
    val speed: (Double) -> Double = { 15.0 },
    val autoOk: (Double) -> Boolean = { true },
    val step: (Double) -> Double = { 0.0 },
    val net: ((Double) -> Boolean)? = null,
    val netNoiseM: Double = 15.0,
    val mount: Double = -177.0,
) {
    sealed class In(val t: Double, val order: Int)
    class Tick(t: Double, val acc: DoubleArray, val gyr: DoubleArray) : In(t, 0)
    class Diag(t: Double, val age: Double) : In(t, 1)
    class Status(t: Double, val hx: Double) : In(t, 2)
    class Auto(t: Double, val e: Double, val n: Double, val hs: Double) : In(t, 3)
    class Net(t: Double, val e: Double, val n: Double, val acc: Double) : In(t, 4)
    class Serve(t: Double) : In(t, 5)

    val dt = 0.1
    val ticks = (0..(tEnd / dt).toInt()).map { it / 10.0 }
    val pos: List<DoubleArray>
    val inputs: List<In>

    init {
        val p = ArrayList<DoubleArray>()
        p += doubleArrayOf(0.0, 0.0)
        for (t in ticks.drop(1)) {
            val h = Math.toRadians(head(t - dt))
            val v = speed(t - dt)
            val last = p.last()
            p += doubleArrayOf(last[0] + v * dt * sin(h), last[1] + v * dt * cos(h))
        }
        pos = p
        val ev = ArrayList<In>()
        for (t in ticks) {
            val w = Math.toRadians((head(t + dt) - head(t)) / dt)
            val af = (speed(t + dt) - speed(t)) / dt
            ev += Tick(t, doubleArrayOf(0.0, 9.80665, af), doubleArrayOf(0.0, -w, 0.0))
        }
        val rnd = Random(7)
        for (k in 1 until tEnd.toInt()) {
            val tr = k + 0.45
            val tf = tr - 0.45
            ev += Status(k + 0.2, wrapDeg(head(k + 0.2) + mount))
            if (k % 10 == 0) ev += Diag(k + 0.3, 0.45)
            if (net?.invoke(k + 0.6) == true) {
                val q = truth(k + 0.6)
                ev += Net(k + 0.6, q[0] + rnd.nextDouble(-1.0, 1.0) * netNoiseM, q[1] + rnd.nextDouble(-1.0, 1.0) * netNoiseM, 25.0)
            }
            if (!autoOk(tr)) continue
            val q = pos[Math.round(tf / dt).toInt()].copyOf()
            val h = Math.toRadians(head(tf))
            q[0] += step(tf) * cos(h)
            q[1] -= step(tf) * sin(h)
            ev += Auto(tr, q[0], q[1], abs(speed(tf)))
        }
        val at = ev.filterIsInstance<Auto>().map { it.t }.sorted()
        for ((a, b) in at.zipWithNext()) if (b - a > 1.5) {
            var s = a + 1.0
            while (s < b - 0.5) { ev += Serve(s); s += 1.0 }
        }
        inputs = ev.sortedWith(compareBy<In>({ it.t }, { it.order }))
    }

    /** True position at [t] (linear between ticks). */
    fun truth(t: Double): DoubleArray {
        val i = (t / dt).toInt().coerceIn(0, pos.size - 2)
        val k = (t - ticks[i]) / dt
        return doubleArrayOf(pos[i][0] + (pos[i + 1][0] - pos[i][0]) * k, pos[i][1] + (pos[i + 1][1] - pos[i][1]) * k)
    }

    class Served(val t: Double, val out: InertialCore.Out?, val state: InertialState, val err: Double)

    fun run(
        config: InertialConfig, useImu: Boolean = true, nullImu: Boolean = false,
        events: MutableList<Triple<Double, String, String>> = ArrayList(), poke: (Double, InertialCore) -> Unit = { _, _ -> },
        recordFires: Boolean = false,
    ): List<Served> {
        val core = InertialCore(config, mount, useImu, recordFires = recordFires) { t, k, m -> events += Triple(t, k, m) }
        val out = ArrayList<Served>()
        for (e in inputs) {
            poke(e.t, core)
            when (e) {
                is Tick -> if (nullImu) core.onTick(e.t, null, null) else core.onTick(e.t, e.acc, e.gyr)
                is Diag -> core.onDiag(e.age)
                is Status -> core.onStatus(e.t, e.hx, 2, 0.4, true, 17)
                is Net -> core.onNetwork(e.t, e.t, e.e, e.n, e.acc)
                is Auto -> {
                    core.onAuto(e.t, e.e, e.n, e.hs)
                    out += served(core, e.t)
                }
                is Serve -> out += served(core, e.t)
            }
        }
        return out
    }

    private fun served(core: InertialCore, t: Double): Served {
        val o = core.served(t)
        val q = truth(t)
        return Served(t, o, core.lastState, if (o == null) Double.NaN else hypot(o.e - q[0], o.n - q[1]))
    }
}

class InertialNavTest {

    @Test
    fun firesAreKeptOnlyWhenAsked() {
        // The live filter runs for hours: its detector confirms and gate rejects are counted, not listed (the list is
        // for the desktop tools' false-alarm statistics).
        val synth = Synth(100.0, step = { tf -> if (tf in 40.0..70.0) 30.0 else 0.0 })
        var plain: InertialCore? = null
        val ev = ArrayList<Triple<Double, String, String>>()
        synth.run(InertialConfig.SHIPPED, events = ev, poke = { _, c -> plain = c })
        assertTrue(ev.named("dish_degraded").isNotEmpty(), "the fault opened a hold: $ev")
        assertTrue(plain!!.fires.isEmpty(), "${plain!!.fires}")
        var tool: InertialCore? = null
        synth.run(InertialConfig.SHIPPED, poke = { _, c -> tool = c }, recordFires = true)
        assertTrue(tool!!.fires.any { it.second == "det" }, "${tool!!.fires}")
    }
    @Test
    fun reversingInAGapHoldsThePositionInsteadOfDrivingOn() {
        // 10 m/s, stop at 25 s, reverse at 3 m/s from 28 s; AUTO gap (receipts) 40-55 s while reversing
        val speed = { t: Double -> when { t < 20 -> 10.0; t < 25 -> 10.0 - 2.0 * (t - 20); t < 28 -> 0.0; else -> -3.0 } }
        val synth = Synth(70.0, speed = speed, autoOk = { it < 40.0 || it >= 55.0 })
        val ev = ArrayList<Triple<Double, String, String>>()
        val all = synth.run(InertialConfig.SHIPPED, events = ev)
        val hold = all.filter { it.t in 41.0..55.0 }
        assertTrue(ev.any { it.second == "reversing" && it.third.startsWith("state=on") && it.first < 40 }, "reversing seen from the AUTO chords: $ev")
        assertTrue(ev.any { it.second == "hold" && it.third.startsWith("reason=reversing") }, "hold logged: $ev")
        // the served position does not run forward along the heading, and the accuracy grows with the time held
        val first = hold.first().out!!
        assertTrue(hold.all { hypot(it.out!!.e - first.e, it.out.n - first.n) < 0.5 }, "held")
        assertTrue(hold.zipWithNext().all { (a, b) -> b.out!!.accuracyM > a.out!!.accuracyM }, "growing accuracy")
        for (s in hold) {
            val o = assertNotNull(s.out, "served at ${s.t}")
            assertTrue(s.err <= o.accuracyM, "honest accuracy at ${s.t}: error ${s.err} vs ${o.accuracyM}")
        }
        // AUTO back: the held covariance grew with the told accuracy, so the first fix is taken (no gate reject)
        assertTrue(ev.none { it.first > 54 && (it.second == "dish_gate_reject" || it.second == "dish_reanchor") }, "$ev")
        val back = all.filter { it.t in 55.0..70.0 }
        assertTrue(back.isNotEmpty())
        for (s in back) {
            val o = assertNotNull(s.out, "served at ${s.t}")
            assertTrue(s.err <= o.accuracyM, "honest after the return at ${s.t}: error ${s.err} vs ${o.accuracyM}")
        }
    }

    @Test
    fun reversingWithAutoPresentIsNotPushedForward() {
        // stop at 25 s, reverse from 28 s up to 6 m/s; AUTO answers throughout
        val speed = { t: Double -> when { t < 20 -> 10.0; t < 25 -> 10.0 - 2.0 * (t - 20); t < 28 -> 0.0; t < 31 -> -(t - 28) * 2.0; else -> -6.0 } }
        val synth = Synth(60.0, speed = speed)
        val ev = ArrayList<Triple<Double, String, String>>()
        val rev = synth.run(InertialConfig.SHIPPED, events = ev).filter { it.t in 33.0..60.0 }
        assertTrue(ev.any { it.second == "reversing" && it.third.startsWith("state=on") }, "$ev")
        for (s in rev) {
            val o = assertNotNull(s.out, "served at ${s.t}")
            assertTrue(o.reversing && o.state == InertialState.TRACKING, "reversing at ${s.t}")
            assertTrue(s.err <= o.accuracyM, "honest while reversing at ${s.t}: error ${s.err} vs ${o.accuracyM}")
            assertTrue(s.err < 4.0, "not pushed forward at ${s.t}: ${s.err}")
            assertEquals(6.0, o.speed, 0.01)
        }
        // forward again: reversing ends, nothing held
        val fwd = Synth(90.0, speed = { t -> if (t < 60) speed(t) else if (t < 63) -6.0 + 4.0 * (t - 60) else 6.0 })
        val ev2 = ArrayList<Triple<Double, String, String>>()
        val out2 = fwd.run(InertialConfig.SHIPPED, events = ev2).filter { it.t in 70.0..90.0 }
        assertTrue(ev2.any { it.second == "reversing" && it.third.startsWith("state=off") }, "$ev2")
        assertTrue(out2.all { it.out != null && !it.out.reversing && it.err < 2.0 }, "forward again: max ${out2.maxOf { it.err }}")
    }

    @Test
    fun stoppingThenDrivingOffInAGapTakesAutoBack() {
        // stopped from 25 s, AUTO lost at 30 s, the car drives off at 35-40 s (no forward axis learned, so the
        // filter cannot see it), AUTO back at 70 s some 300 m further on
        val speed = { t: Double -> when { t < 20 -> 10.0; t < 25 -> 10.0 - 2.0 * (t - 20); t < 35 -> 0.0; t < 40 -> 2.0 * (t - 35); else -> 10.0 } }
        val synth = Synth(90.0, speed = speed, autoOk = { it < 30.0 || it >= 70.0 })
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = synth.run(InertialConfig.SHIPPED, events = ev)
        assertTrue(ev.any { it.second == "hold" && it.third.startsWith("reason=slow") }, "$ev")
        assertTrue(ev.none { it.first > 69 && (it.second == "dish_gate_reject" || it.second == "dish_reanchor") }, "$ev")
        val back = out.filter { it.t in 70.0..90.0 }
        assertTrue(back.all { it.out != null && it.err <= it.out.accuracyM && it.err < 6.0 }, "after the return: max ${back.maxOf { it.err }}")
    }

    @Test
    fun crawlingInAGapAlsoHolds() {
        val speed = { t: Double -> if (t < 20) 10.0 else if (t < 25) 10.0 - 1.8 * (t - 20) else 1.0 }
        val synth = Synth(60.0, speed = speed, autoOk = { it < 35.0 || it >= 50.0 })
        val ev = ArrayList<Triple<Double, String, String>>()
        val all = synth.run(InertialConfig.SHIPPED, events = ev)
        val out = all.filter { it.t in 35.5..50.0 }
        assertTrue(ev.any { it.second == "hold" && it.third.startsWith("reason=slow") }, "$ev")
        assertTrue(out.all { it.out != null && it.err <= it.out.accuracyM }, "honest while holding")
        val back = all.filter { it.t in 50.0..60.0 }
        assertTrue(ev.none { it.first > 49 && (it.second == "dish_gate_reject" || it.second == "dish_reanchor") }, "$ev")
        assertTrue(back.all { it.out != null && it.err <= it.out.accuracyM }, "honest after the return: ${back.map { it.err to it.out?.accuracyM }}")
    }

    @Test
    fun afterAResetAFreshRawFixIsNotServedEither() {
        for (config in listOf(InertialConfig.SHIPPED, InertialConfig.CFG2)) {
            val core = InertialCore(config, -177.0, false) { _, _, _ -> }
            val psi = Math.toRadians(30.0)
            val hx = wrapDeg(30.0 - 177.0)
            for (k in 0 until 300) {
                val t = k / 10.0
                core.onTick(t, null, null)
                if (k % 10 == 2) core.onStatus(t, hx, 2, 0.4, true, 17)
                if (k % 10 == 4) core.onAuto(t, 15.0 * (t - 0.45) * sin(psi), 15.0 * (t - 0.45) * cos(psi), 15.0)
            }
            assertNotNull(core.served(29.5))
            core.ekf.x[0] = Double.NaN
            core.onTick(30.0, null, null)
            assertEquals(1, core.nResets)
            // no status for 3 s and a crawling car: the next fix cannot re-initialise (no heading)
            for (k in 301 until 340) core.onTick(k / 10.0, null, null)
            core.onAuto(33.45, 100.0, 200.0, 1.0)
            val o = core.served(33.5)
            if (config === InertialConfig.SHIPPED) {
                assertNull(o, "nothing until re-initialised")
                assertEquals(InertialState.NO_OUTPUT, core.lastState)
            } else {
                assertTrue(o?.raw == true, "prototype: the raw fix")
            }
        }
    }

    @Test
    fun watchdogResetServesNothingUntilAFreshFixReinitialises() {
        val synth = Synth(90.0, autoOk = { it < 40.0 || it >= 60.0 })
        for (config in listOf(InertialConfig.SHIPPED, InertialConfig.CFG2)) {
            val ev = ArrayList<Triple<Double, String, String>>()
            var poked = false
            val out = synth.run(config, events = ev, poke = { t, core -> if (!poked && t >= 45.0) { core.ekf.x[0] = Double.NaN; poked = true } })
            assertTrue(ev.any { it.second == "filter_reset" }, "reset logged")
            val gap = out.filter { it.t in 45.5..59.9 }
            assertTrue(gap.isNotEmpty())
            if (config === InertialConfig.SHIPPED) {
                assertTrue(gap.all { it.out == null && it.state == InertialState.NO_OUTPUT }, "nothing served after the reset")
            } else {
                // the prototype serves its stale start-up fix (audit item 8)
                assertTrue(gap.all { it.out?.raw == true && it.err > 500 }, "prototype: stale raw fix")
            }
            val back = out.filter { it.t > 60.0 }
            assertTrue(back.all { it.out != null && it.err < 3.0 }, "re-initialised by the next AUTO fix (${config.accFloorM})")
            assertEquals(2, ev.count { it.second == "filter_init" })
        }
    }

    @Test
    fun pushStopsWhenDeadReckoningIsTooUncertainAndResumesWithAuto() {
        val synth = Synth(260.0, head = { 45.0 }, autoOk = { it < 30.0 || it >= 200.0 })
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = synth.run(InertialConfig.SHIPPED, useImu = false, events = ev)
        val gap = out.filter { it.t in 32.5..199.9 }
        assertEquals(InertialState.DEAD_RECKONING, gap.first().state)
        val stop = gap.firstOrNull { it.out == null } ?: error("no push stop in a 170 s gap")
        assertEquals(InertialState.NO_OUTPUT, stop.state)
        assertTrue(gap.filter { it.t >= stop.t }.all { it.out == null }, "stays stopped")
        assertTrue(gap.filter { it.t < stop.t }.all { it.out!!.acc68 <= 300.0 && it.out.cross68 <= 50.0 })
        assertTrue(ev.any { it.second == "push_stop" })
        val back = out.filter { it.t > 201.0 }
        assertTrue(back.all { it.out != null && it.state == InertialState.TRACKING && it.err < 5.0 }, "back on AUTO")
    }

    @Test
    fun withoutAnImuTheDishCarriesEverything() {
        val synth = Synth(60.0, head = { t -> 30.0 + 6.0 * minOf(maxOf(t - 20.0, 0.0), 10.0) })
        val none = synth.run(InertialConfig.SHIPPED, useImu = false)
        val nullSamples = synth.run(InertialConfig.SHIPPED, useImu = true, nullImu = true)
        assertTrue(none.all { it.out != null && it.err < 1.5 }, "max ${none.maxOf { it.err }}")
        assertEquals(none.map { it.out!!.e to it.out.n }, nullSamples.map { it.out!!.e to it.out.n }, "no samples = no IMU")
    }

    @Test
    fun networkFixesHoldTheDeadReckoningInALongOutage() {
        // the car slows from 15 to 5 m/s inside a 100 s outage; no IMU, so dead reckoning keeps 15 m/s
        val speed = { t: Double -> if (t < 35) 15.0 else if (t < 45) 15.0 - (t - 35) else 5.0 }
        val net = Synth(150.0, head = { 45.0 }, speed = speed, autoOk = { it < 30.0 || it >= 130.0 }, net = { it.toInt() % 5 == 0 })
        val ev = ArrayList<Triple<Double, String, String>>()
        val aided = net.run(InertialConfig.SHIPPED, useImu = false, events = ev).filter { it.t in 30.5..129.9 }
        val alone = net.run(InertialConfig.SHIPPED.copy(network = false), useImu = false).filter { it.t in 30.5..129.9 }
        assertTrue(aided.any { it.state == InertialState.NETWORK_AIDED }, "network-aided state")
        val late = aided.filter { it.t > 60 }
        assertTrue(late.all { it.out != null && it.err < 100.0 && it.err <= it.out.accuracyM }, "aided max ${late.maxOf { it.err }}")
        val lateAlone = alone.filter { it.t in 60.0..70.0 && it.out != null }
        assertTrue(lateAlone.isEmpty() || lateAlone.minOf { it.err } > 2 * late.filter { it.t <= 70 }.maxOf { it.err }, "network helps")
        // network fixes are ignored while AUTO answers
        val clean = Synth(40.0, net = { true }, netNoiseM = 200.0)
        val withNet = clean.run(InertialConfig.SHIPPED)
        val noNet = Synth(40.0).run(InertialConfig.SHIPPED)
        assertEquals(noNet.map { it.out!!.e }, withNet.map { it.out!!.e })
    }

    @Test
    fun theWatchOnlyDetectorNeverChangesWhatIsServed() {
        // a 7 m sideways step: enough for the watch-only CUSUM (h 6 m), not for the acting one (h 9 m)
        val synth = Synth(90.0, step = { tf -> if (tf in 40.0..55.0) 7.0 else 0.0 })
        val ev = ArrayList<Triple<Double, String, String>>()
        val watched = synth.run(InertialConfig.SHIPPED, events = ev)
        val plain = synth.run(InertialConfig.SHIPPED.copy(watchCusumH = null))
        assertTrue(ev.any { it.second == "watch_suspect" }, "watch-only fired: $ev")
        assertTrue(ev.none { it.second == "dish_degraded" }, "acting detector stayed closed")
        assertEquals(plain.map { Triple(it.out?.e, it.out?.n, it.out?.accuracyM) }, watched.map { Triple(it.out?.e, it.out?.n, it.out?.accuracyM) })
        assertEquals(plain.map { it.state }, watched.map { it.state })
    }

    @Test
    fun accuracyWhileOpenCoversTheSidewaysOffset() {
        val synth = Synth(90.0, step = { tf -> if (tf in 40.0..70.0) 15.0 else 0.0 })
        val out = synth.run(InertialConfig.SHIPPED)
        val open = out.filter { it.state == InertialState.SIDEWAYS_OPEN }
        assertTrue(open.isNotEmpty(), "opened")
        for (s in open) assertTrue(s.out!!.accuracyM >= abs(s.out.bCross), "accuracy ${s.out.accuracyM} vs offset ${s.out.bCross}")
        assertTrue(out.all { it.out == null || it.out.accuracyM >= 5.0 })
    }

    // ------------------------------------------------------------------ exit policy of the sideways hold

    private fun List<Triple<Double, String, String>>.named(vararg n: String) = filter { it.second in n }

    @Test
    fun aLongSidewaysFaultIsHeldBeyond25sWhileDeadReckoningIsGood() {
        // 30 m to the right for 80 s (fix times 40-120 s) on a straight road at 15 m/s
        val synth = Synth(160.0, step = { tf -> if (tf in 40.0..120.0) 30.0 else 0.0 })
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = synth.run(InertialConfig.SHIPPED, events = ev)
        val old = synth.run(InertialConfig.FIXED_HOLD)
        val open = ev.named("dish_degraded").single()
        assertTrue(open.first in 40.0..44.0, "opened at the onset: $ev")
        // still holding up to the ceiling (holdCeilS 60 s; the fixed hold walked back after 25 s)
        val ceil = InertialConfig.SHIPPED.holdCeilS
        val late = out.filter { it.t in (open.first + 30)..(open.first + ceil - 1) }
        assertTrue(late.all { it.state == InertialState.SIDEWAYS_OPEN && it.err < 3.0 }, "held: max ${late.maxOf { it.err }}")
        val oldLate = old.filter { it.t in (open.first + 60)..119.0 }
        assertTrue(oldLate.all { it.err > 20.0 }, "the fixed hold is back on the faulty dish: min ${oldLate.minOf { it.err }}")
        val walk = ev.named("dish_recovering").single()
        assertTrue(walk.first - open.first in ceil..(ceil + 2), "walk back at the ceiling: $ev")
        // the told accuracy still covers the offset while held
        assertTrue(late.all { it.out!!.accuracyM >= abs(it.out.bCross) })
        // and the return at 120 s is taken at once
        val ret = ev.named("dish_returning").single { it.first > 120.0 }
        assertTrue(ret.first < 123.5 && ret.third.startsWith("cause=reference"), "$ret")
        assertTrue(out.filter { it.t > 124.0 }.all { it.err < 2.0 && it.state == InertialState.TRACKING }, "back on AUTO")
    }

    @Test
    fun theDishsReturnAfterTheHoldEndedIsAcceptedAtOnceWithoutASecondHold() {
        // 20 m to the left for 100 s at 12 m/s: the hold ends (no longer significant against the dead reckoning) and the
        // filter walks back onto the faulty dish; the dish's return at 130 s must not be held as a new fault.
        val synth = Synth(200.0, speed = { 12.0 }, step = { tf -> if (tf in 30.0..130.0) -20.0 else 0.0 })
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = synth.run(InertialConfig.SHIPPED, events = ev)
        val evOld = ArrayList<Triple<Double, String, String>>()
        val old = synth.run(InertialConfig.FIXED_HOLD, events = evOld)
        val walk = ev.named("dish_recovering").single()
        assertTrue(walk.first in 55.0..129.0, "held past 25 s, then walked back: $walk")
        assertTrue(out.filter { it.t in 33.0..(walk.first - 1) }.all { it.err < 3.0 }, "held on the road")
        // the return: one reference return, no second opening
        val ret = ev.named("dish_returning").single { it.first > 130.0 }
        assertTrue(ret.first < 133.5 && ret.third.startsWith("cause=reference"), "$ret")
        assertTrue(ev.named("dish_degraded").none { it.first > 130.0 }, "no second hold: $ev")
        val after = out.filter { it.t > ret.first + 0.5 }
        assertTrue(after.all { it.err < 2.0 && it.state == InertialState.TRACKING }, "on AUTO at once: max ${after.maxOf { it.err }}")
        // what the fixed hold did: the return step opened a second hold, ~20 m off while the dish was right
        assertTrue(evOld.named("dish_degraded").any { it.first > 130.0 }, "old: second hold: $evOld")
        assertTrue(old.filter { it.t in 135.0..150.0 }.any { it.err > 10.0 })
    }

    @Test
    fun aFaultEndingInsideTheHoldIsTakenBackAtOnce() {
        val synth = Synth(120.0, step = { tf -> if (tf in 30.0..70.0) 15.0 else 0.0 })
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = synth.run(InertialConfig.SHIPPED, events = ev)
        val ret = ev.named("dish_returning", "dish_ok").first { it.first > 70.0 }
        assertTrue(ret.first < 73.5, "$ev")
        assertTrue(ev.named("dish_degraded").size == 1, "$ev")
        assertTrue(out.filter { it.t > ret.first + 0.5 }.all { it.err < 2.0 }, "max ${out.filter { it.t > ret.first + 0.5 }.maxOf { it.err }}")
    }

    @Test
    fun aFalseAlarmClosesQuickly() {
        // a two-fix glitch 12 m to the right (fix times 40 and 41 s): the detector opens, AUTO is right again at 42 s
        val synth = Synth(90.0, step = { tf -> if (tf in 39.9..41.1) 12.0 else 0.0 })
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = synth.run(InertialConfig.SHIPPED, events = ev)
        val open = ev.named("dish_degraded").single()
        val close = ev.named("dish_returning", "dish_ok").first { it.first >= open.first }
        assertTrue(close.first - open.first <= 5.0, "closed ${close.first - open.first} s after opening: $ev")
        assertTrue(ev.named("dish_recovering").isEmpty(), "no hold to walk back from: $ev")
        assertTrue(out.filter { it.t > close.first + 0.5 }.all { it.err < 2.0 && it.state == InertialState.TRACKING })
        // and the watch-only detector still logs
        assertTrue(ev.named("watch_suspect").isNotEmpty(), "$ev")
    }

    @Test
    fun aGradualReturnIsFollowedAndCloses() {
        // 25 m to the right from 30 s, back over 50 s (0.5 m/s, like drive 2's real episode) from 60 s
        val synth = Synth(160.0, step = { tf -> when { tf < 30 -> 0.0; tf < 60 -> 25.0; tf < 110 -> 25.0 - 0.5 * (tf - 60); else -> 0.0 } })
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = synth.run(InertialConfig.SHIPPED, events = ev)
        assertTrue(ev.named("dish_degraded").size == 1, "one hold: $ev")
        assertTrue(out.filter { it.t in 33.0..110.0 }.all { it.err < 5.0 }, "held/returning: max ${out.filter { it.t in 33.0..110.0 }.maxOf { it.err }}")
        assertTrue(out.filter { it.t > 115.0 }.all { it.err < 2.0 && it.state == InertialState.TRACKING })
    }

    // exit policy, review 2026-10-15: highway speeds, partial and jumpy returns, gaps, undetected onsets

    private fun dishEvents(ev: List<Triple<Double, String, String>>) =
        ev.filter { it.second.startsWith("dish") }.joinToString("; ") { "%.1f %s %s".format(java.util.Locale.ROOT, it.first, it.second, it.third) }

    @Test
    fun atHighwaySpeedTheReturnAfterTheReferenceExpiredIsTakenAtOnce() {
        // the reference expires after ~95 m driven per metre of offset (2σ = 2 · tan 0.3° · distance): 30 m for 200 s at
        // 25 m/s and 15 m for 90 s at 30 m/s both outlive it; the return mirrors the onset and is taken at once
        for ((m, len, v) in listOf(Triple(30.0, 200.0, 25.0), Triple(15.0, 90.0, 30.0), Triple(-30.0, 220.0, 30.0))) {
            val end = 30.0 + len
            val synth = Synth(end + 100, speed = { v }, step = { tf -> if (tf in 30.0..end) m else 0.0 })
            val ev = ArrayList<Triple<Double, String, String>>()
            val out = synth.run(InertialConfig.SHIPPED, events = ev)
            val what = "$m m for $len s at $v m/s: ${dishEvents(ev)}"
            assertTrue(ev.named("dish_reference_end").any { it.third == "reason=expired" && it.first < end }, what)
            val ret = ev.named("dish_returning").firstOrNull { it.first > end } ?: error(what)
            assertTrue(ret.first < end + 3.5 && ret.third.startsWith("cause=mirror"), what)
            assertTrue(ev.named("dish_degraded").none { it.first > end }, "no second hold: $what")
            val after = out.filter { it.t > ret.first + 0.5 }
            assertTrue(after.all { it.err < 3.0 && it.state == InertialState.TRACKING }, "on AUTO at once: max ${after.maxOf { it.err }}; $what")
        }
    }

    @Test
    fun aJumpyFaultKeepsItsReferenceAcrossLooseReturnsAndItsEndIsTakenAtOnce() {
        // the offset jumps 25/18/30/12/27/16/29/20/24/15 m every 5 s (like real interference): the drop to 12 m is taken
        // as a loose return, the rise after it is the same fault (no new reference on the faulty track), the end a return
        val lv = doubleArrayOf(25.0, 18.0, 30.0, 12.0, 27.0, 16.0, 29.0, 20.0, 24.0, 15.0)
        val synth = Synth(160.0, step = { tf -> if (tf in 30.0..79.99) lv[((tf - 30) / 5).toInt().coerceIn(0, 9)] else 0.0 })
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = synth.run(InertialConfig.SHIPPED, events = ev)
        val old = synth.run(InertialConfig.FIXED_HOLD)
        val what = dishEvents(ev)
        assertTrue(ev.named("dish_returning").any { it.third.startsWith("cause=reference_loose") && it.first < 80 }, what)
        // after a loose return the told accuracy covers what is left of the offset
        val loose = out.filter { o -> o.t < 80 && o.state == InertialState.TRACKING && ev.named("dish_returning").any { it.third.startsWith("cause=reference_loose") && it.first <= o.t } }
        assertTrue(loose.all { it.out!!.accuracyM >= 0.8 * it.err }, "told vs error: ${loose.map { "%.0f:%.1f/%.1f".format(java.util.Locale.ROOT, it.t, it.err, it.out!!.accuracyM) }}; $what")
        val ret = ev.named("dish_returning").first { it.first > 80.0 }
        assertTrue(ret.first < 83.5 && ret.third.startsWith("cause=reference "), what)
        assertTrue(ev.named("dish_degraded").none { it.first > 80.0 }, "no hold after the end: $what")
        val after = out.filter { it.t > ret.first + 0.5 }
        assertTrue(after.all { it.err < 2.0 }, "after: max ${after.maxOf { it.err }}; $what")
        // and no worse than the fixed hold during the fault (the review measured 13.1 m mean before this fix vs 0.4 m)
        fun mean(o: List<Synth.Served>) = o.filter { it.t in 33.0..80.0 && !it.err.isNaN() }.map { it.err }.average()
        assertTrue(mean(out) < mean(old) + 3.0, "during: ${mean(out)} vs fixed hold ${mean(old)}")
    }

    @Test
    fun aDropShortOfTheRoadIsToldHonestlyAndTheTrueReturnIsTakenAtOnce() {
        // 30 m (30-60 s), then 14 m (60-90 s), then back on the road: the 30 → 14 m jump is taken as a loose return
        // (AUTO followed, accuracy ≥ 14 m), the reference stays, so the step back at 90 s is the return, not a new fault
        for (v in listOf(12.0, 25.0)) {
            val synth = Synth(160.0, speed = { v }, step = { tf -> when { tf < 30 -> 0.0; tf < 60 -> 30.0; tf < 90 -> 14.0; else -> 0.0 } })
            val ev = ArrayList<Triple<Double, String, String>>()
            val out = synth.run(InertialConfig.SHIPPED, events = ev)
            val what = "v $v: ${dishEvents(ev)}"
            val mid = out.filter { it.t in 63.0..89.0 && it.out != null }
            assertTrue(mid.all { it.out!!.accuracyM >= 0.8 * it.err }, "told vs error 63-89 s: ${mid.map { "%.1f/%.1f".format(java.util.Locale.ROOT, it.err, it.out!!.accuracyM) }}; $what")
            val ret = ev.named("dish_returning").firstOrNull { it.first > 90.0 } ?: error(what)
            assertTrue(ret.first < 93.5 && ret.third.startsWith("cause=reference "), what)
            assertTrue(ev.named("dish_degraded").none { it.first > 90.0 }, "no second hold: $what")
            assertTrue(out.filter { it.t > ret.first + 0.5 }.all { it.err < 2.0 }, what)
        }
    }

    @Test
    fun aShortAutoGapInsideTheFaultKeepsTheReference() {
        // 15 m from 30 to 120 s with a 3 s AUTO gap at 60 s: the chord across the gap carries the reference over
        for (v in listOf(15.0, 25.0)) {
            val synth = Synth(200.0, speed = { v }, autoOk = { it !in 60.0..63.0 }, step = { tf -> if (tf in 30.0..120.0) 15.0 else 0.0 })
            val ev = ArrayList<Triple<Double, String, String>>()
            val out = synth.run(InertialConfig.SHIPPED, events = ev)
            val what = "v $v: ${dishEvents(ev)}"
            assertTrue(ev.named("dish_reference_end").none { it.first < 120.0 && it.third != "reason=expired" }, what)
            val ret = ev.named("dish_returning").firstOrNull { it.first > 120.0 } ?: error(what)
            assertTrue(ret.first < 123.5, what)
            assertTrue(ev.named("dish_degraded").none { it.first > 120.0 }, "no second hold: $what")
            assertTrue(out.filter { it.t > ret.first + 0.5 }.all { it.err < 3.0 }, what)
        }
    }

    @Test
    fun anUndetectedOnsetsReturnIsHeldNoLongerThanTheCeiling() {
        // a slow drift (0.15 m/s) to 30 m the detector does not see, held 60 s, then the dish steps back on the road: with
        // no onset there is no reference and nothing to mirror, so the step back looks like a fault. The hold of the
        // correct dish is bounded by holdCeilS (the review saw 121 s with the 120 s ceiling).
        val c = InertialConfig.SHIPPED
        val r = 30.0 / 0.15
        val back = 30 + r + 60
        val synth = Synth(back + c.holdCeilS + 80, step = { tf -> when { tf < 30 -> 0.0; tf < 30 + r -> 0.15 * (tf - 30); tf < back -> 30.0; else -> 0.0 } })
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = synth.run(c, events = ev)
        val what = dishEvents(ev)
        val open = ev.named("dish_degraded").first { it.first > back }
        val end = ev.named("dish_recovering", "dish_ok", "dish_returning").first { it.first > open.first }
        assertTrue(end.first - open.first <= c.holdCeilS + 2.0, "held ${end.first - open.first} s: $what")
        assertTrue(out.filter { it.t > end.first + 3 * c.tauWalk }.all { it.err < 3.0 }, what)
    }

    @Test
    fun aHoldOpenedSoonAfterAnotherIsNotExtended() {
        // 15 m for 15 s (taken back at its end), then 20 m from 80 s for 100 s: the second opening comes within
        // holdSigAfterHoldS of the first hold's end, so it gets the plain 25 s hold (it could be the dish's return)
        val synth = Synth(260.0, step = { tf -> when { tf in 30.0..45.0 -> 15.0; tf in 80.0..180.0 -> 20.0; else -> 0.0 } })
        val ev = ArrayList<Triple<Double, String, String>>()
        synth.run(InertialConfig.SHIPPED, events = ev)
        val what = dishEvents(ev)
        val open2 = ev.named("dish_degraded").first { it.first > 80.0 }
        val walk = ev.named("dish_recovering").first { it.first > open2.first }
        assertTrue(walk.first - open2.first in 25.0..27.0, what)
        // the same fault alone is held past 25 s
        val ev1 = ArrayList<Triple<Double, String, String>>()
        Synth(260.0, step = { tf -> if (tf in 80.0..180.0) 20.0 else 0.0 }).run(InertialConfig.SHIPPED, events = ev1)
        val walk1 = ev1.named("dish_recovering").first()
        assertTrue(walk1.first - ev1.named("dish_degraded").first().first > 40.0, dishEvents(ev1))
    }

    // ------------------------------------------------------------------ the facade on the fake dish

    private fun call(sim: FakeDishSimulation, kind: DishCallKind): DishCallResult {
        val (resp, st) = sim.handle(kind.request(1))
        return DishCallResult(kind, 0, 0, "fake", 200, st.first, st.second.ifEmpty { null }, ByteArray(0), resp, null)
    }

    @Test
    fun facadeOnTheFakeDishLearnsTheMountAndCorrectsASidewaysFault() {
        var now = 0L
        val wall0 = 1_791_990_000_000L
        val sim = FakeDishSimulation(
            spoofEverySec = 0, radiusM = 2000.0, startNs = 0, clock = { now }, wallClockMs = { wall0 + now / 1_000_000L },
            faultSidewaysM = 12.0, faultAtS = 300.0, faultForS = 20.0,
        )
        val learned = ArrayList<Double>()
        val events = ArrayList<InertialEvent>()
        val nav = InertialNav(InertialConfig.SHIPPED, storedMountDeg = null, onMountLearned = { learned += it }, onEvent = { events += it })
        val sec = 1_000_000_000L
        val served = ArrayList<Triple<Double, InertialEstimate, FakeDishSimulation.Truth>>()
        for (k in 1..420) {
            now = k * sec + sec / 5
            nav.onDishCall(call(sim, DishCallKind.STATUS), now, wall0 + now / 1_000_000)
            if (k % 10 == 0) {
                now = k * sec + 3 * sec / 10
                nav.onDishCall(call(sim, DishCallKind.DIAGNOSTICS), now, wall0 + now / 1_000_000)
            }
            now = k * sec + 45 * sec / 100
            val est = nav.onDishCall(call(sim, DishCallKind.LOCATION_AUTO), now, wall0 + now / 1_000_000)
            if (est != null) served += Triple(k + 0.45, est, sim.truthAt(k + 0.45))
        }
        assertEquals(1, learned.size, "mount learned once: $learned")
        assertEquals(-177.0, learned[0], 1.0)
        assertTrue(events.any { it.name == "dish_degraded" }, events.joinToString { it.name })
        val err = { e: InertialEstimate, tr: FakeDishSimulation.Truth -> Geo.distanceM(e.lat, e.lon, tr.lat, tr.lon) }
        val before = served.filter { it.first in 250.0..299.0 }
        assertTrue(before.all { err(it.second, it.third) < 3.0 }, "tracking before the fault: ${before.maxOf { err(it.second, it.third) }}")
        val open = served.filter { it.second.state == InertialState.SIDEWAYS_OPEN }
        if (System.getenv("INERTIAL_DEBUG") != null) {
            for (s in served.filter { it.first in 295.0..370.0 }) println("%.2f %s err %.1f off %.1f acc %.1f".format(s.first, s.second.state, err(s.second, s.third), s.second.sidewaysOffsetM, s.second.accuracyM))
            println(events.filter { nav.rel(it.tNs) > 290 }.joinToString("\n") { "%.2f %s %s".format(nav.rel(it.tNs), it.name, it.detail) })
        }
        assertTrue(open.isNotEmpty(), "sideways state opened")
        assertTrue(open.all { err(it.second, it.third) < 6.0 }, "held on the road while AUTO is 12 m off: ${open.maxOf { err(it.second, it.third) }}")
        assertTrue(open.all { it.second.sidewaysOffsetM > 6.0 }, "offset to the right")
        assertTrue(open.all { it.second.accuracyM >= it.second.sidewaysOffsetM })
        val after = served.filter { it.first > 400 }
        assertTrue(after.all { err(it.second, it.third) < 4.0 }, "back after the fault: ${after.maxOf { err(it.second, it.third) }}")
        assertNull(nav.onDishCall(call(sim, DishCallKind.STATUS), now + sec, wall0), "status calls serve nothing")
        assertEquals(InertialEvent.KIND, events.first().toAppEvent().kind)
    }

    @Test
    fun facadeOnTheFakeDishHoldsLongSidewaysFaultsAndTakesTheReturnAtOnce() {
        // what the emulator showed (fake dish, 15 m sideways for 40 s and 90 s): the fixed 25 s hold walked back onto
        // the faulty dish, then held the dish's return as a new fault for another ~25 s
        val wall0 = 1_791_990_000_000L
        for (faultFor in listOf(40.0, 90.0)) {
            // events carry elapsedRealtime ns; the fake drive's clock is k s + fractions, so ns / 1e9 is the drive time
            val nav0 = object { fun rel(tNs: Long) = tNs / 1e9 }
            fun drive(config: InertialConfig): Pair<List<Pair<Double, Double>>, List<InertialEvent>> {
                var now = 0L
                val sim = FakeDishSimulation(
                    spoofEverySec = 0, radiusM = 2000.0, startNs = 0, clock = { now }, wallClockMs = { wall0 + now / 1_000_000L },
                    faultSidewaysM = 15.0, faultAtS = 300.0, faultForS = faultFor,
                )
                val events = ArrayList<InertialEvent>()
                val nav = InertialNav(config, storedMountDeg = -177.0, onEvent = { events += it })
                val err = ArrayList<Pair<Double, Double>>()
                fakeDrive(nav, 1..520, sim, { now = it }, wall0, each = { k, e ->
                    if (e != null) sim.truthAt(k + 0.45).let { tr -> err += (k + 0.45) to Geo.distanceM(e.lat, e.lon, tr.lat, tr.lon) }
                })
                return err to events
            }
            val (new, evNew) = drive(InertialConfig.SHIPPED)
            val (old, _) = drive(InertialConfig.FIXED_HOLD)
            val end = 300.0 + faultFor
            fun maxIn(e: List<Pair<Double, Double>>, a: Double, b: Double) = e.filter { it.first in a..b }.maxOf { it.second }
            // held while the 15 m offset is significant against the dead reckoning's growing uncertainty (~55 s), then
            // walked back onto the dish
            val walk = evNew.firstOrNull { it.name == "dish_recovering" }?.let { nav0.rel(it.tNs) } ?: end
            assertTrue(walk >= minOf(end, 335.0), "$faultFor s: held past 25 s: walk at $walk")
            if (System.getenv("INERTIAL_DEBUG") != null) println(evNew.filter { it.name.startsWith("dish") }.joinToString("\n") { "%.2f %s %s".format(nav0.rel(it.tNs), it.name, it.detail) })
            assertTrue(maxIn(new, 305.0, walk) < 5.0, "$faultFor s: held on the road: ${maxIn(new, 305.0, walk)}")
            assertTrue(maxIn(new, end + 3, end + 120) < 4.0, "$faultFor s: back on AUTO at once: ${maxIn(new, end + 3, end + 120)}")
            assertTrue(evNew.count { it.name == "dish_degraded" } == 1, "$faultFor s: one hold: ${evNew.map { it.name }}")
            // the fixed hold, for the record: 40 s ended inside its walk back (RETURN_WALK), 90 s ended with it on the faulty
            // dish and the return held as a new fault
            if (faultFor > 60) {
                assertTrue(maxIn(old, end - 5, end - 1) > 10.0, "$faultFor s: old on the faulty dish: ${maxIn(old, end - 5, end - 1)}")
                assertTrue(maxIn(old, end + 3, end + 30) > 10.0, "$faultFor s: old second hold: ${maxIn(old, end + 3, end + 30)}")
            }
        }
    }

    /** The fake dish driven for [seconds] (status, diagnostics every 10 s, AUTO), [imu] called before each second's calls. */
    private fun fakeDrive(
        nav: InertialNav, seconds: IntRange, sim: FakeDishSimulation, clock: (Long) -> Unit, wall0: Long,
        imu: (Long) -> Unit = {}, each: (Int, InertialEstimate?) -> Unit = { _, _ -> },
    ) {
        val sec = 1_000_000_000L
        for (k in seconds) {
            imu(k * sec)
            var now = k * sec + sec / 5
            clock(now)
            nav.onDishCall(call(sim, DishCallKind.STATUS), now, wall0 + now / 1_000_000)
            if (k % 10 == 0) {
                now = k * sec + 3 * sec / 10
                clock(now)
                nav.onDishCall(call(sim, DishCallKind.DIAGNOSTICS), now, wall0 + now / 1_000_000)
            }
            now = k * sec + 45 * sec / 100
            clock(now)
            each(k, nav.onDishCall(call(sim, DishCallKind.LOCATION_AUTO), now, wall0 + now / 1_000_000))
        }
    }

    /** 200 Hz of a phone lying still in the car (gravity on +y), types [acc]/[gyr], bias values when uncalibrated. */
    private fun stillImu(nav: InertialNav, fromNs: Long, acc: Int, gyr: Int) {
        val uncal = acc == InertialNav.TYPE_ACCEL_UNCAL
        for (i in 0 until 200) {
            val t = fromNs + i * 5_000_000L
            nav.onImuSample(acc, t, if (uncal) listOf(0.1f, 9.9f, 0.0f, 0.1f, 0.1f, 0.0f) else listOf(0.0f, 9.8f, 0.0f))
            nav.onImuSample(gyr, t, if (uncal) listOf(0.01f, 0.0f, 0.0f, 0.01f, 0.0f, 0.0f) else listOf(0.0f, 0.0f, 0.0f))
        }
    }

    @Test
    fun oneImuTimestampFarAheadIsDroppedAndChangesNothing() {
        val wall0 = 1_791_990_000_000L
        val sec = 1_000_000_000L
        fun drive(bogus: Boolean): List<InertialEstimate?> {
            var now = 0L
            val sim = FakeDishSimulation(spoofEverySec = 0, radiusM = 2000.0, startNs = 0, clock = { now }, wallClockMs = { wall0 + now / 1_000_000L })
            val nav = InertialNav(InertialConfig.SHIPPED, -177.0)
            val out = ArrayList<InertialEstimate?>()
            fakeDrive(nav, 1..90, sim, { now = it }, wall0, imu = { t ->
                if (bogus && t == 60 * sec) nav.onImuSample(InertialNav.TYPE_GYRO_UNCAL, t + 3 * 3600 * sec, listOf(0f, 0f, 0f, 0f, 0f, 0f))
            }, each = { k, e -> if (k > 60) out += e })
            if (bogus) assertEquals(1L, nav.imuAheadSamples)
            return out
        }
        val clean = drive(false)
        val glitch = drive(true)
        assertEquals(clean.map { it?.lat to it?.lon }, glitch.map { it?.lat to it?.lon })
        assertTrue(glitch.all { it != null && it.state == InertialState.TRACKING && it.accuracyM < 10 })
    }

    @Test
    fun imuBinsStayBoundedWhileTheDishIsUnreachable() {
        val nav = InertialNav(InertialConfig.SHIPPED)
        val sec = 1_000_000_000L
        val fail = DishCallResult(DishCallKind.LOCATION_AUTO, 0, 0, "fake", 0, null, null, null, null, "unreachable")
        val v = listOf(0f, 9.8f, 0f, 0f, 0f, 0f)
        var maxBins = 0
        for (s in 1..600) {
            nav.onDishCall(fail, s * sec, 0L)
            for (i in 0 until 200) {
                val t = s * sec + i * 5_000_000L
                nav.onImuSample(InertialNav.TYPE_ACCEL_UNCAL, t, v)
                nav.onImuSample(InertialNav.TYPE_GYRO_UNCAL, t, v)
            }
            maxBins = maxOf(maxBins, nav.binCount)
        }
        assertTrue(maxBins <= 32, "bins kept: $maxBins")
        assertEquals(InertialState.NO_OUTPUT, nav.state)
    }

    @Test
    fun calibratedOnlyPhonesFeedTheImuToo() {
        val wall0 = 1_791_990_000_000L
        fun drive(acc: Int, gyr: Int): Pair<List<Pair<Double, Double>>, ImuState> {
            var now = 0L
            val sim = FakeDishSimulation(spoofEverySec = 0, radiusM = 2000.0, startNs = 0, clock = { now }, wallClockMs = { wall0 + now / 1_000_000L })
            val nav = InertialNav(InertialConfig.SHIPPED, -177.0)
            val out = ArrayList<Pair<Double, Double>>()
            fakeDrive(nav, 1..40, sim, { now = it }, wall0, imu = { t -> stillImu(nav, t, acc, gyr) }, each = { _, e -> e?.let { out += it.lat to it.lon } })
            return out to nav.imuState
        }
        val (uncal, s1) = drive(InertialNav.TYPE_ACCEL_UNCAL, InertialNav.TYPE_GYRO_UNCAL)
        val (cal, s2) = drive(InertialNav.TYPE_ACCEL, InertialNav.TYPE_GYRO)
        assertEquals(ImuState.GYRO, s1)
        assertEquals(ImuState.GYRO, s2)
        assertEquals(uncal.size, cal.size)
        for ((a, b) in uncal.zip(cal)) assertTrue(abs(a.first - b.first) < 1e-9 && abs(a.second - b.second) < 1e-9, "same IMU, same serves")
    }

    @Test
    fun theMountCallbackReportsRefinementsOnce() {
        val wall0 = 1_791_990_000_000L
        for ((stored, expected) in listOf(null to 1, -175.0 to 1, -177.1 to 0)) {
            var now = 0L
            val sim = FakeDishSimulation(spoofEverySec = 0, radiusM = 2000.0, startNs = 0, clock = { now }, wallClockMs = { wall0 + now / 1_000_000L })
            val learned = ArrayList<Double>()
            val nav = InertialNav(InertialConfig.SHIPPED, stored, onMountLearned = { learned += it })
            fakeDrive(nav, 1..300, sim, { now = it }, wall0)
            assertEquals(expected, learned.size, "stored $stored: $learned")
            learned.forEach { assertEquals(-177.0, it, 0.3) }
            assertEquals(-177.0, nav.learnedMountDeg!!, 0.3)
            // a stored value within 5° stays in use; the refined one is what gets persisted
            if (stored != null) assertEquals(stored, nav.mountOffsetDeg)
        }
    }

    @Test
    fun aLearnedMountCloseToAManualOneIsStillReported() {
        // Manual -177.1 in use, nothing learned saved yet: the learned value (within 0.3° of it) must still be saved.
        val wall0 = 1_791_990_000_000L
        var now = 0L
        val sim = FakeDishSimulation(spoofEverySec = 0, radiusM = 2000.0, startNs = 0, clock = { now }, wallClockMs = { wall0 + now / 1_000_000L })
        val learned = ArrayList<Double>()
        val nav = InertialNav(InertialConfig.SHIPPED, -177.1, onMountLearned = { learned += it }, persistedMountDeg = null)
        fakeDrive(nav, 1..300, sim, { now = it }, wall0)
        assertEquals(1, learned.size, "$learned")
        assertEquals(-177.0, learned.single(), 0.3)
        assertEquals(-177.1, nav.mountOffsetDeg)
    }

    @Test
    fun anAutoAnswerWithoutAPositionCountsAsAFailure() {
        val wall0 = 1_791_990_000_000L
        var now = 0L
        val sim = FakeDishSimulation(spoofEverySec = 0, radiusM = 2000.0, startNs = 0, clock = { now }, wallClockMs = { wall0 + now / 1_000_000L })
        val nav = InertialNav(InertialConfig.SHIPPED, -177.0)
        fakeDrive(nav, 1..30, sim, { now = it }, wall0)
        val empty = DishCallResult(DishCallKind.LOCATION_AUTO, 0, 0, "fake", 200, GrpcStatus.OK, null, ByteArray(0),
            tools.apachi.starlinknav.core.dish.proto.DeviceResponse(), null)
        assertTrue(empty.ok)
        val e = nav.onDishCall(empty, 31L * 1_000_000_000L, wall0)
        assertNotNull(e, "served like a failure")
        assertEquals(nav.rel(31L * 1_000_000_000L), nav.rel(e.tNs))
    }
}
