package tools.apachi.starlinknav.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.UnsupportedApiCallException
import com.google.android.gms.location.LocationListener
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.SettingsClient
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Play build: puts Google Play services' fused location provider into mock mode, so apps that
 * read it (Google Maps and most others) get only the dish's position instead of a blend with
 * Wi-Fi and cell positions. Needs this app selected as the mock location app.
 */
// Runs only inside the location foreground service, which cannot start without precise
// location; mock calls also need the mock app selection, and every call handles SecurityException.
@SuppressLint("MissingPermission")
class FusedMock(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val settings = LocationServices.getSettingsClient(context)
    private val appContext = context.applicationContext

    private val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)

    val available: Boolean = availability == ConnectionResult.SUCCESS

    var active = false
        private set

    /** Null on success, otherwise a message for the user. */
    suspend fun enable(): String? {
        // The code name says why (SERVICE_INVALID: the Play Store is not visible to the app; *_UPDATE_REQUIRED: too old).
        if (!available) return "Google Play services not available (${GoogleApiAvailability.getInstance().getErrorString(availability)})"
        return try {
            client.setMockMode(true).await()
            active = true
            null
        } catch (e: SecurityException) {
            "Google fused location refused mock mode: ${e.message}"
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    fun push(location: Location) {
        if (active) runCatching { client.setMockLocation(location) }
    }

    fun disable() {
        if (!active) return
        active = false
        runCatching { client.setMockMode(false) }
    }

    private var listener: LocationListener? = null

    /** Receives what Google's fused location hands to apps, once a second (also without the mock). */
    fun listen(executor: Executor, onLocation: (Location) -> Unit) {
        if (!available || listener != null) return
        val l = LocationListener { onLocation(it) }
        listener = l
        runCatching {
            client.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build(), executor, l)
        }.onFailure { listener = null }
    }

    fun stopListening() {
        listener?.let { runCatching { client.removeLocationUpdates(it) } }
        listener = null
    }

    /**
     * Google Location Accuracy (Wi-Fi and cell positioning). On the dish's moving Wi-Fi it can pin
     * Google's location to wherever that network was seen before. Null when unknown.
     */
    suspend fun googleAccuracyEnabled(): Boolean? {
        if (!available || AccuracyFeature.missing(appContext)) return null
        val read = AccuracyFeature.read(appContext, settings)
        return withTimeoutOrNull(3_000) { runCatching { read.await() }.getOrNull() }
    }

    /** What Google's fused location reports right now, or null. */
    suspend fun readBack(timeoutMs: Long = 4_000): Location? = withTimeoutOrNull(timeoutMs) {
        runCatching {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
        }.getOrNull()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { e -> cont.resumeWithException(e) }
        addOnCanceledListener { cont.cancel() }
    }

    companion object {
        const val IN_BUILD = true
    }
}

/**
 * Play services without the google_location_accuracy_enabled feature (20.45 on LineageOS 18.1 lacks it) post "Update
 * Google Play services" ("... won't run unless you update") once in every app process that asks, then hold the read and
 * retry it every 5 s for about two minutes before it fails with [UnsupportedApiCallException]. So one read is in flight at
 * a time (callers wait for the same one), and that failure is remembered for the Play services version: the read is not
 * tried again until they are updated, and Setup reads Android's network location instead.
 */
private object AccuracyFeature {
    private const val PREFS = "play_services"
    private const val KEY = "accuracyFeatureMissingForGms"

    private var inFlight: Task<Boolean>? = null

    @Synchronized
    fun read(ctx: Context, settings: SettingsClient): Task<Boolean> {
        inFlight?.takeIf { !it.isComplete }?.let { return it }
        return settings.isGoogleLocationAccuracyEnabled().also { task ->
            inFlight = task
            task.addOnFailureListener { e -> if (e is UnsupportedApiCallException) markMissing(ctx) }
        }
    }

    private fun gmsVersion(ctx: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo("com.google.android.gms", 0))
    }.getOrDefault(-1L)

    fun missing(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, Long.MIN_VALUE) == gmsVersion(ctx)

    fun markMissing(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY, gmsVersion(ctx)) }
}
