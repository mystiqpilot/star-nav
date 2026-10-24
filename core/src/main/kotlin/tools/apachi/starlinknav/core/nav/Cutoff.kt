package tools.apachi.starlinknav.core.nav

import kotlinx.serialization.Serializable
import tools.apachi.starlinknav.core.dish.Disablement
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.fix

/** A stretch in which the dish refused GetLocation because it was disabled. Times are recorder-clock ns. */
@Serializable
data class Cutoff(
    val startNs: Long,
    /** Null while it lasts. */
    val endNs: Long? = null,
    /** UtDisablementCode from dish status (14 = MOVING_TOO_FAST_FOR_POLICY); 0 until a status names it. */
    val code: Int = 0,
    /** Dish AUTO speed just before (last fix within 5 s), m/s. */
    val speedBeforeMps: Double? = null,
    /** Highest dish AUTO speed in the 60 s before, m/s. */
    val maxSpeed60sMps: Double? = null,
    /** Dish AUTO speed of the first fix after it (within 5 s of the end), m/s. */
    val speedAfterMps: Double? = null,
) {
    fun durationS(nowNs: Long): Double = ((endNs ?: nowNs) - startNs) / 1e9
}

/**
 * Follows the dish's UT disablement from the calls the recorder makes anyway. While disabled
 * (on the user's plan: MOVING_TOO_FAST_FOR_POLICY, twice for ~30 s after sustained 75-81 km/h on
 * 2026-10-14) the dish refuses GetLocation for every source with PERMISSION_DENIED "disabled
 * due to UT disablement" but keeps answering status. Starts on whichever comes first, the
 * refusal or a status with a disablement code; ends on the first location fix or an OKAY
 * status. Fed in time order, live in the app and over recorded sessions alike. [onChange] gets
 * every change: a start (endNs null), the code named by a later status, a reopening right after
 * an end, the end, and the speed after it.
 */
class CutoffTracker(private val onChange: (Cutoff) -> Unit = {}) {
    var active: Cutoff? = null
        private set
    private val done = ArrayList<Cutoff>()
    val finished: List<Cutoff> get() = done
    private val speeds = ArrayDeque<Pair<Long, Double>>()
    private var lastStatusCode = Disablement.OKAY

    fun onCall(result: DishCallResult, atNs: Long) {
        val kind = result.kind
        if (kind == DishCallKind.STATUS) {
            val status = result.response?.dish_get_status ?: return
            val code = status.disablement_code
            lastStatusCode = code
            when {
                Disablement.isDisabled(code) -> start(atNs, code)
                code == Disablement.OKAY -> end(atNs)
            }
            return
        }
        if (kind.locationSource == null || kind.isSensitive) return
        if (result.refusedByDisablement) {
            start(atNs, if (Disablement.isDisabled(lastStatusCode)) lastStatusCode else 0)
            return
        }
        if (!result.ok) return
        val fix = result.response?.fix()?.takeIf { !it.looksLikePlaceholder } ?: return
        if (kind == DishCallKind.LOCATION_AUTO) onSpeed(atNs, fix.horizontalSpeedMps)
        end(atNs)
    }

    /** The cut-offs so far, the running one last with endNs = null. */
    fun all(): List<Cutoff> = done + listOfNotNull(active)

    private fun onSpeed(atNs: Long, speedMps: Double) {
        speeds.addLast(atNs to speedMps)
        while (speeds.isNotEmpty() && atNs - speeds.first().first > SPEED_WINDOW_NS) speeds.removeFirst()
        val last = done.lastOrNull() ?: return
        val end = last.endNs ?: return
        if (last.speedAfterMps == null && active == null && atNs - end <= NEAR_NS) {
            done[done.lastIndex] = last.copy(speedAfterMps = speedMps)
            onChange(done.last())
        }
    }

    private fun start(atNs: Long, code: Int) {
        val current = active
        if (current != null) {
            if (code != 0 && code != current.code) {
                active = current.copy(code = code)
                onChange(active!!)
            }
            return
        }
        // A refusal right after a fix came back is the same cut-off flapping, not a new one.
        val previous = done.lastOrNull()
        if (previous?.endNs != null && atNs - previous.endNs <= MERGE_NS) {
            done.removeAt(done.lastIndex)
            active = previous.copy(endNs = null, speedAfterMps = null, code = if (code != 0) code else previous.code)
            onChange(active!!)
            return
        }
        val before = speeds.lastOrNull()?.takeIf { atNs - it.first <= NEAR_NS }?.second
        val max60 = speeds.filter { atNs - it.first <= 60_000_000_000L }.maxOfOrNull { it.second }
        active = Cutoff(startNs = atNs, code = code, speedBeforeMps = before, maxSpeed60sMps = max60)
        onChange(active!!)
    }

    private fun end(atNs: Long) {
        val current = active ?: return
        val ended = current.copy(endNs = atNs)
        active = null
        done += ended
        onChange(ended)
    }

    companion object {
        private const val SPEED_WINDOW_NS = 120_000_000_000L
        private const val NEAR_NS = 5_000_000_000L
        private const val MERGE_NS = 5_000_000_000L
    }
}
