package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.AuthOpen
import tools.apachi.starlinknav.core.dish.proto.AuthWpa2
import tools.apachi.starlinknav.core.dish.proto.AuthWpa2Wpa3
import tools.apachi.starlinknav.core.dish.proto.AuthWpa3
import tools.apachi.starlinknav.core.dish.proto.ClientConfig
import tools.apachi.starlinknav.core.dish.proto.ClientName
import tools.apachi.starlinknav.core.dish.proto.DeviceRequest
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishConfig
import tools.apachi.starlinknav.core.dish.proto.DishFactoryResetRequest
import tools.apachi.starlinknav.core.dish.proto.DishPowerSaveRequest
import tools.apachi.starlinknav.core.dish.proto.DishSetConfigRequest
import tools.apachi.starlinknav.core.dish.proto.FactoryResetRequest
import tools.apachi.starlinknav.core.dish.proto.PingHostRequest
import tools.apachi.starlinknav.core.dish.proto.RebootRequest
import tools.apachi.starlinknav.core.dish.proto.StartSpeedtestRequest
import tools.apachi.starlinknav.core.dish.proto.UpdateRequest
import tools.apachi.starlinknav.core.dish.proto.WeeklyBlockSchedule
import tools.apachi.starlinknav.core.dish.proto.WifiConfig
import tools.apachi.starlinknav.core.dish.proto.WifiSetClientGivenNameRequest
import tools.apachi.starlinknav.core.dish.proto.WifiSetConfigRequest

/**
 * A command with parameters. Parameterless commands are plain [DishCallKind]s sent with
 * [DishClient.call]; both kinds of command are sent only on an explicit user action (never by the
 * probe or a poller), and each result is a [DishCallResult] logged with [kind].
 */
sealed interface DeviceCommand {
    val kind: DishCallKind
    val risk: CommandRisk get() = kind.risk ?: CommandRisk.DISRUPTIVE
    fun request(id: Long): DeviceRequest
}

/**
 * The router's Wi-Fi config exactly as the router sent it: passwords, secrets and the fields this app does
 * not know included. The only base a config write ([RouterCommand.SetConfig], [RouterCommand.BlockClient],
 * [RouterCommand.SetClientGivenName]) accepts, because a write starts from the router's own state: a redacted
 * copy (probe report, session log, Debug data, [Redact]) has empty passwords and has lost the unknown fields,
 * and sending its networks back would clear passwords and wipe settings.
 *
 * Held in memory only; [toString] prints no config. Build it with [of] from an unredacted call result.
 */
class RouterConfigRead private constructor(val config: WifiConfig) {
    override fun toString() = "RouterConfigRead(${config.networks.size} networks, secrets hidden)"

    companion object {
        /**
         * The config of a router reply that was not redacted ([DishCallResult.payloadRedacted] false: straight from
         * [RouterClient.call], not from `.redacted()`, `fromRecord` or a Debug data snapshot); null otherwise.
         */
        fun of(result: DishCallResult): RouterConfigRead? {
            if (result.kind.target != CallTarget.ROUTER || !result.ok || result.payloadRedacted) return null
            return of(RouterCommand.configOf(result.response) ?: return null)
        }

        /** Also refuses a config that looks redacted (a WPA or RADIUS network with an empty password). */
        internal fun of(config: WifiConfig): RouterConfigRead? = if (looksRedacted(config)) null else RouterConfigRead(config)

        internal fun looksRedacted(c: WifiConfig): Boolean = c.networks.any { n ->
            n.basic_service_sets.any { b ->
                (b.auth_wpa2 != null && b.auth_wpa2.password.isEmpty()) ||
                    (b.auth_wpa3 != null && b.auth_wpa3.password.isEmpty()) ||
                    (b.auth_wpa2_wpa3 != null && b.auth_wpa2_wpa3.password.isEmpty()) ||
                    (b.auth_radius != null && b.auth_radius.password.isEmpty())
            }
        }
    }
}

object SnowMeltMode {
    const val AUTO = 0
    const val ALWAYS_ON = 1
    const val ALWAYS_OFF = 2
    fun name(code: Int): String = when (code) {
        AUTO -> "AUTO"
        ALWAYS_ON -> "ALWAYS_ON"
        ALWAYS_OFF -> "ALWAYS_OFF"
        else -> "SNOW_MELT_$code"
    }
}

object LevelDishMode {
    const val TILT_LIKE_NORMAL = 0
    const val FORCE_LEVEL = 1
    fun name(code: Int): String = when (code) {
        TILT_LIKE_NORMAL -> "TILT_LIKE_NORMAL"
        FORCE_LEVEL -> "FORCE_LEVEL"
        else -> "LEVEL_MODE_$code"
    }
}

object LocationRequestMode {
    const val NONE = 0
    const val LOCAL = DishCallKind.LOCATION_MODE_LOCAL
    fun name(code: Int): String = when (code) {
        NONE -> "NONE"
        LOCAL -> "LOCAL"
        else -> "LOCATION_MODE_$code"
    }
}

/** Commands for the dish (192.168.100.1). */
sealed class DishCommand : DeviceCommand {
    /** The sleep schedule: dish_power_save. Duration 0 is rejected by the dish even when disabling, so [SleepWindow] never has one. */
    class SleepSchedule(val window: SleepWindow) : DishCommand() {
        override val kind get() = DishCallKind.SLEEP_SCHEDULE
        override fun request(id: Long) = DeviceRequest(
            id = id,
            dish_power_save = DishPowerSaveRequest(
                power_save_start_minutes = window.startUtcMinutes,
                power_save_duration_minutes = window.durationMinutes,
                enable_power_save = window.enabled,
            ),
        )
    }

    /**
     * dish_set_config: only the settings given are changed (each with its apply flag). A Mini probably
     * refuses this as account-only (PERMISSION_DENIED); the app shows the dish's answer.
     */
    class SetConfig(
        val snowMeltMode: Int? = null,
        val levelDishMode: Int? = null,
        /** Hour of day the dish may restart to install an update (timezone unverified). */
        val swupdateRebootHour: Int? = null,
        val swupdateThreeDayDeferral: Boolean? = null,
        val locationRequestMode: Int? = null,
    ) : DishCommand() {
        init {
            require(
                snowMeltMode != null || levelDishMode != null || swupdateRebootHour != null ||
                    swupdateThreeDayDeferral != null || locationRequestMode != null,
            ) { "nothing to change" }
            snowMeltMode?.let { require(it in 0..2) { "snow melt mode $it" } }
            levelDishMode?.let { require(it in 0..1) { "level dish mode $it" } }
            swupdateRebootHour?.let { require(it in 0..23) { "update hour $it" } }
            locationRequestMode?.let { require(it in 0..1) { "location request mode $it" } }
        }

        override val kind get() = DishCallKind.DISH_SET_CONFIG

        /**
         * The settings can be set back, except that no longer answering location requests from the local network cuts
         * the position feed of this app (and of any other client of the dish): that one is disruptive.
         */
        override val risk: CommandRisk
            get() = if (locationRequestMode == LocationRequestMode.NONE) CommandRisk.DISRUPTIVE else CommandRisk.REVERSIBLE

        override fun request(id: Long) = DeviceRequest(id = id, dish_set_config = DishSetConfigRequest(config()))

        /** The DishConfig carrying just the changes. */
        fun config() = DishConfig(
            snow_melt_mode = snowMeltMode ?: 0, apply_snow_melt_mode = snowMeltMode != null,
            level_dish_mode = levelDishMode ?: 0, apply_level_dish_mode = levelDishMode != null,
            swupdate_reboot_hour = swupdateRebootHour ?: 0, apply_swupdate_reboot_hour = swupdateRebootHour != null,
            swupdate_three_day_deferral_enabled = swupdateThreeDayDeferral ?: false,
            apply_swupdate_three_day_deferral_enabled = swupdateThreeDayDeferral != null,
            location_request_mode = locationRequestMode ?: 0, apply_location_request_mode = locationRequestMode != null,
        )
    }

    /**
     * update: check for / take a waiting software update; [scheduleReboot] is sent only when not null
     * (semantics unverified). Only `true` restarts the dish, so only that one has the restarting kind the
     * reboot tracker counts as "restarted by Star Nav".
     */
    class Update(val scheduleReboot: Boolean? = null) : DishCommand() {
        override val kind get() = if (scheduleReboot == true) DishCallKind.DISH_UPDATE_REBOOT else DishCallKind.DISH_UPDATE
        override fun request(id: Long) = DeviceRequest(id = id, update = UpdateRequest(schedule_reboot = scheduleReboot))
    }

    /** The dish pings [address] (the dish answers only if it implements ping_host). */
    class PingHost(val address: String, val size: Int = 0) : DishCommand() {
        init { requireHostName(address); require(size in 0..65_000) { "ping size $size" } }
        override val kind get() = DishCallKind.PING_HOST
        override fun request(id: Long) = DeviceRequest(id = id, ping_host = PingHostRequest(address = address, size = size))
    }

    /** dish_factory_reset: wipes the dish's settings. Destructive: the app makes the user type the word. */
    object FactoryReset : DishCommand() {
        override val kind get() = DishCallKind.DISH_FACTORY_RESET
        override fun request(id: Long) = DeviceRequest(id = id, dish_factory_reset = DishFactoryResetRequest())
    }
}

/** Commands for the router (the Wi-Fi gateway). */
sealed class RouterCommand : DeviceCommand {
    /** The router restarts: its Wi-Fi, and so the phone's link to the dish, is gone until it is back. */
    object Reboot : RouterCommand() {
        override val kind get() = DishCallKind.ROUTER_REBOOT
        override fun request(id: Long) = DeviceRequest(id = id, reboot = RebootRequest())
    }

    /** factory_reset: wipes SSID, password and bypass mode. Destructive. */
    object FactoryReset : RouterCommand() {
        override val kind get() = DishCallKind.ROUTER_FACTORY_RESET
        override fun request(id: Long) = DeviceRequest(id = id, factory_reset = FactoryResetRequest())
    }

    class Update(val scheduleReboot: Boolean? = null) : RouterCommand() {
        override val kind get() = DishCallKind.ROUTER_UPDATE
        override fun request(id: Long) = DeviceRequest(id = id, update = UpdateRequest(schedule_reboot = scheduleReboot))
    }

    class PingHost(val address: String, val size: Int = 0) : RouterCommand() {
        init { requireHostName(address); require(size in 0..65_000) { "ping size $size" } }
        override val kind get() = DishCallKind.ROUTER_PING_HOST
        override fun request(id: Long) = DeviceRequest(id = id, ping_host = PingHostRequest(address = address, size = size))
    }

    /** A speed test costs plan data. It never reports to Starlink (send_telemetry is always false). */
    class StartSpeedtest(val durationS: Int = 10) : RouterCommand() {
        init { require(durationS in 1..120) { "speed test duration $durationS" } }
        override val kind get() = DishCallKind.ROUTER_START_SPEEDTEST
        override fun request(id: Long) = DeviceRequest(
            id = id, start_speedtest = StartSpeedtestRequest(duration_s = durationS, send_telemetry = false),
        )
    }

    /**
     * Names a client; the router answers with an empty reply when it accepts. The client's entry in
     * [base] (its pause schedule, group) is sent along with the new name, so a router that replaces the
     * whole entry does not resume a paused client.
     */
    class SetClientGivenName(val base: RouterConfigRead, val mac: String, val givenName: String, val clientId: Int = 0) : RouterCommand() {
        init {
            requireMac(mac)
            require(givenName.length in 0..64) { "client name too long" }
        }

        override val kind get() = DishCallKind.ROUTER_SET_CLIENT_NAME

        fun clientConfig(): ClientConfig {
            val existing = base.config.client_configs.firstOrNull { it.mac_address.equals(mac, ignoreCase = true) }
            return (existing ?: ClientConfig(client_id = clientId, mac_address = mac)).copy(given_name = givenName)
        }

        override fun request(id: Long) = DeviceRequest(
            id = id,
            wifi_set_client_given_name = WifiSetClientGivenNameRequest(
                client_name = ClientName(mac_address = mac, given_name = givenName),
                client_config = clientConfig(),
            ),
        )
    }

    /**
     * Pauses ([block]) or resumes a client through wifi_set_config.client_configs. [base] is the config
     * read from the router (unredacted): the existing entries are kept, only this client's entry changes. Refuses to
     * pause [ownMac] (the phone running the app would cut itself off).
     */
    class BlockClient(
        val base: RouterConfigRead,
        val mac: String,
        val block: Boolean,
        val clientId: Int = 0,
        val ownMac: String? = null,
    ) : RouterCommand() {
        init {
            requireMac(mac)
            require(!(block && ownMac != null && ownMac.equals(mac, ignoreCase = true))) { "refusing to pause this phone itself" }
        }

        override val kind get() = DishCallKind.ROUTER_BLOCK_CLIENT

        /** Pausing blocks the whole week (minutes since the start of the week; the unit is unverified). */
        fun config(): WifiConfig {
            val configs = base.config.client_configs
            val existing = configs.firstOrNull { it.mac_address.equals(mac, ignoreCase = true) }
            val updated = (existing ?: ClientConfig(client_id = clientId, mac_address = mac)).copy(
                weekly_block_schedules = if (block) {
                    listOf(WeeklyBlockSchedule(block_ranges = listOf(WeeklyBlockSchedule.BlockRange(0, WEEK_MINUTES))))
                } else {
                    emptyList()
                },
            )
            val others = configs.filterNot { it.mac_address.equals(mac, ignoreCase = true) }
            return WifiConfig(client_configs = others + updated, apply_client_configs = true)
        }

        override fun request(id: Long) = DeviceRequest(id = id, wifi_set_config = WifiSetConfigRequest(config()))

        companion object { const val WEEK_MINUTES = 7 * 24 * 60 }
    }

    /**
     * wifi_set_config built read-modify-write: [base] is the config read from the router, unredacted (it may
     * hold fields this app does not know; Wire keeps them inside the messages that are copied). The request
     * is a fresh WifiConfig that carries only what the [edits] rewrite, each with its apply flag, so an
     * apply flag that happened to be set in the reading cannot change anything else. The router's
     * answer holds the new config (with passwords: never log it, see [Redact]).
     */
    class SetConfig(val base: RouterConfigRead, val edits: List<WifiConfigEdit>) : RouterCommand() {
        init { require(edits.isNotEmpty()) { "nothing to change" } }

        override val kind get() = DishCallKind.ROUTER_SET_CONFIG

        /** Bypass mode needs the reset button to leave: that edit is destructive. */
        override val risk: CommandRisk get() = riskOf(edits)

        /** True when the edits can drop the phone from the Wi-Fi it is on (names, password, bands, subnet, bypass). */
        val dropsWifiClients: Boolean get() = edits.any { it.dropsWifiClients }

        /** What the phone has to do after these edits went through. */
        val afterwards: WifiAfterwards get() = afterwardsOf(edits)

        companion object {
            /**
             * The risk of a config write with [edits], known before the config is read and the command built (the
             * confirmation dialog needs it first): every Wi-Fi config write is at least disruptive, switching bypass
             * mode on is destructive.
             */
            fun riskOf(edits: List<WifiConfigEdit>): CommandRisk =
                if (edits.any { it is WifiConfigEdit.SetBypassMode && it.enabled }) CommandRisk.DESTRUCTIVE else CommandRisk.DISRUPTIVE

            /** What the phone is told to do after [edits]: join under the new name or password, rejoin, or nothing. */
            fun afterwardsOf(edits: List<WifiConfigEdit>): WifiAfterwards = when {
                edits.any { it is WifiConfigEdit.SetBypassMode && it.enabled } -> WifiAfterwards.WIFI_OFF
                edits.any { (it is WifiConfigEdit.SetNetworkName && it.which != WifiConfigEdit.Which.GUEST) || (it is WifiConfigEdit.SetPassword && it.which != WifiConfigEdit.Which.GUEST) } ->
                    WifiAfterwards.JOIN_AGAIN_NEW_CREDENTIALS
                edits.any { it.dropsWifiClients } -> WifiAfterwards.MAY_DROP
                else -> WifiAfterwards.NOTHING
            }
        }

        fun config(): WifiConfig = edits.fold(WifiConfig()) { out, edit -> edit.apply(base.config, out) }

        override fun request(id: Long) = DeviceRequest(id = id, wifi_set_config = WifiSetConfigRequest(config()))
    }

    companion object {
        /** The WifiConfig a router reply carries: wifi_get_config, wifi_get_status.config or the answer to a set. */
        fun configOf(response: DeviceResponse?): WifiConfig? =
            response?.wifi_get_config?.wifi_config ?: response?.wifi_get_status?.config ?: response?.wifi_set_config?.updated_wifi_config
    }
}

/** What a Wi-Fi config write asks of the phone afterwards (the app's notice after the router accepted it). */
enum class WifiAfterwards {
    /** The phone is not affected. */
    NOTHING,

    /** The Wi-Fi may restart or move (DNS, LAN subnet, hidden name, a band off): the phone may drop and has to rejoin. */
    MAY_DROP,

    /** The network's name or password changed: the phone must join it again under the new name or with the new password. */
    JOIN_AGAIN_NEW_CREDENTIALS,

    /** Bypass mode on: the Wi-Fi is off and only the reset button on the Mini brings it back. */
    WIFI_OFF,
}

/** One change to the router's Wi-Fi configuration, applied to a request config built from the router's own. */
sealed interface WifiConfigEdit {
    val dropsWifiClients: Boolean get() = false

    /** Adds this edit's changes (with apply flags) to [out], reading the router's current state from [base]. */
    fun apply(base: WifiConfig, out: WifiConfig): WifiConfig

    /** Which Wi-Fi network of the config an edit targets. */
    enum class Which { MAIN, GUEST, ALL }

    /** Changes the name of the network (all its bands, or only [band]). */
    class SetNetworkName(val name: String, val band: Int? = null, val which: Which = Which.MAIN) : WifiConfigEdit {
        init { require(name.encodeToByteArray().size in 1..32) { "a Wi-Fi name is 1..32 bytes" } }
        override val dropsWifiClients get() = which != Which.GUEST
        override fun apply(base: WifiConfig, out: WifiConfig) = out.editBss(base, which, band) { it.copy(ssid = name) }
    }

    /**
     * Sets the password of the network's bands. An open band becomes WPA2/WPA3. The BSSID is cleared when
     * the password changes, as the working cloud example does (the router assigns it again).
     */
    class SetPassword(val password: String, val which: Which = Which.MAIN) : WifiConfigEdit {
        init { require(WifiInput.passwordValid(password)) { "a Wi-Fi password is 8..63 printable ASCII characters" } }
        override val dropsWifiClients get() = which != Which.GUEST
        override fun apply(base: WifiConfig, out: WifiConfig) = out.editBss(base, which, null) { bss ->
            val withPassword = when {
                bss.auth_wpa2 != null -> bss.copy(auth_wpa2 = AuthWpa2(password))
                bss.auth_wpa3 != null -> bss.copy(auth_wpa3 = AuthWpa3(password))
                bss.auth_wpa2_wpa3 != null -> bss.copy(auth_wpa2_wpa3 = AuthWpa2Wpa3(password))
                else -> bss.copy(auth_open = null, auth_open_encrypted = null, auth_radius = null, auth_onboard_radius = null, auth_wpa2_wpa3 = AuthWpa2Wpa3(password))
            }
            withPassword.copy(bssid = "")
        }
    }

    /** Hides (or shows) the name of the network (all its bands, or only [band]). */
    class SetHidden(val hidden: Boolean, val band: Int? = null, val which: Which = Which.MAIN) : WifiConfigEdit {
        override val dropsWifiClients get() = hidden && which != Which.GUEST
        override fun apply(base: WifiConfig, out: WifiConfig) = out.editBss(base, which, band) { it.copy(hidden = hidden) }
    }

    /** Turns a whole radio band off or on (WifiConfig.Band 2, 5 or 6). */
    class SetBandDisabled(val band: Int, val disabled: Boolean) : WifiConfigEdit {
        init { require(band == WifiBand.RF_2GHZ || band == WifiBand.RF_5GHZ || band == WifiBand.RF_5GHZ_HIGH) { "band $band" } }
        override val dropsWifiClients get() = disabled
        override fun apply(base: WifiConfig, out: WifiConfig) = when (band) {
            WifiBand.RF_2GHZ -> out.copy(disable_2ghz = disabled, apply_disable_2ghz = true)
            WifiBand.RF_5GHZ -> out.copy(disable_5ghz = disabled, apply_disable_5ghz = true)
            else -> out.copy(disable_5ghz_high = disabled, apply_disable_5ghz_high = true)
        }
    }

    /** Custom DNS servers (empty = the router's own); [secureDns] asks for encrypted DNS. */
    class SetDns(val servers: List<String>, val secureDns: Boolean = false) : WifiConfigEdit {
        init {
            require(servers.size <= 4) { "at most 4 DNS servers" }
            servers.forEach { require(isIpLiteral(it)) { "not an IP address: $it" } }
        }

        override val dropsWifiClients get() = true
        override fun apply(base: WifiConfig, out: WifiConfig) = out.copy(
            nameservers = servers, apply_nameservers = true, secure_dns = secureDns, apply_secure_dns = true,
        )
    }

    /** Bypass mode: the Mini's Wi-Fi goes off and only the reset button brings it back. Destructive. */
    class SetBypassMode(val enabled: Boolean) : WifiConfigEdit {
        override val dropsWifiClients get() = enabled
        override fun apply(base: WifiConfig, out: WifiConfig) = out.copy(bypass_mode = enabled, apply_bypass_mode = true)
    }

    /** Moves the network's router address (its LAN subnet); the phone must reconnect and the app follow the new gateway. */
    class SetLanSubnet(val routerIpv4: String, val which: Which = Which.MAIN) : WifiConfigEdit {
        init { require(isIpv4(routerIpv4)) { "not an IPv4 address: $routerIpv4" } }
        override val dropsWifiClients get() = true
        override fun apply(base: WifiConfig, out: WifiConfig) = out.editNetworks(base, which) { it.copy(ipv4 = routerIpv4) }
    }

    /**
     * Adds a guest network (client isolation on, WPA2/WPA3, 2.4 and 5 GHz). Unverified on a Mini: the
     * official app may set more than this; the router's answer says whether it accepts.
     */
    class AddGuestNetwork(val name: String, val password: String) : WifiConfigEdit {
        init {
            require(name.encodeToByteArray().size in 1..32) { "a Wi-Fi name is 1..32 bytes" }
            require(WifiInput.passwordValid(password)) { "a Wi-Fi password is 8..63 printable ASCII characters" }
        }

        override fun apply(base: WifiConfig, out: WifiConfig): WifiConfig {
            val current = if (out.apply_networks) out.networks else base.networks
            val guest = WifiConfig.Network(
                guest = true,
                client_isolation = true,
                basic_service_sets = listOf(WifiBand.RF_2GHZ, WifiBand.RF_5GHZ).map { band ->
                    WifiConfig.BasicServiceSet(ssid = name, band = band, auth_wpa2_wpa3 = AuthWpa2Wpa3(password))
                },
            )
            return out.copy(networks = current.filterNot { it.guest } + guest, apply_networks = true)
        }
    }

    /** Removes the guest network. */
    object RemoveGuestNetwork : WifiConfigEdit {
        override fun apply(base: WifiConfig, out: WifiConfig): WifiConfig {
            val current = if (out.apply_networks) out.networks else base.networks
            return out.copy(networks = current.filterNot { it.guest }, apply_networks = true)
        }
    }
}

/** Content filters of the official app: Cloudflare for Families resolvers. */
enum class DnsPreset(val servers: List<String>) {
    NONE(emptyList()),
    MALWARE(listOf("1.1.1.2", "1.0.0.2")),
    MALWARE_AND_ADULT(listOf("1.1.1.3", "1.0.0.3"));

    fun edit(secureDns: Boolean = false) = WifiConfigEdit.SetDns(servers, secureDns)

    companion object {
        /** The preset a config's nameservers correspond to; null for a custom list. */
        fun of(nameservers: List<String>): DnsPreset? = entries.firstOrNull { it.servers == nameservers }
    }
}

private fun WifiConfig.selectedNetworks(base: WifiConfig): List<WifiConfig.Network> = if (apply_networks) networks else base.networks

private fun WifiConfig.editNetworks(
    base: WifiConfig,
    which: WifiConfigEdit.Which,
    change: (WifiConfig.Network) -> WifiConfig.Network,
): WifiConfig {
    val current = selectedNetworks(base)
    require(current.isNotEmpty()) { "the router config has no networks" }
    if (which == WifiConfigEdit.Which.GUEST) require(current.any { it.guest }) { "the router config has no guest network" }
    val main = current.indexOfFirst { !it.guest }.takeIf { it >= 0 } ?: 0
    val edited = current.mapIndexed { i, net ->
        val selected = when (which) {
            WifiConfigEdit.Which.ALL -> true
            WifiConfigEdit.Which.GUEST -> net.guest
            WifiConfigEdit.Which.MAIN -> i == main
        }
        if (selected) change(net) else net
    }
    return copy(networks = edited, apply_networks = true)
}

private fun WifiConfig.editBss(
    base: WifiConfig,
    which: WifiConfigEdit.Which,
    band: Int?,
    change: (WifiConfig.BasicServiceSet) -> WifiConfig.BasicServiceSet,
): WifiConfig = editNetworks(base, which) { net ->
    net.copy(basic_service_sets = net.basic_service_sets.map { if (band == null || it.band == band) change(it) else it })
}

private fun requireMac(mac: String) =
    require(Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$").matches(mac)) { "not a MAC address: $mac" }

private fun requireHostName(address: String) =
    require(address.isNotBlank() && address.length <= 253 && address.none { it.isWhitespace() || it.isISOControl() }) { "not a host name or address" }

internal fun isIpv4(s: String): Boolean {
    val parts = s.split('.')
    return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
}

internal fun isIpLiteral(s: String): Boolean =
    isIpv4(s) || (':' in s && s.all { it.isLetterOrDigit() || it == ':' || it == '.' } && s.count { it == ':' } in 2..7)
