package tools.apachi.starlinknav.service

import android.location.Location
import android.os.SystemClock
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.DishFix
import tools.apachi.starlinknav.core.dish.fix
import tools.apachi.starlinknav.core.dish.readableName
import tools.apachi.starlinknav.core.nav.InertialConfig
import tools.apachi.starlinknav.core.nav.InertialEstimate
import tools.apachi.starlinknav.core.nav.InertialEvent
import tools.apachi.starlinknav.core.nav.InertialNav
import tools.apachi.starlinknav.core.nav.ReceiverClock
import tools.apachi.starlinknav.core.nav.InertialState
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Runs the inertial filter ([InertialNav], decision 2026-10-15) for the service. One thread ("inertial") owns it,
 * because it is not thread-safe: the dish poller's results, every accelerometer/gyroscope event from the sensor
 * thread and the phone's network fixes from the location thread are handed to it in arrival order, and a 250 ms
 * tick asks it what to serve once a second while no AUTO call comes (its clock must keep up with the IMU even when
 * the dish is unreachable).
 *
 * What is served goes to [onServe] while [serving] says so (mock on, Auto, correction on): at every LOCATION_AUTO
 * call (answer or failure) and every second between them. [Serve.fix] is set only when the serve is based on an
 * AUTO answer with a position: only those count as dish pushes for the network fallback, so the fallback still
 * releases Android's network provider after 5 s without a dish fix and the filter gets the network fixes it uses
 * while dead reckoning. When the filter has nothing to serve, apps still get what they would without it: the
 * poller's plain push for an AUTO answer with a position ([dish]'s `plain`), a released network fix as it came
 * ([network]'s `unused`).
 *
 * The filter is fed every dish call while the service runs, also with the mock off or in another mode, so it is
 * warm (and has learned the mount) when Auto serves it; the IMU and network fixes only while it serves. All
 * callbacks run on the inertial thread.
 */
class InertialRunner(
    /** The service's receiver clock (fix ages against GNSS time), shared by every filter made here. */
    private val clock: ReceiverClock,
    /** The mount offset to start from (Settings' effective value), read whenever a filter is made. */
    private val startMount: () -> StartMount,
    private val serving: () -> Boolean,
    private val onServe: (Serve) -> Unit,
    private val onEvent: (InertialEvent) -> Unit,
    private val onMountLearned: (Double) -> Unit,
    /** A failure inside the filter, at most once per [ERROR_EVERY_NS] ([suppressed] = how many were not reported). */
    private val onError: (error: Throwable, suppressed: Int) -> Unit,
) {
    class Serve(
        val estimate: InertialEstimate,
        /** The AUTO answer the serve is based on; null for serves between answers (dead reckoning, AUTO failures). */
        val fix: DishFix?,
        val nowNs: Long,
    )

    /**
     * The mount offset a new filter starts from: [deg] (null = none), entered by hand ([manual]) or learned on an
     * earlier drive and saved at [savedAtMs]; [persisted] = the learned value saved last (what a new one is compared to).
     */
    data class StartMount(val deg: Double?, val manual: Boolean, val savedAtMs: Long, val persisted: Double?)

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "inertial") }
    /** Guards [stopped] and every write of [Live.inertial]: a publish already past its check cannot undo [shutdown]'s reset. */
    private val publishLock = Any()
    private var stopped = false

    // Everything below is touched only on [exec].
    private lateinit var nav: InertialNav
    private var start = StartMount(null, false, 0, null)
    private var lastEvalNs = 0L
    private var lastAutoNs = 0L
    private var lastFixNs = 0L
    private var lastPublishNs = 0L
    private var state = InertialState.NO_OUTPUT
    private var stateSinceNs = 0L
    private var lastEstimate: InertialEstimate? = null
    // Counts since the service started; a new filter (mount change) adds its own to those of the old ones.
    private var openings = 0
    private var gateRejects = 0
    private var deadReckoningS = 0.0
    private var suspectsBefore = 0
    private var resetsBefore = 0
    private var errors = 0
    private var lastError: String? = null
    private var lastErrorReportNs = 0L
    private var errorsUnreported = 0

    init {
        post { newFilter() }
        exec.scheduleWithFixedDelay({ guarded { tick() } }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    }

    private fun newFilter() {
        if (::nav.isInitialized) {
            suspectsBefore += nav.watchSuspects
            resetsBefore += nav.resets
        }
        start = startMount()
        nav = InertialNav(
            InertialConfig.SHIPPED, start.deg,
            onMountLearned = onMountLearned,
            onEvent = { e ->
                when (e.name) {
                    "dish_degraded" -> openings++
                    "dish_gate_reject" -> gateRejects++
                }
                onEvent(e)
            },
            persistedMountDeg = start.persisted,
            clock = clock,
        )
        lastEvalNs = 0L
        lastAutoNs = 0L
        lastFixNs = 0L
        state = InertialState.NO_OUTPUT
        stateSinceNs = SystemClock.elapsedRealtimeNanos()
        lastEstimate = null
        publish(SystemClock.elapsedRealtimeNanos())
    }

    /** A new filter (the mount setting changed): starts again from the next dish calls. */
    fun reset() = post { newFilter() }

    /**
     * Every dish call result, at its receipt ([tNs] elapsedRealtime, [wallMs] wall clock). [plain] pushes what apps
     * get without the correction (an AUTO answer with a position); it runs when the filter serves but has nothing.
     */
    fun dish(call: DishCallResult, tNs: Long, wallMs: Long, plain: (() -> Unit)? = null) = post {
        val est = nav.onDishCall(call, tNs, wallMs)
        if (call.kind == DishCallKind.LOCATION_AUTO) {
            lastAutoNs = tNs
            val fix = if (call.ok) call.response?.fix()?.takeIf { !it.looksLikePlaceholder } else null
            if (fix != null) lastFixNs = tNs
            evaluated(tNs, est, fix, plain)
        }
    }

    /** One accelerometer or gyroscope event as the sensor thread got it. */
    fun imu(sensorType: Int, tNs: Long, values: List<Float>) = post { nav.onImuSample(sensorType, tNs, values) }

    /**
     * A real (not mocked) network-provider fix; the filter uses it only while dead reckoning. While it serves, what
     * to serve is asked at once: the fix may have improved it, and when there is nothing [unused] gets the fix (apps
     * then get it as they would without the correction).
     */
    fun network(loc: Location, nowNs: Long, unused: (Location) -> Unit) {
        if (!loc.hasAccuracy() || loc.elapsedRealtimeNanos <= 0) return
        val fixNs = loc.elapsedRealtimeNanos
        val lat = loc.latitude
        val lon = loc.longitude
        val acc = loc.accuracy.toDouble()
        post {
            nav.onNetworkFix(fixNs, lat, lon, acc, nowNs)
            if (serving()) evaluated(nowNs, nav.serve(nowNs), null) { unused(loc) }
        }
    }

    fun shutdown() {
        exec.shutdownNow()
        synchronized(publishLock) {
            stopped = true
            Live.inertial.value = InertialUi()
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtimeNanos()
        // Once a second without an AUTO call (the poller asks every second, every 3 s while the dish is unreachable).
        if (now - lastEvalNs >= SERVE_EVERY_NS && now - lastAutoNs >= AUTO_SILENT_NS) evaluated(now, nav.serve(now), null)
        if (now - lastPublishNs >= PUBLISH_EVERY_NS) publish(now)
    }

    /** [none] runs when the filter has nothing to serve while it serves apps. */
    private fun evaluated(nowNs: Long, est: InertialEstimate?, fix: DishFix?, none: (() -> Unit)? = null) {
        val s = nav.state
        if (lastEvalNs != 0L && (s == InertialState.DEAD_RECKONING || s == InertialState.NETWORK_AIDED)) {
            deadReckoningS += (nowNs - lastEvalNs).coerceAtLeast(0L) / 1e9
        }
        lastEvalNs = nowNs
        lastEstimate = est
        val changed = s != state
        if (changed) {
            state = s
            stateSinceNs = nowNs
        }
        if (serving()) {
            if (est != null) onServe(Serve(est, fix, nowNs)) else none?.invoke()
        }
        if (changed) publish(nowNs)
    }

    private fun publish(now: Long) {
        if (exec.isShutdown) return
        lastPublishNs = now
        val e = lastEstimate?.takeIf { state != InertialState.NO_OUTPUT }
        val deg = nav.mountOffsetDeg
        val source = when {
            deg == null -> MountSource.NONE
            deg == start.deg -> if (start.manual) MountSource.MANUAL else MountSource.SAVED
            else -> MountSource.LEARNED_NOW
        }
        val ui = InertialUi(
            running = true, serving = serving(), state = state, accuracyM = e?.accuracyM, raw = e?.raw == true,
            sidewaysM = e?.takeIf { !it.raw }?.sidewaysOffsetM, imu = nav.imuState, forwardAxisReady = nav.forwardAxisReady,
            mountDeg = deg, mountSource = source, mountSavedAtMs = if (source == MountSource.SAVED) start.savedAtMs else 0L,
            sinceNs = stateSinceNs, lastFixNs = lastFixNs, sidewaysOpenings = openings, watchSuspects = suspectsBefore + nav.watchSuspects,
            gateRejects = gateRejects, resets = resetsBefore + nav.resets, deadReckoningS = deadReckoningS,
            errors = errors, lastError = lastError, atNs = now,
        )
        // A task that was running when the service stopped must not bring back a stale state.
        synchronized(publishLock) { if (!stopped) Live.inertial.value = ui }
    }

    private fun post(block: () -> Unit) {
        try {
            exec.execute { guarded(block) }
        } catch (_: RejectedExecutionException) {
            // shut down: the service is stopping
        }
    }

    /**
     * A failure must not end the tick (a scheduled task that throws is never run again) or lose later inputs, and
     * one that repeats (the IMU path runs ~200 times a second) must not flood the session log.
     */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            errors++
            lastError = (e.message ?: e.readableName()).take(200)
            val now = SystemClock.elapsedRealtimeNanos()
            if (lastErrorReportNs == 0L || now - lastErrorReportNs >= ERROR_EVERY_NS) {
                lastErrorReportNs = now
                val suppressed = errorsUnreported
                errorsUnreported = 0
                runCatching { onError(e, suppressed) }
                runCatching { if (::nav.isInitialized) publish(now) }
            } else {
                errorsUnreported++
            }
        }
    }

    private companion object {
        const val TICK_MS = 250L
        const val SERVE_EVERY_NS = 1_000_000_000L
        /** AUTO calls normally come every second; a little more than that without one is a gap. */
        const val AUTO_SILENT_NS = 1_250_000_000L
        const val PUBLISH_EVERY_NS = 500_000_000L
        const val ERROR_EVERY_NS = 10_000_000_000L
    }
}
