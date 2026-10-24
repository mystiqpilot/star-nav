package tools.apachi.starlinknav.core.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MountCalibratorTest {

    /**
     * A straight drive at [speed] m/s on course [course] with AUTO fixes once a second, each off by Gaussian noise of
     * [noiseM] per axis (a dish without GNSS), and the dish heading = course + [mount] (+0.2° noise). Returns the time
     * the calibrator first had a value and that value, or null.
     */
    private fun learn(config: InertialConfig, noiseM: Double, mount: Double = -90.0, course: Double = 40.0, speed: Double = 12.0, tEnd: Int = 300): Pair<Int, Double>? {
        val cal = MountCalibrator(config, { _, _, _ -> }, stored = null)
        val rnd = Random(11)
        fun gauss() = rnd.nextDouble(-1.0, 1.0) + rnd.nextDouble(-1.0, 1.0) + rnd.nextDouble(-1.0, 1.0)
        val fixes = ArrayDeque<MountCalibrator.Fix>()
        val heads = ArrayDeque<MountCalibrator.Head>()
        val c = Math.toRadians(course)
        for (k in 1..tEnd) {
            val d = speed * k
            heads.addLast(MountCalibrator.Head(k - 0.3, wrapDeg(course + mount + 0.2 * gauss())))
            while (heads.size > 20) heads.removeFirst()
            fixes.addLast(MountCalibrator.Fix(k.toDouble(), d * sin(c) + noiseM * gauss(), d * cos(c) + noiseM * gauss(), speed))
            while (fixes.size > 40) fixes.removeFirst()
            cal.onFix(k + 0.45, fixes.toList(), heads.toList(), trusted = true)
            cal.value?.let { return k to it }
        }
        return null
    }

    @Test
    fun aCleanAutoIsLearnedQuicklyAsBefore() {
        val shipped = assertNotNull(learn(InertialConfig.SHIPPED, noiseM = 0.3))
        val proto = assertNotNull(learn(InertialConfig.CFG2, noiseM = 0.3))
        assertTrue(abs(wrapDeg(shipped.second + 90.0)) < 0.5, "$shipped")
        assertTrue(abs(wrapDeg(proto.second + 90.0)) < 0.5, "$proto")
        assertTrue(shipped.first <= proto.first + 5, "shipped $shipped, prototype $proto")
    }

    @Test
    fun aNoisyAutoWithoutGnssIsStillLearned() {
        // ~1.7 m of noise per axis: 3-6 s chords give courses a few degrees apart (MAD > 1.5°, the prototype never takes
        // a value); the longest straight chord and the precision rule get within a degree
        assertNull(learn(InertialConfig.CFG2, noiseM = 1.7))
        val shipped = assertNotNull(learn(InertialConfig.SHIPPED, noiseM = 1.7))
        assertTrue(abs(wrapDeg(shipped.second + 90.0)) < 1.0, "$shipped")
    }

    @Test
    fun aHeadingThatWandersIsNotTaken() {
        // a dish heading off the course by ±10° at random: the MAD stays above the cap, nothing is learned
        val cal = MountCalibrator(InertialConfig.SHIPPED, { _, _, _ -> }, stored = null)
        val rnd = Random(3)
        val fixes = ArrayDeque<MountCalibrator.Fix>()
        for (k in 1..300) {
            fixes.addLast(MountCalibrator.Fix(k.toDouble(), 0.0, 12.0 * k, 12.0))
            while (fixes.size > 40) fixes.removeFirst()
            val h = wrapDeg(-90.0 + rnd.nextDouble(-10.0, 10.0))
            // straight within 3° over each chord (the rule's own test), but a different offset every few seconds
            val heads = (0 until 20).map { MountCalibrator.Head(k - 19.0 + it, h) }
            cal.onFix(k + 0.45, fixes.toList(), heads, trusted = true)
        }
        assertNull(cal.value)
    }
}
