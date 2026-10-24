package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DishAlerts
import tools.apachi.starlinknav.core.dish.proto.DishGetHistoryResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetObstructionMapResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.proto.DishOutage
import tools.apachi.starlinknav.core.dish.proto.UXEvent

/** The dish's one-sample-per-second history, oldest sample first. */
class DishHistory(
    val latencyMs: List<Float>,
    val dropRate: List<Float>,
    val downlinkBps: List<Float>,
    val uplinkBps: List<Float>,
    /** Watts; empty on firmware without it. */
    val powerInW: List<Float>,
    val outages: List<DishOutage>,
    /** Samples ever written. */
    val current: Long = 0,
    /** The event log, newest first. */
    val events: List<UXEvent> = emptyList(),
) {
    val size: Int get() = latencyMs.size

    /** Seconds before the newest sample that sample [index] was taken (0 for the newest). */
    fun ageS(index: Int, series: List<Float> = latencyMs): Int = series.size - 1 - index

    companion object {
        /**
         * Unrolls the ring buffers (see [RingBuffer]): `current` counts every sample ever written, so the
         * newest sample sits at index (current - 1) % size and, once the buffer has wrapped, the oldest
         * at current % size. Values may be NaN; [Series] skips them.
         */
        fun of(h: DishGetHistoryResponse): DishHistory = DishHistory(
            latencyMs = RingBuffer.unroll(h.pop_ping_latency_ms, h.current),
            dropRate = RingBuffer.unroll(h.pop_ping_drop_rate, h.current),
            downlinkBps = RingBuffer.unroll(h.downlink_throughput_bps, h.current),
            uplinkBps = RingBuffer.unroll(h.uplink_throughput_bps, h.current),
            powerInW = RingBuffer.unroll(h.power_in, h.current),
            outages = h.outages.sortedBy { it.start_timestamp_ns },
            current = h.current,
            events = h.event_log?.events.orEmpty().sortedByDescending { it.start_timestamp_ns },
        )
    }
}

object OutageCause {
    private val names = mapOf(
        0 to "UNKNOWN", 1 to "BOOTING", 2 to "STOWED", 3 to "THERMAL_SHUTDOWN", 4 to "NO_SCHEDULE", 5 to "NO_SATS",
        6 to "OBSTRUCTED", 7 to "NO_DOWNLINK", 8 to "NO_PINGS", 9 to "ACTUATOR_ACTIVITY", 10 to "CABLE_TEST",
        11 to "SLEEPING", 13 to "SKY_SEARCH", 14 to "INHIBIT_RF",
    )
    fun name(code: Int): String = names[code] ?: "CAUSE_$code"
}

object RebootReason {
    private val names = mapOf(
        0 to "NONE", 1 to "MANUAL", 2 to "LOSS_OF_COMM", 3 to "SWUPDATE_NOW", 4 to "SWUPDATE_SCHEDULED", 5 to "APP",
        6 to "EMC", 7 to "FACTORY_RESET", 8 to "TEST_CASE", 9 to "THERMAL_POWER_CUT", 10 to "CRITICAL_PROCESS_DIED",
        11 to "NO_RF_READY", 12 to "POSTPONED_LOSS_OF_COMM", 13 to "SWUPDATE_STATIONARY", 14 to "AAP_CRASH",
        15 to "XP70_SACS", 16 to "INE_FAILED", 17 to "KERNEL_TAINTED", 18 to "MINI2_AUTO_OFF", 19 to "BATTERY_AUTO_OFF",
        100 to "HARD_POWER_DOWN", 200 to "ERROR_FILE_MISSING", 201 to "ERROR_CRC_MISMATCH", 202 to "ERROR_EDR_TIMEOUT",
        203 to "ERROR_UNSUPPORTED_VERSION", 204 to "ERROR_BOOT_COUNT_MISMATCH",
    )
    fun name(code: Int): String = names[code] ?: "REASON_$code"
}

/** BootReason of DeviceInfo.boot.last_reason: how the device last came up. */
object BootReason {
    private val names = mapOf(
        0 to "UNKNOWN", 1 to "FORGOTTEN", 2 to "POWER_CYCLE", 3 to "COMMAND", 4 to "SOFTWARE_UPDATE", 5 to "CONFIG_UPDATE",
        6 to "UPTIME_FDIR", 7 to "REPEATER_FDIR", 8 to "AVIATION_ETH_WAN_FDIR", 9 to "KERNEL_PANIC", 11 to "MCU_BRINGUP_FAILED_FDIR",
        12 to "AVIATION_OUTAGE_FDIR", 13 to "SOFTWARE_WATCHDOG", 14 to "INTENTIONAL_KERNEL_PANIC", 15 to "HARDWARE_WATCHDOG",
        16 to "REPEATER_UNTRUSTED_FDIR",
    )
    fun name(code: Int): String = names[code] ?: "BOOT_REASON_$code"
}

/** SoftwareUpdateState of dish status. */
object SoftwareUpdate {
    const val REBOOT_REQUIRED = 6
    private val names = listOf("UNKNOWN", "IDLE", "FETCHING", "PRE_CHECK", "WRITING", "POST_CHECK", "REBOOT_REQUIRED", "DISABLED", "FAULTED")
    fun name(code: Int): String = names.getOrNull(code) ?: "STATE_$code"
}

/** A downloaded software update waits for the dish to restart. */
fun DishGetStatusResponse.updateWaiting(): Boolean =
    software_update_state == SoftwareUpdate.REBOOT_REQUIRED || swupdate_reboot_ready || software_update_stats?.update_requires_reboot == true

/** When the dish plans that restart, Unix s; null when it does not say. */
fun DishGetStatusResponse.updateRestartAtS(): Long? = software_update_stats?.reboot_scheduled_utc_time?.takeIf { it > 0 }

/** Names of the alerts that are set, as the proto names them (stable keys for translations). */
fun DishAlerts.active(): List<String> = listOfNotNull(
    "motors_stuck".takeIf { motors_stuck },
    "thermal_shutdown".takeIf { thermal_shutdown },
    "thermal_throttle".takeIf { thermal_throttle },
    "unexpected_location".takeIf { unexpected_location },
    "mast_not_near_vertical".takeIf { mast_not_near_vertical },
    "slow_ethernet_speeds".takeIf { slow_ethernet_speeds },
    "slow_ethernet_speeds_100".takeIf { slow_ethernet_speeds_100 },
    "install_pending".takeIf { install_pending },
    "is_heating".takeIf { is_heating },
    "power_supply_thermal_throttle".takeIf { power_supply_thermal_throttle },
    "is_power_save_idle".takeIf { is_power_save_idle },
    "dbf_telem_stale".takeIf { dbf_telem_stale },
    "low_motor_current".takeIf { low_motor_current },
    "lower_signal_than_predicted".takeIf { lower_signal_than_predicted },
    "obstruction_map_reset".takeIf { obstruction_map_reset },
    "dish_water_detected".takeIf { dish_water_detected },
    "router_water_detected".takeIf { router_water_detected },
    "upsu_router_port_slow".takeIf { upsu_router_port_slow },
    "no_ethernet_link".takeIf { no_ethernet_link },
)

/** Sky map cell states for drawing. */
enum class SkyCell { NO_DATA, BLOCKED, REDUCED, CLEAR }

/**
 * The obstruction map as rows of cells, row-major as sent. Values are recorded signal quality:
 * negative = no data for that patch of sky, exactly 0 = blocked, between 0 and 1 = reduced
 * signal, 1 or more = clear (semantics as documented by Star Debug). In FRAME_EARTH grids
 * north is at the top.
 */
fun DishGetObstructionMapResponse.cells(): List<List<SkyCell>> {
    val rows = num_rows
    val cols = num_cols
    if (rows <= 0 || cols <= 0 || snr.size < rows * cols) return emptyList()
    return List(rows) { r ->
        List(cols) { c ->
            val v = snr[r * cols + c]
            when {
                !v.isFinite() || v < 0 -> SkyCell.NO_DATA
                v == 0f -> SkyCell.BLOCKED
                v < 1f -> SkyCell.REDUCED
                else -> SkyCell.CLEAR
            }
        }
    }
}

/** Blocked share of the observed cells (reduced-signal cells count as observed, not blocked). */
fun DishGetObstructionMapResponse.blockedFraction(): Double? {
    val observed = snr.count { it.isFinite() && it >= 0 }
    if (observed == 0) return null
    return snr.count { it == 0f }.toDouble() / observed
}
