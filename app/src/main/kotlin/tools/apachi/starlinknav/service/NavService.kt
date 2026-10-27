package tools.apachi.starlinknav.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.BuildConfig
import tools.apachi.starlinknav.MockMode
import tools.apachi.starlinknav.MountMode
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.SettingsSnapshot
import tools.apachi.starlinknav.core.dish.BootReason
import tools.apachi.starlinknav.core.dish.CallTarget
import tools.apachi.starlinknav.core.dish.Disablement
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.DishFix
import tools.apachi.starlinknav.core.dish.RebootReason
import tools.apachi.starlinknav.core.dish.fix
import tools.apachi.starlinknav.core.log.Sessions
import tools.apachi.starlinknav.core.log.proto.AppEvent
import tools.apachi.starlinknav.core.log.proto.Record
import tools.apachi.starlinknav.core.log.proto.SessionHeader
import tools.apachi.starlinknav.core.nav.Cutoff
import tools.apachi.starlinknav.core.nav.CutoffTracker
import tools.apachi.starlinknav.core.nav.DishPositionBounds
import tools.apachi.starlinknav.core.nav.DishReboot
import tools.apachi.starlinknav.core.nav.FixExtrapolator
import tools.apachi.starlinknav.core.nav.ReceiverClock
import tools.apachi.starlinknav.core.nav.GpsTrust
import tools.apachi.starlinknav.core.nav.TrustFix
import tools.apachi.starlinknav.core.nav.InertialState
import tools.apachi.starlinknav.core.nav.RebootTracker
import tools.apachi.starlinknav.ui.MainActivity
import tools.apachi.starlinknav.ui.MockTileService
import tools.apachi.starlinknav.ui.disablementReason
import tools.apachi.starlinknav.ui.duration
import tools.apachi.starlinknav.ui.rebootCause
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The engine, a location foreground service. It runs while the mock GPS or a recording is on:
 * polls the dish, feeds the mock (gps, network and fused providers, and Google's fused location
 * in the play build), checks what apps receive, follows speed-policy cut-offs and dish restarts, and while
 * recording writes everything (dish, phone GNSS, IMU) to one session log.
 *
 * Auto mode with the inertial correction on serves the inertial filter ([InertialRunner], its own thread); with
 * the correction off, and in Raw and Starlink only, the dish fix as before (FixExtrapolator, network fallback
 * passed straight to apps).
 */
class NavService : Service() {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            // Keep what still works running; make the failure visible in the log and on screen.
            event("error", e.stackTraceToString().take(4000))
            Live.service.update { it.copy(error = getString(R.string.status_error_internal, e.message)) }
        },
    )
    private val rates = RateMeter()
    private val phoneState = PhoneState()
    private val clients = DishClients()
    private lateinit var wifi: WifiTracker
    private lateinit var mock: MockGpsOutput
    private lateinit var googleFused: FusedMock
    private val delivery = MockDelivery()
    private val trails = TrailBook()
    private val fusedExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "gms-fused") }
    private var running = false
    @Volatile private var writer: SessionWriter? = null
    private var sessionId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var gnss: GnssCollector? = null
    /** The phone's wall clock against GNSS time (real GPS fixes, NMEA): the diagnostics fix age is measured with it. */
    private val receiverClock = ReceiverClock()
    private var imu: PhoneSensors? = null
    private val jobs = mutableListOf<Job>()
    @Volatile private var mockEnabled = false
    /** The live map is open: poll every dish source even without a recording. */
    @Volatile private var watching = false
    // Network fallback state; changed from the poller, the location thread and the publish loop.
    private val fallbackLock = Any()
    @Volatile private var mockOnNs = 0L
    @Volatile private var lastDishPushNs = 0L
    @Volatile private var fallbackSinceNs = 0L
    private var fallbackFixes = 0
    @Volatile private var mockPushes = 0L
    // Fed only from the dish poller's coroutine.
    private var cutoffs = CutoffTracker()
    /** Fed from the dish poller and from [sendCommand] (the command bus): every access holds [trackerLock]. */
    private var reboots = RebootTracker()
    private val trackerLock = Any()
    /** The command bus' clients (not the poller's [clients]), shared with the screens' one-off calls. */
    private val devices by lazy { Devices.shared(this) }
    /** Sends the commands the screens ask for through [CommandBus]: logged into the session, counted by the restart tracker. */
    private val commandHandler = CommandBus.Handler { call -> sendCommand(call) }
    private var served = FixExtrapolator()
    /** Is the phone's GPS spoofed: its fixes against the dish's; fed from the location thread and the dish poller. */
    private val trust = GpsTrust()
    /** Course of the Starlink-only source from its own fixes (no GNSS-derived data in that mode). */
    private var starlinkCourse = FixExtrapolator()
    private var starlinkLast: DishFix? = null
    private var starlinkChangedNs = 0L
    private var lastAutoSpeed = 0.0
    @Volatile private var lastAutoAlt: Double? = null
    /** The inertial filter, from the service's start to its end. */
    @Volatile private var inertial: InertialRunner? = null
    /** Settings seen last by [onSettings] (main thread). */
    private var lastSettings: SettingsSnapshot? = null
    /** A filter restart waiting for the mount setting to settle (main thread). */
    private var mountRestart: Job? = null
    @Volatile private var announcedCutoffStart: Long? = null
    /** What the cut-off alert on screen says: the position continues (true), or not; null = no cut-off alert. */
    @Volatile private var cutoffAlertContinues: Boolean? = null
    private var endedCutoffStart: Long? = null
    private var lastNotificationText: String? = null
    /** The running recording was started by switching the mock on, so it ends with the mock. */
    private var recordingFollowsMock = false
    /** The start id of the last command handled (main thread): [stopIfIdle] stops only if no later one is queued. */
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        wifi = WifiTracker(this)
        mock = MockGpsOutput(this)
        googleFused = FusedMock(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_RECORD -> if (intent.getBooleanExtra(EXTRA_ON, true)) startRecording() else stopRecording()
            ACTION_MOCK -> setMock(intent.getBooleanExtra(EXTRA_ON, !mockEnabled))
            ACTION_WATCH -> setWatch(intent.getBooleanExtra(EXTRA_ON, true))
            ACTION_STOP_ALL -> stopAll()
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    private fun settings(): SettingsSnapshot = App.instance.settings.value

    /** Brings up the foreground service and the dish poller; false when Android refuses. */
    private fun ensureRunning(): Boolean {
        if (running) return true
        try {
            val starting = notification(getString(R.string.notif_starting))
            // The service type argument is API 29 (the manifest's foregroundServiceType="location" is ignored before it).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, starting)
        } catch (e: Exception) {
            Live.service.value = ServiceUi(error = getString(R.string.status_error_cannot_start, e.message))
            return false
        }
        running = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "starnav:service")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        cutoffs = CutoffTracker(::onCutoff)
        synchronized(trackerLock) { reboots = RebootTracker(::onReboot) }
        served = FixExtrapolator()
        trust.reset()
        Live.trust.value = TrustUi()
        starlinkCourse = FixExtrapolator()
        starlinkLast = null
        announcedCutoffStart = null
        endedCutoffStart = null
        Live.cutoff.value = CutoffUi()
        Live.reboot.value = RebootUi()
        trails.clear()
        Live.trails.value = emptyMap()
        Live.dish.value = DishUi()
        lastAutoAlt = null
        inertial = InertialRunner(
            clock = receiverClock,
            startMount = {
                settings().let {
                    InertialRunner.StartMount(it.effectiveMountDeg, it.mountMode == MountMode.MANUAL, it.mountLearnedAtMs, it.mountLearned)
                }
            },
            serving = ::inertialServing,
            onServe = ::pushInertial,
            onEvent = { e ->
                val nowNs = SystemClock.elapsedRealtimeNanos()
                submit(Record(t_mono_ns = e.tNs, t_wall_ms = System.currentTimeMillis() - (nowNs - e.tNs) / 1_000_000, event = e.toAppEvent()))
            },
            onMountLearned = ::onMountLearned,
            // Rate-limited by the runner (one per 10 s); the Details card shows the count either way.
            onError = { e, suppressed ->
                event("error", "inertial" + (if (suppressed > 0) " ($suppressed more not logged)" else "") + ": " + e.stackTraceToString().take(4000))
            },
        )
        lastSettings = null
        wifi.start()
        startGnss(full = false)
        // What Google's fused location gives apps (play build), logged as provider "gms_fused".
        googleFused.listen(fusedExecutor) { loc ->
            rates.inc("gms_fused_fix")
            onReceived("gms_fused", loc)
            submit(Record(t_mono_ns = SystemClock.elapsedRealtimeNanos(), t_wall_ms = System.currentTimeMillis(), phone_location = loc.toProto().copy(provider = "gms_fused")))
        }
        jobs += scope.launch {
            DishPoller(client = { clients.get(settings(), wifi.network.value) }, wanted = ::wantedKinds, onResult = ::onDish).run()
        }
        jobs += scope.launch { publishLoop() }
        CommandBus.attach(commandHandler)
        // The IMU and the filter follow the mode, the correction switch and the mount setting.
        jobs += scope.launch(Dispatchers.Main) { App.instance.settings.flow.collect(::onSettings) }
        Live.service.value = ServiceUi(running = true)
        return true
    }

    /**
     * Everything while recording; every location source while the live map is open; otherwise
     * status, diagnostics (fix age) and the locations the mock needs.
     */
    private fun wantedKinds(): Set<DishCallKind> {
        val base = setOf(DishCallKind.STATUS, DishCallKind.DIAGNOSTICS, DishCallKind.DEVICE_INFO, DishCallKind.LOCATION_AUTO)
        if (writer != null) return base + DishCallKind.RECORDED_LOCATIONS + DishCallKind.GNSS_MEASUREMENT
        if (watching) return base + DishCallKind.RECORDED_LOCATIONS
        return base + settings().mockMode.source
    }

    private fun startGnss(full: Boolean) {
        gnss?.stop()
        gnss = GnssCollector(this, ::submit, rates, phoneState, ::onReceived, onNmea = { m -> receiverClock.onNmea(m, System.currentTimeMillis()) }).also {
            it.start(full)
            // The Position page keeps the satellite counts and AGC current without a recording.
            if (!full && watching) it.setLive(true)
        }
    }

    private fun startRecording() {
        if (writer != null) return
        // Logging switched off in Settings: no session log is ever opened, whoever asks (screen, notification, mock).
        if (!settings().loggingEnabled) return
        if (!ensureRunning()) return
        val s = settings()
        SessionStore.prune(this, s, active = null)
        val (id, file) = SessionStore.newSession(this)
        sessionId = id
        val w = SessionWriter(file, scope).also { it.start() }
        writer = w
        submit(
            Record(
                t_mono_ns = SystemClock.elapsedRealtimeNanos(), t_wall_ms = System.currentTimeMillis(),
                header_ = SessionHeader(
                    session_id = id, app_version = BuildConfig.VERSION_NAME, device_manufacturer = Build.MANUFACTURER,
                    device_model = Build.MODEL, sdk_int = Build.VERSION.SDK_INT, config_json = s.toJson(),
                    clock = "elapsedRealtimeNanos",
                ),
            ),
        )
        event("recorder_start", "flavor=${BuildConfig.FLAVOR} mock=${if (mockEnabled) s.mockMode.name else "off"}")
        if (mockEnabled) event("mock", "on mode=${s.mockMode.name} providers=${mock.providers.joinToString(",")} inertial=${onOff(s.inertialCorrection)}")
        startGnss(full = true)
        updateImu()
        Live.recorder.value = RecorderUi(running = true, sessionId = id, sessionFile = file.name, startedWallMs = System.currentTimeMillis())
        updateNotification(force = true)
    }

    private fun stopRecording() {
        recordingFollowsMock = false
        val w = writer ?: return
        Live.recorder.update { it.copy(stopping = true) }
        w.submit(Record(t_mono_ns = SystemClock.elapsedRealtimeNanos(), t_wall_ms = System.currentTimeMillis(), event = AppEvent(kind = "recorder_stop")))
        writer = null
        sessionId = null
        updateImu()
        if (running) startGnss(full = false)
        scope.launch {
            w.close()
            withContext(Dispatchers.IO) { runCatching { Sessions.writeIndex(w.file, Sessions.scan(w.file)) } }
            Live.recorder.value = RecorderUi()
            withContext(Dispatchers.Main) {
                updateNotification(force = true)
                stopIfIdle()
            }
        }
    }

    private fun setMock(on: Boolean) {
        if (on == mockEnabled && (!on || mock.active)) return
        if (on) {
            if (!ensureRunning()) {
                Live.mock.value = MockUi(error = Live.service.value.error)
                return
            }
            val s = settings()
            val error = mock.enable()
            mockEnabled = error == null
            delivery.clear()
            synchronized(fallbackLock) {
                mockOnNs = SystemClock.elapsedRealtimeNanos()
                lastDishPushNs = 0L
                fallbackSinceNs = 0L
            }
            Live.mock.value = MockUi(
                enabled = mockEnabled, mode = s.mockMode, error = error, providers = mock.providers,
                googleFused = if (FusedMock.IN_BUILD && mockEnabled) getString(R.string.mock_google_fused_starting) else null,
            )
            if (mockEnabled && s.recordWithMock && s.loggingEnabled && writer == null) {
                startRecording()
                recordingFollowsMock = true
            }
            event("mock", if (error == null) "on mode=${s.mockMode.name} providers=${mock.providers.joinToString(",")} inertial=${onOff(s.inertialCorrection)}" else "failed: $error")
            // Extra providers this Android could not replace (e.g. "fused" before Android 12): the GPS mock runs without them.
            for ((provider, why) in mock.unavailable) event("mock_provider_unavailable", "$provider: $why")
            updateImu()
            if (mockEnabled && FusedMock.IN_BUILD) {
                scope.launch {
                    val e = googleFused.enable()
                    event("mock_google_fused", e ?: "on")
                    Live.mock.update { it.copy(googleFused = e ?: getString(R.string.mock_google_fused_on)) }
                }
            }
        } else {
            endFallback("mock off")
            mock.disable()
            googleFused.disable()
            mockEnabled = false
            // The phone's last GPS fix is the mock's own: forget it, or the GPS card says "mock fixes: another app is
            // mocking" until the receiver's first real fix arrives (seconds on a Pixel). Mock fixes still in flight are dropped.
            trust.forgetGps(SystemClock.elapsedRealtimeNanos())
            Live.mock.update { it.copy(enabled = false, providers = emptyList(), delivery = emptyMap(), frozenS = null) }
            event("mock", "off")
            // A recording started together with the mock ends with it.
            if (recordingFollowsMock) stopRecording()
            updateImu()
        }
        updateNotification(force = true)
        TileService.requestListeningState(this, ComponentName(this, MockTileService::class.java))
    }

    private fun setWatch(on: Boolean) {
        if (on && !ensureRunning()) return
        watching = on
        gnss?.setLive(on)
        updateNotification(force = true)
    }

    private fun stopAll() {
        if (mockEnabled) setMock(false)
        stopRecording()
        stopIfIdle()
    }

    /**
     * Neither mock nor recording: take the service down (a closing recording finishes first). Stops only when no start
     * is queued after the last one handled: an "on" that is already on its way (the live map's watch from an Activity
     * recreated at once) brings the service up again instead of reaching one that is being destroyed.
     */
    private fun stopIfIdle() {
        if (mockEnabled || writer != null || watching || Live.recorder.value.stopping) return
        if (running) {
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        }
        stopSelfResult(lastStartId)
    }

    private fun shutdown() {
        running = false
        CommandBus.detach(commandHandler)
        jobs.forEach { it.cancel() }
        jobs.clear()
        gnss?.stop()
        gnss = null
        imu?.stop()
        imu = null
        inertial?.shutdown()
        inertial = null
        googleFused.stopListening()
        wifi.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        Live.service.value = ServiceUi()
        Live.dish.value = DishUi()
        Live.trust.value = TrustUi()
        trust.reset()
        TileService.requestListeningState(this, ComponentName(this, MockTileService::class.java))
    }

    private fun onDish(result: DishCallResult) {
        val now = SystemClock.elapsedRealtimeNanos()
        val wallMs = System.currentTimeMillis()
        submit(Record(t_mono_ns = now, t_wall_ms = wallMs, dish = result.toRecord()))
        val fix = if (result.ok) result.response?.fix()?.takeIf { !it.looksLikePlaceholder } else null
        val mode = settings().mockMode
        // Today's Auto position, fed while the mock is off too, so the course is known the moment it is switched on.
        val autoPosition = if (result.kind == DishCallKind.LOCATION_AUTO && fix != null) {
            lastAutoSpeed = fix.horizontalSpeedMps
            lastAutoAlt = fix.alt
            served.serve(fix, now)
        } else {
            null
        }
        // Every call, mode or not: the filter is warm when Auto serves it. With the correction on it pushes itself,
        // and today's position when it has nothing at an AUTO fix (not started yet, or after a reset).
        val plain: (() -> Unit)? = if (fix != null && autoPosition != null && mode == MockMode.AUTO) {
            { push(fix, autoPosition, result.kind, MockMode.AUTO, now) }
        } else {
            null
        }
        inertial?.dish(result, now, wallMs, plain)
        Live.dish.update { d -> d.copy(transport = result.transport, last = d.last + (result.kind to DishCallSample(result, now))) }
        cutoffs.onCall(result, now)
        CapabilityStore.record(result)
        synchronized(trackerLock) {
            reboots.onCall(result, now)
            if (result.kind == DishCallKind.STATUS && result.ok) {
                val st = result.response?.dish_get_status
                Live.reboot.update {
                    it.copy(
                        uptimeS = reboots.uptimeS, uptimeAtNs = reboots.uptimeAtNs ?: 0, software = reboots.software,
                        updateState = st?.software_update_state ?: 0, updateWaiting = reboots.updateWaiting, updateRestartAtS = reboots.updateRestartAtS,
                    )
                }
            }
        }
        if (result.kind == DishCallKind.DIAGNOSTICS && result.ok) {
            result.response?.dish_get_diagnostics?.location?.gps_time_s
                ?.let { receiverClock.fixAgeS(it, System.currentTimeMillis()) }?.let(served::onFixAge)
        }
        if (fix == null) return
        // The explicit STARLINK source is told as what it is worth (6-21 km from AUTO on the 2026-10-26 drive): the trail
        // and the Starlink-only push carry the bound; the GPS check keeps the raw fix (its rule 3 wants the dish's own σ).
        val bounded = if (result.kind == DishCallKind.LOCATION_STARLINK) fix.copy(sigmaM = DishPositionBounds.toldAccuracyM(result.kind, fix)) else fix
        TRAIL_KEYS[result.kind]?.let { key ->
            trails.add(key, TrailPoint(now, bounded.lat, bounded.lon, bounded.sigmaM.toFloat(), bounded.horizontalSpeedMps.toFloat()))
        }
        // The GPS check: the AUTO fix at the moment it describes (receipt minus its age), the Starlink-only one as it came.
        if (result.kind == DishCallKind.LOCATION_AUTO && autoPosition != null) {
            trust.onAutoFix(fix.lat, fix.lon, fix.sigmaM, fix.horizontalSpeedMps, now - (autoPosition.ageS * 1e9).toLong())
        } else if (result.kind == DishCallKind.LOCATION_STARLINK) {
            trust.onStarlinkOnlyFix(fix.lat, fix.lon, now)
        }
        when (result.kind) {
            DishCallKind.LOCATION_AUTO -> {
                val position = autoPosition ?: return
                when (mode) {
                    // With the correction on the inertial thread pushes the filter's position instead.
                    MockMode.AUTO -> if (!inertialServing()) push(fix, position, result.kind, mode, now)
                    MockMode.RAW -> push(fix, position.copy(lat = fix.lat, lon = fix.lon, shiftM = 0.0), result.kind, mode, now)
                    MockMode.STARLINK_ONLY -> Unit
                }
            }
            DishCallKind.LOCATION_STARLINK -> {
                val course = starlinkCourse.serve(fix, now)
                val previous = starlinkLast
                if (previous == null || previous.lat != fix.lat || previous.lon != fix.lon) starlinkChangedNs = now
                starlinkLast = fix
                if (mode == MockMode.STARLINK_ONLY) {
                    // The source has been seen frozen for whole drives: say so while the car moves.
                    val frozen = ((now - starlinkChangedNs) / 1e9).takeIf { it > FROZEN_AFTER_S && lastAutoSpeed > 3.0 }
                    Live.mock.update { it.copy(frozenS = frozen) }
                    push(bounded, course.copy(lat = fix.lat, lon = fix.lon, shiftM = 0.0), result.kind, mode, now)
                }
            }
            else -> Unit
        }
    }

    private fun push(fix: DishFix, position: FixExtrapolator.Served, kind: DishCallKind, mode: MockMode, now: Long) {
        if (!mockEnabled) return
        // The dish is back: Android's network provider is replaced again before this push reaches it.
        endFallback("dish back")
        lastDishPushNs = now
        val pushed = mock.push(fix, position, "dish:${kind.label}:${mode.name}", before = delivery::pushed)
        val out = pushed.record
        googleFused.push(pushed.location)
        submit(Record(t_mono_ns = SystemClock.elapsedRealtimeNanos(), t_wall_ms = System.currentTimeMillis(), mock_output = out))
        mockPushes++
        trails.add("served", TrailPoint(now, out.lat, out.lon, out.h_acc, out.speed, out.bearing))
        Live.mock.update {
            it.copy(
                enabled = mock.active, mode = mode, pushes = mockPushes, lastSource = out.source, lastPushMonoNs = now,
                error = out.error.ifEmpty { null }, lastShiftM = position.shiftM, lastAgeS = position.ageS, accuracyM = out.h_acc,
            )
        }
        if (!mock.active) mockLost()
    }

    /**
     * Any thread: a push found the mock gone (the mock-location permission was taken back). Pushes stop at once;
     * the rest of switching it off (IMU, a recording that followed it, the notification) runs on the main thread.
     */
    private fun mockLost() {
        if (!mockEnabled) return
        mockEnabled = false
        // The replaced providers are forgotten (given back where Android still allows it), so the next "on" starts clean.
        mock.disable()
        scope.launch(Dispatchers.Main) {
            Live.mock.update { it.copy(enabled = false, providers = emptyList(), delivery = emptyMap(), frozenS = null) }
            if (!running) return@launch
            endFallback("mock lost")
            googleFused.disable()
            event("mock", "off: not active (permission?)")
            if (recordingFollowsMock) stopRecording()
            updateImu()
            updateNotification(force = true)
            TileService.requestListeningState(this@NavService, ComponentName(this@NavService, MockTileService::class.java))
            stopIfIdle()
        }
    }

    /** Auto with the inertial correction and the mock on: apps get the filter's position. */
    private fun inertialServing(): Boolean = mockEnabled && settings().let { it.mockMode == MockMode.AUTO && it.inertialCorrection }

    /**
     * What the inertial filter serves (on its thread). Only a serve based on an AUTO answer with a position counts
     * as a dish push for the network fallback: dead-reckoning serves must not hold the fallback back, or Android's
     * network provider is never released and the filter never gets the network fixes it uses in long outages.
     */
    private fun pushInertial(s: InertialRunner.Serve) {
        if (!mockEnabled) return
        val now = s.nowNs
        if (s.fix != null) {
            // The dish is back: Android's network provider is replaced again before this push reaches it.
            endFallback("dish back")
            lastDishPushNs = now
        }
        val est = s.estimate
        val pushed = mock.pushEstimate(est, s.fix, s.fix?.alt ?: lastAutoAlt, "inertial:${est.state.name}", before = delivery::pushed)
        val out = pushed.record
        googleFused.push(pushed.location)
        submit(Record(t_mono_ns = SystemClock.elapsedRealtimeNanos(), t_wall_ms = System.currentTimeMillis(), mock_output = out))
        mockPushes++
        trails.add("served", TrailPoint(now, out.lat, out.lon, out.h_acc, out.speed, out.bearing))
        Live.mock.update {
            it.copy(
                enabled = mock.active, mode = MockMode.AUTO, pushes = mockPushes, lastSource = out.source, lastPushMonoNs = now,
                error = out.error.ifEmpty { null }, lastShiftM = out.shift_m?.toDouble(), lastAgeS = null, accuracyM = out.h_acc,
            )
        }
        if (!mock.active) mockLost()
    }

    /** The filter learned the mount offset, or its learned value moved (inertial thread): kept for the next start. */
    private fun onMountLearned(deg: Double) {
        App.instance.settings.update { it.copy(mountLearnedDeg = deg.toFloat(), mountLearnedAtMs = System.currentTimeMillis()) }
        event("mount_saved", "deg=%+.1f mode=%s".format(Locale.ROOT, deg, settings().mountMode.name))
    }

    /** Main thread: the IMU follows the mode and switches, a changed mount setting restarts the filter. */
    private fun onSettings(s: SettingsSnapshot) {
        val prev = lastSettings
        lastSettings = s
        if (!running) return
        // Logging switched off while a recording runs: it ends now (and the notification loses its record action).
        if (!s.loggingEnabled && writer != null) stopRecording()
        // Logging switched back on while the mock is on and "record whenever the mock is on" is set: that recording starts
        // now (it would otherwise wait for the next switch of the mock).
        if (prev != null && !prev.loggingEnabled && s.loggingEnabled && mockEnabled && s.recordWithMock && writer == null) {
            startRecording()
            recordingFollowsMock = writer != null
        }
        updateImu()
        if (prev == null) return
        if (prev.loggingEnabled != s.loggingEnabled) updateNotification(force = true)
        // NaN (no manual value) compared by bits: NaN != NaN would restart the filter on every settings change.
        if (prev.mountMode != s.mountMode || (s.mountMode == MountMode.MANUAL && prev.mountManualDeg.toBits() != s.mountManualDeg.toBits())) {
            // The manual field commits every keystroke (-1, -17, -177): the filter restarts once the value has settled,
            // not with each partial one as its mount.
            mountRestart?.cancel()
            mountRestart = scope.launch(Dispatchers.Main) {
                delay(MOUNT_SETTLE_MS)
                val now = settings()
                event("setting", "mount mode=${now.mountMode.name} deg=${now.effectiveMountDeg?.let { "%+.1f".format(Locale.ROOT, it) } ?: "-"}")
                inertial?.reset()
            }
        }
        if (prev.inertialCorrection != s.inertialCorrection) event("setting", "inertialCorrection=${onOff(s.inertialCorrection)}")
        if (prev.mockMode != s.mockMode) event("setting", "mockMode=${s.mockMode.name}")
        if (prev.inertialCorrection != s.inertialCorrection || prev.mockMode != s.mockMode) updateNotification(force = true)
    }

    /**
     * Main thread. The phone IMU runs while recording (every sensor, written to the session, batched as before) and
     * while Auto serves the inertial filter (accelerometer and gyroscope per event, unbatched); one registration
     * feeds both. A recording without the correction serving registers exactly as before the filter existed.
     */
    private fun updateImu() {
        val wantRecord = writer != null
        val wantTap = inertialServing()
        val want = running && (wantRecord || wantTap)
        val cur = imu
        if (!want) {
            cur?.stop()
            imu = null
            return
        }
        if (cur != null && cur.recording == wantRecord && cur.tapping == wantTap) return
        cur?.stop()
        val tap: ((Int, Long, List<Float>) -> Unit)? = if (wantTap) { type, tNs, v -> inertial?.imu(type, tNs, v) } else null
        imu = PhoneSensors(this, if (wantRecord) ::submit else null, rates, tap).also { it.start(settings().imuRateHz) }
    }

    /** A location some provider handed to this app: with the mock on, check it is the pushed one. */
    private fun onReceived(source: String, loc: Location) {
        if (source == "gps") {
            val at = loc.elapsedRealtimeNanos.takeIf { it > 0 } ?: SystemClock.elapsedRealtimeNanos()
            if (!loc.isMockFix) receiverClock.onGpsFix(loc.time, loc.elapsedRealtimeNanos, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
            trust.onGpsFix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0, at, isMock = loc.isMockFix)
        }
        // The phone's own positions for the live map; with the mock on they are the mock's.
        if (!loc.isMockFix) {
            trails.add(
                if (source == "gms_fused") source else "phone_$source",
                TrailPoint(
                    loc.elapsedRealtimeNanos.takeIf { it > 0 } ?: SystemClock.elapsedRealtimeNanos(), loc.latitude, loc.longitude,
                    loc.accuracy.takeIf { loc.hasAccuracy() }, loc.speed.takeIf { loc.hasSpeed() }, loc.bearing.takeIf { loc.hasBearing() },
                ),
            )
        }
        if (!mockEnabled) return
        if (source == "network" && fallbackSinceNs != 0L) {
            // Android's own cell/Wi-Fi position, released for the fallback. With the correction on it is a weak input
            // of the filter while it dead-reckons, and goes to apps as it came when the filter has nothing to serve;
            // else it is passed on.
            if (!loc.isMockFix) {
                val runner = inertial
                if (inertialServing() && runner != null) {
                    noteFallbackFix(loc)
                    runner.network(loc, SystemClock.elapsedRealtimeNanos()) { pushFallback(it, counted = true) }
                } else {
                    pushFallback(loc)
                }
            }
            return
        }
        val d = delivery.check(loc) ?: return
        Live.mock.update { it.copy(delivery = it.delivery + (source to d)) }
    }

    private fun fallbackWanted(): Boolean = settings().let { it.networkFallback && it.mockMode == MockMode.AUTO }

    /**
     * Auto mode: once the dish has given no
     * position for [FALLBACK_AFTER_NS], Android's own network provider (cell towers and Wi-Fi via
     * the phone's location service) is let through and its fixes are passed on to apps, until
     * the dish answers again. Checked twice a second.
     */
    private fun checkFallback() {
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(fallbackLock) {
            if (fallbackSinceNs != 0L) {
                if (!mockEnabled || !fallbackWanted()) endFallback("off")
                return
            }
            if (!mockEnabled || !fallbackWanted() || now - maxOf(lastDishPushNs, mockOnNs) < FALLBACK_AFTER_NS) return
            mock.releaseNetwork()
            fallbackSinceNs = now
            fallbackFixes = 0
        }
        val enabled = runCatching { getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        event("fallback", "start network_enabled=$enabled to=${if (inertialServing()) "inertial" else "apps"}")
        Live.mock.update { it.copy(fallbackSinceNs = now, fallbackLastNs = 0, fallbackAccuracyM = null, networkEnabled = enabled) }
        updateNotificationAsync()
    }

    private fun endFallback(why: String) {
        val since: Long
        val fixes: Int
        synchronized(fallbackLock) {
            if (fallbackSinceNs == 0L) return
            since = fallbackSinceNs
            fixes = fallbackFixes
            fallbackSinceNs = 0L
            mock.reclaimNetwork()
        }
        event("fallback", "end %s after %.1f s fixes=%d".format(Locale.ROOT, why, (SystemClock.elapsedRealtimeNanos() - since) / 1e9, fixes))
        Live.mock.update { it.copy(fallbackSinceNs = 0, fallbackLastNs = 0) }
        updateNotificationAsync()
    }

    /** A network fix the fallback released that went into the inertial filter instead of straight to apps. */
    private fun noteFallbackFix(loc: Location) {
        synchronized(fallbackLock) {
            if (fallbackSinceNs == 0L) return
            fallbackFixes++
        }
        Live.mock.update { it.copy(fallbackLastNs = SystemClock.elapsedRealtimeNanos(), fallbackAccuracyM = loc.accuracy) }
    }

    /** [counted]: already counted by [noteFallbackFix] (the filter had it first). */
    private fun pushFallback(loc: Location, counted: Boolean = false) {
        synchronized(fallbackLock) {
            if (fallbackSinceNs == 0L || !mockEnabled) return
            if (!counted) fallbackFixes++
        }
        val pushed = mock.pushLocation(loc, "network-fallback", before = delivery::pushed)
        googleFused.push(pushed.location)
        val now = SystemClock.elapsedRealtimeNanos()
        submit(Record(t_mono_ns = now, t_wall_ms = System.currentTimeMillis(), mock_output = pushed.record))
        trails.add("served", TrailPoint(now, loc.latitude, loc.longitude, loc.accuracy, loc.speed.takeIf { loc.hasSpeed() }, loc.bearing.takeIf { loc.hasBearing() }))
        Live.mock.update { it.copy(fallbackLastNs = now, fallbackAccuracyM = loc.accuracy, lastSource = "network-fallback") }
        updateNotificationAsync()
    }

    /** From any thread: the notification is built on the main thread. */
    private fun updateNotificationAsync() {
        scope.launch(Dispatchers.Main) { updateNotification(force = true) }
    }

    /**
     * A command the user sent from a screen ([CommandBus], which runs it in its own scope, so it finishes even when the
     * screen is gone), on the IO dispatcher: sent with this service's [devices], logged into the running session as a dish
     * call (router replies redacted by [DishCallResult.toRecord]), and given to the restart tracker, so a restart it
     * causes reads "Restart sent from Star Nav" and not the dish's reason.
     */
    private suspend fun sendCommand(call: DeviceCall): DishCallResult {
        val result = withContext(Dispatchers.IO) {
            try {
                CommandBus.execute(devices, call)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DishCallResult.failure(call.kind, SystemClock.elapsedRealtimeNanos(), 0, "-", "client: ${e.message}")
            }
        }
        // The record and the tracker are what this handler is for: they are not skipped if the caller is cancelled now.
        withContext(NonCancellable) {
            val now = SystemClock.elapsedRealtimeNanos()
            submit(Record(t_mono_ns = now, t_wall_ms = System.currentTimeMillis(), dish = result.toRecord()))
            event("command", "${result.kind.label} ${result.outcome}")
            // Marked with the time the command went out: a dish that restarts may keep the connection waiting for seconds.
            if (result.kind.target == CallTarget.DISH) {
                val commandedNs = synchronized(trackerLock) {
                    reboots.onCall(result, result.sentMonoNs.coerceAtMost(now))
                    reboots.commandNs
                }
                // The Position page names a dish that goes quiet after this a restart.
                if (commandedNs != null) Live.reboot.update { it.copy(commandedNs = commandedNs) }
            }
        }
        return result
    }

    /** Called by the tracker when a cut-off starts, gets its code from a status, or ends. */
    private fun onCutoff(c: Cutoff) {
        val now = SystemClock.elapsedRealtimeNanos()
        val all = cutoffs.all()
        Live.cutoff.value = CutoffUi(
            active = cutoffs.active, last = cutoffs.finished.lastOrNull(), count = all.size,
            finishedS = cutoffs.finished.sumOf { it.durationS(now) },
        )
        val reason = disablementReason(this, c.code)
        val speeds = "speed=${kmh(c.speedBeforeMps)} max60s=${kmh(c.maxSpeed60sMps)}"
        when {
            // The speed after one already reported as ended: the screen above is all it changes.
            c.endNs != null && announcedCutoffStart != c.startNs -> return
            c.endNs != null -> {
                event("cutoff", "end after %.1f s code=%d".format(Locale.ROOT, c.durationS(now), c.code))
                alert(
                    getString(R.string.notif_back_title),
                    getString(R.string.notif_back_text, "%.0f".format(Locale.ROOT, c.durationS(now)), reason),
                    fresh = false, sticky = false,
                )
                endedCutoffStart = c.startNs
                announcedCutoffStart = null
                cutoffAlertContinues = null
            }
            announcedCutoffStart == c.startNs -> {
                event("cutoff", "code=${c.code} ${Disablement.name(c.code)}")
                cutoffAlert(reason, fresh = false)
            }
            endedCutoffStart == c.startNs -> {
                // Refused again within seconds of answering: the same cut-off, no new alert.
                event("cutoff", "resumed code=${c.code}")
                cutoffAlert(reason, fresh = false)
                announcedCutoffStart = c.startNs
            }
            else -> {
                event("cutoff", "start code=${c.code} ${Disablement.name(c.code)} $speeds")
                cutoffAlert(reason, fresh = true)
                announcedCutoffStart = c.startNs
            }
        }
        updateNotification(force = true)
    }

    /** Called by the tracker when the first status after a dish restart arrives. */
    private fun onReboot(r: DishReboot) {
        Live.reboot.update { it.copy(last = r, count = reboots.reboots.size) }
        event(
            "dish_reboot",
            "cause=${r.cause} reason=${RebootReason.name(r.reason)} boot_reason=${r.bootReason?.let(BootReason::name) ?: "-"} " +
                "offline=${r.offlineS?.let { "%.1fs".format(Locale.ROOT, it) } ?: "-"} uptime=${r.uptimeS}s " +
                "software=${r.softwareBefore ?: "-"}->${r.softwareAfter ?: "-"} bootcount=${r.bootcountBefore ?: "-"}->${r.bootcountAfter ?: "-"} " +
                "update_waiting=${r.updateWaiting}",
        )
        val cause = rebootCause(this, r)
        val text = r.offlineS?.let { getString(R.string.notif_reboot_text, cause, duration((it * 1000).toLong())) } ?: cause
        // Stays a while: whoever drives may only look at the phone later.
        alert(getString(R.string.notif_reboot_title), text, fresh = true, sticky = false, timeoutMs = 10 * 60_000L)
        updateNotification(force = true)
    }

    /** With the correction on and a position to carry on from, apps keep getting one (dead reckoning). */
    private fun cutoffContinues(): Boolean = inertialServing() && Live.inertial.value.let { it.running && it.state != InertialState.NO_OUTPUT }

    private fun cutoffAlert(reason: String, fresh: Boolean) {
        val continues = cutoffContinues()
        cutoffAlertContinues = continues
        val text = getString(if (continues) R.string.notif_cutoff_text_inertial else R.string.notif_cutoff_text, reason)
        alert(getString(R.string.notif_cutoff_title), text, fresh, sticky = true)
    }

    /** Publish loop: the cut-off alert says the position continues only while that is true (dead reckoning can stop). */
    private fun checkCutoffAlert() {
        val shown = cutoffAlertContinues ?: return
        val active = cutoffs.active ?: return
        if (announcedCutoffStart != active.startNs) return
        if (cutoffContinues() != shown) cutoffAlert(disablementReason(this, active.code), fresh = false)
    }

    /** One alert slot: a fresh alert pops up, an update only changes the text; a non-[sticky] one goes away by itself. */
    private fun alert(title: String, text: String, fresh: Boolean, sticky: Boolean, timeoutMs: Long = 20_000) {
        val nm = getSystemService(NotificationManager::class.java)
        if (fresh) nm.cancel(ALERT_ID)
        val b = NotificationCompat.Builder(this, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (!sticky) b.setTimeoutAfter(timeoutMs).setAutoCancel(true)
        nm.notify(ALERT_ID, b.build())
    }

    private suspend fun publishLoop() {
        var tick = 0
        while (scope.isActive) {
            delay(500)
            writer?.let { w -> Live.recorder.update { it.copy(bytes = w.bytes, records = w.written.get(), dropped = w.dropped.get()) } }
            val snapshot = trails.snapshot()
            Live.trails.value = snapshot
            publishTrust(SystemClock.elapsedRealtimeNanos(), snapshot)
            checkFallback()
            checkCutoffAlert()
            Live.phone.value = PhoneUi(
                lastGps = phoneState.lastGps, lastGpsAtMonoNs = phoneState.lastGpsAtMonoNs, lastNetwork = phoneState.lastNetwork,
                satsVisible = phoneState.satsVisible, satsUsed = phoneState.satsUsed, meanCn0Used = phoneState.meanCn0Used,
                agcDb = phoneState.agcDb, rates = rates.sample(SystemClock.elapsedRealtimeNanos()), gnssModel = phoneState.gnssModel,
                gnssAtMonoNs = phoneState.gnssAtMonoNs,
            )
            val s = settings()
            val c = clients.get(s, wifi.network.value)
            Live.dish.update { it.copy(route = DishClients.route(s, wifi.network.value), endpoint = c.endpoint) }
            // The status line ages every few seconds; only changed text reaches the notification.
            if (++tick % 4 == 0) withContext(Dispatchers.Main) { updateNotification() }
        }
    }

    /**
     * The GPS verdict and the position the Position page centres on / opens in Maps. What the mock serves counts as
     * the Starlink position only while the mock is on (then the phone's own GPS reads as the mock).
     */
    private fun publishTrust(nowNs: Long, snapshot: Map<String, List<TrailPoint>>) {
        val servedFix = if (mockEnabled) {
            snapshot["served"]?.lastOrNull()?.takeIf { (nowNs - it.tNs) / 1e9 <= TRUST_SERVED_FRESH_S }
                ?.let { TrustFix(it.lat, it.lon, it.accM?.toDouble() ?: 0.0, it.tNs) }
        } else {
            null
        }
        Live.trust.value = TrustUi(
            verdict = trust.verdict(nowNs), gps = trust.latestGps, auto = trust.latestAuto, comparison = trust.lastComparison,
            chosen = trust.choose(nowNs, servedFix), atNs = nowNs,
        )
    }

    private fun submit(record: Record) {
        writer?.submit(record)
    }

    private fun event(kind: String, detail: String = "") = submit(
        Record(t_mono_ns = SystemClock.elapsedRealtimeNanos(), t_wall_ms = System.currentTimeMillis(), event = AppEvent(kind = kind, detail = detail)),
    )

    /** One line about the position apps get right now. */
    private fun statusLine(): String {
        val now = SystemClock.elapsedRealtimeNanos()
        val m = Live.mock.value
        val parts = ArrayList<String>()
        val inertialLine = if (mockEnabled && m.lastPushMonoNs != 0L && inertialServing()) inertialStatus(now, m) else null
        when {
            inertialLine != null -> {
                cutoffs.active?.let { parts += getString(R.string.notif_line_cutoff, "%.0f".format(Locale.ROOT, it.durationS(now))) }
                parts += inertialLine
                val fresh = m.delivery.filterValues { now - it.atMonoNs <= 10_000_000_000L }
                if (fresh.isNotEmpty()) parts += getString(if (fresh.values.all { it.ok }) R.string.notif_line_apps_ok else R.string.notif_line_apps_bad)
            }
            mockEnabled && fallbackSinceNs != 0L -> parts += m.fallbackAccuracyM?.takeIf { m.fallbackLastNs != 0L }
                ?.let { getString(R.string.notif_line_fallback, "%.0f".format(Locale.ROOT, it)) }
                ?: getString(R.string.notif_line_fallback_waiting)
            cutoffs.active != null -> parts += getString(R.string.notif_line_cutoff, "%.0f".format(Locale.ROOT, cutoffs.active!!.durationS(now)))
            !mockEnabled -> parts += getString(R.string.notif_line_mock_off)
            m.lastPushMonoNs == 0L -> parts += getString(R.string.notif_line_waiting)
            else -> {
                val age = (now - m.lastPushMonoNs) / 1e9
                // A transport failure on the last status: the dish (or its Wi-Fi) is gone, e.g. restarting.
                val dishDown = Live.dish.value.last[DishCallKind.STATUS]?.result?.httpStatus == 0
                parts += if (age > 5) {
                    getString(if (dishDown) R.string.notif_line_dish_down else R.string.notif_line_no_fix, "%.0f".format(Locale.ROOT, age))
                } else {
                    getString(R.string.notif_line_fix, "%.1f".format(Locale.ROOT, age), "%.0f".format(Locale.ROOT, m.accuracyM ?: 0f))
                }
                val fresh = m.delivery.filterValues { now - it.atMonoNs <= 10_000_000_000L }
                if (fresh.isNotEmpty()) parts += getString(if (fresh.values.all { it.ok }) R.string.notif_line_apps_ok else R.string.notif_line_apps_bad)
            }
        }
        if (writer != null) parts += getString(R.string.notif_line_recording)
        if (watching && writer == null && !mockEnabled) parts += getString(R.string.notif_line_watching)
        return parts.joinToString(" · ")
    }

    /**
     * The filter's state for the notification: starting, corrected, dish held sideways, dead reckoning,
     * network-aided, stopped; null = the plain lines (a network fix went to apps as it came).
     */
    private fun inertialStatus(now: Long, m: MockUi): String? {
        val i = Live.inertial.value
        if (!i.running) return null
        val acc = i.accuracyM?.let { "%.0f".format(Locale.ROOT, it) } ?: "-"
        val sinceFix = "%.0f".format(Locale.ROOT, if (i.lastFixNs != 0L) (now - i.lastFixNs).coerceAtLeast(0L) / 1e9 else 0.0)
        return when (inertialLine(i, m, now)) {
            InertialLine.PLAIN -> null
            InertialLine.STARTING -> getString(R.string.notif_line_inertial_starting, "%.0f".format(Locale.ROOT, m.accuracyM ?: 0f))
            InertialLine.NO_MOUNT -> getString(R.string.notif_line_inertial_no_mount, acc)
            InertialLine.TRACKING -> getString(R.string.notif_line_inertial_tracking, acc)
            InertialLine.SIDEWAYS -> getString(R.string.notif_line_inertial_sideways, "%.0f".format(Locale.ROOT, kotlin.math.abs(i.sidewaysM ?: 0.0)), acc)
            InertialLine.DEAD_RECKONING -> getString(R.string.notif_line_inertial_dr, sinceFix, acc)
            InertialLine.NETWORK -> getString(R.string.notif_line_inertial_network, sinceFix, acc)
            InertialLine.STOPPED -> getString(R.string.notif_line_inertial_stopped, sinceFix)
        }
    }

    private fun updateNotification(force: Boolean = false) {
        if (!running) return
        val text = statusLine()
        if (!force && text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    /** The notification opens the app on its Position page. */
    private fun openApp() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_POSITION, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(code: Int, action: String, on: Boolean? = null): PendingIntent {
        val i = Intent(this, NavService::class.java).setAction(action)
        on?.let { i.putExtra(EXTRA_ON, it) }
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notification(text: String): Notification {
        val mode = settings().mockMode
        val title = if (mockEnabled) getString(R.string.notif_title_mock_on, getString(modeLabel(mode))) else getString(R.string.notif_title_mock_off)
        val builder = NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp())
            .addAction(0, getString(if (mockEnabled) R.string.notif_action_mock_off else R.string.notif_action_mock_on), serviceIntent(1, ACTION_MOCK, !mockEnabled))
        // With logging off there is nothing to start; a recording that is still closing can be stopped but not restarted.
        if (settings().loggingEnabled || writer != null) {
            builder.addAction(0, getString(if (writer != null) R.string.notif_action_record_stop else R.string.notif_action_record), serviceIntent(2, ACTION_RECORD, writer == null))
        }
        return builder
            .addAction(0, getString(R.string.notif_action_stop_all), serviceIntent(3, ACTION_STOP_ALL))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        val w = writer
        if (mockEnabled) {
            mock.disable()
            googleFused.disable()
            mockEnabled = false
            Live.mock.update { it.copy(enabled = false, providers = emptyList(), delivery = emptyMap()) }
        }
        if (running) shutdown()
        if (w != null) {
            writer = null
            runBlocking { withTimeoutOrNull(3_000) { w.close() } }
            Live.recorder.value = RecorderUi()
        }
        scope.cancel()
        fusedExecutor.shutdown()
        // A notify() that raced the end (a status update after stopForeground) must not stay behind as an "ongoing" card.
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    companion object {
        const val ACTION_RECORD = "record"
        const val ACTION_MOCK = "mock"
        const val ACTION_STOP_ALL = "stop_all"
        const val ACTION_WATCH = "watch"
        const val EXTRA_ON = "on"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_ID = 2
        /** Starlink-only fixes unchanged this long while the car moves count as frozen. */
        private const val FROZEN_AFTER_S = 10.0
        /** No dish position for this long starts the network fallback. */
        private const val FALLBACK_AFTER_NS = 5_000_000_000L
        private val TRAIL_KEYS = mapOf(
            DishCallKind.LOCATION_AUTO to "dish_auto", DishCallKind.LOCATION_GPS to "dish_gps", DishCallKind.LOCATION_STARLINK to "dish_starlink",
        )

        /** A pushed position older than this no longer counts as what apps get (Center, Open in Maps). */
        private const val TRUST_SERVED_FRESH_S = 10.0

        /** How long the mount setting must stay unchanged before the filter restarts with it. */
        private const val MOUNT_SETTLE_MS = 1500L

        private fun onOff(on: Boolean) = if (on) "on" else "off"

        private fun kmh(mps: Double?) = mps?.let { "%.0fkm/h".format(Locale.ROOT, it * 3.6) } ?: "-"

        fun modeLabel(mode: MockMode) = when (mode) {
            MockMode.AUTO -> R.string.mode_auto
            MockMode.RAW -> R.string.mode_raw
            MockMode.STARLINK_ONLY -> R.string.mode_starlink
        }

        /**
         * Switching on starts the foreground service (the app, a notification or the tile must be in
         * front); switching off talks to the running service. A service started as a foreground
         * service must reach startForeground, so an "off" is never sent that way.
         */
        fun record(ctx: Context, on: Boolean) {
            // Not even a start: a service started as a foreground service must reach startForeground, and with logging
            // off the service would refuse the recording before it got there.
            if (on && !App.instance.settings.value.loggingEnabled) return
            send(ctx, ACTION_RECORD, on)
        }

        fun mock(ctx: Context, on: Boolean) = send(ctx, ACTION_MOCK, on)

        /** The live map is on screen: keep the service up and poll every dish source. */
        fun watch(ctx: Context, on: Boolean) = send(ctx, ACTION_WATCH, on)

        private fun send(ctx: Context, action: String, on: Boolean) {
            val i = Intent(ctx, NavService::class.java).setAction(action).putExtra(EXTRA_ON, on)
            if (on) ContextCompat.startForegroundService(ctx, i) else if (Live.service.value.running) ctx.startService(i)
        }
    }
}
