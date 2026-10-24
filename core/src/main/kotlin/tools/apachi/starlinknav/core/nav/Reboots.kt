package tools.apachi.starlinknav.core.nav

import kotlinx.serialization.Serializable
import tools.apachi.starlinknav.core.dish.CallOutcome
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.GrpcStatus
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.updateRestartAtS
import tools.apachi.starlinknav.core.dish.updateWaiting

/**
 * A restart of the dish, seen in its status answers: the uptime it reports went back (or its boot
 * count went up) after a stretch without answers. Times are recorder-clock ns.
 */
@Serializable
data class DishReboot(
    /** When it came up again: receipt time of the first status after it minus the uptime it reported. */
    val bootNs: Long,
    /** The first status after it, which gave it away. */
    val seenNs: Long,
    /** The last dish answer before it went quiet; null when the session saw none. */
    val lastAnswerNs: Long? = null,
    /** The first dish answer after it. */
    val backNs: Long,
    /** Uptime in the first status after it, s. */
    val uptimeS: Long = 0,
    /** RebootReason the dish reported, from the last status before or the first after (0 = NONE). */
    val reason: Int = 0,
    /** BootReason from DeviceInfo.boot, on firmware that reports it. */
    val bootReason: Int? = null,
    val softwareBefore: String? = null,
    val softwareAfter: String? = null,
    /** A downloaded software update was waiting for a restart before it. */
    val updateWaiting: Boolean = false,
    /** When the dish had planned that restart, Unix s. */
    val updateRestartAtS: Long? = null,
    /** Star Nav sent the reboot command shortly before. */
    val commanded: Boolean = false,
    val bootcountBefore: Int? = null,
    val bootcountAfter: Int? = null,
) {
    /** Seconds without dish answers around it; null when the session started during it. */
    val offlineS: Double? get() = lastAnswerNs?.let { (backNs - it) / 1e9 }

    /** The software version changed across it. */
    val updated: Boolean get() = !softwareBefore.isNullOrEmpty() && !softwareAfter.isNullOrEmpty() && softwareBefore != softwareAfter

    val cause: RebootCause get() = RebootCause.of(this)
}

/** Why the dish restarted, as far as its answers tell; the app puts it into words. */
enum class RebootCause {
    /** The software version changed across the restart. */
    UPDATE_INSTALLED,
    /** Star Nav sent the reboot command. */
    STAR_NAV,
    /** Restarted to install a software update (SWUPDATE_*, boot SOFTWARE_UPDATE). */
    UPDATE,
    /** A restart was requested by a user or an app (MANUAL, APP, boot COMMAND). */
    REQUESTED,
    /** The power was cut (HARD_POWER_DOWN, boot POWER_CYCLE). */
    POWER,
    /** Automatic power-off (MINI2_AUTO_OFF, BATTERY_AUTO_OFF). */
    AUTO_OFF,
    /** Overheating protection (THERMAL_POWER_CUT). */
    OVERHEAT,
    /** No contact with the Starlink network for too long (LOSS_OF_COMM, INE_FAILED). */
    NO_NETWORK,
    /** A crash, watchdog, fault protection or failed start-up check. */
    FAULT,
    FACTORY_RESET,
    /** Restarted to apply new settings (boot CONFIG_UPDATE). */
    CONFIG,
    /** A reason this app has no words for; the technical name says it. */
    OTHER,
    NOT_REPORTED;

    companion object {
        fun of(r: DishReboot): RebootCause = when {
            r.updated -> UPDATE_INSTALLED
            r.commanded -> STAR_NAV
            r.reason != 0 -> ofRebootReason(r.reason)
            r.bootReason != null -> ofBootReason(r.bootReason)
            else -> NOT_REPORTED
        }

        /** RebootReason of dish status (field 1032). */
        fun ofRebootReason(code: Int): RebootCause = when (code) {
            0 -> NOT_REPORTED
            1, 5 -> REQUESTED
            2, 12, 16 -> NO_NETWORK
            3, 4, 13 -> UPDATE
            7 -> FACTORY_RESET
            9 -> OVERHEAT
            6, 10, 11, 14, 15, 17, in 200..204 -> FAULT
            18, 19 -> AUTO_OFF
            100 -> POWER
            else -> OTHER
        }

        /** BootReason of DeviceInfo.boot.last_reason. */
        fun ofBootReason(code: Int): RebootCause = when (code) {
            0, 1 -> NOT_REPORTED
            2 -> POWER
            3 -> REQUESTED
            4 -> UPDATE
            5 -> CONFIG
            6, 7, 8, 9, 11, 12, 13, 14, 15, 16 -> FAULT
            else -> OTHER
        }
    }
}

/**
 * Finds dish restarts in the calls the app makes anyway. Each status carries the dish's uptime,
 * so receipt time minus uptime is when it booted; that moving forward by more than a few seconds,
 * after a stretch without answers, is a restart (an uptime that only glitches is not). The boot
 * count going up counts too. Fed in time order, live in the app and over recorded sessions
 * alike; [onReboot] gets each one when the first status after it arrives.
 */
class RebootTracker(private val onReboot: (DishReboot) -> Unit = {}) {
    private class Seen(
        val atNs: Long,
        val bootNs: Long?,
        val uptimeS: Long?,
        val software: String?,
        val bootcount: Int?,
        val reason: Int,
        val updateWaiting: Boolean,
        val updateRestartAtS: Long?,
    )

    private val found = ArrayList<DishReboot>()
    val reboots: List<DishReboot> get() = found
    /** The latest status, and the latest one that carried an uptime (what restarts are judged against). */
    private var latest: Seen? = null
    private var known: Seen? = null
    private var lastAnswerNs: Long? = null
    private var gapFromNs: Long? = null
    private var gapToNs: Long? = null
    /** When a command that restarts the dish (reboot, an update with a scheduled restart, a factory reset) may have reached it; null = none. */
    var commandNs: Long? = null
        private set

    /** When the dish booted, per the first status of the session (recorder clock); null before one. */
    var firstBootNs: Long? = null
        private set

    /** Uptime in the latest status that had one, s, and when that status came. */
    val uptimeS: Long? get() = known?.uptimeS
    val uptimeAtNs: Long? get() = known?.atNs
    val software: String? get() = latest?.software
    val updateWaiting: Boolean get() = latest?.updateWaiting == true
    val updateRestartAtS: Long? get() = latest?.updateRestartAtS

    fun onCall(result: DishCallResult, atNs: Long) {
        // A restart command, or one that makes the dish restart (update with a scheduled restart, factory reset), that
        // may have reached it: the dish may drop the connection as it goes down, so one sent without an answer counts.
        // One that never left the phone (no connection) or that the dish refused does not.
        if (result.kind.restartsDish && mayHaveReachedDish(result)) commandNs = atNs
        if (!result.ok) return
        val prevAnswer = lastAnswerNs
        if (prevAnswer != null && atNs - prevAnswer >= GAP_NS) {
            gapFromNs = prevAnswer
            gapToNs = atNs
        }
        lastAnswerNs = atNs
        val status = result.response?.dish_get_status
        if (result.kind == DishCallKind.STATUS && status != null) onStatus(status, atNs)
    }

    /**
     * The command went out and was not refused: answered, sent without an answer, or an answer that is no gRPC error
     * (a reply that did not decode, an HTTP error without a gRPC status).
     */
    private fun mayHaveReachedDish(r: DishCallResult): Boolean = when (r.outcome) {
        CallOutcome.OK, CallOutcome.SENT_NO_ANSWER -> true
        CallOutcome.OTHER -> r.grpcStatus == null || r.grpcStatus == GrpcStatus.OK
        else -> false
    }

    private fun onStatus(s: DishGetStatusResponse, atNs: Long) {
        // proto3 leaves a zero out, so a missing uptime is unknown rather than a fresh boot.
        val uptime = s.device_state?.uptime_s?.takeIf { it > 0 }
        val info = s.device_info
        val now = Seen(
            atNs = atNs,
            bootNs = uptime?.let { atNs - it * 1_000_000_000L },
            uptimeS = uptime,
            software = info?.software_version?.ifEmpty { null },
            bootcount = info?.bootcount?.takeIf { it > 0 },
            reason = s.reboot_reason,
            updateWaiting = s.updateWaiting(),
            updateRestartAtS = s.updateRestartAtS(),
        )
        latest = now
        val bootNs = now.bootNs ?: return
        if (firstBootNs == null) firstBootNs = bootNs
        val prev = known
        known = now
        if (prev?.bootNs == null) return
        val bootMoved = bootNs - prev.bootNs > BOOT_JUMP_NS
        val counted = prev.bootcount != null && now.bootcount != null && now.bootcount > prev.bootcount
        // A restart takes the dish away for tens of seconds; an uptime that jumps without that is a glitch.
        val gap = gapToNs?.takeIf { to -> to <= atNs && atNs - to <= GAP_MATCH_NS && (gapFromNs ?: to) <= bootNs + SLACK_NS }
        if (!(counted || (bootMoved && gap != null))) return
        val reboot = DishReboot(
            bootNs = bootNs,
            seenNs = atNs,
            lastAnswerNs = if (gap != null) gapFromNs else prev.atNs,
            backNs = gap ?: atNs,
            uptimeS = uptime ?: 0,
            reason = if (now.reason != 0) now.reason else prev.reason,
            bootReason = info?.boot?.last_reason,
            softwareBefore = prev.software,
            softwareAfter = now.software,
            updateWaiting = prev.updateWaiting,
            updateRestartAtS = prev.updateRestartAtS,
            commanded = commandNs?.let { it <= bootNs && bootNs - it <= COMMAND_WINDOW_NS } == true,
            bootcountBefore = prev.bootcount,
            bootcountAfter = now.bootcount,
        )
        found += reboot
        onReboot(reboot)
    }

    companion object {
        /** Boot time moving by more than this is a restart (uptime is whole seconds; clocks drift ~0.2 s/h). */
        private const val BOOT_JUMP_NS = 10_000_000_000L
        /** Answers this far apart make a gap (the poller asks every second). */
        private const val GAP_NS = 5_000_000_000L
        /** The gap must have ended this shortly before the revealing status. */
        private const val GAP_MATCH_NS = 120_000_000_000L
        private const val SLACK_NS = 3_000_000_000L
        /** A reboot command this long before the boot counts as its cause. */
        private const val COMMAND_WINDOW_NS = 600_000_000_000L
    }
}
