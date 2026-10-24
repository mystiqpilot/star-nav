package tools.apachi.starlinknav.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.MockMode
import tools.apachi.starlinknav.MountMode
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.SettingsSnapshot
import tools.apachi.starlinknav.service.InertialUi
import tools.apachi.starlinknav.service.Live
import tools.apachi.starlinknav.service.inertialServesApps

/**
 * The Mock GPS page: what the mock is, how to enable it (the checklist), its settings (what it serves, the inertial
 * correction, the network fallback, the mount offset), what apps receive and how the correction is doing.
 */
@Composable
fun MockScreen() {
    val mock by Live.mock.collectAsStateWithLifecycle()
    val inertial by Live.inertial.collectAsStateWithLifecycle()
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    val now = rememberMonoNow()
    val inertialServing = inertialServesApps(inertial, mock, settings)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Section(stringResource(R.string.mockp_what_title)) {
            Text(stringResource(R.string.mockp_what, stringResource(R.string.app_name)), style = MaterialTheme.typography.bodyMedium)
            Hint(stringResource(R.string.mockp_why))
        }
        MockHeroCard(details = false)
        SetupChecklist()
        MockSettingsCard(settings)
        MountCard(settings, inertial)
        MockOutputCard(mock, now)
        InertialCard(inertial, settings, inertialServing, now)
    }
}

/** What the mock serves (Auto, Raw, Starlink only), the inertial correction and the network fallback. */
@Composable
private fun MockSettingsCard(settings: SettingsSnapshot) {
    Section(stringResource(R.string.home_mode_title)) {
        ModeSelector(settings.mockMode) { m -> App.instance.settings.update { it.copy(mockMode = m) } }
        val auto = settings.mockMode == MockMode.AUTO
        SwitchSetting(stringResource(R.string.home_inertial), settings.inertialCorrection) { v ->
            App.instance.settings.update { it.copy(inertialCorrection = v) }
        }
        // Outside Auto both switches do nothing: the line under the fallback switch says so once.
        if (auto) Hint(stringResource(if (settings.inertialCorrection) R.string.home_inertial_hint else R.string.home_inertial_off_hint))
        SwitchSetting(stringResource(R.string.home_fallback), settings.networkFallback) { v ->
            App.instance.settings.update { it.copy(networkFallback = v) }
        }
        Hint(
            stringResource(
                when {
                    !auto -> R.string.home_fallback_auto_only
                    settings.inertialCorrection -> R.string.home_fallback_hint_inertial
                    else -> R.string.home_fallback_hint
                },
            ),
        )
    }
}

/** The mount offset (dish yaw against the car): learned while driving, or entered. */
@Composable
private fun MountCard(s: SettingsSnapshot, inertial: InertialUi) {
    val settings = App.instance.settings
    Section(stringResource(R.string.config_mount_title)) {
        SegmentedRow(
            options = MountMode.entries,
            selected = s.mountMode,
            onSelect = { m ->
                settings.update {
                    // Manual without a value starts from the learned one (a default 0° would be ~177° off).
                    val prefill = m == MountMode.MANUAL && it.mountManual == null && it.mountLearned != null
                    it.copy(mountMode = m, mountManualDeg = if (prefill) roundedDeg(it.mountLearned!!) else it.mountManualDeg)
                }
            },
        ) { m -> stringResource(if (m == MountMode.AUTO) R.string.config_mount_auto else R.string.config_mount_manual) }
        val learned = s.mountLearned
        KeyValueRow(
            stringResource(R.string.config_mount_learned),
            learned?.let { stringResource(R.string.config_mount_learned_value, signedDeg(it), learnedWhen(s.mountLearnedAtMs)) }
                ?: stringResource(R.string.config_mount_not_learned),
        )
        if (s.mountMode == MountMode.MANUAL) {
            // Empty = no value: the filter then starts without one, as in Learned mode before anything was learned.
            NumberSetting(
                stringResource(R.string.config_yaw_offset), s.mountManual?.let { s.mountManualDeg.toString() } ?: "",
                { it.isBlank() || it.toFloatOrNull()?.let { d -> d in -180f..360f } == true },
            ) { v ->
                settings.update { it.copy(mountManualDeg = v.toFloatOrNull() ?: Float.NaN) }
            }
            if (learned != null) {
                ActionRow { LowButton(stringResource(R.string.config_mount_use_learned), onClick = { settings.update { it.copy(mountManualDeg = roundedDeg(learned)) } }) }
            }
        }
        if (inertial.running) KeyValueRow(stringResource(R.string.config_mount_in_use), mountText(inertial))
        Hint(stringResource(R.string.config_mount_hint))
    }
}

/** Auto (recommended), Raw, Starlink only: radio rows with what each serves, each a [Space.touch]-tall target. */
@Composable
fun ModeSelector(selected: MockMode, onSelect: (MockMode) -> Unit) {
    Column(Modifier.selectableGroup()) {
        for (mode in MockMode.entries) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Space.touch)
                    .selectable(selected = mode == selected, role = Role.RadioButton, onClick = { onSelect(mode) })
                    .padding(vertical = Space.xs),
                verticalAlignment = Alignment.Top,
            ) {
                // The radio sits on the title's line, not in the middle of the description under it.
                RadioButton(selected = mode == selected, onClick = null, modifier = Modifier.padding(top = Space.hair, end = Space.s))
                Column {
                    Text(stringResource(modeTitle(mode)), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                    Hint(stringResource(modeDescription(mode)))
                }
            }
        }
    }
}

private fun modeTitle(mode: MockMode) = when (mode) {
    MockMode.AUTO -> R.string.mode_auto_title
    MockMode.RAW -> R.string.mode_raw_title
    MockMode.STARLINK_ONLY -> R.string.mode_starlink_title
}

private fun modeDescription(mode: MockMode) = when (mode) {
    MockMode.AUTO -> R.string.mode_auto_description
    MockMode.RAW -> R.string.mode_raw_description
    MockMode.STARLINK_ONLY -> R.string.mode_starlink_description
}
