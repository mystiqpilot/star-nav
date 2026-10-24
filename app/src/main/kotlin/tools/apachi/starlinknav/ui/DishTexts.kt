package tools.apachi.starlinknav.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.AlertSeverity
import tools.apachi.starlinknav.core.dish.AttitudeState
import tools.apachi.starlinknav.core.dish.DishLinkState
import tools.apachi.starlinknav.core.dish.EventReason
import tools.apachi.starlinknav.core.dish.HardwareSelfTest
import tools.apachi.starlinknav.core.dish.PowerSource
import tools.apachi.starlinknav.core.dish.RateLimitReason

// Plain words for the codes the dish sends as numbers (core keeps their code names). A code from a newer
// firmware that has no words yet shows its code name: nothing here throws on an unknown code.

@StringRes
fun linkStateRes(s: DishLinkState): Int = when (s) {
    DishLinkState.CONNECTED -> R.string.dish_state_connected
    DishLinkState.SEARCHING -> R.string.dish_state_searching
    DishLinkState.BOOTING -> R.string.dish_state_booting
    DishLinkState.STOWED -> R.string.dish_state_stowed
    DishLinkState.SLEEPING -> R.string.dish_state_sleeping
    DishLinkState.THERMAL_SHUTDOWN -> R.string.dish_state_thermal
    DishLinkState.OBSTRUCTED -> R.string.dish_state_obstructed
    DishLinkState.RF_INHIBITED -> R.string.dish_state_rf_inhibited
    DishLinkState.BUSY -> R.string.dish_state_busy
    DishLinkState.UNKNOWN -> R.string.dish_state_unknown
}

/** An alert key of [tools.apachi.starlinknav.core.dish.AlertList] in plain words; null for a key without words. */
@StringRes
fun alertRes(key: String): Int? = when (key) {
    "motors_stuck" -> R.string.alert_motors_stuck
    "thermal_shutdown" -> R.string.alert_thermal_shutdown
    "thermal_throttle" -> R.string.alert_thermal_throttle
    "unexpected_location" -> R.string.alert_unexpected_location
    "mast_not_near_vertical" -> R.string.alert_mast_not_near_vertical
    "slow_ethernet_speeds" -> R.string.alert_slow_ethernet_speeds
    "slow_ethernet_speeds_100" -> R.string.alert_slow_ethernet_speeds_100
    "install_pending" -> R.string.alert_install_pending
    "is_heating" -> R.string.alert_is_heating
    "power_supply_thermal_throttle" -> R.string.alert_power_supply_thermal_throttle
    "is_power_save_idle" -> R.string.alert_is_power_save_idle
    "dbf_telem_stale" -> R.string.alert_dbf_telem_stale
    "low_motor_current" -> R.string.alert_low_motor_current
    "lower_signal_than_predicted" -> R.string.alert_lower_signal_than_predicted
    "obstruction_map_reset" -> R.string.alert_obstruction_map_reset
    "dish_water_detected" -> R.string.alert_dish_water_detected
    "router_water_detected" -> R.string.alert_router_water_detected
    "upsu_router_port_slow" -> R.string.alert_upsu_router_port_slow
    "no_ethernet_link" -> R.string.alert_no_ethernet_link
    "obstructed" -> R.string.alert_obstructed
    "hardware_self_test_failed" -> R.string.alert_hardware_self_test_failed
    "overage_rate_limited" -> R.string.alert_overage_rate_limited
    else -> null
}

@Composable
fun alertText(key: String): String = alertRes(key)?.let { stringResource(it) } ?: key.replace('_', ' ')

@StringRes
fun severityRes(s: AlertSeverity): Int = when (s) {
    AlertSeverity.WARNING -> R.string.severity_warning
    AlertSeverity.CAUTION -> R.string.severity_caution
    AlertSeverity.ADVISORY -> R.string.severity_advisory
}

/** UXEvent.severity (1 warning, 2 caution, 3 advisory). */
@StringRes
fun eventSeverityRes(code: Int): Int = when (code) {
    1 -> R.string.severity_warning
    2 -> R.string.severity_caution
    else -> R.string.severity_advisory
}

/** DishOutage.Cause in plain words; null for a cause this app has no words for. */
@StringRes
fun outageCauseRes(cause: Int): Int? = when (cause) {
    0 -> R.string.outage_unknown
    1 -> R.string.outage_booting
    2 -> R.string.outage_stowed
    3 -> R.string.outage_thermal_shutdown
    4 -> R.string.outage_no_schedule
    5 -> R.string.outage_no_sats
    6 -> R.string.outage_obstructed
    7 -> R.string.outage_no_downlink
    8 -> R.string.outage_no_pings
    9 -> R.string.outage_actuator_activity
    10 -> R.string.outage_cable_test
    11 -> R.string.outage_sleeping
    13 -> R.string.outage_sky_search
    14 -> R.string.outage_inhibit_rf
    else -> null
}

@Composable
fun outageCauseText(cause: Int): String =
    outageCauseRes(cause)?.let { stringResource(it) } ?: stringResource(R.string.outage_other, cause.toString())

/**
 * UXEvent.reason (EventReason 0..53) in plain words. Every reason the dish itself sends (its outages, the dish's
 * alerts, packet loss) has words in English and Ukrainian; a router or Wi-Fi client event shows its code name in a
 * generic sentence ([eventReasonText]).
 */
@StringRes
fun eventReasonRes(code: Int): Int? = when (code) {
    0 -> R.string.event_unknown
    1 -> R.string.outage_unknown
    2 -> R.string.outage_booting
    3 -> R.string.outage_stowed
    4 -> R.string.outage_thermal_shutdown
    5 -> R.string.outage_no_schedule
    6 -> R.string.outage_no_sats
    7 -> R.string.outage_obstructed
    8 -> R.string.outage_no_downlink
    9 -> R.string.outage_no_pings
    10 -> R.string.outage_sleeping
    11 -> R.string.outage_moving_not_allowed
    12 -> R.string.event_high_downlink_packet_loss
    19 -> R.string.outage_sky_search
    22 -> R.string.event_rain_snr_low
    23 -> R.string.event_eth_no_link
    24 -> R.string.event_eth_slow_link
    25 -> R.string.event_eth_slow_link_100
    50 -> R.string.outage_inhibit_rf
    52 -> R.string.event_user_reported_issue
    else -> null
}

@Composable
fun eventReasonText(code: Int): String =
    eventReasonRes(code)?.let { stringResource(it) } ?: stringResource(R.string.event_other, EventReason.name(code))

@StringRes
fun initStageRes(key: String): Int? = when (key) {
    "attitude_initialization" -> R.string.init_attitude_initialization
    "burst_detected" -> R.string.init_burst_detected
    "ekf_converged" -> R.string.init_ekf_converged
    "first_cplane" -> R.string.init_first_cplane
    "first_pop_ping" -> R.string.init_first_pop_ping
    "gps_valid" -> R.string.init_gps_valid
    "initial_network_entry" -> R.string.init_initial_network_entry
    "network_schedule" -> R.string.init_network_schedule
    "rf_ready" -> R.string.init_rf_ready
    "stable_connection" -> R.string.init_stable_connection
    else -> null
}

@Composable
fun initStageText(key: String): String = initStageRes(key)?.let { stringResource(it) } ?: key.replace('_', ' ')

@StringRes
fun rateLimitRes(code: Int): Int? = when (code) {
    RateLimitReason.NO_LIMIT -> R.string.limit_none
    2 -> R.string.limit_policy
    3 -> R.string.limit_user
    5 -> R.string.limit_overage
    6 -> R.string.limit_low_speed
    else -> null
}

/** Why the plan restricts bandwidth, in plain words ("-" when the dish says nothing). */
@Composable
fun rateLimitText(code: Int): String =
    if (code == 0) "-" else rateLimitRes(code)?.let { stringResource(it) } ?: RateLimitReason.name(code)

@Composable
fun mobilityText(code: Int): String = when (code) {
    0 -> stringResource(R.string.mobility_stationary)
    1 -> stringResource(R.string.mobility_nomadic)
    2 -> stringResource(R.string.mobility_mobile)
    else -> "-"
}

@Composable
fun attitudeStateText(code: Int): String = when (code) {
    0 -> stringResource(R.string.attitude_reset)
    1 -> stringResource(R.string.attitude_unconverged)
    AttitudeState.CONVERGED -> stringResource(R.string.attitude_converged)
    3 -> stringResource(R.string.attitude_faulted)
    4 -> stringResource(R.string.attitude_invalid)
    else -> AttitudeState.name(code)
}

@Composable
fun selfTestText(code: Int): String = when (code) {
    HardwareSelfTest.PASSED -> stringResource(R.string.selftest_passed)
    HardwareSelfTest.FAILED -> stringResource(R.string.selftest_failed)
    else -> stringResource(R.string.selftest_none)
}

@Composable
fun powerSourceText(code: Int): String = when (code) {
    1 -> stringResource(R.string.power_source_usbc)
    2 -> stringResource(R.string.power_source_battery)
    3 -> stringResource(R.string.power_source_usbc_battery)
    else -> PowerSource.name(code)
}

/** The class of service by its product name (UNKNOWN, CONSUMER, BUSINESS, ...), spaced. */
fun classOfServiceText(name: String): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
