package tools.apachi.starlinknav.core.nav

import tools.apachi.starlinknav.core.dish.CallOutcome
import tools.apachi.starlinknav.core.dish.DiagnosticsFix
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.DishFix
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.core.dish.LocalLocation
import tools.apachi.starlinknav.core.dish.LocalLocationState
import tools.apachi.starlinknav.core.dish.diagnosticsFix
import tools.apachi.starlinknav.core.dish.fix
import tools.apachi.starlinknav.core.dish.proto.DishConfig

/**
 * Why the dish refuses `get_location` (every position source), apart from the speed-policy disablement that
 * [CutoffTracker] follows: three states the app names with different words and acts on differently. The history behind
 * them (research of 2026-10-27, verified against Home Assistant and community reports): Starlink switched local
 * `get_location` off by policy on 2026-05-20 (V4 and Mini answered gRPC 7 "GetLocation requests disabled due to
 * policy"), gave it back to Priority plans around July 2026 and not to Residential (a non-Priority Mini in router mode
 * answers UNIMPLEMENTED), and the call also needs the Starlink app's "allow access on local network" setting
 * (`DishConfig.location_request_mode` LOCAL, which [LocalLocation] reads).
 */
enum class LocationRefusal {
    /**
     * Refused by the plan's or the firmware's policy: gRPC PERMISSION_DENIED with the policy wording ("due to policy",
     * [CallOutcome.PLAN_RESTRICTED]), or a PERMISSION_DENIED / UNAUTHENTICATED while the setting reads as on. Lasts until
     * the plan or the policy changes; enabling the setting does not help.
     */
    POLICY_DENIED,

    /** gRPC UNIMPLEMENTED or "not supported": this firmware or plan does not offer the call at all. */
    UNIMPLEMENTED,

    /**
     * The setting is not LOCAL: read from the config, or inferred from a plain refusal while no setting is readable
     * ([LocalLocation.read] says OFF). The user must enable location on the local network (the Setup row does that).
     */
    NOT_ENABLED;

    companion object {
        /**
         * The refusal a `get_location` result ([auto], any position source) comes to, read together with the setting
         * ([statusConfig] / [getConfig] / [diagnosticsEnabled] as [LocalLocation.read] takes them). Null when the call is
         * no refusal of this kind: it answered, it never reached the dish, or it was refused by a UT disablement (a
         * cut-off, [CutoffTracker]'s business). The dish's own words win over the setting: a plan or firmware refusal is
         * what it says whatever the setting reads; a plain PERMISSION_DENIED is NOT_ENABLED while the setting reads as
         * off (or is not readable at all, the probable reason) and POLICY_DENIED while it reads as on.
         */
        fun classify(auto: DishCallResult, statusConfig: DishConfig?, getConfig: DishConfig? = null, diagnosticsEnabled: Boolean? = null): LocationRefusal? {
            if (auto.ok || auto.httpStatus == 0 || auto.refusedByDisablement) return null
            val reading = LocalLocation.read(statusConfig, getConfig, auto, diagnosticsEnabled)
            return when (auto.outcome) {
                CallOutcome.PLAN_RESTRICTED -> POLICY_DENIED
                CallOutcome.UNIMPLEMENTED -> UNIMPLEMENTED
                CallOutcome.PERMISSION_DENIED, CallOutcome.UNAUTHENTICATED ->
                    if (reading.state == LocalLocationState.OFF) NOT_ENABLED else POLICY_DENIED
                // another failure (an internal error, a reply that did not decode): a refusal only if the setting itself reads as off
                else -> if (reading.state == LocalLocationState.OFF && reading.settingRead) NOT_ENABLED else null
            }
        }
    }
}

/**
 * Follows the dish's refusal of `get_location` from the calls the poller makes anyway: the status (its embedded config,
 * field 2000), `dish_get_config` when it is polled, the diagnostics (`location.enabled`) and every AUTO answer. [current]
 * is one of the three states while the last AUTO call was refused that way, null while the dish answers (or has not
 * been asked yet). A call that never reached the dish or a UT disablement changes nothing: the first says nothing, the
 * second is a cut-off. Fed in time order, live in the app and over recorded sessions alike; [onChange] gets every change
 * of [current] with the call that made it.
 */
class LocationRefusalTracker(private val onChange: (LocationRefusal?, DishCallResult, Long) -> Unit = { _, _, _ -> }) {
    var current: LocationRefusal? = null
        private set

    /** When [current] started (the time of the first refused call), ns; 0 while null. */
    var sinceNs = 0L
        private set

    /** The dish's own words (gRPC message, or the transport's error) of the last refused call; null while not refused. */
    var message: String? = null
        private set
    private var statusConfig: DishConfig? = null
    private var getConfig: DishConfig? = null
    private var diagnosticsEnabled: Boolean? = null

    fun onCall(result: DishCallResult, atNs: Long) {
        when (result.kind) {
            DishCallKind.STATUS -> if (result.ok) statusConfig = result.response?.dish_get_status?.config
            DishCallKind.CONFIG -> if (result.ok) getConfig = result.response?.dish_get_config?.dish_config
            DishCallKind.DIAGNOSTICS -> if (result.ok) diagnosticsEnabled = result.response?.dish_get_diagnostics?.location?.enabled
            DishCallKind.LOCATION_AUTO -> {
                if (result.httpStatus == 0 || result.refusedByDisablement) return
                val next = LocationRefusal.classify(result, statusConfig, getConfig, diagnosticsEnabled)
                val words = if (next == null) null else (result.grpcMessage ?: result.error)?.take(200)
                if (next == current) {
                    message = words
                    return
                }
                current = next
                sinceNs = if (next != null) atNs else 0L
                message = words
                onChange(next, result, atNs)
            }
            else -> Unit
        }
    }
}

/**
 * The first two links of the dish-location fallback chain (research note 2026-10-27, option 1: `get_location` →
 * `get_diagnostics.location` → phone network fix → dead reckoning): the AUTO answer while `get_location` answers, and
 * while it is refused for one of the three [LocationRefusal] reasons the diagnostics' location (the same solution the
 * dish would have served, polled every second anyway) when it is enabled and not a placeholder. During a speed-policy
 * cut-off the dish withholds that too (`enabled` false, zero coordinates and fix time on every one of 578 such
 * diagnostics of the 2026-10-26 reference drive), so nothing stands in there and the dead reckoning runs as before;
 * while `get_location` answers the diagnostics is never used, so replays of recorded sessions do not change.
 *
 * The diagnostics gives no speed: [Link.fix] carries the chord speed between distinct diagnostics positions over their
 * fix times (`gps_time_s`, else the receipts), NaN for the first one after a gap of more than [MAX_CHORD_S] (the filter
 * drops a fix without a speed and takes the next). Fed every dish call in time order; the app's service and the
 * inertial filter each run one.
 */
class DishLocationChain(onRefusalChange: (LocationRefusal?, DishCallResult, Long) -> Unit = { _, _, _ -> }) {
    val refusals = LocationRefusalTracker(onRefusalChange)
    val refusal: LocationRefusal? get() = refusals.current

    /** The position a call stands for: an AUTO answer, or ([fromDiagnostics]) the diagnostics' location standing in. */
    class Link(val fix: DishFix, val fromDiagnostics: Boolean, val diagnostics: DiagnosticsFix? = null)

    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastGpsS = 0.0
    private var lastNs = Long.MIN_VALUE
    private var lastSpeed = Double.NaN

    /** Every dish call result at its receipt; returns the position this call stands for in the chain, or null. */
    fun onCall(result: DishCallResult, atNs: Long): Link? {
        refusals.onCall(result, atNs)
        return when (result.kind) {
            DishCallKind.LOCATION_AUTO -> result.takeIf { it.ok }?.response?.fix()?.takeIf { !it.looksLikePlaceholder }?.let { Link(it, false) }
            DishCallKind.DIAGNOSTICS -> {
                val d = result.takeIf { it.ok }?.response?.diagnosticsFix() ?: return null
                if (!d.enabled || d.looksLikePlaceholder) {
                    // withheld (a cut-off, the setting off): the next position is not the end of a chord
                    lastLat = Double.NaN
                    lastSpeed = Double.NaN
                    return null
                }
                val speed = chordSpeed(d, atNs)
                if (refusals.current == null) return null
                Link(d.toDishFix(speed), fromDiagnostics = true, diagnostics = d)
            }
            else -> null
        }
    }

    /** The speed of [d]: kept for the same position seen again, the chord over the fix-time difference for a new one. */
    private fun chordSpeed(d: DiagnosticsFix, atNs: Long): Double {
        if (!lastLat.isNaN() && d.lat == lastLat && d.lon == lastLon) return lastSpeed
        var speed = Double.NaN
        if (!lastLat.isNaN()) {
            val dt = if (d.gpsTimeS > 0 && lastGpsS > 0) d.gpsTimeS - lastGpsS else (atNs - lastNs) / 1e9
            if (dt > 0 && dt <= MAX_CHORD_S) {
                val v = Geo.distanceM(lastLat, lastLon, d.lat, d.lon) / dt
                if (v.isFinite() && v <= 90.0) speed = v
            }
        }
        lastLat = d.lat
        lastLon = d.lon
        lastGpsS = d.gpsTimeS
        lastNs = atNs
        lastSpeed = speed
        return speed
    }

    companion object {
        /** Two diagnostics positions further apart in time than this make no chord (a gap, not a motion), s. */
        const val MAX_CHORD_S = 5.0
    }
}
