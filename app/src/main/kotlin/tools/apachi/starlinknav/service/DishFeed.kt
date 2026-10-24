package tools.apachi.starlinknav.service

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.apachi.starlinknav.core.dish.CallTarget
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult

/** The latest answer of one call, with when it came (wall clock and monotonic). */
class FeedSample(val result: DishCallResult, val wallMs: Long, val monoNs: Long)

/**
 * What the Dish screens show: the latest answer of each read the visible sub-tab wants, polled only while [run] is
 * collecting (the screen sets that up lifecycle-aware, so nothing is polled with the app in the background or the tab
 * left). Reads only; commands go through [CommandBus]. While the location service runs, its own latest status
 * and diagnostics are reused instead of asking the dish twice.
 *
 * Router answers are kept here unredacted (the Router sub-tab needs the real config for a write) and only in memory,
 * for as long as the feed lives (the screen is open); they are never logged or stored.
 */
class DishFeed(context: Context) {
    private val app = context.applicationContext
    private val devices = Devices(app)
    private val state = MutableStateFlow<Map<DishCallKind, FeedSample>>(emptyMap())

    val samples: StateFlow<Map<DishCallKind, FeedSample>> = state

    /** The reads [run] polls now; router reads are kept in [samples] only while one of them is in it. */
    @Volatile
    private var wantedNow: Set<DishCallKind> = emptySet()

    /**
     * Polls [wanted] (the set can change at any time) until cancelled. The unredacted router answers do not outlive it:
     * when the wanted set has no router read they are dropped here, and when this stops (the app goes to the background)
     * they are replaced by redacted copies, after every poll that could still write one has finished, not before.
     */
    suspend fun run(wanted: Flow<Set<DishCallKind>>) {
        try {
            wanted.distinctUntilChanged().collectLatest { kinds ->
                // The block of the previous set has ended (collectLatest joins it): nothing can write an old answer any more.
                wantedNow = kinds
                if (kinds.none { it.target == CallTarget.ROUTER }) forgetRouterAnswers()
                coroutineScope {
                    for (kind in kinds) launch { poll(kind) }
                }
            }
        } finally {
            wantedNow = emptySet()
            redactRouterAnswers()
        }
    }

    /**
     * Asks the device for [kind] right now (a read) and puts the answer into [samples]: used before a router config write
     * (which starts from a fresh, unredacted read), after a change, and by the speed test. Never throws.
     */
    suspend fun readNow(kind: DishCallKind): FeedSample {
        require(!kind.isCommand) { "$kind is a command: it goes through the command bus" }
        val sample = call(kind)
        keep(kind, sample)
        CapabilityStore.record(sample.result, sample.wallMs)
        return sample
    }

    /** Puts [sample] into [samples]; a router answer only while a router read is wanted (it holds the Wi-Fi password in clear). */
    private fun keep(kind: DishCallKind, sample: FeedSample) {
        if (kind.target == CallTarget.ROUTER && wantedNow.none { it.target == CallTarget.ROUTER }) return
        state.update { it + (kind to sample) }
    }

    /**
     * Drops every router answer from memory. They hold the Wi-Fi password in clear, so this runs when the Router sub-tab is
     * left (at once, from the screen, and again by [run] once the wanted set has no router read); the next visit reads them
     * again. The app going to the background redacts them instead ([redactRouterAnswers]).
     */
    fun forgetRouterAnswers() {
        state.update { samples -> samples.filterKeys { it.target != CallTarget.ROUTER } }
    }

    /**
     * Replaces every router answer by its redacted copy (no password, secret, key or unknown field): when the app goes to
     * the background. What the Router sub-tab shows stays (names, clients, settings), so an edit dialog that is open and
     * what was typed into it survive a switch to another app; a change still starts from a fresh, unredacted read, and the
     * polls bring the real answers back once the app is in front again.
     */
    fun redactRouterAnswers() {
        state.update { samples ->
            samples.mapValues { (kind, s) ->
                if (kind.target == CallTarget.ROUTER && !s.result.payloadRedacted) FeedSample(s.result.redacted(), s.wallMs, s.monoNs) else s
            }
        }
    }

    private suspend fun poll(kind: DishCallKind) {
        val period = PERIODS_MS[kind] ?: error("$kind is not polled by the feed")
        while (currentCoroutineContext().isActive) {
            val started = SystemClock.elapsedRealtime()
            val sample = borrowFromService(kind) ?: call(kind)
            keep(kind, sample)
            CapabilityStore.record(sample.result, sample.wallMs)
            // An unreachable device is asked again later, not every period (each try costs the connect timeout).
            val wait = if (sample.result.httpStatus == 0) maxOf(period, UNREACHABLE_RETRY_MS) else period
            delay((wait - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(MIN_GAP_MS))
        }
    }

    /** The service polls status, diagnostics and device info itself: its answer is used while it is fresh. */
    private fun borrowFromService(kind: DishCallKind): FeedSample? {
        if (kind !in SERVICE_KINDS || !Live.service.value.running) return null
        val s = Live.dish.value.last[kind] ?: return null
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - s.atMonoNs > BORROW_MAX_AGE_NS) return null
        return FeedSample(s.result, System.currentTimeMillis() - (now - s.atMonoNs) / 1_000_000, s.atMonoNs)
    }

    private suspend fun call(kind: DishCallKind): FeedSample = withContext(Dispatchers.IO) {
        val result = try {
            when (kind.target) {
                CallTarget.DISH -> devices.dish().call(kind)
                CallTarget.ROUTER -> devices.router()?.client?.call(kind)
                    ?: DishCallResult.failure(kind, SystemClock.elapsedRealtimeNanos(), 0, "-", AppErrors.NO_ROUTER_ADDRESS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DishCallResult.failure(kind, SystemClock.elapsedRealtimeNanos(), 0, "-", "client: ${e.message}")
        }
        FeedSample(result, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
    }

    companion object {
        /** How often each read is repeated while the sub-tab that shows it is open. */
        val PERIODS_MS: Map<DishCallKind, Long> = mapOf(
            DishCallKind.STATUS to 1_000L,
            DishCallKind.DIAGNOSTICS to 5_000L,
            DishCallKind.HISTORY to 5_000L,
            DishCallKind.OBSTRUCTION_MAP to 30_000L,
            DishCallKind.ROUTER_STATUS to 3_000L,
            DishCallKind.ROUTER_HISTORY to 10_000L,
            DishCallKind.ROUTER_CLIENTS to 5_000L,
            DishCallKind.ROUTER_CONFIG to 10_000L,
        )

        private val SERVICE_KINDS = setOf(DishCallKind.STATUS, DishCallKind.DIAGNOSTICS)
        private const val BORROW_MAX_AGE_NS = 2_500_000_000L
        private const val UNREACHABLE_RETRY_MS = 3_000L
        private const val MIN_GAP_MS = 200L
    }
}
