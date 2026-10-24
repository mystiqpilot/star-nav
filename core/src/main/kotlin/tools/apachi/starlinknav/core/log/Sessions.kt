package tools.apachi.starlinknav.core.log

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.core.dish.fix
import java.io.File

@Serializable
data class TrackPoint(val t: Long, val lat: Double, val lon: Double)

@Serializable
data class SessionIndex(
    val id: String,
    val file: String,
    val startWallMs: Long,
    val endWallMs: Long,
    val startMonoNs: Long,
    val endMonoNs: Long,
    val bytes: Long,
    val records: Map<String, Long>,
    val truncatedTail: Boolean = false,
    val corruptRecords: Int = 0,
    val appVersion: String? = null,
    val deviceModel: String? = null,
    val dishSoftware: String? = null,
    /** Which dish location kind the dish track was built from, e.g. "location:STARLINK". */
    val dishTrackKind: String? = null,
    val dishTrack: List<TrackPoint> = emptyList(),
    val phoneTrack: List<TrackPoint> = emptyList(),
    val distanceM: Double? = null,
    val indexVersion: Int = VERSION,
) {
    val durationMs: Long get() = (endMonoNs - startMonoNs) / 1_000_000

    companion object {
        const val VERSION = 2
    }
}

object Sessions {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private const val MAX_TRACK_POINTS = 3000

    fun indexFileFor(log: File) = File(log.parentFile, log.name.removeSuffix(".${Slog.EXTENSION}") + ".json")

    fun readIndex(log: File): SessionIndex? = indexFileFor(log).takeIf { it.isFile }?.let {
        runCatching { json.decodeFromString(SessionIndex.serializer(), it.readText()) }.getOrNull()
    }?.takeIf { it.indexVersion == SessionIndex.VERSION && it.bytes == log.length() }

    fun writeIndex(log: File, index: SessionIndex) {
        val tmp = File(log.parentFile, indexFileFor(log).name + ".tmp")
        tmp.writeText(json.encodeToString(SessionIndex.serializer(), index))
        tmp.renameTo(indexFileFor(log))
    }

    fun indexOrScan(log: File): SessionIndex = readIndex(log) ?: scan(log).also { writeIndex(log, it) }

    fun scan(log: File): SessionIndex {
        val counts = sortedMapOf<String, Long>()
        var startMono = Long.MAX_VALUE
        var endMono = Long.MIN_VALUE
        var startWall = Long.MAX_VALUE
        var endWall = Long.MIN_VALUE
        var id = log.name.removeSuffix(".${Slog.EXTENSION}")
        var appVersion: String? = null
        var deviceModel: String? = null
        var dishSoftware: String? = null
        val dishByKind = HashMap<DishCallKind, MutableList<Pair<TrackPoint, Double>>>()
        val phone = ArrayList<TrackPoint>()
        val reader = SlogReader.open(log)
        reader.use { r ->
            for (rec in r.records()) {
                val type = Slog.payloadType(rec)
                counts[type] = (counts[type] ?: 0) + 1
                // An export keeps the header from before its window; it must not stretch the bounds.
                if (rec.header_ == null && rec.t_mono_ns != 0L) {
                    startMono = minOf(startMono, rec.t_mono_ns)
                    endMono = maxOf(endMono, rec.t_mono_ns)
                }
                if (rec.t_wall_ms != 0L) {
                    startWall = minOf(startWall, rec.t_wall_ms)
                    endWall = maxOf(endWall, rec.t_wall_ms)
                }
                rec.header_?.let { h ->
                    if (h.session_id.isNotEmpty()) id = h.session_id
                    appVersion = h.app_version
                    deviceModel = "${h.device_manufacturer} ${h.device_model}".trim()
                }
                rec.dish?.let { d ->
                    val call = DishCallResult.fromRecord(d) ?: return@let
                    val resp = call.response ?: return@let
                    if (dishSoftware == null) {
                        dishSoftware = resp.dish_get_status?.device_info?.software_version?.ifEmpty { null }
                            ?: resp.get_device_info?.device_info?.software_version?.ifEmpty { null }
                    }
                    if (call.ok && call.kind.locationSource != null && !call.kind.isSensitive) {
                        val f = resp.fix() ?: return@let
                        if (!f.looksLikePlaceholder) {
                            dishByKind.getOrPut(call.kind) { ArrayList() } += TrackPoint(rec.t_mono_ns, f.lat, f.lon) to f.sigmaM
                        }
                    }
                }
                rec.phone_location?.let { p ->
                    if (p.provider == "gps" && !p.is_mock) phone += TrackPoint(rec.t_mono_ns, p.lat, p.lon)
                }
            }
        }
        val dishKind = trackKind(dishByKind)
        val dishTrack = dishKind?.let { k -> decimate(perSecond(dishByKind.getValue(k).map { it.first })) }.orEmpty()
        val phoneTrack = decimate(perSecond(phone))
        if (startMono == Long.MAX_VALUE) startMono = 0
        if (endMono == Long.MIN_VALUE) endMono = startMono
        if (startWall == Long.MAX_VALUE) startWall = log.lastModified()
        if (endWall == Long.MIN_VALUE) endWall = startWall
        return SessionIndex(
            id = id,
            file = log.name,
            startWallMs = startWall,
            endWallMs = endWall,
            startMonoNs = startMono,
            endMonoNs = endMono,
            bytes = log.length(),
            records = counts,
            truncatedTail = reader.truncatedTail,
            corruptRecords = reader.corruptRecords,
            appVersion = appVersion,
            deviceModel = deviceModel,
            dishSoftware = dishSoftware,
            dishTrackKind = dishKind?.label,
            dishTrack = dishTrack,
            phoneTrack = phoneTrack,
            distanceM = pathLengthM(dishTrack.ifEmpty { phoneTrack }),
        )
    }

    /**
     * The dish source drawn as the track: the most precise one (lowest median sigma) among
     * those that answered at least half as often as the most frequent source.
     */
    private fun trackKind(byKind: Map<DishCallKind, List<Pair<TrackPoint, Double>>>): DishCallKind? {
        val most = byKind.values.maxOfOrNull { it.size } ?: return null
        return byKind.filterValues { it.size * 2 >= most }
            .minByOrNull { (_, pts) -> pts.map { it.second }.sorted()[pts.size / 2] }?.key
    }

    /** Sum of segment lengths, skipping segments that imply > 100 m/s (spoof jumps, gaps). */
    fun pathLengthM(track: List<TrackPoint>): Double? {
        if (track.size < 2) return null
        var total = 0.0
        for (i in 1 until track.size) {
            val a = track[i - 1]
            val b = track[i]
            val d = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
            val dt = (b.t - a.t) / 1e9
            if (dt > 0 && d / dt <= 100.0) total += d
        }
        return total
    }

    private fun perSecond(points: List<TrackPoint>): List<TrackPoint> {
        val out = ArrayList<TrackPoint>(points.size)
        var lastSecond = Long.MIN_VALUE
        for (p in points.sortedBy { it.t }) {
            val s = p.t / 1_000_000_000L
            if (s != lastSecond) {
                out += p
                lastSecond = s
            }
        }
        return out
    }

    private fun decimate(points: List<TrackPoint>): List<TrackPoint> {
        if (points.size <= MAX_TRACK_POINTS) return points
        val step = points.size.toDouble() / MAX_TRACK_POINTS
        return List(MAX_TRACK_POINTS) { i -> points[(i * step).toInt()] } + points.last()
    }
}
