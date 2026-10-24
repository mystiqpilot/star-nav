package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.AlignmentStats
import tools.apachi.starlinknav.core.dish.proto.BootInfo
import tools.apachi.starlinknav.core.dish.proto.BootReasonCount
import tools.apachi.starlinknav.core.dish.proto.DeviceInfo
import tools.apachi.starlinknav.core.dish.proto.DeviceRequest
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DeviceState
import tools.apachi.starlinknav.core.dish.proto.DishAlerts
import tools.apachi.starlinknav.core.dish.proto.DishApsStats
import tools.apachi.starlinknav.core.dish.proto.DishClearObstructionMapResponse
import tools.apachi.starlinknav.core.dish.proto.DishConfig
import tools.apachi.starlinknav.core.dish.proto.DishFactoryResetResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetConfigResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetDiagnosticsResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetHistoryResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetObstructionMapResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.proto.DishGpsStats
import tools.apachi.starlinknav.core.dish.proto.DishInhibitGpsResponse
import tools.apachi.starlinknav.core.dish.proto.DishInhibitRfResponse
import tools.apachi.starlinknav.core.dish.proto.DishObstructionStats
import tools.apachi.starlinknav.core.dish.proto.DishOutage
import tools.apachi.starlinknav.core.dish.proto.DishReadyStates
import tools.apachi.starlinknav.core.dish.proto.DishSetConfigResponse
import tools.apachi.starlinknav.core.dish.proto.DishStowResponse
import tools.apachi.starlinknav.core.dish.proto.DishUpsuStats
import tools.apachi.starlinknav.core.dish.proto.DownstreamRoutersEntry
import tools.apachi.starlinknav.core.dish.proto.EthernetNetworkInterface
import tools.apachi.starlinknav.core.dish.proto.EventLog
import tools.apachi.starlinknav.core.dish.proto.GetConnectionsResponse
import tools.apachi.starlinknav.core.dish.proto.GetDeviceInfoResponse
import tools.apachi.starlinknav.core.dish.proto.GetLocationResponse
import tools.apachi.starlinknav.core.dish.proto.GetNetworkInterfacesResponse
import tools.apachi.starlinknav.core.dish.proto.GetPingResponse
import tools.apachi.starlinknav.core.dish.proto.GetRadioStatsResponse
import tools.apachi.starlinknav.core.dish.proto.GetSpeedtestStatusResponse
import tools.apachi.starlinknav.core.dish.proto.GetTimeResponse
import tools.apachi.starlinknav.core.dish.proto.InitializationDurationSeconds
import tools.apachi.starlinknav.core.dish.proto.LLAPosition
import tools.apachi.starlinknav.core.dish.proto.NetworkInterface
import tools.apachi.starlinknav.core.dish.proto.PingHostResponse
import tools.apachi.starlinknav.core.dish.proto.PingResult
import tools.apachi.starlinknav.core.dish.proto.PingResultEntry
import tools.apachi.starlinknav.core.dish.proto.PingTarget
import tools.apachi.starlinknav.core.dish.proto.Quaternion
import tools.apachi.starlinknav.core.dish.proto.RebootResponse
import tools.apachi.starlinknav.core.dish.proto.RouterInfo
import tools.apachi.starlinknav.core.dish.proto.ServiceConnection
import tools.apachi.starlinknav.core.dish.proto.ServiceConnectionEntry
import tools.apachi.starlinknav.core.dish.proto.SoftwareUpdateStats
import tools.apachi.starlinknav.core.dish.proto.SpeedtestStatus
import tools.apachi.starlinknav.core.dish.proto.UXEvent
import tools.apachi.starlinknav.core.dish.proto.UpdateResponse
import java.io.IOException
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/** A simulated device (dish or router): what the fake servers and [FakeTransport] serve. */
interface FakeDevice {
    /** Restarting: a real device neither answers nor accepts connections then. */
    fun isDown(): Boolean

    /** The response, or a gRPC status code and message when the call is "not available". */
    fun handle(req: DeviceRequest): Pair<DeviceResponse?, Pair<Int, String>>
}

/** Plugs a [FakeDevice] in as a transport, with no sockets: for tests and tools that talk to a simulation directly. */
class FakeTransport(private val device: FakeDevice, override val name: String = "fake", override val endpoint: String = "fake://device") : GrpcTransport {
    override fun unary(path: String, request: ByteArray, safeToRepeat: Boolean): GrpcReply {
        if (device.isDown()) throw IOException("fake device: restarting")
        val req = DeviceRequest.ADAPTER.decode(request)
        val (resp, status) = device.handle(req)
        return GrpcReply(
            name, 200, status.first, status.second.ifEmpty { null }, resp?.let { DeviceResponse.ADAPTER.encode(it) },
        )
    }
}

/** Which request arms a user-typed token ("time", "radio_stats", "router:ping", "get_ping") stands for, for `--unimplemented`. */
object FakeArms {
    fun forToken(token: String): Set<String> = FakeSupport.armsFor(token)
}

/** Shared bits of the simulations. */
internal object FakeSupport {
    /** Request arms by proto field name; the first one set is the call. */
    fun armName(req: DeviceRequest): String? = when {
        req.reboot != null -> "reboot"
        req.get_status != null -> "get_status"
        req.get_history != null -> "get_history"
        req.get_device_info != null -> "get_device_info"
        req.get_ping != null -> "get_ping"
        req.factory_reset != null -> "factory_reset"
        req.update != null -> "update"
        req.get_network_interfaces != null -> "get_network_interfaces"
        req.ping_host != null -> "ping_host"
        req.get_location != null -> "get_location"
        req.get_persistent_stats != null -> "get_persistent_stats"
        req.get_connections != null -> "get_connections"
        req.start_speedtest != null -> "start_speedtest"
        req.get_speedtest_status != null -> "get_speedtest_status"
        req.get_radio_stats != null -> "get_radio_stats"
        req.time != null -> "time"
        req.dish_stow != null -> "dish_stow"
        req.dish_get_obstruction_map != null -> "dish_get_obstruction_map"
        req.dish_set_config != null -> "dish_set_config"
        req.dish_get_config != null -> "dish_get_config"
        req.dish_power_save != null -> "dish_power_save"
        req.dish_inhibit_gps != null -> "dish_inhibit_gps"
        req.dish_clear_obstruction_map != null -> "dish_clear_obstruction_map"
        req.dish_factory_reset != null -> "dish_factory_reset"
        req.dish_inhibit_rf != null -> "dish_inhibit_rf"
        req.wifi_set_config != null -> "wifi_set_config"
        req.wifi_get_clients != null -> "wifi_get_clients"
        req.wifi_get_ping_metrics != null -> "wifi_get_ping_metrics"
        req.wifi_get_config != null -> "wifi_get_config"
        req.wifi_set_client_given_name != null -> "wifi_set_client_given_name"
        req.wifi_guest_info != null -> "wifi_guest_info"
        req.get_diagnostics != null -> "get_diagnostics"
        req.get_gnss_measurement != null -> "get_gnss_measurement"
        else -> null
    }

    /** How the firmware words an unimplemented request: "Unimplemented: *device.Request_GetRadioStats". */
    fun unimplemented(arm: String): Pair<Int, String> =
        GrpcStatus.UNIMPLEMENTED to "Unimplemented: *device.Request_" + arm.split('_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    /** The proto arm names the user means by a token such as "radio_stats", "router:time" or "get_ping". */
    fun armsFor(token: String): Set<String> {
        val t = token.trim().lowercase()
        if (t.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        for (kind in DishCallKind.entries) {
            if (kind.parametric) continue
            val arm = runCatching { armName(kind.request(1)) }.getOrNull() ?: continue
            if (t == kind.name.lowercase() || t == kind.label.lowercase() || t == kind.label.lowercase().substringAfter(':') || t == arm) out += arm
        }
        if (out.isEmpty() && t.all { it.isLetterOrDigit() || it == '_' }) out += t // an arm name the kinds do not cover
        return out
    }

    /** Deterministic noise in 0..1 for sample [k] of a series [salt]: the same sample reads the same on every poll. */
    fun unit(k: Long, salt: Int): Float {
        var z = k * -7046029254386353131L + salt * 0x632BE59BD9B4E019L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        return (z ushr 40).toFloat() / (1 shl 24)
    }

    /**
     * A ring of [size] slots (900 for a history) holding samples [current-size, current): slot i holds the newest
     * sequence number k with k % size == i that is below [current] (never-written slots are 0).
     */
    fun ring(current: Long, size: Int = 900, value: (Long) -> Float): List<Float> {
        val base = current - current % size
        return List(size) { i ->
            var k = base + i
            if (k >= current) k -= size
            if (k < 0) 0f else value(k)
        }
    }
}

/**
 * Synthetic dish for development without hardware: a car circling at constant speed. Its
 * answers follow what a real Gen 3 Mini (firmware 2026.05.13) returned on 2026-10-14: AUTO
 * is labelled STARLINK with sigma ~1.6 m, GPS reports a nominal 15 m, the Starlink-only
 * source ~100 m, the GNC_* / UT_INFO / EXTERNAL sources are "not supported", GNSS
 * measurements are unimplemented, and the quaternion follows the verified convention
 * (body->NED, body +z = array normal). During periodic spoofing windows the GPS source
 * jumps to Lima. Noise levels are invented. Commands (reboot, stow, GNSS/RF inhibit, local
 * location access, sleep schedule, update, factory reset) change its state. Like the
 * user's Mini it starts with a software update waiting for a restart; a restart (commanded, or
 * every [rebootEverySec]) takes it away for [rebootDownSec] and installs that update.
 *
 * The newer calls answer as the 2026.09.18 firmware schema allows: 900-sample history rings consistent
 * with the uptime (the same second reads the same on every poll), power 25-40 W, an obstruction map with
 * a blocked patch, initialization durations that fill in as the uptime grows, alerts, an event log with
 * the outages, time, network interfaces, ping, connections and config. [denySetConfig] makes
 * dish_set_config answer PERMISSION_DENIED as a Mini probably does; [unimplemented] lists request arms
 * (proto field names such as "time" or "get_ping") that answer UNIMPLEMENTED; the wifi_* arms always do
 * (they belong to the router: [FakeRouterSimulation]).
 */
class FakeDishSimulation(
    private val centerLat: Double = 50.4501,
    private val centerLon: Double = 30.5234,
    private val radiusM: Double = 400.0,
    private val speedMps: Double = 12.0,
    private val spoofEverySec: Long = 120,
    private val spoofForSec: Long = 30,
    private val tiltDeg: Double = 20.0,
    /** Periodic "moving too fast for policy" disablement, as a real Mini did at ~77 km/h. 0 = never. */
    private val disableEverySec: Long = 0,
    private val disableForSec: Long = 0,
    /**
     * `DishConfig.location_request_mode` = LOCAL: whether GetLocation answers on the local network (the Starlink app's
     * setting). It shows in dish_get_config and in the status' embedded config, a dish_set_config that is accepted
     * ([denySetConfig] off) changes it, and with it off every GetLocation is refused PERMISSION_DENIED. `slnav fake-dish
     * --location-off` starts it off, the way a Mini starts before the setting is switched on.
     */
    @Volatile private var locationLocal: Boolean = true,
    /** Restart by itself this often (0 = never), reporting [rebootReason] (RebootReason) afterwards. */
    private val rebootEverySec: Long = 0,
    private val rebootReason: Int = 2,
    /** How long a restart keeps it from answering; it boots 3 s into that. */
    private val rebootDownSec: Long = 40,
    @Volatile private var updateWaiting: Boolean = true,
    /** dish_set_config answers PERMISSION_DENIED (a Mini probably leaves these settings to the logged-in app). */
    @Volatile var denySetConfig: Boolean = false,
    /** Request arms (proto field names) that answer UNIMPLEMENTED. */
    unimplemented: Set<String> = emptySet(),
    /** The local UTC offset the dish reports, seconds. */
    private val utcOffsetS: Int = 3 * 3600,
    /** A dish with motors (stow works); a Mini has none and refuses stow with an error carrying HAS_ACTUATORS_NO. */
    private val hasActuators: Boolean = false,
    /**
     * Dish yaw (body +x, the status heading) minus the car's track, degrees: the user's Mini sits on the roof facing
     * backwards (−177°). The inertial filter needs the two to agree, so the fake's heading follows its track.
     */
    val mountOffsetDeg: Double = -177.0,
    /**
     * A sideways fault on the AUTO and diagnostics positions (not GPS or Starlink-only): this many metres to the right
     * of the track (negative = left) from [faultAtS] seconds after the start, reached over [faultRampS] seconds
     * (0 = a step), for [faultForS] seconds (0 = for good). Exercises the filter's dish-error state on an emulator.
     */
    private val faultSidewaysM: Double = 0.0,
    private val faultAtS: Double = 0.0,
    private val faultRampS: Double = 0.0,
    private val faultForS: Double = 0.0,
    private val startNs: Long = System.nanoTime(),
    private val clock: () -> Long = System::nanoTime,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) : FakeDevice {
    private val random = Random(42)
    private val unimplementedArms: Set<String> = unimplemented.toSet()
    private val actuators: Int get() = if (hasActuators) HasActuators.YES else HasActuators.NO
    /** Up for 656 s at the start, as the user's Mini was on its first recorded drive. */
    @Volatile private var bootNs = startNs - 656_000_000_000L
    @Volatile private var downUntilNs = Long.MIN_VALUE
    @Volatile private var lastAutoRebootNs = startNs
    @Volatile private var bootcount = 6
    @Volatile private var software = "fake-2026.05"
    @Volatile private var lastRebootReason = 0
    @Volatile private var lastBootReason = 2
    @Volatile private var stowed = false
    @Volatile private var gpsInhibited = false
    @Volatile private var rfInhibited = false
    @Volatile private var rfInhibitedSinceWallMs = 0L
    @Volatile private var snowMelt = 0
    @Volatile private var levelDish = 0
    @Volatile private var updateHour = 3
    @Volatile private var deferral = false
    @Volatile private var sleepStart = 0
    @Volatile private var sleepDuration = 0
    @Volatile private var sleepEnabled = false
    @Volatile private var obstructionCleared = false

    /** The commands this simulation has accepted, in order, by request arm (for tests and for watching a session). */
    val accepted: List<String> get() = acceptedLog.toList()
    private val acceptedLog = java.util.Collections.synchronizedList(ArrayList<String>())

    class Truth(val lat: Double, val lon: Double, val headingDeg: Double, val spoofed: Boolean, val tSec: Double, val disabled: Boolean)

    fun truth(): Truth = truthAt((clock() - startNs) / 1e9)

    /** Where the car was [t] seconds after the start. */
    fun truthAt(t: Double): Truth {
        val angle = speedMps * t / radiusM
        val north = radiusM * sin(angle)
        val east = radiusM * cos(angle)
        val lat = centerLat + north / 110_540.0
        val lon = centerLon + east / (111_320.0 * cos(Math.toRadians(centerLat)))
        val heading = (Math.toDegrees(-angle) + 360.0) % 360.0
        val spoofed = spoofEverySec > 0 && (t.toLong() % spoofEverySec) >= spoofEverySec - spoofForSec
        val disabled = disableEverySec > 0 && (t.toLong() % disableEverySec) >= disableEverySec - disableForSec
        return Truth(lat, lon, heading, spoofed, t, disabled)
    }

    /** The dish heading (body +x) for a car on [trackDeg]. */
    fun dishHeadingDeg(trackDeg: Double): Double = ((trackDeg + mountOffsetDeg) % 360.0 + 360.0) % 360.0

    /** The sideways fault in force at [tSec], m to the right of the track. */
    fun faultAt(tSec: Double): Double {
        if (faultSidewaysM == 0.0 || tSec < faultAtS || (faultForS > 0 && tSec >= faultAtS + faultForS)) return 0.0
        return if (faultRampS > 0) faultSidewaysM * minOf((tSec - faultAtS) / faultRampS, 1.0) else faultSidewaysM
    }

    /** [lat]/[lon] moved by the fault in force, to the right of the track. */
    private fun faulty(tr: Truth, lat: Double, lon: Double): Pair<Double, Double> {
        val d = faultAt(tr.tSec)
        if (d == 0.0) return lat to lon
        val h = Math.toRadians(tr.headingDeg + 90.0)
        return lat + d * cos(h) / 110_540.0 to lon + d * sin(h) / (111_320.0 * cos(Math.toRadians(lat)))
    }

    /** Body +x along [headingDeg] (the dish heading: track + mount), array normal tilted [tiltDeg] from vertical. */
    private fun quaternion(headingDeg: Double): Quaternion {
        val yaw = Math.toRadians(headingDeg) / 2
        val roll = Math.toRadians(-(180.0 - tiltDeg)) / 2
        return Quaternion(
            q_scalar = (cos(yaw) * cos(roll)).toFloat(),
            q_x = (cos(yaw) * sin(roll)).toFloat(),
            q_y = (sin(yaw) * sin(roll)).toFloat(),
            q_z = (sin(yaw) * cos(roll)).toFloat(),
        )
    }

    /** Restarting: a real dish neither answers nor accepts connections then. */
    override fun isDown(): Boolean {
        val now = clock()
        // The period counts time up: from the start, then from coming back.
        if (rebootEverySec > 0 && now >= downUntilNs && now - lastAutoRebootNs >= rebootEverySec * 1_000_000_000L) {
            restart(rebootReason, bootReason = 6)
            lastAutoRebootNs = downUntilNs
        }
        return now < downUntilNs
    }

    private fun restart(reason: Int, bootReason: Int) {
        val now = clock()
        downUntilNs = now + rebootDownSec * 1_000_000_000L
        bootNs = now + 3_000_000_000L
        bootcount++
        stowed = false
        gpsInhibited = false // inhibit_gps resets to "GPS enabled" at boot
        lastRebootReason = reason
        lastBootReason = bootReason
        if (updateWaiting) {
            updateWaiting = false
            software = "fake-2026.09"
        }
    }

    private fun uptimeS(): Long = ((clock() - bootNs) / 1_000_000_000L).coerceAtLeast(0)

    private fun utcMinuteNow(): Int = ((wallClockMs() / 60_000L) % SleepWindow.DAY).toInt()

    private fun asleep(): Boolean = sleepEnabled && sleepDuration > 0 && SleepWindow(sleepStart, sleepDuration.coerceIn(1, SleepWindow.DAY), true).contains(utcMinuteNow())

    /** Power draw at the second [k] of the history, W. */
    private fun powerAt(k: Long, sleeping: Boolean, rf: Boolean): Float =
        if (sleeping) 7f + FakeSupport.unit(k, 5) else if (rf) 22f + 2f * FakeSupport.unit(k, 5) else 32f + 4f * sin(k / 90.0).toFloat() + 3f * (FakeSupport.unit(k, 4) - 0.5f)

    override fun handle(req: DeviceRequest): Pair<DeviceResponse?, Pair<Int, String>> {
        if (isDown()) return null to (GrpcStatus.UNAVAILABLE to "fake dish: restarting")
        val arm = FakeSupport.armName(req)
        // The router's arms and the ones the user switched off are not implemented by a dish.
        if (arm != null && (arm in unimplementedArms || arm.startsWith("wifi_") || arm == "factory_reset" || arm == "start_speedtest" || arm == "get_persistent_stats")) {
            return null to FakeSupport.unimplemented(arm)
        }
        val tr = truth()
        val ok = GrpcStatus.OK to ""
        fun empty() = DeviceResponse(id = req.id) to ok
        fun accepted() { arm?.let(acceptedLog::add) }
        return when {
            req.reboot != null -> {
                accepted()
                // RebootReason APP: what a request over this API is assumed to report; BootReason COMMAND.
                restart(5, bootReason = 3)
                DeviceResponse(id = req.id, reboot = RebootResponse()) to ok
            }
            req.update != null -> {
                accepted()
                if (updateWaiting && req.update!!.schedule_reboot == true) restart(3, bootReason = 4) // SWUPDATE_NOW
                DeviceResponse(id = req.id, update = UpdateResponse()) to ok
            }
            req.dish_factory_reset != null -> {
                accepted()
                // Back to the factory default: the local-network location setting is off until it is switched on again.
                locationLocal = false
                snowMelt = 0; levelDish = 0; updateHour = 3; deferral = false
                sleepStart = 0; sleepDuration = 0; sleepEnabled = false
                rfInhibited = false
                obstructionCleared = true
                restart(7, bootReason = 3)
                DeviceResponse(id = req.id, dish_factory_reset = DishFactoryResetResponse()) to ok
            }
            req.dish_stow != null -> {
                // dishwatch: a dish without motors answers with an error carrying HAS_ACTUATORS_NO (the status code is a guess).
                if (!hasActuators) return null to (GrpcStatus.FAILED_PRECONDITION to "HAS_ACTUATORS_NO")
                accepted()
                stowed = !req.dish_stow!!.unstow
                DeviceResponse(id = req.id, dish_stow = DishStowResponse()) to ok
            }
            req.dish_inhibit_gps != null -> {
                accepted()
                gpsInhibited = req.dish_inhibit_gps!!.inhibit_gps
                DeviceResponse(id = req.id, dish_inhibit_gps = DishInhibitGpsResponse(gpsInhibited)) to ok
            }
            req.dish_inhibit_rf != null -> {
                accepted()
                val on = req.dish_inhibit_rf!!.inhibit_rf
                if (on && !rfInhibited) rfInhibitedSinceWallMs = wallClockMs()
                rfInhibited = on
                DeviceResponse(id = req.id, dish_inhibit_rf = DishInhibitRfResponse(rfInhibited)) to ok
            }
            req.dish_power_save != null -> {
                val p = req.dish_power_save!!
                if (p.power_save_duration_minutes == 0 || p.power_save_duration_minutes > SleepWindow.DAY || p.power_save_start_minutes >= SleepWindow.DAY) {
                    // The real dish rejects a zero duration even when disabling.
                    null to (3 to "Invalid power save schedule: duration must be 1..1440 minutes and start below 1440")
                } else {
                    accepted()
                    sleepStart = p.power_save_start_minutes
                    sleepDuration = p.power_save_duration_minutes
                    sleepEnabled = p.enable_power_save
                    empty()
                }
            }
            req.dish_set_config != null -> {
                if (denySetConfig) {
                    null to (GrpcStatus.PERMISSION_DENIED to "Permission denied")
                } else {
                    accepted()
                    val c = req.dish_set_config!!.dish_config
                    if (c != null) {
                        if (c.apply_location_request_mode) locationLocal = c.location_request_mode == DishCallKind.LOCATION_MODE_LOCAL
                        if (c.apply_snow_melt_mode) snowMelt = c.snow_melt_mode
                        if (c.apply_level_dish_mode) levelDish = c.level_dish_mode
                        if (c.apply_swupdate_reboot_hour) updateHour = c.swupdate_reboot_hour
                        if (c.apply_swupdate_three_day_deferral_enabled) deferral = c.swupdate_three_day_deferral_enabled
                        if (c.apply_power_save_start_minutes) sleepStart = c.power_save_start_minutes
                        if (c.apply_power_save_duration_minutes) sleepDuration = c.power_save_duration_minutes
                        if (c.apply_power_save_mode) sleepEnabled = c.power_save_mode
                    }
                    DeviceResponse(id = req.id, dish_set_config = DishSetConfigResponse(config())) to ok
                }
            }
            req.dish_get_config != null -> DeviceResponse(id = req.id, dish_get_config = DishGetConfigResponse(config())) to ok
            req.get_history != null -> DeviceResponse(id = req.id, dish_get_history = history()) to ok
            req.dish_get_obstruction_map != null -> DeviceResponse(id = req.id, dish_get_obstruction_map = obstructionMap()) to ok
            req.dish_clear_obstruction_map != null -> {
                accepted()
                obstructionCleared = true
                DeviceResponse(id = req.id, dish_clear_obstruction_map = DishClearObstructionMapResponse()) to ok
            }
            req.get_device_info != null -> DeviceResponse(id = req.id, get_device_info = GetDeviceInfoResponse(deviceInfo())) to ok
            req.time != null -> DeviceResponse(id = req.id, time = GetTimeResponse(unix_nano = wallClockMs() * 1_000_000L)) to ok
            req.get_network_interfaces != null -> DeviceResponse(id = req.id, get_network_interfaces = networkInterfaces()) to ok
            req.get_ping != null -> DeviceResponse(id = req.id, get_ping = ping()) to ok
            req.ping_host != null -> {
                accepted()
                val target = req.ping_host!!.address
                DeviceResponse(id = req.id, ping_host = PingHostResponse(PingResult(PingTarget(service = "host", address = target), 0f, 34f))) to ok
            }
            req.get_connections != null -> DeviceResponse(id = req.id, get_connections = connections()) to ok
            req.get_radio_stats != null -> DeviceResponse(id = req.id, get_radio_stats = GetRadioStatsResponse()) to ok // a dish has no Wi-Fi radios
            req.get_speedtest_status != null -> DeviceResponse(id = req.id, get_speedtest_status = GetSpeedtestStatusResponse(SpeedtestStatus(running = false))) to ok
            req.get_status != null -> DeviceResponse(id = req.id, dish_get_status = status(tr)) to ok
            req.get_location != null && tr.disabled ->
                null to (GrpcStatus.PERMISSION_DENIED to "GetLocation requests disabled due to UT disablement")
            // With the setting off every source is refused. The wording is the one the research found in the firmware's
            // source comments (not seen on a real Mini); it is neither a UT disablement nor a plan restriction.
            req.get_location != null && !locationLocal ->
                null to (GrpcStatus.PERMISSION_DENIED to "GetLocation requests are not enabled on this device")
            req.get_location != null -> {
                val src = req.get_location!!.source
                // A Mini's fix is ~0.45 s old when it arrives (diagnostics gps_time_s says the same, below).
                val fixTr = truthAt(tr.tSec - FIX_AGE_S)
                val (lat, lon, sigma) = when (src) {
                    // With GNSS inhibited a real dish's AUTO behaviour is unknown; a coarse fix stands in.
                    PositionSource.AUTO.code -> (if (gpsInhibited) jitter(fixTr, 15.0, 30.0) else jitter(fixTr, 0.15, 1.65)).let { (la, lo, sg) ->
                        faulty(fixTr, la, lo).let { Triple(it.first, it.second, sg) }
                    }
                    PositionSource.GPS.code -> if (tr.spoofed) Triple(-12.0464, -77.0428, 15.0) else jitter(tr, 3.0, 15.0)
                    PositionSource.STARLINK.code -> jitter(tr, 40.0, 80.0 + 40.0 * random.nextDouble())
                    else -> return null to (2 to "Failed to get location: Requested position source is not supported: $src")
                }
                val reported = if (src == PositionSource.AUTO.code) PositionSource.STARLINK.code else src
                DeviceResponse(
                    id = req.id,
                    get_location = GetLocationResponse(
                        LLAPosition(lat, lon, 115.0 + random.nextGaussian()), source = reported, sigma_m = sigma,
                        horizontal_speed_mps = speedMps, vertical_speed_mps = 0.2 * random.nextGaussian(),
                    ),
                ) to ok
            }
            req.get_diagnostics != null -> {
                val fixTr = truthAt(tr.tSec - FIX_AGE_S)
                val (lat, lon) = jitter(fixTr, 0.15, 1.6).let { faulty(fixTr, it.first, it.second) }
                val info = deviceInfo()
                DeviceResponse(
                    id = req.id,
                    dish_get_diagnostics = DishGetDiagnosticsResponse(
                        id = info.id, hardware_version = info.hardware_version, software_version = info.software_version,
                        utc_offset_s = utcOffsetS,
                        disablement_code = if (tr.disabled) 14 else 1,
                        alerts = DishGetDiagnosticsResponse.Alerts(software_install_pending = updateWaiting, dish_is_heating = false),
                        hardware_self_test = HardwareSelfTest.PASSED,
                        alignment_stats = DishGetDiagnosticsResponse.AlignmentStats(
                            boresight_azimuth_deg = quaternion(dishHeadingDeg(tr.headingDeg)).attitude().normalAzimuthDeg.toFloat(),
                            boresight_elevation_deg = 70f, desired_boresight_azimuth_deg = 0f, desired_boresight_elevation_deg = 90f,
                        ),
                        location = DishGetDiagnosticsResponse.Location(
                            // Official API: "whether local location queries are enabled for this terminal" (the setting).
                            enabled = locationLocal, latitude = lat, longitude = lon, altitude_meters = 115.5,
                            uncertainty_meters_valid = true, uncertainty_meters = 1.59,
                            // The real dish reported fixes ~0.5 s older than the phone clock.
                            gps_time_s = (wallClockMs() - 450 - 315_964_800_000L + 18_000L) / 1000.0,
                        ),
                    ),
                ) to ok
            }
            else -> null to FakeSupport.unimplemented(arm ?: "unknown")
        }
    }

    private fun deviceInfo() = DeviceInfo(
        id = "ut-fake-0000-0001", hardware_version = "mini1_fake", software_version = software, country_code = "UA",
        utc_offset_s = utcOffsetS, bootcount = bootcount, software_partitions_equal = !updateWaiting,
        boot = BootInfo(
            last_reason = lastBootReason, last_count = bootcount,
            count_by_reason = listOf(BootReasonCount(2, 1), BootReasonCount(lastBootReason, bootcount - 1)).distinctBy { it.reason },
        ),
    )

    private fun config() = DishConfig(
        location_request_mode = if (locationLocal) DishCallKind.LOCATION_MODE_LOCAL else 0, snow_melt_mode = snowMelt,
        level_dish_mode = levelDish, swupdate_reboot_hour = updateHour, swupdate_three_day_deferral_enabled = deferral,
        power_save_start_minutes = sleepStart, power_save_duration_minutes = sleepDuration, power_save_mode = sleepEnabled,
    )

    /** The ongoing outage, if any, the way the status reports it. */
    private fun currentOutage(uptime: Long): DishOutage? {
        val nowNs = wallClockMs() * 1_000_000L
        return when {
            uptime < 30 -> DishOutage(cause = 1, start_timestamp_ns = nowNs - uptime * 1_000_000_000L, duration_ns = uptime * 1_000_000_000L)
            stowed -> DishOutage(cause = 2, start_timestamp_ns = nowNs - 60_000_000_000L, duration_ns = 60_000_000_000L)
            rfInhibited -> DishOutage(cause = 14, start_timestamp_ns = rfInhibitedSinceWallMs * 1_000_000L, duration_ns = (wallClockMs() - rfInhibitedSinceWallMs) * 1_000_000L)
            asleep() -> DishOutage(cause = 11, start_timestamp_ns = nowNs - 60_000_000_000L, duration_ns = 60_000_000_000L)
            else -> null
        }
    }

    private fun status(tr: Truth): DishGetStatusResponse {
        val q = quaternion(dishHeadingDeg(tr.headingDeg))
        val at = q.attitude()
        val uptime = uptimeS()
        val outage = currentOutage(uptime)
        val booting = uptime < 30
        val sleeping = asleep()
        val rf = rfInhibited
        val power = powerAt(uptime, sleeping, rf)
        return DishGetStatusResponse(
            device_info = deviceInfo(),
            device_state = DeviceState(uptime_s = uptime),
            gps_stats = DishGpsStats(
                gps_valid = !gpsInhibited && !booting, gps_sats = if (gpsInhibited) 0 else if (tr.spoofed) 9 else 17, inhibit_gps = gpsInhibited,
            ),
            stow_requested = stowed,
            outage = outage,
            alerts = DishAlerts(
                is_heating = snowMelt == 1, lower_signal_than_predicted = tr.spoofed, install_pending = updateWaiting,
                is_power_save_idle = sleeping,
            ),
            obstruction_stats = DishObstructionStats(fraction_obstructed = 0.012f, valid_s = tr.tSec.toFloat(), patches_valid = 900),
            config = config(),
            reboot_reason = lastRebootReason,
            software_update_state = if (updateWaiting) SoftwareUpdate.REBOOT_REQUIRED else 1,
            software_update_stats = SoftwareUpdateStats(
                software_update_state = if (updateWaiting) SoftwareUpdate.REBOOT_REQUIRED else 1,
                update_requires_reboot = updateWaiting,
                reboot_scheduled_utc_time = if (updateWaiting) nextHourUtcS(updateHour) else 0,
            ),
            alignment_stats = AlignmentStats(
                has_actuators = actuators,
                attitude_estimation_state = AttitudeState.CONVERGED, attitude_uncertainty_deg = 0.5f,
                tilt_angle_deg = tiltDeg.toFloat(),
                desired_boresight_azimuth_deg = 0f, desired_boresight_elevation_deg = 90f,
            ),
            has_actuators = actuators,
            boresight_azimuth_deg = at.normalAzimuthDeg.toFloat(),
            boresight_elevation_deg = at.normalElevationDeg.toFloat(),
            ned2dish_quaternion = q,
            is_moving_fast_persisted = speedMps > 10,
            mobility_class = 2,
            class_of_service = 2,
            disablement_code = if (tr.disabled) 14 else 1,
            pop_ping_latency_ms = if (rf || outage != null) 0f else 31f,
            pop_ping_drop_rate = if (rf || outage != null) 1f else 0.004f,
            downlink_throughput_bps = if (rf || sleeping) 0f else 2.4e6f,
            uplink_throughput_bps = if (rf || sleeping) 0f else 3.1e5f,
            eth_speed_mbps = 1000,
            is_snr_above_noise_floor = !rf,
            ready_states = if (booting) DishReadyStates() else DishReadyStates(cady = false, scp = true, l1l2 = true, xphy = true, aap = true, rf = !rf),
            signal_quality = if (rf) 0f else 0.82f,
            initialization_duration_seconds = initializationDurations(uptime),
            has_signed_cals = true,
            account_shard = 1,
            nat_flag = 2,
            treat_as_metered = false,
            user_debug_mode_enabled = false,
            dl_bandwidth_restricted_reason = RateLimitReason.NO_LIMIT,
            ul_bandwidth_restricted_reason = RateLimitReason.NO_LIMIT,
            upsu_stats = DishUpsuStats(dish_power = power, router_power = 4.5f),
            aps_stats = DishApsStats(dish_power = power),
            connected_routers = listOf("Router-fake-0001"),
            downstream_routers = listOf(DownstreamRoutersEntry("Router-fake-0001", RouterInfo(role = RouterRole.CONTROLLER, last_seen = wallClockMs() / 1000))),
        )
    }

    /** Stage times of a typical Mini start-up (Gen 3 Standard sample: gps_valid 14, rf_ready 26, ekf 29, stable 39); 0 while not reached. */
    private fun initializationDurations(uptime: Long): InitializationDurationSeconds {
        fun at(s: Int) = if (uptime >= s) s else 0
        return InitializationDurationSeconds(
            gps_valid = at(14), attitude_initialization = at(12), rf_ready = at(26), ekf_converged = at(29), burst_detected = at(31),
            first_cplane = at(33), initial_network_entry = at(35), network_schedule = at(36), first_pop_ping = at(38), stable_connection = at(39),
        )
    }

    /** The next [hour]:00 UTC as Unix seconds. */
    private fun nextHourUtcS(hour: Int): Long {
        val day = 86_400L
        val now = wallClockMs() / 1000
        val next = now - now % day + hour * 3600L
        return if (next > now) next else next + day
    }

    /**
     * Outages in the history, oldest first: an obstruction of 2.4 s every 10 minutes from the 5th minute of the uptime, a
     * 1 s lack of satellites every 22 minutes, and the ongoing one the dish is in. They sit at fixed seconds of the uptime, so
     * a later poll shows the same ones at the same samples.
     */
    private fun outages(): List<DishOutage> {
        val nowNs = wallClockMs() * 1_000_000L
        val uptime = uptimeS()
        fun at(sinceBootS: Long, cause: Int, durS: Double) = DishOutage(
            cause = cause, start_timestamp_ns = nowNs - (uptime - sinceBootS) * 1_000_000_000L, duration_ns = (durS * 1e9).toLong(),
        )
        val list = ArrayList<DishOutage>()
        fun periodic(first: Long, every: Long, cause: Int, durS: Double) {
            var t = first
            while (t <= uptime) {
                if (t >= uptime - 905) list += at(t, cause, durS)
                t += every
            }
        }
        periodic(300, 600, 6, 2.4) // OBSTRUCTED
        periodic(700, 1300, 5, 1.0) // NO_SATS
        currentOutage(uptime)?.let { if (it.cause != 1) list += it }
        return list.sortedBy { it.start_timestamp_ns }
    }

    private val causeToReason = mapOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7, 7 to 8, 8 to 9, 11 to 10, 13 to 19, 14 to 50)

    /** 900 one-second samples ending now, ping 25-45 ms, a few drops, power 25-40 W; the same second always reads the same. */
    private fun history(): DishGetHistoryResponse {
        val current = uptimeS()
        val nowNs = wallClockMs() * 1_000_000L
        val outages = outages()
        fun inOutage(k: Long): Boolean {
            val tNs = nowNs - (current - 1 - k) * 1_000_000_000L
            return outages.any { tNs >= it.start_timestamp_ns && tNs < it.start_timestamp_ns + it.duration_ns }
        }
        val sleeping = asleep()
        val rf = rfInhibited
        return DishGetHistoryResponse(
            current = current,
            pop_ping_latency_ms = FakeSupport.ring(current) { k -> if (inOutage(k)) 0f else 25f + 20f * FakeSupport.unit(k, 1) },
            pop_ping_drop_rate = FakeSupport.ring(current) { k -> if (inOutage(k) || FakeSupport.unit(k, 2) > 0.985f) 1f else 0f },
            downlink_throughput_bps = FakeSupport.ring(current) { k -> if (inOutage(k) || rf || sleeping) 0f else 1e5f + 5e6f * FakeSupport.unit(k, 3) },
            uplink_throughput_bps = FakeSupport.ring(current) { k -> if (inOutage(k) || rf || sleeping) 0f else 5e4f + 1e6f * FakeSupport.unit(k, 6) },
            power_in = FakeSupport.ring(current) { k -> powerAt(k, sleeping, rf) },
            outages = outages,
            event_log = EventLog(
                events = outages.map { o ->
                    UXEvent(
                        severity = if (o.duration_ns > 5_000_000_000L) 1 else 3, reason = causeToReason[o.cause] ?: 1,
                        start_timestamp_ns = o.start_timestamp_ns, duration_ns = o.duration_ns,
                    )
                },
                start_timestamp_ns = nowNs - current * 1_000_000_000L, current_timestamp_ns = nowNs,
            ),
        )
    }

    private fun networkInterfaces() = GetNetworkInterfacesResponse(
        listOf(
            NetworkInterface(
                name = "eth0", up = true, mac_address = "02:00:00:aa:bb:01", ipv4_addresses = listOf("192.168.100.1"),
                rx_stats = NetworkInterface.RxStats(bytes = 123_456_789, packets = 98_765), tx_stats = NetworkInterface.TxStats(bytes = 23_456_789, packets = 45_678),
                ethernet = EthernetNetworkInterface(link_detected = true, speed_mbps = 1000, autonegotiation_on = true, duplex = 2),
            ),
            NetworkInterface(name = "lo", up = true, ipv4_addresses = listOf("127.0.0.1")),
        ),
    )

    private fun ping() = GetPingResponse(
        listOf(
            PingResultEntry("pop", PingResult(PingTarget(service = "pop", location = "fake", address = "100.64.0.1"), 0.004f, 31.2f)),
            PingResultEntry("dns", PingResult(PingTarget(service = "dns", location = "fake", address = "1.1.1.1"), 0.006f, 38.5f)),
        ),
    )

    private fun connections() = GetConnectionsResponse(
        listOf(
            ServiceConnectionEntry("ground", ServiceConnection("ground.fake.example:443", 2)),
            ServiceConnectionEntry("telemetry", ServiceConnection("telemetry.fake.example:443", 9)),
            ServiceConnectionEntry("updates", ServiceConnection("updates.fake.example:443", 41)),
        ),
    )

    /** 123 x 123 cells, an obstruction wedge in the north-east, no data near the horizon. */
    private fun obstructionMap(): DishGetObstructionMapResponse {
        val n = 123
        val snr = List(n * n) { i ->
            val x = i % n - n / 2.0
            val y = i / n - n / 2.0
            val r = kotlin.math.hypot(x, y) / (n / 2.0)
            val az = (Math.toDegrees(kotlin.math.atan2(x, -y)) + 360) % 360
            when {
                r > 1.0 -> -1f
                r > 0.92 -> -1f
                obstructionCleared -> if (r > 0.3 && i % 7 == 0) 1f else -1f // just cleared: little observed yet
                az in 30.0..75.0 && r > 0.55 -> 0f
                az in 75.0..90.0 && r > 0.55 -> 0.5f
                else -> 1f
            }
        }
        return DishGetObstructionMapResponse(num_rows = n, num_cols = n, snr = snr, min_elevation_deg = 25f, max_theta_deg = 65f, map_reference_frame = 1)
    }

    private fun jitter(tr: Truth, noiseM: Double, sigma: Double): Triple<Double, Double, Double> {
        val dn = random.nextGaussian() * noiseM
        val de = random.nextGaussian() * noiseM
        return Triple(tr.lat + dn / 110_540.0, tr.lon + de / (111_320.0 * cos(Math.toRadians(tr.lat))), sigma)
    }

    private companion object {
        const val FIX_AGE_S = 0.45
    }
}
