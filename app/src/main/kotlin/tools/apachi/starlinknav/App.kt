package tools.apachi.starlinknav

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import tools.apachi.starlinknav.account.StarlinkAccountManager
import tools.apachi.starlinknav.service.CapabilityStore
import tools.apachi.starlinknav.service.SessionStore
import tools.apachi.starlinknav.ui.applyToApp

class App : Application() {
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        CapabilityStore.init(this)
        // The system keeps the app's night mode, but ours is the source of truth.
        settings.value.themeMode.applyToApp(this)
        createChannels()
        // Retention and the storage cap apply even when no recording ever starts (logging switched off).
        SessionStore.pruneAsync(this)
        // Contacts nobody: only empties a WebView store a killed sign-in left behind (does nothing unless it was up).
        StarlinkAccountManager.wipeLeftovers(this)
    }

    // Creating a channel again only renames it, so the names follow a change of the app's language.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        // Default importance keeps the icon in the status bar (silent notifications hide it on
        // recent Pixels); without sound or vibration it never disturbs. Replaces "recorder" (LOW).
        nm.deleteNotificationChannel("recorder")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        // Heads-up but silent: it pops up over a navigation app while driving.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_ALERTS = "alerts"
        lateinit var instance: App
            private set
    }
}
