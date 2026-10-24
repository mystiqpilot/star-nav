package tools.apachi.starlinknav.service

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.DishClient

/**
 * Polls every dish call kind on its own period. Kinds the firmware does not support back off
 * to once a minute but are still logged, so a session shows exactly what the dish answered.
 */
class DishPoller(
    private val client: () -> DishClient,
    /** The kinds to poll right now: everything while recording, only what the mock needs otherwise. */
    private val wanted: () -> Set<DishCallKind>,
    private val onResult: (DishCallResult) -> Unit,
) {
    // Diagnostics runs every second for its gps_time_s, the only timestamp attached to a dish
    // fix. Location sources the firmware does not support fail and back off to once a minute.
    private val periodsMs = linkedMapOf<DishCallKind, Long>().apply {
        put(DishCallKind.STATUS, 1_000L)
        for (kind in DishCallKind.RECORDED_LOCATIONS) put(kind, 1_000L)
        put(DishCallKind.DIAGNOSTICS, 1_000L)
        put(DishCallKind.DEVICE_INFO, 60_000L)
        put(DishCallKind.GNSS_MEASUREMENT, 5_000L)
    }

    suspend fun run() {
        val next = periodsMs.keys.associateWith { 0L }.toMutableMap()
        val failures = periodsMs.keys.associateWith { 0 }.toMutableMap()
        while (currentCoroutineContext().isActive) {
            val now = SystemClock.elapsedRealtime()
            val want = wanted()
            for ((kind, period) in periodsMs) {
                if (kind !in want || next.getValue(kind) > now) continue
                val result = withContext(Dispatchers.IO) {
                    try {
                        client().call(kind)
                    } catch (e: Exception) {
                        DishCallResult.failure(kind, SystemClock.elapsedRealtimeNanos(), 0, "-", "client: ${e.message}")
                    }
                }
                onResult(result)
                if (result.httpStatus == 0) {
                    // Unreachable: retry everything later rather than time out once per kind.
                    val retry = SystemClock.elapsedRealtime() + 3_000L
                    for (k in periodsMs.keys) if (next.getValue(k) < retry) next[k] = retry
                    break
                }
                // Back off only from calls the firmware does not offer. Refusals such as
                // "disabled due to UT disablement" (moving too fast for the plan) last seconds,
                // and backing off from them once left a minute without any dish position.
                val fails = if (result.unsupported) failures.getValue(kind) + 1 else 0
                failures[kind] = fails
                next[kind] = SystemClock.elapsedRealtime() + if (fails >= 3) 60_000L else period
            }
            val wait = next.values.min() - SystemClock.elapsedRealtime()
            delay(wait.coerceIn(20L, 1_000L))
        }
    }
}
