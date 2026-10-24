package tools.apachi.starlinknav.core.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.withSign

/*
 * The 8-state EKF of the inertial filter (port of `Ekf2` in nav2.py, PORTING2.md §2-§3): x = [E, N, v, ψ, b_a, b_g,
 * b_e, b_n] in a local east/north plane (m), forward speed (m/s), car heading clockwise from north (rad), forward
 * accel bias, gyro bias, and the dish's own position error b (AUTO = true + b). A ring of the last [InertialConfig.nb]
 * ticks keeps the prior of each tick so a delayed measurement (an AUTO fix ~0.45 s old, the dish speed 1 s before
 * that) is applied at its own time and the later ticks are re-propagated.
 */

internal const val N_X = 8
internal const val B_TIGHT = 0
internal const val B_OPEN = 1
internal const val B_WALK = 2

/** Python's `(a + π) % 2π − π`: to [−π, π). */
internal fun wrap(a: Double): Double {
    val w = 2 * Math.PI
    var m = (a + Math.PI) % w
    if (m != 0.0 && m < 0) m += w
    return m - Math.PI
}

/** To [−180, 180). */
internal fun wrapDeg(a: Double): Double {
    var m = (a + 180.0) % 360.0
    if (m != 0.0 && m < 0) m += 360.0
    return m - 180.0
}

/** Row-major square matrices of size [N_X] and the few products the filter needs. */
internal object M {
    fun eye(): DoubleArray = DoubleArray(N_X * N_X).also { for (i in 0 until N_X) it[i * N_X + i] = 1.0 }

    /** A·B for n×n matrices. */
    fun mul(a: DoubleArray, b: DoubleArray, n: Int = N_X): DoubleArray {
        val out = DoubleArray(n * n)
        for (i in 0 until n) for (k in 0 until n) {
            val aik = a[i * n + k]
            if (aik == 0.0) continue
            for (j in 0 until n) out[i * n + j] += aik * b[k * n + j]
        }
        return out
    }

    /** A·B·Aᵀ. */
    fun sandwich(a: DoubleArray, b: DoubleArray): DoubleArray {
        val ab = mul(a, b)
        val out = DoubleArray(N_X * N_X)
        for (i in 0 until N_X) for (j in 0 until N_X) {
            var s = 0.0
            for (k in 0 until N_X) s += ab[i * N_X + k] * a[j * N_X + k]
            out[i * N_X + j] = s
        }
        return out
    }

    fun symmetrise(p: DoubleArray): DoubleArray {
        val out = DoubleArray(N_X * N_X)
        for (i in 0 until N_X) for (j in 0 until N_X) out[i * N_X + j] = 0.5 * (p[i * N_X + j] + p[j * N_X + i])
        return out
    }

    /** uᵀ·B·u of the 2×2 block of [p] starting at (o, o). */
    fun quad2(p: DoubleArray, o: Int, u0: Double, u1: Double): Double =
        u0 * (p[o * N_X + o] * u0 + p[o * N_X + o + 1] * u1) + u1 * (p[(o + 1) * N_X + o] * u0 + p[(o + 1) * N_X + o + 1] * u1)

    /** Adds a symmetric 2×2 matrix (m00, m01, m11) to the 2×2 block at (o, o). */
    fun add2(p: DoubleArray, o: Int, m00: Double, m01: Double, m11: Double) {
        p[o * N_X + o] += m00
        p[o * N_X + o + 1] += m01
        p[(o + 1) * N_X + o] += m01
        p[(o + 1) * N_X + o + 1] += m11
    }

    fun finite(a: DoubleArray) = a.all { it.isFinite() }

    /**
     * Solves A·x = b for a small n×n system by Gaussian elimination with partial pivoting; null when singular.
     */
    fun solve(a: DoubleArray, b: DoubleArray, n: Int): DoubleArray? {
        val m = a.copyOf()
        val x = b.copyOf()
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(m[r * n + c]) > abs(m[piv * n + c])) piv = r
            if (m[piv * n + c] == 0.0) return null
            if (piv != c) {
                for (j in 0 until n) m[c * n + j] = m[piv * n + j].also { m[piv * n + j] = m[c * n + j] }
                x[c] = x[piv].also { x[piv] = x[c] }
            }
            for (r in c + 1 until n) {
                val f = m[r * n + c] / m[c * n + c]
                if (f == 0.0) continue
                for (j in c until n) m[r * n + j] -= f * m[c * n + j]
                x[r] -= f * x[c]
            }
        }
        for (c in n - 1 downTo 0) {
            var s = x[c]
            for (j in c + 1 until n) s -= m[c * n + j] * x[j]
            x[c] = s / m[c * n + c]
        }
        return x
    }
}

/** What one tick predicts with: forward specific force (NaN = none), yaw rate and its source, dish-error mode. */
internal class TickInput(
    val f: Double,
    val w: Double,
    /** 1 = gyro, 2 = dish heading rate, 0 = none. */
    val src: Int,
    val bMode: Int,
    /** Port-side: hold the position (reversing, or crawling in an AUTO gap). */
    val hold: Boolean = false,
    /** Port-side, while holding: along-track position variance added in this tick (the unknown slow motion), m². */
    val holdVar: Double = 0.0,
)

/** One part of a measurement, decided once (PORTING2 §2 table). */
internal sealed class Part {
    /** AUTO position: z = (E + b_e, N + b_n), R 2×2 symmetric. */
    class Pos(val z0: Double, val z1: Double, val r00: Double, val r01: Double, val r11: Double) : Part()
    /** Port-side: a position that does not share the dish error (network fix): z = (E, N). */
    class Net(val z0: Double, val z1: Double, val r: Double) : Part()
    class Speed(val z: Double, val r: Double) : Part()
    class Head(val z: Double, val r: Double) : Part()
    /** P_bb += M (opening, cross-track only). */
    class OpenB(val m00: Double, val m01: Double, val m11: Double) : Part()
    object ShrinkB : Part()
    object ResetB : Part()
    /** Port-side (exit policy): b = 0 in both directions, its covariance tight and uncorrelated. */
    object ResetBAll : Part()
    /** Port-side (exit policy): b += d and the position −= d (AUTO's predicted position stays): the hold moves onto the reference. */
    class ShiftB(val d0: Double, val d1: Double) : Part()
    /** P_pos += M (re-anchor). */
    class Reanchor(val m00: Double, val m01: Double, val m11: Double) : Part()
}

/** A measurement in the ring; an AUTO fix is decided at its first application and its parts cached. */
internal class Meas(val t: Double, var parts: List<Part>, var decided: Boolean = true, val auto: AutoFix? = null, val net: NetFix? = null)

/** What the AUTO decision needs (PORTING2 §4). */
internal class AutoFix(var z0: Double, var z1: Double, val hs: Double, val rx: Double, val verdict: Verdict, val decel: Double)

/** [RETURN_REF]: the exit policy's return (AUTO back on the fault-free track the reference keeps), port-side. */
internal enum class Verdict { NONE, SUSPECT, CONFIRM, RETURN, RETURN_WALK, RETURN_REF }

internal class Ekf(private val c: InertialConfig) {
    var x = DoubleArray(N_X)
    var p = DoubleArray(N_X * N_X)
    var ok = false

    class Entry(val t: Double, var x: DoubleArray, var p: DoubleArray, val u: TickInput?, val meas: MutableList<Meas> = ArrayList())

    val buf = ArrayDeque<Entry>()
    private var pending = ArrayList<Meas>()

    /** Called for an AUTO measurement the first time it is applied, with the state at its time. */
    var decide: (Meas, DoubleArray, DoubleArray) -> Unit = { _, _, _ -> }

    val headTime: Double get() = buf.last().t

    fun init(t: Double, e: Double, n: Double, v: Double, psi: Double, sigPsi: Double) {
        x = doubleArrayOf(e, n, v, psi, 0.0, 0.0, 0.0, 0.0)
        p = DoubleArray(N_X * N_X)
        val d = doubleArrayOf(sq(c.rAl), sq(c.rAl), 1.0, sq(sigPsi), sq(0.3), sq(0.05 * D2R), sq(c.sigB), sq(c.sigB))
        for (i in 0 until N_X) p[i * N_X + i] = d[i]
        buf.clear()
        pending = ArrayList()
        buf.addLast(Entry(t, x.copyOf(), p.copyOf(), null))
        ok = true
    }

    fun predict(x0: DoubleArray, p0: DoubleArray, u: TickInput, dt: Double): Pair<DoubleArray, DoubleArray> {
        val v = x0[2]
        val psi = x0[3]
        val sp = sin(psi)
        val cp = cos(psi)
        val f = M.eye()
        val q = DoubleArray(N_X)
        val x = x0.copyOf()
        f[0 * N_X + 2] = sp * dt; f[0 * N_X + 3] = v * cp * dt
        f[1 * N_X + 2] = cp * dt; f[1 * N_X + 3] = -v * sp * dt
        // port-side hold: the mean stays, the covariance still grows as if moving (plus [TickInput.holdVar])
        if (!u.hold) {
            x[0] += v * sp * dt
            x[1] += v * cp * dt
        }
        if (!u.f.isNaN()) {
            var a = u.f - x[4]
            if (c.accDb > 0) a = max(abs(a) - c.accDb, 0.0).withSign(a)
            x[2] += a * dt
            if (a != 0.0) f[2 * N_X + 4] = -dt
            q[2] = c.qVImu * dt
        } else {
            q[2] = c.qVFree * dt
        }
        when (u.src) {
            1 -> {
                x[3] += (u.w - x[5]) * dt
                f[3 * N_X + 5] = -dt
                q[3] = (c.qPsiGyro + sq(c.kScale * u.w)) * dt
            }
            2 -> {
                x[3] += u.w * dt
                q[3] = c.qPsiDishRate * dt
            }
            else -> q[3] = c.qPsiFree * dt
        }
        x[3] = wrap(x[3])
        q[4] = c.qBa * dt
        q[5] = c.qBg * dt
        // dish error: along always tight; cross per mode (u = along unit, n = cross unit, both from the prior ψ)
        val uu00 = sp * sp; val uu01 = sp * cp; val uu11 = cp * cp
        val nn00 = cp * cp; val nn01 = -cp * sp; val nn11 = sp * sp
        val pa = exp(-dt / c.tauB)
        val (pc, sc) = when (u.bMode) {
            B_OPEN -> exp(-dt / c.tauBOpen) to c.sigBOpenCr
            B_WALK -> exp(-dt / c.tauWalk) to c.sigB
            else -> pa to c.sigB
        }
        val fb00 = pa * uu00 + pc * nn00
        val fb01 = pa * uu01 + pc * nn01
        val fb11 = pa * uu11 + pc * nn11
        val b0 = fb00 * x[6] + fb01 * x[7]
        val b1 = fb01 * x[6] + fb11 * x[7]
        x[6] = b0
        x[7] = b1
        f[6 * N_X + 6] = fb00; f[6 * N_X + 7] = fb01
        f[7 * N_X + 6] = fb01; f[7 * N_X + 7] = fb11
        val p = M.sandwich(f, p0)
        for (i in 0 until N_X) p[i * N_X + i] += q[i]
        val qcr = if (u.bMode == B_TIGHT) c.qPos else c.qPosCrDegr
        M.add2(p, 0, dt * (c.qPos * uu00 + qcr * nn00), dt * (c.qPos * uu01 + qcr * nn01), dt * (c.qPos * uu11 + qcr * nn11))
        if (u.hold && u.holdVar > 0) M.add2(p, 0, u.holdVar * uu00, u.holdVar * uu01, u.holdVar * uu11)
        var ka = (1 - pa * pa) * sq(c.sigB)
        val kc = (1 - pc * pc) * sq(sc)
        // port-side leak guard: while open, a turn makes the along-track part of the dish error uncertain
        if (c.leakGuard && u.bMode == B_OPEN) ka += c.leakK * (sq(x0[6]) + sq(x0[7])) * abs(wrap(x[3] - psi))
        M.add2(p, 6, ka * uu00 + kc * nn00, ka * uu01 + kc * nn01, ka * uu11 + kc * nn11)
        if (x[2] < 0) x[2] = 0.0
        return x to p
    }

    /** Kalman update with an m×8 H (m = 1 or 2), Joseph form, symmetrised. */
    private fun update(x: DoubleArray, p: DoubleArray, y: DoubleArray, h: Array<DoubleArray>, r: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val m = y.size
        // PHᵀ (8×m)
        val pht = Array(N_X) { i -> DoubleArray(m) { k -> var s = 0.0; for (j in 0 until N_X) s += p[i * N_X + j] * h[k][j]; s } }
        val s = DoubleArray(m * m)
        for (a in 0 until m) for (b in 0 until m) {
            var acc = r[a * m + b]
            for (j in 0 until N_X) acc += h[a][j] * pht[j][b]
            s[a * m + b] = acc
        }
        val si = inv(s, m)
        val k = Array(N_X) { i -> DoubleArray(m) { b -> var acc = 0.0; for (a in 0 until m) acc += pht[i][a] * si[a * m + b]; acc } }
        val xn = x.copyOf()
        for (i in 0 until N_X) for (a in 0 until m) xn[i] += k[i][a] * y[a]
        val ikh = M.eye()
        for (i in 0 until N_X) for (j in 0 until N_X) {
            var acc = 0.0
            for (a in 0 until m) acc += k[i][a] * h[a][j]
            ikh[i * N_X + j] -= acc
        }
        val pn = M.sandwich(ikh, p)
        for (i in 0 until N_X) for (j in 0 until N_X) {
            var acc = 0.0
            for (a in 0 until m) for (b in 0 until m) acc += k[i][a] * r[a * m + b] * k[j][b]
            pn[i * N_X + j] += acc
        }
        return xn to M.symmetrise(pn)
    }

    private fun inv(s: DoubleArray, m: Int): DoubleArray = if (m == 1) doubleArrayOf(1.0 / s[0]) else {
        val det = s[0] * s[3] - s[1] * s[2]
        doubleArrayOf(s[3] / det, -s[1] / det, -s[2] / det, s[0] / det)
    }

    fun apply(x0: DoubleArray, p0: DoubleArray, m: Meas): Pair<DoubleArray, DoubleArray> {
        if (!m.decided) {
            decide(m, x0, p0)
            m.decided = true
        }
        var x = x0
        var p = p0
        for (part in m.parts) {
            val r: Pair<DoubleArray, DoubleArray> = when (part) {
                is Part.Pos -> {
                    val h = arrayOf(DoubleArray(N_X).also { it[0] = 1.0; it[6] = 1.0 }, DoubleArray(N_X).also { it[1] = 1.0; it[7] = 1.0 })
                    val y = doubleArrayOf(part.z0 - (x[0] + x[6]), part.z1 - (x[1] + x[7]))
                    update(x, p, y, h, doubleArrayOf(part.r00, part.r01, part.r01, part.r11))
                }
                is Part.Net -> {
                    val h = arrayOf(DoubleArray(N_X).also { it[0] = 1.0 }, DoubleArray(N_X).also { it[1] = 1.0 })
                    update(x, p, doubleArrayOf(part.z0 - x[0], part.z1 - x[1]), h, doubleArrayOf(part.r, 0.0, 0.0, part.r))
                }
                is Part.Head -> update(x, p, doubleArrayOf(wrap(part.z - x[3])), arrayOf(DoubleArray(N_X).also { it[3] = 1.0 }), doubleArrayOf(part.r))
                is Part.Speed -> update(x, p, doubleArrayOf(part.z - x[2]), arrayOf(DoubleArray(N_X).also { it[2] = 1.0 }), doubleArrayOf(part.r))
                is Part.OpenB -> {
                    p = p.copyOf().also { M.add2(it, 6, part.m00, part.m01, part.m11) }
                    continue
                }
                Part.ShrinkB -> {
                    p = shrink(p, x[3], c.sigB)
                    continue
                }
                Part.ResetBAll -> {
                    x = x.copyOf().also { it[6] = 0.0; it[7] = 0.0 }
                    p = resetAll(p, c.sigB)
                    continue
                }
                is Part.ShiftB -> {
                    x = x.copyOf().also { it[6] += part.d0; it[7] += part.d1; it[0] -= part.d0; it[1] -= part.d1 }
                    continue
                }
                Part.ResetB -> {
                    val n0 = cos(x[3]); val n1 = -sin(x[3])
                    val bc = x[6] * n0 + x[7] * n1
                    x = x.copyOf().also { it[6] = x[6] - bc * n0; it[7] = x[7] - bc * n1 }
                    p = shrink(p, x[3], c.sigB)
                    continue
                }
                is Part.Reanchor -> {
                    p = p.copyOf().also { M.add2(it, 0, part.m00, part.m01, part.m11) }
                    continue
                }
            }
            x = r.first
            p = r.second
            if (x[2] < 0) x[2] = 0.0
        }
        return x to p
    }

    fun tick(t: Double, u: TickInput) {
        var (x1, p1) = predict(x, p, u, c.dt)
        val ent = Entry(t, x1.copyOf(), p1.copyOf(), u)
        buf.addLast(ent)
        while (buf.size > c.nb) buf.removeFirst()
        val keep = ArrayList<Meas>()
        for (m in pending) (if (m.t <= t + 1e-9) ent.meas else keep).add(m)
        pending = keep
        ent.meas.sortBy { it.t }
        for (m in ent.meas) {
            val r = apply(x1, p1, m)
            x1 = r.first
            p1 = r.second
        }
        x = x1
        p = p1
    }

    fun insert(m: Meas) {
        val t = m.t
        if (t > buf.last().t + 1e-9) {
            pending.add(m)
            return
        }
        var k = 0
        for (i in buf.size - 1 downTo 0) {
            if (buf[i].t < t - 1e-9) {
                k = i + 1
                break
            }
        }
        k = minOf(k, buf.size - 1)
        val ent = buf[k]
        ent.meas.add(m)
        ent.meas.sortBy { it.t }
        var xx = ent.x.copyOf()
        var pp = ent.p.copyOf()
        for (mm in ent.meas) {
            val r = apply(xx, pp, mm)
            xx = r.first
            pp = r.second
        }
        for (i in k + 1 until buf.size) {
            val e = buf[i]
            val r = predict(xx, pp, e.u!!, c.dt)
            xx = r.first
            pp = r.second
            e.x = xx.copyOf()
            e.p = pp.copyOf()
            for (mm in e.meas) {
                val r2 = apply(xx, pp, mm)
                xx = r2.first
                pp = r2.second
            }
        }
        x = xx
        p = pp
    }

    /** Moves every stored position by the map [f] (re-centring the local plane). */
    fun remap(f: (Double, Double) -> Pair<Double, Double>) {
        fun mv(a: DoubleArray) {
            val (e, n) = f(a[0], a[1])
            a[0] = e
            a[1] = n
        }
        mv(x)
        for (e in buf) mv(e.x)
        val all = pending + buf.flatMap { it.meas }
        for (m in all) {
            m.parts = m.parts.map { part ->
                when (part) {
                    is Part.Pos -> f(part.z0, part.z1).let { Part.Pos(it.first, it.second, part.r00, part.r01, part.r11) }
                    is Part.Net -> f(part.z0, part.z1).let { Part.Net(it.first, it.second, part.r) }
                    else -> part
                }
            }
            m.auto?.let { a -> f(a.z0, a.z1).let { a.z0 = it.first; a.z1 = it.second } }
            m.net?.let { a -> f(a.z0, a.z1).let { a.z0 = it.first; a.z1 = it.second } }
        }
    }

    companion object {
        /** The dish-error block of P set to σ²·I and its correlations with the other states removed. */
        fun resetAll(p: DoubleArray, sig: Double): DoubleArray {
            val out = p.copyOf()
            for (i in 0 until N_X) for (j in 6..7) {
                out[i * N_X + j] = 0.0
                out[j * N_X + i] = 0.0
            }
            out[6 * N_X + 6] = sig * sig
            out[7 * N_X + 7] = sig * sig
            return out
        }

        /** P = T P Tᵀ with T scaling the cross-track part of b to the tight σ (PORTING2 `shrinkb`). */
        fun shrink(p: DoubleArray, psi: Double, sigT: Double): DoubleArray {
            val n0 = cos(psi); val n1 = -sin(psi)
            val u0 = sin(psi); val u1 = cos(psi)
            val pcr = M.quad2(p, 6, n0, n1)
            if (pcr <= sigT * sigT) return p
            val s = sigT / sqrt(pcr)
            val t = M.eye()
            t[6 * N_X + 6] = u0 * u0 + s * n0 * n0
            t[6 * N_X + 7] = u0 * u1 + s * n0 * n1
            t[7 * N_X + 6] = u1 * u0 + s * n1 * n0
            t[7 * N_X + 7] = u1 * u1 + s * n1 * n1
            return M.symmetrise(M.sandwich(t, p))
        }
    }
}
