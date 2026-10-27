package tools.apachi.starlinknav.core.nav

import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.FakeDishSimulation
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.log.proto.PhoneLocation
import tools.apachi.starlinknav.core.log.proto.Record
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The synthetic drive of [InertialNavTest]'s harness (phone up = +y, forward = +z, AUTO received 0.45 s after its fix
 * time, status at k + 0.2 s with heading = car heading + mount, 1 Hz serves inside AUTO gaps) with the dish's GNSS
 * flags, outages, the phone flag, repeats, a dish error in the car frame and diagnostics carrying a fix time: the
 * diagnostics at `tDiag` say `gpsTimeS = 1000 + (tDiag − age)` and `wallGpsS = 1000 + tDiag`, so the slot reference is
 * exactly `1000 + t`, the 15 s boundaries sit at t = 5, 20, 35, … and a fix at `tf` has phase `(1000 + tf) mod 15`.
 */
private class SoSynth(
    val tEnd: Double,
    val head: (Double) -> Double = { 30.0 },
    /** Signed: negative = reversing. */
    val speed: (Double) -> Double = { 15.0 },
    val autoOk: (Double) -> Boolean = { true },
    val net: ((Double) -> Boolean)? = null,
    val netNoiseM: Double = 15.0,
    val mount: Double = -177.0,
    /** `gps_valid` of the status at t; null = a status without gps_stats. */
    val gpsValid: (Double) -> Boolean? = { true },
    val inhibit: (Double) -> Boolean = { false },
    val pnt: (Double) -> Int = { 2 },
    val outage: (Double) -> OutageInfo? = { null },
    /** Seconds between diagnostics. */
    val diagEvery: Double = 10.0,
    /** (along, cross) error added to AUTO at the fix time, in the car frame (cross + = right). */
    val dishErr: (Double) -> Pair<Double, Double> = { 0.0 to 0.0 },
    /** The AUTO answer at this receipt repeats the previous answer's position and speed. */
    val repeat: (Double) -> Boolean = { false },
    /** The speed a repeated answer carries instead of the previous answer's. */
    val repeatHs: ((Double) -> Double)? = null,
    /** The AUTO answer at this receipt describes the fix time returned instead of its own (a two-point oscillation). */
    val pinTf: (Double) -> Double? = { null },
    /** The owner's phone flag, fed as a level at every status. */
    val phoneSuspect: (Double) -> Boolean = { false },
    /** The phone is being handled (gyro far above the car's yaw rate) at t. */
    val handling: (Double) -> Boolean = { false },
    /** Shift of the diagnostics' fix time against the slot grid (phase pinning), s. */
    val gpsTimeShift: Double = 0.0,
    /** The diagnostics' fix time at tDiag (a stopped dish clock); default 1000 + (tDiag − 0.45) + [gpsTimeShift]. */
    val diagGps: ((Double) -> Double)? = null,
    /** The phone's wall clock off by this much, s: wallGpsS is shifted and the age comes from it (no receiver clock). */
    val wallOffset: Double = 0.0,
) {
    sealed class In(val t: Double, val order: Int)
    class Tick(t: Double, val acc: DoubleArray, val gyr: DoubleArray) : In(t, 0)
    class Diag(t: Double, val age: Double, val gps: Double, val wall: Double, val fromClock: Boolean) : In(t, 1)
    class Status(t: Double, val hx: Double, val gpsValid: Boolean?, val inhibit: Boolean, val pnt: Int, val outage: OutageInfo?, val phone: Boolean) : In(t, 2)
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
            val gyr = doubleArrayOf(0.0, -w, 0.0)
            if (handling(t)) gyr[0] += 3.0
            ev += Tick(t, doubleArrayOf(0.0, 9.80665, af), gyr)
        }
        val rnd = Random(7)
        var last: Auto? = null
        val every = diagEvery.toInt().coerceAtLeast(1)
        for (k in 1 until tEnd.toInt()) {
            val tr = k + 0.45
            val tf = tr - 0.45
            val ts = k + 0.2
            ev += Status(ts, wrapDeg(head(ts) + mount), gpsValid(ts), inhibit(ts), pnt(ts), outage(ts), phoneSuspect(ts))
            if (k % every == 0) {
                val td = k + 0.3
                val gps = diagGps?.invoke(td) ?: (1000.0 + (td - 0.45) + gpsTimeShift)
                val wall = 1000.0 + td + wallOffset
                ev += Diag(td, if (wallOffset == 0.0) 0.45 else wall - gps, gps, wall, wallOffset == 0.0)
            }
            if (net?.invoke(k + 0.6) == true) {
                val q = truth(k + 0.6)
                ev += Net(k + 0.6, q[0] + rnd.nextDouble(-1.0, 1.0) * netNoiseM, q[1] + rnd.nextDouble(-1.0, 1.0) * netNoiseM, 25.0)
            }
            if (!autoOk(tr)) continue
            val prev = last
            if (repeat(tr) && prev != null) {
                ev += Auto(tr, prev.e, prev.n, repeatHs?.invoke(tr) ?: prev.hs)
                continue
            }
            val tx = pinTf(tr) ?: tf
            val q = pos[Math.round(tx / dt).toInt()].copyOf()
            val h = Math.toRadians(head(tx))
            val (al, cr) = dishErr(tx)
            q[0] += al * sin(h) + cr * cos(h)
            q[1] += al * cos(h) - cr * sin(h)
            val a = Auto(tr, q[0], q[1], abs(speed(tx)))
            ev += a
            last = a
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

    /** [autoE] / [autoN]: the AUTO answer this serve followed, moved forward by its age at receipt (NaN for a gap serve). */
    class Served(val t: Double, val out: InertialCore.Out?, val state: InertialState, val err: Double, val autoE: Double = Double.NaN, val autoN: Double = Double.NaN) {
        val acc: Double get() = out?.accuracyM ?: Double.NaN
        /** Distance of the served position from the AUTO answer, m. */
        val fromAuto: Double get() = if (out == null || autoE.isNaN()) Double.NaN else hypot(out.e - autoE, out.n - autoN)
    }

    fun run(
        config: InertialConfig, useImu: Boolean = true,
        events: MutableList<Triple<Double, String, String>> = ArrayList(), poke: (Double, InertialCore) -> Unit = { _, _ -> },
        storedMount: Double? = mount,
    ): List<Served> {
        val core = InertialCore(config, storedMount, useImu) { t, k, m -> events += Triple(t, k, m) }
        val out = ArrayList<Served>()
        for (e in inputs) {
            poke(e.t, core)
            when (e) {
                is Tick -> core.onTick(e.t, e.acc, e.gyr)
                is Diag -> core.onDiag(e.t, e.age, e.gps, e.wall, e.fromClock)
                is Status -> {
                    core.onStatus(e.t, e.hx, 2, 0.4, e.gpsValid, if (e.gpsValid == null) null else 17, e.inhibit, e.pnt, e.outage)
                    core.onPhoneFlag(e.t, e.phone)
                }
                is Net -> core.onNetwork(e.t, e.t, e.e, e.n, e.acc)
                is Auto -> {
                    core.onAuto(e.t, e.e, e.n, e.hs)
                    // the AUTO answer moved forward by the 0.45 s it is old at receipt (what the serve at receipt follows)
                    val h = Math.toRadians(head(e.t - 0.45))
                    out += served(core, e.t, e.e + e.hs * 0.45 * sin(h), e.n + e.hs * 0.45 * cos(h))
                }
                is Serve -> out += served(core, e.t)
            }
        }
        return out
    }

    private fun served(core: InertialCore, t: Double, ae: Double = Double.NaN, an: Double = Double.NaN): Served {
        val o = core.served(t)
        val q = truth(t)
        return Served(t, o, core.lastState, if (o == null) Double.NaN else hypot(o.e - q[0], o.n - q[1]), ae, an)
    }
}

class StarlinkOnlyTest {
    private val sec = 1_000_000_000L

    private fun List<Triple<Double, String, String>>.named(vararg n: String) = filter { it.second in n }

    private fun show(ev: List<Triple<Double, String, String>>) =
        ev.filter { it.second.startsWith("dish") || it.second == "slot_step" || it.second == "push_stop" || it.second == "watch_suspect" }
            .joinToString("; ") { "%.1f %s %s".format(java.util.Locale.ROOT, it.first, it.second, it.third) }

    private fun pct(xs: List<Double>, q: Double): Double {
        val s = xs.filter { !it.isNaN() }.sorted()
        if (s.isEmpty()) return Double.NaN
        return s[((q / 100.0) * (s.size - 1)).toInt().coerceIn(0, s.size - 1)]
    }

    private fun coverage(out: List<SoSynth.Served>) = out.count { it.out != null && it.err <= it.acc }.toDouble() / out.size

    /** A one-way jump of (+40, +25) m from the first fix time at or after [from]. */
    private fun jump(from: Double) = { tf: Double -> if (tf >= from) 40.0 to 25.0 else 0.0 to 0.0 }

    // ------------------------------------------------------------------ 1. the regime

    @Test
    fun theRegimeFollowsTheDishGnssFlags() {
        // valid, invalid 30-90 s, valid again; a status gap (no gps_stats) 100-112 s
        val synth = SoSynth(170.0, gpsValid = { t -> when { t in 100.0..112.0 -> null; t >= 30.0 && t < 90.0 -> false; else -> true } })
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = synth.run(InertialConfig.SHIPPED, events = ev)
        val regimes = ev.named("dish_regime").map { "%.0f %s".format(java.util.Locale.ROOT, it.first, it.third) }
        assertEquals(
            listOf(
                "1 from=UNKNOWN to=TRUSTED cause=gps_valid", "30 from=TRUSTED to=STARLINK_ONLY cause=gps_valid",
                "90 from=STARLINK_ONLY to=TRUSTED cause=gps_valid", "109 from=TRUSTED to=UNKNOWN cause=stale", "112 from=UNKNOWN to=TRUSTED cause=gps_valid",
            ),
            regimes,
        )
        assertEquals(1, ev.named("dish_switch").size, show(ev))
        assertTrue(ev.named("dish_switch").single().first in 90.0..91.0, show(ev))
        for (s in out) {
            val o = assertNotNull(s.out, "served at ${s.t}")
            when {
                s.t in 31.0..90.0 -> {
                    assertEquals(85.0, o.dishFloorM, 1e-9, "floor at ${s.t}")
                    assertEquals(DishGnssRegime.STARLINK_ONLY, o.dishRegime, "regime at ${s.t}")
                }
                s.t > 90.5 -> {
                    val decay = InertialConfig.SHIPPED.soDecayS
                    assertTrue(o.dishFloorM <= 85.0 * exp(-(s.t - 90.2) / decay) + 0.01, "decay at ${s.t}: ${o.dishFloorM}")
                    if (s.t < 90.2 + 5 * decay - 0.5) assertTrue(o.dishFloorM > 0.0, "still decaying at ${s.t}")
                    if (s.t > 109.5 && s.t < 112.0) assertEquals(DishGnssRegime.UNKNOWN, o.dishRegime, "stale at ${s.t}")
                    if (s.t >= 90.2 + 5 * decay + 0.5) assertEquals(0.0, o.dishFloorM, "gone at ${s.t}")
                }
                s.t > 2.0 && s.t < 30.0 -> assertEquals(0.0, o.dishFloorM, "no floor at ${s.t}")
            }
            assertTrue(s.err <= o.accuracyM, "honest at ${s.t}: ${s.err} vs ${o.accuracyM}")
        }
        // inhibit_gps alone
        val ev2 = ArrayList<Triple<Double, String, String>>()
        val out2 = SoSynth(60.0, inhibit = { it in 30.0..60.0 }).run(InertialConfig.SHIPPED, events = ev2)
        assertTrue(ev2.named("dish_regime").any { it.third == "from=TRUSTED to=STARLINK_ONLY cause=inhibit" }, "$ev2")
        assertTrue(out2.filter { it.t > 31.0 }.all { it.out!!.dishFloorM == 85.0 && it.out.dishRegime == DishGnssRegime.STARLINK_ONLY })
        // the unconverged dish
        val out3 = SoSynth(40.0, pnt = { if (it < 20.0) 1 else 2 }).run(InertialConfig.SHIPPED)
        assertTrue(out3.filter { it.t < 20.0 }.all { it.out!!.dishFloorM >= 150.0 && it.out.accuracyM >= 150.0 }, "unconverged floor")
        assertTrue(out3.filter { it.t > 21.0 }.all { it.out!!.dishFloorM == 0.0 }, "converged")
    }

    // ------------------------------------------------------------------ 2. honest accuracy

    @Test
    fun starlinkOnlyAccuracyCoversTheWander() {
        fun wander(crossM: Double) = { tf: Double -> 70.0 * sin(2 * PI * tf / 120) to crossM * sin(2 * PI * tf / 40) }
        val synth = SoSynth(240.0, gpsValid = { false }, dishErr = wander(3.0))
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = synth.run(InertialConfig.SHIPPED, events = ev).filter { it.t > 10.0 }
        assertTrue(coverage(out) >= 0.95, "coverage ${coverage(out)}: ${show(ev)}")
        val follow = pct(out.map { it.fromAuto }, 95.0)
        assertTrue(follow < 5.0, "served follows AUTO: p95 $follow m; ${show(ev)}")
        assertTrue(ev.named("dish_degraded").isEmpty(), show(ev))
        // a 12 m cross wander: without holds the same, with holds the coverage and every hold closes
        val wide = SoSynth(240.0, gpsValid = { false }, dishErr = wander(12.0))
        val evN = ArrayList<Triple<Double, String, String>>()
        val noHolds = wide.run(InertialConfig.SHIPPED.copy(so = InertialConfig.SHIPPED.so.copy(soHolds = false)), events = evN).filter { it.t > 10.0 }
        assertTrue(coverage(noHolds) >= 0.95, "coverage without holds ${coverage(noHolds)}: ${show(evN)}")
        assertTrue(pct(noHolds.map { it.fromAuto }, 95.0) < 5.0, "follows AUTO without holds: ${show(evN)}")
        assertTrue(evN.named("dish_degraded").isEmpty(), show(evN))
        val evH = ArrayList<Triple<Double, String, String>>()
        val holds = wide.run(InertialConfig.SHIPPED, events = evH).filter { it.t > 10.0 }
        assertTrue(coverage(holds) >= 0.95, "coverage with holds ${coverage(holds)}: ${show(evH)}")
        for (open in evH.named("dish_degraded")) {
            assertTrue(evH.named("dish_ok", "dish_recovering", "dish_returning").any { it.first > open.first }, "the hold at ${open.first} closes: ${show(evH)}")
        }
        // an AUTO gap 60-90 s: the floor in quadrature with the dead reckoning's own radius
        val evG = ArrayList<Triple<Double, String, String>>()
        val gap = SoSynth(150.0, gpsValid = { false }, dishErr = wander(3.0), autoOk = { it < 60.0 || it >= 90.0 }).run(InertialConfig.SHIPPED, events = evG)
        val dr = gap.filter { it.t in 63.0..90.0 }
        assertTrue(dr.isNotEmpty() && dr.all { it.state == InertialState.DEAD_RECKONING }, "dead reckoning: ${dr.map { it.state }}")
        for (s in dr) {
            val o = assertNotNull(s.out, "served at ${s.t}")
            assertTrue(o.accuracyM >= hypot(85.0, o.acc68) - 1e-6, "floor in quadrature at ${s.t}: ${o.accuracyM} vs ${hypot(85.0, o.acc68)}")
            assertTrue(s.err <= o.accuracyM, "honest DR at ${s.t}: ${s.err} vs ${o.accuracyM}")
        }
        assertTrue(evG.named("push_stop").isEmpty(), show(evG))
    }

    // ------------------------------------------------------------------ 3. the switch back

    @Test
    fun theSwitchToGnssIsAcceptedWithoutAGateFight() {
        // Starlink-only until 50 s with a (−60, +15) m error, then valid: the error decays as e^(−Δt/8) (the measured
        // slide) and the chord across the flip carries a one-off excess
        fun slide(exAl: Double, exCr: Double, flip: Double = 50.0) = { tf: Double ->
            if (tf < flip) -60.0 to 15.0 else exp(-(tf - flip) / 8.0).let { d -> (-60.0 + exAl) * d to (15.0 + exCr) * d }
        }
        for ((label, err) in listOf("a: +20 along" to slide(20.0, 0.0), "b: +22 cross +8 along" to slide(8.0, 22.0))) {
            val synth = SoSynth(120.0, gpsValid = { it >= 50.0 }, dishErr = err)
            val ev = ArrayList<Triple<Double, String, String>>()
            val out = synth.run(InertialConfig.SHIPPED, events = ev)
            val what = "$label: ${show(ev)}"
            assertEquals(1, ev.named("dish_switch").size, what)
            assertTrue(ev.named("dish_gate_reject", "dish_reanchor", "dish_degraded").none { it.first > 48.0 }, what)
            for (s in out.filter { it.t in 50.0..80.0 }) {
                val o = assertNotNull(s.out, "served at ${s.t}")
                assertTrue(s.err <= o.accuracyM, "$label honest at ${s.t}: ${s.err} vs ${o.accuracyM}")
            }
            assertTrue(out.filter { it.t >= 100.0 }.all { it.out!!.accuracyM <= 8.0 }, "$label accuracy by 100 s: ${out.filter { it.t >= 100.0 }.map { it.acc }}")
        }
        // (c) the flip lands inside an AUTO gap 45-60 s
        val evC = ArrayList<Triple<Double, String, String>>()
        val outC = SoSynth(120.0, gpsValid = { it >= 50.0 }, dishErr = slide(20.0, 0.0), autoOk = { it < 45.0 || it >= 60.0 }).run(InertialConfig.SHIPPED, events = evC)
        val whatC = "c: ${show(evC)}"
        assertTrue(evC.named("push_stop").isEmpty(), whatC)
        val sw = evC.named("dish_switch").single()
        assertTrue(sw.first in 60.0..61.0, whatC)
        assertTrue(evC.named("dish_gate_reject", "dish_reanchor", "dish_degraded").none { it.first > 59.0 }, whatC)
        assertTrue(outC.filter { it.t in 61.0..70.0 }.all { it.out != null && it.fromAuto < 3.0 }, "c: on AUTO after the gap: ${outC.filter { it.t in 61.0..70.0 }.map { it.fromAuto }}")
        // (d) the status flips one second AFTER the flip chord, and the chord sits outside every slot window: the step
        // chord is the fix at tf 57 (phase 7), the status at 57.2 still says invalid, 58.2 says valid (spec §3, the one fix
        // of lag). With a cross-dominant excess (+30 m cross, +20 m along; the stored mount is known) the gate reject at
        // 57.45 opens a sideways hold (cause=gate); the switch back at the next fix (58.45) drops it (hold_dropped=1).
        // (+22 m cross, +8 m along opens no hold: the chord's 22 m sideways makes the acting detector SUSPECT, which widens
        // the gate's cross sigma to the innovation itself, so only the along part can push the NIS over the gate, and
        // 8 m along does not: nis 5; it takes about +16 m along. Raising the cross excess alone changes nothing.)
        val evD = ArrayList<Triple<Double, String, String>>()
        val outD = SoSynth(120.0, gpsValid = { it >= 58.0 }, dishErr = slide(20.0, 30.0, flip = 57.0)).run(InertialConfig.SHIPPED, events = evD)
        val whatD = "d: ${show(evD)}"
        val degraded = evD.named("dish_degraded")
        assertEquals(1, degraded.size, whatD)
        assertTrue(degraded.single().first in 57.0..58.0 && degraded.single().third.startsWith("cause=gate"), whatD)
        assertTrue(evD.named("dish_gate_reject").any { it.first in 57.0..58.0 }, whatD)
        val swD = evD.named("dish_switch")
        assertEquals(1, swD.size, whatD)
        assertTrue(swD.single().first in 58.0..59.5 && swD.single().third.endsWith("hold_dropped=1"), whatD)
        assertTrue(outD.filter { it.t >= 61.0 }.all { it.state == InertialState.TRACKING }, "$whatD; states ${outD.filter { it.t >= 61.0 }.map { it.state }.distinct()}")
        for (s in outD.filter { it.t in 57.0..80.0 }) {
            val o = assertNotNull(s.out, "served at ${s.t}")
            assertTrue(s.err <= o.accuracyM, "d honest at ${s.t}: ${s.err} vs ${o.accuracyM}; $whatD")
        }
        // the same flip with an along-dominant excess (+20 m along): the gate rejects without a hold, nothing to drop
        val evA = ArrayList<Triple<Double, String, String>>()
        val outA = SoSynth(120.0, gpsValid = { it >= 58.0 }, dishErr = slide(20.0, 0.0, flip = 57.0)).run(InertialConfig.SHIPPED, events = evA)
        val whatA = "d/along: ${show(evA)}"
        assertTrue(evA.named("dish_degraded").isEmpty(), whatA)
        val swA = evA.named("dish_switch").single()
        assertTrue(swA.first in 58.0..59.5 && swA.third.endsWith("hold_dropped=0"), whatA)
        assertTrue(outA.filter { it.t >= 61.0 }.all { it.state == InertialState.TRACKING }, whatA)
        for (s in outA.filter { it.t in 57.0..80.0 }) {
            val o = assertNotNull(s.out, "served at ${s.t}")
            assertTrue(s.err <= o.accuracyM, "d/along honest at ${s.t}: ${s.err} vs ${o.accuracyM}; $whatA")
        }
    }

    @Test
    fun theFloorAndTheQuietWindowScaleWithTheStarlinkOnlyDwell() {
        // a 5 s dwell (30.2-35.2 s): the floor at the switch back is what the wander could have built, 85 · 5 / 45 ≈ 9.4 m
        // (not 85), and the quiet window 20 · 5 / 45 ≈ 2.2 s, so a 30 m cross step at tf 42 opens a hold as in TRUSTED
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = SoSynth(90.0, gpsValid = { it !in 30.0..35.0 }, dishErr = { tf -> if (tf >= 42.0) 0.0 to 30.0 else 0.0 to 0.0 }).run(InertialConfig.SHIPPED, events = ev)
        val what = show(ev)
        assertEquals(1, ev.named("dish_switch").size, what)
        assertTrue(out.filter { it.t in 31.0..35.0 }.all { it.out!!.dishFloorM == 85.0 }, "the full floor inside")
        for (s in out.filter { it.t in 35.4..36.0 }) assertTrue(s.out!!.dishFloorM in 8.5..9.5, "floor after a 5 s dwell at ${s.t}: ${s.out.dishFloorM}")
        val hold = ev.named("dish_degraded").firstOrNull() ?: error(what)
        assertTrue(hold.first in 42.0..43.5, what)
        // a flap: Starlink-only 50 s, GNSS 1 s, Starlink-only 1 s, GNSS: the second switch back inherits what the first
        // still had to decay (85 · e^(−1/τ) + 85 / 45 ≈ 81 m with τ = soDecayS 15 s), not 85 / 45
        val evF = ArrayList<Triple<Double, String, String>>()
        val outF = SoSynth(120.0, gpsValid = { t -> !(t in 30.0..80.0 || t in 81.0..82.0) }).run(InertialConfig.SHIPPED, events = evF)
        assertEquals(2, evF.named("dish_switch").size, show(evF))
        // (Starlink-only again 81.2-82.2: the first decay ran 1 s, the 1 s dwell built 85 / 45; then decaying from 82.2 s)
        val tau = InertialConfig.SHIPPED.soDecayS
        for (s in outF.filter { it.t in 82.4..83.0 }) {
            val expected = (85.0 * exp(-1.0 / tau) + 85.0 / InertialConfig.SHIPPED.soWanderTauS) * exp(-(s.t - 82.2) / tau)
            assertEquals(expected, s.out!!.dishFloorM, 0.5, "floor after the flap at ${s.t}")
            assertTrue(s.out.dishFloorM > 70.0, "inherits the first decay, not 85 / 45: ${s.out.dishFloorM}")
        }
        // a long dwell: the full floor and the full window (test 1 pins the decay)
        val outL = SoSynth(120.0, gpsValid = { it !in 30.0..90.0 }).run(InertialConfig.SHIPPED)
        for (s in outL.filter { it.t in 90.4..91.0 }) assertTrue(s.out!!.dishFloorM in 82.0..85.0, "floor after a 60 s dwell at ${s.t}: ${s.out.dishFloorM}")
    }

    @Test
    fun aReferenceFromTheStarlinkOnlyTrackEndsAtTheSwitchBack() {
        // Starlink-only: a +30 m cross step at tf 20 (phase 0) is a slot step, so the track re-anchors 30 m off the
        // truth; the step back at tf 42 (phase 7) opens a hold whose fault-free reference is measured against that wrong
        // track, while the dish is right from then on. The switch back at 110 s: the reference must not survive it (once
        // the hold has walked back and closed, its offset would be told as the accuracy for minutes)
        val err = { tf: Double -> if (tf >= 20.0 && tf < 42.0) 0.0 to 30.0 else 0.0 to 0.0 }
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = SoSynth(200.0, gpsValid = { it >= 110.0 }, dishErr = err).run(InertialConfig.SHIPPED, events = ev)
        val what = show(ev)
        assertEquals(1, ev.named("slot_step").size, what)
        val hold = ev.named("dish_degraded").firstOrNull() ?: error(what)
        assertTrue(hold.first in 42.0..44.0, what)
        val sw = ev.named("dish_switch").single()
        assertTrue(sw.first in 110.0..111.5, what)
        // the hold walked back before the switch with its reference alive (measured against the wrong track)
        assertTrue(ev.named("dish_recovering").any { it.first < sw.first && it.third.contains("reference_m=-3") }, what)
        assertTrue(ev.named("dish_reference_end").none { it.first > sw.first }, what)
        assertTrue(ev.named("dish_returning").none { it.first > sw.first && it.third.startsWith("cause=reference") }, what)
        val late = out.filter { it.t >= 160.0 }
        assertTrue(late.all { it.out != null && it.acc <= 8.0 && it.err <= it.acc }, "accuracy after the switch: ${late.map { "%.0f %.1f".format(java.util.Locale.ROOT, it.t, it.acc) }}; $what")
    }

    // ------------------------------------------------------------------ 4.-6. slot windows

    @Test
    fun aBoundaryStepInStarlinkOnlyIsReanchoredNotRejectedOrHeld() {
        // Starlink-only; (+40, +25) m from tf 51 (phase 1.0) for good
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = SoSynth(90.0, gpsValid = { false }, dishErr = jump(50.8)).run(InertialConfig.SHIPPED, events = ev)
        val what = show(ev)
        val steps = ev.named("slot_step")
        assertEquals(1, steps.size, what)
        assertTrue(steps.single().third.startsWith("window=boundary phase_s=1.0"), what)
        assertTrue(ev.named("dish_gate_reject", "dish_degraded", "dish_reanchor").isEmpty(), what)
        assertTrue(ev.named("watch_suspect").none { it.first in 51.0..52.5 }, what)
        assertTrue(out.filter { it.t >= 53.0 }.all { it.out != null && it.fromAuto < 3.0 }, "on the new AUTO: ${out.filter { it.t >= 51.0 }.map { "%.1f".format(java.util.Locale.ROOT, it.fromAuto) }}")
        assertTrue(out.all { it.out != null && it.err <= it.acc }, "honest")
        // the same jump at phase 7: the old behaviour
        val ev7 = ArrayList<Triple<Double, String, String>>()
        SoSynth(90.0, gpsValid = { false }, dishErr = jump(56.8)).run(InertialConfig.SHIPPED, events = ev7)
        assertTrue(ev7.named("slot_step").isEmpty(), show(ev7))
        assertTrue(ev7.named("dish_gate_reject").isNotEmpty() && ev7.named("dish_reanchor", "dish_degraded").isNotEmpty(), show(ev7))
        // phase pinning: the fix time shifted against the grid to 14.6 (inside the 0.5 s before-margin) and 3.2 (just outside)
        val evIn = ArrayList<Triple<Double, String, String>>()
        SoSynth(90.0, gpsValid = { false }, dishErr = jump(64.6), gpsTimeShift = -0.4).run(InertialConfig.SHIPPED, events = evIn)
        assertTrue(evIn.named("slot_step").singleOrNull()?.third?.startsWith("window=boundary phase_s=14.6") == true, show(evIn))
        val evOut = ArrayList<Triple<Double, String, String>>()
        SoSynth(90.0, gpsValid = { false }, dishErr = jump(52.8), gpsTimeShift = 0.2).run(InertialConfig.SHIPPED, events = evOut)
        assertTrue(evOut.named("slot_step").isEmpty() && evOut.named("dish_gate_reject").isNotEmpty(), show(evOut))
        // slot-aware off: the old way
        val evOff = ArrayList<Triple<Double, String, String>>()
        SoSynth(90.0, gpsValid = { false }, dishErr = jump(50.8)).run(InertialConfig.SHIPPED.copy(so = InertialConfig.SHIPPED.so.copy(slotAware = false)), events = evOff)
        assertTrue(evOff.named("slot_step").isEmpty() && evOff.named("dish_gate_reject").isNotEmpty(), show(evOff))
    }

    @Test
    fun aStepNearADidSwitchOutageIsReanchored() {
        // the status at 60 s carries an outage that began at GPS 1000 + 58 s and lasted 2 s; a jump from tf 70 (phase 5,
        // outside the boundary window, inside the outage window 53-75 s)
        fun outage(didSwitch: Boolean) = { t: Double -> if (t >= 60.0 && t < 61.0) OutageInfo(5, 1058L * sec, 2 * sec, didSwitch) else null }
        val ev = ArrayList<Triple<Double, String, String>>()
        SoSynth(100.0, gpsValid = { false }, dishErr = jump(69.6), outage = outage(true)).run(InertialConfig.SHIPPED, events = ev)
        val step = ev.named("slot_step").singleOrNull()
        assertNotNull(step, show(ev))
        assertTrue(step.third.startsWith("window=outage phase_s=5.0") && step.first in 70.0..71.0, show(ev))
        assertTrue(ev.named("dish_gate_reject", "dish_degraded").isEmpty(), show(ev))
        val evNo = ArrayList<Triple<Double, String, String>>()
        SoSynth(100.0, gpsValid = { false }, dishErr = jump(69.6), outage = outage(false)).run(InertialConfig.SHIPPED, events = evNo)
        assertTrue(evNo.named("slot_step").isEmpty() && evNo.named("dish_gate_reject").isNotEmpty(), show(evNo))
    }

    @Test
    fun withDishGnssValidTheWindowsChangeNothing() {
        val ev = ArrayList<Triple<Double, String, String>>()
        SoSynth(90.0, dishErr = jump(50.8)).run(InertialConfig.SHIPPED, events = ev)
        assertTrue(ev.named("slot_step").isEmpty(), show(ev))
        assertTrue(ev.named("dish_gate_reject").isNotEmpty() && ev.named("dish_reanchor", "dish_degraded").isNotEmpty(), show(ev))
    }

    // ------------------------------------------------------------------ 7.-10. the stall detector

    @Test
    fun aFrozenDishWhileMovingStartsDeadReckoning() {
        for (so in listOf(false, true)) for (hsZero in listOf(false, true)) {
            val synth = SoSynth(80.0, gpsValid = { !so }, repeat = { it in 41.0..60.0 }, repeatHs = if (hsZero) { _ -> 0.0 } else null)
            val ev = ArrayList<Triple<Double, String, String>>()
            val out = synth.run(InertialConfig.SHIPPED, events = ev)
            val what = "so=$so hs0=$hsZero: ${show(ev)}"
            val stall = ev.named("dish_stall").singleOrNull() ?: error(what)
            assertTrue(stall.first in 44.0..47.0 && stall.third.startsWith("reason=frozen"), what)
            val dr = out.filter { it.t in 48.0..60.0 }
            assertTrue(dr.isNotEmpty() && dr.all { it.state == InertialState.DEAD_RECKONING && it.out?.stalled == true }, "$what; states ${dr.map { it.state }}")
            val at55 = out.first { it.t >= 55.0 }
            assertTrue(at55.err < 20.0 && at55.err <= at55.acc, "$what; at 55 s: ${at55.err} vs ${at55.acc}")
            val end = ev.named("dish_stall_end").singleOrNull() ?: error(what)
            assertTrue(end.first in 60.0..61.0, what)
            assertTrue(out.filter { it.t in 63.0..70.0 }.all { it.out != null && it.err < 5.0 && !it.out.stalled }, "$what; after ${out.filter { it.t in 63.0..70.0 }.map { it.err }}")
            assertTrue(ev.named("dish_degraded").isEmpty(), what)
        }
    }

    @Test
    fun aFrozenFixWhileStandingIsNoStall() {
        val speed = { t: Double -> if (t < 25.0) 15.0 else if (t < 30.0) 15.0 - 3.0 * (t - 25.0) else 0.0 }
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = SoSynth(70.0, speed = speed, repeat = { it >= 35.0 }).run(InertialConfig.SHIPPED, events = ev)
        assertTrue(ev.named("dish_stall").isEmpty(), show(ev))
        val standing = out.filter { it.t > 35.0 }
        assertTrue(standing.all { it.state == InertialState.TRACKING && it.out?.stalled == false }, "${standing.map { it.state }}")
        assertTrue(standing.all { it.acc <= 15.0 }, "accuracy ${standing.map { it.acc }}")
    }

    @Test
    fun aStoppedDishClockStallsTheDish() {
        // diagnostics every second; the fix time stops advancing at 50 s (the same value at 50, 51, …, 69 s) and advances
        // again from 70 s: the stall (reason stale) is declared once the receipts have gone on for more than stallAgeS
        // (~61 s) and holds, with the dead reckoning serving, until the first diagnostics with an advanced clock (70.3 s)
        val stuck = 1000.0 + (50.3 - 0.45)
        val synth = SoSynth(90.0, diagEvery = 1.0, diagGps = { td -> if (td >= 50.0 && td < 70.0) stuck else 1000.0 + (td - 0.45) })
        val ev = ArrayList<Triple<Double, String, String>>()
        val rx = HashMap<Double, Double>()
        val out = synth.run(InertialConfig.SHIPPED, events = ev, poke = { t, c -> if (t == 63.0 || t == 69.0 || t == 71.0 || t == 72.0) rx[t] = c.lastAutoRx })
        val what = show(ev)
        val stall = ev.named("dish_stall").singleOrNull() ?: error(what)
        assertTrue(stall.first in 60.5..62.0 && stall.third.startsWith("reason=stale"), what)
        assertTrue(out.filter { it.t in 61.4..62.5 }.all { it.out?.stalled == true }, what)
        assertEquals(60.45, rx[63.0]!!, 1e-9, "AUTO ignored while stalled")
        assertEquals(60.45, rx[69.0]!!, 1e-9, "AUTO still ignored at 69 s")
        // dead reckoning from 64.5 s on until the clock advances: stalled, honest, and the told accuracy grows
        val dr = out.filter { it.t > 64.5 && it.t < 70.0 }
        assertTrue(dr.size >= 4, "serves while stalled: ${dr.size}")
        assertTrue(dr.all { it.state == InertialState.DEAD_RECKONING && it.out?.stalled == true }, "$what; ${dr.map { it.state to it.out?.stalled }}")
        for (s in dr) assertTrue(s.out != null && s.err <= s.acc, "honest DR at ${s.t}: ${s.err} vs ${s.acc}; $what")
        assertTrue(dr.last().acc > dr.first().acc, "the told accuracy grows: ${dr.map { "%.1f".format(java.util.Locale.ROOT, it.acc) }}")
        assertTrue(dr.zipWithNext().all { (a, b) -> b.acc >= a.acc - 1e-9 }, "never shrinks while stalled: ${dr.map { "%.1f".format(java.util.Locale.ROOT, it.acc) }}")
        val end = ev.named("dish_stall_end").singleOrNull() ?: error(what)
        assertTrue(end.first in 70.0..71.0, what)
        assertEquals(70.45, rx[71.0]!!, 1e-9, "AUTO taken back")
        // TRACKING again, not stalled, by 72.5 s
        val back = out.filter { it.t >= 72.5 }
        assertTrue(back.isNotEmpty() && back.all { it.state == InertialState.TRACKING && it.out != null && !it.out.stalled && it.err < 5.0 }, "$what; ${back.take(5).map { it.state to it.err }}")
        // a phone wall clock 30 s off (no receiver clock: the age comes from it): no stall
        val evW = ArrayList<Triple<Double, String, String>>()
        SoSynth(80.0, diagEvery = 1.0, wallOffset = 30.0).run(InertialConfig.SHIPPED, events = evW)
        assertTrue(evW.named("dish_stall").isEmpty(), show(evW))
    }

    @Test
    fun aTwoPointOscillationIsAStall() {
        // the 40 s answer and a point 30 m ahead (the fix of 42 s) in alternation from 41 to 56 s while moving
        val synth = SoSynth(80.0, pinTf = { tr -> if (tr in 41.0..56.0) (if ((tr - 41.0).toInt() % 2 == 0) 40.0 else 42.0) else null })
        val ev = ArrayList<Triple<Double, String, String>>()
        val out = synth.run(InertialConfig.SHIPPED, events = ev)
        val what = show(ev)
        val stall = ev.named("dish_stall").singleOrNull() ?: error(what)
        assertTrue(stall.first in 48.0..50.0 && stall.third.startsWith("reason=two_point"), what)
        assertTrue(out.filter { it.t in 52.0..56.0 }.all { it.state == InertialState.DEAD_RECKONING && it.out?.stalled == true }, "$what; ${out.filter { it.t in 52.0..56.0 }.map { it.state }}")
        val end = ev.named("dish_stall_end").singleOrNull() ?: error(what)
        assertTrue(end.first in 56.0..57.0, "ends with the first new position (receipt 56.45): $what")
        assertTrue(out.filter { it.t > 60.0 }.all { it.out != null && it.err < 5.0 }, what)
    }

    // ------------------------------------------------------------------ 11.-12. the phone flag and nothing else moves

    @Test
    fun theSuspectPhoneRaisesTheFloorWhileGnssIsValid() {
        val ev0 = ArrayList<Triple<Double, String, String>>()
        val plain = SoSynth(90.0).run(InertialConfig.SHIPPED, events = ev0)
        val ev1 = ArrayList<Triple<Double, String, String>>()
        val flagged = SoSynth(90.0, phoneSuspect = { it in 30.0..60.0 }).run(InertialConfig.SHIPPED, events = ev1)
        val phone = ev1.named("dish_regime").filter { it.third.endsWith("cause=phone") }
        assertEquals(listOf("from=TRUSTED to=SUSPECT cause=phone", "from=SUSPECT to=TRUSTED cause=phone"), phone.map { it.third })
        assertEquals(plain.size, flagged.size)
        for ((a, b) in plain.zip(flagged)) {
            assertEquals(a.out!!.e, b.out!!.e, "e at ${a.t}")
            assertEquals(a.out.n, b.out.n, "n at ${a.t}")
            assertEquals(a.state, b.state, "state at ${a.t}")
            assertTrue(b.out.accuracyM >= a.out.accuracyM, "accuracy at ${a.t}")
            if (b.t in 31.0..60.0) {
                assertEquals(DishGnssRegime.SUSPECT, b.out.dishRegime, "regime at ${b.t}")
                assertEquals(85.0, b.out.dishFloorM, 1e-9, "floor at ${b.t}")
                assertTrue(b.out.accuracyM > a.out.accuracyM && b.out.accuracyM >= 85.0, "larger at ${b.t}")
            } else if (b.t > 61.0 || b.t > 2.0 && b.t < 30.0) {
                assertEquals(DishGnssRegime.TRUSTED, b.out.dishRegime, "regime at ${b.t}")
                assertEquals(a.out.accuracyM, b.out.accuracyM, "accuracy back at ${b.t}")
            }
        }
        assertEquals(ev0.filter { it.second != "dish_regime" }, ev1.filter { it.second != "dish_regime" })
    }

    @Test
    fun nothingChangesWithDishGnssValid() {
        // 600 s: turns, a 20 s stop (205-225 s) with repeated fixes, a 40 s AUTO gap (400-440 s), network fixes, a handling burst
        val speed = { t: Double -> when { t < 200 -> 15.0; t < 205 -> 15.0 - 3.0 * (t - 200); t < 225 -> 0.0; t < 230 -> 3.0 * (t - 225); else -> 15.0 } }
        fun synth(gpsValid: (Double) -> Boolean?) = SoSynth(
            600.0, head = { 30.0 + 40.0 * sin(2 * PI * it / 200) }, speed = speed, autoOk = { it !in 400.0..440.0 },
            net = { it.toInt() % 5 == 0 }, repeat = { it in 208.0..222.0 }, handling = { it in 300.0..300.6 }, gpsValid = gpsValid,
        )
        val off = InertialConfig.SHIPPED.copy(so = InertialConfig.SHIPPED.so.copy(soEnabled = false, slotAware = false, stallDetector = false))
        for ((label, gv) in listOf<Pair<String, (Double) -> Boolean?>>("valid" to { true }, "unknown" to { null })) {
            val s = synth(gv)
            val evA = ArrayList<Triple<Double, String, String>>()
            val a = s.run(InertialConfig.SHIPPED, events = evA)
            val evB = ArrayList<Triple<Double, String, String>>()
            val b = s.run(off, events = evB)
            fun key(x: SoSynth.Served) = listOf(x.t, x.out?.e, x.out?.n, x.out?.accuracyM, x.state)
            assertEquals(b.map(::key), a.map(::key), "$label: served")
            assertEquals(evB.filter { it.second != "dish_regime" }, evA.filter { it.second != "dish_regime" }, "$label: events")
            assertTrue(evA.named("dish_stall", "slot_step", "dish_switch").isEmpty(), "$label: ${show(evA)}")
            assertTrue(evA.named("imu_handling_start").isNotEmpty() && evA.named("mount_learned").isNotEmpty() && a.any { it.state == InertialState.DEAD_RECKONING || it.state == InertialState.NETWORK_AIDED } &&
                a.any { it.t in 210.0..222.0 && it.out?.speed?.let { v -> v < 0.5 } == true }, "$label: the drive exercised the paths: ${evA.map { it.second }.distinct()}")
            if (label == "unknown") assertTrue(a.all { it.out == null || it.out.dishRegime == DishGnssRegime.UNKNOWN })
        }
    }

    @Test
    fun cfg2KeepsEveryNewSwitchOff() {
        assertFalse(InertialConfig.CFG2.soEnabled)
        assertFalse(InertialConfig.CFG2.slotAware)
        assertFalse(InertialConfig.CFG2.stallDetector)
        assertFalse(InertialConfig.CFG2_H9.soEnabled)
        assertTrue(InertialConfig.SHIPPED.soEnabled && InertialConfig.SHIPPED.slotAware && InertialConfig.SHIPPED.stallDetector)
        assertTrue(InertialConfig.FIXED_HOLD.soEnabled && InertialConfig.FIXED_HOLD.slotAware && InertialConfig.FIXED_HOLD.stallDetector)
    }

    // ------------------------------------------------------------------ 14. the replay's phone flag

    private fun call(sim: FakeDishSimulation, kind: DishCallKind): DishCallResult {
        val (resp, st) = sim.handle(kind.request(1))
        val raw = resp?.let { DeviceResponse.ADAPTER.encode(it) } ?: ByteArray(0)
        return DishCallResult(kind, 0, 0, "fake", 200, st.first, st.second.ifEmpty { null }, raw, resp, null)
    }

    @Test
    fun theReplayFeedsThePhoneFlag() {
        val wall0 = 1_791_990_000_000L
        fun drive(phoneFar: Boolean, farM: Double = 2000.0, farUntil: Int = 59): Pair<List<InertialEstimate>, List<InertialEvent>> {
            var now = 0L
            val sim = FakeDishSimulation(spoofEverySec = 0, radiusM = 2000.0, startNs = 0, clock = { now }, wallClockMs = { wall0 + now / 1_000_000L })
            val recs = ArrayList<Record>()
            fun rec(c: DishCallResult, t: Long) = Record(t_mono_ns = t, t_wall_ms = wall0 + t / 1_000_000L, dish = c.toRecord())
            for (k in 1..120) {
                now = k * sec + sec / 5
                recs += rec(call(sim, DishCallKind.STATUS), now)
                if (k % 10 == 0) {
                    now = k * sec + 3 * sec / 10
                    recs += rec(call(sim, DishCallKind.DIAGNOSTICS), now)
                }
                now = k * sec + 45 * sec / 100
                recs += rec(call(sim, DishCallKind.LOCATION_AUTO), now)
                // the phone's own GPS: on the dish, or [farM] (2 km) east of it from 40 s to [farUntil] (60 s)
                val tr = sim.truthAt(k + 0.6)
                val (plat, plon) = if (phoneFar && k in 40..farUntil) Geo.offset(tr.lat, tr.lon, 90.0, farM) else tr.lat to tr.lon
                val tp = k * sec + 6 * sec / 10
                recs += Record(
                    t_mono_ns = tp, t_wall_ms = wall0 + tp / 1_000_000L,
                    phone_location = PhoneLocation(provider = "gps", lat = plat, lon = plon, h_acc = 4f, speed = 12f, time_ms = wall0 + tp / 1_000_000L, elapsed_ns = tp),
                )
            }
            val events = ArrayList<InertialEvent>()
            val nav = InertialNav(InertialConfig.SHIPPED, -177.0, onEvent = { events += it })
            val served = ArrayList<InertialEstimate>()
            val replay = InertialReplay(nav, { s -> s.estimate?.let { served += it } })
            replay.addAll(recs.asSequence())
            replay.finish()
            return served to events
        }
        val (far, evFar) = drive(true)
        val rel = { e: InertialEstimate -> e.tNs / 1e9 }
        // the 2 km jump at 40 s is distrusted at once (GpsTrust rule 2, latched 30 s) and so is the jump back at 60 s, so
        // SUSPECT runs from the next AUTO call to ~91 s; TRUSTED before and after, the floor 85 m while suspect
        val show = far.map { "%.0f %s".format(java.util.Locale.ROOT, rel(it), it.dishRegime) }.toString()
        assertTrue(far.filter { rel(it) in 5.0..41.0 }.all { it.dishRegime == DishGnssRegime.TRUSTED && it.dishFloorM == 0.0 }, show)
        assertTrue(far.filter { rel(it) in 43.0..90.0 }.let { it.isNotEmpty() && it.all { e -> e.dishRegime == DishGnssRegime.SUSPECT && e.dishFloorM == 85.0 && e.accuracyM >= 85.0 } }, show)
        assertTrue(far.filter { rel(it) > 95.0 }.let { it.isNotEmpty() && it.all { e -> e.dishRegime == DishGnssRegime.TRUSTED && e.dishFloorM == 0.0 } }, show)
        assertEquals(2, evFar.count { it.name == "dish_regime" && it.detail.endsWith("cause=phone") }, evFar.filter { it.name == "dish_regime" }.joinToString { it.detail })
        val (near, evNear) = drive(false)
        assertTrue(near.filter { rel(it) > 5.0 }.all { it.dishRegime == DishGnssRegime.TRUSTED }, near.map { it.dishRegime }.distinct().toString())
        assertTrue(evNear.none { it.name == "dish_regime" && it.detail.endsWith("cause=phone") })
        // a phone 160 m off for the rest of the drive: rule 1 (far from Starlink) holds the flag once the 30 s jump latch
        // has run out. The check is fed the dish's own floor, not the SUSPECT floor: with that one the limit would widen
        // to ~200 m, the distrust clear after 5 s within, the floor drop, the distrust return, every 5 s
        val (held, evHeld) = drive(true, farM = 160.0, farUntil = 119)
        val showHeld = held.map { "%.0f %s".format(java.util.Locale.ROOT, rel(it), it.dishRegime) }.toString()
        assertTrue(held.filter { rel(it) in 47.0..119.0 }.let { it.size > 60 && it.all { e -> e.dishRegime == DishGnssRegime.SUSPECT && e.dishFloorM == 85.0 } }, showHeld)
        assertEquals(1, evHeld.count { it.name == "dish_regime" && it.detail.endsWith("cause=phone") }, evHeld.filter { it.name == "dish_regime" }.joinToString { it.detail })
    }

    // ------------------------------------------------------------------ the facade's phone flag before the first dish call

    @Test
    fun thePhoneFlagBeforeTheFirstDishCallIsAppliedWithIt() {
        val wall0 = 1_791_990_000_000L
        class Run(val nav: InertialNav, val events: List<InertialEvent>, val served: List<InertialEstimate>, val firstRegime: DishGnssRegime)
        fun drive(flagsBefore: List<Boolean>): Run {
            var now = 0L
            val sim = FakeDishSimulation(spoofEverySec = 0, radiusM = 2000.0, startNs = 0, clock = { now }, wallClockMs = { wall0 + now / 1_000_000L })
            val events = ArrayList<InertialEvent>()
            val nav = InertialNav(InertialConfig.SHIPPED, -177.0, onEvent = { events += it })
            // the flag arrives before anything else (no time origin yet): at the facade's clock zero and a little later
            flagsBefore.forEachIndexed { i, f -> nav.onPhoneGnssSuspect(i * sec / 10, f) }
            assertNull(nav.serve(0L), "nothing served before a dish call")
            val served = ArrayList<InertialEstimate>()
            now = sec + sec / 5
            val tFirst = now
            nav.onDishCall(call(sim, DishCallKind.STATUS), now, wall0 + now / 1_000_000L)
            val firstRegime = nav.dishRegime
            for (k in 1..30) {
                if (k > 1) {
                    now = k * sec + sec / 5
                    nav.onDishCall(call(sim, DishCallKind.STATUS), now, wall0 + now / 1_000_000L)
                }
                if (k % 10 == 0) {
                    now = k * sec + 3 * sec / 10
                    nav.onDishCall(call(sim, DishCallKind.DIAGNOSTICS), now, wall0 + now / 1_000_000L)
                }
                now = k * sec + 45 * sec / 100
                nav.onDishCall(call(sim, DishCallKind.LOCATION_AUTO), now, wall0 + now / 1_000_000L)?.let { served += it }
            }
            assertTrue(events.all { it.tNs >= tFirst }, "no event before the first dish call: ${events.map { it.name to it.tNs }}")
            return Run(nav, events, served, firstRegime)
        }
        // (the flag raised before the first call: SUSPECT from the first status, one dish_regime, none stamped at time 0)
        val on = drive(listOf(true))
        val (nav, ev, served) = Triple(on.nav, on.events, on.served)
        assertEquals(DishGnssRegime.SUSPECT, on.firstRegime, "the reported regime after the first status")
        val regimes = ev.filter { it.name == "dish_regime" }
        assertEquals(listOf("from=UNKNOWN to=SUSPECT cause=gps_valid"), regimes.map { it.detail })
        assertTrue(regimes.none { it.tNs == 0L } && regimes.all { it.tNs >= sec }, regimes.map { it.tNs }.toString())
        assertTrue(served.isNotEmpty() && served.all { it.dishRegime == DishGnssRegime.SUSPECT && it.dishFloorM == 85.0 && it.accuracyM >= 85.0 }, served.map { it.dishRegime }.distinct().toString())
        assertEquals(DishGnssRegime.SUSPECT, nav.dishRegime)
        // the GPS check's floor has no phone term (dish GNSS valid: 0), and the facade keeps the flag it was given
        assertEquals(0.0, nav.trustFloorM, "trust floor while SUSPECT")
        assertEquals(85.0, nav.dishFloorM, "told floor while SUSPECT")
        assertTrue(nav.phoneGnssSuspect)
        // (raised and lowered again before the first call: the latest value counts, TRUSTED as without a flag)
        val off = drive(listOf(true, false))
        assertEquals(DishGnssRegime.TRUSTED, off.firstRegime)
        assertEquals(listOf("from=UNKNOWN to=TRUSTED cause=gps_valid"), off.events.filter { it.name == "dish_regime" }.map { it.detail })
        assertTrue(off.served.isNotEmpty() && off.served.all { it.dishRegime == DishGnssRegime.TRUSTED && it.dishFloorM == 0.0 })
    }
}
