package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.GetSpeedtestStatusResponse
import tools.apachi.starlinknav.core.dish.proto.UXEvent
import tools.apachi.starlinknav.core.dish.proto.WifiClient
import tools.apachi.starlinknav.core.dish.proto.WifiConfig
import tools.apachi.starlinknav.core.dish.proto.WifiSoftwareUpdateStats
import java.util.Locale

/*
 * What the Router sub-tab shows, worked out from router replies. Plain functions over the decoded messages so the
 * rules (who is this phone, which name does a client have, what a Wi-Fi band looks like, what the speed test says) are
 * tested here and not inside Compose. The replies these read are unredacted and live in memory only (see [Redact]):
 * nothing here logs, stores or prints a password.
 */

/** How a Wi-Fi band authenticates. */
enum class WifiAuth {
    OPEN, WPA2, WPA3, WPA2_WPA3, RADIUS, OPEN_ENCRYPTED, ONBOARD_RADIUS, UNKNOWN;

    /** A shared password protects the band. */
    val hasPassword: Boolean get() = this == WPA2 || this == WPA3 || this == WPA2_WPA3
}

/**
 * One band of a Wi-Fi network. [password] is the Wi-Fi password in clear, for a masked field with a reveal button; it is
 * held in memory only and [toString] leaves it out.
 */
class WifiBandView(
    val ssid: String,
    /** [WifiBand] code: 2, 5 or 6. */
    val band: Int,
    val hidden: Boolean,
    val disabled: Boolean,
    val auth: WifiAuth,
    val password: String?,
) {
    override fun toString() = "WifiBandView(band=$band, auth=$auth, password hidden)"
}

class WifiNetworkView(
    val guest: Boolean,
    val ipv4: String,
    val clientIsolation: Boolean,
    val bands: List<WifiBandView>,
) {
    /** All bands carry one name. */
    val oneName: Boolean get() = bands.map { it.ssid }.distinct().size <= 1

    /** The name is hidden on every band. */
    val hidden: Boolean get() = bands.isNotEmpty() && bands.all { it.hidden }

    fun band(code: Int): WifiBandView? = bands.firstOrNull { it.band == code }
}

/** The router's Wi-Fi settings the Router sub-tab shows and edits. */
class WifiOverview(
    val networks: List<WifiNetworkView>,
    val bypass: Boolean,
    val nameservers: List<String>,
    val secureDns: Boolean,
    val disable2ghz: Boolean,
    val disable5ghz: Boolean,
    val countryCode: String,
) {
    /** The main (non-guest) network: the one the phone is on. */
    val main: WifiNetworkView? get() = networks.firstOrNull { !it.guest }
    val guest: WifiNetworkView? get() = networks.firstOrNull { it.guest }

    /** The content filter the nameservers amount to; null for a custom list. */
    val dnsPreset: DnsPreset? get() = DnsPreset.of(nameservers)

    override fun toString() = "WifiOverview(${networks.size} networks, bypass=$bypass, passwords hidden)"

    companion object {
        fun of(c: WifiConfig): WifiOverview = WifiOverview(
            networks = c.networks.map { n ->
                WifiNetworkView(
                    guest = n.guest, ipv4 = n.ipv4, clientIsolation = n.client_isolation,
                    bands = n.basic_service_sets.map { b ->
                        val (auth, password) = when {
                            b.auth_wpa2 != null -> WifiAuth.WPA2 to b.auth_wpa2.password
                            b.auth_wpa3 != null -> WifiAuth.WPA3 to b.auth_wpa3.password
                            b.auth_wpa2_wpa3 != null -> WifiAuth.WPA2_WPA3 to b.auth_wpa2_wpa3.password
                            b.auth_open != null -> WifiAuth.OPEN to null
                            b.auth_radius != null -> WifiAuth.RADIUS to null
                            b.auth_open_encrypted != null -> WifiAuth.OPEN_ENCRYPTED to null
                            b.auth_onboard_radius != null -> WifiAuth.ONBOARD_RADIUS to null
                            else -> WifiAuth.UNKNOWN to null
                        }
                        WifiBandView(b.ssid, b.band, b.hidden, b.disable, auth, password?.takeIf { it.isNotEmpty() })
                    },
                )
            },
            bypass = c.bypass_mode, nameservers = c.nameservers, secureDns = c.secure_dns,
            disable2ghz = c.disable_2ghz, disable5ghz = c.disable_5ghz, countryCode = c.country_code,
        )
    }
}

/** Checks on what the user types before it becomes an edit (the edits themselves refuse a bad value). */
object WifiInput {
    const val NAME_MAX_BYTES = 32
    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 63
    const val DNS_MAX = 4

    /** A Wi-Fi name is 1..32 bytes. */
    fun nameValid(name: String): Boolean = name.encodeToByteArray().size in 1..NAME_MAX_BYTES

    /**
     * A WPA passphrase: 8..63 printable ASCII characters (space to `~`). Android refuses a non-ASCII one, so a password
     * typed with a Cyrillic layout would lock the phones out of the router's Wi-Fi.
     */
    fun passwordValid(password: String): Boolean =
        password.length in PASSWORD_MIN..PASSWORD_MAX && password.all { it in ' '..'~' }

    fun isIpv4Address(s: String): Boolean = isIpv4(s.trim())

    /**
     * DNS servers typed separated by commas, spaces or lines; null when one is not an IP address or there are more than
     * [DNS_MAX]. Empty text is an empty list (the router's own resolver).
     */
    fun parseServers(text: String): List<String>? {
        val servers = text.split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
        if (servers.size > DNS_MAX || servers.any { !isIpLiteral(it) }) return null
        return servers
    }

    /** [name] with [suffix] appended, the name cut short (by characters, never mid-character) so the result fits 32 bytes. */
    fun withSuffix(name: String, suffix: String): String {
        var base = name
        while (base.isNotEmpty() && (base + suffix).encodeToByteArray().size > NAME_MAX_BYTES) base = base.dropLast(1)
        return base + suffix
    }
}

/** Router clients (WifiClient) as the Devices list shows them. */
object ClientList {
    /** What a client is called: the name the user gave it, else its own host name, else its address. */
    fun displayName(c: WifiClient): String =
        c.given_name.ifBlank { c.name }.ifBlank { c.ip_address }.ifBlank { c.mac_address }.ifBlank { "#${c.client_id}" }

    enum class State { ONLINE, PAUSED, OFFLINE }

    fun state(c: WifiClient): State = when {
        c.blocked -> State.PAUSED
        c.active || c.associated_time_s > 0 -> State.ONLINE
        else -> State.OFFLINE
    }

    /** Online first, then paused, then offline; by name inside each group. */
    fun sorted(list: List<WifiClient>): List<WifiClient> =
        list.sortedWith(compareBy<WifiClient>({ state(it).ordinal }, { displayName(it).lowercase(Locale.ROOT) }))

    private fun normalize(address: String): String = address.trim().substringBefore('%').lowercase(Locale.ROOT)

    /**
     * This phone is the client whose IPv4 or IPv6 address is one of [ownAddresses] (the addresses of the phone's Wi-Fi
     * link). Android hides the Wi-Fi MAC address from apps (a random one per network), so the address is the only way to know.
     */
    fun isThisPhone(c: WifiClient, ownAddresses: Collection<String>): Boolean {
        if (ownAddresses.isEmpty()) return false
        val own = ownAddresses.map(::normalize).filter { it.isNotEmpty() }.toSet()
        val theirs = (listOf(c.ip_address) + c.ipv6_addresses).map(::normalize).filter { it.isNotEmpty() }
        return theirs.any { it in own }
    }

    fun byId(list: List<WifiClient>, id: Int): WifiClient? = list.firstOrNull { it.client_id == id }
}

/** The metadata of a router event that names a client. */
object RouterEvents {
    fun clientId(e: UXEvent): Int? =
        e.client_reconnecting_often_metadata?.client_id
            ?: e.client_switching_band_metadata?.client_id
            ?: e.client_switching_upstream_mac_metadata?.client_id
            ?: e.client_excessive_network_connections_metadata?.client_id
}

/** Series helpers for the router's charts. */
object RouterSeries {
    /** Means of [size] consecutive samples (NaN-free ones; NaN when a bucket has none); the last bucket may be short. */
    fun bucketMean(xs: List<Float>, size: Int): List<Float> {
        require(size > 0)
        return xs.chunked(size).map { chunk -> Series.mean(chunk) ?: Float.NaN }
    }

    /** A drop rate sample as percent; a negative or missing sample is "no data". */
    fun percent(xs: List<Float>): List<Float> = xs.map { if (it.isFinite() && it >= 0f) it * 100f else Float.NaN }
}

/** The router's software update, from `software_update_stats` of the status. */
class RouterUpdateView(
    val state: Int,
    /** 0..1 while downloading. */
    val progress: Float?,
    val runningVersion: String,
    val versionInProgress: String,
) {
    /** Getting the target version, downloading or flashing. */
    val busy: Boolean get() = state in 1..3
    val rebootPending: Boolean get() = state == WifiSoftwareUpdate.REBOOT_PENDING
    val failed: Boolean get() = WifiSoftwareUpdate.isFailure(state)
    val upToDate: Boolean get() = state == WifiSoftwareUpdate.NO_UPDATE_REQUIRED

    companion object {
        fun of(s: WifiSoftwareUpdateStats?): RouterUpdateView? = s?.let {
            RouterUpdateView(
                it.state, it.software_download_progress.takeIf { p -> p.isFinite() && p > 0f }?.coerceAtMost(1f),
                it.running_version, it.version_in_progress,
            )
        }
    }
}

/** A speed test of the router: the Mbit/s of each second, per direction. */
class SpeedtestView(
    val running: Boolean,
    val id: Int,
    val downMbps: List<Float>,
    val upMbps: List<Float>,
    val downError: Int,
    val upError: Int,
) {
    val downAvg: Float? get() = Series.mean(downMbps)
    val downMax: Float? get() = Series.max(downMbps)
    val upAvg: Float? get() = Series.mean(upMbps)
    val upMax: Float? get() = Series.max(upMbps)

    /** At least one second of either direction was measured. */
    val hasData: Boolean get() = downMbps.isNotEmpty() || upMbps.isNotEmpty()

    /** The test ended with an error code (anything but none). */
    val failed: Boolean get() = !running && (downError > 0 || upError > 0)

    companion object {
        fun of(r: GetSpeedtestStatusResponse?): SpeedtestView? = r?.status?.let { s ->
            SpeedtestView(
                running = s.running, id = s.id,
                downMbps = s.down?.throughputs_mbps.orEmpty(), upMbps = s.up?.throughputs_mbps.orEmpty(),
                downError = s.down?.err ?: 0, upError = s.up?.err ?: 0,
            )
        }
    }
}
