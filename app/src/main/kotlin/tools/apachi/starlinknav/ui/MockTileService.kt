package tools.apachi.starlinknav.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.service.Live
import tools.apachi.starlinknav.service.NavService

/**
 * Quick Settings tile next to Wi-Fi and Bluetooth: switches the mock GPS on and off. While the
 * service runs it toggles directly; otherwise it opens the app, which starts the location
 * service from the foreground (Android may refuse to start one from a tile).
 */
class MockTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val on = !Live.mock.value.enabled
        val permitted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (Live.service.value.running && permitted) {
            NavService.mock(this, on)
            qsTile?.let {
                it.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                it.updateTile()
            }
            return
        }
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_START_MOCK, on && permitted)
            .putExtra(MainActivity.EXTRA_OPEN_POSITION, true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The PendingIntent form is Android 14; before it the Intent form is the only one. Lint's own check
            // (StartActivityAndCollapseDeprecated, "throws from Android 14 when targeting 34+") ignores this version
            // check, so it is the one suppression here: the branch never runs on Android 14.
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(open)
        } else {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val m = Live.mock.value
        tile.state = if (m.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        // A tile subtitle is API 29; Android 8 and 9 show the label only (the tile's state says on / off).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (m.enabled) getString(NavService.modeLabel(m.mode)) else getString(R.string.tile_off)
        }
        tile.updateTile()
    }
}
