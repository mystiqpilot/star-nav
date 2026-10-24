package tools.apachi.starlinknav.core.nav

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Mount offset (dish heading_x − car course, degrees) learned from AUTO chords while driving straight and fast
 * with a trusted AUTO (port of `MountCal`, PORTING2.md §9). A stored value is used from the start and replaced
 * only when the learned one differs by more than [InertialConfig.mntRelearnDiffDeg]. [onLearned] reports the
 * refined learned value (port-side) whenever it is more than [InertialConfig.mntPersistDiffDeg] from the last value
 * reported (or [persisted], the value the app last saved; by default the stored one), at most once per fix, so what
 * the app persists keeps improving.
 */
internal class MountCalibrator(
    private val c: InertialConfig,
    private val log: (Double, String, String) -> Unit,
    private val stored: Double?,
    persisted: Double? = stored,
) {
    private val samples = ArrayDeque<Double>()
    var value: Double? = stored
        private set
    var learned: Double? = null
        private set
    private var lastSampleT = -1e9
    val ready: Boolean get() = value != null

    /** Called with the learned value when it first exists and whenever it moved > [InertialConfig.mntPersistDiffDeg]. */
    var onLearned: (Double) -> Unit = {}
    private var reported: Double? = persisted

    class Fix(val tf: Double, val e: Double, val n: Double, val hs: Double)
    class Head(val t: Double, val hxU: Double)

    private fun robust(): Pair<Double, Double> {
        val ref = samples.first()
        val d = samples.map { wrapDeg(it - ref) }
        val med = median(d)
        val mad = median(d.map { abs(it - med) })
        return wrapDeg(ref + med) to mad
    }

    fun onFix(t: Double, fixes: List<Fix>, heads: List<Head>, trusted: Boolean) {
        if (!trusted || t - lastSampleT < 2.0 || fixes.size < 2) return
        val k = fixes.last()
        val j = fixes.asReversed().firstOrNull { k.tf - it.tf in c.mntChordMinS..c.mntChordMaxS } ?: return
        if (minOf(k.hs, j.hs) < c.mntMinSpeed) return
        val de = k.e - j.e
        val dn = k.n - j.n
        if (hypot(de, dn) < 20.0) return
        val hs = heads.filter { it.t >= j.tf - 0.5 && it.t <= k.tf + 0.5 }.map { it.hxU }
        if (hs.size < 3) return
        val d = hs.map { wrapDeg(it - hs[0]) }
        if (d.max() - d.min() > c.mntStraightDeg) return
        val course = Math.toDegrees(atan2(de, dn))
        samples.addLast(wrapDeg(hs[0] + d.average() - course))
        while (samples.size > c.mntKeep) samples.removeFirst()
        lastSampleT = t
        if (samples.size >= c.mntN) {
            val (v, mad) = robust()
            if (mad <= c.mntMadDeg) {
                val first = learned == null
                if (first) log(t, "mount_learned", "deg=%+.1f n=%d mad=%.1f".format(Locale.ROOT, v, samples.size, mad))
                learned = v
                val cur = value
                if (cur == null) {
                    value = v
                } else if (samples.size >= c.mntRelearnN && abs(wrapDeg(v - cur)) > c.mntRelearnDiffDeg) {
                    log(t, "mount_relearned", "from=%+.1f to=%+.1f".format(Locale.ROOT, cur, v))
                    value = v
                } else if (stored == null) {
                    value = v
                }
                val r = reported
                if (r == null || abs(wrapDeg(v - r)) > c.mntPersistDiffDeg) {
                    reported = v
                    onLearned(v)
                }
            }
        }
    }

    companion object {
        /** numpy median: the mean of the two middle values for an even count. */
        fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
        }
    }
}
