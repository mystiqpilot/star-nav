package tools.apachi.starlinknav.core.dish

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import tools.apachi.starlinknav.core.dish.proto.DeviceRequest
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishClearObstructionMapRequest
import tools.apachi.starlinknav.core.dish.proto.DishConfig
import tools.apachi.starlinknav.core.dish.proto.DishFactoryResetRequest
import tools.apachi.starlinknav.core.dish.proto.DishGetConfigRequest
import tools.apachi.starlinknav.core.dish.proto.DishGetObstructionMapRequest
import tools.apachi.starlinknav.core.dish.proto.DishInhibitGpsRequest
import tools.apachi.starlinknav.core.dish.proto.DishInhibitRfRequest
import tools.apachi.starlinknav.core.dish.proto.DishSetConfigRequest
import tools.apachi.starlinknav.core.dish.proto.DishStowRequest
import tools.apachi.starlinknav.core.dish.proto.FactoryResetRequest
import tools.apachi.starlinknav.core.dish.proto.GetConnectionsRequest
import tools.apachi.starlinknav.core.dish.proto.GetDeviceInfoRequest
import tools.apachi.starlinknav.core.dish.proto.GetDiagnosticsRequest
import tools.apachi.starlinknav.core.dish.proto.GetGnssMeasurementRequest
import tools.apachi.starlinknav.core.dish.proto.GetHistoryRequest
import tools.apachi.starlinknav.core.dish.proto.GetLocationRequest
import tools.apachi.starlinknav.core.dish.proto.GetNetworkInterfacesRequest
import tools.apachi.starlinknav.core.dish.proto.GetPersistentStatsRequest
import tools.apachi.starlinknav.core.dish.proto.GetPingRequest
import tools.apachi.starlinknav.core.dish.proto.GetRadioStatsRequest
import tools.apachi.starlinknav.core.dish.proto.GetSpeedtestStatusRequest
import tools.apachi.starlinknav.core.dish.proto.GetStatusRequest
import tools.apachi.starlinknav.core.dish.proto.GetTimeRequest
import tools.apachi.starlinknav.core.dish.proto.RebootRequest
import tools.apachi.starlinknav.core.dish.proto.WifiGetClientsRequest
import tools.apachi.starlinknav.core.dish.proto.WifiGetConfigRequest
import tools.apachi.starlinknav.core.dish.proto.WifiGetPingMetricsRequest
import tools.apachi.starlinknav.core.dish.proto.WifiGuestInfoRequest
import tools.apachi.starlinknav.core.log.proto.DishCall
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class PositionSource(val code: Int) {
    AUTO(0), NONE(1), UT_INFO(2), EXTERNAL(3), GPS(4), STARLINK(5), GNC_FUSED(6), GNC_BAD_SAT(7),
    GNC_GPS(8), GNC_PNT(9), GNC_STATIC(10);

    companion object {
        fun name(code: Int): String = entries.firstOrNull { it.code == code }?.name ?: "SOURCE_$code"
    }
}

object AttitudeState {
    const val CONVERGED = 2
    private val names = listOf("FILTER_RESET", "FILTER_UNCONVERGED", "FILTER_CONVERGED", "FILTER_FAULTED", "FILTER_INVALID")
    fun name(code: Int): String = names.getOrNull(code) ?: "STATE_$code"
}

object MobilityClass {
    private val names = listOf("STATIONARY", "NOMADIC", "MOBILE")
    fun name(code: Int): String = names.getOrNull(code) ?: "CLASS_$code"
}

/** UtDisablementCode. While a dish is disabled it refuses GetLocation for every source. */
object Disablement {
    const val OKAY = 1
    private val names = mapOf(
        0 to "UNKNOWN_STATE", 1 to "OKAY", 2 to "NO_ACTIVE_ACCOUNT", 3 to "TOO_FAR_FROM_SERVICE_ADDRESS",
        4 to "IN_OCEAN", 6 to "BLOCKED_COUNTRY", 7 to "DATA_OVERAGE_SANDBOX_POLICY", 8 to "CELL_IS_DISABLED",
        10 to "ROAM_RESTRICTED", 11 to "UNKNOWN_LOCATION", 12 to "ACCOUNT_DISABLED", 13 to "UNSUPPORTED_VERSION",
        14 to "MOVING_TOO_FAST_FOR_POLICY", 15 to "UNDER_AVIATION_FLYOVER_LIMITS", 16 to "BLOCKED_AREA",
        17 to "OUTSIDE_HOME_REGION",
    )
    fun name(code: Int): String = names[code] ?: "CODE_$code"

    /** Any code but OKAY; UNKNOWN_STATE (0) is what a firmware without the field reads as. */
    fun isDisabled(code: Int): Boolean = code != OKAY && code != 0

    /** Short reason for people, e.g. "moving too fast for the plan". */
    fun reason(code: Int): String = when (code) {
        0 -> "UT disablement"
        14 -> "moving too fast for the plan"
        17 -> "outside the home region"
        else -> name(code).lowercase().replace('_', ' ')
    }
}

/** UserClassOfService; a Priority plan reports BUSINESS. */
object ClassOfService {
    private val names = listOf("UNKNOWN", "CONSUMER", "BUSINESS", "BUSINESS_PLUS", "COMMERCIAL_AVIATION")
    fun name(code: Int): String = names.getOrNull(code) ?: "COS_$code"
}

/** Which device answers a call: the dish (192.168.100.1) or the router (the Wi-Fi gateway). */
enum class CallTarget { DISH, ROUTER }

/** How much a command can hurt; the app asks first and names the effect (destructive: the word typed). */
enum class CommandRisk { REVERSIBLE, DISRUPTIVE, DESTRUCTIVE }

/**
 * Codes are persisted in session logs: never renumber, only append. Commands change the dish or
 * the router (reboot, stow, GNSS/RF inhibit, config, resets): only an explicit user action may
 * send one, never the probe or a poller. Router replies hold Wi-Fi passwords: calls with target
 * [CallTarget.ROUTER] are logged redacted only (see [DishCallResult.toRecord]).
 *
 * Parametric commands (sleep schedule, config changes, ...) have a kind for logging and the
 * capability book, but their request is built by a [DeviceCommand]; [request] refuses them.
 */
enum class DishCallKind(
    val code: Int,
    val label: String,
    val locationSource: PositionSource? = null,
    val isCommand: Boolean = false,
    val target: CallTarget = CallTarget.DISH,
    /** Needs parameters: built by a [DeviceCommand], not by [request]. */
    val parametric: Boolean = false,
    /** Restarts the device it is sent to. */
    val restarts: Boolean = false,
    val risk: CommandRisk? = null,
) {
    DEVICE_INFO(1, "device_info"),
    STATUS(2, "status"),
    LOCATION_AUTO(3, "location:AUTO", PositionSource.AUTO),
    LOCATION_GPS(4, "location:GPS", PositionSource.GPS),
    LOCATION_STARLINK(5, "location:STARLINK", PositionSource.STARLINK),
    LOCATION_GNC_FUSED(6, "location:GNC_FUSED", PositionSource.GNC_FUSED),
    DIAGNOSTICS(7, "diagnostics"),
    GNSS_MEASUREMENT(8, "gnss_measurement"),
    LOCATION_UT_INFO(9, "location:UT_INFO", PositionSource.UT_INFO),
    LOCATION_EXTERNAL(10, "location:EXTERNAL", PositionSource.EXTERNAL),
    LOCATION_GNC_BAD_SAT(11, "location:GNC_BAD_SAT", PositionSource.GNC_BAD_SAT),
    LOCATION_GNC_GPS(12, "location:GNC_GPS", PositionSource.GNC_GPS),
    LOCATION_GNC_PNT(13, "location:GNC_PNT", PositionSource.GNC_PNT),
    LOCATION_GNC_STATIC(14, "location:GNC_STATIC", PositionSource.GNC_STATIC),
    REBOOT(15, "reboot", isCommand = true, restarts = true, risk = CommandRisk.DISRUPTIVE),
    STOW(16, "stow", isCommand = true, risk = CommandRisk.DISRUPTIVE),
    UNSTOW(17, "unstow", isCommand = true, risk = CommandRisk.DISRUPTIVE),
    INHIBIT_GPS(18, "inhibit_gps:on", isCommand = true, risk = CommandRisk.REVERSIBLE),
    ALLOW_GPS(19, "inhibit_gps:off", isCommand = true, risk = CommandRisk.REVERSIBLE),
    INHIBIT_RF(20, "inhibit_rf:on", isCommand = true, risk = CommandRisk.DISRUPTIVE),
    ALLOW_RF(21, "inhibit_rf:off", isCommand = true, risk = CommandRisk.REVERSIBLE),
    /** DishConfig.location_request_mode = LOCAL: GetLocation answers on the local network. */
    ALLOW_LOCAL_LOCATION(22, "config:location_local", isCommand = true, risk = CommandRisk.REVERSIBLE),
    CLEAR_OBSTRUCTION_MAP(23, "obstruction_map:clear", isCommand = true, risk = CommandRisk.DISRUPTIVE),
    CONFIG(24, "config"),
    HISTORY(25, "history"),
    OBSTRUCTION_MAP(26, "obstruction_map"),

    // ---- dish, added with the dish and router features (codes 27..)
    NETWORK_INTERFACES(27, "network_interfaces"),
    PING(28, "ping"),
    RADIO_STATS(29, "radio_stats"),
    CONNECTIONS(30, "connections"),
    TIME(31, "time"),
    SPEEDTEST_STATUS(32, "speedtest_status"),
    /** dish_power_save: the sleep schedule ([DishCommand.SleepSchedule]). */
    SLEEP_SCHEDULE(33, "power_save", isCommand = true, parametric = true, risk = CommandRisk.DISRUPTIVE),
    /** dish_set_config with apply flags ([DishCommand.SetConfig]). */
    DISH_SET_CONFIG(34, "config:set", isCommand = true, parametric = true, risk = CommandRisk.REVERSIBLE),
    /** update without a scheduled restart ([DishCommand.Update]): checks for / takes a waiting update, the dish keeps running. */
    DISH_UPDATE(35, "update", isCommand = true, parametric = true, risk = CommandRisk.DISRUPTIVE),
    DISH_FACTORY_RESET(36, "factory_reset:dish", isCommand = true, restarts = true, risk = CommandRisk.DESTRUCTIVE),
    /** ping_host: the device pings an address ([DishCommand.PingHost]). */
    PING_HOST(37, "ping_host", isCommand = true, parametric = true, risk = CommandRisk.REVERSIBLE),

    // ---- router (target ROUTER): reads
    ROUTER_STATUS(38, "router:status", target = CallTarget.ROUTER),
    ROUTER_HISTORY(39, "router:history", target = CallTarget.ROUTER),
    ROUTER_DEVICE_INFO(40, "router:device_info", target = CallTarget.ROUTER),
    ROUTER_DIAGNOSTICS(41, "router:diagnostics", target = CallTarget.ROUTER),
    ROUTER_CLIENTS(42, "router:clients", target = CallTarget.ROUTER),
    ROUTER_CONFIG(43, "router:config", target = CallTarget.ROUTER),
    ROUTER_PING_METRICS(44, "router:ping_metrics", target = CallTarget.ROUTER),
    ROUTER_NETWORK_INTERFACES(45, "router:network_interfaces", target = CallTarget.ROUTER),
    ROUTER_PING(46, "router:ping", target = CallTarget.ROUTER),
    ROUTER_RADIO_STATS(47, "router:radio_stats", target = CallTarget.ROUTER),
    ROUTER_CONNECTIONS(48, "router:connections", target = CallTarget.ROUTER),
    ROUTER_TIME(49, "router:time", target = CallTarget.ROUTER),
    ROUTER_SPEEDTEST_STATUS(50, "router:speedtest_status", target = CallTarget.ROUTER),
    ROUTER_PERSISTENT_STATS(51, "router:persistent_stats", target = CallTarget.ROUTER),
    ROUTER_GUEST_INFO(52, "router:guest_info", target = CallTarget.ROUTER),

    // ---- router: commands
    ROUTER_REBOOT(53, "router:reboot", isCommand = true, target = CallTarget.ROUTER, restarts = true, risk = CommandRisk.DISRUPTIVE),
    ROUTER_FACTORY_RESET(54, "router:factory_reset", isCommand = true, target = CallTarget.ROUTER, restarts = true, risk = CommandRisk.DESTRUCTIVE),
    ROUTER_UPDATE(55, "router:update", isCommand = true, target = CallTarget.ROUTER, parametric = true, risk = CommandRisk.DISRUPTIVE),
    ROUTER_SET_CONFIG(56, "router:config:set", isCommand = true, target = CallTarget.ROUTER, parametric = true, risk = CommandRisk.DISRUPTIVE),
    ROUTER_SET_CLIENT_NAME(57, "router:client_name", isCommand = true, target = CallTarget.ROUTER, parametric = true, risk = CommandRisk.REVERSIBLE),
    /** Pausing a client: wifi_set_config with client_configs ([RouterCommand.BlockClient]). */
    ROUTER_BLOCK_CLIENT(58, "router:client_block", isCommand = true, target = CallTarget.ROUTER, parametric = true, risk = CommandRisk.DISRUPTIVE),
    ROUTER_START_SPEEDTEST(59, "router:speedtest:start", isCommand = true, target = CallTarget.ROUTER, parametric = true, risk = CommandRisk.REVERSIBLE),
    ROUTER_PING_HOST(60, "router:ping_host", isCommand = true, target = CallTarget.ROUTER, parametric = true, risk = CommandRisk.REVERSIBLE),

    /** update with schedule_reboot = true: the dish restarts to install ([DishCommand.Update]); the reboot tracker counts only this one. */
    DISH_UPDATE_REBOOT(61, "update:reboot", isCommand = true, parametric = true, restarts = true, risk = CommandRisk.DISRUPTIVE),

    /**
     * get_location asked of the router (AUTO): home-assistant traces say the router server answers UNIMPLEMENTED; the probe
     * checks. Not a [locationSource] kind (those are the dish's, polled and recorded); a coordinate in an answer is dropped by [Redact].
     */
    ROUTER_LOCATION(62, "router:location", target = CallTarget.ROUTER);

    /**
     * Sources that may answer with a registered or externally supplied position (such as the
     * service address) rather than a measurement: probed only, never recorded or compared.
     */
    val isSensitive: Boolean
        get() = locationSource == PositionSource.UT_INFO || locationSource == PositionSource.EXTERNAL

    /** The device this kind's request is a command for restarts the dish (the reboot tracker reads this). */
    val restartsDish: Boolean get() = restarts && target == CallTarget.DISH

    fun request(id: Long): DeviceRequest {
        require(!parametric) { "$this takes parameters: build it with a DeviceCommand" }
        locationSource?.let { return DeviceRequest(id = id, get_location = GetLocationRequest(source = it.code)) }
        return when (this) {
            DEVICE_INFO, ROUTER_DEVICE_INFO -> DeviceRequest(id = id, get_device_info = GetDeviceInfoRequest())
            STATUS, ROUTER_STATUS -> DeviceRequest(id = id, get_status = GetStatusRequest())
            DIAGNOSTICS, ROUTER_DIAGNOSTICS -> DeviceRequest(id = id, get_diagnostics = GetDiagnosticsRequest())
            GNSS_MEASUREMENT -> DeviceRequest(id = id, get_gnss_measurement = GetGnssMeasurementRequest())
            REBOOT, ROUTER_REBOOT -> DeviceRequest(id = id, reboot = RebootRequest())
            STOW -> DeviceRequest(id = id, dish_stow = DishStowRequest(unstow = false))
            UNSTOW -> DeviceRequest(id = id, dish_stow = DishStowRequest(unstow = true))
            INHIBIT_GPS -> DeviceRequest(id = id, dish_inhibit_gps = DishInhibitGpsRequest(inhibit_gps = true))
            ALLOW_GPS -> DeviceRequest(id = id, dish_inhibit_gps = DishInhibitGpsRequest(inhibit_gps = false))
            INHIBIT_RF -> DeviceRequest(id = id, dish_inhibit_rf = DishInhibitRfRequest(inhibit_rf = true))
            ALLOW_RF -> DeviceRequest(id = id, dish_inhibit_rf = DishInhibitRfRequest(inhibit_rf = false))
            ALLOW_LOCAL_LOCATION -> DeviceRequest(
                id = id,
                dish_set_config = DishSetConfigRequest(DishConfig(location_request_mode = LOCATION_MODE_LOCAL, apply_location_request_mode = true)),
            )
            CLEAR_OBSTRUCTION_MAP -> DeviceRequest(id = id, dish_clear_obstruction_map = DishClearObstructionMapRequest())
            CONFIG -> DeviceRequest(id = id, dish_get_config = DishGetConfigRequest())
            HISTORY, ROUTER_HISTORY -> DeviceRequest(id = id, get_history = GetHistoryRequest())
            OBSTRUCTION_MAP -> DeviceRequest(id = id, dish_get_obstruction_map = DishGetObstructionMapRequest())
            NETWORK_INTERFACES, ROUTER_NETWORK_INTERFACES -> DeviceRequest(id = id, get_network_interfaces = GetNetworkInterfacesRequest())
            PING, ROUTER_PING -> DeviceRequest(id = id, get_ping = GetPingRequest())
            RADIO_STATS, ROUTER_RADIO_STATS -> DeviceRequest(id = id, get_radio_stats = GetRadioStatsRequest())
            CONNECTIONS, ROUTER_CONNECTIONS -> DeviceRequest(id = id, get_connections = GetConnectionsRequest())
            TIME, ROUTER_TIME -> DeviceRequest(id = id, time = GetTimeRequest())
            SPEEDTEST_STATUS, ROUTER_SPEEDTEST_STATUS -> DeviceRequest(id = id, get_speedtest_status = GetSpeedtestStatusRequest())
            DISH_FACTORY_RESET -> DeviceRequest(id = id, dish_factory_reset = DishFactoryResetRequest())
            ROUTER_FACTORY_RESET -> DeviceRequest(id = id, factory_reset = FactoryResetRequest())
            ROUTER_CLIENTS -> DeviceRequest(id = id, wifi_get_clients = WifiGetClientsRequest())
            ROUTER_CONFIG -> DeviceRequest(id = id, wifi_get_config = WifiGetConfigRequest())
            ROUTER_LOCATION -> DeviceRequest(id = id, get_location = GetLocationRequest(source = PositionSource.AUTO.code))
            ROUTER_PING_METRICS -> DeviceRequest(id = id, wifi_get_ping_metrics = WifiGetPingMetricsRequest())
            ROUTER_PERSISTENT_STATS -> DeviceRequest(id = id, get_persistent_stats = GetPersistentStatsRequest())
            ROUTER_GUEST_INFO -> DeviceRequest(id = id, wifi_guest_info = WifiGuestInfoRequest())
            else -> error("no request for $this")
        }
    }

    companion object {
        fun of(code: Int): DishCallKind? = entries.firstOrNull { it.code == code }
        val LOCATIONS = entries.filter { it.locationSource != null }
        val RECORDED_LOCATIONS = LOCATIONS.filterNot { it.isSensitive }
        /** Everything that only reads, on either device: safe for the probe and pollers. */
        val QUERIES = entries.filterNot { it.isCommand }
        val DISH_QUERIES = QUERIES.filter { it.target == CallTarget.DISH }
        val ROUTER_QUERIES = QUERIES.filter { it.target == CallTarget.ROUTER }
        val COMMANDS = entries.filter { it.isCommand }

        /** DishConfig.LocationRequestMode.LOCAL */
        const val LOCATION_MODE_LOCAL = 1
    }
}

class DishCallResult(
    val kind: DishCallKind,
    val sentMonoNs: Long,
    val rttNs: Long,
    val transport: String,
    /** 0 when no HTTP response arrived. */
    val httpStatus: Int,
    val grpcStatus: Int?,
    val grpcMessage: String?,
    val raw: ByteArray?,
    val response: DeviceResponse?,
    val error: String?,
    /**
     * A command went out and the connection ended without an answer (a restarting device, a timeout): it
     * may or may not have been carried out. Not set when it failed before any connection existed.
     */
    val sentNoAnswer: Boolean = false,
    /**
     * [raw] and [response] went through [Redact] (router data): they hold no password, secret or key and
     * no unknown fields, so a router config write must not start from them (see [RouterConfigRead]).
     */
    val payloadRedacted: Boolean = false,
) {
    val ok: Boolean get() = grpcStatus == GrpcStatus.OK && response != null && error == null

    /**
     * The firmware does not offer this call at all ("not supported", UNIMPLEMENTED), as opposed to
     * refusing it for now (e.g. "disabled due to UT disablement" while moving too fast for the plan).
     */
    val unsupported: Boolean
        get() = grpcStatus == GrpcStatus.UNIMPLEMENTED || grpcMessage?.contains("not supported", ignoreCase = true) == true

    /** Refused because the dish is disabled (e.g. moving too fast for the plan), not unsupported. */
    val refusedByDisablement: Boolean
        get() = grpcStatus == GrpcStatus.PERMISSION_DENIED && grpcMessage?.contains("disablement", ignoreCase = true) == true

    /**
     * A copy whose payload carries no Wi-Fi password, secret or key (router replies, or any reply with
     * router data); the original is returned for dish replies. Use it for anything that outlives the call.
     */
    fun redacted(): DishCallResult {
        val storesRouterData = kind.target == CallTarget.ROUTER || response?.hasRouterPayload() == true
        if (!storesRouterData) return this
        val safe = response?.let(Redact::response)
        return DishCallResult(
            kind, sentMonoNs, rttNs, transport, httpStatus, grpcStatus, grpcMessage,
            safe?.let { DeviceResponse.ADAPTER.encode(it) }, safe, error, sentNoAnswer, payloadRedacted = true,
        )
    }

    /** What the call came to, in the classes the capability book remembers. */
    val outcome: CallOutcome get() = CallOutcome.of(this)

    val statusText: String
        get() = when {
            httpStatus == 0 -> "TRANSPORT_ERROR"
            grpcStatus == GrpcStatus.OK && response == null && raw != null -> "DECODE_ERROR"
            grpcStatus == null && httpStatus != 200 -> "HTTP_$httpStatus"
            else -> GrpcStatus.name(grpcStatus)
        }

    /**
     * The session-log record. Router replies hold Wi-Fi passwords and keys, so any call of a router
     * kind (or any reply that turns out to carry router data, e.g. a dish kind sent to the wrong
     * host) is stored redacted only; one that cannot be decoded is stored without its payload.
     * Nothing else writes DishCall records: keep it that way.
     */
    fun toRecord(): DishCall {
        val storesRouterData = kind.target == CallTarget.ROUTER || response?.hasRouterPayload() == true
        val payload: okio.ByteString = when {
            raw == null -> okio.ByteString.EMPTY
            !storesRouterData -> raw.toByteString()
            response != null -> DeviceResponse.ADAPTER.encode(Redact.response(response)).toByteString()
            else -> okio.ByteString.EMPTY
        }
        val note = (if (storesRouterData && raw != null && response == null) " [router payload dropped: undecodable]" else "") +
            (if (sentNoAnswer) " $SENT_NO_ANSWER_NOTE" else "")
        return DishCall(
            kind = kind.code,
            sent_mono_ns = sentMonoNs,
            rtt_ns = rttNs,
            transport = transport,
            http_status = httpStatus,
            grpc_status = grpcStatus,
            grpc_message = grpcMessage.orEmpty(),
            response = payload,
            error = (error.orEmpty() + note).trim(),
        )
    }

    companion object {
        /** Appended to the error text in the session log (the log format has no field for it). */
        const val SENT_NO_ANSWER_NOTE = "[command sent, no answer]"

        /** A call that produced no HTTP response (unreachable dish, bad address, ...). */
        fun failure(kind: DishCallKind, sentMonoNs: Long, rttNs: Long, transport: String, error: String, sentNoAnswer: Boolean = false) =
            DishCallResult(kind, sentMonoNs, rttNs, transport, 0, null, null, null, null, error, sentNoAnswer)

        fun fromRecord(record: DishCall): DishCallResult? {
            val kind = DishCallKind.of(record.kind) ?: return null
            var raw = record.response.takeIf { it.size > 0 }?.toByteArray()
            var decodeError: String? = null
            var response = raw?.let {
                try {
                    DeviceResponse.ADAPTER.decode(it)
                } catch (e: Exception) {
                    decodeError = "decode: ${e.message}"
                    null
                }
            }
            // A log that somehow holds a raw router reply (older or foreign) is read redacted.
            var redacted = false
            if (response != null && (kind.target == CallTarget.ROUTER || response.hasRouterPayload())) {
                response = Redact.response(response)
                raw = DeviceResponse.ADAPTER.encode(response)
                redacted = true
            } else if (response == null && kind.target == CallTarget.ROUTER) {
                raw = null
                redacted = true
            }
            return DishCallResult(
                kind, record.sent_mono_ns, record.rtt_ns, record.transport, record.http_status,
                record.grpc_status, record.grpc_message.ifEmpty { null }, raw, response,
                record.error.ifEmpty { null } ?: decodeError,
                sentNoAnswer = SENT_NO_ANSWER_NOTE in record.error, payloadRedacted = redacted,
            )
        }
    }
}

enum class TransportMode { AUTO, WEB, H2C }

/**
 * Sends calls to one device over [transport]. [target] says which device that is, so a dish
 * client refuses router kinds and the other way round; [RouterClient] is the router flavour.
 */
class DishClient(
    private val transport: GrpcTransport,
    private val monoNanos: () -> Long = System::nanoTime,
    val target: CallTarget = CallTarget.DISH,
) {
    private val ids = AtomicLong(1)
    val transportName: String get() = transport.name
    val endpoint: String get() = transport.endpoint

    fun call(kind: DishCallKind): DishCallResult = exchange(kind) { id -> kind.request(id) }

    /** A command with parameters, or any command object; the result is logged with the command's kind. */
    fun send(command: DeviceCommand): DishCallResult = exchange(command.kind) { id -> command.request(id) }

    private fun exchange(kind: DishCallKind, build: (Long) -> DeviceRequest): DishCallResult {
        require(kind.target == target) { "$kind is a ${kind.target} call, this client talks to the $target" }
        val request = DeviceRequest.ADAPTER.encode(build(ids.getAndIncrement()))
        val t0 = monoNanos()
        val reply = try {
            // A command is not sent twice: see FallbackTransport.
            transport.unary(HANDLE_PATH, request, safeToRepeat = !kind.isCommand)
        } catch (e: Exception) {
            val maybeDelivered = kind.isCommand && !GrpcErrors.neverReachedDevice(e)
            return DishCallResult.failure(kind, t0, monoNanos() - t0, transport.name, e.describe(), sentNoAnswer = maybeDelivered)
        }
        val rtt = monoNanos() - t0
        var decodeError: String? = null
        val response = reply.message?.let { bytes ->
            try {
                DeviceResponse.ADAPTER.decode(bytes)
            } catch (e: Exception) {
                decodeError = "decode: ${e.describe()}"
                null
            }
        }
        return DishCallResult(
            kind, t0, rtt, reply.transport, reply.httpStatus, reply.grpcStatus, reply.grpcMessage,
            reply.message, response, decodeError,
        )
    }

    companion object {
        const val HANDLE_PATH = "/SpaceX.API.Device.Device/Handle"
        const val DEFAULT_HOST = "192.168.100.1"
        const val WEB_PORT = 9201
        const val H2C_PORT = 9200

        /** "host" or "host:port" (the port is the gRPC-Web port; plain gRPC stays on 9200). */
        fun parseAddress(address: String, defaultPort: Int = WEB_PORT): Pair<String, Int> {
            val a = address.trim()
            val colon = a.lastIndexOf(':')
            if (colon > 0 && a.indexOf(':') == colon) {
                a.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 }?.let { return a.substring(0, colon) to it }
            }
            return a to defaultPort
        }

        fun isValidAddress(address: String): Boolean {
            val (host, port) = parseAddress(address)
            return runCatching { HttpUrl.Builder().scheme("http").host(host).port(port).build() }.isSuccess
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        fun create(
            http: OkHttpClient,
            host: String = DEFAULT_HOST,
            mode: TransportMode = TransportMode.AUTO,
            webPort: Int = WEB_PORT,
            h2cPort: Int = H2C_PORT,
            monoNanos: () -> Long = System::nanoTime,
        ): DishClient {
            fun url(port: Int) = HttpUrl.Builder().scheme("http").host(host).port(port).build()
            val web = GrpcWebTransport(http, url(webPort))
            val h2c = GrpcH2cTransport(http, url(h2cPort))
            val transport = when (mode) {
                TransportMode.AUTO -> FallbackTransport(web, h2c)
                TransportMode.WEB -> web
                TransportMode.H2C -> h2c
            }
            return DishClient(transport, monoNanos)
        }
    }
}

/** Includes suppressed exceptions: with the fallback transport the first failure is the informative one. */
internal fun Throwable.describe(): String {
    val own = "${readableName()}: ${message ?: ""}".trimEnd(' ', ':')
    return (suppressed.map { it.describe() } + own).joinToString(" | then ")
}

/**
 * The exception's class name as a user may see it. R8 renames the app's own and some library classes in a release build
 * ("gs0: bad frame length ..."), so a name is printed only when it is still the real one (its package is one R8 leaves
 * alone, or the class is ours and known), else "error".
 */
fun Throwable.readableName(): String = when {
    this is GrpcProtocolException -> "GrpcProtocolException"
    READABLE_PACKAGES.any { javaClass.name.startsWith(it) } -> javaClass.simpleName
    else -> "error"
}

/** Packages whose class names survive a release build (or, for the app's own and the libraries', a debug one). */
private val READABLE_PACKAGES = listOf("java.", "javax.", "kotlin.", "kotlinx.", "android.", "okhttp3.", "okio.", "tools.apachi.")
