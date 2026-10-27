package tools.apachi.starlinknav.tools

import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.attitude
import tools.apachi.starlinknav.core.dish.fix
import tools.apachi.starlinknav.core.log.SlogReader
import java.io.File
import java.io.PrintWriter

/** Flattens a session into one CSV per stream, for analysis outside the app. */
object CsvExport {
    private class Table(dir: File, name: String, header: List<String>) {
        private val out = PrintWriter(File(dir, "$name.csv").bufferedWriter())
        var rows = 0
            private set

        init {
            out.println(header.joinToString(","))
        }

        fun row(vararg values: Any?) {
            out.println(values.joinToString(",") { cell(it) })
            rows++
        }

        fun close() = out.close()

        private fun cell(v: Any?): String {
            val s = when (v) {
                null -> ""
                is Boolean -> if (v) "1" else "0"
                else -> v.toString()
            }
            return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
    }

    fun run(log: File, outDir: File): Map<String, Int> {
        outDir.mkdirs()
        val calls = Table(outDir, "dish_calls", listOf("t_mono_ns", "kind", "http", "grpc", "rtt_ms", "error"))
        val locs = Table(outDir, "dish_loc", listOf("t_mono_ns", "kind", "source", "lat", "lon", "alt", "sigma_m", "hspeed", "vspeed"))
        val status = Table(
            outDir, "dish_status",
            listOf(
                "t_mono_ns", "gps_valid", "gps_sats", "inhibit_gps", "att_state", "att_unc_deg", "tilt_deg",
                "bore_az", "bore_el", "qw", "qx", "qy", "qz", "heading_x", "x_elev", "normal_az", "normal_el",
                "moving_fast", "uptime_s", "class_of_service", "mobility", "pop_latency_ms", "drop_rate", "disablement",
                // Satellite-slot and signal fields (appended 2026-10-27; old readers take the columns by name).
                "slot_s", "signal_quality", "snr_ok", "snr_low", "outage_cause", "outage_start_ns", "outage_dur_ns",
                "outage_switch", "pnt_state", "dl_bps", "ul_bps",
            ),
        )
        val diag = Table(outDir, "dish_diag", listOf("t_mono_ns", "t_wall_ms", "enabled", "lat", "lon", "alt", "unc_valid", "unc_m", "gps_time_s"))
        val phone = Table(
            outDir, "phone_loc",
            listOf(
                "t_mono_ns", "t_wall_ms", "provider", "lat", "lon", "alt", "h_acc", "v_acc", "speed", "speed_acc",
                "bearing", "bearing_acc", "time_ms", "elapsed_ns", "is_mock", "msl_alt",
            ),
        )
        val gstat = Table(
            outDir, "gnss_status",
            listOf("t_mono_ns", "n_seen", "n_used", "mean_cn0_used", "max_cn0", "used_by_constellation", "seen_l5", "used_l5"),
        )
        val gmeas = Table(
            outDir, "gnss_meas",
            listOf(
                "t_mono_ns", "n_meas", "time_ns", "full_bias_ns", "bias_ns", "drift_nsps", "hw_disc", "elapsed_rt_ns",
                "mean_cn0", "agc_db", "full_tracking", "by_constellation", "n_l5",
            ),
        )
        val imu = Table(outDir, "imu", listOf("t_ns", "type", "v0", "v1", "v2", "v3", "v4", "v5", "accuracy"))
        val events = Table(outDir, "events", listOf("t_mono_ns", "t_wall_ms", "kind", "detail"))
        val mock = Table(
            outDir, "mock",
            listOf("t_mono_ns", "provider", "lat", "lon", "alt", "h_acc", "speed", "bearing", "source", "error", "fix_lat", "fix_lon", "shift_m", "age_s"),
        )
        val nmea = Table(outDir, "nmea", listOf("t_mono_ns", "timestamp_ms", "sentence"))
        val nav = Table(outDir, "nav_message", listOf("t_mono_ns", "type", "svid", "message_id", "submessage_id", "status", "data_hex"))
        val tables = listOf(calls, locs, status, diag, phone, gstat, gmeas, imu, events, mock, nmea, nav)
        try {
            SlogReader.open(log).use { r ->
                for (rec in r.records()) {
                    val t = rec.t_mono_ns
                    rec.dish?.let { d ->
                        val c = DishCallResult.fromRecord(d) ?: return@let
                        calls.row(t, c.kind.label, c.httpStatus, c.grpcStatus, c.rttNs / 1e6, c.error ?: c.grpcMessage)
                        val resp = c.response ?: return@let
                        if (c.ok && c.kind.locationSource != null) {
                            resp.fix()?.let { f -> locs.row(t, c.kind.label, f.sourceName, f.lat, f.lon, f.alt, f.sigmaM, f.horizontalSpeedMps, f.verticalSpeedMps) }
                        }
                        resp.dish_get_status?.let { s ->
                            val g = s.gps_stats
                            val a = s.alignment_stats
                            val q = s.ned2dish_quaternion
                            val at = q?.attitude()
                            status.row(
                                t, g?.gps_valid, g?.gps_sats, g?.inhibit_gps, a?.attitude_estimation_state, a?.attitude_uncertainty_deg,
                                a?.tilt_angle_deg, s.boresight_azimuth_deg, s.boresight_elevation_deg, q?.q_scalar, q?.q_x, q?.q_y, q?.q_z,
                                at?.headingDeg, at?.xElevationDeg, at?.normalAzimuthDeg, at?.normalElevationDeg,
                                s.is_moving_fast_persisted, s.device_state?.uptime_s, s.class_of_service, s.mobility_class,
                                s.pop_ping_latency_ms, s.pop_ping_drop_rate, s.disablement_code,
                                s.seconds_to_first_nonempty_slot, s.signal_quality, s.is_snr_above_noise_floor, s.is_snr_persistently_low,
                                s.outage?.cause, s.outage?.start_timestamp_ns, s.outage?.duration_ns, s.outage?.did_switch,
                                g?.pnt_filter_convergence_state, s.downlink_throughput_bps, s.uplink_throughput_bps,
                            )
                        }
                        resp.dish_get_diagnostics?.location?.let { l ->
                            diag.row(t, rec.t_wall_ms, l.enabled, l.latitude, l.longitude, l.altitude_meters, l.uncertainty_meters_valid, l.uncertainty_meters, l.gps_time_s)
                        }
                    }
                    rec.phone_location?.let { p ->
                        phone.row(
                            t, rec.t_wall_ms, p.provider, p.lat, p.lon, p.alt, p.h_acc, p.v_acc, p.speed, p.speed_acc,
                            p.bearing, p.bearing_acc, p.time_ms, p.elapsed_ns, p.is_mock, p.msl_alt,
                        )
                    }
                    rec.gnss_status?.let { g ->
                        val used = g.sats.filter { it.used }
                        val byC = used.groupingBy { it.constellation }.eachCount().toSortedMap().entries.joinToString(" ") { "${it.key}:${it.value}" }
                        val l5 = { x: tools.apachi.starlinknav.core.log.proto.GnssSat -> (x.carrier_hz ?: 0f) in 1.1e9f..1.2e9f }
                        gstat.row(
                            t, g.sats.size, used.size, used.map { it.cn0.toDouble() }.average().takeIf { used.isNotEmpty() },
                            g.sats.maxOfOrNull { it.cn0 }, byC, g.sats.count(l5), used.count(l5),
                        )
                    }
                    rec.gnss_measurements?.let { m ->
                        val c = m.clock
                        gmeas.row(
                            t, m.meas.size, c?.time_ns, c?.full_bias_ns, c?.bias_ns, c?.drift_nsps, c?.hw_discontinuity_count,
                            c?.elapsed_realtime_ns, m.meas.map { it.cn0 }.average().takeIf { m.meas.isNotEmpty() },
                            m.agc.joinToString(" ") { "%d:%.1f".format(it.constellation, it.level_db) }, m.full_tracking,
                            m.meas.groupingBy { it.constellation }.eachCount().toSortedMap().entries.joinToString(" ") { "${it.key}:${it.value}" },
                            m.meas.count { (it.carrier_hz ?: 0f) in 1.1e9f..1.2e9f },
                        )
                    }
                    rec.imu?.samples?.forEach { s ->
                        imu.row(s.t_ns, s.sensor_type, s.v.getOrNull(0), s.v.getOrNull(1), s.v.getOrNull(2), s.v.getOrNull(3), s.v.getOrNull(4), s.v.getOrNull(5), s.accuracy)
                    }
                    rec.event?.let { events.row(t, rec.t_wall_ms, it.kind, it.detail) }
                    rec.mock_output?.let {
                        mock.row(t, it.provider, it.lat, it.lon, it.alt, it.h_acc, it.speed, it.bearing, it.source, it.error, it.fix_lat, it.fix_lon, it.shift_m, it.age_s)
                    }
                    rec.nmea?.let { nmea.row(t, it.timestamp_ms, it.sentence.trim()) }
                    rec.nav_message?.let { nav.row(t, it.type, it.svid, it.message_id, it.submessage_id, it.status, it.data_.hex()) }
                }
            }
        } finally {
            tables.forEach { it.close() }
        }
        return mapOf(
            "dish_calls" to calls.rows, "dish_loc" to locs.rows, "dish_status" to status.rows, "dish_diag" to diag.rows,
            "phone_loc" to phone.rows, "gnss_status" to gstat.rows, "gnss_meas" to gmeas.rows, "imu" to imu.rows,
            "events" to events.rows, "mock" to mock.rows, "nmea" to nmea.rows, "nav_message" to nav.rows,
        )
    }
}
