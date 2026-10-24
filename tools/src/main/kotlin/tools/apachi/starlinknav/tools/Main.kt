package tools.apachi.starlinknav.tools

import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.system.exitProcess
import tools.apachi.starlinknav.core.dish.BootReason
import tools.apachi.starlinknav.core.dish.DebugDataBuilder
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.DishClient
import tools.apachi.starlinknav.core.dish.DishProbe
import tools.apachi.starlinknav.core.dish.FakeArms
import tools.apachi.starlinknav.core.dish.FakeDishSimulation
import tools.apachi.starlinknav.core.dish.FakeRouterSimulation
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.core.dish.RebootReason
import tools.apachi.starlinknav.core.dish.RouterClient
import tools.apachi.starlinknav.core.dish.TransportMode
import tools.apachi.starlinknav.core.dish.fix
import tools.apachi.starlinknav.core.log.SessionExporter
import tools.apachi.starlinknav.core.log.Sessions
import tools.apachi.starlinknav.core.log.Slog
import tools.apachi.starlinknav.core.log.SlogReader
import tools.apachi.starlinknav.core.log.SlogWriter
import tools.apachi.starlinknav.core.log.proto.Record
import tools.apachi.starlinknav.core.log.proto.SessionHeader

private const val USAGE = """slnav — starlink-nav desktop tools

  slnav probe [--host 192.168.100.1[:9201]] [--router 192.168.1.1[:9001]] [--transport auto|web|h2c]
              [--coords] [--repeat N] [--record out.slog] [--debugdata out.json]
      Ask the dish what it answers (status, location per source, diagnostics, GNSS, history, obstruction
      map, time, network interfaces, ping, connections, ...) and, with --router, the router at the Wi-Fi
      gateway (status, history, clients, config, ping metrics, diagnostics, ...): every read-only call, each
      with its outcome class (OK, UNIMPLEMENTED, PERMISSION_DENIED, UNAUTHENTICATED, DISABLED_BY_POLICY, PLAN_RESTRICTED,
      UNREACHABLE, SENT_NO_ANSWER, OTHER) and the device's message. Never sends a command. Router replies are redacted:
      no Wi-Fi password, secret or key is printed or recorded. Coordinates and identifiers are redacted
      unless --coords is given. --debugdata writes a Debug data snapshot (JSON; identifiers removed
      unless --coords; never a password).
  slnav debugdata <file.json>
      Decode a Debug data snapshot written by --debugdata (or the app): summary and every answer.
  slnav dump <session.slog> [--type dish|imu|phone_location|...] [--limit N] [--coords]
  slnav index <session.slog>
  slnav csv <session.slog> <outdir>
      One CSV per stream (dish calls, dish locations, dish status, diagnostics, phone fixes,
      GNSS status/measurements, IMU, events, mock output, NMEA) for analysis.
  slnav export <session.slog> --from SEC --to SEC [--out file.zip]
      SEC is seconds after the first record.
  slnav fake-dish [--bind 127.0.0.1] [--port 9201] [--spoof-every 120] [--spoof-for 30]
                  [--disable-every 0] [--disable-for 0] [--location-off]
                  [--reboot-every 0] [--reboot-reason 2] [--reboot-down 40] [--no-update]
                  [--deny-set-config true|false] [--allow-set-config] [--unimplemented a,b,...]
                  [--mount-offset -177] [--fault-sideways METRES --fault-at S [--fault-ramp S] [--fault-for S]]
                  [--router-port N] [--router-reboot-down 30] [--router-deny-set-config]
                  [--router-unimplemented a,b,...]
      Synthetic dish (gRPC-Web) circling in Kyiv at 12 m/s; its GPS source jumps to Lima
      during spoofing windows. Its dish heading is the track plus --mount-offset (default -177°, a dish
      facing backwards), its fixes are 0.45 s old like a Mini's, and --fault-sideways moves
      the AUTO and diagnostics positions that many metres to the right of the track (negative = left) from
      --fault-at seconds after the start, reached over --fault-ramp seconds (0 = a step), for --fault-for
      seconds (0 = for good): the inertial filter's sideways correction can be watched on an emulator. --location-off starts with location sharing on the local
      network switched off: location_request_mode is NONE in dish_get_config and in the status config, and GetLocation is refused
      (PERMISSION_DENIED "GetLocation requests are not enabled on this device") until a dish_set_config write switches it to LOCAL
      (needs --allow-set-config; with the default --deny-set-config the write is refused and the dish stays off). To test the app's
      enable flow on an emulator: `slnav fake-dish --location-off --allow-set-config` (accepted) or `--location-off` (refused).
      It starts with a software update waiting (unless --no-update); a restart (the reboot command,
      or every --reboot-every seconds, then reporting RebootReason --reboot-reason) leaves it silent
      for --reboot-down seconds and installs the update.
      Writes answer like a real Mini probably does: dish_set_config is refused with PERMISSION_DENIED
      (--deny-set-config, on by default; --allow-set-config or --deny-set-config false accepts it);
      --unimplemented lists calls (kind labels such as time, radio_stats, or request names such as
      get_ping) that answer UNIMPLEMENTED. Everything else works: sleep schedule, inhibit GPS / RF, update,
      reboot, factory reset change the fake state; history, obstruction map, alerts, event log, time,
      network interfaces, ping and connections answer.
      --router-port also starts a fake router (gRPC-Web) on its own port: wifi_get_status with clients
      (fake MACs and IPs) and a config with SSID STARLINK and a fake password, history with events,
      reboot (silent for --router-reboot-down seconds), wifi_set_config (accepted, or refused with
      --router-deny-set-config), client rename / pause, speed test (running, then a result), factory
      reset; --router-unimplemented switches router calls off like --unimplemented does for the dish.
      An emulator reaches them at 10.0.2.2."""

fun main(args: Array<String>) {
    val cmd = args.firstOrNull()
    val opts = Opts(args.drop(1))
    try {
        when (cmd) {
            "probe" -> probe(opts)
            "debugdata" -> debugData(opts)
            "dump" -> dump(opts)
            "index" -> index(opts)
            "export" -> export(opts)
            "csv" -> {
                val out = File(opts.positional.getOrNull(1) ?: throw IllegalArgumentException("missing <outdir>"))
                CsvExport.run(opts.file(), out).forEach { (t, n) -> println("%-12s %8d rows".format(t, n)) }
            }
            "fake-dish" -> fakeDish(opts)
            else -> {
                println(USAGE)
                exitProcess(if (cmd == null || cmd == "help" || cmd == "--help") 0 else 2)
            }
        }
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}\n\n$USAGE")
        exitProcess(2)
    }
}

private class Opts(args: List<String>) {
    val positional = mutableListOf<String>()
    private val named = mutableMapOf<String, String?>()
    /** Every value of a repeatable option, in order (a flag without a value adds ""). */
    val all = mutableMapOf<String, MutableList<String>>()

    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                val next = args.getOrNull(i + 1)
                if (next != null && !next.startsWith("--")) {
                    named[a] = next
                    all.getOrPut(a) { mutableListOf() } += next
                    i++
                } else {
                    named[a] = null
                    all.getOrPut(a) { mutableListOf() } += ""
                }
            } else {
                positional += a
            }
            i++
        }
    }

    fun flag(name: String) = named.containsKey(name)
    fun value(name: String): String? = named[name]

    /** `--name` alone is true, `--name false` / `off` / `no` / `0` is false, absent is [default]. */
    fun boolOption(name: String, default: Boolean): Boolean {
        if (!named.containsKey(name)) return default
        return named[name]?.lowercase() !in setOf("false", "off", "no", "0")
    }
    fun file(): File = File(positional.firstOrNull() ?: throw IllegalArgumentException("missing <session.slog>"))
}

private fun probe(o: Opts) {
    val (host, webPort) = DishClient.parseAddress(o.value("--host") ?: DishClient.DEFAULT_HOST)
    val mode = TransportMode.valueOf((o.value("--transport") ?: "auto").uppercase())
    val coords = o.flag("--coords")
    val repeat = o.value("--repeat")?.toInt() ?: 1
    val client = DishClient.create(DishClient.defaultHttpClient(), host, mode, webPort = webPort)
    val routerClient = o.value("--router")?.let { address ->
        require(RouterClient.isValidAddress(address)) { "bad --router address: $address" }
        val (routerHost, routerWebPort) = RouterClient.parseAddress(address)
        routerHost to RouterClient.create(DishClient.defaultHttpClient(), routerHost, mode, webPort = routerWebPort)
    }
    val recorder = o.value("--record")?.let { path ->
        SlogWriter.create(File(path)).also {
            it.write(
                Record(
                    t_mono_ns = System.nanoTime(), t_wall_ms = System.currentTimeMillis(),
                    header_ = SessionHeader(
                        session_id = UUID.randomUUID().toString(), app_version = "slnav-probe",
                        device_model = System.getProperty("os.name"), clock = "nanoTime",
                    ),
                ),
            )
        }
    }
    try {
        if (repeat <= 1) {
            val results = DishProbe.run(client)
            // Unredacted results stay in this function: the report and the recorder get redacted ones; Debug data
            // takes the real ones, redacts them itself and checks its output against the real secrets.
            val routerRaw = routerClient?.let { DishProbe.runRouterUnredacted(it.second) }.orEmpty()
            val routerResults = routerRaw.map { it.redacted() }
            results.filterNot { it.kind.isSensitive }.forEach { recorder?.record(it) }
            routerResults.forEach { recorder?.record(it) } // redacted by toRecord, whatever the call
            print(
                DishProbe.report(
                    results, "$host (${client.endpoint})", coords,
                    routerResults = routerResults, routerEndpoint = routerClient?.let { "${it.first} (${it.second.endpoint})" },
                ),
            )
            routerClient?.let { (routerHost, _) -> DishProbe.surveyRouterPort(DishClient.defaultHttpClient(), routerHost).forEach(::println) }
            o.value("--debugdata")?.let { path ->
                File(path).writeText(
                    DebugDataBuilder.toJson(results.filterNot { it.kind.isSensitive }, routerRaw, DebugDataBuilder.Options("slnav", removeIdentifiers = !coords)) + "\n",
                )
                println("wrote $path")
            }
            return
        }
        val kinds = listOf(DishCallKind.STATUS, DishCallKind.DIAGNOSTICS) + DishCallKind.RECORDED_LOCATIONS
        repeat(repeat) { round ->
            val started = System.nanoTime()
            val results = kinds.map(client::call)
            results.forEach { recorder?.record(it) }
            println(roundLine(round, results))
            val sleepMs = 1000 - (System.nanoTime() - started) / 1_000_000
            if (sleepMs > 0 && round < repeat - 1) Thread.sleep(sleepMs)
        }
    } finally {
        recorder?.close()
    }
}

private fun debugData(o: Opts) {
    val data = DebugDataBuilder.decode(o.file().readText())
    val captured = runCatching { java.time.Instant.parse(data.capturedAt).toEpochMilli() }.getOrNull()
    println("${data.format}  captured ${data.capturedAt}  app ${data.app.version}  identifiers ${if (data.identifiersRemoved) "removed" else "kept"}")
    for ((name, device) in listOf("dish" to data.dish, "router" to data.router)) {
        device ?: continue
        println("$name: ${if (device.reachable) "reachable" else "not reachable"}${device.transport?.let { " via $it" }.orEmpty()}")
        device.summary.forEach { (k, v) -> println("  %-30s %s".format(Locale.ROOT, k, v)) }
        println("  calls: " + device.outcomes.entries.joinToString { "${it.key}=${it.value}" })
        for (r in DebugDataBuilder.results(device)) {
            println("  [${r.kind.label}] ${DishProbe.describe(r, o.flag("--coords"), captured)}")
        }
    }
}

private fun SlogWriter.record(r: DishCallResult) =
    write(Record(t_mono_ns = r.sentMonoNs + r.rttNs, t_wall_ms = System.currentTimeMillis(), dish = r.toRecord()))

private fun roundLine(round: Int, results: List<DishCallResult>): String = buildString {
    append("#%-4d".format(Locale.ROOT, round))
    for (r in results) {
        append(' ').append(r.kind.label.removePrefix("location:")).append('=')
        if (!r.ok) {
            append(r.statusText)
            continue
        }
        val s = r.response?.dish_get_status
        val f = r.response?.fix()
        when {
            s != null -> append("gps:${s.gps_stats?.gps_valid}/${s.gps_stats?.gps_sats}")
            f != null -> append(if (f.looksLikePlaceholder) "no-fix" else "σ%.1f".format(Locale.ROOT, f.sigmaM))
            else -> append("ok")
        }
    }
    val fixes = results.mapNotNull { r -> r.response?.fix()?.takeIf { r.ok && !it.looksLikePlaceholder }?.let { r.kind to it } }
    val gps = fixes.firstOrNull { it.first == DishCallKind.LOCATION_GPS }?.second
    val sl = fixes.firstOrNull { it.first == DishCallKind.LOCATION_STARLINK }?.second
    if (gps != null && sl != null) append("  |gps-starlink|=%.1fm".format(Locale.ROOT, Geo.distanceM(gps.lat, gps.lon, sl.lat, sl.lon)))
}

private fun dump(o: Opts) {
    val type = o.value("--type")
    val limit = o.value("--limit")?.toLong() ?: Long.MAX_VALUE
    val coords = o.flag("--coords")
    SlogReader.open(o.file()).use { r ->
        var n = 0L
        for (rec in r.records()) {
            if (type != null && Slog.payloadType(rec) != type) continue
            if (n++ >= limit) break
            println(describe(rec, coords))
        }
        if (r.truncatedTail) println("(truncated tail)")
        if (r.corruptRecords > 0) println("(${r.corruptRecords} undecodable records skipped)")
    }
}

private fun describe(rec: Record, coords: Boolean): String {
    val t = "%12.3f".format(Locale.ROOT, rec.t_mono_ns / 1e9)
    val type = Slog.payloadType(rec)
    val body = when {
        rec.dish != null -> DishCallResult.fromRecord(rec.dish!!)?.let { c ->
            val f = c.response?.fix()
            "${c.kind.label} ${c.statusText} ${c.rttNs / 1_000_000}ms" + (f?.let { " ${it.sourceName} σ=${it.sigmaM}" + if (coords) " ${it.lat},${it.lon}" else "" } ?: "") + boot(c) + newFields(c, coords, rec.t_wall_ms.takeIf { it > 0 })
        } ?: "unknown kind ${rec.dish!!.kind}"
        rec.phone_location != null -> rec.phone_location!!.let { p ->
            "${p.provider} acc=${p.h_acc} spd=${p.speed} brg=${p.bearing} mock=${p.is_mock}" + if (coords) " ${p.lat},${p.lon}" else ""
        }
        rec.imu != null -> "${rec.imu!!.samples.size} samples types=${rec.imu!!.samples.map { it.sensor_type }.toSortedSet()}"
        rec.gnss_status != null -> rec.gnss_status!!.sats.let { s -> "${s.size} sats, ${s.count { it.used }} used, mean cn0=${"%.1f".format(Locale.ROOT, s.map { it.cn0 }.average())}" }
        rec.gnss_measurements != null -> "${rec.gnss_measurements!!.meas.size} measurements, agc=${rec.gnss_measurements!!.agc.map { it.level_db }}"
        rec.nmea != null -> rec.nmea!!.sentence.trim()
        rec.event != null -> "${rec.event!!.kind}: ${rec.event!!.detail}"
        rec.header_ != null -> rec.header_.toString()
        rec.mock_output != null -> rec.mock_output!!.let { "${it.provider} from ${it.source} acc=${it.h_acc} ${it.error}" }
        else -> ""
    }
    return "$t $type $body"
}

/** The fields added with the dish and router features (router answers arrive redacted from the log). */
private fun newFields(c: DishCallResult, coords: Boolean, receivedWallMs: Long?): String {
    if (!c.ok || c.kind.locationSource != null) return ""
    val status = c.response?.dish_get_status
    return when {
        status != null -> " " + DishProbe.statusExtras(status)
        c.kind == DishCallKind.DEVICE_INFO -> ""
        else -> DishProbe.describe(c, coords, receivedWallMs).let { if (it.isEmpty()) "" else " $it" }
    }
}

/** Uptime and reboot fields of status and device-info answers. */
private fun boot(c: DishCallResult): String {
    val status = c.response?.dish_get_status
    val info = status?.device_info ?: c.response?.get_device_info?.device_info ?: return ""
    val b = info.boot
    return buildString {
        status?.device_state?.let { append(" uptime=${it.uptime_s}s") }
        append(" sw=${info.software_version} boots=${info.bootcount}")
        if (b != null) append(" boot.last=${BootReason.name(b.last_reason)} boot.count=${b.last_count} crash=${b.crash_boot}")
        if (status != null) {
            append(" reboot_reason=${RebootReason.name(status.reboot_reason)} update=${status.software_update_state}")
            if (status.swupdate_reboot_ready) append(" reboot_ready")
            if (status.seconds_until_swupdate_reboot_possible != 0) append(" reboot_possible_in=${status.seconds_until_swupdate_reboot_possible}s")
            status.software_update_stats?.let { u ->
                append(" update_stats=${u.software_update_state}/${u.software_update_progress}/requires_reboot=${u.update_requires_reboot}")
                if (u.reboot_scheduled_utc_time != 0L) append("/scheduled=${u.reboot_scheduled_utc_time}")
            }
            status.config?.let { cfg -> append(" reboot_hour=${cfg.swupdate_reboot_hour} deferral=${cfg.swupdate_three_day_deferral_enabled}") }
        }
    }
}

private fun index(o: Opts) {
    val idx = Sessions.scan(o.file())
    println("session ${idx.id}  ${idx.deviceModel ?: "?"}  app ${idx.appVersion ?: "?"}  dish ${idx.dishSoftware ?: "?"}")
    println("duration ${idx.durationMs / 1000.0}s  bytes ${idx.bytes}  distance ${idx.distanceM?.let { "%.0f m".format(Locale.ROOT, it) } ?: "-"}")
    println("records ${idx.records}")
    println("dish track ${idx.dishTrack.size} pts (${idx.dishTrackKind ?: "none"}), phone track ${idx.phoneTrack.size} pts")
    if (idx.truncatedTail) println("truncated tail")
}

private fun export(o: Opts) {
    val log = o.file()
    val idx = Sessions.scan(log)
    val from = idx.startMonoNs + ((o.value("--from") ?: throw IllegalArgumentException("--from required")).toDouble() * 1e9).toLong()
    val to = idx.startMonoNs + ((o.value("--to") ?: throw IllegalArgumentException("--to required")).toDouble() * 1e9).toLong()
    val out = File(o.value("--out") ?: (log.path.removeSuffix(".slog") + "-export.zip"))
    val m = SessionExporter.export(log, from, to, out, "slnav")
    println("wrote $out  records ${m.records}")
}

private fun fakeDish(o: Opts) {
    val deny = if (o.flag("--allow-set-config")) false else o.boolOption("--deny-set-config", default = true)
    val unimplemented = o.value("--unimplemented")?.split(',')?.flatMap { FakeArms.forToken(it) }?.toSet().orEmpty()
    val sim = FakeDishSimulation(
        spoofEverySec = o.value("--spoof-every")?.toLong() ?: 120,
        spoofForSec = o.value("--spoof-for")?.toLong() ?: 30,
        disableEverySec = o.value("--disable-every")?.toLong() ?: 0,
        disableForSec = o.value("--disable-for")?.toLong() ?: 0,
        locationLocal = !o.flag("--location-off"),
        rebootEverySec = o.value("--reboot-every")?.toLong() ?: 0,
        rebootReason = o.value("--reboot-reason")?.toInt() ?: 2,
        rebootDownSec = o.value("--reboot-down")?.toLong() ?: 40,
        updateWaiting = !o.flag("--no-update"),
        denySetConfig = deny,
        unimplemented = unimplemented,
        mountOffsetDeg = o.value("--mount-offset")?.toDouble() ?: -177.0,
        faultSidewaysM = o.value("--fault-sideways")?.toDouble() ?: 0.0,
        faultAtS = o.value("--fault-at")?.toDouble() ?: 0.0,
        faultRampS = o.value("--fault-ramp")?.toDouble() ?: 0.0,
        faultForS = o.value("--fault-for")?.toDouble() ?: 0.0,
    )
    val bind = o.value("--bind") ?: "127.0.0.1"
    val server = FakeDishServer(sim, bind, o.value("--port")?.toInt() ?: DishClient.WEB_PORT)
    println("fake dish serving gRPC-Web on $bind:${server.port} (Ctrl-C to stop)")
    println("  dish_set_config ${if (deny) "refused (PERMISSION_DENIED)" else "accepted"}; unimplemented: ${unimplemented.sorted().ifEmpty { listOf("-") }.joinToString()}")
    println("  location on the local network (location_request_mode): ${if (o.flag("--location-off")) "OFF, GetLocation refused until dish_set_config switches it to LOCAL" + (if (deny) " (refused: needs --allow-set-config)" else "") else "LOCAL"}")
    println("  dish heading = track %+.1f°".format(Locale.ROOT, sim.mountOffsetDeg) + (o.value("--fault-sideways")?.let { m ->
        "; AUTO and diagnostics $m m sideways from ${o.value("--fault-at") ?: "0"} s (ramp ${o.value("--fault-ramp") ?: "0"} s, for ${o.value("--fault-for") ?: "ever"} s)"
    } ?: ""))
    val router = o.value("--router-port")?.let { port ->
        val routerSim = FakeRouterSimulation(
            denySetConfig = o.flag("--router-deny-set-config"),
            unimplemented = o.value("--router-unimplemented")?.split(',')?.flatMap { FakeArms.forToken(it) }?.toSet().orEmpty(),
            rebootDownSec = o.value("--router-reboot-down")?.toLong() ?: 30,
        )
        FakeDishServer(routerSim, bind, port.toInt()).also { println("fake router serving gRPC-Web on $bind:${it.port}") }
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.close(); router?.close() })
    Thread.currentThread().join()
}
