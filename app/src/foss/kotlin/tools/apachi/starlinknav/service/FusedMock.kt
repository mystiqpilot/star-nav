package tools.apachi.starlinknav.service

import android.content.Context
import android.location.Location
import java.util.concurrent.Executor

/**
 * FOSS build: no Google Play services. Google's fused location (used by Google Maps) is not
 * put into mock mode here; it follows the mocked GPS provider while that is the best source.
 */
@Suppress("UNUSED_PARAMETER")
class FusedMock(context: Context) {
    val available = false
    val active = false

    suspend fun enable(): String? = null

    fun push(location: Location) = Unit

    fun disable() = Unit

    fun listen(executor: Executor, onLocation: (Location) -> Unit) = Unit

    fun stopListening() = Unit

    suspend fun readBack(timeoutMs: Long = 4_000): Location? = null

    /** Unknown without Play services; Setup falls back to whether Android's network location is on. */
    suspend fun googleAccuracyEnabled(): Boolean? = null

    companion object {
        const val IN_BUILD = false
    }
}
