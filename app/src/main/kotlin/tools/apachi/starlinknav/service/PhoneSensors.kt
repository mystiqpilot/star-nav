package tools.apachi.starlinknav.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import tools.apachi.starlinknav.core.log.proto.AppEvent
import tools.apachi.starlinknav.core.log.proto.ImuBatch
import tools.apachi.starlinknav.core.log.proto.ImuSample
import tools.apachi.starlinknav.core.log.proto.Record

/**
 * Phone IMU/baro at fixed rates. One registration serves both users: while recording ([submit] set) every sensor
 * is written as 100 ms batches; [tap] gets each accelerometer and gyroscope event as delivered (the inertial
 * filter, which must see them per event, not per batch). Without a recording only those two are registered.
 * Uncalibrated variants keep bias estimates (the calibrated ones where a phone has none).
 */
class PhoneSensors(
    context: Context,
    /** The session writer; null = not recording (accelerometer and gyroscope only, nothing written). */
    private val submit: ((Record) -> Unit)?,
    private val rates: RateMeter,
    /** (sensor type, event timestamp on elapsedRealtimeNanos, values) for every accelerometer and gyroscope event. */
    private val tap: ((Int, Long, List<Float>) -> Unit)? = null,
) : SensorEventListener {
    val recording: Boolean get() = submit != null
    val tapping: Boolean get() = tap != null
    /** Set by [stop]: the last flush must not post itself again onto the quitting thread. */
    @Volatile private var stopped = false
    private val sm = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("imu").apply { start() }
    private val handler = Handler(thread.looper)
    private val buffer = ArrayList<ImuSample>(128)
    private val registered = mutableListOf<Sensor>()

    private val flush = object : Runnable {
        override fun run() {
            val submit = submit ?: return
            if (buffer.isNotEmpty()) {
                submit(
                    Record(
                        t_mono_ns = SystemClock.elapsedRealtimeNanos(),
                        t_wall_ms = System.currentTimeMillis(),
                        imu = ImuBatch(buffer.toList()),
                    ),
                )
                buffer.clear()
            }
            if (!stopped) handler.postDelayed(this, 100)
        }
    }

    fun start(imuRateHz: Int) {
        val fast = 1_000_000 / imuRateHz.coerceIn(10, 200)
        val imu = listOf(
            listOf(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER) to fast,
            listOf(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_GYROSCOPE) to fast,
        )
        val wanted = if (submit == null) imu else imu + listOf(
            listOf(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.TYPE_MAGNETIC_FIELD) to maxOf(fast, 20_000),
            listOf(Sensor.TYPE_PRESSURE) to 100_000,
            listOf(Sensor.TYPE_GAME_ROTATION_VECTOR) to 20_000,
            listOf(Sensor.TYPE_ROTATION_VECTOR) to 100_000,
        )
        val meta = JSONArray()
        for ((types, periodUs) in wanted) {
            val sensor = types.firstNotNullOfOrNull { sm.getDefaultSensor(it) } ?: continue
            // The inertial filter drops a sample delivered after a dish call later than its 0.1 s tick, so the
            // accelerometer and gyroscope are not batched in the sensor hub when it reads them.
            val latencyUs = if (tap != null && sensor.type in IMU_TYPES) 0 else 50_000
            if (sm.registerListener(this, sensor, periodUs, latencyUs, handler)) {
                registered += sensor
                meta.put(
                    JSONObject().put("type", sensor.type).put("name", sensor.name).put("vendor", sensor.vendor)
                        .put("periodUs", periodUs).put("resolution", sensor.resolution.toDouble())
                        .put("maxRange", sensor.maximumRange.toDouble()).put("fifoMax", sensor.fifoMaxEventCount)
                        .put("wakeUp", sensor.isWakeUpSensor),
                )
            }
        }
        submit?.invoke(
            Record(
                t_mono_ns = SystemClock.elapsedRealtimeNanos(),
                t_wall_ms = System.currentTimeMillis(),
                event = AppEvent(kind = "sensors", detail = meta.toString()),
            ),
        )
        if (submit != null) handler.postDelayed(flush, 100)
    }

    override fun onSensorChanged(e: SensorEvent) {
        // The framework reuses e.values after this returns: copied once, for both users.
        val v = e.values.toList()
        val type = e.sensor.type
        if (submit != null) buffer += ImuSample(sensor_type = type, t_ns = e.timestamp, v = v, accuracy = e.accuracy)
        if (type in IMU_TYPES) tap?.invoke(type, e.timestamp, v)
        rates.inc("sensor_$type")
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    fun stop() {
        sm.unregisterListener(this)
        stopped = true
        handler.removeCallbacks(flush)
        // The last batch, on the sensor thread (it owns the buffer); quitSafely still runs it.
        handler.post(flush)
        thread.quitSafely()
    }

    companion object {
        private val IMU_TYPES = setOf(
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_GYROSCOPE,
        )

        fun label(type: Int): String = when (type) {
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "accel(u)"
            Sensor.TYPE_ACCELEROMETER -> "accel"
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "gyro(u)"
            Sensor.TYPE_GYROSCOPE -> "gyro"
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "mag(u)"
            Sensor.TYPE_MAGNETIC_FIELD -> "mag"
            Sensor.TYPE_PRESSURE -> "baro"
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "game_rv"
            Sensor.TYPE_ROTATION_VECTOR -> "rv"
            else -> "type$type"
        }
    }
}
