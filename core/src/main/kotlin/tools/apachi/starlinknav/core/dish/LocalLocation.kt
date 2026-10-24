package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DishConfig

/**
 * What the dish's "location on the local network" setting (`DishConfig.location_request_mode`, LOCAL = 1) comes to for this
 * app, which cannot work without the dish answering `GetLocation`.
 */
enum class LocalLocationState {
    /** The dish reports the setting as LOCAL. */
    ON,

    /** The dish reports the setting as not LOCAL (or, with no setting to read, it refused a position request), and no position was served. */
    OFF,

    /**
     * A position request was answered, so location is available whatever the setting says: either the setting reads
     * as off but the dish answers anyway, or the setting could not be read at all.
     */
    ANSWERS_ANYWAY,

    /** Nothing says: the dish did not give the setting and its answer to a position request means nothing (disabled right now, plan, unreachable). */
    UNKNOWN,
}

/** Where a [LocalLocationReading] came from; the first one that applies wins. */
enum class LocalLocationBasis {
    /** `status.config` (field 2000), which rides along with every status and is there when `dish_get_config` is refused. */
    STATUS_CONFIG,

    /** `dish_get_config` (2011): the fallback when the status carries no config. */
    GET_CONFIG,

    /**
     * No config was readable; the official diagnostics' `location.enabled` ("whether local location queries are enabled for this
     * terminal") says. The read-back where `dish_get_config` is UNIMPLEMENTED and the status carries no config.
     */
    DIAGNOSTICS,

    /** No setting was readable; a position request (AUTO) was answered. */
    AUTO_FIX,

    /** No setting was readable; a position request was refused with a gRPC status, so the setting is probably off. */
    AUTO_REFUSED,

    /** Nothing to go by. */
    NONE,
}

data class LocalLocationReading(
    val state: LocalLocationState,
    val basis: LocalLocationBasis,
    /** The raw `location_request_mode` the dish reported; null when no config could be read. */
    val mode: Int? = null,
    /** The dish is disabled right now (moving too fast for the plan, ...): it refuses position requests whatever the setting. */
    val disabledNow: Boolean = false,
    /** The plan refuses position requests ("Failed to get location: Disabled due to policy"); switching the setting on would not help. */
    val planRestricted: Boolean = false,
) {
    /** Whether location is, as far as can be told, available now: the setting is on, or the dish answers anyway. */
    val available: Boolean get() = state == LocalLocationState.ON || state == LocalLocationState.ANSWERS_ANYWAY

    /** The setting was read from the dish (not inferred from a position answer). */
    val settingRead: Boolean get() = mode != null

    companion object {
        val UNKNOWN = LocalLocationReading(LocalLocationState.UNKNOWN, LocalLocationBasis.NONE)
    }
}

/** Derives the [LocalLocationState] from the dish's answers, without an Android API (so it is tested in core). */
object LocalLocation {
    /**
     * The state of the local-network location setting.
     *
     * The setting is taken from the status' embedded config ([statusConfig], `status.config`, field 2000) first and from a
     * `dish_get_config` answer ([getConfig]) only when the status carries none: the research says `dish_get_config` is
     * UNIMPLEMENTED on non-Priority plans since 2026-05-20, and a status poll already runs anyway. With neither, the
     * diagnostics' `location.enabled` ([diagnosticsEnabled], null = not asked or no answer) is the dish's own statement: true
     * means on, false means off unless something else says more. [auto] is the answer to a position request (AUTO); a real
     * fix means location is available whatever the setting says.
     */
    fun read(statusConfig: DishConfig?, getConfig: DishConfig?, auto: DishCallResult?, diagnosticsEnabled: Boolean? = null): LocalLocationReading {
        val fromStatus = statusConfig?.location_request_mode
        val fromGet = getConfig?.location_request_mode
        val mode = fromStatus ?: fromGet
        val settingBasis = if (fromStatus != null) LocalLocationBasis.STATUS_CONFIG else LocalLocationBasis.GET_CONFIG
        val autoFix = auto != null && auto.ok && auto.response?.fix()?.looksLikePlaceholder == false
        val disabled = auto?.refusedByDisablement == true
        val plan = auto?.outcome == CallOutcome.PLAN_RESTRICTED
        fun reading(state: LocalLocationState, basis: LocalLocationBasis) = LocalLocationReading(state, basis, mode, disabled, plan)
        return when {
            mode == LocationRequestMode.LOCAL -> reading(LocalLocationState.ON, settingBasis)
            mode == null && diagnosticsEnabled == true -> reading(LocalLocationState.ON, LocalLocationBasis.DIAGNOSTICS)
            autoFix -> reading(LocalLocationState.ANSWERS_ANYWAY, if (mode != null) settingBasis else LocalLocationBasis.AUTO_FIX)
            // The config answers even while the dish is disabled, so a setting that is not LOCAL is off whatever AUTO says.
            mode != null -> reading(LocalLocationState.OFF, settingBasis)
            // No setting to read from here on: a refusal means nothing while the dish is disabled or the plan refuses positions.
            disabled || plan -> reading(LocalLocationState.UNKNOWN, LocalLocationBasis.NONE)
            diagnosticsEnabled == false -> reading(LocalLocationState.OFF, LocalLocationBasis.DIAGNOSTICS)
            auto != null && !auto.ok && auto.grpcStatus != null && auto.outcome != CallOutcome.UNIMPLEMENTED ->
                reading(LocalLocationState.OFF, LocalLocationBasis.AUTO_REFUSED)
            else -> reading(LocalLocationState.UNKNOWN, LocalLocationBasis.NONE)
        }
    }
}
