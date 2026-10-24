package tools.apachi.starlinknav.core.nav

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** What the filter serves; positions in the local plane (m). */
enum class InertialState {
    /** Following the dish (AUTO), or the raw fix before the filter started. */
    TRACKING,
    /** The dish-error state is open: AUTO is held to be off to the side by [InertialEstimate.sidewaysOffsetM]. */
    SIDEWAYS_OPEN,
    /** No AUTO fix for more than 2.5 s: dead reckoning on IMU and dish heading. */
    DEAD_RECKONING,
    /** Dead reckoning with network (cell/Wi-Fi) fixes holding the position. */
    NETWORK_AIDED,
    /** Nothing to serve (not started, reset, or dead reckoning too uncertain: push stop). */
    NO_OUTPUT,
}

/** The phone IMU as the filter uses it. */
enum class ImuState {
    /** No usable samples (none arrive, invalid, or the IMU is switched off). */
    NONE,
    /** Learning gravity after a start or a handling event; the gyro is not used yet. */
    WARMING,
    /** Phone moved in its mount: IMU dropped until it is still again. */
    HANDLING,
    /** Gyro yaw rate in use; the forward axis is not learned (speed from the dish only). */
    GYRO,
    /** Gyro and forward accel in use. */
    FORWARD,
}

/**
 * The inertial filter in the local plane on one relative clock (s): the logic of `Nav2` in `nav2.py`
 * (PORTING2.md §4-§10) plus the port-side changes in [InertialConfig] (accuracy floors, nothing served after a reset
 * until re-initialised, position held while reversing or crawling in a gap, network fixes, two detectors). [InertialNav] feeds it from dish
 * calls, IMU samples and network fixes; tests feed it directly.
 */
internal class InertialCore(
    val c: InertialConfig,
    storedMountDeg: Double?,
    private val useImu: Boolean,
    persistedMountDeg: Double? = storedMountDeg,
    /** Keep [fires] (desktop tools); off in the app, whose filter runs for hours and only counts them. */
    private val recordFires: Boolean = false,
    private val log: (Double, String, String) -> Unit,
) {
    val ekf = Ekf(c)
    val imu = ImuFrontEnd(c, log)
    val mount = MountCalibrator(c, log, storedMountDeg, persistedMountDeg)
    private val det = SidewaysDetector(c, c.cusumH)
    private val watch = c.watchCusumH?.let { SidewaysDetector(c, it) }
    private val latency = FixLatency(c.defaultLatency, c.latMin..c.latMax)
    val fixes = ArrayDeque<MountCalibrator.Fix>()
    val heads = ArrayDeque<MountCalibrator.Head>()
    private var lastStatusT = Double.NEGATIVE_INFINITY
    private var lastGpsValid: Boolean? = null
    private var lastSats: Int? = null
    private var dishRate = 0.0
    private var dishRateT = -1e9
    private var lastXy: Pair<Double, Double>? = null
    var lastAutoRx = -1e9
        private set
    private var rawPos: Pair<Double, Double>? = null
    private var rawT = -1e9
    private var resetSinceInit = false
    private val fHist = ArrayDeque<Double>()
    private var prevState: InertialState? = null
    var bMode = B_TIGHT
        private set
    private var tTrig = -1e9
    private var trigSign = 0
    private var nClose = 0
    private var nReject = 0
    private var lastTrustBreak = -1e9
    var nResets = 0
        private set
    /** (t, "det" | "gate") of every acting-detector confirm and gate reject (false-alarm statistics); only with [recordFires]. */
    val fires = ArrayList<Pair<Double, String>>()
    var watchConfirms = 0
        private set
    var imuState = ImuState.NONE
        private set
    // port-side state
    var reversing = false
        private set
    private var reverseVotes = 0
    private var holdActive = false
    /** The held position is the position at this time (the last fix's receipt, or its fix time while reversing). */
    private var holdAnchorT = 0.0
    private var holdAcc0 = 0.0
    private var holdRate = 0.0
    private var holdFresh = false
    private var lastNetT = -1e9
    private var pushStopped = false
    // exit policy (port-side): the hold's start and the fault-free reference (see InertialConfig.returnRule)
    /** The opening of the current (or last) hold. */
    private var tOpen = -1e9
    /** The reference is alive: from an opening until the return, an early close, a gap, or [InertialConfig.refMaxS] after the hold. */
    private var refOn = false
    /** AUTO's offset from the fault-free track at the opening (cross-track, + = right), set at the opening fix. */
    private var refF0 = Double.NaN
    /** AUTO's own sideways motion against the dish heading since then, and the distance driven, m. */
    private var refA = 0.0
    private var refD = 0.0
    /** When the hold ended (walk back), or NaN while it is open. */
    private var refHoldEnd = Double.NaN
    /** Sideways drift of trusted AUTO chords against the dish heading: Σ cross-track part, Σ length (distance-weighted). */
    private var chordLatSum = 0.0
    private var chordDist = 0.0
    /** When the last hold ended (walk back, close, return); an opening soon after gets no extension. */
    private var lastHoldEnd = -1e9
    /** The current hold may go on past holdMax (its opening was not soon after a hold or an AUTO gap). */
    private var openTrusted = true
    /** Since when AUTO has been tracked without a gap longer than retGapS (the filter's start does not count as one). */
    private var trackSince = -1e9
    /** AUTO was taken back on a one-chord jump while still away from the reference: the reference stays for the rest. */
    private var refLoose = false
    /** The onset of a fault whose reference ended without a return (for the mirror test), and when. */
    private var mirrorF0 = Double.NaN
    private var mirrorT = -1e9

    init {
        ekf.decide = { m, x, p -> decide(m, x, p) }
    }

    // ------------------------------------------------------------------ inputs

    fun onDiag(age: Double) = latency.add(age)

    val latencyS: Double get() = latency.value

    fun onTick(t: Double, acc: DoubleArray?, gyr: DoubleArray?) {
        val rate = if (t - dishRateT < 2.5) dishRate else 0.0
        val out = if (useImu) imu.step(t, acc, gyr, rate) else null
        imuState = when {
            !useImu -> ImuState.NONE
            out != null -> if (imu.faReady) ImuState.FORWARD else ImuState.GYRO
            else -> imu.lastStep
        }
        val f = out?.f ?: Double.NaN
        val (w, src) = when {
            out != null -> out.w to 1
            t - dishRateT < 2.5 -> rate * D2R to 2
            else -> 0.0 to 0
        }
        if (!f.isNaN()) {
            fHist.addLast(f)
            while (fHist.size > 10) fHist.removeFirst()
        } else {
            fHist.clear()
        }
        if (!ekf.ok) return
        // Reversing (with or without AUTO): no forward integration at all, AUTO alone moves the position; crawling
        // (the dish said so at the last fix, and the filter still thinks so): once dead reckoning starts. The position
        // covariance and the told accuracy grow from the last fix on, at the speed a car could have moved backwards
        // or slowly since, so the gate takes AUTO back when it returns.
        val sinceFix = t - lastAutoRx
        val slow = (fixes.lastOrNull()?.hs ?: 0.0) < c.holdSpeed && ekf.x[2] < c.holdSpeed
        val hold = c.reverseHold && (reversing || (sinceFix > c.drAfter && slow))
        if (hold && !holdActive) {
            startHold(t)
        } else if (!hold && holdActive) {
            holdActive = false
        }
        var holdVar = 0.0
        if (holdActive) {
            // along-track σ(τ) = rate·τ (τ since the anchor: the car went forwards or backwards by up to that), as the
            // increment over this tick; the first held tick catches up from the anchor. Cross-track stays (no push
            // stop on the cross 1σ for a car standing at a light).
            val t1 = max(t - holdAnchorT, 0.0)
            val t0 = if (holdFresh) 0.0 else max(t - c.dt - holdAnchorT, 0.0)
            holdVar = sq(holdRate) * (t1 * t1 - t0 * t0)
            holdFresh = false
        }
        ekf.tick(t, TickInput(f, w, src, bMode, holdActive, holdVar))
        watchdog(t)
    }

    private fun startHold(t: Double) {
        holdActive = true
        holdFresh = true
        val last = fixes.lastOrNull()
        holdAnchorT = if (reversing && last != null) last.tf else lastAutoRx
        holdRate = if (reversing) max(c.holdAccRate, last?.hs ?: 0.0) else c.holdAccRate
        holdAcc0 = max(acc68(ekf.p, 0.0, ekf.x), c.accFloorM)
        log(t, "hold", "reason=${if (reversing) "reversing" else "slow"} speed_mps=%.1f".format(Locale.ROOT, ekf.x[2]))
    }

    private fun watchdog(t: Double) {
        if (!(M.finite(ekf.x) && M.finite(ekf.p))) {
            ekf.ok = false
            nResets++
            bMode = B_TIGHT
            holdActive = false
            refOn = false
            if (c.noRawAfterReset) {
                rawPos = null
                resetSinceInit = true
            }

            log(t, "filter_reset", "reason=non_finite")
        }
    }

    fun onStatus(t: Double, hx: Double?, attState: Int?, uncDeg: Double?, gpsValid: Boolean?, sats: Int?) {
        lastStatusT = t
        lastGpsValid = gpsValid
        lastSats = sats
        if (attState != 2 || hx == null || !hx.isFinite()) return
        val hxU: Double
        if (heads.isNotEmpty()) {
            val prev = heads.last()
            hxU = prev.hxU + wrapDeg(hx - prev.hxU)
            if (t - prev.t > 0.3 && t - prev.t < 3.0) {
                dishRate = (hxU - prev.hxU) / (t - prev.t)
                dishRateT = t
            }
        } else {
            hxU = hx
        }
        heads.addLast(MountCalibrator.Head(t, hxU))
        while (heads.size > 20) heads.removeFirst()
        if (!ekf.ok) return
        val mv = mount.value
        if (mv != null && (uncDeg == null || !uncDeg.isFinite() || uncDeg < 10.0)) {
            val z = wrap((hx - mv) * D2R)
            val u = if (uncDeg != null && uncDeg.isFinite()) uncDeg else 1.0
            val sig = max(u, c.rHeadMinDeg) * D2R
            ekf.insert(Meas(t, listOf(Part.Head(z, sig * sig))))
        }
        val hs = fixes.lastOrNull()?.hs ?: 99.0
        val still = hs < c.zuptHs && (!useImu || imu.stationary() || imu.g == null)
        if (still && t - lastAutoRx < 2.5) ekf.insert(Meas(t, listOf(Part.Speed(0.0, sq(c.zuptSig)))))
        watchdog(t)
    }

    /** Dish car heading (rad) and rate (rad/s) at [t] from the converged headings, or null. */
    private fun headAt(t: Double): Pair<Double, Double>? {
        val mv = mount.value ?: return null
        if (heads.size < 2) return null
        if (t > heads.last().t + 1.5 || t < heads.first().t) return null
        for (i in heads.size - 1 downTo 1) {
            val a = heads[i - 1]
            val b = heads[i]
            if ((a.t <= t && t <= b.t) || (i == heads.size - 1 && t > b.t)) {
                if (b.t - a.t > 3.0 || b.t - a.t <= 0) return null
                val rate = (b.hxU - a.hxU) / (b.t - a.t)
                val h = a.hxU + rate * (t - a.t)
                return wrap((h - mv) * D2R) to rate * D2R
            }
        }
        return null
    }

    /** An AUTO answer repeating the previous fix (prototype: `on_auto` with the same xy): only the receipt time counts. */
    fun onAutoSeenAgain(t: Double) {
        lastAutoRx = t
    }

    fun onAuto(t: Double, e: Double, n: Double, hs: Double) {
        if (!e.isFinite() || !n.isFinite() || !hs.isFinite() || hs < 0 || hs > 90) return
        lastAutoRx = t
        lastXy?.let { if (it.first == e && it.second == n) return }
        lastXy = e to n
        val tf = t - latency.value
        val prev = fixes.lastOrNull()
        fixes.addLast(MountCalibrator.Fix(tf, e, n, hs))
        while (fixes.size > 40) fixes.removeFirst()
        var ddec = 0.0
        if (fixes.size >= 3) {
            val ta = fixes[fixes.size - 3]
            if (tf - ta.tf > 1.0 && tf - ta.tf < 3.5) ddec = (hs - ta.hs) / (tf - ta.tf)
        }
        // ---- detectors (dish data only)
        var verdict = Verdict.NONE
        if (prev != null) {
            val dt = tf - prev.tf
            if (dt > max(2.5, c.retGapS)) trackSince = t
            if (dt > 2.5) {
                det.reset()
                watch?.reset()
            } else if (dt > 0.3 && 0.5 * (hs + prev.hs) > c.detMinSpeed) {
                val hm = headAt(0.5 * (tf + prev.tf))
                if (hm != null) {
                    val psi = hm.first
                    val vLat = ((e - prev.e) * cos(psi) - (n - prev.n) * sin(psi)) / dt
                    val sats = lastSats
                    val health = t - lastStatusT < 3.0 && ((sats != null && sats <= c.satsMin) || lastGpsValid == false)
                    if (c.detector) verdict = det.step(dt, vLat, health, learn = bMode == B_TIGHT)
                    watch?.let { w ->
                        if (w.step(dt, vLat, health, learn = bMode == B_TIGHT) == Verdict.CONFIRM) {
                            watchConfirms++
                            log(t, "watch_suspect", "sign=%+d vlat_mps=%+.2f h_m=%.1f acting=%s".format(Locale.ROOT, w.sign, vLat, w.lastH, verdict.name.lowercase()))
                        }
                    }
                    if (verdict == Verdict.CONFIRM) {
                        if (recordFires) fires += t to "det"
                        lastTrustBreak = t
                        val sg = det.sign
                        val bCr = if (ekf.ok) ekf.x[6] * cos(ekf.x[3]) - ekf.x[7] * sin(ekf.x[3]) else 0.0
                        if (sg == -trigSign && bMode == B_TIGHT && t - tTrig < c.tRev) {
                            log(t, "dish_returning", "sign=%+d".format(Locale.ROOT, sg))
                            verdict = Verdict.RETURN
                        } else if (bMode == B_WALK && bCr * sg < 0) {
                            log(t, "dish_returning", "sign=%+d reset_m=%+.1f".format(Locale.ROOT, sg, bCr))
                            verdict = Verdict.RETURN_WALK
                            lastHoldEnd = t
                        }
                        trigSign = sg
                    }
                }
            }
            // ---- the dish's return after its reference was lost: a jump mirroring that fault's onset (port-side)
            if (verdict == Verdict.CONFIRM && bMode != B_OPEN && ekf.ok && mirrors(t, det.runLat)) {
                log(t, "dish_returning", "cause=mirror onset_m=%+.1f jump_m=%+.1f onset_s_ago=%.0f".format(Locale.ROOT, mirrorF0, det.runLat, t - mirrorT))
                verdict = Verdict.RETURN_REF
                det.reset()
                trigSign = 0
                mirrorF0 = Double.NaN
                lastHoldEnd = t
            }
            // ---- the dish's return to the fault-free reference (port-side exit policy)
            val hmChord = if (c.returnRule && dt > 0.0 && dt <= max(2.5, c.retGapS)) headAt(0.5 * (tf + prev.tf)) else null
            val dLat = if (hmChord == null) 0.0 else (e - prev.e) * cos(hmChord.first) - (n - prev.n) * sin(hmChord.first)
            val ds = hypot(e - prev.e, n - prev.n)
            // the chords' mean sideways drift against the dish heading in trusted driving (a mount offset a few tenths
            // of a degree off, the dish heading's own bias), over the last few km
            if (hmChord != null && dt <= 2.5 && bMode == B_TIGHT && !refOn && verdict == Verdict.NONE && t - lastTrustBreak > 10.0 && 0.5 * (hs + prev.hs) > 5.0) {
                val w = kotlin.math.exp(-ds / c.retBiasHorizonM)
                chordLatSum = chordLatSum * w + dLat
                chordDist = chordDist * w + ds
            }
            if (c.returnRule && refOn && !refF0.isNaN()) {
                if (hmChord == null) {
                    endRef(t, if (dt > max(2.5, c.retGapS)) "gap" else "no_chord")
                } else {
                    val bMax = kotlin.math.tan(c.retBiasMaxDeg * D2R)
                    val bias = if (chordDist > c.retBiasMinM) (chordLatSum / chordDist).coerceIn(-bMax, bMax) else 0.0
                    refA += dLat - bias * ds
                    refD += ds
                    val off = refF0 + refA
                    val sig = refSigma()
                    val sgn = if (refF0 > 0) 1.0 else -1.0
                    // how far AUTO came back toward the fault-free track since the opening, and with this chord alone
                    val back = -refA * sgn
                    val jump = -dLat * sgn >= c.retFrac * abs(refF0)
                    // back on the reference within its σ, or (one chord jumping back) landing within retFrac of the offset
                    // from it: in turns the reference drifts more than its distance-based σ says
                    val strict = abs(off) <= max(c.retMinM, c.retK * sig)
                    val full = back >= c.retFrac * abs(refF0) && (strict || (jump && abs(off) <= c.retFrac * abs(refF0)))
                    val partial = !full && back >= c.retMinM && off * refF0 > 0
                    // the detector (or the jump itself) says AUTO moved sideways now, toward the reference
                    val toward = jump || (verdict == Verdict.CONFIRM && det.sign * sgn < 0)
                    if (ekf.ok && toward && (full || partial)) {
                        val cause = when { !full -> "reference_partial"; strict -> "reference"; else -> "reference_loose" }
                        log(t, "dish_returning", "cause=%s offset_m=%+.1f moved_m=%+.1f sigma_m=%.1f held_s=%.0f".format(Locale.ROOT,
                            cause, refF0, refA, sig, t - tOpen))
                        // full: AUTO is back on the fault-free track, take it at once; partial: on its way back, follow it (no new hold)
                        verdict = if (full) Verdict.RETURN_REF else Verdict.RETURN
                        det.reset()
                        trigSign = 0
                        if (full) {
                            lastHoldEnd = t
                            // a one-chord jump that landed short of the reference (30 m back to 14 m): AUTO is taken, but the
                            // reference stays, so a later rise is the same fault and the final drop is the return
                            if (strict) endRef(t, null) else {
                                refLoose = true
                                refHoldEnd = t
                            }
                        }
                    } else if (!refHoldEnd.isNaN() && (t - refHoldEnd > c.refMaxS || c.retK * sig > abs(refF0))) {
                        endRef(t, "expired")
                    }
                }
            }
            // ---- reversing (port-side): the AUTO chord against the car heading
            // Only chords that move at about the dish speed count (a standing car's noise and AUTO jumps do not),
            // and it takes two in a row to switch reversing on.
            // (not while the dish error is open with the leak guard: a sideways error turning with the road moves the chords)
            if (c.reverseHold && dt > 0.3 && dt <= 2.5 && !(c.leakGuard && bMode == B_OPEN)) {
                val de = e - prev.e
                val dn = n - prev.n
                val chordV = hypot(de, dn) / dt
                val hsMean = 0.5 * (hs + prev.hs)
                val psi = headAt(0.5 * (tf + prev.tf))?.first ?: if (ekf.ok) ekf.x[3] else null
                if (psi != null && minOf(hs, prev.hs) >= c.reverseMinChordSpeed && chordV >= c.reverseMinChordSpeed &&
                    abs(chordV - hsMean) <= max(1.5, 0.3 * hsMean)
                ) {
                    val off = abs(wrap(atan2(de, dn) - psi)) * R2D
                    reverseVotes = if (off > c.reverseOnDeg) reverseVotes + 1 else 0
                    if (!reversing && reverseVotes >= 2) {
                        reversing = true
                        log(t, "reversing", "state=on chord_deg=%.0f".format(Locale.ROOT, off))
                    } else if (reversing && off < c.reverseOffDeg) {
                        reversing = false
                        log(t, "reversing", "state=off chord_deg=%.0f".format(Locale.ROOT, off))
                    }
                }
            }
        }
        // ---- forward-axis epoch fit (trusted fixes only)
        val trusted = bMode == B_TIGHT && verdict == Verdict.NONE && t - lastTrustBreak > 10.0
        // reversing: the dish speed is |v| with the car going backwards, so it fits neither the forward axis nor the speed
        if (useImu && trusted && fixes.size >= 3 && !reversing) {
            for (j in fixes.size - 2 downTo 0) {
                val fj = fixes[j]
                if (tf - fj.tf >= 1.5) {
                    if (tf - fj.tf <= 3.0) imu.learn(t, fj.tf, fj.hs, tf, hs)
                    break
                }
            }
        }
        if (!ekf.ok) {
            rawPos = e to n
            rawT = t
            tryInit(t, tf, e, n, hs, prev)
            return
        }
        mount.onFix(t, fixes, heads, bMode == B_TIGHT && t - lastTrustBreak > c.mntTrustS)
        var decel = if (fHist.size >= 5) fHist.sum() / fHist.size - ekf.x[4] else ddec
        decel = minOf(decel, ddec)
        ekf.insert(Meas(tf, emptyList(), decided = false, auto = AutoFix(e, n, hs, t, verdict, decel)))
        if (decel > c.speedSkipDecel && !reversing) ekf.insert(Meas(tf - c.speedLag, listOf(Part.Speed(hs, sq(c.rSpeed)))))
        afterFix(t)
        if (holdActive) {
            if (reversing) {
                // still reversing: the held position is now this fix's (at its fix time)
                holdAnchorT = tf
                holdRate = max(c.holdAccRate, hs)
                holdAcc0 = max(acc68(ekf.p, 0.0, ekf.x), c.accFloorM)
            } else {
                // AUTO is back (or the car drives forward again): the next tick decides afresh
                holdActive = false
            }
        }
        watchdog(t)
    }

    /** A network (cell/Wi-Fi) fix at its own time [tFix], 68 % radius [acc68]; used only while dead reckoning. */
    fun onNetwork(tNow: Double, tFix: Double, e: Double, n: Double, acc68: Double) {
        if (!c.network || !ekf.ok) return
        if (!e.isFinite() || !n.isFinite() || !acc68.isFinite() || acc68 <= 0 || acc68 > c.netMaxAcc) return
        if (tNow - lastAutoRx <= c.drAfter || tFix <= lastAutoRx) return
        if (tFix < ekf.buf.first().t) return
        // σ per axis from the 68 % radius, floored: on drive 2 network fixes claiming 20-130 m were 40-180 m off.
        val sig = max(acc68 / 1.51, c.netSigMin)
        // Only a fix better than the dead reckoning helps; coarse cell fixes (drive 2: 500-600 m claimed, km off) do not.
        if (1.51 * sig >= acc68(ekf.p, 0.0, ekf.x)) return
        ekf.insert(Meas(tFix, emptyList(), decided = false, net = NetFix(e, n, sig * sig, tNow)))
        watchdog(tNow)
    }

    private fun tryInit(t: Double, tf: Double, e: Double, n: Double, hs: Double, prev: MountCalibrator.Fix?) {
        var psi: Double? = null
        var sig = 0.0
        val mv = mount.value
        if (mv != null && heads.isNotEmpty() && t - heads.last().t < 2.0) {
            psi = wrap((heads.last().hxU - mv) * D2R)
            sig = 2.0 * D2R
        } else if (prev != null && hs > 3.0) {
            val de = e - prev.e
            val dn = n - prev.n
            if (hypot(de, dn) >= 3.0) {
                psi = atan2(de, dn)
                sig = 10.0 * D2R
            }
        }
        if (psi == null) return
        val lat = t - tf
        ekf.init(t, e + hs * lat * sin(psi), n + hs * lat * cos(psi), hs, psi, sig)
        bMode = B_TIGHT
        det.reset()
        watch?.reset()
        resetSinceInit = false
        holdActive = false
        pushStopped = false
        refOn = false
        mirrorF0 = Double.NaN
        log(t, "filter_init", "heading=${if (sig < 5 * D2R) "dish" else "auto_course"}")
    }

    // ------------------------------------------------------------------ AUTO decision at the fix-time state

    private fun decide(m: Meas, x0: DoubleArray, p0: DoubleArray) {
        m.net?.let { decideNet(m, it, x0, p0); return }
        val a = m.auto ?: return
        val t = a.rx
        var x = x0
        val psi = x[3]
        val u0 = sin(psi); val u1 = cos(psi)
        val n0 = cos(psi); val n1 = -sin(psi)
        val brake = max(0.0, -a.decel - 1.0)
        var y0 = a.z0 - (x[0] + x[6])
        var y1 = a.z1 - (x[1] + x[7])
        var yAl = y0 * u0 + y1 * u1
        var yCr = y0 * n0 + y1 * n1
        val rAl = c.rAl + c.rAlBrakeK * brake
        var rCr = c.rCr
        val gAl = c.rgAl + c.rgBrakeK * brake
        var gCr = c.rgCr
        val parts = ArrayList<Part>()
        var p2 = p0
        if (a.verdict == Verdict.SUSPECT) {
            rCr = max(rCr, abs(yCr))
            gCr = max(gCr, abs(yCr))
        }
        if (a.verdict == Verdict.RETURN_REF) {
            // AUTO is back on the fault-free track: the whole dish error goes (after a long hold with turns its along-track
            // part holds what was sideways before the turn) and the position is re-anchored to AUTO
            parts += Part.ResetBAll
            p2 = Ekf.resetAll(p0, c.sigB)
            x = x.copyOf()
            x[6] = 0.0
            x[7] = 0.0
            y0 = a.z0 - x[0]
            y1 = a.z1 - x[1]
            yAl = y0 * u0 + y1 * u1
            yCr = y0 * n0 + y1 * n1
            parts += Part.Reanchor(y0 * y0, y0 * y1, y1 * y1)
            p2 = p2.copyOf().also { M.add2(it, 0, y0 * y0, y0 * y1, y1 * y1) }
            bMode = B_TIGHT
            tTrig = t
            nClose = 0
        }
        if (a.verdict == Verdict.RETURN_WALK) {
            parts += Part.ResetB
            p2 = Ekf.shrink(p0, psi, c.sigB)
            x = x.copyOf()
            val bc = x[6] * n0 + x[7] * n1
            x[6] -= bc * n0
            x[7] -= bc * n1
            y0 = a.z0 - (x[0] + x[6])
            y1 = a.z1 - (x[1] + x[7])
            yAl = y0 * u0 + y1 * u1
            yCr = y0 * n0 + y1 * n1
            val k = yCr * yCr
            parts += Part.Reanchor(k * n0 * n0, k * n0 * n1, k * n1 * n1)
            p2 = p2.copyOf().also { M.add2(it, 0, k * n0 * n0, k * n0 * n1, k * n1 * n1) }
            bMode = B_TIGHT
            tTrig = t
            nClose = 0
        }
        if (a.verdict == Verdict.CONFIRM) {
            tTrig = t
            nClose = 0
            if (bMode != B_OPEN) {
                val k = sq(c.openInitCr)
                parts += Part.OpenB(k * n0 * n0, k * n0 * n1, k * n1 * n1)
                p2 = p0.copyOf().also { M.add2(it, 6, k * n0 * n0, k * n0 * n1, k * n1 * n1) }
                bMode = B_OPEN
                val refWas = refNote()
                // the dish's offset from the track before the detector's run began (a ramp is partly followed before it fires)
                val onto = opened(t, yCr, det.runLat.takeIf { it * yCr > 0 && abs(it) > abs(yCr) } ?: yCr)
                if (!onto.isNaN()) {
                    // the same fault rising again after a loose return: the position followed AUTO off the road, so the
                    // hold starts from the reference's track (b = its offset), not from where the filter is
                    val d = onto - yCr
                    parts += Part.ShiftB(d * n0, d * n1)
                    x = x.copyOf().also { it[6] += d * n0; it[7] += d * n1; it[0] -= d * n0; it[1] -= d * n1 }
                }
                log(t, "dish_degraded", "cause=cusum cross_m=%+.1f".format(Locale.ROOT, yCr) + refWas +
                    (if (onto.isNaN()) "" else " onto_reference_m=%+.1f".format(Locale.ROOT, onto)))
            }
        }
        // NIS with the gating sigmas: S = H P Hᵀ + Rg, H = [I2 0 0 0 0 I2]
        val gAl2 = gAl * gAl
        val gCr2 = gCr * gCr
        val s00 = hph(p2, 0, 0) + gAl2 * u0 * u0 + gCr2 * n0 * n0
        val s01 = hph(p2, 0, 1) + gAl2 * u0 * u1 + gCr2 * n0 * n1
        val s10 = hph(p2, 1, 0) + gAl2 * u1 * u0 + gCr2 * n1 * n0
        val s11 = hph(p2, 1, 1) + gAl2 * u1 * u1 + gCr2 * n1 * n1
        val sol = M.solve(doubleArrayOf(s00, s01, s10, s11), doubleArrayOf(y0, y1), 2)
        val nis = if (sol == null) Double.POSITIVE_INFINITY else y0 * sol[0] + y1 * sol[1]
        val rAl2 = rAl * rAl
        val rCr2 = rCr * rCr
        var accept = true
        if (nis > c.gate) {
            nReject++
            if (recordFires) fires += t to "gate"
            lastTrustBreak = t
            if (nReject >= c.nReanchor) {
                parts += Part.Reanchor(y0 * y0, y0 * y1, y1 * y1)
                log(t, "dish_reanchor", "nis=%.0f along_m=%+.0f cross_m=%+.0f".format(Locale.ROOT, nis, yAl, yCr))
                nReject = 0
            } else if (abs(yCr) > abs(yAl) && bMode != B_OPEN && a.verdict != Verdict.RETURN_REF && mirrors(t, yCr)) {
                // the dish's return after its reference was lost (the jump mirrors that fault's onset): AUTO at once
                log(t, "dish_returning", "cause=mirror onset_m=%+.1f jump_m=%+.1f onset_s_ago=%.0f".format(Locale.ROOT, mirrorF0, yCr, t - mirrorT))
                mirrorF0 = Double.NaN
                lastHoldEnd = t
                nReject = 0
                parts += Part.ResetBAll
                p2 = Ekf.resetAll(p2, c.sigB)
                x = x.copyOf()
                x[6] = 0.0
                x[7] = 0.0
                y0 = a.z0 - x[0]
                y1 = a.z1 - x[1]
                parts += Part.Reanchor(y0 * y0, y0 * y1, y1 * y1)
                bMode = B_TIGHT
                tTrig = t
                nClose = 0
                det.reset()
                trigSign = 0
            } else {
                accept = false
                log(t, "dish_gate_reject", "nis=%.0f along_m=%+.0f cross_m=%+.0f".format(Locale.ROOT, nis, yAl, yCr))
                if (abs(yCr) > abs(yAl) && bMode != B_OPEN) {
                    val k = sq(c.openInitCr)
                    parts += Part.OpenB(k * n0 * n0, k * n0 * n1, k * n1 * n1)
                    bMode = B_OPEN
                    tTrig = t
                    nClose = 0
                    val refWas = refNote()
                    val onto = opened(t, yCr, yCr)
                    if (!onto.isNaN()) {
                        val d = onto - yCr
                        parts += Part.ShiftB(d * n0, d * n1)
                    }
                    log(t, "dish_degraded", "cause=gate cross_m=%+.1f".format(Locale.ROOT, yCr) + refWas +
                        (if (onto.isNaN()) "" else " onto_reference_m=%+.1f".format(Locale.ROOT, onto)))
                }
            }
        } else {
            nReject = 0
        }
        if (accept) parts += Part.Pos(a.z0, a.z1, rAl2 * u0 * u0 + rCr2 * n0 * n0, rAl2 * u0 * u1 + rCr2 * n0 * n1, rAl2 * u1 * u1 + rCr2 * n1 * n1)
        m.parts = parts
    }

    /** (H P Hᵀ)[i][j] for the AUTO H (position + dish error). */
    private fun hph(p: DoubleArray, i: Int, j: Int): Double =
        p[i * N_X + j] + p[i * N_X + 6 + j] + p[(6 + i) * N_X + j] + p[(6 + i) * N_X + 6 + j]

    private fun decideNet(m: Meas, f: NetFix, x: DoubleArray, p: DoubleArray) {
        val y0 = f.z0 - x[0]
        val y1 = f.z1 - x[1]
        val s = doubleArrayOf(p[0] + f.r, p[1], p[N_X], p[N_X + 1] + f.r)
        val sol = M.solve(s, doubleArrayOf(y0, y1), 2)
        val nis = if (sol == null) Double.POSITIVE_INFINITY else y0 * sol[0] + y1 * sol[1]
        if (nis > c.netGate) {
            m.parts = emptyList()
            log(f.rx, "network_reject", "nis=%.0f dist_m=%.0f sigma_m=%.0f".format(Locale.ROOT, nis, hypot(y0, y1), sqrt(f.r)))
        } else {
            m.parts = listOf(Part.Net(f.z0, f.z1, f.r))
            lastNetT = max(lastNetT, f.rx)
        }
    }

    private fun afterFix(t: Double) {
        val x = ekf.x
        val p = ekf.p
        val psi = x[3]
        val n0 = cos(psi); val n1 = -sin(psi)
        val bCr = x[6] * n0 + x[7] * n1
        if (bMode == B_OPEN) {
            if (t - tTrig >= c.minOpen && abs(bCr) < c.bClose) nClose++ else nClose = 0
            if (nClose >= c.nClose) {
                ekf.insert(Meas(ekf.headTime, listOf(Part.ShrinkB)))
                bMode = B_TIGHT
                // AUTO agrees with the held track again; if it is still away from the reference (a ramp the filter
                // followed before the detector fired), keep the reference for the return
                if (refOn) {
                    if (abs(refF0 + refA) >= max(c.retMinM, c.retK * refSigma())) refHoldEnd = t else endRef(t, null)
                }
                lastHoldEnd = t
                log(t, "dish_ok", "offset_m=%+.1f".format(Locale.ROOT, bCr))
            } else if (t - tTrig > c.holdMax && !holdSignificant(t, bCr)) {
                bMode = B_WALK
                if (refOn) refHoldEnd = t
                lastHoldEnd = t
                log(t, "dish_recovering", "offset_m=%+.1f tau_s=%.0f held_s=%.0f".format(Locale.ROOT, bCr, c.tauWalk, t - tOpen) + refNote())
            }
        } else if (bMode == B_WALK) {
            val pcr = M.quad2(p, 6, n0, n1)
            if (pcr < sq(2 * c.sigB) && abs(bCr) < c.bClose) {
                bMode = B_TIGHT
                lastHoldEnd = t
                log(t, "dish_ok", "offset_m=%+.1f".format(Locale.ROOT, bCr))
            }
        }
    }

    /** Exit policy: the hold goes on past [InertialConfig.holdMax] while the offset is significant (and below the ceiling). */
    private fun holdSignificant(t: Double, bCr: Double): Boolean {
        if (!c.holdSig || !openTrusted || t - tOpen > c.holdCeilS) return false
        if (abs(bCr) < c.holdSigK * (c.holdSig0 + c.holdSigRate * (t - tOpen))) return false
        // and the held track still agrees with the dish-heading reference about the offset (else the dead reckoning drifted)
        if (!c.returnRule) return true
        if (!refOn || refF0.isNaN()) return false
        return abs(bCr - (refF0 + refA)) <= max(c.holdConsM, c.retK * refSigma())
    }

    private fun refSigma(): Double = sqrt(sq(c.retSigM) + sq(kotlin.math.tan(c.retHeadBiasDeg * D2R) * refD))

    /**
     * A new opening (from TIGHT or WALK): a fresh reference, unless one of the same fault is still alive. Returns the
     * reference's offset when the hold should start from the reference's track (the same fault after a loose return,
     * with the position on AUTO), else NaN.
     */
    private fun opened(t: Double, yCr: Double, f0: Double): Double {
        // the same fault again (the walk back or a loose return went onto it): AUTO is still clearly off the reference on
        // the fault's side, and moved further that way
        val off = refF0 + refA
        val same = refOn && !refF0.isNaN() && off * yCr > 0 && off * refF0 > 0 && abs(off) >= max(c.retMinM, c.retK * refSigma())
        if (same) {
            // keep the reference (and the opening's time and trust), the hold is open again
            val onto = if (refLoose && abs(off) > abs(yCr)) off else Double.NaN
            refHoldEnd = Double.NaN
            refLoose = false
            return onto
        }
        tOpen = t
        openTrusted = (c.holdSigAfterHoldS <= 0.0 || t - lastHoldEnd > c.holdSigAfterHoldS) && t - trackSince >= c.holdSigTrackS
        // a new fault: an older reference no longer describes the track (and a small offset gets none)
        refOn = false
        refLoose = false
        if (!c.returnRule || abs(f0) < c.retMinM + 1.0) return Double.NaN
        refOn = true
        refF0 = f0
        refA = 0.0
        refD = 0.0
        refHoldEnd = Double.NaN
        mirrorF0 = Double.NaN
        return Double.NaN
    }

    /** The jump [jump] (cross-track, m) mirrors the onset of a fault whose reference ended without a return. */
    private fun mirrors(t: Double, jump: Double): Boolean {
        if (!c.returnRule || !c.retMirror || refOn || mirrorF0.isNaN() || t - mirrorT > c.retMirrorS) return false
        return jump * mirrorF0 < 0 && abs(abs(jump) - abs(mirrorF0)) <= c.retMirrorFrac * abs(mirrorF0)
    }

    /** For logs: the reference's offset and σ while one is alive. */
    private fun refNote(): String =
        if (refOn && !refF0.isNaN()) " reference_m=%+.1f sigma_m=%.1f".format(Locale.ROOT, refF0 + refA, refSigma()) else ""

    private fun endRef(t: Double, why: String?) {
        if (!refOn) return
        refOn = false
        refLoose = false
        if (why != null) {
            // ended without a return: remember the onset, a mirrored jump later is the return
            mirrorF0 = refF0
            mirrorT = tOpen
            log(t, "dish_reference_end", "reason=$why")
        }
    }

    // ------------------------------------------------------------------ output

    class Out(
        val e: Double,
        val n: Double,
        val state: InertialState,
        /** Reported 68 % radius with the port-side rules, m. */
        val accuracyM: Double,
        /** The filter's own 68 % radius (prototype acc68), m; NaN for the raw fix. */
        val acc68: Double,
        val along68: Double,
        val cross68: Double,
        val speed: Double,
        val psi: Double,
        val bAlong: Double,
        val bCross: Double,
        val raw: Boolean,
        /** The car is reversing (port-side): [speed] is the dish speed, the motion is against [psi]. */
        val reversing: Boolean = false,
    )

    private fun acc68(p: DoubleArray, dt: Double, x: DoubleArray): Double {
        val u0 = sin(x[3]); val u1 = cos(x[3])
        val n0 = cos(x[3]); val n1 = -sin(x[3])
        val k1 = dt * dt * p[2 * N_X + 2]
        val k2 = dt * dt * x[2] * x[2] * p[3 * N_X + 3]
        val p00 = p[0] + k1 * u0 * u0 + k2 * n0 * n0
        val p11 = p[N_X + 1] + k1 * u1 * u1 + k2 * n1 * n1
        return 1.51 * sqrt(max(0.5 * (p00 + p11), 0.0))
    }

    /** What to serve at [t]; null = nothing (state NO_OUTPUT, see [lastState]). */
    fun served(t: Double): Out? {
        if (!ekf.ok) {
            lastState = InertialState.NO_OUTPUT
            // after a watchdog reset: nothing until a fresh AUTO fix re-initialises (not even a fresh raw fix)
            if (c.noRawAfterReset && resetSinceInit) return null
            val r = rawPos ?: return null
            if (t - rawT > c.rawMaxAgeS) return null
            lastState = InertialState.TRACKING
            return Out(r.first, r.second, InertialState.TRACKING, max(c.accFloorM, 0.0), Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.0, 0.0, true)
        }
        val x = ekf.x
        val p = ekf.p
        val dt = t - ekf.headTime
        val adv = if (holdActive) 0.0 else x[2] * dt
        val e = x[0] + adv * sin(x[3])
        val n = x[1] + adv * cos(x[3])
        val u0 = sin(x[3]); val u1 = cos(x[3])
        val n0 = cos(x[3]); val n1 = -sin(x[3])
        val k1 = dt * dt * p[2 * N_X + 2]
        val k2 = dt * dt * x[2] * x[2] * p[3 * N_X + 3]
        val pp00 = p[0] + k1 * u0 * u0 + k2 * n0 * n0
        val pp01 = p[1] + k1 * u0 * u1 + k2 * n0 * n1
        val pp11 = p[N_X + 1] + k1 * u1 * u1 + k2 * n1 * n1
        val acc68 = 1.51 * sqrt(max(0.5 * (pp00 + pp11), 0.0))
        val al68 = sqrt(max(u0 * (pp00 * u0 + pp01 * u1) + u1 * (pp01 * u0 + pp11 * u1), 0.0))
        val cr68 = sqrt(max(n0 * (pp00 * n0 + pp01 * n1) + n1 * (pp01 * n0 + pp11 * n1), 0.0))
        val bCr = x[6] * n0 + x[7] * n1
        val bAl = x[6] * u0 + x[7] * u1
        var state = when {
            t - lastAutoRx > c.drAfter -> if (t - lastNetT <= c.netAidedS) InertialState.NETWORK_AIDED else InertialState.DEAD_RECKONING
            bMode == B_OPEN && abs(bCr) > 5.0 -> InertialState.SIDEWAYS_OPEN
            else -> InertialState.TRACKING
        }
        var accuracy = acc68
        if (c.accOpenOffset && bMode != B_TIGHT) accuracy = max(accuracy, abs(bCr) + sqrt(max(M.quad2(p, 6, n0, n1), 0.0)))
        if (holdActive) accuracy = max(accuracy, holdAcc0 + holdRate * (t - holdAnchorT))
        // following AUTO while the live reference says it is off the road (after a loose return, or after the walk back
        // onto a fault): at least that offset
        if (refOn && bMode == B_TIGHT && !refHoldEnd.isNaN() && abs(refF0 + refA) >= c.retMinM) {
            accuracy = max(accuracy, abs(refF0 + refA))
        }
        accuracy = max(accuracy, c.accFloorM)
        val dr = state == InertialState.DEAD_RECKONING || state == InertialState.NETWORK_AIDED
        // push stop on what would be told (the prototype's acc68, or more with the port-side rules)
        if (dr && (accuracy > c.maxAccPush || cr68 > c.maxCrossPush)) {
            if (!pushStopped) {
                pushStopped = true
                log(t, "push_stop", "acc_m=%.0f cross_m=%.0f".format(Locale.ROOT, accuracy, cr68))
            }
            state = InertialState.NO_OUTPUT
        } else if (!dr) {
            pushStopped = false
        }
        if (state != prevState) {
            log(t, "state", "from=${prevState?.name ?: "-"} to=${state.name}")
            prevState = state
        }
        lastState = state
        if (state == InertialState.NO_OUTPUT) return null
        val rev = c.reverseHold && reversing
        val speed = if (rev) fixes.lastOrNull()?.hs ?: 0.0 else x[2]
        return Out(e, n, state, accuracy, acc68, al68, cr68, speed, x[3], bAl, bCr, false, rev)
    }

    var lastState: InertialState = InertialState.NO_OUTPUT
        private set

    /** Moves every stored position by [f] (the facade re-centres its local plane). */
    fun remap(f: (Double, Double) -> Pair<Double, Double>) {
        if (ekf.ok) ekf.remap(f)
        val fs = fixes.map { fx -> f(fx.e, fx.n).let { MountCalibrator.Fix(fx.tf, it.first, it.second, fx.hs) } }
        fixes.clear()
        fixes.addAll(fs)
        lastXy = lastXy?.let { f(it.first, it.second) }
        rawPos = rawPos?.let { f(it.first, it.second) }
    }
}

/** A network fix waiting for its decision (gate) at its own time. */
internal class NetFix(var z0: Double, var z1: Double, val r: Double, val rx: Double)
