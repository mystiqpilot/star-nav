package tools.apachi.starlinknav.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.BuildConfig
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.ThemeMode
import tools.apachi.starlinknav.service.SessionStore

/** App settings: look, logging (the master switch for session logs), motion sensors, and About. */
@Composable
fun SettingsScreen() {
    val settings = App.instance.settings
    val s by settings.flow.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Section(stringResource(R.string.config_theme_title)) {
            SegmentedRow(ThemeMode.entries, s.themeMode, { m ->
                settings.update { it.copy(themeMode = m) }
                // Android recreates the activity when the night mode changes.
                m.applyToApp(ctx)
            }) { stringResource(themeLabel(it)) }
            // The system's dark theme is Android 10: before it "System" can only be the light look.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && s.themeMode == ThemeMode.SYSTEM) {
                Hint(stringResource(R.string.config_theme_system_light_hint))
            }
        }
        Section(stringResource(R.string.config_logging_title)) {
            SwitchSetting(stringResource(R.string.config_logging_switch), s.loggingEnabled) { v ->
                settings.update { it.copy(loggingEnabled = v) }
                // Sessions already on the phone still age out by the retention and the cap with logging off.
                if (!v) SessionStore.pruneAsync(ctx)
            }
            Hint(stringResource(if (s.loggingEnabled) R.string.config_logging_on_hint else R.string.config_logging_off_hint))
            // Retention and the cap apply to the sessions on the phone whether or not logging is on (they are enforced when
            // the app starts, when Sessions opens, when logging is switched off and when a recording starts).
            NumberSetting(stringResource(R.string.config_retention_days), s.retentionDays.toString(), { it.toIntOrNull() in 1..365 }) { v ->
                settings.update { it.copy(retentionDays = v.toInt()) }
            }
            NumberSetting(stringResource(R.string.config_storage_cap), s.maxStorageMb.toString(), { it.toIntOrNull() in 100..100_000 }) { v ->
                settings.update { it.copy(maxStorageMb = v.toInt()) }
            }
            Hint(stringResource(R.string.config_recording_hint))
        }
        Section(stringResource(R.string.config_imu_title)) {
            NumberSetting(stringResource(R.string.config_imu_rate), s.imuRateHz.toString(), { it.toIntOrNull() in 10..200 }) { v ->
                settings.update { it.copy(imuRateHz = v.toInt()) }
            }
            Hint(stringResource(R.string.config_imu_hint))
        }
        Section(stringResource(R.string.about_title)) {
            Text(
                stringResource(R.string.config_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium.mono(),
                fontWeight = FontWeight.SemiBold,
            )
            KeyValueRow(stringResource(R.string.about_license), stringResource(R.string.about_license_value), mono = false)
            Hint(stringResource(R.string.about_source))
            Text(stringResource(R.string.about_privacy_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Space.xs))
            Hint(stringResource(R.string.about_privacy))
            val sourceUrl = stringResource(R.string.about_source_url)
            val privacyUrl = stringResource(R.string.about_privacy_url)
            if (sourceUrl.isNotBlank() || privacyUrl.isNotBlank()) {
                // Opened in the browser only when tapped; the app itself sends nothing.
                ActionRow {
                    if (privacyUrl.isNotBlank()) SecondaryButton(stringResource(R.string.about_privacy_link), { openLink(ctx, privacyUrl) })
                    if (sourceUrl.isNotBlank()) SecondaryButton(stringResource(R.string.about_source_link), { openLink(ctx, sourceUrl) })
                }
            }
            Hint(stringResource(R.string.about_disclaimer))
        }
    }
}

private fun themeLabel(m: ThemeMode) = when (m) {
    ThemeMode.SYSTEM -> R.string.config_theme_system
    ThemeMode.LIGHT -> R.string.config_theme_light
    ThemeMode.DARK -> R.string.config_theme_dark
}

private fun openLink(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: nothing to open.
    }
}
