package tools.apachi.starlinknav.service

import android.location.Location
import android.location.LocationListener
import android.os.Build
import android.os.Bundle

/**
 * Differences between Android versions that the rest of the app should not repeat (minSdk is 29, Android 10).
 * Version-specific calls elsewhere sit behind an `SDK_INT` check of their own with the older call next to it;
 * what is used in more than one place lives here.
 */
object Compat {
    /** `LocationManager.FUSED_PROVIDER` is API 31; the string is the same on every version that has such a provider. */
    const val FUSED_PROVIDER = "fused"

    fun atLeast(sdk: Int): Boolean = Build.VERSION.SDK_INT >= sdk
}

/**
 * True when Android marks this fix as coming from a mock provider: `isMock` (API 31) replaced
 * `isFromMockProvider`, which is all Android 10 has. The one place that asks, so the GPS-trust feed, the delivery check
 * and the recording all agree.
 */
val Location.isMockFix: Boolean
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider

/**
 * A [LocationListener] that only wants the fixes. Android 10's interface has three more abstract methods
 * (`onStatusChanged`, `onProviderEnabled`, `onProviderDisabled`; they became default methods in Android 11), and a Kotlin
 * lambda or an object that leaves them out dies with AbstractMethodError the first time Android 10 calls one, e.g. when
 * the app's own mock enables or removes a provider. Always make listeners with this.
 */
fun locationListener(onFix: (Location) -> Unit): LocationListener = object : LocationListener {
    override fun onLocationChanged(location: Location) = onFix(location)

    @Deprecated("Android 10 still calls it; never called from Android 11.")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit
}
