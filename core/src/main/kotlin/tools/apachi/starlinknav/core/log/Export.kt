package tools.apachi.starlinknav.core.log

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.ByteString.Companion.toByteString
import tools.apachi.starlinknav.core.dish.CallTarget
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.Redact
import tools.apachi.starlinknav.core.dish.hasRouterPayload
import tools.apachi.starlinknav.core.dish.proto.DeviceInfo
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetDiagnosticsResponse
import tools.apachi.starlinknav.core.dish.proto.EventLog
import tools.apachi.starlinknav.core.dish.proto.UXEvent
import tools.apachi.starlinknav.core.dish.proto.WifiClient
import tools.apachi.starlinknav.core.dish.proto.WifiConfig
import tools.apachi.starlinknav.core.log.proto.Record
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class ExportManifest(
    val format: String = "starlink-nav-export/1",
    val exportedBy: String,
    val sessionId: String?,
    val windowStartMonoNs: Long,
    val windowEndMonoNs: Long,
    val windowStartWallMs: Long?,
    val windowEndWallMs: Long?,
    val records: Map<String, Long>,
    val sourceTruncatedTail: Boolean,
    val appVersion: String?,
    val deviceManufacturer: String?,
    val deviceModel: String?,
    val sdkInt: Int?,
    val dishSoftwareVersion: String?,
    val dishHardwareVersion: String?,
    val redactions: List<String>,
)

/**
 * Clears dish and router identifiers from raw responses; everything else is kept as recorded. Router
 * replies are stored without secrets already ([Redact]); this also clears the router's own ids, the
 * clients' names, MAC and IP addresses, SSIDs and BSSIDs. Used by the session exporter and the Debug
 * data snapshot.
 */
object Redactor {
    const val REDACTED = "redacted"
    val DESCRIPTION = listOf(
        "dish device_info.id",
        "diagnostics id",
        "gnss_measurement device_id",
        "status connected_routers / downstream_routers",
        "answers of UT_INFO / EXTERNAL location sources (may hold a registered address)",
        "router ids, WAN / public addresses, SSIDs, BSSIDs, client names / MACs / IPs, DHCP leases",
        "event log device ids, network interface MACs",
        "router Wi-Fi passwords, RADIUS secrets and keys (never recorded at all)",
    )

    /** What [response] clears besides the ids every export clears. */
    class Options(
        /** Clear coordinates in the diagnostics (a Debug data snapshot does; session exports keep them for the track). */
        val coordinates: Boolean = false,
    )

    fun redact(record: Record, options: Options = Options()): Record {
        record.header_?.let { h -> return headerConfigFree(h.config_json).let { if (it == h.config_json) record else record.copy(header_ = h.copy(config_json = it)) } }
        val dish = record.dish ?: return record
        if (dish.response.size == 0) return record
        if (DishCallKind.of(dish.kind)?.isSensitive == true) {
            return record.copy(dish = dish.copy(response = okio.ByteString.EMPTY, error = (dish.error + " [response dropped by exporter: sensitive source]").trim()))
        }
        val resp = try {
            DeviceResponse.ADAPTER.decode(dish.response)
        } catch (_: Exception) {
            // Cannot prove it is identifier-free: drop the payload rather than export it blind.
            return record.copy(dish = dish.copy(response = okio.ByteString.EMPTY, error = (dish.error + " [response dropped by exporter: undecodable]").trim()))
        }
        val routerKind = DishCallKind.of(dish.kind)?.target == CallTarget.ROUTER
        val safe = if (routerKind || resp.hasRouterPayload()) Redact.response(resp) else resp
        val redacted = response(safe, options)
        if (redacted == resp) return record
        return record.copy(dish = dish.copy(response = DeviceResponse.ADAPTER.encode(redacted).toByteString()))
    }

    /**
     * Settings keys a header written by an earlier build carried that tell something outside the exported part: the
     * moment the mount offset was learned (a wall-clock time, normally on an earlier drive). The exporter keeps the
     * header whatever the trim window, so they are dropped from it.
     */
    private val HEADER_DROP = setOf("mountLearnedAtMs")

    /** The header's settings JSON without [HEADER_DROP]; unchanged when it has none of them (or is no JSON object). */
    fun headerConfigFree(configJson: String): String {
        if (HEADER_DROP.none { "\"$it\"" in configJson }) return configJson
        val obj = try {
            Json.parseToJsonElement(configJson) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return configJson
        return JsonObject(obj.filterKeys { it !in HEADER_DROP }).toString()
    }

    /**
     * [resp] without any position: the diagnostics' location (latitude, longitude, altitude, GPS time; its unknown
     * fields too) is zeroed and a location answer loses its coordinates. Identifiers stay. A Debug data snapshot
     * always goes through this, whether or not it keeps identifiers.
     */
    fun withoutCoordinates(resp: DeviceResponse): DeviceResponse = resp.copy(
        dish_get_diagnostics = resp.dish_get_diagnostics?.let { d -> d.copy(location = d.location?.let(::noPosition)) },
        get_location = resp.get_location?.copy(lla = null),
    )

    private fun noPosition(l: DishGetDiagnosticsResponse.Location) =
        l.copy(latitude = 0.0, longitude = 0.0, altitude_meters = 0.0, gps_time_s = 0.0, unknownFields = okio.ByteString.EMPTY)

    /** [resp] without identifiers. Secrets are not this function's business: see [Redact]. */
    fun response(resp: DeviceResponse, options: Options = Options()): DeviceResponse = resp.copy(
        get_device_info = resp.get_device_info?.let { it.copy(device_info = it.device_info?.redactId()) },
        dish_get_status = resp.dish_get_status?.let {
            it.copy(device_info = it.device_info?.redactId(), connected_routers = emptyList(), downstream_routers = emptyList())
        },
        dish_get_history = resp.dish_get_history?.let { h -> h.copy(event_log = h.event_log?.redactEvents()) },
        dish_get_diagnostics = resp.dish_get_diagnostics?.let { d ->
            d.copy(
                id = if (d.id.isEmpty()) d.id else REDACTED,
                location = if (options.coordinates) d.location?.let(::noPosition) else d.location,
            )
        },
        get_gnss_measurement = resp.get_gnss_measurement?.let { if (it.device_id.isEmpty()) it else it.copy(device_id = REDACTED) },
        get_network_interfaces = resp.get_network_interfaces?.let { n ->
            n.copy(
                network_interfaces = n.network_interfaces.map {
                    // The WAN side holds a public (or carrier-grade NAT) address: only private IPv4 stays.
                    it.copy(mac_address = "", ipv6_addresses = emptyList(), ipv4_addresses = it.ipv4_addresses.filter(::isPrivateIpv4))
                },
            )
        },
        wifi_get_status = resp.wifi_get_status?.let { s ->
            s.copy(
                device_info = s.device_info?.redactId(), dish_id = if (s.dish_id.isEmpty()) "" else REDACTED,
                ipv4_wan_address = "", ipv6_wan_addresses = emptyList(), public_ipv4 = "",
                dhcp_servers = s.dhcp_servers.map { d -> d.copy(leases = d.leases.map { l -> l.copy(ip_address = "", mac_address = "", hostname = "") }) },
                config = s.config?.let(::config), clients = s.clients.map(::client),
            )
        },
        wifi_get_clients = resp.wifi_get_clients?.let { c -> c.copy(clients = c.clients.map(::client)) },
        wifi_get_config = resp.wifi_get_config?.let { it.copy(wifi_config = it.wifi_config?.let(::config)) },
        wifi_set_config = resp.wifi_set_config?.let { it.copy(updated_wifi_config = it.updated_wifi_config?.let(::config)) },
        wifi_get_history = resp.wifi_get_history?.let { h -> h.copy(event_log = h.event_log?.redactEvents()) },
        wifi_get_diagnostics = resp.wifi_get_diagnostics?.let { d ->
            d.copy(id = if (d.id.isEmpty()) "" else REDACTED, networks = d.networks.map { it.copy(ipv6 = emptyList()) })
        },
    )

    private fun DeviceInfo.redactId() = if (id.isEmpty()) this else copy(id = REDACTED)

    private fun EventLog.redactEvents() = copy(events = events.map { it.redactIds() })

    /** The event's device id and the mesh repeater ids in its metadata (identifiers of other devices). */
    private fun UXEvent.redactIds() = copy(
        device_id = "",
        mesh_connection_changing_metadata = mesh_connection_changing_metadata?.copy(repeater_id = ""),
        mesh_backhaul_low_phy_metadata = mesh_backhaul_low_phy_metadata?.copy(repeater_ids = emptyList()),
    )

    /** RFC 1918, loopback and link-local IPv4; carrier-grade NAT (100.64.0.0/10) is not private. */
    internal fun isPrivateIpv4(address: String): Boolean {
        val p = address.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
    }

    private fun client(c: WifiClient) = c.copy(
        name = "", given_name = "", mac_address = "", ip_address = "", ipv6_addresses = emptyList(), domain = "", iface_name = c.iface_name,
        links = c.links.map { it.copy(link_address = "") },
    )

    /** Router config without addresses and names of this network. */
    private fun config(c: WifiConfig) = c.copy(
        mac_wan = "", mac_lan = "",
        networks = c.networks.map { n ->
            n.copy(basic_service_sets = n.basic_service_sets.map { b -> b.copy(bssid = "", ssid = if (b.ssid.isEmpty()) "" else REDACTED) })
        },
        client_configs = c.client_configs.map { it.copy(mac_address = "", given_name = "") },
        client_names = c.client_names.map { it.copy(mac_address = "", given_name = "") },
    )
}

object SessionExporter {
    const val LOG_ENTRY = "session.slog"
    const val MANIFEST_ENTRY = "manifest.json"

    /**
     * Writes a zip holding only the records captured inside [fromMonoNs]..[toMonoNs] (plus the
     * coordinate-free session header) with identifiers redacted, and a manifest describing it.
     */
    fun export(log: File, fromMonoNs: Long, toMonoNs: Long, outZip: File, exportedBy: String): ExportManifest {
        require(fromMonoNs <= toMonoNs) { "empty export window" }
        val counts = sortedMapOf<String, Long>()
        var firstWall: Long? = null
        var lastWall: Long? = null
        var header: tools.apachi.starlinknav.core.log.proto.SessionHeader? = null
        var dishSw: String? = null
        var dishHw: String? = null
        var truncated: Boolean
        outZip.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outZip)).use { zip ->
            zip.putNextEntry(ZipEntry(LOG_ENTRY))
            val writer = SlogWriter.into(zip)
            val reader = SlogReader.open(log)
            reader.use { r ->
                for (rec in r.records()) {
                    val isHeader = rec.header_ != null
                    if (!isHeader && rec.t_mono_ns !in fromMonoNs..toMonoNs) continue
                    val out = Redactor.redact(rec)
                    writer.write(out)
                    val type = Slog.payloadType(out)
                    counts[type] = (counts[type] ?: 0) + 1
                    if (isHeader) {
                        header = rec.header_
                        continue
                    }
                    if (rec.t_wall_ms != 0L) {
                        if (firstWall == null) firstWall = rec.t_wall_ms
                        lastWall = rec.t_wall_ms
                    }
                    out.dish?.response?.takeIf { it.size > 0 }?.let { bytes ->
                        runCatching { DeviceResponse.ADAPTER.decode(bytes) }.getOrNull()?.let { resp ->
                            val info = resp.dish_get_status?.device_info ?: resp.get_device_info?.device_info
                            if (info != null && info.software_version.isNotEmpty()) {
                                dishSw = info.software_version
                                dishHw = info.hardware_version.ifEmpty { dishHw }
                            }
                        }
                    }
                }
            }
            truncated = reader.truncatedTail
            writer.flush()
            zip.closeEntry()
            val h = header
            val manifest = ExportManifest(
                exportedBy = exportedBy,
                sessionId = h?.session_id,
                windowStartMonoNs = fromMonoNs,
                windowEndMonoNs = toMonoNs,
                windowStartWallMs = firstWall,
                windowEndWallMs = lastWall,
                records = counts,
                sourceTruncatedTail = truncated,
                appVersion = h?.app_version,
                deviceManufacturer = h?.device_manufacturer,
                deviceModel = h?.device_model,
                sdkInt = h?.sdk_int,
                dishSoftwareVersion = dishSw,
                dishHardwareVersion = dishHw,
                redactions = Redactor.DESCRIPTION,
            )
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(Sessions.json.encodeToString(ExportManifest.serializer(), manifest).encodeToByteArray())
            zip.closeEntry()
            return manifest
        }
    }
}
