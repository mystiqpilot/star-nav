package tools.apachi.starlinknav.setup

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.LocalLocation
import tools.apachi.starlinknav.core.dish.LocalLocationReading
import tools.apachi.starlinknav.core.dish.LocalLocationState
import tools.apachi.starlinknav.service.DishClients
import tools.apachi.starlinknav.service.FusedMock
import tools.apachi.starlinknav.service.Live
import tools.apachi.starlinknav.service.WifiTracker

enum class CheckState { OK, NEEDED, RECOMMENDED, UNKNOWN }

/** Everything the phone and dish need, in the order the Setup page shows it. */
enum class SetupItem(val required: Boolean) {
    LOCATION_PERMISSION(true),
    LOCATION_ON(true),
    DEVELOPER_OPTIONS(true),
    MOCK_APP(true),
    DISH_WIFI(true),
    DISH_LOCATION(true),
    GOOGLE_ACCURACY(false),
    NOTIFICATIONS(false),
    BATTERY(false),
    MOCK_DELIVERY(false),
}

data class SetupCheck(val item: SetupItem, val state: CheckState, val detail: Detail? = null)

/** Extra facts the page explains; the UI turns them into text. */
sealed interface Detail {
    data object PermissionDenied : Detail
    data object AlertsChannelOff : Detail
    data object BackgroundRestricted : Detail
    data object WifiButNoDish : Detail
    /** What the Setup row for the dish's local-network location says: the reading it was derived from. */
    data class DishLocation(val reading: LocalLocationReading, val reachable: Boolean) : Detail
    data class DeliveryFailed(val sources: List<String>) : Detail
    data object MockOff : Detail
}

/** What one round of dish calls found. */
data class DishFindings(
    val reachable: Boolean,
    /** The dish's "location on the local network" setting: [LocalLocationState] ON / OFF / answers anyway / unknown. */
    val location: LocalLocationReading,
    val atMonoNs: Long,
) {
    /** Whether the dish gives its position to this phone: null when it cannot be told (unreachable, disabled right now, plan). */
    val locationShared: Boolean?
        get() = when (location.state) {
            LocalLocationState.ON, LocalLocationState.ANSWERS_ANYWAY -> true
            LocalLocationState.OFF -> false
            LocalLocationState.UNKNOWN -> null
        }
}

object SetupChecks {
    /** Checks that read only the phone's state; cheap, run every couple of seconds. */
    fun phone(ctx: Context): Map<SetupItem, SetupCheck> {
        val out = LinkedHashMap<SetupItem, SetupCheck>()
        fun put(item: SetupItem, ok: Boolean, detail: Detail? = null) {
            out[item] = SetupCheck(item, if (ok) CheckState.OK else if (item.required) CheckState.NEEDED else CheckState.RECOMMENDED, detail)
        }
        val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        put(SetupItem.LOCATION_PERMISSION, fine)
        put(SetupItem.LOCATION_ON, locationEnabled(ctx))
        put(SetupItem.DEVELOPER_OPTIONS, Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1)
        put(SetupItem.MOCK_APP, isMockApp(ctx))
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val notifOk = (!notificationPermissionNeeded || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) && nm.areNotificationsEnabled()
        val alertsOn = nm.getNotificationChannel(App.CHANNEL_ALERTS)?.importance != NotificationManager.IMPORTANCE_NONE
        put(SetupItem.NOTIFICATIONS, notifOk && alertsOn, if (notifOk && !alertsOn) Detail.AlertsChannelOff else null)
        val pm = ctx.getSystemService(PowerManager::class.java)
        // "Restricted" background use is Android 9 (API 28); Android 8 has only the battery optimisation.
        val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        put(SetupItem.BATTERY, pm.isIgnoringBatteryOptimizations(ctx.packageName) && !restricted, if (restricted) Detail.BackgroundRestricted else null)
        return out
    }

    /**
     * Notifications need a runtime permission only from Android 13 (API 33). Before it they are allowed unless the user
     * switched them off for the app, which `areNotificationsEnabled` reports: there is nothing to request.
     */
    val notificationPermissionNeeded: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Whether the location master switch is on: `LocationManager.isLocationEnabled` is API 28, before it the setting says. */
    fun locationEnabled(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ctx.getSystemService(LocationManager::class.java).isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
        }

    fun isMockApp(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        // `unsafeCheckOpNoThrow` is API 29 (`checkOpNoThrow` was deprecated for it: it may be answered for a proxied uid).
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun onWifi(ctx: Context): Boolean = WifiTracker.currentWifi(ctx.getSystemService(ConnectivityManager::class.java)) != null

    /**
     * Asks the dish (status, AUTO location, and the config only when the status carries none), or reuses what the
     * recorder got in the last few seconds. The "location on the local network" setting is read from the status'
     * embedded config first, so it is known even where `dish_get_config` is refused, then from `dish_get_config`, then from
     * the official diagnostics' `location.enabled`; see [LocalLocation.read].
     */
    suspend fun dish(ctx: Context): DishFindings = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtimeNanos()
        val recent = Live.dish.value.last
        fun fresh(kind: DishCallKind) = recent[kind]?.takeIf { now - it.atMonoNs < 5_000_000_000L }?.result
        val running = Live.service.value.running
        val s = App.instance.settings.value
        val client by lazy {
            val net = WifiTracker.currentWifi(ctx.getSystemService(ConnectivityManager::class.java))
            DishClients.build(s, if (s.bindToWifi) net else null)
        }
        fun call(kind: DishCallKind): DishCallResult =
            (if (running) fresh(kind) else null) ?: runCatching { client.call(kind) }.getOrElse {
                DishCallResult.failure(kind, now, 0, "-", it.toString())
            }
        val status = call(DishCallKind.STATUS)
        if (status.httpStatus == 0) return@withContext DishFindings(false, LocalLocationReading.UNKNOWN, now)
        val auto = call(DishCallKind.LOCATION_AUTO)
        val statusConfig = status.takeIf { it.ok }?.response?.dish_get_status?.config
        // dish_get_config only when the status has no config: it is refused on some plans, the status config is not.
        val getConfig = if (statusConfig != null) null else {
            runCatching { client.call(DishCallKind.CONFIG) }.getOrNull()?.takeIf { it.ok }?.response?.dish_get_config?.dish_config
        }
        // The official diagnostics' location.enabled only when no config could be read at all (it is the third source).
        val diagnosticsEnabled = if (statusConfig != null || getConfig != null) null else {
            call(DishCallKind.DIAGNOSTICS).takeIf { it.ok }?.response?.dish_get_diagnostics?.location?.enabled
        }
        DishFindings(reachable = true, location = LocalLocation.read(statusConfig, getConfig, auto, diagnosticsEnabled), atMonoNs = now)
    }

    /**
     * Google Location Accuracy: Play services' answer in the play build; otherwise whether Android's
     * network location is on (not readable while the mock replaces that provider). Null = unknown.
     */
    suspend fun googleAccuracy(ctx: Context): Boolean? {
        FusedMock(ctx).googleAccuracyEnabled()?.let { return it }
        if (Live.mock.value.enabled) return null
        return runCatching { ctx.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrNull()
    }

    /** Combines phone checks, the last dish findings and the mock's delivery check into the page's list. */
    fun combine(ctx: Context, phone: Map<SetupItem, SetupCheck>, dish: DishFindings?, googleAccuracy: Boolean?): List<SetupCheck> = SetupItem.entries.map { item ->
        phone[item] ?: when (item) {
            // The network fallback needs Google's network location; without it, off is better.
            SetupItem.GOOGLE_ACCURACY -> when (googleAccuracy) {
                null -> SetupCheck(item, CheckState.UNKNOWN)
                App.instance.settings.value.networkFallback -> SetupCheck(item, CheckState.OK)
                else -> SetupCheck(item, CheckState.RECOMMENDED)
            }
            SetupItem.DISH_WIFI -> when {
                dish?.reachable == true -> SetupCheck(item, CheckState.OK)
                dish == null -> SetupCheck(item, CheckState.UNKNOWN)
                onWifi(ctx) -> SetupCheck(item, CheckState.NEEDED, Detail.WifiButNoDish)
                else -> SetupCheck(item, CheckState.NEEDED)
            }
            SetupItem.DISH_LOCATION -> when (dish?.locationShared) {
                true -> SetupCheck(item, CheckState.OK, Detail.DishLocation(dish.location, dish.reachable))
                false -> SetupCheck(item, CheckState.NEEDED, Detail.DishLocation(dish.location, dish.reachable))
                null -> SetupCheck(item, CheckState.UNKNOWN, dish?.let { Detail.DishLocation(it.location, it.reachable) })
            }
            SetupItem.MOCK_DELIVERY -> {
                val mock = Live.mock.value
                val now = SystemClock.elapsedRealtimeNanos()
                val fresh = mock.delivery.filterValues { now - it.atMonoNs <= 10_000_000_000L }
                when {
                    !mock.enabled -> SetupCheck(item, CheckState.UNKNOWN, Detail.MockOff)
                    fresh.isEmpty() -> SetupCheck(item, CheckState.UNKNOWN)
                    fresh.values.all { it.ok } -> SetupCheck(item, CheckState.OK)
                    else -> SetupCheck(item, CheckState.RECOMMENDED, Detail.DeliveryFailed(fresh.filterValues { !it.ok }.keys.toList()))
                }
            }
            else -> SetupCheck(item, CheckState.UNKNOWN)
        }
    }

    private fun granted(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /** Package of the official Starlink app (declared in the manifest's <queries>). */
    const val STARLINK_APP = "com.starlink.mobile"
}
