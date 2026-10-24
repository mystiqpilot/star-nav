package tools.apachi.starlinknav.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.account.AccountEnableButton
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCommand
import tools.apachi.starlinknav.core.dish.DishLinkState
import tools.apachi.starlinknav.core.dish.DishStates
import tools.apachi.starlinknav.core.dish.LocalLocation
import tools.apachi.starlinknav.core.dish.LocalLocationState
import tools.apachi.starlinknav.core.dish.LocationRequestMode
import tools.apachi.starlinknav.core.dish.SleepWindow
import tools.apachi.starlinknav.core.dish.SnowMeltMode
import tools.apachi.starlinknav.core.dish.canStow
import tools.apachi.starlinknav.core.dish.proto.DishConfig
import tools.apachi.starlinknav.core.dish.proto.DishGetStatusResponse
import tools.apachi.starlinknav.service.CapabilityStore
import tools.apachi.starlinknav.service.DeviceCall
import tools.apachi.starlinknav.service.DishFeed
import java.time.Instant
import java.time.ZoneId

/**
 * What the app can tell the dish to do. Each control says what it does and shows the dish's current setting; each
 * command is sent only after a confirmation that says what will happen (a destructive one needs a word typed), and
 * shows its last outcome in plain words, including a refusal ("Refused by the dish: permission denied. The Starlink
 * app, logged in, can change this."). A control the dish said it does not implement, or refuses this client, is
 * greyed out with that answer until "Forget what the dish refused" is pressed. Commands go through the command bus:
 * with the location service running they are logged into the session and a restart is tracked as one this app sent.
 */
@Composable
fun DishControls(feed: DishFeed) {
    val samples by feed.samples.collectAsStateWithLifecycle()
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    val statusSample = samples[DishCallKind.STATUS]
    val status = statusSample.okResponse()?.dish_get_status
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (status == null) FeedProblem(statusSample, address = settings.dishHost)
        Hint(stringResource(R.string.controls_intro))
        RebootCard()
        SleepCard(status?.config)
        SnowMeltCard(status?.config)
        UpdateWindowCard(status?.config)
        LocalLocationCard(status?.config)
        GnssCard(status)
        RfCard(status)
        StowCard(status)
        CheckUpdateCard()
        FactoryResetCard()
        ForgetCard()
    }
}

// ---- Reboot

@Composable
private fun RebootCard() {
    val sender = rememberSender()
    var confirm by remember { mutableStateOf(false) }
    ControlCard(
        title = stringResource(R.string.controls_reboot_title),
        description = stringResource(R.string.controls_reboot_text),
        kinds = listOf(DishCallKind.REBOOT),
        tag = DishCallKind.REBOOT.label,
    ) { enabled ->
        SecondaryButton(stringResource(R.string.controls_reboot_button), { confirm = true }, enabled = enabled && !sender.busy)
    }
    if (confirm) {
        val call = DeviceCall.of(DishCallKind.REBOOT)
        ConfirmDialog(
            title = stringResource(R.string.controls_reboot_title),
            text = stringResource(R.string.controls_reboot_confirm),
            confirmLabel = stringResource(R.string.controls_reboot_button),
            risk = call.risk,
            onConfirm = {
                confirm = false
                sender.send(call, DishCallKind.REBOOT.label)
            },
            onDismiss = { confirm = false },
        )
    }
}

// ---- Sleep schedule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepCard(config: DishConfig?) {
    val sender = rememberSender()
    val zone = remember { ZoneId.systemDefault() }
    val window = remember(config) { SleepWindow.of(config)?.takeIf { config?.power_save_duration_minutes != 0 } }
    // The editor takes over the dish's setting when it first sees one and whenever the setting itself changes (a write that
    // went through shows up in the status a moment later), and keeps the user's edits in between: the status polls that
    // still show the old setting do not pull the editor back.
    var startMinutes by rememberSaveable { mutableIntStateOf(22 * 60) }
    var hours by rememberSaveable { mutableStateOf("8") }
    var minutes by rememberSaveable { mutableStateOf("0") }
    var enabled by rememberSaveable { mutableStateOf(false) }
    var loadedFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(window, config != null) {
        if (config == null) return@LaunchedEffect
        val key = window.toString()
        if (key == loadedFrom) return@LaunchedEffect
        loadedFrom = key
        if (window != null) {
            val now = Instant.now()
            startMinutes = window.startLocalMinutes(zone, now)
            hours = (window.durationMinutes / 60).toString()
            minutes = (window.durationMinutes % 60).toString()
            enabled = window.enabled
        }
    }
    val duration = (hours.toIntOrNull() ?: -1) * 60 + (minutes.toIntOrNull() ?: -1)
    val valid = (hours.toIntOrNull() ?: -1) in 0..24 && (minutes.toIntOrNull() ?: -1) in 0..59 && duration in 1..SleepWindow.DAY
    ControlCard(
        title = stringResource(R.string.controls_sleep_title),
        description = stringResource(R.string.controls_sleep_text),
        kinds = listOf(DishCallKind.SLEEP_SCHEDULE),
        tag = DishCallKind.SLEEP_SCHEDULE.label,
    ) { allowed ->
        val now = Instant.now()
        KeyValueRow(
            stringResource(R.string.controls_now),
            when {
                config == null -> "-"
                window == null -> stringResource(R.string.controls_sleep_none)
                !window.enabled -> stringResource(R.string.controls_sleep_off)
                else -> stringResource(
                    R.string.controls_sleep_on, SleepWindow.clock(window.startLocalMinutes(zone, now)), SleepWindow.clock(window.endLocalMinutes(zone, now)),
                )
            },
        )
        Row(Modifier.heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.controls_sleep_enabled), Modifier.weight(1f))
            Switch(checked = enabled, enabled = allowed, onCheckedChange = { enabled = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.controls_sleep_start), Modifier.weight(1f))
            SecondaryButton(SleepWindow.clock(startMinutes), { picking = true }, enabled = allowed)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.controls_sleep_length), Modifier.weight(1f))
            NumberField(hours, { hours = it }, stringResource(R.string.controls_hours), allowed, Modifier.weight(0.6f))
            NumberField(minutes, { minutes = it }, stringResource(R.string.controls_minutes), allowed, Modifier.weight(0.6f))
        }
        if (!valid) StatusPill(Status.BAD, stringResource(R.string.controls_sleep_invalid))
        PrimaryButton(stringResource(R.string.controls_apply), { confirm = true }, enabled = allowed && valid && config != null && !sender.busy)
    }
    if (picking) {
        val state = rememberTimePickerState(initialHour = startMinutes / 60, initialMinute = startMinutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { LowButton(stringResource(R.string.controls_ok), { startMinutes = state.hour * 60 + state.minute; picking = false }) },
            dismissButton = { LowButton(stringResource(R.string.common_cancel), { picking = false }) },
            text = { TimePicker(state) },
        )
    }
    if (confirm && valid) {
        val now = Instant.now()
        // The next time the clock reads the start, converted with the offset in force then (daylight saving included).
        val next = SleepWindow.fromLocal(startMinutes, duration, enabled, zone, now)
        val call = DeviceCall.of(DishCommand.SleepSchedule(next))
        ConfirmDialog(
            title = stringResource(R.string.controls_sleep_title),
            text = if (enabled) {
                stringResource(
                    R.string.controls_sleep_confirm_on, SleepWindow.clock(startMinutes), SleepWindow.clock((startMinutes + duration) % SleepWindow.DAY),
                    zone.id, SleepWindow.clock(next.startUtcMinutes), duration.toString(),
                )
            } else {
                stringResource(R.string.controls_sleep_confirm_off)
            },
            confirmLabel = stringResource(R.string.controls_apply),
            risk = call.risk,
            onConfirm = {
                confirm = false
                sender.send(call, DishCallKind.SLEEP_SCHEDULE.label)
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 4 && v.all(Char::isDigit)) onChange(v) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

// ---- Snow melt

@Composable
private fun SnowMeltCard(config: DishConfig?) {
    val sender = rememberSender()
    var pick by rememberSaveable { mutableIntStateOf(-1) }
    var confirm by remember { mutableStateOf(false) }
    val current = config?.snow_melt_mode
    val modes = listOf(SnowMeltMode.AUTO to R.string.controls_snow_auto, SnowMeltMode.ALWAYS_ON to R.string.controls_snow_on, SnowMeltMode.ALWAYS_OFF to R.string.controls_snow_off)
    val chosen = if (pick >= 0) pick else current ?: SnowMeltMode.AUTO
    ControlCard(
        title = stringResource(R.string.controls_snow_title),
        description = stringResource(R.string.controls_snow_text),
        kinds = listOf(DishCallKind.DISH_SET_CONFIG),
        tag = TAG_SNOW,
    ) { allowed ->
        KeyValueRow(stringResource(R.string.controls_now), current?.let { c -> modes.firstOrNull { it.first == c }?.let { stringResource(it.second) } ?: SnowMeltMode.name(c) } ?: "-")
        // Without the dish's current setting there is nothing to change from: it is not shown and nothing is sent.
        val known = allowed && current != null
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = chosen == mode, enabled = known, onClick = { pick = mode },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(stringResource(label), maxLines = 1, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        PrimaryButton(stringResource(R.string.controls_apply), { confirm = true }, enabled = known && !sender.busy && chosen != current)
    }
    if (confirm && current != null && chosen != current) {
        val call = DeviceCall.of(DishCommand.SetConfig(snowMeltMode = chosen))
        ConfirmDialog(
            title = stringResource(R.string.controls_snow_title),
            text = stringResource(
                when (chosen) {
                    SnowMeltMode.ALWAYS_ON -> R.string.controls_snow_confirm_on
                    SnowMeltMode.ALWAYS_OFF -> R.string.controls_snow_confirm_off
                    else -> R.string.controls_snow_confirm_auto
                },
            ),
            confirmLabel = stringResource(R.string.controls_apply),
            risk = call.risk,
            onConfirm = {
                confirm = false
                sender.send(call, TAG_SNOW) { if (it.ok) pick = -1 }
            },
            onDismiss = { confirm = false },
        )
    }
}

private const val TAG_SNOW = "snow_melt"

// ---- Update window and deferral

@Composable
private fun UpdateWindowCard(config: DishConfig?) {
    val sender = rememberSender()
    var hour by rememberSaveable { mutableStateOf("") }
    var deferral by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val currentHour = config?.swupdate_reboot_hour
    val currentDeferral = config?.swupdate_three_day_deferral_enabled
    val wantedHour = hour.toIntOrNull()?.takeIf { it in 0..23 }
    val hourChanged = hour.isNotEmpty() && wantedHour != null && wantedHour != currentHour
    val deferralChanged = deferral != null && deferral != currentDeferral
    val hourInvalid = hour.isNotEmpty() && wantedHour == null
    val changed = (hourChanged || deferralChanged) && !hourInvalid
    ControlCard(
        title = stringResource(R.string.controls_update_title),
        description = stringResource(R.string.controls_update_text),
        kinds = listOf(DishCallKind.DISH_SET_CONFIG),
        tag = TAG_UPDATE_WINDOW,
    ) { allowed ->
        KeyValueRow(stringResource(R.string.controls_now), if (config == null) "-" else stringResource(R.string.controls_update_now, currentHour.toString(), stringResource(if (currentDeferral == true) R.string.common_yes else R.string.common_no)))
        // Without the dish's current setting it is not shown and nothing is sent.
        val known = allowed && config != null
        NumberField(hour, { hour = it }, stringResource(R.string.controls_update_hour, currentHour?.toString() ?: "-"), known, Modifier.fillMaxWidth())
        if (hourInvalid) StatusPill(Status.BAD, stringResource(R.string.controls_update_hour_invalid))
        Row(Modifier.heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.controls_update_deferral), Modifier.weight(1f))
            Switch(checked = deferral ?: currentDeferral ?: false, enabled = known, onCheckedChange = { deferral = it })
        }
        Hint(stringResource(R.string.controls_update_clock_hint))
        PrimaryButton(stringResource(R.string.controls_apply), { confirm = true }, enabled = known && !sender.busy && changed)
    }
    // The status keeps coming while the dialog is open: if it now shows what was asked for, there is nothing left to send.
    if (confirm && changed && config != null) {
        ConfirmDialog(
            title = stringResource(R.string.controls_update_title),
            text = stringResource(R.string.controls_update_confirm),
            confirmLabel = stringResource(R.string.controls_apply),
            risk = DishCommand.SetConfig(swupdateRebootHour = wantedHour.takeIf { hourChanged }, swupdateThreeDayDeferral = deferral.takeIf { deferralChanged }).risk,
            onConfirm = {
                confirm = false
                val newHour = wantedHour.takeIf { hourChanged }
                val newDeferral = deferral.takeIf { deferralChanged }
                if (newHour == null && newDeferral == null) return@ConfirmDialog
                val command = DishCommand.SetConfig(swupdateRebootHour = newHour, swupdateThreeDayDeferral = newDeferral)
                sender.send(DeviceCall.of(command), TAG_UPDATE_WINDOW) {
                    if (it.ok) {
                        hour = ""
                        deferral = null
                    }
                }
            },
            onDismiss = { confirm = false },
        )
    }
}

private const val TAG_UPDATE_WINDOW = "update_window"

// ---- Location on the local network

@Composable
private fun LocalLocationCard(config: DishConfig?) {
    val sender = rememberSender()
    var confirm by remember { mutableStateOf(false) }
    // The setting from the status' own config (read every second, there even where dish_get_config is refused); without it
    // what the Setup page last found (dish_get_config, or a position request the dish answered).
    val setup by SetupState.dish.collectAsStateWithLifecycle()
    val reading = remember(config, setup) {
        if (config != null) {
            val own = LocalLocation.read(config, null, null)
            val setupReading = setup?.location
            if (own.state == LocalLocationState.OFF && setupReading?.state == LocalLocationState.ANSWERS_ANYWAY) setupReading else own
        } else {
            setup?.location
        }
    }
    val allowedNow = reading?.state == LocalLocationState.ON
    ControlCard(
        title = stringResource(R.string.controls_location_title),
        description = stringResource(R.string.controls_location_text),
        // Only the call this button would send counts: a refused config write must not grey out "allow" (its own call).
        kinds = listOf(if (allowedNow) DishCallKind.DISH_SET_CONFIG else DishCallKind.ALLOW_LOCAL_LOCATION),
        // The remembered refusal and the last outcome say the honest thing for the enable command (may need the Starlink app once).
        unavailableSentence = { e ->
            if (allowedNow) outcomeSentence(e.outcome, Device.DISH) + outcomeDetail(e.outcome, e.message)
            else localLocationOutcomeSentence(DishCallKind.ALLOW_LOCAL_LOCATION, e.outcome) + outcomeDetail(e.outcome, e.message)
        },
    ) { enabled ->
        val (value, color) = localLocationValue(reading)
        KeyValueRow(stringResource(R.string.controls_now), value, valueColor = color)
        if (reading != null && !reading.isPlain) Hint(localLocationSentence(reading))
        // Switching it off needs a setting read from the dish; asking to switch it on does not (the dish may refuse; its answer is shown).
        PrimaryButton(stringResource(if (allowedNow) R.string.controls_location_block_button else R.string.controls_location_allow_button), { confirm = true }, enabled = enabled && !sender.busy && (!allowedNow || config != null))
        LocalLocationOutcomeLine(showRemembered = false)
        if (!allowedNow) {
            // Not a LAN command, so not greyed out by the dish's refusal: the account route is for exactly that case.
            val refused = rememberCapabilities().unavailable(DishCallKind.ALLOW_LOCAL_LOCATION) != null
            if (refused) Hint(stringResource(R.string.account_enable_hint_refused))
            AccountEnableButton(emphasized = refused)
        }
    }
    if (confirm) {
        val call = if (allowedNow) {
            DeviceCall.of(DishCommand.SetConfig(locationRequestMode = LocationRequestMode.NONE))
        } else {
            DeviceCall.of(DishCallKind.ALLOW_LOCAL_LOCATION)
        }
        ConfirmDialog(
            title = stringResource(R.string.controls_location_title),
            text = stringResource(if (allowedNow) R.string.controls_location_block_confirm else R.string.controls_location_allow_confirm),
            confirmLabel = stringResource(if (allowedNow) R.string.controls_location_block_button else R.string.controls_location_allow_button),
            risk = call.risk,
            onConfirm = {
                confirm = false
                sender.send(call, TAG_LOCAL_LOCATION)
            },
            onDismiss = { confirm = false },
        )
    }
}

// ---- GNSS inhibit

@Composable
private fun GnssCard(status: DishGetStatusResponse?) {
    val sender = rememberSender()
    var confirm by remember { mutableStateOf(false) }
    val inhibited = status?.gps_stats?.inhibit_gps
    ControlCard(
        title = stringResource(R.string.controls_gnss_title),
        description = stringResource(R.string.controls_gnss_text),
        kinds = listOf(if (inhibited == true) DishCallKind.ALLOW_GPS else DishCallKind.INHIBIT_GPS),
        tag = TAG_GNSS,
    ) { enabled ->
        KeyValueRow(
            stringResource(R.string.controls_now),
            inhibited?.let { stringResource(if (it) R.string.controls_gnss_off else R.string.controls_gnss_on) } ?: "-", status = if (inhibited == true) Status.WARN else null)
        PrimaryButton(stringResource(if (inhibited == true) R.string.controls_gnss_allow_button else R.string.controls_gnss_inhibit_button), { confirm = true }, enabled = enabled && !sender.busy && inhibited != null)
    }
    if (confirm) {
        val allow = inhibited == true
        val call = DeviceCall.of(if (allow) DishCallKind.ALLOW_GPS else DishCallKind.INHIBIT_GPS)
        ConfirmDialog(
            title = stringResource(R.string.controls_gnss_title),
            text = stringResource(if (allow) R.string.controls_gnss_allow_confirm else R.string.controls_gnss_inhibit_confirm),
            confirmLabel = stringResource(if (allow) R.string.controls_gnss_allow_button else R.string.controls_gnss_inhibit_button),
            risk = call.risk,
            onConfirm = {
                confirm = false
                sender.send(call, TAG_GNSS)
            },
            onDismiss = { confirm = false },
        )
    }
}

private const val TAG_GNSS = "inhibit_gps"

// ---- RF inhibit

@Composable
private fun RfCard(status: DishGetStatusResponse?) {
    val sender = rememberSender()
    var confirm by remember { mutableStateOf(false) }
    val off = status?.let { DishStates.of(it).state == DishLinkState.RF_INHIBITED }
    ControlCard(
        title = stringResource(R.string.controls_rf_title),
        description = stringResource(R.string.controls_rf_text),
        kinds = listOf(if (off == true) DishCallKind.ALLOW_RF else DishCallKind.INHIBIT_RF),
        tag = TAG_RF,
    ) { enabled ->
        KeyValueRow(
            stringResource(R.string.controls_now),
            off?.let { stringResource(if (it) R.string.controls_rf_off else R.string.controls_rf_on) } ?: "-", status = if (off == true) Status.WARN else null)
        PrimaryButton(stringResource(if (off == true) R.string.controls_rf_allow_button else R.string.controls_rf_inhibit_button), { confirm = true }, enabled = enabled && !sender.busy && off != null)
    }
    if (confirm) {
        val allow = off == true
        val call = DeviceCall.of(if (allow) DishCallKind.ALLOW_RF else DishCallKind.INHIBIT_RF)
        ConfirmDialog(
            title = stringResource(R.string.controls_rf_title),
            text = stringResource(if (allow) R.string.controls_rf_allow_confirm else R.string.controls_rf_inhibit_confirm),
            confirmLabel = stringResource(if (allow) R.string.controls_rf_allow_button else R.string.controls_rf_inhibit_button),
            risk = call.risk,
            onConfirm = {
                confirm = false
                sender.send(call, TAG_RF)
            },
            onDismiss = { confirm = false },
        )
    }
}

private const val TAG_RF = "inhibit_rf"

// ---- Stow / unstow: only a dish that says it has motors

@Composable
private fun StowCard(status: DishGetStatusResponse?) {
    // A Mini has no motors, and a dish that does not say is not offered a command that can only fail.
    if (status == null || !status.canStow()) {
        Section(stringResource(R.string.controls_stow_title)) { Hint(stringResource(R.string.controls_stow_none)) }
        return
    }
    val sender = rememberSender()
    var pending by remember { mutableStateOf<DishCallKind?>(null) }
    ControlCard(
        title = stringResource(R.string.controls_stow_title),
        description = stringResource(R.string.controls_stow_text),
        kinds = listOf(DishCallKind.STOW, DishCallKind.UNSTOW),
        tag = TAG_STOW,
    ) { enabled ->
        KeyValueRow(stringResource(R.string.controls_now), stringResource(if (status.stow_requested) R.string.controls_stow_stowed else R.string.controls_stow_unstowed))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SecondaryButton(stringResource(R.string.controls_stow_button), { pending = DishCallKind.STOW }, enabled = enabled && !sender.busy)
            SecondaryButton(stringResource(R.string.controls_unstow_button), { pending = DishCallKind.UNSTOW }, enabled = enabled && !sender.busy)
        }
    }
    pending?.let { kind ->
        val stow = kind == DishCallKind.STOW
        val call = DeviceCall.of(kind)
        ConfirmDialog(
            title = stringResource(R.string.controls_stow_title),
            text = stringResource(if (stow) R.string.controls_stow_confirm else R.string.controls_unstow_confirm),
            confirmLabel = stringResource(if (stow) R.string.controls_stow_button else R.string.controls_unstow_button),
            risk = call.risk,
            onConfirm = {
                pending = null
                sender.send(call, TAG_STOW)
            },
            onDismiss = { pending = null },
        )
    }
}

private const val TAG_STOW = "stow"

// ---- Check for update

@Composable
private fun CheckUpdateCard() {
    val sender = rememberSender()
    var confirm by remember { mutableStateOf(false) }
    ControlCard(
        title = stringResource(R.string.controls_check_title),
        description = stringResource(R.string.controls_check_text),
        kinds = listOf(DishCallKind.DISH_UPDATE),
        tag = DishCallKind.DISH_UPDATE.label,
    ) { enabled ->
        SecondaryButton(stringResource(R.string.controls_check_button), { confirm = true }, enabled = enabled && !sender.busy)
    }
    if (confirm) {
        val call = DeviceCall.of(DishCommand.Update())
        ConfirmDialog(
            title = stringResource(R.string.controls_check_title),
            text = stringResource(R.string.controls_check_confirm),
            confirmLabel = stringResource(R.string.controls_check_button),
            risk = call.risk,
            onConfirm = {
                confirm = false
                sender.send(call, DishCallKind.DISH_UPDATE.label)
            },
            onDismiss = { confirm = false },
        )
    }
}

// ---- Factory reset: typed confirmation

@Composable
private fun FactoryResetCard() {
    val sender = rememberSender()
    var confirm by remember { mutableStateOf(false) }
    ControlCard(
        title = stringResource(R.string.controls_reset_title),
        description = stringResource(R.string.controls_reset_text),
        kinds = listOf(DishCallKind.DISH_FACTORY_RESET),
        tag = DishCallKind.DISH_FACTORY_RESET.label,
    ) { enabled ->
        SecondaryButton(stringResource(R.string.controls_reset_button), { confirm = true }, enabled = enabled && !sender.busy, color = StarTheme.colors.bad)
    }
    if (confirm) {
        val call = DeviceCall.of(DishCommand.FactoryReset)
        ConfirmDialog(
            title = stringResource(R.string.controls_reset_title),
            text = stringResource(R.string.controls_reset_confirm),
            confirmLabel = stringResource(R.string.controls_reset_button),
            risk = call.risk,
            onConfirm = {
                confirm = false
                sender.send(call, DishCallKind.DISH_FACTORY_RESET.label)
            },
            onDismiss = { confirm = false },
        )
    }
}

// ---- Greyed-out controls can be tried again

@Composable
private fun ForgetCard() {
    val caps = rememberCapabilities()
    val anyGreyed = DishCallKind.COMMANDS.any { it.target == tools.apachi.starlinknav.core.dish.CallTarget.DISH && caps.unavailable(it) != null }
    if (!anyGreyed) return
    Section(stringResource(R.string.controls_forget_title)) {
        Hint(stringResource(R.string.controls_forget_text))
        SecondaryButton(stringResource(R.string.controls_forget_button), { CapabilityStore.forgetDish() })
    }
}
