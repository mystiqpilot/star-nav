package tools.apachi.starlinknav.service

import kotlinx.coroutines.flow.MutableStateFlow
import tools.apachi.starlinknav.MockMode
import tools.apachi.starlinknav.SettingsSnapshot
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.log.proto.PhoneLocation
import tools.apachi.starlinknav.core.nav.ChosenPosition
import tools.apachi.starlinknav.core.nav.Cutoff
import tools.apachi.starlinknav.core.nav.DishReboot
import tools.apachi.starlinknav.core.nav.ImuState
import tools.apachi.starlinknav.core.nav.GpsTrustState
import tools.apachi.starlinknav.core.nav.GpsTrustVerdict
import tools.apachi.starlinknav.core.nav.InertialState
import tools.apachi.starlinknav.core.nav.TrustComparison
import tools.apachi.starlinknav.core.nav.TrustFix
import tools.apachi.starlinknav.core.nav.UnknownWhy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class RecorderUi(
    val running: Boolean = false,
    val stopping: Boolean = false,
    val sessionId: String? = null,
    val sessionFile: String? = null,
    val startedWallMs: Long = 0,
    val bytes: Long = 0,
    val records: Long = 0,
    val dropped: Long = 0,
    val error: String? = null,
)

data class DishCallSample(val result: DishCallResult, val atMonoNs: Long)

data class DishUi(
    val route: DishRoute? = null,
    val transport: String = "-",
    val endpoint: String = "-",
    val last: Map<DishCallKind, DishCallSample> = emptyMap(),
)

data class PhoneUi(
    val lastGps: PhoneLocation? = null,
    val lastGpsAtMonoNs: Long = 0,
    val lastNetwork: PhoneLocation? = null,
    val satsVisible: Int = 0,
    val satsUsed: Int = 0,
    val meanCn0Used: Double? = null,
    val agcDb: List<Double> = emptyList(),
    val rates: Map<String, Double> = emptyMap(),
    val gnssModel: String? = null,
    /** When the satellite counts, signal level and AGC above were last updated (monotonic ns, 0 = never). */
    val gnssAtMonoNs: Long = 0,
)

/** The foreground service: runs while the mock or a recording is on. */
data class ServiceUi(val running: Boolean = false, val error: String? = null)

data class MockUi(
    val enabled: Boolean = false,
    val mode: tools.apachi.starlinknav.MockMode = tools.apachi.starlinknav.MockMode.AUTO,
    /** Reported accuracy of the last push, m. */
    val accuracyM: Float? = null,
    /** Starlink-only: seconds the source has not changed while the dish moved; null when it changes. */
    val frozenS: Double? = null,
    val error: String? = null,
    val pushes: Long = 0,
    val lastSource: String? = null,
    val lastPushMonoNs: Long = 0,
    /** How far the last pushed fix was moved forward, and its age then. */
    val lastShiftM: Double? = null,
    val lastAgeS: Double? = null,
    /** Android providers replaced by the mock (gps, network, fused). */
    val providers: List<String> = emptyList(),
    /** Google Play services' fused location: null = not in this build; else "on" or the error. */
    val googleFused: String? = null,
    /** Per source (gps, network, fused, gms_fused): does what apps receive match the push? */
    val delivery: Map<String, DeliveryUi> = emptyMap(),
    /** Network fallback: since when (0 = not active), the last fix passed on, its accuracy. */
    val fallbackSinceNs: Long = 0,
    val fallbackLastNs: Long = 0,
    val fallbackAccuracyM: Float? = null,
    /** Whether Android's network provider was on when the fallback started (Google Location Accuracy). */
    val networkEnabled: Boolean? = null,
)

data class DeliveryUi(val ok: Boolean, val offsetM: Double, val isMock: Boolean, val atMonoNs: Long)

/** Dish location cut-offs (UT disablement) in the running session. */
data class CutoffUi(
    val active: Cutoff? = null,
    val last: Cutoff? = null,
    val count: Int = 0,
    /** Total of the finished ones; the running one adds its own duration. */
    val finishedS: Double = 0.0,
)

/** Dish restarts seen while the service runs, and what the latest status says about uptime and updates. */
data class RebootUi(
    val last: DishReboot? = null,
    val count: Int = 0,
    /** Uptime in the latest status that had one, s, and when it came (monotonic ns). */
    val uptimeS: Long? = null,
    val uptimeAtNs: Long = 0,
    val software: String? = null,
    /** SoftwareUpdateState of the latest status; a downloaded update waits for a restart, planned at Unix s. */
    val updateState: Int = 0,
    val updateWaiting: Boolean = false,
    val updateRestartAtS: Long? = null,
    /** When a command that restarts the dish may have reached it (monotonic ns, 0 = none this service run). */
    val commandedNs: Long = 0,
)

/** Where the mount offset the filter uses came from. */
enum class MountSource {
    /** None yet: the sideways correction waits for one (dead reckoning starts from the AUTO course). */
    NONE,
    /** The value entered in Settings. */
    MANUAL,
    /** The value learned on an earlier drive, saved at [InertialUi.mountSavedAtMs]. */
    SAVED,
    /** Learned on this drive (it replaced the stored value, or there was none). */
    LEARNED_NOW,
}

/**
 * The inertial filter (InertialNav) as the service runs it, published about twice a second. It is fed while the
 * service runs; [serving] says whether Auto serves its output (mock on, Auto, correction on).
 */
data class InertialUi(
    val running: Boolean = false,
    val serving: Boolean = false,
    val state: InertialState = InertialState.NO_OUTPUT,
    /** Accuracy of the filter's output (told to apps while serving: 68 % radius), m; null while it has none. */
    val accuracyM: Double? = null,
    /** The output is the start-up raw AUTO fix: the filter itself has not started. */
    val raw: Boolean = false,
    /** The dish's estimated error to the right of the car, m (AUTO = true position + offset). */
    val sidewaysM: Double? = null,
    val imu: ImuState = ImuState.NONE,
    val forwardAxisReady: Boolean = false,
    /** Mount offset in use (stored or learned), degrees, where it came from, and when a saved one was learned. */
    val mountDeg: Double? = null,
    val mountSource: MountSource = MountSource.NONE,
    val mountSavedAtMs: Long = 0,
    /** When the current state started, and the last AUTO answer with a position (monotonic ns, 0 = never). */
    val sinceNs: Long = 0,
    val lastFixNs: Long = 0,
    /**
     * Times AUTO was held off to the side, watch-only suspects, gate rejects, filter resets since the service started
     * (a mount change restarts the filter; the counts carry on).
     */
    val sidewaysOpenings: Int = 0,
    val watchSuspects: Int = 0,
    val gateRejects: Int = 0,
    val resets: Int = 0,
    /** Seconds dead-reckoned (network-aided included) since the service started. */
    val deadReckoningS: Double = 0.0,
    /** Failures inside the filter's thread since the service started, and the last one's message. */
    val errors: Int = 0,
    val lastError: String? = null,
    /** When this snapshot was taken (monotonic ns). */
    val atNs: Long = 0,
)

/** What the status lines (Home, notification) say about Auto with the correction on. */
enum class InertialLine {
    /** Apps get the dish fix as without the correction: the filter has not started or had nothing at this fix. */
    STARTING,
    /** Following the dish; the sideways correction waits for the mount offset. */
    NO_MOUNT,
    TRACKING,
    SIDEWAYS,
    DEAD_RECKONING,
    NETWORK,
    /** Nothing served (no dish position and the dead reckoning is too uncertain). */
    STOPPED,
    /** Android's network position went straight to apps (the filter had nothing): the plain fallback lines. */
    PLAIN,
}

/**
 * Apps get the inertial filter's position: the service's own answer ([InertialUi.serving], published with the filter's
 * state), not the mode of the last push, which stays Auto after a switch to Raw while no new position goes out.
 * The settings are checked too, so the screens follow a switch at once and not at the next publish.
 */
fun inertialServesApps(i: InertialUi, m: MockUi, s: SettingsSnapshot): Boolean =
    i.running && i.serving && m.enabled && s.mockMode == MockMode.AUTO && s.inertialCorrection

/** Picks the status line from what was pushed last and the filter's state ([now] monotonic ns). */
fun inertialLine(i: InertialUi, m: MockUi, now: Long): InertialLine {
    val recent = m.lastPushMonoNs != 0L && now - m.lastPushMonoNs < 5_000_000_000L
    val source = m.lastSource.orEmpty()
    if (recent && source == "network-fallback") return InertialLine.PLAIN
    if (recent && source.startsWith("dish:")) return InertialLine.STARTING
    return when (i.state) {
        InertialState.TRACKING -> when {
            i.raw -> InertialLine.STARTING
            i.mountDeg == null -> InertialLine.NO_MOUNT
            else -> InertialLine.TRACKING
        }
        InertialState.SIDEWAYS_OPEN -> InertialLine.SIDEWAYS
        InertialState.DEAD_RECKONING -> InertialLine.DEAD_RECKONING
        InertialState.NETWORK_AIDED -> InertialLine.NETWORK
        InertialState.NO_OUTPUT -> InertialLine.STOPPED
    }
}

/** One position of one source, for the live map. Time is monotonic ns (elapsedRealtimeNanos). */
data class TrailPoint(
    val tNs: Long,
    val lat: Double,
    val lon: Double,
    val accM: Float? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
)

/**
 * The last minutes of every position source, newest last. Keys: "served" (what the mock gave
 * apps), "dish_auto", "dish_gps", "dish_starlink", and the phone's real fixes "phone_gps",
 * "phone_network", "phone_fused", "gms_fused" (absent while the mock replaces them).
 */
class TrailBook(private val keepNs: Long = 180_000_000_000L) {
    private val trails = HashMap<String, ArrayDeque<TrailPoint>>()

    @Synchronized
    fun add(key: String, p: TrailPoint) {
        val q = trails.getOrPut(key) { ArrayDeque() }
        q.addLast(p)
        while (q.isNotEmpty() && p.tNs - q.first().tNs > keepNs) q.removeFirst()
    }

    @Synchronized
    fun snapshot(): Map<String, List<TrailPoint>> = trails.mapValues { it.value.toList() }

    @Synchronized
    fun clear() = trails.clear()
}

/**
 * Can the phone's GPS be believed, and which position do "Center" and "Open in Google Maps" use ([chosen]): the
 * service's [tools.apachi.starlinknav.core.nav.GpsTrust] as of [atNs], published about twice a second while the service
 * runs. [gps] is the latest GPS fix, a mock fix too (the app's own mock while it is on: [GpsTrustState.MOCKED]); [auto]
 * the latest dish AUTO position, timed at the moment it describes.
 */
data class TrustUi(
    val verdict: GpsTrustVerdict = GpsTrustVerdict(GpsTrustState.UNKNOWN, unknown = UnknownWhy.NO_GPS_FIX),
    val gps: TrustFix? = null,
    val auto: TrustFix? = null,
    val comparison: TrustComparison? = null,
    val chosen: ChosenPosition? = null,
    val atNs: Long = 0,
)

/** Process-wide state the service publishes and the UI observes. */
object Live {
    val service = MutableStateFlow(ServiceUi())
    val recorder = MutableStateFlow(RecorderUi())
    val dish = MutableStateFlow(DishUi())
    val phone = MutableStateFlow(PhoneUi())
    val mock = MutableStateFlow(MockUi())
    val cutoff = MutableStateFlow(CutoffUi())
    val reboot = MutableStateFlow(RebootUi())
    val inertial = MutableStateFlow(InertialUi())
    val trust = MutableStateFlow(TrustUi())
    val trails = MutableStateFlow<Map<String, List<TrailPoint>>>(emptyMap())
}

/** Counts events per stream; [sample] returns rates averaged over the last [windowNs]. */
class RateMeter(private val windowNs: Long = 5_000_000_000L) {
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val history = ArrayDeque<Pair<Long, Map<String, Long>>>()

    fun inc(key: String, n: Long = 1) {
        counts.getOrPut(key) { AtomicLong() }.addAndGet(n)
    }

    @Synchronized
    fun sample(nowNs: Long): Map<String, Double> {
        val cur = counts.mapValues { it.value.get() }
        history.addLast(nowNs to cur)
        while (history.size > 2 && nowNs - history[1].first >= windowNs) history.removeFirst()
        val (t0, old) = history.first()
        val dt = (nowNs - t0) / 1e9
        if (dt <= 0) return emptyMap()
        return cur.mapValues { (k, v) -> (v - (old[k] ?: 0L)) / dt }.toSortedMap()
    }
}
