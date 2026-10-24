package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DishGetDiagnosticsResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetObstructionMapResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.proto.InitializationDurationSeconds
import tools.apachi.starlinknav.core.dish.proto.UXEvent
import tools.apachi.starlinknav.core.dish.proto.WifiAlerts
import tools.apachi.starlinknav.core.dish.proto.WifiGetHistoryResponse
import kotlin.math.atan2
import kotlin.math.floor

/** Ring buffers of the dish and router history. */
object RingBuffer {
    /**
     * Oldest sample first. [written] counts every sample ever put into the ring of `xs.size` slots, so
     * the newest sample sits at index (written - 1) % size and, once the ring has wrapped, the oldest
     * at written % size. Before it wraps only the first [written] slots hold samples.
     */
    fun <T> unroll(xs: List<T>, written: Long): List<T> {
        val n = xs.size
        if (n == 0 || written <= 0) return emptyList()
        if (written < n) return xs.subList(0, written.toInt())
        val start = (written % n).toInt()
        return xs.subList(start, n) + xs.subList(0, start)
    }
}

/** Statistics over samples that may be NaN (the dish writes NaN for a stat it could not compute). */
object Series {
    fun finite(xs: List<Float>): List<Float> = xs.filter { it.isFinite() }
    fun mean(xs: List<Float>): Float? = finite(xs).takeIf { it.isNotEmpty() }?.let { f -> (f.sumOf { it.toDouble() } / f.size).toFloat() }
    fun max(xs: List<Float>): Float? = finite(xs).maxOrNull()
    fun min(xs: List<Float>): Float? = finite(xs).minOrNull()
    fun lastFinite(xs: List<Float>): Float? = xs.lastOrNull { it.isFinite() }

    /** Share of finite samples above [threshold], 0..1; null when there are none. */
    fun shareAbove(xs: List<Float>, threshold: Float): Float? =
        finite(xs).takeIf { it.isNotEmpty() }?.let { f -> f.count { it > threshold }.toFloat() / f.size }

    /** Linear-interpolated percentile ([p] 0..100). */
    fun percentile(xs: List<Float>, p: Double): Float? {
        val f = finite(xs).sorted()
        if (f.isEmpty()) return null
        val pos = (p / 100.0).coerceIn(0.0, 1.0) * (f.size - 1)
        val lo = floor(pos).toInt()
        val hi = minOf(lo + 1, f.size - 1)
        return (f[lo] + (f[hi] - f[lo]) * (pos - lo)).toFloat()
    }
}

/** The router's history: ping series per second and the 15 s drop-rate series, oldest first. */
class RouterHistory(
    val pingLatencyMs: List<Float>,
    val pingDropRate: List<Float>,
    /** 15-second buckets of ping drop rate towards the POP, Google and Cloudflare (IPv4, IPv6). */
    val popIpv4Drop15s: List<Float>,
    val popIpv6Drop15s: List<Float>,
    val googleIpv4Drop15s: List<Float>,
    val googleIpv6Drop15s: List<Float>,
    val cloudflareIpv4Drop15s: List<Float>,
    val cloudflareIpv6Drop15s: List<Float>,
    /** Resolver name -> 15 s drop rates. */
    val dnsResolverDrop15s: Map<String, List<Float>>,
    val current: Long,
    val current15s: Long,
    /** The router's event log, newest first. */
    val events: List<UXEvent>,
) {
    companion object {
        fun of(h: WifiGetHistoryResponse): RouterHistory {
            // ASSUMPTION (unverified on a real router): current_index_15s counts the 15 s samples written, like
            // `current` counts the per-second ones. If it is an index into the ring, these series come out
            // truncated or in the wrong order. The probe prints it beside current/15 to settle this.
            fun s15(xs: List<Float>) = RingBuffer.unroll(xs, h.current_index_15s)
            return RouterHistory(
                pingLatencyMs = RingBuffer.unroll(h.ping_latency_ms, h.current),
                pingDropRate = RingBuffer.unroll(h.ping_drop_rate, h.current),
                popIpv4Drop15s = s15(h.pop_ipv4_ping_drop_rate_last_15s),
                popIpv6Drop15s = s15(h.pop_ipv6_ping_drop_rate_last_15s),
                googleIpv4Drop15s = s15(h.google_ipv4_ping_drop_rate_last_15s),
                googleIpv6Drop15s = s15(h.google_ipv6_ping_drop_rate_last_15s),
                cloudflareIpv4Drop15s = s15(h.cloudflare_ipv4_ping_drop_rate_last_15s),
                cloudflareIpv6Drop15s = s15(h.cloudflare_ipv6_ping_drop_rate_last_15s),
                dnsResolverDrop15s = h.dns_resolver_drop_rate.associate { it.resolver to s15(it.value_?.drop_rate_last_15s.orEmpty()) },
                current = h.current,
                current15s = h.current_index_15s,
                events = h.event_log?.events.orEmpty().sortedByDescending { it.start_timestamp_ns },
            )
        }
    }
}

/** One 45-degree wedge of the sky, centred on a compass point (0 = north, 1 = north-east, ...). */
data class SkySector(val index: Int, val observed: Int, val blocked: Int, val reduced: Int) {
    val centreDeg: Double get() = index * 45.0
    val blockedFraction: Double? get() = if (observed == 0) null else blocked.toDouble() / observed
}

/**
 * The obstruction map in numbers. Cells hold recorded signal quality: negative (or NaN) = no data for
 * that patch of sky, exactly 0 = blocked, between 0 and 1 = reduced, 1 or more = clear. Shares are of
 * observed cells, not of sky area (the map is a projection).
 */
data class ObstructionAnalysis(
    val rows: Int,
    val cols: Int,
    val frame: Int,
    val observed: Int,
    val blocked: Int,
    val reduced: Int,
    val clear: Int,
    /**
     * Eight wedges of 45 degrees, centred on N, NE, E, SE, S, SW, W, NW (N = 337.5..22.5). Star Debug's
     * wedges start at 0, 45, ... instead, so its per-wedge shares differ from these. Without the zenith cell.
     */
    val sectors: List<SkySector>,
    /** Cells in the largest 4-connected blocked patch. */
    val largestBlockedPatch: Int,
    /** Degrees the picture was turned to put north up (azimuth of the panel top for FRAME_UT; 0 otherwise). */
    val rotationDeg: Double,
    /** The azimuths rest on an assumption (FRAME_UT turned by a heading, or an unknown frame). */
    val approximate: Boolean,
) {
    val blockedFraction: Double? get() = if (observed == 0) null else blocked.toDouble() / observed
    val largestPatchFraction: Double? get() = if (observed == 0) null else largestBlockedPatch.toDouble() / observed
    val clearFraction: Double? get() = if (observed == 0) null else clear.toDouble() / observed
}

object Obstruction {
    const val FRAME_UNKNOWN = 0
    const val FRAME_EARTH = 1
    const val FRAME_UT = 2

    /**
     * Azimuth of a cell, degrees clockwise from the picture's up, in the map's own frame. Row 0 is the
     * top; the middle of the map is the zenith.
     */
    fun cellAzimuthDeg(rows: Int, cols: Int, row: Int, col: Int): Double {
        val dx = col - (cols - 1) / 2.0
        val dy = (rows - 1) / 2.0 - row
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    /**
     * [panelUpAzimuthDeg] is the azimuth of the top of the panel (body +y, [DishAttitude.panelUpAzimuthDeg]):
     * a FRAME_UT map is fixed to the dish with the panel top at the top of the picture (+x to the right), so
     * it is turned by that azimuth to put north up. That turn is an uncalibrated assumption (flagged
     * [ObstructionAnalysis.approximate]; Star Debug and the paper it cites draw it this way). It is not the
     * heading (body +x): that is 90 degrees off. FRAME_EARTH maps are north-up already.
     *
     * Sector counts leave out the zenith cell (the middle of a map with an odd number of rows and
     * columns): it has no azimuth. Totals include it.
     */
    fun analyze(map: DishGetObstructionMapResponse, panelUpAzimuthDeg: Double? = null): ObstructionAnalysis? {
        val rows = map.num_rows
        val cols = map.num_cols
        if (rows <= 0 || cols <= 0 || map.snr.size < rows * cols) return null
        val rotation = if (map.map_reference_frame == FRAME_UT) panelUpAzimuthDeg ?: 0.0 else 0.0
        val approximate = map.map_reference_frame != FRAME_EARTH
        val observedBySector = IntArray(8)
        val blockedBySector = IntArray(8)
        val reducedBySector = IntArray(8)
        var observed = 0
        var blocked = 0
        var reduced = 0
        var clear = 0
        val isBlocked = BooleanArray(rows * cols)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val v = map.snr[r * cols + c]
                if (!v.isFinite() || v < 0f) continue
                observed++
                val zenith = rows % 2 == 1 && cols % 2 == 1 && r == rows / 2 && c == cols / 2
                val sector = if (zenith) -1 else (((cellAzimuthDeg(rows, cols, r, c) + rotation + 360.0) % 360.0 + 22.5) / 45.0).toInt() % 8
                if (sector >= 0) observedBySector[sector]++
                when {
                    v == 0f -> {
                        blocked++
                        if (sector >= 0) blockedBySector[sector]++
                        isBlocked[r * cols + c] = true
                    }
                    v < 1f -> {
                        reduced++
                        if (sector >= 0) reducedBySector[sector]++
                    }
                    else -> clear++
                }
            }
        }
        return ObstructionAnalysis(
            rows = rows, cols = cols, frame = map.map_reference_frame,
            observed = observed, blocked = blocked, reduced = reduced, clear = clear,
            sectors = List(8) { SkySector(it, observedBySector[it], blockedBySector[it], reducedBySector[it]) },
            largestBlockedPatch = largestPatch(isBlocked, rows, cols),
            rotationDeg = rotation, approximate = approximate,
        )
    }

    private fun largestPatch(blocked: BooleanArray, rows: Int, cols: Int): Int {
        val seen = BooleanArray(blocked.size)
        val stack = IntArray(blocked.size)
        var best = 0
        for (start in blocked.indices) {
            if (!blocked[start] || seen[start]) continue
            var top = 0
            var size = 0
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val i = stack[--top]
                size++
                val r = i / cols
                val c = i % cols
                if (r > 0) top = push(i - cols, blocked, seen, stack, top)
                if (r < rows - 1) top = push(i + cols, blocked, seen, stack, top)
                if (c > 0) top = push(i - 1, blocked, seen, stack, top)
                if (c < cols - 1) top = push(i + 1, blocked, seen, stack, top)
            }
            if (size > best) best = size
        }
        return best
    }

    private fun push(i: Int, blocked: BooleanArray, seen: BooleanArray, stack: IntArray, top: Int): Int {
        if (!blocked[i] || seen[i]) return top
        seen[i] = true
        stack[top] = i
        return top + 1
    }
}

/** What the dish is doing, derived from its outage cause and ready states (the status has no state field). */
enum class DishLinkState { CONNECTED, SEARCHING, BOOTING, STOWED, SLEEPING, THERMAL_SHUTDOWN, OBSTRUCTED, RF_INHIBITED, BUSY, UNKNOWN }

data class DerivedDishState(
    val state: DishLinkState,
    /** DishOutage.Cause when the status carries an outage. */
    val outageCause: Int?,
    /** Ready states that are not up yet (cady is skipped on hardware without that clock generator). */
    val notReady: List<String>,
)

object DishStates {
    /** Hardware without the `cady` clock generator reports it false all the time (Mini, Gen 3 Standard, rev5). */
    fun hasCady(hardwareVersion: String): Boolean =
        !(hardwareVersion.startsWith("mini1_") || hardwareVersion.startsWith("rev4") || hardwareVersion.startsWith("rev5") || hardwareVersion == "rev_mini_prod1")

    fun of(s: DishGetStatusResponse): DerivedDishState {
        val rs = s.ready_states
        val notReady = if (rs == null) {
            emptyList()
        } else {
            val cady = hasCady(s.device_info?.hardware_version.orEmpty())
            listOfNotNull(
                "cady".takeIf { cady && !rs.cady }, "scp".takeIf { !rs.scp }, "l1l2".takeIf { !rs.l1l2 },
                "xphy".takeIf { !rs.xphy }, "aap".takeIf { !rs.aap }, "rf".takeIf { !rs.rf },
            )
        }
        val outage = s.outage
        val state = when {
            outage != null -> when (outage.cause) {
                1 -> DishLinkState.BOOTING
                2 -> DishLinkState.STOWED
                3 -> DishLinkState.THERMAL_SHUTDOWN
                6 -> DishLinkState.OBSTRUCTED
                11 -> DishLinkState.SLEEPING
                14 -> DishLinkState.RF_INHIBITED
                9, 10 -> DishLinkState.BUSY
                0, 4, 5, 7, 8, 13 -> DishLinkState.SEARCHING
                else -> DishLinkState.UNKNOWN
            }
            s.stow_requested && s.hasActuators() != HasActuators.NO -> DishLinkState.STOWED
            notReady.isNotEmpty() -> DishLinkState.BOOTING
            else -> DishLinkState.CONNECTED
        }
        return DerivedDishState(state, outage?.cause, notReady)
    }
}

/** HasActuators of the status, falling back to the alignment stats' copy; UNKNOWN (0) when neither says. */
fun DishGetStatusResponse.hasActuators(): Int =
    has_actuators.takeIf { it != HasActuators.UNKNOWN } ?: alignment_stats?.has_actuators ?: HasActuators.UNKNOWN

/** Stow and unstow make sense only when the dish says it has motors (a Mini has none). */
fun DishGetStatusResponse.canStow(): Boolean = hasActuators() == HasActuators.YES

enum class AlertSeverity { WARNING, CAUTION, ADVISORY }

/** One alert, [key] being the proto field name (stable key for translations). */
data class DishAlert(val key: String, val severity: AlertSeverity)

object AlertList {
    private val warnings = setOf("thermal_shutdown", "motors_stuck", "dish_water_detected", "router_water_detected", "hardware_self_test_failed")
    private val advisories = setOf("install_pending", "is_heating", "is_power_save_idle", "obstruction_map_reset")
    private val motorAlerts = setOf("motors_stuck", "low_motor_current")

    private val diagnosticsKeys = mapOf(
        "dish_is_heating" to "is_heating", "dish_thermal_throttle" to "thermal_throttle", "dish_thermal_shutdown" to "thermal_shutdown",
        "software_install_pending" to "install_pending",
    )

    fun severity(key: String): AlertSeverity = when (key) {
        in warnings -> AlertSeverity.WARNING
        in advisories -> AlertSeverity.ADVISORY
        else -> AlertSeverity.CAUTION
    }

    /**
     * The alerts of the status and, when given, of the diagnostics (which adds `obstructed`, a failed
     * hardware self test and overage rate limiting), most severe first. Motor alerts are dropped on a
     * dish without motors.
     */
    fun of(status: DishGetStatusResponse?, diagnostics: DishGetDiagnosticsResponse? = null): List<DishAlert> {
        val keys = LinkedHashSet<String>()
        status?.alerts?.active()?.let(keys::addAll)
        diagnostics?.alerts?.let { a ->
            listOfNotNull(
                "dish_is_heating".takeIf { a.dish_is_heating }, "dish_thermal_throttle".takeIf { a.dish_thermal_throttle },
                "dish_thermal_shutdown".takeIf { a.dish_thermal_shutdown },
                "power_supply_thermal_throttle".takeIf { a.power_supply_thermal_throttle },
                "motors_stuck".takeIf { a.motors_stuck }, "mast_not_near_vertical".takeIf { a.mast_not_near_vertical },
                "slow_ethernet_speeds".takeIf { a.slow_ethernet_speeds }, "software_install_pending".takeIf { a.software_install_pending },
                "obstructed".takeIf { a.obstructed },
            ).forEach { keys += diagnosticsKeys[it] ?: it }
        }
        if (diagnostics?.hardware_self_test == HardwareSelfTest.FAILED) keys += "hardware_self_test_failed"
        if (diagnostics?.overage_rate_limited == true) keys += "overage_rate_limited"
        if (status != null && status.hasActuators() == HasActuators.NO) keys.removeAll(motorAlerts)
        return keys.map { DishAlert(it, severity(it)) }.sortedBy { it.severity.ordinal }
    }

    /** Names of the router alerts that are set, as the proto names them. */
    fun router(a: WifiAlerts?): List<String> = if (a == null) emptyList() else listOfNotNull(
        "thermal_throttle".takeIf { a.thermal_throttle }, "install_pending".takeIf { a.install_pending },
        "freshly_fused".takeIf { a.freshly_fused }, "lan_eth_slow_link_10".takeIf { a.lan_eth_slow_link_10 },
        "lan_eth_slow_link_100".takeIf { a.lan_eth_slow_link_100 }, "wan_eth_poor_connection".takeIf { a.wan_eth_poor_connection },
        "mesh_topology_changing_often".takeIf { a.mesh_topology_changing_often }, "mesh_unreliable_backhaul".takeIf { a.mesh_unreliable_backhaul },
        "radius_missing_process".takeIf { a.radius_missing_process }, "eth_switch_error".takeIf { a.eth_switch_error },
        "poe_on_dish_unreachable".takeIf { a.poe_on_dish_unreachable }, "poe_fuse_blown".takeIf { a.poe_fuse_blown },
        "poe_router_overcurrent".takeIf { a.poe_router_overcurrent }, "poe_off_current_nominal".takeIf { a.poe_off_current_nominal },
        "poe_vin_overvoltage".takeIf { a.poe_vin_overvoltage }, "poe_vin_undervoltage".takeIf { a.poe_vin_undervoltage },
        "high_cable_ping_drop_rate".takeIf { a.high_cable_ping_drop_rate }, "sandbox_disabled".takeIf { a.sandbox_disabled },
        "only_overflight_blocked".takeIf { a.only_overflight_blocked }, "offline_networks_disabled".takeIf { a.offline_networks_disabled },
        "wired_mesh_not_using_wan_iface".takeIf { a.wired_mesh_not_using_wan_iface },
    )
}

/** One start-up stage of this power-on and the second (since boot) it was reached. */
data class InitStage(val key: String, val seconds: Int)

/** The dish's initialization durations: how long each stage of this power-on took, in order of time. */
data class InitializationView(val stages: List<InitStage>, val notReached: List<String>) {
    /** When the last reported stage was reached: how long this power-on took to settle. */
    val totalS: Int? get() = stages.maxOfOrNull { it.seconds }
    fun secondsOf(key: String): Int? = stages.firstOrNull { it.key == key }?.seconds

    companion object {
        val KEYS = listOf(
            "attitude_initialization", "burst_detected", "ekf_converged", "first_cplane", "first_pop_ping",
            "gps_valid", "initial_network_entry", "network_schedule", "rf_ready", "stable_connection",
        )

        fun of(d: InitializationDurationSeconds?): InitializationView {
            if (d == null) return InitializationView(emptyList(), KEYS)
            val all = listOf(
                "attitude_initialization" to d.attitude_initialization, "burst_detected" to d.burst_detected,
                "ekf_converged" to d.ekf_converged, "first_cplane" to d.first_cplane, "first_pop_ping" to d.first_pop_ping,
                "gps_valid" to d.gps_valid, "initial_network_entry" to d.initial_network_entry,
                "network_schedule" to d.network_schedule, "rf_ready" to d.rf_ready, "stable_connection" to d.stable_connection,
            )
            val reached = all.filter { it.second > 0 }
            return InitializationView(
                stages = reached.map { InitStage(it.first, it.second) }.sortedWith(compareBy({ it.seconds }, { KEYS.indexOf(it.key) })),
                notReached = all.filter { it.second <= 0 }.map { it.first },
            )
        }
    }
}
