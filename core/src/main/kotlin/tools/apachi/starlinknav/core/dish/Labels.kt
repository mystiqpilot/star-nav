package tools.apachi.starlinknav.core.dish

/*
 * Code names for the enum values the dish and the router send as plain numbers (the proto subset
 * keeps every enum-typed field int32 so values from newer firmware survive). These are the
 * firmware's names, not user-facing text: the app maps them to strings. Unknown codes stay
 * printable as "<PREFIX>_<n>".
 */

private fun label(names: Map<Int, String>, code: Int, prefix: String): String = names[code] ?: "${prefix}_$code"

/** UXEvent.reason (EventReason 0..53 of firmware 2026.09.18). */
object EventReason {
    private val names = mapOf(
        0 to "UNKNOWN", 1 to "OUTAGE_UNKNOWN", 2 to "OUTAGE_BOOTING", 3 to "OUTAGE_STOWED", 4 to "OUTAGE_THERMAL_SHUTDOWN",
        5 to "OUTAGE_NO_SCHEDULE", 6 to "OUTAGE_NO_SATS", 7 to "OUTAGE_OBSTRUCTED", 8 to "OUTAGE_NO_DOWNLINK",
        9 to "OUTAGE_NO_PINGS", 10 to "OUTAGE_SLEEPING", 11 to "OUTAGE_MOVING_WHILE_NOT_ALLOWED",
        12 to "HIGH_DOWNLINK_PACKET_LOSS", 13 to "ROUTER_FACTORY_RESET", 14 to "ROUTER_REBOOT", 15 to "ROUTER_POWER_CYCLE",
        16 to "ROUTER_CONFIG_UPDATE", 17 to "ROUTER_SOFTWARE_UPDATE", 18 to "ROUTER_THERMAL_THROTTLED", 19 to "OUTAGE_SKY_SEARCH",
        20 to "ROUTER_REBOOT_CONFIG_UPDATE", 21 to "ROUTER_PUBLIC_IPV4_CHANGE", 22 to "UT_ALERT_RAIN_SNR_PERSISTENTLY_LOW",
        23 to "UT_ALERT_ETH_NO_LINK", 24 to "UT_ALERT_ETH_SLOW_LINK", 25 to "UT_ALERT_ETH_SLOW_LINK_100",
        26 to "ROUTER_POP_IPV4_PING_DROP", 27 to "ROUTER_POP_IPV6_PING_DROP", 28 to "ROUTER_LAN_ETH_HIGH_PACKET_LOSS",
        29 to "ROUTER_WAN_ETH_HIGH_PACKET_LOSS", 30 to "ROUTER_DHCP_SERVER_FAIL_TO_START", 31 to "ROUTER_DHCP_SERVER_EXHAUSTED",
        32 to "ROUTER_DNS_IPV4_SERVER_FAIL_TO_START", 33 to "ROUTER_DNS_IPV6_SERVER_FAIL_TO_START",
        34 to "ROUTER_SET_CUSTOM_DNS_DISALLOWED", 35 to "ROUTER_SET_CUSTOM_DNS_ALLOWED", 36 to "ROUTER_MAX_WIRELESS_CLIENTS",
        37 to "ROUTER_MAX_5GHIGH_WIRELESS_CLIENTS", 38 to "ROUTER_MESH_TOPOLOGY_CHANGING_OFTEN", 39 to "ROUTER_CONNTRACK_FULL",
        40 to "ROUTER_DISH_PING_DROP", 41 to "ROUTER_UT_CONNECTED_TO_LAN", 42 to "CLIENT_RECONNECTING_OFTEN",
        43 to "CLIENT_SWITCHING_BAND", 44 to "CLIENT_SWITCHING_UPSTREAM_MAC", 45 to "ROUTER_MESH_CONNECTION_CHANGING",
        46 to "MESH_BACKHAUL_LOW_PHY", 47 to "ROUTER_HIGH_OVERLAPPING_BSS", 48 to "CLIENT_EXCESSIVE_NETWORK_CONNECTIONS",
        49 to "ROUTER_LAN_ETH_LOW_SPEED", 50 to "OUTAGE_INHIBIT_RF", 51 to "ROUTER_MESH_BACKHAUL_FLAPPING",
        52 to "UT_ALERT_USER_REPORTED_ISSUE", 53 to "ROUTER_WAN_ETH_DOWN_FDIR",
    )

    /** All named codes, 0..53. */
    val codes: Set<Int> get() = names.keys

    fun name(code: Int): String = label(names, code, "EVENT_REASON")

    enum class Scope { DISH, ROUTER, CLIENT, UNKNOWN }

    /** Whose event it is: outages and UT alerts are the dish's, ROUTER_* the router's, CLIENT_* a Wi-Fi client's. */
    fun scope(code: Int): Scope {
        val n = names[code] ?: return Scope.UNKNOWN
        return when {
            n.startsWith("OUTAGE_") || n.startsWith("UT_ALERT_") || n == "HIGH_DOWNLINK_PACKET_LOSS" -> Scope.DISH
            n.startsWith("ROUTER_") || n == "MESH_BACKHAUL_LOW_PHY" -> Scope.ROUTER
            n.startsWith("CLIENT_") -> Scope.CLIENT
            else -> Scope.UNKNOWN
        }
    }

    /** An outage of the dish's link (OUTAGE_*), as opposed to an alert or a router event. */
    fun isOutage(code: Int): Boolean = names[code]?.startsWith("OUTAGE_") == true

    /** The DishOutage.Cause an OUTAGE_* event reason corresponds to; null for other reasons. */
    fun outageCause(code: Int): Int? = when (code) {
        1 -> 0
        2 -> 1
        3 -> 2
        4 -> 3
        5 -> 4
        6 -> 5
        7 -> 6
        8 -> 7
        9 -> 8
        10 -> 11
        19 -> 13
        50 -> 14
        else -> null
    }
}

/** UXEvent.severity. */
object EventSeverity {
    private val names = mapOf(0 to "UNKNOWN", 1 to "WARNING", 2 to "CAUTION", 3 to "ADVISORY")
    fun name(code: Int): String = label(names, code, "SEVERITY")
}

/** DishGetStatusResponse.has_actuators / AlignmentStats.has_actuators. A Mini has no motors. */
object HasActuators {
    const val UNKNOWN = 0
    const val YES = 1
    const val NO = 2
    fun name(code: Int): String = label(mapOf(0 to "UNKNOWN", 1 to "YES", 2 to "NO"), code, "ACTUATORS")
}

/** WifiConfig.Band (BasicServiceSet.band, RadioStats.band). */
object WifiBand {
    const val RF_2GHZ = 2
    const val RF_5GHZ = 5
    const val RF_5GHZ_HIGH = 6
    private val names = mapOf(0 to "UNKNOWN", 2 to "RF_2GHZ", 5 to "RF_5GHZ", 6 to "RF_5GHZ_HIGH")
    fun name(code: Int): String = label(names, code, "BAND")

    /** WirelessBand of a network interface link: 1 = 2.4 GHz, 2 = 5 GHz. */
    fun wirelessName(code: Int): String = label(mapOf(0 to "UNKNOWN", 1 to "RF_2GHZ", 2 to "RF_5GHZ"), code, "WIRELESS_BAND")

    /** WifiClient.Interface: how a client is connected. */
    fun clientInterfaceName(code: Int): String =
        label(mapOf(0 to "UNKNOWN", 1 to "ETH", 2 to "RF_2GHZ", 3 to "RF_5GHZ", 4 to "RF_5GHZ_HIGH"), code, "IFACE")
}

/** RouterRole (GetStatusRequest.router_role, RouterInfo.role in the dish's downstream_routers). */
object RouterRole {
    const val UNSPECIFIED = 0
    const val CONTROLLER = 1
    const val REPEATER = 2
    const val BYPASSED = 3
    fun name(code: Int): String = label(mapOf(0 to "UNSPECIFIED", 1 to "CONTROLLER", 2 to "REPEATER", 3 to "BYPASSED"), code, "ROLE")
}

/** WifiClient.Role. */
object ClientRole {
    fun name(code: Int): String = label(mapOf(0 to "UNKNOWN", 1 to "CLIENT", 2 to "REPEATER", 3 to "CONTROLLER"), code, "CLIENT_ROLE")
}

/** WifiSetupRequirementState of the router's first-run setup. */
object SetupRequirement {
    const val NOT_REQUIRED = 0
    private val names = mapOf(0 to "NOT_REQUIRED", 1 to "REQUIRED_COUNTDOWN", 2 to "REQUIRED_PAUSED", 3 to "REQUIRED_COMPLETE")
    fun name(code: Int): String = label(names, code, "SETUP")

    /** The router still wants its first-run setup. */
    fun isRequired(code: Int): Boolean = code != NOT_REQUIRED && code in names
}

/** WifiSoftwareUpdateState of the router's update. */
object WifiSoftwareUpdate {
    const val NOT_RUN = 0
    const val NO_UPDATE_REQUIRED = 4
    const val REBOOT_PENDING = 5
    private val names = mapOf(
        0 to "NOT_RUN", 1 to "GETTING_TARGET_VERSION", 2 to "DOWNLOADING_UPDATE_IMAGE", 3 to "FLASHING", 4 to "NO_UPDATE_REQUIRED",
        5 to "REBOOT_PENDING", 6 to "GETTING_TARGET_VERSION_FAILED", 7 to "GETTING_TARGET_VERSION_EXHAUSTED",
        8 to "NO_VALID_ARTIFACT", 9 to "ILLEGAL_ARTIFACT", 10 to "DOWNLOADING_UPDATE_IMAGE_FAILED",
        11 to "DOWNLOADING_UPDATE_IMAGE_EXHAUSTED", 12 to "FLASHING_FAILED",
    )
    fun name(code: Int): String = label(names, code, "WIFI_UPDATE_STATE")
    fun isFailure(code: Int): Boolean = code in 6..12
}

/** DishGetDiagnosticsResponse.hardware_self_test and hardware_self_test_codes. */
object HardwareSelfTest {
    private val results = mapOf(0 to "NO_RESULT", 1 to "PASSED", 2 to "FAILED")
    private val codes = mapOf(
        0 to "GENERAL", 1 to "BOOT_UP", 2 to "CPU_VOLTAGE", 3 to "DBF_AAP_CS", 4 to "DBF_NUM_FEMS", 5 to "DBF_READ_ERRORS",
        6 to "DBF_T_DIE_0", 7 to "DBF_T_DIE_1", 8 to "DBF_T_DIE_0_VALID", 9 to "DBF_T_DIE_1_VALID", 10 to "ETH_PRIME",
        11 to "EIRP", 12 to "FEM_CUT", 13 to "FUSE_AVS", 14 to "GPS", 15 to "IMU", 16 to "PHY", 17 to "SCP_ERROR",
        18 to "TEMPERATURE", 19 to "VTSENS",
    )
    const val PASSED = 1
    const val FAILED = 2
    fun resultName(code: Int): String = label(results, code, "TEST_RESULT")
    fun codeName(code: Int): String = label(codes, code, "TEST_CODE")
}

/** DishBatteryStats.power_source (the Mini with an internal battery). */
object PowerSource {
    fun name(code: Int): String = label(mapOf(0 to "UNKNOWN", 1 to "USBC", 2 to "BATTERY", 3 to "USBC_AND_BATTERY"), code, "POWER_SOURCE")
}

object NatFlag {
    fun name(code: Int): String = label(mapOf(0 to "UNKNOWN", 1 to "DISABLED", 2 to "ENABLED"), code, "NAT")
}

object AccountShard {
    fun name(code: Int): String = label(mapOf(0 to "UNKNOWN", 1 to "DEFAULT", 2 to "INDIA", 3 to "STARSHIELD"), code, "SHARD")
}

/** RateLimitReason of dl/ul_bandwidth_restricted_reason: why the plan restricts bandwidth. */
object RateLimitReason {
    const val NO_LIMIT = 1
    private val names = mapOf(
        0 to "UNKNOWN", 1 to "NO_LIMIT", 2 to "POLICY_LIMIT", 3 to "USER_CUSTOM_LIMIT", 5 to "OVERAGE_LIMIT", 6 to "LOW_SPEED_POLICY_LIMIT",
    )
    fun name(code: Int): String = label(names, code, "RATE_LIMIT")

    /** The plan restricts bandwidth now (anything but unknown / no limit). */
    fun isRestricted(code: Int): Boolean = code != 0 && code != NO_LIMIT
}

object SpeedtestError {
    fun name(code: Int): String = label(
        mapOf(0 to "NONE", 1 to "UNKNOWN", 2 to "TOKEN", 3 to "API", 4 to "NO_RESULT", 5 to "OFFLINE"), code, "SPEEDTEST_ERROR",
    )
}

object Duplex {
    fun name(code: Int): String = label(mapOf(0 to "UNKNOWN", 1 to "HALF", 2 to "FULL"), code, "DUPLEX")
}
