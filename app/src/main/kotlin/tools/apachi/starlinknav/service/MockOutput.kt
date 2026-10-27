package tools.apachi.starlinknav.service

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.DishFix
import tools.apachi.starlinknav.core.dish.Geo
import tools.apachi.starlinknav.core.log.proto.MockOutput
import tools.apachi.starlinknav.core.nav.FixExtrapolator
import tools.apachi.starlinknav.core.nav.InertialEstimate
import tools.apachi.starlinknav.core.nav.InertialState

/**
 * Replaces Android's location providers with test providers fed with the dish position:
 * "gps" always, and "network" and "fused" where the platform allows, so no Wi-Fi/cell position
 * from Android's own providers can pull apps away (on drive 4 the network provider was up to
 * 9 km off). Requires the app to be chosen in Developer options > Select mock location app.
 * Google Play services' fused location is handled by [FusedMock] in the play build.
 */
/** Called from the dish poller, the location listeners and the service, hence synchronized. */
class MockGpsOutput(private val context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val mocked = ArrayList<String>()

    var active = false
        private set

    /** The providers replaced right now. */
    val providers: List<String> get() = mocked.toList()

    /**
     * Providers that could not be replaced, with the reason ("fused": none on this Android, or the platform refused). Only
     * the extras can end up here: they never stop the GPS mock. Valid after [enable].
     */
    val unavailable: Map<String, String> get() = skipped.toMap()
    private val skipped = LinkedHashMap<String, String>()

    /** Returns null on success, otherwise a message for the user. GPS must succeed; the rest are best effort. */
    @Synchronized
    fun enable(): String? {
        // What a lost mock left behind goes first, so no provider is listed (and released or reclaimed) twice.
        clear()
        skipped.clear()
        try {
            add(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
            return context.getString(R.string.mock_not_allowed, context.getString(R.string.app_name))
        } catch (e: Exception) {
            return e.toString()
        }
        for (p in listOf(LocationManager.NETWORK_PROVIDER, Compat.FUSED_PROVIDER)) {
            // Android has a "fused" provider of its own only from Android 12 (API 31); on Android 10 / 11 there is nothing to
            // replace and a test provider of that name would be a new, empty provider apps could pick by accident.
            if (p == Compat.FUSED_PROVIDER && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && p !in lm.allProviders) {
                skipped[p] = "no such provider on Android ${Build.VERSION.RELEASE}"
                continue
            }
            runCatching { add(p) }.onFailure { skipped[p] = it.toString() }
        }
        active = true
        return null
    }

    @Synchronized
    private fun add(provider: String) {
        runCatching { lm.removeTestProvider(provider) }
        addTestProvider(provider)
        try {
            lm.setTestProviderEnabled(provider, true)
        } catch (e: Exception) {
            // Added but not usable: do not leave a half-made provider behind.
            runCatching { lm.removeTestProvider(provider) }
            throw e
        }
        if (provider !in mocked) mocked += provider
    }

    /** A provider that reports altitude, speed and bearing, at the best accuracy and power class. */
    private fun addTestProvider(provider: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val props = ProviderProperties.Builder()
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .setPowerUsage(ProviderProperties.POWER_USAGE_HIGH)
                .build()
            lm.addTestProvider(provider, props)
        } else {
            // Android 10 / 11: the ten-argument form (requiresNetwork, requiresSatellite, requiresCell, hasMonetaryCost,
            // supportsAltitude, supportsSpeed, supportsBearing, powerRequirement, accuracy).
            // (ProviderProperties' constants have the same values as Criteria.POWER_HIGH and Criteria.ACCURACY_FINE; lint
            // wants them for these parameters.)
            @Suppress("DEPRECATION")
            lm.addTestProvider(provider, false, false, false, false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE)
        }
    }

    /**
     * Gives Android's network provider back (cell towers and Wi-Fi via the phone's location
     * service), so its own fixes can be read and passed on while the dish gives no position.
     * False when it was not replaced.
     */
    @Synchronized
    fun releaseNetwork(): Boolean {
        if (LocationManager.NETWORK_PROVIDER !in mocked) return false
        runCatching { lm.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false) }
        runCatching { lm.removeTestProvider(LocationManager.NETWORK_PROVIDER) }
        mocked -= LocationManager.NETWORK_PROVIDER
        return true
    }

    /** Replaces the network provider again once the dish is back. */
    @Synchronized
    fun reclaimNetwork() {
        if (active && LocationManager.NETWORK_PROVIDER !in mocked) runCatching { add(LocationManager.NETWORK_PROVIDER) }
    }

    /**
     * Gives every replaced provider back. Also after a push found the mock gone ([active] already false): the list is
     * forgotten then too, or switching the mock on again would replace each provider a second time.
     */
    @Synchronized
    fun disable() = clear()

    private fun clear() {
        active = false
        for (p in mocked) {
            runCatching { lm.setTestProviderEnabled(p, false) }
            runCatching { lm.removeTestProvider(p) }
        }
        mocked.clear()
    }

    class Pushed(val record: MockOutput, val location: Location)

    /**
     * Pushes one fix as [served] (the fix moved forward by its age, see FixExtrapolator) to every
     * replaced provider; returns the record to log (with an error when the GPS push failed) and
     * the location, for Google's fused provider. [before] sees the location before any provider
     * gets it: providers deliver to listeners on other threads at once, so the delivery check
     * must know the push first.
     */
    @Synchronized
    fun push(fix: DishFix, served: FixExtrapolator.Served, source: String, before: (Location) -> Unit = {}): Pushed {
        val accuracy = fix.sigmaM.coerceAtLeast(1.0).toFloat()
        val bearing = served.courseDeg?.toFloat()
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = served.lat
            longitude = served.lon
            altitude = fix.alt
            this.accuracy = accuracy
            verticalAccuracyMeters = accuracy * 1.5f
            // the diagnostics' position standing in for a refused get_location has no speed until a second one came (NaN)
            fix.horizontalSpeedMps.takeIf { it.isFinite() }?.let {
                speed = it.toFloat()
                speedAccuracyMetersPerSecond = 0.5f
            }
            bearing?.let {
                this.bearing = it
                bearingAccuracyDegrees = 10f
            }
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        before(loc)
        val error = send(loc)
        val record = MockOutput(
            provider = mocked.joinToString("+"), lat = served.lat, lon = served.lon, alt = fix.alt, h_acc = accuracy,
            speed = fix.horizontalSpeedMps.takeIf { it.isFinite() }?.toFloat(), bearing = bearing, source = source, error = error,
            fix_lat = fix.lat, fix_lon = fix.lon, shift_m = served.shiftM.toFloat(), age_s = served.ageS.toFloat(),
        )
        return Pushed(record, loc)
    }

    /**
     * Pushes what the inertial filter serves (Auto with the correction on): its position, accuracy, speed and
     * heading. [fix] is the AUTO answer the serve is based on (null while dead reckoning); [alt] the last dish
     * altitude (the filter is horizontal only).
     */
    @Synchronized
    fun pushEstimate(est: InertialEstimate, fix: DishFix?, alt: Double?, source: String, before: (Location) -> Unit = {}): Pushed {
        val accuracy = est.accuracyM.coerceAtLeast(1.0).toFloat()
        val speed = (est.speedMps ?: fix?.horizontalSpeedMps)?.takeIf { it.isFinite() }?.coerceAtLeast(0.0)?.toFloat()
        val bearing = est.bearingDeg?.toFloat()
        val dr = est.state == InertialState.DEAD_RECKONING || est.state == InertialState.NETWORK_AIDED
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = est.lat
            longitude = est.lon
            alt?.let {
                altitude = it
                verticalAccuracyMeters = maxOf(accuracy * 1.5f, 3f)
            }
            this.accuracy = accuracy
            speed?.let {
                this.speed = it
                speedAccuracyMetersPerSecond = if (dr) 2f else 0.5f
            }
            bearing?.let {
                this.bearing = it
                bearingAccuracyDegrees = if (dr) 10f else 5f
            }
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        before(loc)
        val error = send(loc)
        val record = MockOutput(
            provider = mocked.joinToString("+"), lat = est.lat, lon = est.lon, alt = alt, h_acc = accuracy,
            speed = speed, bearing = bearing, source = source, error = error,
            fix_lat = fix?.lat, fix_lon = fix?.lon, shift_m = fix?.let { Geo.distanceM(it.lat, it.lon, est.lat, est.lon).toFloat() },
        )
        return Pushed(record, loc)
    }

    /**
     * Pushes a fix from another source, as it came (the network fallback: Android's own cell/Wi-Fi
     * position while the dish gives none), to every replaced provider.
     */
    @Synchronized
    fun pushLocation(src: Location, source: String, before: (Location) -> Unit = {}): Pushed {
        val loc = Location(src).apply {
            provider = LocationManager.GPS_PROVIDER
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        before(loc)
        val error = send(loc)
        val record = MockOutput(
            provider = mocked.joinToString("+"), lat = loc.latitude, lon = loc.longitude, alt = loc.altitude.takeIf { loc.hasAltitude() },
            h_acc = loc.accuracy, speed = loc.speed.takeIf { loc.hasSpeed() }, bearing = loc.bearing.takeIf { loc.hasBearing() },
            source = source, error = error,
        )
        return Pushed(record, loc)
    }

    /** Hands [loc] to every replaced provider; returns an error for the log, empty when the GPS push worked. */
    @Synchronized
    private fun send(loc: Location): String {
        var error = ""
        for (p in mocked.toList()) {
            try {
                lm.setTestProviderLocation(p, Location(loc).apply { provider = p })
            } catch (e: SecurityException) {
                active = false
                error = context.getString(R.string.mock_permission_revoked, e.message)
                break
            } catch (e: IllegalArgumentException) {
                if (p == LocationManager.GPS_PROVIDER) error = e.message.orEmpty()
            }
        }
        return error
    }
}

/**
 * Checks that what apps receive is the pushed position: every location a provider hands to
 * this app is compared with the push nearest in time. Fed from the poller and the location
 * listeners' threads.
 */
class MockDelivery {
    private val pushes = ArrayDeque<Location>()

    @Synchronized
    fun pushed(loc: Location) {
        pushes.addLast(loc)
        while (pushes.size > 60) pushes.removeFirst()
    }

    @Synchronized
    fun clear() = pushes.clear()

    /** Null when nothing was pushed around that time. */
    @Synchronized
    fun check(received: Location): DeliveryUi? {
        val t = received.elapsedRealtimeNanos
        val near = pushes.minByOrNull { kotlin.math.abs(it.elapsedRealtimeNanos - t) }
            ?.takeIf { kotlin.math.abs(it.elapsedRealtimeNanos - t) <= 3_000_000_000L } ?: return null
        val offset = Geo.distanceM(near.latitude, near.longitude, received.latitude, received.longitude)
        return DeliveryUi(ok = received.isMockFix && offset <= OK_M, offsetM = offset, isMock = received.isMockFix, atMonoNs = SystemClock.elapsedRealtimeNanos())
    }

    companion object {
        /** Fused providers may smooth a little; anything beyond this is not our position. */
        const val OK_M = 10.0
    }
}
