package tools.apachi.starlinknav.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssNavigationMessage
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import tools.apachi.starlinknav.core.log.proto.GnssAgc
import tools.apachi.starlinknav.core.log.proto.GnssClockData
import tools.apachi.starlinknav.core.log.proto.GnssMeas
import tools.apachi.starlinknav.core.log.proto.GnssMeasurementBatch
import tools.apachi.starlinknav.core.log.proto.GnssSat
import tools.apachi.starlinknav.core.log.proto.GnssStatusSnapshot
import tools.apachi.starlinknav.core.log.proto.NmeaSentence
import tools.apachi.starlinknav.core.log.proto.PhoneLocation
import tools.apachi.starlinknav.core.log.proto.Record
import tools.apachi.starlinknav.core.log.proto.NavMessage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Mutable latest-values holder; the service turns it into [PhoneUi] twice a second. */
class PhoneState {
    @Volatile var lastGps: PhoneLocation? = null
    @Volatile var lastGpsAtMonoNs = 0L
    @Volatile var lastNetwork: PhoneLocation? = null
    @Volatile var satsVisible = 0
    @Volatile var satsUsed = 0
    @Volatile var meanCn0Used: Double? = null
    @Volatile var agcDb: List<Double> = emptyList()
    @Volatile var gnssModel: String? = null
    /** elapsedRealtimeNanos of the last satellite status or measurement batch; 0 = none. */
    @Volatile var gnssAtMonoNs = 0L
}

/**
 * Records everything the phone's GNSS stack exposes. Raw measurements, satellite status and
 * NMEA come from the chipset directly; whether they keep flowing while this app mocks the
 * "gps" provider is device-specific, which is why their rates are shown live.
 *
 * Three levels: locations only (the mock's delivery check), plus [setLive] (the Position page is open: satellite
 * counts, signal level and AGC for [PhoneState], nothing written, no full tracking), and `full` (a recording).
 */
@SuppressLint("MissingPermission")
class GnssCollector(
    context: Context,
    private val submit: (Record) -> Unit,
    private val rates: RateMeter,
    private val state: PhoneState,
    /** Every location a provider hands to this app, by source: "gps", "network", "fused". */
    private val onLocation: (String, Location) -> Unit = { _, _ -> },
) {
    private val lm = context.getSystemService(LocationManager::class.java)
    /**
     * The callbacks (locations, satellite status, measurements, NMEA) run on this thread. A looper and not a plain
     * executor because Android 10 (API 29) only has the `Handler` / `Looper` forms of the registrations; the executor
     * forms (API 30, API 31 for locations) post to the same thread.
     */
    private val thread = HandlerThread("gnss").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }

    /**
     * Registrations that can block on the chipset run here, never on [executor] (a stalled call would hold every location
     * back behind it) and never on the service's main thread (an ANR).
     */
    private val registrar = Executors.newSingleThreadExecutor { r -> Thread(r, "gnss-reg").apply { isDaemon = true } }

    /** Everything is written to the session (the `full` start); else the callbacks only update [state]. */
    @Volatile private var recording = false
    private var live = false

    private fun record(build: (Record) -> Record) =
        submit(build(Record(t_mono_ns = SystemClock.elapsedRealtimeNanos(), t_wall_ms = System.currentTimeMillis())))

    private val gpsListener = locationListener { loc ->
        val p = loc.toProto()
        rates.inc(if (loc.isMockFix) "gps_fix_mock" else "gps_fix")
        onLocation("gps", loc)
        if (!loc.isMockFix) {
            state.lastGps = p
            state.lastGpsAtMonoNs = SystemClock.elapsedRealtimeNanos()
        }
        record { it.copy(phone_location = p) }
    }

    // Android's own fused provider, as apps that use LocationManager see it; with the mock on
    // it shows whether the dish position reaches them.
    private val fusedListener = locationListener { loc ->
        rates.inc("fused_fix")
        onLocation("fused", loc)
        record { it.copy(phone_location = loc.toProto()) }
    }

    private val networkListener = locationListener { loc ->
        val p = loc.toProto()
        rates.inc("network_fix")
        onLocation("network", loc)
        state.lastNetwork = p
        record { it.copy(phone_location = p) }
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            rates.inc("gnss_status")
            state.gnssAtMonoNs = SystemClock.elapsedRealtimeNanos()
            if (!recording) {
                var used = 0
                var cn0 = 0.0
                for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) {
                    used++
                    cn0 += status.getCn0DbHz(i)
                }
                state.satsVisible = status.satelliteCount
                state.satsUsed = used
                state.meanCn0Used = if (used > 0) cn0 / used else null
                return
            }
            val sats = (0 until status.satelliteCount).map { i ->
                GnssSat(
                    constellation = status.getConstellationType(i),
                    svid = status.getSvid(i),
                    cn0 = status.getCn0DbHz(i),
                    elevation = status.getElevationDegrees(i),
                    azimuth = status.getAzimuthDegrees(i),
                    used = status.usedInFix(i),
                    almanac = status.hasAlmanacData(i),
                    ephemeris = status.hasEphemerisData(i),
                    carrier_hz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else null,
                    // Baseband C/N0 is API 30; empty on Android 10.
                    baseband_cn0 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && status.hasBasebandCn0DbHz(i)) status.getBasebandCn0DbHz(i).toDouble() else null,
                )
            }
            state.satsVisible = sats.size
            val used = sats.filter { it.used }
            state.satsUsed = used.size
            state.meanCn0Used = used.takeIf { it.isNotEmpty() }?.map { it.cn0.toDouble() }?.average()
            record { it.copy(gnss_status = GnssStatusSnapshot(sats)) }
        }

        override fun onStarted() = event("gnss_started")
        override fun onStopped() = event("gnss_stopped")
        override fun onFirstFix(ttffMillis: Int) = event("gnss_first_fix", "ttff_ms=$ttffMillis")
    }

    private val measurementCallback = object : GnssMeasurementsEvent.Callback() {
        @Suppress("DEPRECATION")
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            rates.inc("gnss_meas")
            state.gnssAtMonoNs = SystemClock.elapsedRealtimeNanos()
            if (!recording) {
                // The event-wide AGC list is API 33; below it the per-measurement level is all there is.
                val eventAgc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) event.gnssAutomaticGainControls.map { it.levelDb } else emptyList()
                state.agcDb = eventAgc.ifEmpty {
                    event.measurements.mapNotNull { m -> if (m.hasAutomaticGainControlLevelDb()) m.automaticGainControlLevelDb else null }.distinct()
                }
                return
            }
            val c = event.clock
            val api29 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val clock = GnssClockData(
                time_ns = c.timeNanos,
                full_bias_ns = if (c.hasFullBiasNanos()) c.fullBiasNanos else null,
                bias_ns = if (c.hasBiasNanos()) c.biasNanos else null,
                bias_unc_ns = if (c.hasBiasUncertaintyNanos()) c.biasUncertaintyNanos else null,
                drift_nsps = if (c.hasDriftNanosPerSecond()) c.driftNanosPerSecond else null,
                drift_unc_nsps = if (c.hasDriftUncertaintyNanosPerSecond()) c.driftUncertaintyNanosPerSecond else null,
                hw_discontinuity_count = c.hardwareClockDiscontinuityCount,
                leap_second = if (c.hasLeapSecond()) c.leapSecond else null,
                // The clock's elapsed-realtime fields are API 29: empty on Android 8 and 9.
                elapsed_realtime_ns = if (api29 && c.hasElapsedRealtimeNanos()) c.elapsedRealtimeNanos else null,
                elapsed_realtime_unc_ns = if (api29 && c.hasElapsedRealtimeUncertaintyNanos()) c.elapsedRealtimeUncertaintyNanos else null,
                time_unc_ns = if (c.hasTimeUncertaintyNanos()) c.timeUncertaintyNanos else null,
            )
            val api30 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val meas = event.measurements.map { m ->
                GnssMeas(
                    svid = m.svid,
                    constellation = m.constellationType,
                    time_offset_ns = m.timeOffsetNanos,
                    state = m.state,
                    received_sv_time_ns = m.receivedSvTimeNanos,
                    received_sv_time_unc_ns = m.receivedSvTimeUncertaintyNanos,
                    cn0 = m.cn0DbHz,
                    pseudorange_rate_mps = m.pseudorangeRateMetersPerSecond,
                    pseudorange_rate_unc_mps = m.pseudorangeRateUncertaintyMetersPerSecond,
                    adr_state = m.accumulatedDeltaRangeState,
                    adr_m = m.accumulatedDeltaRangeMeters,
                    adr_unc_m = m.accumulatedDeltaRangeUncertaintyMeters,
                    carrier_hz = if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz else null,
                    multipath = m.multipathIndicator,
                    snr_db = if (m.hasSnrInDb()) m.snrInDb else null,
                    agc_db = if (m.hasAutomaticGainControlLevelDb()) m.automaticGainControlLevelDb else null,
                    // Baseband C/N0 and the inter-signal biases are API 30: empty on Android 10.
                    baseband_cn0 = if (api30 && m.hasBasebandCn0DbHz()) m.basebandCn0DbHz else null,
                    full_isb_ns = if (api30 && m.hasFullInterSignalBiasNanos()) m.fullInterSignalBiasNanos else null,
                    satellite_isb_ns = if (api30 && m.hasSatelliteInterSignalBiasNanos()) m.satelliteInterSignalBiasNanos else null,
                    // The code type (signal name, e.g. "C", "L5I") is API 29: empty on Android 8 and 9.
                    code_type = if (api29 && m.hasCodeType()) m.codeType else null,
                )
            }
            val agc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                event.gnssAutomaticGainControls.map {
                    GnssAgc(level_db = it.levelDb, constellation = it.constellationType, carrier_hz = it.carrierFrequencyHz)
                }
            } else {
                emptyList()
            }
            state.agcDb = agc.map { it.level_db }.ifEmpty { meas.mapNotNull { it.agc_db }.distinct() }
            record {
                it.copy(gnss_measurements = GnssMeasurementBatch(clock = clock, meas = meas, agc = agc, full_tracking = fullTracking(event)))
            }
        }
    }

    private val navCallback = object : GnssNavigationMessage.Callback() {
        override fun onGnssNavigationMessageReceived(event: GnssNavigationMessage) {
            rates.inc("nav_msg")
            record {
                it.copy(
                    nav_message = NavMessage(
                        type = event.type, svid = event.svid, message_id = event.messageId,
                        submessage_id = event.submessageId, data_ = event.data.toByteString(), status = event.status,
                    ),
                )
            }
        }
    }

    private val nmeaListener = OnNmeaMessageListener { message, timestamp ->
        rates.inc("nmea")
        record { it.copy(nmea = NmeaSentence(timestamp_ms = timestamp, sentence = message)) }
    }

    private fun event(kind: String, detail: String = "") =
        record { it.copy(event = tools.apachi.starlinknav.core.log.proto.AppEvent(kind = kind, detail = detail)) }


    /**
     * [full]: everything (recording). Otherwise only the location listeners the mock's delivery
     * check needs, which keeps the GNSS chip's raw measurements and NMEA off.
     */
    fun start(full: Boolean = true) {
        recording = full
        if (!full) {
            listenForLocations()
            return
        }
        state.gnssModel = hardwareModel()
        event("gnss_capabilities", capabilities().toString())
        listenForLocations()
        // The chipset-side registrations can block (see [setLive]): not on the caller's thread.
        runOnGnssThread {
            register("gnss_status_failed") { registerStatus() }
            register("gnss_measurements_failed") { registerMeasurements(fullTracking = true) }
            register("nmea_failed") { registerNmea() }
            register("nav_messages_failed") { registerNavigation() }
        }
    }

    /** The GNSS chipset's model name (`LocationManager.getGnssHardwareModelName` is API 28); null on Android 8. */
    private fun hardwareModel(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.gnssHardwareModelName else null

    /**
     * What this phone's GNSS stack can do, as far as this Android version can say: the capability object is API 30,
     * its measurement / navigation message / antenna answers API 31, the rest API 34. A key that is missing is not
     * "no", it is "this Android cannot tell". Full tracking (API 31) is only requested from Android 12, and only
     * Android 14 reports it per batch (`GnssMeasurementBatch.full_tracking` is false below that).
     */
    private fun capabilities(): JSONObject {
        val o = JSONObject()
            .put("api", Build.VERSION.SDK_INT)
            .put("fullTrackingRequested", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            .put("fullTrackingReported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        // The chipset's model name and year are API 28: absent on Android 8.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            o.put("model", lm.gnssHardwareModelName).put("year", lm.gnssYearOfHardware)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val caps = lm.gnssCapabilities
            o.put("measurements", caps.hasMeasurements())
                .put("navigationMessages", caps.hasNavigationMessages())
                .put("antennaInfo", caps.hasAntennaInfo())
                .put("accumulatedDeltaRange", caps.hasAccumulatedDeltaRange())
                .put("measurementCorrections", caps.hasMeasurementCorrections())
                .put("satellitePvt", caps.hasSatellitePvt())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val caps = lm.gnssCapabilities
            o.put("measurements", caps.hasMeasurements())
                .put("navigationMessages", caps.hasNavigationMessages())
                .put("antennaInfo", caps.hasAntennaInfo())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            o.put("antennaInfo", lm.gnssCapabilities.hasGnssAntennaInfo())
        } else {
            o.put("capabilities", "not available before Android 11")
        }
        return o
    }

    /**
     * The Position page is open (or closed again): keep the satellite counts, signal level and AGC current without
     * writing anything. Raw measurements are asked for without full tracking, so the chip may still duty-cycle. A
     * recording has them already.
     */
    fun setLive(on: Boolean) {
        if (recording || on == live) return
        live = on
        // Off the caller's thread (the service's main thread): the GNSS measurement (un)registration is a binder call that
        // waits for the chipset's HAL and has stalled for minutes on the emulator (an ANR in NavService.onStartCommand).
        // The registrar runs the requests in order, so on / off / on stays consistent.
        runOnGnssThread {
            if (on) {
                runCatching { registerStatus() }
                runCatching { registerMeasurements(fullTracking = false) }
            } else {
                runCatching { lm.unregisterGnssStatusCallback(statusCallback) }
                runCatching { lm.unregisterGnssMeasurementsCallback(measurementCallback) }
            }
        }
    }

    /** Runs [block] on the registrar thread; dropped once [stop] has shut it down. */
    private fun runOnGnssThread(block: () -> Unit) {
        try {
            registrar.execute(block)
        } catch (_: RejectedExecutionException) {
            // Stopped: its callbacks are already gone.
        }
    }

    /** One registration of a recording: a throw or a refusal (false: the chipset or this Android has no such feed) is logged. */
    private inline fun register(failedEvent: String, call: () -> Boolean) {
        runCatching { call() }
            .onSuccess { if (!it) event(failedEvent, "registration refused") }
            .onFailure { event(failedEvent, it.toString()) }
    }

    // The Handler forms exist from Android 7 and are what Android 10 has; the executor forms (API 30) are used where
    // there is one, so Android 11 and later run exactly the calls they always did.
    private fun registerStatus(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lm.registerGnssStatusCallback(executor, statusCallback)
        else lm.registerGnssStatusCallback(statusCallback, handler)

    /**
     * Android 12 (API 31) can ask for full tracking (no duty cycling) per request; before it the one callback form
     * gives whatever the chipset does by default, so a recording on Android 10 / 11 may have gaps in its raw measurements.
     */
    private fun registerMeasurements(fullTracking: Boolean): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lm.registerGnssMeasurementsCallback(GnssMeasurementRequest.Builder().setFullTracking(fullTracking).build(), executor, measurementCallback)
        } else {
            lm.registerGnssMeasurementsCallback(measurementCallback, handler)
        }

    private fun registerNmea(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lm.addNmeaListener(executor, nmeaListener)
        else lm.addNmeaListener(nmeaListener, handler)

    private fun registerNavigation(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lm.registerGnssNavigationMessageCallback(executor, navCallback)
        else lm.registerGnssNavigationMessageCallback(navCallback, handler)

    /** One location listener per provider; one fix a second at the best accuracy the provider has. */
    private fun requestUpdates(provider: String, listener: LocationListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val request = LocationRequest.Builder(1_000L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
            lm.requestLocationUpdates(provider, request, executor, listener)
        } else {
            lm.requestLocationUpdates(provider, 1_000L, 0f, listener, handler.looper)
        }
    }

    private fun listenForLocations() {
        runCatching { requestUpdates(LocationManager.GPS_PROVIDER, gpsListener) }
            .onFailure { event("gps_updates_failed", it.toString()) }
        val providers = lm.allProviders
        if (LocationManager.NETWORK_PROVIDER in providers) {
            runCatching { requestUpdates(LocationManager.NETWORK_PROVIDER, networkListener) }
                .onFailure { event("network_updates_failed", it.toString()) }
        }
        // Android's own "fused" provider exists only from Android 12 (and where a vendor adds one): listen only if there is one.
        if (Compat.FUSED_PROVIDER in providers) {
            runCatching { requestUpdates(Compat.FUSED_PROVIDER, fusedListener) }
                .onFailure { event("fused_updates_failed", it.toString()) }
        }
    }

    /**
     * Unregisters everything on the registrar thread (the calls can block on the chipset, see [setLive]) and then shuts
     * both threads down: requests already queued, such as a late [setLive], run before it.
     */
    fun stop() {
        runOnGnssThread {
            runCatching { lm.removeUpdates(gpsListener) }
            runCatching { lm.removeUpdates(networkListener) }
            runCatching { lm.removeUpdates(fusedListener) }
            runCatching { lm.unregisterGnssStatusCallback(statusCallback) }
            runCatching { lm.unregisterGnssMeasurementsCallback(measurementCallback) }
            runCatching { lm.removeNmeaListener(nmeaListener) }
            runCatching { lm.unregisterGnssNavigationMessageCallback(navCallback) }
        }
        registrar.shutdown()
        thread.quitSafely()
    }
}

/** Whether a measurement batch was delivered with full tracking: the flag is API 34; below it the answer is "not known" = false. */
private fun fullTracking(event: GnssMeasurementsEvent): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && event.isFullTracking

fun Location.toProto(): PhoneLocation {
    // The mean-sea-level altitude is API 34: empty below it.
    val msl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    return PhoneLocation(
        provider = provider.orEmpty(),
        lat = latitude,
        lon = longitude,
        alt = if (hasAltitude()) altitude else null,
        h_acc = if (hasAccuracy()) accuracy else null,
        v_acc = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
        speed = if (hasSpeed()) speed else null,
        speed_acc = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
        bearing = if (hasBearing()) bearing else null,
        bearing_acc = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
        time_ms = time,
        elapsed_ns = elapsedRealtimeNanos,
        is_mock = isMockFix,
        msl_alt = if (msl && hasMslAltitude()) mslAltitudeMeters else null,
        msl_acc = if (msl && hasMslAltitudeAccuracy()) mslAltitudeAccuracyMeters else null,
        // The fix's elapsed-realtime uncertainty is API 29: empty on Android 8 and 9.
        elapsed_unc_ns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasElapsedRealtimeUncertaintyNanos()) elapsedRealtimeUncertaintyNanos else null,
    )
}
