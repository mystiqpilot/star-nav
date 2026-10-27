package tools.apachi.starlinknav.core.nav

import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.attitude
import tools.apachi.starlinknav.core.dish.fix
import tools.apachi.starlinknav.core.log.proto.AppEvent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong

/** What the filter serves at one moment (positions WGS84; offsets in the car frame). */
data class InertialEstimate(
    /** elapsedRealtime of the moment served, ns. */
    val tNs: Long,
    val lat: Double,
    val lon: Double,
    /** 68 % radius (Location.accuracy), m. */
    val accuracyM: Double,
    /** Forward speed, m/s; null for the raw fix before the filter started. */
    val speedMps: Double?,
    /** Car heading, degrees clockwise from north; null for the raw fix. */
    val bearingDeg: Double?,
    val state: InertialState,
    /** The dish's estimated error to the right of the car (AUTO = true + offset), m. */
    val sidewaysOffsetM: Double,
    val alongOffsetM: Double,
    val imu: ImuState,
    /** Dish yaw − car heading in use (stored or learned), degrees; null while unknown. */
    val mountOffsetDeg: Double?,
    /** Position in the filter's local plane (metres from its origin, the first AUTO fix until re-centred). */
    val eastM: Double,
    val northM: Double,
    /** The filter's own 68 % radius before the port-side floors (the prototype's acc68), m; NaN for the raw fix. */
    val filterAcc68M: Double,
    /** The cross-track 1σ of the served position, m. */
    val cross68M: Double,
    /** The start-up raw AUTO fix (the filter has not started). */
    val raw: Boolean = false,
    /** The car is reversing: [bearingDeg] is the direction of motion (car heading + 180°). */
    val reversing: Boolean = false,
    /** What the dish's position is made from (its GNSS flags, the owner's phone flag, the status age). */
    val dishRegime: DishGnssRegime = DishGnssRegime.UNKNOWN,
    /** The Starlink-only / suspect floor in force in [accuracyM], m (0 = none). */
    val dishFloorM: Double = 0.0,
    /** The dish is refused by the stall detector (frozen, two-point, stale clock): dead reckoning until it moves again. */
    val stalled: Boolean = false,
)

/** One `inertial` session event. [detail] is `name key=value ...`, relative quantities only. */
data class InertialEvent(val tNs: Long, val name: String, val detail: String) {
    fun toAppEvent(): AppEvent = AppEvent(kind = KIND, detail = if (detail.isEmpty()) name else "$name $detail")

    companion object {
        const val KIND = "inertial"
    }
}

/**
 * The inertial filter for Auto mode: dead reckoning
 * through dish outages on the phone IMU and the dish heading, and the sideways correction when AUTO drifts off to
 * the side of the road. Feed it, in arrival order on the elapsedRealtime clock, every dish call result, every IMU
 * sample (uncalibrated accelerometer and gyroscope with their bias values, as logged in `ImuBatch`) and the
 * phone's network fixes; [onDishCall] returns what to serve at each AUTO answer or failure, [serve] what to serve
 * in between (every second while dead reckoning). The same code runs live and over recorded sessions
 * ([InertialReplay]).
 *
 * Time: everything is converted to seconds since the first dish call; IMU samples are averaged over 0.1 s ticks
 * on that clock ((t − 0.1, t]). Only dish calls, network fixes and [serve] move the clock and run the ticks up to
 * their time; IMU samples only fill the bins, and a sample more than [InertialConfig.imuMaxAheadS] ahead of the
 * clock (a bogus timestamp) is dropped, as is one arriving after its tick ran ([imuLateSamples]).
 *
 * Wiring (live), the rules the replay cannot check:
 * - Not thread-safe: the dish poller, the sensor handler thread and the location callbacks must hand their inputs
 *   to one thread (or one lock) in arrival order.
 * - Call [serve] (or deliver dish calls) at least once a second, so the clock keeps up with the IMU.
 * - Feed IMU samples per event from `onSensorChanged` (as delivered by the sensor HAL), not from the recorder's
 *   100 ms batches: a sample delivered after a dish call later than its tick is dropped (the batch order is the
 *   worst case).
 * - Network fixes reach the app only while the network fallback has released Android's network provider (5 s
 *   without a dish push). The filter's 1 Hz dead-reckoning pushes must not count as dish pushes for that, or use
 *   the passive provider, or the network input never arrives (replays of mock-on sessions overstate it).
 */
class InertialNav(
    val config: InertialConfig = InertialConfig.SHIPPED,
    storedMountDeg: Double? = null,
    useImu: Boolean = true,
    /**
     * The mount offset was learned, or the learned value moved more than [InertialConfig.mntPersistDiffDeg] since the
     * last call (or from the stored value): persist it. [learnedMountDeg] at the end of a session is the most refined.
     */
    private val onMountLearned: (Double) -> Unit = {},
    private val onEvent: (InertialEvent) -> Unit = {},
    /**
     * The learned value the app saved last, which [onMountLearned] compares against (default [storedMountDeg]). The
     * app passes it separately when [storedMountDeg] is a manual value, so a learned one close to it is still saved.
     */
    persistedMountDeg: Double? = storedMountDeg,
    /** Keep [fires] (desktop tools). Off by default: the app's filter runs for hours and would only grow the list. */
    recordFires: Boolean = false,
    /** The phone's wall clock against GNSS time, fed by the owner (real GPS fixes, NMEA): the diagnostics fix age needs it. */
    val clock: ReceiverClock = ReceiverClock(),
) {
    private var originNs: Long? = null
    /** The owner's latest phone flag; one given before the first dish call (no origin yet) is applied with it. */
    private var phoneSuspect = false
    private var lat0 = Double.NaN
    private var lon0 = Double.NaN
    private var cosLat0 = Double.NaN
    private val core: InertialCore = InertialCore(config, storedMountDeg, useImu, persistedMountDeg, recordFires) { t, kind, msg ->
        onEvent(InertialEvent(nsOf(t), kind, msg))
    }
    private var started = false
    private var nextK = 0L
    /** The latest dish call, network fix or serve, ns (the clock IMU samples are checked against). */
    private var clockNs = Long.MIN_VALUE
    private val bins = HashMap<Long, Bin>()
    private var seenUncalAcc = false
    private var seenUncalGyr = false
    private val early = ArrayDeque<Triple<Long, Int, List<Float>>>()
    private var lastSigma = Double.NaN
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    init {
        core.mount.onLearned = onMountLearned
    }

    private class Bin {
        val acc = DoubleArray(3)
        var nAcc = 0
        val gyr = DoubleArray(3)
        var nGyr = 0
    }

    val state: InertialState get() = core.lastState
    val mountOffsetDeg: Double? get() = core.mount.value
    val learnedMountDeg: Double? get() = core.mount.learned
    val imuState: ImuState get() = core.imuState
    /** The phone's forward axis is learned for the current mount epoch (forward accel in use when the IMU is). */
    val forwardAxisReady: Boolean get() = core.imu.faReady
    val resets: Int get() = core.nResets
    val watchSuspects: Int get() = core.watchConfirms
    /** (t since the first dish call s, "det" | "gate") of acting-detector confirms and gate rejects; empty unless `recordFires`. */
    val fires: List<Pair<Double, String>> get() = core.fires
    /** The fix latency in use, s. */
    val latencyS: Double get() = core.latencyS
    /** The dish's GNSS regime as reported now (for the runner and the replay's GpsTrust). */
    val dishRegime: DishGnssRegime get() = core.dishRegime
    /** The Starlink-only / suspect / unconverged floor in force now, m (0 = none). */
    val dishFloorM: Double get() = core.dishFloorM
    /** The dish is refused by the stall detector now. */
    val stalled: Boolean get() = core.stalled
    /** IMU samples dropped because their tick had already run (live delivery later than a dish call). */
    var imuLateSamples = 0L
        private set
    /** IMU samples dropped because their timestamp was more than [InertialConfig.imuMaxAheadS] ahead of the clock. */
    var imuAheadSamples = 0L
        private set
    internal val binCount: Int get() = bins.size

    /** Seconds since the first dish call (the filter's clock). */
    fun rel(tNs: Long): Double = ((tNs - (originNs ?: tNs)).toDouble()) / 1e9

    private fun nsOf(t: Double): Long = (originNs ?: 0L) + (t * 1e9).roundToLong()

    // ------------------------------------------------------------------ projection

    val hasOrigin: Boolean get() = !lat0.isNaN()

    /** Sets the local plane's origin (the first AUTO fix) unless it is set. */
    fun ensureOrigin(lat: Double, lon: Double) {
        if (hasOrigin) return
        lat0 = lat
        lon0 = lon
        cosLat0 = cos(Math.toRadians(lat0))
    }

    /** WGS84 → local east/north metres (equirectangular around the origin, as the prototype). */
    fun localOf(lat: Double, lon: Double): Pair<Double, Double> =
        (lon - lon0) * cosLat0 * Math.PI / 180 * R_E to (lat - lat0) * Math.PI / 180 * R_E

    fun geoOf(e: Double, n: Double): Pair<Double, Double> =
        lat0 + n / R_E * 180 / Math.PI to lon0 + e / (R_E * cosLat0) * 180 / Math.PI

    private fun maybeRebase() {
        if (!core.ekf.ok) return
        val e = core.ekf.x[0]
        val n = core.ekf.x[1]
        if (abs(e) <= config.rebaseM && abs(n) <= config.rebaseM) return
        val (latN, lonN) = geoOf(e, n)
        val oLat = lat0; val oLon = lon0; val oCos = cosLat0
        val nCos = cos(Math.toRadians(latN))
        core.remap { ee, nn ->
            val la = oLat + nn / R_E * 180 / Math.PI
            val lo = oLon + ee / (R_E * oCos) * 180 / Math.PI
            (lo - lonN) * nCos * Math.PI / 180 * R_E to (la - latN) * Math.PI / 180 * R_E
        }
        lat0 = latN
        lon0 = lonN
        cosLat0 = nCos
    }

    // ------------------------------------------------------------------ clock and ticks

    /** Moves the clock to [tNs]; before the ticks start, drops the bins the start would drop anyway. */
    private fun clockTo(tNs: Long) {
        if (tNs <= clockNs) return
        clockNs = tNs
        if (!started && originNs != null && bins.isNotEmpty()) {
            val k0 = 10L * ceil(max(0.0, rel(clockNs))).toLong()
            bins.keys.removeAll { it < k0 }
        }
    }

    private fun setOrigin(tNs: Long) {
        clockTo(tNs)
        if (originNs != null) return
        originNs = tNs
        if (phoneSuspect) core.onPhoneFlag(0.0, true)
        val pending = early.toList()
        early.clear()
        for ((t, type, v) in pending) onImuSample(type, t, v)
    }

    private fun startTicks(t: Double) {
        if (started) return
        started = true
        nextK = 10L * ceil(max(0.0, t)).toLong()
        bins.keys.removeAll { it < nextK }
    }

    private fun advance(t: Double, inclusive: Boolean) {
        if (!started) return
        while (true) {
            val tk = nextK / 10.0
            if (!(tk < t || (inclusive && tk <= t))) break
            val b = bins.remove(nextK)
            val acc = b?.takeIf { it.nAcc > 0 }?.let { bb -> DoubleArray(3) { bb.acc[it] / bb.nAcc } }
            val gyr = b?.takeIf { it.nGyr > 0 }?.let { bb -> DoubleArray(3) { bb.gyr[it] / bb.nGyr } }
            core.onTick(tk, acc, gyr)
            nextK++
        }
    }

    /**
     * One IMU sample (SensorEvent: type, timestamp, values). The uncalibrated accelerometer (35) and gyroscope (16)
     * are used with their bias estimates (values[3..5]) removed; on phones without them the calibrated ones (1, 4),
     * which are the same with the bias already removed (ignored once an uncalibrated sample of that kind arrived).
     */
    fun onImuSample(sensorType: Int, tNs: Long, values: List<Float>) {
        val accel = when (sensorType) {
            TYPE_ACCEL_UNCAL -> { seenUncalAcc = true; true }
            TYPE_GYRO_UNCAL -> { seenUncalGyr = true; false }
            TYPE_ACCEL -> if (seenUncalAcc) return else true
            TYPE_GYRO -> if (seenUncalGyr) return else false
            else -> return
        }
        if (originNs == null) {
            early.addLast(Triple(tNs, sensorType, values))
            while (early.size > 1 && early.last().first - early.first().first > 300_000_000L) early.removeFirst()
            return
        }
        if (values.size < 3) return
        if (tNs - clockNs > (config.imuMaxAheadS * 1e9).toLong()) {
            imuAheadSamples++
            return
        }
        val t = rel(tNs)
        // the first tick at or after t (Python: searchsorted(tk, t, 'left'), then t > tk − 0.1)
        var k = ceil(t * 10).toLong()
        while ((k - 1) / 10.0 >= t) k--
        while (k / 10.0 < t) k++
        if (!(t > k / 10.0 - 0.1)) return
        if (started && k < nextK) {
            imuLateSamples++
            return
        }
        if (!started && k < 10L * ceil(max(0.0, rel(clockNs))).toLong()) return // the start of the ticks drops it anyway
        val b = bins.getOrPut(k) { Bin() }
        val bias = values.size >= 6 && (sensorType == TYPE_ACCEL_UNCAL || sensorType == TYPE_GYRO_UNCAL)
        if (accel) {
            for (i in 0..2) b.acc[i] += f2d(values[i]) - if (bias) f2d(values[3 + i]) else 0.0
            b.nAcc++
        } else {
            for (i in 0..2) b.gyr[i] += f2d(values[i]) - if (bias) f2d(values[3 + i]) else 0.0
            b.nGyr++
        }
    }

    // ------------------------------------------------------------------ dish

    /**
     * A dish call result received at [tNs] (elapsedRealtime) and [wallMs] (wall clock, for the diagnostics fix age).
     * Returns what to serve for a LOCATION_AUTO call (answer or failure); null otherwise or when there is nothing
     * to serve ([state] says why).
     */
    fun onDishCall(call: DishCallResult, tNs: Long, wallMs: Long): InertialEstimate? {
        setOrigin(tNs)
        val resp = call.response
        when (call.kind) {
            DishCallKind.STATUS -> {
                val s = resp?.dish_get_status ?: return null
                val t = rel(tNs)
                startTicks(t)
                advance(t, inclusive = true)
                val a = s.alignment_stats
                val g = s.gps_stats
                core.onStatus(
                    t, s.ned2dish_quaternion?.attitude()?.headingDeg, a?.attitude_estimation_state, a?.attitude_uncertainty_deg?.let(::f2d),
                    g?.gps_valid, g?.gps_sats,
                    inhibit = g?.inhibit_gps ?: false, pnt = g?.pnt_filter_convergence_state,
                    outage = s.outage?.let { OutageInfo(it.cause, it.start_timestamp_ns, it.duration_ns, it.did_switch) },
                )
                return null
            }
            DishCallKind.DIAGNOSTICS -> {
                // every diagnostics answer: the fix age (receiver clock, else the raw wall clock, null while the fix time
                // is withheld), the fix time for the slot clock and the stale-clock rule, the receipt on the GPS time line
                val d = resp?.dish_get_diagnostics ?: return null
                val gps = d.location?.gps_time_s ?: 0.0
                val t = rel(tNs)
                advance(t, inclusive = true)
                val fromClock = clock.fixAgeS(gps, wallMs)
                val ageS = fromClock ?: FixExtrapolator.fixAgeS(gps, wallMs)
                val wallGpsS = (clock.gnssMs(wallMs) ?: wallMs.toDouble()) / 1000.0 - GPS_EPOCH_UNIX_S + GPS_LEAP_S
                core.onDiag(t, ageS, gps, wallGpsS, fromReceiverClock = fromClock != null)
                return null
            }
            DishCallKind.LOCATION_AUTO -> {
                val f = if (call.ok) resp?.fix() else null
                if (f != null && !f.looksLikePlaceholder) {
                    ensureOrigin(f.lat, f.lon)
                    lastSigma = f.sigmaM
                    if (f.lat == lastLat && f.lon == lastLon) {
                        // the dish's solution seen again (it computes one every ~1.08 s, the app asks every second):
                        // compared as received, so a re-centred plane cannot turn it into a new fix
                        val t = rel(tNs)
                        advance(t, inclusive = true)
                        core.onAutoSeenAgain(t, f.horizontalSpeedMps)
                        return serveAt(t, tNs)
                    }
                    if (f.horizontalSpeedMps.isFinite() && f.horizontalSpeedMps in 0.0..90.0) {
                        lastLat = f.lat
                        lastLon = f.lon
                    }
                    val (e, n) = localOf(f.lat, f.lon)
                    return onAutoLocal(tNs, e, n, f.horizontalSpeedMps)
                }
                // an answer without a position counts as a failure (the filter's clock and state still move on)
                return onAutoFailure(tNs)
            }
            else -> return null
        }
    }

    /** An AUTO answer already in the local plane (the replay's fault injection works there). */
    fun onAutoLocal(tNs: Long, e: Double, n: Double, hs: Double): InertialEstimate? {
        setOrigin(tNs)
        val t = rel(tNs)
        startTicks(t)
        advance(t, inclusive = true)
        core.onAuto(t, e, n, hs)
        maybeRebase()
        return serveAt(t, tNs)
    }

    /** An AUTO call that gave no position (refused in a cut-off, unreachable, ...). */
    fun onAutoFailure(tNs: Long): InertialEstimate? {
        setOrigin(tNs)
        val t = rel(tNs)
        advance(t, inclusive = true)
        return serveAt(t, tNs)
    }

    /**
     * A phone network-provider fix (not mocked): [fixNs] its elapsedRealtimeNanos, [accuracyM] its 68 % radius,
     * [nowNs] when it arrived. Used only while dead reckoning.
     */
    fun onNetworkFix(fixNs: Long, lat: Double, lon: Double, accuracyM: Double, nowNs: Long) {
        if (originNs == null || !hasOrigin) return
        clockTo(nowNs)
        val t = rel(nowNs)
        advance(t, inclusive = true)
        val (e, n) = localOf(lat, lon)
        core.onNetwork(t, rel(fixNs), e, n, accuracyM)
    }

    /**
     * The owner's flag at [tNs]: the phone's own GNSS is distrusted (a level; the latest value counts). Only the
     * reported regime (SUSPECT while the dish's GNSS is valid) and the told accuracy change, never the filter. Fed by
     * the app's service (its GPS check's verdict, every 0.5 s), [InertialReplay] and tests. Before the first dish call
     * there is no time origin: the latest value is kept and applied with that call (at relative time 0), so no event is
     * stamped before it.
     */
    fun onPhoneGnssSuspect(tNs: Long, suspect: Boolean) {
        phoneSuspect = suspect
        if (originNs != null) core.onPhoneFlag(rel(tNs), suspect)
    }

    /** What to serve at [tNs] between AUTO answers (every second while dead reckoning). */
    fun serve(tNs: Long): InertialEstimate? {
        if (originNs == null) return null
        clockTo(tNs)
        val t = rel(tNs)
        advance(t, inclusive = true)
        return serveAt(t, tNs)
    }

    private fun serveAt(t: Double, tNs: Long): InertialEstimate? {
        val o = core.served(t) ?: return null
        if (!hasOrigin) return null
        val (lat, lon) = geoOf(o.e, o.n)
        return InertialEstimate(
            tNs = tNs, lat = lat, lon = lon,
            accuracyM = if (o.raw) max(o.accuracyM, if (lastSigma.isFinite()) lastSigma else 0.0) else o.accuracyM,
            speedMps = o.speed.takeIf { !o.raw },
            bearingDeg = if (o.raw) null else ((Math.toDegrees(o.psi) + if (o.reversing) 180.0 else 0.0) % 360.0 + 360.0) % 360.0,
            state = o.state, sidewaysOffsetM = o.bCross, alongOffsetM = o.bAlong, imu = core.imuState,
            mountOffsetDeg = core.mount.value, eastM = o.e, northM = o.n, filterAcc68M = o.acc68, cross68M = o.cross68, raw = o.raw,
            reversing = o.reversing, dishRegime = o.dishRegime, dishFloorM = o.dishFloorM, stalled = o.stalled,
        )
    }

    companion object {
        const val TYPE_ACCEL_UNCAL = 35
        const val TYPE_GYRO_UNCAL = 16
        const val TYPE_ACCEL = 1
        const val TYPE_GYRO = 4
        /** The radius of the prototype's (and slnav csv's) local plane. */
        private const val R_E = 6371008.8
        /** Unix seconds at the GPS epoch and GPS − UTC since 2017 (as [FixExtrapolator]): the receipt on the GPS time line. */
        private const val GPS_EPOCH_UNIX_S = 315_964_800.0
        private const val GPS_LEAP_S = 18.0

        /** A float as printed (what `slnav csv` and the prototype read), so replays see the same numbers. */
        internal fun f2d(x: Float): Double = if (x.isFinite()) x.toString().toDouble() else x.toDouble()
    }
}
