package tools.apachi.starlinknav.core.nav

import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.DishFix
import tools.apachi.starlinknav.core.dish.fix
import tools.apachi.starlinknav.core.log.proto.Record
import java.util.PriorityQueue
import java.util.TreeSet
import kotlin.math.max

/**
 * Runs an [InertialNav] over a recorded session the way it runs live: records in file order are re-sorted by
 * their own times (IMU samples carry their sensor timestamps and are written in 100 ms batches) within a
 * [windowNs] look-ahead, then fed in time order. With [liveOrder] the IMU samples are fed at their batch's record
 * time instead (the order the recorder saw them: the worst case of live delivery, where samples that arrive after a
 * dish call later than their tick are dropped). The filter is asked what to serve at every AUTO answer, every
 * AUTO failure, and once a second inside AUTO gaps longer than 1.5 s (the prototype's serving moments: at a + 1,
 * a + 2, ... s after the answer a while the next answer is more than 0.5 s away). Next to the filter it runs
 * "today" ([FixExtrapolator], the served position up to app 0.3) on the same answers.
 *
 * [autoMod] (tools) changes AUTO answers in the local plane: (seconds since the first dish call, east, north) →
 * the position to use, or null to drop the answer (a simulated cut-off).
 *
 * The owner's phone flag ([InertialNav.onPhoneGnssSuspect]) is fed from a [GpsTrust] run over the same queue: the
 * phone's real GPS fixes and every AUTO answer (with the filter's honest accuracy, [InertialNav.dishFloorM], so a
 * wandering Starlink-only dish does not flag a good phone), the verdict read at every AUTO call and every gap serve. It is fed from the time-ordered
 * queue, not from [add]: the queue runs up to [windowNs] behind `add`, which would exceed [GpsTrustConfig.maxSkewS]
 * and never compare.
 */
class InertialReplay(
    val nav: InertialNav,
    private val onServe: (Served) -> Unit,
    private val autoMod: ((Double, Double, Double) -> Pair<Double, Double>?)? = null,
    private val windowNs: Long = 5_000_000_000L,
    /** Feed the phone's network fixes (provider "network", not mocked). */
    private val network: Boolean = nav.config.network,
    private val liveOrder: Boolean = false,
    /** The GPS check that feeds the phone flag; null = the flag is never fed. */
    private val trust: GpsTrust? = GpsTrust(),
) {
    enum class Kind { AUTO, FAIL, GAP }

    /** One serving moment: the filter's answer (null = nothing served) and today's position (null before any fix). */
    class Served(
        val kind: Kind,
        val tNs: Long,
        val estimate: InertialEstimate?,
        val todayLat: Double?,
        val todayLon: Double?,
        /** The AUTO answer as fed (after [autoMod]); null for failures and gap serves. */
        val fix: DishFix?,
    )

    private class Input(val t: Long, val order: Int, val seq: Long, val call: DishCallResult? = null, val wallMs: Long = 0,
                        val sensor: Int = 0, val values: List<Float>? = null, val sampleNs: Long = 0,
                        val netLat: Double = 0.0, val netLon: Double = 0.0, val netAcc: Double = 0.0, val netFixNs: Long = 0,
                        /** A real phone GPS fix for the GPS check (lat / lon / accuracy in the net fields). */
                        val gps: Boolean = false, val gpsMock: Boolean = false)

    private val queue = PriorityQueue<Input>(compareBy<Input>({ it.t }, { it.order }, { it.seq }))
    private val answers = TreeSet<Long>()
    private var seq = 0L
    private var maxSeen = Long.MIN_VALUE
    private var lastAnswer: Long? = null
    private var nextServe = 1L
    private var finishing = false
    val today = FixExtrapolator()
    private var todayLat: Double? = null
    private var todayLon: Double? = null

    fun add(rec: Record) {
        val t = rec.t_mono_ns
        // the receiver clock (fix ages) as the app feeds it live: every real GPS fix and NMEA sentence as it came
        rec.phone_location?.let { p ->
            if (p.provider == "gps" && !p.is_mock && p.time_ms > 0 && p.elapsed_ns > 0) nav.clock.onGpsFix(p.time_ms, p.elapsed_ns, rec.t_wall_ms, t)
        }
        rec.nmea?.let { nav.clock.onNmea(it.sentence, rec.t_wall_ms) }
        rec.dish?.let { d ->
            val call = DishCallResult.fromRecord(d) ?: return@let
            val order = when (call.kind) {
                DishCallKind.DIAGNOSTICS -> 1
                DishCallKind.STATUS -> 2
                DishCallKind.LOCATION_AUTO -> if (isAnswer(call)) 3 else 4
                else -> 1
            }
            if (call.kind == DishCallKind.LOCATION_AUTO && isAnswer(call)) answers += t
            queue += Input(t, order, seq++, call = call, wallMs = rec.t_wall_ms)
        }
        rec.imu?.samples?.forEach { s ->
            if (s.sensor_type in IMU_TYPES) {
                // live order: at the batch's record time (after the dish calls recorded before it), in batch order
                queue += Input(if (liveOrder) t else s.t_ns, if (liveOrder) 7 else 0, seq++, sensor = s.sensor_type, values = s.v, sampleNs = s.t_ns)
            }
        }
        rec.phone_location?.let { p ->
            val acc = p.h_acc
            if (network && p.provider == "network" && !p.is_mock && acc != null && p.lat.isFinite() && p.lon.isFinite()) {
                queue += Input(t, 6, seq++, netLat = p.lat, netLon = p.lon, netAcc = acc.toDouble(), netFixNs = if (p.elapsed_ns > 0) p.elapsed_ns else t)
            }
            // the phone's real GPS for the GPS check, at the fix's own time
            if (trust != null && p.provider == "gps" && !p.is_mock && p.lat.isFinite() && p.lon.isFinite()) {
                queue += Input(
                    if (p.elapsed_ns > 0) p.elapsed_ns else t, 1, seq++,
                    netLat = p.lat, netLon = p.lon, netAcc = acc?.toDouble() ?: Double.NaN, gps = true, gpsMock = p.is_mock,
                )
            }
        }
        if (t != 0L && t > maxSeen) maxSeen = t
        drain(maxSeen - windowNs)
    }

    fun addAll(records: Sequence<Record>) {
        for (r in records) add(r)
    }

    fun finish() {
        finishing = true
        drain(Long.MAX_VALUE)
    }

    private fun isAnswer(call: DishCallResult) = call.ok && call.response?.fix() != null

    private fun drain(limit: Long) {
        while (true) {
            val head = queue.peek() ?: break
            if (head.t > limit) break
            queue.poll()
            gapServes(head.t)
            process(head)
        }
    }

    /** Serves at a + k s before an input at [t] (Python order: serve after everything at the same time). */
    private fun gapServes(t: Long) {
        val a = lastAnswer ?: return
        while (true) {
            val s = a + nextServe * 1_000_000_000L
            if (s >= t) return
            val b = answers.ceiling(a + 1)
            if (b != null && b - s <= 500_000_000L) {
                nextServe = Long.MAX_VALUE / 2_000_000_000L
                return
            }
            if (b == null && finishing) return
            phoneFlag(s)
            onServe(Served(Kind.GAP, s, nav.serve(s), todayLat, todayLon, null))
            nextServe++
        }
    }

    private fun process(i: Input) {
        val call = i.call
        if (call != null) {
            if (call.kind == DishCallKind.DIAGNOSTICS) {
                call.response?.dish_get_diagnostics?.location?.gps_time_s?.let { nav.clock.fixAgeS(it, i.wallMs) }?.let(today::onFixAge)
            }
            if (call.kind != DishCallKind.LOCATION_AUTO) {
                nav.onDishCall(call, i.t, i.wallMs)
                return
            }
            val answer = isAnswer(call)
            if (answer) {
                answers.remove(i.t)
                lastAnswer = i.t
                nextServe = 1
            }
            val f = if (answer) call.response?.fix() else null
            if (f == null || f.looksLikePlaceholder) {
                val est = nav.onDishCall(call, i.t, i.wallMs)
                phoneFlag(i.t)
                onServe(Served(Kind.FAIL, i.t, est, todayLat, todayLon, null))
                return
            }
            val mod = autoMod
            if (mod == null) {
                val est = nav.onDishCall(call, i.t, i.wallMs)
                autoSeen(f, i.t)
                phoneFlag(i.t)
                today(f, i.t)
                onServe(Served(Kind.AUTO, i.t, est, todayLat, todayLon, f))
                return
            }
            nav.ensureOrigin(f.lat, f.lon)
            val (e, n) = nav.localOf(f.lat, f.lon)
            val r = mod(nav.rel(i.t), e, n)
            if (r == null) {
                val est = nav.onAutoFailure(i.t)
                phoneFlag(i.t)
                onServe(Served(Kind.FAIL, i.t, est, todayLat, todayLon, null))
                return
            }
            val est = nav.onAutoLocal(i.t, r.first, r.second, f.horizontalSpeedMps)
            val (la, lo) = nav.geoOf(r.first, r.second)
            val moved = f.copy(lat = la, lon = lo)
            autoSeen(moved, i.t)
            phoneFlag(i.t)
            today(moved, i.t)
            onServe(Served(Kind.AUTO, i.t, est, todayLat, todayLon, moved))
            return
        }
        val v = i.values
        if (v != null) {
            nav.onImuSample(i.sensor, i.sampleNs, v)
            return
        }
        if (i.gps) {
            trust?.onGpsFix(i.netLat, i.netLon, i.netAcc, i.t, i.gpsMock)
            return
        }
        nav.onNetworkFix(i.netFixNs, i.netLat, i.netLon, i.netAcc, i.t)
    }

    /** An AUTO answer with a position (repeats included) for the GPS check, at its fix time with the honest accuracy. */
    private fun autoSeen(f: DishFix, tNs: Long) {
        trust?.onAutoFix(f.lat, f.lon, max(f.sigmaM, nav.dishFloorM), f.horizontalSpeedMps, tNs - (nav.latencyS * 1e9).toLong())
    }

    private fun phoneFlag(tNs: Long) {
        val tr = trust ?: return
        nav.onPhoneGnssSuspect(tNs, tr.verdict(tNs).state == GpsTrustState.DISTRUSTED)
    }

    private companion object {
        val IMU_TYPES = setOf(InertialNav.TYPE_ACCEL_UNCAL, InertialNav.TYPE_GYRO_UNCAL, InertialNav.TYPE_ACCEL, InertialNav.TYPE_GYRO)
    }

    private fun today(f: DishFix, t: Long) {
        val s = today.serve(f, t)
        todayLat = s.lat
        todayLon = s.lon
    }
}
