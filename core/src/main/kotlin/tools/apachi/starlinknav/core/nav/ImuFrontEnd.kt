package tools.apachi.starlinknav.core.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phone IMU front end (port of `ImuFront2`, PORTING2.md §8): gravity axis from a slow low-pass of the accelerometer,
 * the clockwise yaw rate about it, the horizontal specific force in a fixed basis, a handling detector (the phone
 * moved in its mount: gyro far above the car's yaw rate) that drops the IMU and starts a new mount epoch, and the
 * per-epoch forward-axis fit (recursive least squares of the horizontal specific force on the dish speed derivative
 * and the centripetal accel, with forgetting and a quality gate).
 */
internal class ImuFrontEnd(private val c: InertialConfig, private val log: (Double, String, String) -> Unit) {
    var g: DoubleArray? = null
        private set
    private var fastUntil = -1e9
    private var handledUntil = -1e9
    var handling = false
        private set
    private val exc = ArrayDeque<Boolean>()
    private var gyroOkFrom = 1e18
    private var gyroReadyLogged = false

    /** (t, ah0, ah1, ω_cw), the last 12 s. */
    private class H(val t: Double, val a0: Double, val a1: Double, val w: Double)
    private val hist = ArrayDeque<H>()
    var nHandling = 0
        private set
    var epoch = 0
        private set

    // forward-axis fit
    private var nn = DoubleArray(16)
    private var rr = DoubleArray(4)
    private var yy = 0.0
    private var n = 0.0
    private var sx = DoubleArray(2)
    private var sxx = DoubleArray(2)
    private val thHist = ArrayDeque<Double>()
    var faReady = false
        private set
    private var th = 0.0
    private var cc = DoubleArray(2)
    private var lastFitT: Double? = null

    /** NONE (no valid sample), HANDLING or WARMING when the last step returned nothing; GYRO otherwise. */
    var lastStep = ImuState.NONE
        private set

    init {
        resetFa()
    }

    private fun resetFa() {
        nn = DoubleArray(16); rr = DoubleArray(4); yy = 0.0; n = 0.0
        sx = DoubleArray(2); sxx = DoubleArray(2)
        thHist.clear()
        faReady = false
        th = 0.0; cc = DoubleArray(2)
        lastFitT = null
    }

    class Out(val f: Double, val w: Double)

    /** One tick: the mean accel and gyro over (t − DT, t] (bias removed), or null; returns (f or NaN, ω_cw) or null. */
    fun step(t: Double, acc: DoubleArray?, gyr: DoubleArray?, dishRateDeg: Double): Out? {
        lastStep = ImuState.NONE
        if (acc == null || gyr == null || !acc.all { it.isFinite() } || !gyr.all { it.isFinite() }) return null
        val gn = norm(gyr)
        val an = norm(acc)
        if (gn > 35.0 || an > 100.0 || an < 1.0) return null
        lastStep = ImuState.HANDLING
        exc.addLast(gn * R2D - abs(dishRateDeg) > c.handleExcessDeg)
        while (exc.size > 5) exc.removeFirst()
        if (exc.count { it } >= 4) {
            if (!handling) {
                handling = true
                nHandling++
                log(t, "imu_handling_start", "epoch=$epoch")
            }
            handledUntil = t + c.handleHold
        }
        if (handling) {
            if (t < handledUntil) return null
            handling = false
            log(t, "imu_handling_end", "epoch=${epoch + 1}")
            g = null
            if (faReady) log(t, "imu_forward_axis_lost", "reason=handling epoch=$epoch")
            resetFa()
            epoch++
        }
        var gg = g
        if (gg == null) {
            gg = acc.copyOf()
            fastUntil = t + c.gFastS
            gyroOkFrom = t + c.gyroReadyAfter
            gyroReadyLogged = false
            hist.clear()
        }
        val tau = if (t < fastUntil) c.gTauFast else c.gTau
        var a = c.dt / tau
        val gnorm0 = norm(gg)
        val up0 = doubleArrayOf(gg[0] / gnorm0, gg[1] / gnorm0, gg[2] / gnorm0)
        val wCw = -(gyr[0] * up0[0] + gyr[1] * up0[1] + gyr[2] * up0[2])
        if (abs(wCw) * R2D > 5.0 || abs(an - 9.80665) > 0.7) a *= 0.1
        val g2 = DoubleArray(3) { (1 - a) * gg[it] + a * acc[it] }
        g = g2
        val gnorm = norm(g2)
        val up = doubleArrayOf(g2[0] / gnorm, g2[1] / gnorm, g2[2] / gnorm)
        val e1 = if (abs(up[2]) < 0.9) cross(up, doubleArrayOf(0.0, 0.0, 1.0)) else cross(up, doubleArrayOf(1.0, 0.0, 0.0))
        val e1n = norm(e1)
        for (i in 0..2) e1[i] /= e1n
        val e2 = cross(up, e1)
        val au = acc[0] * up[0] + acc[1] * up[1] + acc[2] * up[2]
        val ah = DoubleArray(3) { acc[it] - au * up[it] }
        val ah0 = ah[0] * e1[0] + ah[1] * e1[1] + ah[2] * e1[2]
        val ah1 = ah[0] * e2[0] + ah[1] * e2[1] + ah[2] * e2[2]
        hist.addLast(H(t, ah0, ah1, wCw))
        while (hist.size > 120) hist.removeFirst()
        if (t < gyroOkFrom) {
            lastStep = ImuState.WARMING
            return null
        }
        lastStep = ImuState.GYRO
        if (!gyroReadyLogged) {
            gyroReadyLogged = true
            log(t, "imu_ready", "epoch=$epoch")
        }
        val f = if (faReady) cos(th) * (ah0 - cc[0]) + sin(th) * (ah1 - cc[1]) else Double.NaN
        return Out(f, wCw)
    }

    private fun window(ta: Double, tb: Double): DoubleArray? {
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var k = 0
        for (h in hist) if (ta < h.t && h.t <= tb) {
            s0 += h.a0; s1 += h.a1; s2 += h.w; k++
        }
        if (k < 0.8 * (tb - ta) / c.dt) return null
        return doubleArrayOf(s0 / k, s1 / k, s2 / k)
    }

    /** Two trusted fixes (fix time, dish speed) 1.5-3 s apart. */
    fun learn(t: Double, t0: Double, hs0: Double, t1: Double, hs1: Double) {
        if (!c.forwardAxis || handling || g == null) return
        if (!(t1 - t0 in 1.5..3.0)) return
        val vbar = 0.5 * (hs0 + hs1)
        if (minOf(hs0, hs1) < c.faMinV) return
        val mw = window(t0 - c.faLag, t1 - c.faLag) ?: return
        val dv = (hs1 - hs0) / (t1 - t0)
        val al = -vbar * mw[2]
        // ah = k R(θ) [dv, al] + c : ah0 = p dv − q al + c0 ; ah1 = q dv + p al + c1
        val rows = arrayOf(doubleArrayOf(dv, -al, 1.0, 0.0), doubleArrayOf(al, dv, 0.0, 1.0))
        val ys = doubleArrayOf(mw[0], mw[1])
        val lam = exp(-(t1 - (lastFitT ?: t1)) / c.faTau)
        lastFitT = t1
        for (i in 0..3) for (j in 0..3) nn[i * 4 + j] = lam * nn[i * 4 + j] + rows[0][i] * rows[0][j] + rows[1][i] * rows[1][j]
        for (i in 0..3) rr[i] = lam * rr[i] + rows[0][i] * ys[0] + rows[1][i] * ys[1]
        yy = lam * yy + ys[0] * ys[0] + ys[1] * ys[1]
        n = lam * n + 1.0
        sx[0] = lam * sx[0] + dv; sx[1] = lam * sx[1] + al
        sxx[0] = lam * sxx[0] + dv * dv; sxx[1] = lam * sxx[1] + al * al
        if (n < 10) return
        val reg = nn.copyOf().also { for (i in 0..3) it[i * 4 + i] += 1e-9 }
        val beta = M.solve(reg, rr, 4) ?: return
        val pp = beta[0]
        val qq = beta[1]
        val thNew = atan2(qq, pp)
        val kk = hypot(pp, qq)
        var bnb = 0.0
        for (i in 0..3) for (j in 0..3) bnb += beta[i] * nn[i * 4 + j] * beta[j]
        val br = beta[0] * rr[0] + beta[1] * rr[1] + beta[2] * rr[2] + beta[3] * rr[3]
        val rss = max(yy - 2 * br + bnb, 0.0)
        val rms = sqrt(rss / (2 * n))
        val sd0 = sqrt(max(sxx[0] / n - sq(sx[0] / n), 0.0))
        val sd1 = sqrt(max(sxx[1] / n - sq(sx[1] / n), 0.0))
        thHist.addLast(thNew)
        while (thHist.size > c.faNStab) thHist.removeFirst()
        val stable = thHist.size == c.faNStab && thHist.maxOf { abs(wrap(it - thNew)) } * R2D <= c.faStableDeg
        val good = n >= c.faNMin && sd0 >= c.faSdDv && sd1 >= c.faSdLat && rms <= c.faRms && kk >= c.faKMin && kk <= c.faKMax && stable
        if (good) {
            th = thNew
            cc = doubleArrayOf(beta[2], beta[3])
            if (!faReady) {
                faReady = true
                log(t, "imu_forward_axis_ready", "theta_deg=%+.1f scale=%.2f rms=%.2f n=%.0f epoch=%d".format(java.util.Locale.ROOT, thNew * R2D, kk, rms, n, epoch))
            }
        } else if (faReady && (kk < c.faKMin - 0.1 || kk > c.faKMax + 0.1 || rms > c.faRms + 0.1 || abs(wrap(thNew - th)) * R2D > 3 * c.faStableDeg)) {
            faReady = false
            log(t, "imu_forward_axis_lost", "reason=fit theta_deg=%+.1f scale=%.2f rms=%.2f epoch=%d".format(java.util.Locale.ROOT, thNew * R2D, kk, rms, epoch))
        }
    }

    /** Ticks of history behind [stationary] (it needs 10). */
    val histSize: Int get() = hist.size

    /** Max |ω_cw| over the last 10 ticks below the ZUPT limit (needs 10 ticks of history). */
    fun stationary(): Boolean {
        if (hist.size < 10) return false
        var m = 0.0
        for (i in hist.size - 10 until hist.size) m = max(m, abs(hist[i].w))
        return m * R2D < c.zuptGyroDeg
    }

    private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
}
