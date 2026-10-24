package tools.apachi.starlinknav.core.dish

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.core.dish.proto.WifiConfig
import java.time.Instant
import java.util.Locale

/**
 * One-shot survey of what this dish, its router, firmware and plan actually answer: every read-only (R0)
 * RPC of the dish and, with a router address, of the router, each with the outcome class and the
 * device's own message. Never a command; router replies are redacted (no Wi-Fi password, key or secret
 * is ever printed or recorded).
 */
object DishProbe {
    /** Read-only calls only: the probe must never reboot, stow or reconfigure the dish. */
    val KINDS: List<DishCallKind> = DishCallKind.DISH_QUERIES

    /** Read-only calls of the router. */
    val ROUTER_KINDS: List<DishCallKind> = DishCallKind.ROUTER_QUERIES

    /**
     * Every call of [kinds] once. [stopWhenUnreachable] ends the run at the first call that reached no device at all
     * (no route, refused connection, timeout): the rest would only repeat it, each costing the connect timeout.
     */
    fun run(client: DishClient, kinds: List<DishCallKind> = KINDS, stopWhenUnreachable: Boolean = false): List<DishCallResult> =
        callAll(kinds, stopWhenUnreachable, client::call)

    private fun callAll(kinds: List<DishCallKind>, stopWhenUnreachable: Boolean, call: (DishCallKind) -> DishCallResult): List<DishCallResult> {
        val out = ArrayList<DishCallResult>(kinds.size)
        for (kind in kinds) {
            val r = call(kind)
            out += r
            if (stopWhenUnreachable && r.httpStatus == 0) break
        }
        return out
    }

    /** The router's reads as they came back, WITH Wi-Fi passwords: in memory only (Debug data builds from these and redacts itself). */
    fun runRouterUnredacted(client: RouterClient, kinds: List<DishCallKind> = ROUTER_KINDS, stopWhenUnreachable: Boolean = false): List<DishCallResult> =
        callAll(kinds, stopWhenUnreachable, client::call)

    /** The router's reads; the results come back redacted (see [Redact]). */
    fun runRouter(client: RouterClient, kinds: List<DishCallKind> = ROUTER_KINDS, stopWhenUnreachable: Boolean = false): List<DishCallResult> =
        runRouterUnredacted(client, kinds, stopWhenUnreachable).map { it.redacted() }

    /**
     * One user's packet capture says the official app reads router data from gateway:9005 (single source,
     * unverified; the router client itself uses 9001 and 9000). Asks `get_status` there over both transports
     * and describes what came back, so a real Mini settles it. Results are redacted; one line per transport.
     */
    fun surveyRouterPort(http: OkHttpClient, host: String, port: Int = EXTRA_ROUTER_PORT): List<String> {
        val url = HttpUrl.Builder().scheme("http").host(host).port(port).build()
        return listOf<GrpcTransport>(GrpcWebTransport(http, url), GrpcH2cTransport(http, url)).map { transport ->
            val r = DishClient(transport, target = CallTarget.ROUTER).call(DishCallKind.ROUTER_STATUS).redacted()
            val arm = when {
                r.response?.wifi_get_status != null -> "wifi_get_status (a router)"
                r.response?.dish_get_status != null -> "dish_get_status (the dish)"
                else -> "no status"
            }
            "router port $port ${transport.name}: ${r.outcome}${r.grpcMessage?.let { " \"${it.take(100)}\"" }.orEmpty()}${if (r.ok) ", $arm" else ""}"
        }
    }

    const val EXTRA_ROUTER_PORT = 9005

    /** A position answer, from a location call or from diagnostics. */
    private class Pos(val label: String, val lat: Double, val lon: Double, val alt: Double, val sigmaM: Double)

    /**
     * Text report safe to paste into a chat by default: coordinates are left out unless [includeCoordinates] is set,
     * identifiers (dish and router ids, MAC addresses, WAN and public IP addresses, network names) unless
     * [includeIdentifiers] is (the CLI's `--coords` sets both). Distances between measured sources are always shown;
     * they reveal no position. Sensitive sources (UT_INFO, EXTERNAL) never enter the distance table.
     */
    fun report(
        results: List<DishCallResult>,
        endpoint: String,
        includeCoordinates: Boolean,
        nowWallMs: Long = System.currentTimeMillis(),
        /** Now on the clock the client stamped the calls with (System.nanoTime for the CLI). */
        nowMonoNs: Long = System.nanoTime(),
        /** The router's results ([runRouter]) and where they came from; the section is left out without any. */
        routerResults: List<DishCallResult> = emptyList(),
        routerEndpoint: String? = null,
        includeIdentifiers: Boolean = includeCoordinates,
    ): String = buildString {
        fun receivedWallMs(r: DishCallResult) = nowWallMs - (nowMonoNs - (r.sentMonoNs + r.rttNs)) / 1_000_000
        fun rows(list: List<DishCallResult>) {
            for (r in list.sortedBy { it.kind.ordinalForReport() }) {
                append(r.kind.label.padEnd(27))
                append(r.statusText.padEnd(17))
                append(r.outcome.name.padEnd(19))
                append("%5.0f ms".format(Locale.ROOT, r.rttNs / 1e6))
                val detail = detail(r, includeCoordinates, includeIdentifiers, receivedWallMs(r))
                if (detail.isNotEmpty()) append("  ").append(detail)
                if (!r.ok) {
                    r.grpcMessage?.let { append("  msg=\"").append(it.take(160)).append('"') }
                    r.error?.let { append("  err=").append(it.take(160)) }
                }
                appendLine()
            }
        }
        appendLine("starlink-nav dish probe  ${Instant.ofEpochMilli(nowWallMs)}")
        appendLine("endpoint: $endpoint  transport: ${results.lastOrNull()?.transport ?: "-"}")
        appendLine("call".padEnd(27) + "status".padEnd(17) + "outcome".padEnd(19) + "     rtt  detail")
        rows(results)
        val positions = positions(results)
        if (positions.size >= 2) {
            appendLine("distances between sources (horizontal, vertical):")
            for (i in positions.indices) for (j in i + 1 until positions.size) {
                val a = positions[i]
                val b = positions[j]
                appendLine(
                    "  %-20s vs %-20s %10.1f m  %+7.1f m".format(
                        Locale.ROOT, a.label, b.label, Geo.distanceM(a.lat, a.lon, b.lat, b.lon), b.alt - a.alt,
                    ),
                )
            }
        }
        appendLine(verdict(results))
        starlinkNote(positions)?.let(::appendLine)
        appendLine(outcomeSummary("dish", results))
        if (routerResults.isNotEmpty()) {
            appendLine()
            appendLine("router: ${routerEndpoint ?: "-"}  transport: ${routerResults.lastOrNull()?.transport ?: "-"}")
            appendLine("call".padEnd(27) + "status".padEnd(17) + "outcome".padEnd(19) + "     rtt  detail")
            rows(routerResults)
            appendLine(routerVerdict(routerResults))
            appendLine(outcomeSummary("router", routerResults))
            appendLine("secrets: Wi-Fi passwords, RADIUS secrets and keys in router replies are never printed or recorded")
        }
    }

    /** "dish calls: 14 OK, 2 UNIMPLEMENTED" */
    fun outcomeSummary(who: String, results: List<DishCallResult>): String =
        "$who calls: " + results.groupingBy { it.outcome }.eachCount().entries.sortedBy { it.key.ordinal }.joinToString { "${it.value} ${it.key}" }

    private fun routerVerdict(results: List<DishCallResult>): String {
        val reachable = results.any { it.httpStatus != 0 }
        if (!reachable) return "ROUTER VERDICT: router not reachable (is the phone on the Starlink Wi-Fi? gateway, ports 9001/9000)"
        val status = results.firstNotNullOfOrNull { it.response?.wifi_get_status }
        val answered = results.count { it.ok }
        val unavailable = results.filter { it.outcome.isUnavailable }.map { it.kind.label.removePrefix("router:") }
        val onDishPort = results.firstNotNullOfOrNull { it.response?.dish_get_status }
        return buildString {
            append("ROUTER VERDICT: $answered of ${results.size} calls answered")
            when {
                status != null -> append("; get_status came back as wifi_get_status (a router)")
                onDishPort != null -> append("; get_status came back as dish_get_status: this address is the dish, not the router")
                else -> append("; no router status")
            }
            if (unavailable.isNotEmpty()) append("; not available to this client: ${unavailable.joinToString()}")
        }
    }

    private fun DishCallKind.ordinalForReport(): Int = when {
        locationSource != null -> 100 + (locationSource.code)
        else -> code
    }

    private fun positions(results: List<DishCallResult>): List<Pos> {
        val out = ArrayList<Pos>()
        for (r in results) {
            if (!r.ok || r.kind.isSensitive) continue
            val resp = r.response ?: continue
            resp.fix()?.takeIf { !it.looksLikePlaceholder }?.let {
                out += Pos(r.kind.label.removePrefix("location:"), it.lat, it.lon, it.alt, it.sigmaM)
            }
            resp.dish_get_diagnostics?.location?.takeIf { it.enabled && (it.latitude != 0.0 || it.longitude != 0.0) }?.let {
                out += Pos("diagnostics", it.latitude, it.longitude, it.altitude_meters, if (it.uncertainty_meters_valid) it.uncertainty_meters else Double.NaN)
            }
        }
        return out
    }

    /**
     * The Starlink-only fix updates rarely (one value through whole drives), so its distance
     * from the live fix mostly measures driving since its last update. Only a continent-scale
     * gap (a Lima-style jump) says GNSS spoofing.
     */
    private fun starlinkNote(positions: List<Pos>): String? {
        val live = positions.firstOrNull { it.label == "AUTO" } ?: positions.firstOrNull { it.label == "GPS" } ?: return null
        val ref = positions.firstOrNull { it.label == "STARLINK" } ?: return null
        val km = Geo.distanceM(live.lat, live.lon, ref.lat, ref.lon) / 1000
        return if (km > SPOOF_KM) {
            "WARNING: dish ${live.label} is %.0f km from its STARLINK fix: GNSS spoofing likely".format(Locale.ROOT, km)
        } else {
            "note: STARLINK fix is %.2f km from ${live.label}; it is updated rarely, so this is mostly distance driven since".format(Locale.ROOT, km)
        }
    }

    private const val SPOOF_KM = 1000.0

    private fun detail(r: DishCallResult, coords: Boolean, ids: Boolean, receivedWallMs: Long): String {
        val resp = r.response ?: return ""
        if (r.kind.locationSource != null) {
            val f = resp.fix() ?: return "no lla in response"
            val pos = if (coords) "lat=${f.lat.fmt(7)} lon=${f.lon.fmt(7)}" else "lat/lon=<redacted>"
            return "source=${f.sourceName} sigma=${f.sigmaM.fmt(2)} m $pos alt=${if (coords || !r.kind.isSensitive) f.alt.fmt(1) else "<redacted>"} m " +
                "hspeed=${f.horizontalSpeedMps.fmt(2)} vspeed=${f.verticalSpeedMps.fmt(2)} m/s" +
                if (f.looksLikePlaceholder) "  NO_FIX(impossible coordinate)" else ""
        }
        return when (r.kind) {
            DishCallKind.DEVICE_INFO -> resp.get_device_info?.device_info?.let {
                "hw=${it.hardware_version} sw=${it.software_version} board_rev=${it.board_rev} " +
                    "gen=${it.generation_number} country=${it.country_code} cohoused=${it.dish_cohoused} hw_index=${it.hardware_index} " +
                    "id=${if (it.id.isEmpty()) "-" else if (ids) it.id else "<redacted>"}"
            }.orEmpty()

            DishCallKind.STATUS -> resp.dish_get_status?.let { s ->
                val g = s.gps_stats
                val a = s.alignment_stats
                val q = s.ned2dish_quaternion
                buildString {
                    append("gps_valid=${g?.gps_valid} sats=${g?.gps_sats} no_sats_after_ttff=${g?.no_sats_after_ttff} ")
                    // Field 5 is absent before firmware 2026.09; proto3 cannot tell absent from FILTER_RESET.
                    val pnt = g?.pnt_filter_convergence_state ?: 0
                    append("inhibit_gps=${g?.inhibit_gps} pnt=${if (pnt == 0) "n/a" else AttitudeState.name(pnt)} ")
                    append("attitude=${a?.let { AttitudeState.name(it.attitude_estimation_state) }} ")
                    append("att_unc=${a?.attitude_uncertainty_deg?.fmt()}° tilt=${a?.tilt_angle_deg?.fmt()}° ")
                    append("boresight=${s.boresight_azimuth_deg.fmt()}/${s.boresight_elevation_deg.fmt()}° ")
                    if (q != null) {
                        val at = q.attitude()
                        append("q=[${q.q_scalar.fmt(4)},${q.q_x.fmt(4)},${q.q_y.fmt(4)},${q.q_z.fmt(4)}] ")
                        append("heading_x=${at.headingDeg.fmt()}° x_elev=${at.xElevationDeg.fmt()}° ")
                        append("normal(q)=${at.normalAzimuthDeg.fmt()}/${at.normalElevationDeg.fmt()}° ")
                    } else {
                        append("q=absent ")
                    }
                    append("moving_fast=${s.is_moving_fast_persisted} mobility=${MobilityClass.name(s.mobility_class)} ")
                    append("cos=${ClassOfService.name(s.class_of_service)} disablement=${Disablement.name(s.disablement_code)} ")
                    append("uptime=${s.device_state?.uptime_s}s sw=${s.device_info?.software_version} boots=${s.device_info?.bootcount}")
                    append(" update=${SoftwareUpdate.name(s.software_update_state)}")
                    s.updateRestartAtS()?.takeIf { s.updateWaiting() }?.let { append(" restart_at=${java.time.Instant.ofEpochSecond(it)}") }
                    if (s.reboot_reason != 0) append(" reboot_reason=${RebootReason.name(s.reboot_reason)}")
                    s.device_info?.boot?.let { append(" boot_reason=${BootReason.name(it.last_reason)}") }
                    append(' ').append(statusExtras(s))
                }
            }.orEmpty()

            DishCallKind.DIAGNOSTICS -> resp.dish_get_diagnostics?.let { d ->
                val l = d.location
                buildString {
                    append("hw=${d.hardware_version} sw=${d.software_version} disablement=${Disablement.name(d.disablement_code)} stowed=${d.stowed} ")
                    append("self_test=${HardwareSelfTest.resultName(d.hardware_self_test)}")
                    if (d.hardware_self_test_codes.isNotEmpty()) append("[${d.hardware_self_test_codes.joinToString(",") { HardwareSelfTest.codeName(it) }}]")
                    append(" alerts=${AlertList.of(null, d).map { it.key }} overage_limited=${d.overage_rate_limited} ")
                    if (l == null) {
                        append("location=absent")
                    } else {
                        append("location.enabled=${l.enabled} ")
                        if (coords) append("lat=${l.latitude.fmt(7)} lon=${l.longitude.fmt(7)} ")
                        append("alt=${l.altitude_meters.fmt(1)} unc=${if (l.uncertainty_meters_valid) l.uncertainty_meters.fmt(2) else "invalid"} ")
                        if (l.gps_time_s > 0) {
                            val utc = Geo.gpsSecondsToUnixMs(l.gps_time_s)
                            append("gps_time=${Instant.ofEpochMilli(utc)}")
                            if (receivedWallMs != UNKNOWN_TIME) append(" (age at receipt ${((receivedWallMs - utc) / 1000.0).fmt(3)}s)")
                        }
                    }
                }
            }.orEmpty()

            DishCallKind.GNSS_MEASUREMENT -> resp.get_gnss_measurement?.let { g ->
                val bySys = g.measurements.groupingBy { it.satellite_system }.eachCount()
                "measurements=${g.measurements.size} by_system=$bySys"
            }.orEmpty()

            DishCallKind.CONFIG -> resp.dish_get_config?.dish_config?.let { c ->
                "location_request_mode=${if (c.location_request_mode == DishCallKind.LOCATION_MODE_LOCAL) "LOCAL" else "NONE(${c.location_request_mode})"} " +
                    "snow_melt=${c.snow_melt_mode} level_dish=${c.level_dish_mode} power_save=${c.power_save_mode} " +
                    "swupdate_reboot_hour=${c.swupdate_reboot_hour}"
            }.orEmpty()

            DishCallKind.HISTORY -> resp.dish_get_history?.let { h ->
                "current=${h.current} samples=${h.pop_ping_latency_ms.size} power_in=${h.power_in.size} " +
                    "outages=${h.outages.size} events=${h.event_log?.events?.size ?: 0}"
            }.orEmpty()

            DishCallKind.OBSTRUCTION_MAP -> resp.dish_get_obstruction_map?.let { m ->
                "map=${m.num_rows}x${m.num_cols} cells_with_data=${m.snr.count { it >= 0 }} " +
                    "min_elevation=${m.min_elevation_deg.fmt()}° frame=${m.map_reference_frame}"
            }.orEmpty()

            else -> newDetail(r.kind, resp, ids, receivedWallMs)
        }
    }

    /** The one-line description of a result as the probe report and `slnav dump` print it (no secrets, ever). */
    fun describe(r: DishCallResult, includeCoordinates: Boolean, receivedWallMs: Long? = null, includeIdentifiers: Boolean = includeCoordinates): String =
        detail(r, includeCoordinates, includeIdentifiers, receivedWallMs ?: UNKNOWN_TIME)

    /** "When the answer arrived" is not known (an answer read from a file without a receipt time). */
    private const val UNKNOWN_TIME = Long.MIN_VALUE

    /** Fields of the status added with the dish features, on one line. */
    fun statusExtras(s: DishGetStatusResponse): String = buildString {
        val st = DishStates.of(s)
        append("state=${st.state}")
        if (st.notReady.isNotEmpty()) append("(not_ready=${st.notReady})")
        append(" actuators=${HasActuators.name(s.hasActuators())}")
        append(" signal_quality=${s.signal_quality.fmt(2)}")
        s.upsu_stats?.let { append(" upsu_power=${it.dish_power.fmt(1)}W/router=${it.router_power.fmt(1)}W") }
        s.aps_stats?.let { append(" aps_power=${it.dish_power.fmt(1)}W") }
        s.battery_stats?.let { append(" battery=${it.state_of_charge}%/${if (it.is_charging) "charging" else "idle"}/${PowerSource.name(it.power_source)}") }
        val init = InitializationView.of(s.initialization_duration_seconds)
        append(" init=${init.stages.joinToString(",") { "${it.key}:${it.seconds}s" }.ifEmpty { "-" }}")
        append(" metered=${s.treat_as_metered} debug_mode=${s.user_debug_mode_enabled} signed_cals=${s.has_signed_cals}")
        append(" shard=${AccountShard.name(s.account_shard)} nat=${NatFlag.name(s.nat_flag)}")
        if (s.dl_bandwidth_restricted_reason != 0 || s.ul_bandwidth_restricted_reason != 0) {
            append(" restricted=dl:${RateLimitReason.name(s.dl_bandwidth_restricted_reason)}/ul:${RateLimitReason.name(s.ul_bandwidth_restricted_reason)}")
        }
        val alerts = AlertList.of(s).map { it.key }
        append(" alerts=$alerts")
        s.downstream_routers.takeIf { it.isNotEmpty() }?.let { r -> append(" routers=${r.map { RouterRole.name(it.value_?.role ?: 0) }}") }
        SleepWindow.of(s.config)?.takeIf { s.config?.power_save_duration_minutes != 0 }?.let {
            append(" sleep=${SleepWindow.clock(it.startUtcMinutes)}utc+${it.durationMinutes}min/${if (it.enabled) "on" else "off"}")
        }
    }

    /** [ids]: print identifiers (MAC addresses, ids, WAN and public IP addresses, network names). */
    private fun newDetail(kind: DishCallKind, resp: DeviceResponse, ids: Boolean, receivedWallMs: Long): String = when (kind) {
        DishCallKind.NETWORK_INTERFACES, DishCallKind.ROUTER_NETWORK_INTERFACES -> resp.get_network_interfaces?.let { n ->
            "interfaces=" + n.network_interfaces.joinToString(",", "[", "]") { i ->
                val e = i.ethernet
                i.name + (if (i.up) ":up" else ":down") + (if (e != null) ":${e.speed_mbps}Mbps" else "") + (if (i.wifi != null) ":wifi" else "") +
                    (if (ids && i.mac_address.isNotEmpty()) ":${i.mac_address}" else "")
            }
        }.orEmpty()

        DishCallKind.PING, DishCallKind.ROUTER_PING -> resp.get_ping?.let { p ->
            "targets=" + p.results.joinToString(",", "[", "]") { "${it.target_name}:${it.value_?.latency_ms?.fmt(1)}ms/${((it.value_?.drop_rate ?: 0f) * 100).fmt(1)}%" }
        }.orEmpty()

        DishCallKind.RADIO_STATS, DishCallKind.ROUTER_RADIO_STATS -> resp.get_radio_stats?.let { r ->
            "radios=" + r.radio_stats.joinToString(",", "[", "]") { "${WifiBand.name(it.band)}:${it.thermal_status?.temp ?: 0}C" }
        }.orEmpty()

        DishCallKind.CONNECTIONS, DishCallKind.ROUTER_CONNECTIONS -> resp.get_connections?.let { c ->
            "services=${c.services.size} worst_since_success=${c.services.maxOfOrNull { it.value_?.seconds_since_success ?: 0 } ?: 0}s"
        }.orEmpty()

        DishCallKind.TIME, DishCallKind.ROUTER_TIME -> resp.time?.let { t ->
            val ms = t.unix_nano / 1_000_000
            "device_time=${Instant.ofEpochMilli(ms)}" + if (receivedWallMs == UNKNOWN_TIME) "" else " minus_phone=${ms - receivedWallMs}ms"
        }.orEmpty()

        DishCallKind.SPEEDTEST_STATUS, DishCallKind.ROUTER_SPEEDTEST_STATUS -> resp.get_speedtest_status?.status?.let { s ->
            "running=${s.running} id=${s.id} down_samples=${s.down?.throughputs_mbps?.size ?: 0} up_samples=${s.up?.throughputs_mbps?.size ?: 0}"
        }.orEmpty()

        DishCallKind.ROUTER_DEVICE_INFO -> resp.get_device_info?.device_info?.let {
            "hw=${it.hardware_version} sw=${it.software_version} cohoused=${it.dish_cohoused} hw_index=${it.hardware_index} " +
                "boots=${it.bootcount} id=${if (it.id.isEmpty()) "-" else if (ids) it.id else "<redacted>"}"
        }.orEmpty()

        DishCallKind.ROUTER_STATUS -> resp.wifi_get_status?.let { s ->
            val c = s.config
            buildString {
                append("uptime=${s.device_state?.uptime_s}s sw=${s.device_info?.software_version} ")
                append("wan=${if (ids) s.ipv4_wan_address else if (s.ipv4_wan_address.isEmpty()) "-" else "<redacted>"} ")
                append("public_ip=${if (ids) s.public_ipv4 else if (s.public_ipv4.isEmpty()) "-" else "<redacted>"} no_wan_link=${s.no_wan_link} ")
                append("ping=${s.ping_latency_ms.fmt(1)}ms/${(s.ping_drop_rate * 100).fmt(1)}% pop=${s.pop_ping_latency_ms.fmt(1)}ms dish=${s.dish_ping_latency_ms.fmt(1)}ms ")
                append("clients=${s.clients.size} ")
                s.rf_2ghz_status?.let { append("busy2g=${(it.chan_busy_time_fraction * 100).fmt(0)}% ") }
                s.rf_5ghz_status?.let { append("busy5g=${(it.chan_busy_time_fraction * 100).fmt(0)}% ") }
                append("alerts=${AlertList.router(s.alerts)} ")
                s.software_update_stats?.let { append("update=${WifiSoftwareUpdate.name(it.state)} ") }
                s.setup_requirement?.let { append("setup=${SetupRequirement.name(it.state)} ") }
                append("dish_disablement=${Disablement.name(s.dish_disablement_code)} ")
                if (c != null) append(configSummary(c, ids)) else append("config=absent")
            }
        }.orEmpty()

        DishCallKind.ROUTER_CONFIG -> resp.wifi_get_config?.wifi_config?.let { configSummary(it, ids) }.orEmpty()

        DishCallKind.ROUTER_CLIENTS -> resp.wifi_get_clients?.let { c ->
            val by = c.clients.groupingBy { WifiBand.clientInterfaceName(it.iface) }.eachCount()
            "clients=${c.clients.size} by_interface=$by blocked=${c.clients.count { it.blocked }}"
        }.orEmpty()

        DishCallKind.ROUTER_HISTORY -> resp.wifi_get_history?.let { h ->
            val rh = RouterHistory.of(h)
            // current_index_15s is read as a count of 15 s samples written (unverified: it may be an index into the ring);
            // current/15 is printed beside it so a real router shows which one it is.
            "current=${h.current} current/15=${h.current / 15} current_index_15s=${h.current_index_15s} " +
                "samples=${rh.pingLatencyMs.size} samples_15s=${rh.popIpv4Drop15s.size} mean_latency=${Series.mean(rh.pingLatencyMs)?.fmt(1)}ms events=${rh.events.size} " +
                "latest=${rh.events.firstOrNull()?.let { EventReason.name(it.reason) } ?: "-"}"
        }.orEmpty()

        DishCallKind.ROUTER_PING_METRICS -> resp.wifi_get_ping_metrics?.internet?.let {
            "latency=${it.latency_mean_ms.fmt(1)}ms 5m=${it.latency_mean_ms_5m.fmt(1)}ms drop=${(it.drop_rate * 100).fmt(2)}% 1d=${(it.drop_rate_1d * 100).fmt(2)}%"
        }.orEmpty()

        DishCallKind.ROUTER_DIAGNOSTICS -> resp.wifi_get_diagnostics?.let { d ->
            "hw=${d.hardware_version} sw=${d.software_version} networks=${d.networks.size} " +
                "clients=${d.networks.sumOf { it.clients_ethernet + it.clients_2ghz + it.clients_5ghz }}"
        }.orEmpty()

        DishCallKind.ROUTER_PERSISTENT_STATS -> resp.wifi_get_persistent_stats?.stats?.let {
            "factory_resets: button=${it.factory_reset_button?.count ?: 0} command=${it.factory_reset_command?.count ?: 0} " +
                "update_reboots=${it.reboot_from_software_update?.count ?: 0}"
        }.orEmpty()

        DishCallKind.ROUTER_GUEST_INFO -> resp.wifi_guest_info?.let { "is_guest=${it.is_guest} online=${it.is_online}" }.orEmpty()

        else -> ""
    }

    /** Names and counts only: never an auth arm, a password or a key. */
    private fun configSummary(c: WifiConfig, ids: Boolean): String {
        val bss = c.networks.flatMap { it.basic_service_sets }
        val names = bss.map { it.ssid }.distinct()
        val ssids = if (ids) names.joinToString(",", "[", "]") else if (names.isEmpty()) "[]" else "<redacted>"
        return "config: networks=${c.networks.size} guest=${c.networks.count { it.guest }} bands=${bss.map { WifiBand.name(it.band) }} hidden=${bss.count { it.hidden }} " +
            "ssids=$ssids disabled_bands=${listOfNotNull("2g".takeIf { c.disable_2ghz }, "5g".takeIf { c.disable_5ghz }, "5g_high".takeIf { c.disable_5ghz_high })} " +
            "bypass=${c.bypass_mode} dns=${DnsPreset.of(c.nameservers) ?: if (c.nameservers.isEmpty()) "none" else "custom(${c.nameservers.size})"} secure_dns=${c.secure_dns} " +
            "client_configs=${c.client_configs.size} setup_complete=${c.setup_complete} wifi_secrets=hidden"
    }

    private fun verdict(results: List<DishCallResult>): String {
        val reachable = results.any { it.httpStatus != 0 }
        if (!reachable) return "VERDICT: dish not reachable (check Wi-Fi network binding, host, ports 9201/9200)"
        val disabled = results.firstNotNullOfOrNull { it.response?.dish_get_status?.disablement_code }
            ?.takeIf { it != Disablement.OKAY }
        val blocked = disabled?.let { " (dish disabled: ${Disablement.name(it)}, location is refused meanwhile)" }.orEmpty()
        val loc = results.filter { it.kind.locationSource != null }
        val answered = loc.filter { r -> r.ok && r.response?.fix()?.looksLikePlaceholder == false }
        val unsupported = loc.filter { !it.ok }.map { it.kind.locationSource!!.name }
        val text = when {
            answered.isNotEmpty() -> "VERDICT: location API available; sources with a fix: " +
                answered.joinToString { r ->
                    val f = r.response!!.fix()!!
                    val requested = r.kind.locationSource!!
                    val via = if (f.source != requested.code) "→${f.sourceName}" else ""
                    "${requested.name}$via σ${f.sigmaM.fmt(1)}m"
                } + if (unsupported.isEmpty()) "" else "; not answered: ${unsupported.joinToString()}"
            loc.any { it.ok } -> "VERDICT: location API answers but without a usable fix"
            else -> "VERDICT: location API not available: " + loc.joinToString { "${it.kind.locationSource}=${it.statusText}" }
        }
        return text + blocked
    }

    private fun Double.fmt(decimals: Int = 1) = "%.${decimals}f".format(Locale.ROOT, this)
    private fun Float.fmt(decimals: Int = 1) = toDouble().fmt(decimals)
}
