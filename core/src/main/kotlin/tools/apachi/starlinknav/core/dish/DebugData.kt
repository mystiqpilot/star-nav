package tools.apachi.starlinknav.core.dish

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.log.Redactor
import java.time.Instant
import java.util.Locale

/**
 * A Debug data snapshot in the spirit of the official app's export, for support and for Star Debug style
 * viewing: a summary of the key fields of the dish and the router, and the raw protobuf of each answer
 * (base64) so any viewer can decode all of it. Identifiers (device ids, router ids, MAC / IP addresses,
 * SSIDs, client names) are removed on request; Wi-Fi passwords, RADIUS secrets and keys, and any
 * position (the diagnostics' location) are never in it: router answers pass through [Redact], every answer through
 * [Redactor.withoutCoordinates], and the builder checks the finished document for every secret it saw.
 * kotlinx.serialization with generated serializers only, so R8 cannot break it.
 */
@Serializable
data class DebugData(
    val format: String = FORMAT,
    /** ISO-8601, UTC. */
    val capturedAt: String,
    val app: DebugApp,
    val identifiersRemoved: Boolean,
    val dish: DebugDevice? = null,
    val router: DebugDevice? = null,
) {
    companion object {
        const val FORMAT = "starnav-debugdata-1"
    }
}

@Serializable
data class DebugApp(val version: String)

@Serializable
data class DebugDevice(
    val reachable: Boolean,
    /** Where it was read from, and how (transport name only: no secrets, no ids). */
    val transport: String? = null,
    /** Key fields as text, in display order. */
    val summary: Map<String, String> = emptyMap(),
    /** Call label ([DishCallKind.label]) -> outcome class, for every call that was made. */
    val outcomes: Map<String, String> = emptyMap(),
    /** Call label -> base64 of the response bytes (a DeviceResponse), for the calls that answered. */
    val raw: Map<String, String> = emptyMap(),
)

object DebugDataBuilder {
    class Options(
        val appVersion: String = "dev",
        /** Remove device ids, router ids, addresses, SSIDs and client identity (a position is never kept). */
        val removeIdentifiers: Boolean = true,
    )

    /** Dish calls kept raw: no location answers (coordinates), no GNSS measurements. */
    val DISH_RAW = listOf(
        DishCallKind.STATUS, DishCallKind.HISTORY, DishCallKind.OBSTRUCTION_MAP, DishCallKind.DEVICE_INFO,
        DishCallKind.DIAGNOSTICS, DishCallKind.CONFIG, DishCallKind.NETWORK_INTERFACES, DishCallKind.PING,
        DishCallKind.CONNECTIONS, DishCallKind.TIME,
    )

    val ROUTER_RAW = listOf(
        DishCallKind.ROUTER_STATUS, DishCallKind.ROUTER_HISTORY, DishCallKind.ROUTER_DEVICE_INFO, DishCallKind.ROUTER_DIAGNOSTICS,
        DishCallKind.ROUTER_CLIENTS, DishCallKind.ROUTER_CONFIG, DishCallKind.ROUTER_PING_METRICS, DishCallKind.ROUTER_NETWORK_INTERFACES,
        DishCallKind.ROUTER_PING, DishCallKind.ROUTER_CONNECTIONS, DishCallKind.ROUTER_TIME,
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    /** Builds the snapshot from probe-style results of the dish and (optionally) the router. */
    fun build(
        dish: List<DishCallResult>,
        router: List<DishCallResult> = emptyList(),
        options: Options = Options(),
        nowWallMs: Long = System.currentTimeMillis(),
    ): DebugData = DebugData(
        capturedAt = Instant.ofEpochMilli(nowWallMs).toString(),
        app = DebugApp(options.appVersion),
        identifiersRemoved = options.removeIdentifiers,
        dish = dish.takeIf { it.isNotEmpty() }?.let { device(it, DISH_RAW, options, nowWallMs, ::dishSummary) },
        router = router.takeIf { it.isNotEmpty() }?.let { device(it, ROUTER_RAW, options, nowWallMs, ::routerSummary) },
    )

    private fun device(
        results: List<DishCallResult>,
        rawKinds: List<DishCallKind>,
        options: Options,
        nowWallMs: Long,
        summarize: (List<DishCallResult>, Options) -> Map<String, String>,
    ): DebugDevice {
        val raw = LinkedHashMap<String, String>()
        for (r in results) {
            if (r.kind !in rawKinds) continue
            val response = r.response?.takeIf { r.ok } ?: continue
            raw[r.kind.label] = safeBytes(r.kind, response, options).toByteString().base64()
        }
        return DebugDevice(
            reachable = results.any { it.httpStatus != 0 },
            transport = results.lastOrNull { it.httpStatus != 0 }?.transport,
            summary = summarize(results, options),
            outcomes = results.sortedBy { it.kind.code }.associate { it.kind.label to it.outcome.name },
            raw = raw,
        )
    }

    /** The response as it may leave the app: secrets always out, identifiers out on request. */
    private fun safeBytes(kind: DishCallKind, response: DeviceResponse, options: Options): ByteArray {
        var r = response
        if (kind.target == CallTarget.ROUTER || r.hasRouterPayload()) r = Redact.response(r)
        // Never a position, whatever the options: the diagnostics' location is zeroed even when identifiers stay.
        r = Redactor.withoutCoordinates(r)
        if (options.removeIdentifiers) r = Redactor.response(r, Redactor.Options(coordinates = true))
        return DeviceResponse.ADAPTER.encode(r)
    }

    fun encode(data: DebugData): String = json.encodeToString(DebugData.serializer(), data)

    /**
     * Builds and encodes; refuses to return a document that holds a Wi-Fi password, secret or key that the
     * router replies in [router] carried. Pass the router results as they came from [RouterClient] (not
     * `.redacted()` ones): the builder redacts them itself, and the check needs the real secrets to look for.
     * The look is in the text and inside every base64 blob, decoded.
     */
    fun toJson(
        dish: List<DishCallResult>,
        router: List<DishCallResult> = emptyList(),
        options: Options = Options(),
        nowWallMs: Long = System.currentTimeMillis(),
    ): String {
        val data = build(dish, router, options, nowWallMs)
        val text = encode(data)
        val secrets = (dish + router).flatMap { Redact.secretsIn(it.response) }.filter { it.length >= 4 }.distinct()
        checkNoSecrets(data, text, secrets)
        return text
    }

    /** Throws when any of [secrets] is in [text] or in a decoded raw blob of [data]. */
    internal fun checkNoSecrets(data: DebugData, text: String, secrets: List<String>) {
        val blobs = listOfNotNull(data.dish, data.router).flatMap { d -> d.raw.map { (label, b64) -> label to (b64.decodeBase64()?.toByteArray() ?: ByteArray(0)) } }
        for (secret in secrets) {
            check(secret !in text) { "debug data would contain a Wi-Fi secret" }
            val needle = secret.encodeToByteArray()
            for ((label, bytes) in blobs) check(!containsBytes(bytes, needle)) { "debug data ($label) would contain a Wi-Fi secret" }
        }
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (k in needle.indices) if (haystack[i + k] != needle[k]) continue@outer
            return true
        }
        return false
    }

    fun decode(text: String): DebugData {
        val d = json.decodeFromString(DebugData.serializer(), text)
        require(d.format == DebugData.FORMAT) { "not a ${DebugData.FORMAT} document (format ${d.format})" }
        return d
    }

    /** The answers inside a snapshot as results again (for describing them), in call-code order. */
    fun results(device: DebugDevice?): List<DishCallResult> {
        device ?: return emptyList()
        return device.raw.mapNotNull { (label, b64) ->
            val kind = DishCallKind.entries.firstOrNull { it.label == label } ?: return@mapNotNull null
            val bytes = b64.decodeBase64()?.toByteArray() ?: return@mapNotNull null
            val response = runCatching { DeviceResponse.ADAPTER.decode(bytes) }.getOrNull()
            DishCallResult(kind, 0, 0, device.transport ?: "debugdata", 200, GrpcStatus.OK, null, bytes, response, null, payloadRedacted = kind.target == CallTarget.ROUTER)
        }.sortedBy { it.kind.code }
    }

    // ---- summaries

    private fun dishSummary(results: List<DishCallResult>, options: Options): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun resp(kind: DishCallKind) = results.firstOrNull { it.kind == kind && it.ok }?.response
        val s = resp(DishCallKind.STATUS)?.dish_get_status
        val info = s?.device_info ?: resp(DishCallKind.DEVICE_INFO)?.get_device_info?.device_info
        info?.let {
            out["hardware"] = it.hardware_version
            out["software"] = it.software_version
            out["country"] = it.country_code
            out["boots"] = it.bootcount.toString()
            if (!options.removeIdentifiers && it.id.isNotEmpty()) out["id"] = it.id
        }
        if (s != null) {
            val st = DishStates.of(s)
            out["uptime_s"] = (s.device_state?.uptime_s ?: 0).toString()
            out["state"] = st.state.name
            out["disablement"] = Disablement.name(s.disablement_code)
            out["software_update"] = SoftwareUpdate.name(s.software_update_state)
            out["has_actuators"] = HasActuators.name(s.hasActuators())
            out["mobility"] = MobilityClass.name(s.mobility_class)
            out["class_of_service"] = ClassOfService.name(s.class_of_service)
            out["latency_ms"] = s.pop_ping_latency_ms.fmt(1)
            out["drop_rate"] = s.pop_ping_drop_rate.fmt(3)
            out["downlink_mbps"] = (s.downlink_throughput_bps / 1e6f).fmt(2)
            out["uplink_mbps"] = (s.uplink_throughput_bps / 1e6f).fmt(2)
            out["signal_quality"] = s.signal_quality.fmt(2)
            out["obstructed_fraction"] = (s.obstruction_stats?.fraction_obstructed ?: 0f).fmt(3)
            s.upsu_stats?.let { out["power_w"] = it.dish_power.fmt(1) }
            s.gps_stats?.let { out["gps"] = "valid=${it.gps_valid} sats=${it.gps_sats} inhibit=${it.inhibit_gps}" }
            s.alignment_stats?.let { out["tilt_deg"] = it.tilt_angle_deg.fmt(1) }
            out["alerts"] = AlertList.of(s, resp(DishCallKind.DIAGNOSTICS)?.dish_get_diagnostics).joinToString(",") { it.key }
            val init = InitializationView.of(s.initialization_duration_seconds)
            out["initialization"] = init.stages.joinToString(",") { "${it.key}=${it.seconds}s" }
            s.battery_stats?.let { out["battery"] = "${it.state_of_charge}% ${PowerSource.name(it.power_source)}" }
            SleepWindow.of(s.config)?.takeIf { s.config?.power_save_duration_minutes != 0 }?.let {
                out["sleep_schedule"] = "${SleepWindow.clock(it.startUtcMinutes)} UTC for ${it.durationMinutes} min, ${if (it.enabled) "on" else "off"}"
            }
        }
        resp(DishCallKind.HISTORY)?.dish_get_history?.let { h ->
            val dh = DishHistory.of(h)
            out["history_samples"] = dh.size.toString()
            Series.mean(dh.powerInW)?.let { out["history_mean_power_w"] = it.fmt(1) }
            out["history_outages"] = dh.outages.size.toString()
        }
        resp(DishCallKind.OBSTRUCTION_MAP)?.dish_get_obstruction_map?.let { m ->
            Obstruction.analyze(m, s?.ned2dish_quaternion?.attitude()?.panelUpAzimuthDeg)?.let { a ->
                out["obstruction_blocked_of_observed"] = a.blockedFraction?.let { (it * 100).fmt(1) + "%" } ?: "-"
                out["obstruction_largest_patch_cells"] = a.largestBlockedPatch.toString()
            }
        }
        return out
    }

    private fun routerSummary(results: List<DishCallResult>, options: Options): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val s = results.firstOrNull { it.kind == DishCallKind.ROUTER_STATUS && it.ok }?.response?.wifi_get_status ?: return out
        s.device_info?.let {
            out["hardware"] = it.hardware_version
            out["software"] = it.software_version
            out["cohoused"] = it.dish_cohoused.toString()
            if (!options.removeIdentifiers && it.id.isNotEmpty()) out["id"] = it.id
        }
        out["uptime_s"] = (s.device_state?.uptime_s ?: 0).toString()
        out["ping_ms"] = s.ping_latency_ms.fmt(1)
        out["ping_drop_rate"] = s.ping_drop_rate.fmt(3)
        out["pop_ping_ms"] = s.pop_ping_latency_ms.fmt(1)
        out["dish_ping_ms"] = s.dish_ping_latency_ms.fmt(1)
        out["no_wan_link"] = s.no_wan_link.toString()
        out["clients"] = s.clients.size.toString()
        s.rf_2ghz_status?.let { out["busy_2ghz"] = (it.chan_busy_time_fraction * 100f).fmt(0) + "%" }
        s.rf_5ghz_status?.let { out["busy_5ghz"] = (it.chan_busy_time_fraction * 100f).fmt(0) + "%" }
        out["alerts"] = AlertList.router(s.alerts).joinToString(",")
        s.software_update_stats?.let { out["software_update"] = WifiSoftwareUpdate.name(it.state) }
        s.setup_requirement?.let { out["setup"] = SetupRequirement.name(it.state) }
        out["dish_disablement"] = Disablement.name(s.dish_disablement_code)
        s.config?.let { c ->
            out["networks"] = c.networks.size.toString()
            out["bands"] = c.networks.flatMap { n -> n.basic_service_sets.map { WifiBand.name(it.band) } }.joinToString(",")
            out["bypass_mode"] = c.bypass_mode.toString()
            out["dns"] = (DnsPreset.of(c.nameservers)?.name ?: if (c.nameservers.isEmpty()) "NONE" else "CUSTOM")
            if (!options.removeIdentifiers) out["ssids"] = c.networks.flatMap { n -> n.basic_service_sets.map { it.ssid } }.distinct().joinToString(",")
        }
        return out
    }

    private fun Float.fmt(decimals: Int) = toDouble().fmt(decimals)
    private fun Double.fmt(decimals: Int) = "%.${decimals}f".format(Locale.ROOT, this)
}
