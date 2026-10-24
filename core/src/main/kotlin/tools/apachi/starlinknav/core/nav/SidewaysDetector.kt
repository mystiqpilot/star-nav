package tools.apachi.starlinknav.core.nav

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Sideways detector from dish data only (port of `SideDet`, PORTING2.md §5): a two-sided CUSUM of the AUTO
 * chord's sideways velocity against the dish heading at the chord middle, with an adaptive σ. The first
 * exceedance is SUSPECT (that fix is de-weighted sideways), the second consecutive one CONFIRM. The filter runs
 * two: the acting one (h = [InertialConfig.cusumH]) and a watch-only one ([InertialConfig.watchCusumH]) that only
 * logs what it would have done.
 */
internal class SidewaysDetector(private val c: InertialConfig, private val hRef: Double) {
    private var sp = 0.0
    private var sm = 0.0
    private var sigv2 = sq(c.sigvInit)
    private var prevExceed = false
    var sign = 0
        private set
    /** max(S+, S−) / h of the last step, for logs. */
    var lastRatio = 0.0
        private set
    var lastVLat = 0.0
        private set
    var lastH = 0.0
        private set
    /** AUTO's sideways displacement (Σ vLat·dt) since S+ / S− last left zero: how far the dish moved in the current run. */
    private var latP = 0.0
    private var latM = 0.0
    /** At a CONFIRM: the displacement of the run that fired (the dish's sideways move since it began), m. */
    var runLat = 0.0
        private set

    fun reset() {
        sp = 0.0
        sm = 0.0
        latP = 0.0
        latM = 0.0
        prevExceed = false
    }

    fun step(dt: Double, vLat: Double, healthBad: Boolean, learn: Boolean): Verdict {
        val sig = min(max(sqrt(sigv2), c.sigvMin), c.sigvMax)
        val s = sig / c.sigvRef
        val k = c.cusumK * s
        var h = hRef * s
        if (healthBad) h *= c.healthH
        sp = max(0.0, sp + (vLat - k) * dt)
        sm = max(0.0, sm + (-vLat - k) * dt)
        latP = if (sp > 0) latP + vLat * dt else 0.0
        latM = if (sm > 0) latM + vLat * dt else 0.0
        var exceed = sp > h || sm > h
        lastRatio = max(sp, sm) / h
        var res = Verdict.NONE
        sign = 0
        if (exceed && prevExceed) {
            res = Verdict.CONFIRM
            sign = if (sp > sm) 1 else -1
            runLat = if (sp > sm) latP else latM
            sp = 0.0
            sm = 0.0
            latP = 0.0
            latM = 0.0
            exceed = false
        } else if (exceed) {
            res = Verdict.SUSPECT
        }
        prevExceed = exceed
        if (learn && res == Verdict.NONE && abs(vLat) < 4 * sig) {
            val a = 1.0 / c.sigvTauN
            sigv2 = (1 - a) * sigv2 + a * vLat * vLat
        }
        lastVLat = vLat
        lastH = h
        return res
    }
}
