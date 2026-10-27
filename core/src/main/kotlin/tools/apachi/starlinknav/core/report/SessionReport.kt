package tools.apachi.starlinknav.core.report

import kotlinx.serialization.Serializable
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.DishFix
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.core.dish.fix
import tools.apachi.starlinknav.core.log.Sessions
import tools.apachi.starlinknav.core.log.Slog
import tools.apachi.starlinknav.core.log.SlogReader
import tools.apachi.starlinknav.core.log.TrackPoint
import tools.apachi.starlinknav.core.log.proto.Record
import tools.apachi.starlinknav.core.nav.CutoffTracker
import tools.apachi.starlinknav.core.nav.DishReboot
import tools.apachi.starlinknav.core.nav.RebootTracker
import tools.apachi.starlinknav.core.nav.FixExtrapolator
import tools.apachi.starlinknav.core.nav.ReceiverClock
import tools.apachi.starlinknav.core.nav.InertialConfig
import tools.apachi.starlinknav.core.nav.InertialEstimate
import tools.apachi.starlinknav.core.nav.InertialEvent
import tools.apachi.starlinknav.core.nav.InertialNav
import tools.apachi.starlinknav.core.nav.InertialReplay
import tools.apachi.starlinknav.core.nav.InertialState
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** One dish AUTO answer. Times are recorder-clock ns (receipt). */
@Serializable
data class ReportPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    /** Dish horizontal speed, m/s. */
    val v: Float,
    /** Dish-reported sigma, m. */
    val sigma: Float,
    /**
     * Distance between the position the app serves for this fix (moved forward by its age, see
     * FixExtrapolator) and the phone's own GNSS fix at the same moment, m. Null when the phone
     * had no usable real fix then (mock on, no fix yet, poor accuracy).
     */
    val err: Float? = null,
    /** The same for the position the inertial filter serves at this moment (null when it served nothing). */
    val ferr: Float? = null,
)

/** A real (not mocked) phone GNSS fix, at its own fix time. */
@Serializable
data class RefPoint(val t: Long, val lat: Double, val lon: Double, val acc: Float? = null, val v: Float? = null)

@Serializable
data class ReportSpan(val kind: String, val fromNs: Long, val toNs: Long) {
    val durationS: Double get() = (toNs - fromNs) / 1e9

    companion object {
        /** Phone GNSS AGC below 0 dB: strong in-band power (jamming on the 2026-10-14 drives). */
        const val INTERFERENCE = "interference"
        /** Phone GNSS and dish more than [AccuracyClasses.CONFLICT_M] apart: one of them is spoofed or broken. */
        const val CONFLICT = "conflict"
        /** Dish GNSS invalid or under [SessionReports.DISH_SATS_LOW] satellites. */
        const val DISH_GNSS = "dish_gnss"
        const val MOCK = "mock"
        const val UNREACHABLE = "unreachable"
        /** From the last dish answer before a restart to the first after it (not counted as unreachable). */
        const val REBOOT = "reboot"
        /** The dish gave no position and apps got Android's network (cell/Wi-Fi) position instead. */
        const val FALLBACK = "fallback"
        /** The inertial filter held AUTO to be off to the side (its dish-error state was open). */
        const val SIDEWAYS_OPEN = "sideways_open"
        /** The inertial filter dead-reckoned (no AUTO fix for more than 2.5 s), network-aided or not, push stops included. */
        const val DEAD_RECKONING = "dead_reckoning"
    }
}

@Serializable
data class CutoffEntry(
    val startNs: Long,
    val endNs: Long,
    /** UtDisablementCode (14 = MOVING_TOO_FAST_FOR_POLICY), 0 = not named by a status. */
    val code: Int,
    /** Still on when the recording ended. */
    val open: Boolean = false,
    val speedBeforeMps: Double? = null,
    val maxSpeed60sMps: Double? = null,
    val speedAfterMps: Double? = null,
    /** Phone GNSS speed at the start and the end, when the phone had real fixes, m/s. */
    val phoneSpeedStartMps: Double? = null,
    val phoneSpeedEndMps: Double? = null,
    /** Distance driven during it: the phone's track, or the chord between the dish fixes around it, m. */
    val distanceM: Double? = null,
) {
    val durationS: Double get() = (endNs - startNs) / 1e9
}

/** For one location source while the mock was on: how often it handed apps the pushed position. */
@Serializable
data class DeliveryStats(
    val received: Int,
    /** Mocked and within [SessionReports.DELIVERY_OK_M] of the push nearest in time. */
    val followed: Int,
    /** Fixes that were not mocked at all (the source bypassed the mock). */
    val notMock: Int,
    val maxOffsetM: Double? = null,
)

@Serializable
data class ReportSummary(
    val durationS: Double,
    /** Seconds the dish said it moved (>= [SessionReports.MOVING_MPS]) plus the cut-offs. */
    val movingS: Double,
    val distanceM: Double,
    val maxSpeedMps: Double? = null,
    val dishFixes: Int,
    /** Moving seconds per accuracy class (see [AccuracyClasses]). */
    val classS: List<Double>,
    /** Moving seconds with a dish fix but no usable phone reference. */
    val noReferenceS: Double,
    /** Moving seconds where the phone's GNSS and the dish were more than 1 km apart (not in the classes). */
    val conflictS: Double = 0.0,
    val cutoffS: Double,
    /** Served-position error while moving, m. */
    val errP50M: Double? = null,
    val errP90M: Double? = null,
    val errP95M: Double? = null,
    val errMaxM: Double? = null,
    /** The same without moving fixes by their age (what app 0.1.3 and earlier served), m. */
    val rawP50M: Double? = null,
    val rawP95M: Double? = null,
    /** Median age of dish fixes at receipt (diagnostics), s. */
    val latencyS: Double? = null,
    val sigmaP50M: Double? = null,
    /** The reference's own reported accuracy, m. */
    val phoneAccP50M: Double? = null,
    val mockOnS: Double = 0.0,
    /** Seconds the network fallback served apps (from app 0.3.0 on). */
    val fallbackS: Double = 0.0,
    val interferenceS: Double = 0.0,
    val dishGnssDegradedS: Double = 0.0,
    /** By source (gps, network, fused, gms_fused), from app 0.2.0 on. */
    val delivery: Map<String, DeliveryStats> = emptyMap(),
    /** The inertial filter replayed over the session (what Auto serves with the correction on). */
    val inertial: InertialSummary? = null,
) {
    val referencedMovingS: Double get() = classS.sum()
}

/** The inertial filter over the session (report version 6 on). Errors only when the phone had real fixes. */
@Serializable
data class InertialSummary(
    /** Seconds dead-reckoned (no AUTO fix for more than 2.5 s), including seconds with nothing pushed. */
    val deadReckonedS: Double,
    /** Of those, seconds with nothing pushed (the dead reckoning too uncertain). */
    val pushStoppedS: Double = 0.0,
    /** Times the dish-error state opened (AUTO held to be off to the side). */
    val sidewaysOpenings: Int = 0,
    /** Seconds the served position was held off AUTO sideways (SIDEWAYS_OPEN). */
    val sidewaysOpenS: Double = 0.0,
    /** Largest sideways offset held, m. */
    val maxSidewaysM: Double? = null,
    /** What the watch-only (more sensitive) detector would have opened, for tuning. */
    val watchSuspects: Int = 0,
    /** Gate rejects of AUTO fixes. */
    val gateRejects: Int = 0,
    val events: Int = 0,
    /** Mount offset in use at the end (stored or learned), degrees. */
    val mountOffsetDeg: Double? = null,
    /** Served-position error while moving at the same AUTO moments as [ReportSummary.errP50M], m. */
    val errP50M: Double? = null,
    val errP95M: Double? = null,
    val errMaxM: Double? = null,
    /** Today's (FixExtrapolator) error at exactly the moments the filter served, for a fair comparison, m. */
    val todayP50M: Double? = null,
    val todayP95M: Double? = null,
    /** Error while dead reckoning (1 Hz serves in AUTO gaps), m. */
    val drErrP50M: Double? = null,
    val drErrP95M: Double? = null,
    /** Share of the dead-reckoning serves whose error was within the accuracy told to apps. */
    val drWithinAccuracy: Double? = null,
)

@Serializable
data class SessionReport(
    val version: Int = SessionReports.VERSION,
    val sessionId: String,
    /** Size of the log the report was built from; a different size means a stale report. */
    val bytes: Long,
    val startNs: Long,
    val endNs: Long,
    val startWallMs: Long,
    val appVersion: String? = null,
    val points: List<ReportPoint>,
    val reference: List<RefPoint>,
    val cutoffs: List<CutoffEntry>,
    val spans: List<ReportSpan>,
    val summary: ReportSummary,
    /** Dish restarts during the session. */
    val reboots: List<DishReboot> = emptyList(),
    /** When the dish had booted, per the first status of the session (recorder clock). */
    val dishBootNs: Long? = null,
)

/**
 * Error classes for colouring tracks: < 5, 5-10, 10-20, 20-50, >= 50 m. A distance of a kilometre
 * or more is no accuracy figure but a conflict: one receiver is spoofed (or the session is a test).
 */
object AccuracyClasses {
    const val CONFLICT_M = 1000.0

    fun isConflict(errM: Double): Boolean = errM >= CONFLICT_M

    /** Upper bounds of all classes but the last, m. The phone reference itself is good to ~3-5 m. */
    val UPPER_M = listOf(5.0, 10.0, 20.0, 50.0)
    val LABELS = listOf("< 5 m", "5–10 m", "10–20 m", "20–50 m", "≥ 50 m")
    val COUNT = LABELS.size

    fun of(errM: Double): Int = UPPER_M.indexOfFirst { errM < it }.let { if (it < 0) UPPER_M.size else it }
}

object SessionReports {
    /** 7 (2026-10-27): the filter's Starlink-only regime (numbers and lanes change on such stretches); cached reports are rebuilt. */
    const val VERSION = 7
    const val MOVING_MPS = 2.0
    /** The dish saw 16-19 satellites on normal drives and 3-8 under interference. */
    const val DISH_SATS_LOW = 10
    const val DELIVERY_OK_M = 10.0
    /** Sources whose fixes are compared with the mock's pushes. */
    val DELIVERY_SOURCES = listOf("gps", "network", "fused", "gms_fused")
    private const val MAX_POINTS = 20_000
    private const val REF_MAX_GAP_NS = 2_500_000_000L
    private const val REF_MAX_ACC_M = 25f

    fun fileFor(log: File) = File(log.parentFile, log.name.removeSuffix(".${Slog.EXTENSION}") + ".report.json")

    fun read(log: File): SessionReport? = fileFor(log).takeIf { it.isFile }?.let {
        runCatching { Sessions.json.decodeFromString(SessionReport.serializer(), it.readText()) }.getOrNull()
    }?.takeIf { it.version == VERSION && it.bytes == log.length() }

    fun write(log: File, report: SessionReport) {
        val tmp = File(log.parentFile, fileFor(log).name + ".tmp")
        tmp.writeText(Sessions.json.encodeToString(SessionReport.serializer(), report))
        tmp.renameTo(fileFor(log))
    }

    fun loadOrBuild(log: File): SessionReport = read(log) ?: build(log).also { runCatching { write(log, it) } }

    fun build(log: File): SessionReport =
        SlogReader.open(log).use { build(it.records(), log.length(), log.name.removeSuffix(".${Slog.EXTENSION}")) }

    fun build(records: Sequence<Record>, bytes: Long = 0, fallbackId: String = ""): SessionReport {
        val b = Builder()
        for (r in records) b.add(r)
        return b.finish(bytes, fallbackId)
    }

    private class AutoFix(val t: Long, val fix: DishFix, val servedLat: Double, val servedLon: Double)

    private class Received(val source: String, val t: Long, val lat: Double, val lon: Double, val mock: Boolean)

    private class Builder {
        var startNs = Long.MAX_VALUE
        var endNs = Long.MIN_VALUE
        var startWall = Long.MAX_VALUE
        var id: String? = null
        var appVersion: String? = null
        val cutoffs = CutoffTracker()
        val reboots = RebootTracker()
        val served = FixExtrapolator()
        /** The phone's wall clock against GNSS time, from the recorded GPS fixes and NMEA (the fix ages need it). */
        val clock = ReceiverClock()
        val ages = ArrayList<Double>()
        val auto = ArrayList<AutoFix>()
        val phone = ArrayList<RefPoint>()
        val dishGnss = ArrayList<Pair<Long, Boolean>>()
        val agc = ArrayList<Pair<Long, Boolean>>()
        val unreachable = ArrayList<Long>()
        val mockEvents = ArrayList<Pair<Long, Boolean>>()
        val fallbackEvents = ArrayList<Pair<Long, Boolean>>()
        /** Pushed positions (time, lat, lon) and what sources handed back (source, time, lat, lon, mocked). */
        val pushes = ArrayList<Triple<Long, Double, Double>>()
        val received = ArrayList<Received>()
        // The inertial filter, started on the first record after the header (which may carry a mount offset).
        var storedMount: Double? = null
        var inertial: InertialReplay? = null
        val inertialAt = HashMap<Long, InertialEstimate?>()
        /** Every serving moment of the filter: (t, state, estimate). */
        val inertialServes = ArrayList<Triple<Long, InertialState, InertialEstimate?>>()
        val inertialEvents = ArrayList<InertialEvent>()

        private fun inertial(): InertialReplay = inertial ?: InertialReplay(
            InertialNav(InertialConfig.SHIPPED, storedMount, onEvent = { inertialEvents += it }),
            onServe = { s ->
                if (s.kind == InertialReplay.Kind.AUTO) inertialAt[s.tNs] = s.estimate
                inertialServes += Triple(s.tNs, s.estimate?.state ?: inertial!!.nav.state, s.estimate)
            },
        ).also { inertial = it }

        fun add(rec: Record) {
            rec.header_?.let { h ->
                if (h.session_id.isNotEmpty()) id = h.session_id
                appVersion = h.app_version.ifEmpty { null }
                storedMount = mountFrom(h.config_json)
                return
            }
            inertial().add(rec)
            val t = rec.t_mono_ns
            if (t != 0L) {
                startNs = min(startNs, t)
                endNs = max(endNs, t)
            }
            if (rec.t_wall_ms != 0L) startWall = min(startWall, rec.t_wall_ms)
            rec.dish?.let { d -> onDish(DishCallResult.fromRecord(d) ?: return@let, t, rec.t_wall_ms) }
            rec.nmea?.let { clock.onNmea(it.sentence, rec.t_wall_ms) }
            rec.phone_location?.let { p ->
                if (p.provider == "gps" && !p.is_mock && p.time_ms > 0 && p.elapsed_ns > 0) clock.onGpsFix(p.time_ms, p.elapsed_ns, rec.t_wall_ms, t)
                if (p.provider == "gps" && !p.is_mock && p.lat.isFinite() && p.lon.isFinite()) {
                    phone += RefPoint(if (p.elapsed_ns > 0) p.elapsed_ns else t, p.lat, p.lon, p.h_acc, p.speed)
                }
                if (p.provider in DELIVERY_SOURCES) received += Received(p.provider, if (p.elapsed_ns > 0) p.elapsed_ns else t, p.lat, p.lon, p.is_mock)
            }
            rec.mock_output?.let { m -> if (m.error.isEmpty()) pushes += Triple(t, m.lat, m.lon) }
            rec.gnss_measurements?.let { m -> if (m.agc.isNotEmpty()) agc += t to (m.agc.minOf { it.level_db } < 0.0) }
            rec.event?.let { e ->
                if (e.kind == "mock") mockEvents += t to e.detail.startsWith("on")
                if (e.kind == "fallback") fallbackEvents += t to e.detail.startsWith("start")
            }
        }

        private fun onDish(call: DishCallResult, t: Long, wallMs: Long) {
            cutoffs.onCall(call, t)
            reboots.onCall(call, t)
            if (call.httpStatus == 0) unreachable += t
            if (!call.ok) return
            val resp = call.response ?: return
            when (call.kind) {
                DishCallKind.DIAGNOSTICS -> {
                    val gpsTime = resp.dish_get_diagnostics?.location?.gps_time_s ?: return
                    val age = clock.fixAgeS(gpsTime, wallMs) ?: return
                    if (age in 0.0..3.0) ages += age
                    served.onFixAge(age)
                }
                DishCallKind.STATUS -> resp.dish_get_status?.gps_stats?.let { g ->
                    dishGnss += t to (!g.gps_valid || g.gps_sats < DISH_SATS_LOW)
                }
                DishCallKind.LOCATION_AUTO -> {
                    val f = resp.fix()?.takeIf { !it.looksLikePlaceholder } ?: return
                    val s = served.serve(f, t)
                    auto += AutoFix(t, f, s.lat, s.lon)
                }
                else -> Unit
            }
        }

        fun finish(bytes: Long, fallbackId: String): SessionReport {
            inertial?.finish()
            if (startNs == Long.MAX_VALUE) startNs = 0
            if (endNs == Long.MIN_VALUE) endNs = startNs
            if (startWall == Long.MAX_VALUE) startWall = 0
            phone.sortBy { it.t }
            val ref = Reference(phone)

            val points = ArrayList<ReportPoint>(auto.size)
            val errs = ArrayList<Double>()
            val raws = ArrayList<Double>()
            val ferrs = ArrayList<Double>()
            val todayAtF = ArrayList<Double>()
            val classS = DoubleArray(AccuracyClasses.COUNT)
            var noRefS = 0.0
            var conflictS = 0.0
            val conflicts = ArrayList<Long>()
            var movingFixS = 0.0
            for ((i, a) in auto.withIndex()) {
                val r = ref.at(a.t)
                val err = r?.let { Geo.distanceM(a.servedLat, a.servedLon, it.first, it.second) }
                val fe = inertialAt[a.t]
                val ferr = if (r != null && fe != null) Geo.distanceM(fe.lat, fe.lon, r.first, r.second) else null
                points += ReportPoint(a.t, a.fix.lat, a.fix.lon, a.fix.horizontalSpeedMps.toFloat(), a.fix.sigmaM.toFloat(), err?.toFloat(), ferr?.toFloat())
                if (err != null && AccuracyClasses.isConflict(err)) conflicts += a.t
                if (a.fix.horizontalSpeedMps < MOVING_MPS) continue
                // Up to the next fix, but a gap (cut-off, unreachable dish) is not this fix's time.
                val dt = auto.getOrNull(i + 1)?.let { (it.t - a.t) / 1e9 }?.takeIf { it <= 2.0 } ?: 1.0
                movingFixS += dt
                if (r == null || err == null) {
                    noRefS += dt
                } else if (AccuracyClasses.isConflict(err)) {
                    conflictS += dt
                } else {
                    errs += err
                    raws += Geo.distanceM(a.fix.lat, a.fix.lon, r.first, r.second)
                    if (ferr != null && !AccuracyClasses.isConflict(ferr)) {
                        ferrs += ferr
                        todayAtF += err
                    }
                    classS[AccuracyClasses.of(err)] += dt
                }
            }

            val cutoffList = cutoffs.all().map { c ->
                val end = c.endNs ?: endNs
                val during = phone.filter { it.t in c.startNs..end }
                val chord = run {
                    val before = auto.lastOrNull { it.t < c.startNs }
                    val after = auto.firstOrNull { it.t > end }?.takeIf { it.t - end <= 10_000_000_000L }
                    if (before != null && after != null) Geo.distanceM(before.fix.lat, before.fix.lon, after.fix.lat, after.fix.lon) else null
                }
                CutoffEntry(
                    startNs = c.startNs, endNs = end, code = c.code, open = c.endNs == null,
                    speedBeforeMps = c.speedBeforeMps, maxSpeed60sMps = c.maxSpeed60sMps, speedAfterMps = c.speedAfterMps,
                    phoneSpeedStartMps = ref.speedNear(c.startNs), phoneSpeedEndMps = ref.speedNear(end),
                    distanceM = if (during.size >= 2) Sessions.pathLengthM(during.map { TrackPoint(it.t, it.lat, it.lon) }) else chord,
                )
            }
            val cutoffS = cutoffList.sumOf { it.durationS }

            val restarts = reboots.reboots.map { ReportSpan(ReportSpan.REBOOT, it.lastAnswerNs ?: startNs, it.backNs) }
            val spans = buildList {
                addAll(runs(agc, ReportSpan.INTERFERENCE, mergeNs = 3_000_000_000L, minNs = 2_000_000_000L))
                addAll(runs(dishGnss, ReportSpan.DISH_GNSS, mergeNs = 3_000_000_000L, minNs = 3_000_000_000L))
                addAll(onOffSpans(mockEvents, ReportSpan.MOCK))
                addAll(onOffSpans(fallbackEvents, ReportSpan.FALLBACK))
                addAll(restarts)
                val unanswered = unreachable.filter { t -> restarts.none { t in it.fromNs..it.toNs } }
                addAll(times(unanswered, ReportSpan.UNREACHABLE, mergeNs = 5_000_000_000L, minNs = 3_000_000_000L))
                addAll(times(conflicts, ReportSpan.CONFLICT, mergeNs = 5_000_000_000L, minNs = 0L))
                addAll(stateSpans(setOf(InertialState.DEAD_RECKONING, InertialState.NETWORK_AIDED), ReportSpan.DEAD_RECKONING, alsoNoOutput = true))
                addAll(stateSpans(setOf(InertialState.SIDEWAYS_OPEN), ReportSpan.SIDEWAYS_OPEN))
            }
            val autoTrack = auto.map { TrackPoint(it.t, it.fix.lat, it.fix.lon) }
            val delivery = delivery()
            val summary = ReportSummary(
                durationS = (endNs - startNs) / 1e9,
                movingS = movingFixS + cutoffS,
                distanceM = Sessions.pathLengthM(autoTrack) ?: Sessions.pathLengthM(phone.map { TrackPoint(it.t, it.lat, it.lon) }) ?: 0.0,
                maxSpeedMps = (auto.map { it.fix.horizontalSpeedMps } + phone.mapNotNull { it.v?.toDouble() }).maxOrNull(),
                dishFixes = auto.size,
                classS = classS.toList(),
                noReferenceS = noRefS,
                conflictS = conflictS,
                cutoffS = cutoffS,
                errP50M = percentile(errs, 0.50),
                errP90M = percentile(errs, 0.90),
                errP95M = percentile(errs, 0.95),
                errMaxM = errs.maxOrNull(),
                rawP50M = percentile(raws, 0.50),
                rawP95M = percentile(raws, 0.95),
                latencyS = percentile(ages, 0.50),
                sigmaP50M = percentile(auto.map { it.fix.sigmaM }, 0.50),
                phoneAccP50M = percentile(phone.mapNotNull { it.acc?.toDouble() }, 0.50),
                mockOnS = spans.filter { it.kind == ReportSpan.MOCK }.sumOf { it.durationS },
                fallbackS = spans.filter { it.kind == ReportSpan.FALLBACK }.sumOf { it.durationS },
                interferenceS = spans.filter { it.kind == ReportSpan.INTERFERENCE }.sumOf { it.durationS },
                dishGnssDegradedS = spans.filter { it.kind == ReportSpan.DISH_GNSS }.sumOf { it.durationS },
                delivery = delivery,
                inertial = inertialSummary(spans, ref, ferrs, todayAtF),
            )
            return SessionReport(
                sessionId = id ?: fallbackId,
                bytes = bytes,
                startNs = startNs,
                endNs = endNs,
                startWallMs = startWall,
                appVersion = appVersion,
                points = decimate(points),
                reference = decimate(phone),
                cutoffs = cutoffList,
                spans = spans,
                summary = summary,
                reboots = reboots.reboots.toList(),
                dishBootNs = reboots.firstBootNs,
            )
        }

        /**
         * Spans of the filter's serving moments in [states]; [alsoNoOutput] keeps a span going through push stops
         * (nothing served while dead reckoning) but does not start one.
         */
        private fun stateSpans(states: Set<InertialState>, kind: String, alsoNoOutput: Boolean = false): List<ReportSpan> {
            val out = ArrayList<ReportSpan>()
            var from: Long? = null
            var last = 0L
            for ((t, s, _) in inertialServes) {
                val inIt = s in states || (alsoNoOutput && from != null && s == InertialState.NO_OUTPUT)
                if (inIt) {
                    if (from == null) from = t
                    last = t
                } else if (from != null) {
                    out += ReportSpan(kind, from, t)
                    from = null
                }
            }
            from?.let { out += ReportSpan(kind, it, last) }
            // a state that flickers (an offset around the 5 m line) is one span
            val merged = ArrayList<ReportSpan>()
            for (sp in out) {
                val prev = merged.lastOrNull()
                if (prev != null && sp.fromNs - prev.toNs <= 3_000_000_000L) merged[merged.size - 1] = prev.copy(toNs = sp.toNs) else merged += sp
            }
            return merged
        }

        private fun inertialSummary(spans: List<ReportSpan>, ref: Reference, ferrs: List<Double>, todayAtF: List<Double>): InertialSummary? {
            val nav = inertial?.nav ?: return null
            if (inertialServes.isEmpty()) return null
            val dr = spans.filter { it.kind == ReportSpan.DEAD_RECKONING }
            val open = spans.filter { it.kind == ReportSpan.SIDEWAYS_OPEN }
            // push stops: from a NO_OUTPUT serve to the next serve
            var pushStoppedS = 0.0
            for ((a, b) in inertialServes.zipWithNext()) if (a.second == InertialState.NO_OUTPUT && dr.any { a.first in it.fromNs..it.toNs }) pushStoppedS += (b.first - a.first) / 1e9
            val drServes = inertialServes.filter { (it.second == InertialState.DEAD_RECKONING || it.second == InertialState.NETWORK_AIDED) && it.third != null }
            val drErr = drServes.mapNotNull { (t, _, e) -> ref.at(t)?.let { r -> Geo.distanceM(e!!.lat, e.lon, r.first, r.second) to e.accuracyM } }
            return InertialSummary(
                deadReckonedS = dr.sumOf { it.durationS },
                pushStoppedS = pushStoppedS,
                sidewaysOpenings = inertialEvents.count { it.name == "dish_degraded" },
                sidewaysOpenS = open.sumOf { it.durationS },
                maxSidewaysM = inertialServes.filter { it.second == InertialState.SIDEWAYS_OPEN }.mapNotNull { it.third?.sidewaysOffsetM?.let { o -> kotlin.math.abs(o) } }.maxOrNull(),
                watchSuspects = nav.watchSuspects,
                gateRejects = inertialEvents.count { it.name == "dish_gate_reject" },
                events = inertialEvents.size,
                mountOffsetDeg = nav.mountOffsetDeg,
                errP50M = percentile(ferrs, 0.50),
                errP95M = percentile(ferrs, 0.95),
                errMaxM = ferrs.maxOrNull(),
                todayP50M = percentile(todayAtF, 0.50),
                todayP95M = percentile(todayAtF, 0.95),
                drErrP50M = percentile(drErr.map { it.first }, 0.50),
                drErrP95M = percentile(drErr.map { it.first }, 0.95),
                drWithinAccuracy = drErr.takeIf { it.isNotEmpty() }?.let { l -> l.count { it.first <= it.second }.toDouble() / l.size },
            )
        }

        /** Each received fix against the push nearest in time (within 3 s); fixes far from any push are skipped. */
        private fun delivery(): Map<String, DeliveryStats> {
            if (pushes.isEmpty()) return emptyMap()
            pushes.sortBy { it.first }
            val ts = LongArray(pushes.size) { pushes[it].first }
            val out = LinkedHashMap<String, DeliveryStats>()
            for (source in DELIVERY_SOURCES) {
                var n = 0
                var followed = 0
                var notMock = 0
                var maxOffset: Double? = null
                for (r in received) {
                    if (r.source != source) continue
                    var i = ts.binarySearch(r.t)
                    if (i < 0) i = (-i - 1).coerceIn(0, ts.size - 1)
                    val j = listOf(i - 1, i).filter { it in ts.indices }.minByOrNull { kotlin.math.abs(ts[it] - r.t) } ?: continue
                    if (kotlin.math.abs(ts[j] - r.t) > 3_000_000_000L) continue
                    val offset = Geo.distanceM(pushes[j].second, pushes[j].third, r.lat, r.lon)
                    n++
                    if (!r.mock) notMock++
                    if (r.mock && offset <= DELIVERY_OK_M) followed++
                    maxOffset = max(maxOffset ?: 0.0, offset)
                }
                if (n > 0) out[source] = DeliveryStats(n, followed, notMock, maxOffset)
            }
            return out
        }

        /** Spans between "on" and "off" events; one still on runs to the end of the session. */
        private fun onOffSpans(events: List<Pair<Long, Boolean>>, kind: String): List<ReportSpan> {
            val out = ArrayList<ReportSpan>()
            var from: Long? = null
            for ((t, on) in events) {
                if (on && from == null) from = t
                if (!on && from != null) {
                    out += ReportSpan(kind, from, t)
                    from = null
                }
            }
            from?.let { out += ReportSpan(kind, it, endNs) }
            return out
        }
    }

    /**
     * The mount offset the app's filter started from (session header config). From app 0.4 the header says where it
     * came from: `mountMode` MANUAL = `mountManualDeg` (absent while none is entered), AUTO = `mountLearnedDeg` (absent
     * while none is learned). Before that only `dishYawOffsetDeg`, where 0 meant "never set".
     */
    internal fun mountFrom(configJson: String): Double? = runCatching {
        val o = Sessions.json.parseToJsonElement(configJson) as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
        fun num(key: String) = (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
        when ((o["mountMode"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
            "MANUAL" -> num("mountManualDeg")
            "AUTO" -> num("mountLearnedDeg")
            else -> num("dishYawOffsetDeg")?.takeIf { it != 0.0 }
        }
    }.getOrNull()

    /** Linear interpolation in the phone's real fixes, only between two close, accurate ones. */
    private class Reference(private val pts: List<RefPoint>) {
        private val ts = LongArray(pts.size) { pts[it].t }

        fun at(t: Long): Pair<Double, Double>? {
            if (pts.isEmpty()) return null
            var i = ts.binarySearch(t)
            if (i >= 0) return pts[i].takeIf(::usable)?.let { it.lat to it.lon }
            i = -i - 1
            if (i == 0 || i == pts.size) return null
            val a = pts[i - 1]
            val b = pts[i]
            if (b.t - a.t > REF_MAX_GAP_NS || !usable(a) || !usable(b)) return null
            val k = (t - a.t).toDouble() / (b.t - a.t)
            return a.lat + (b.lat - a.lat) * k to a.lon + (b.lon - a.lon) * k
        }

        fun speedNear(t: Long): Double? {
            if (pts.isEmpty()) return null
            val i = ts.binarySearch(t).let { if (it >= 0) it else (-it - 1).coerceIn(0, pts.size - 1) }
            return listOfNotNull(pts.getOrNull(i - 1), pts.getOrNull(i))
                .filter { kotlin.math.abs(it.t - t) <= 2_000_000_000L }
                .minByOrNull { kotlin.math.abs(it.t - t) }?.v?.toDouble()
        }

        private fun usable(p: RefPoint) = (p.acc ?: 0f) <= REF_MAX_ACC_M
    }

    /** Spans where [flags] is true, gaps up to [mergeNs] bridged, shorter than [minNs] dropped. */
    private fun runs(flags: List<Pair<Long, Boolean>>, kind: String, mergeNs: Long, minNs: Long): List<ReportSpan> =
        times(flags.filter { it.second }.map { it.first }, kind, mergeNs, minNs)

    private fun times(ts: List<Long>, kind: String, mergeNs: Long, minNs: Long): List<ReportSpan> {
        val out = ArrayList<ReportSpan>()
        var from = Long.MIN_VALUE
        var to = Long.MIN_VALUE
        for (t in ts.sorted()) {
            if (from != Long.MIN_VALUE && t - to <= mergeNs) {
                to = t
                continue
            }
            if (from != Long.MIN_VALUE && to - from >= minNs) out += ReportSpan(kind, from, to)
            from = t
            to = t
        }
        if (from != Long.MIN_VALUE && to - from >= minNs) out += ReportSpan(kind, from, to)
        return out
    }

    private fun percentile(xs: List<Double>, q: Double): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val pos = q * (s.size - 1)
        val lo = pos.toInt()
        val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    private fun <T> decimate(xs: List<T>): List<T> {
        if (xs.size <= MAX_POINTS) return xs
        val step = xs.size.toDouble() / MAX_POINTS
        return List(MAX_POINTS) { xs[(it * step).toInt()] }
    }
}
