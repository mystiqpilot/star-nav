package tools.apachi.starlinknav.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.account.AccountEnableButton
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.LocalLocationState
import tools.apachi.starlinknav.core.nav.LocationRefusal
import tools.apachi.starlinknav.service.CapabilityStore
import tools.apachi.starlinknav.service.DeviceCall
import tools.apachi.starlinknav.service.FusedMock
import tools.apachi.starlinknav.service.Live
import tools.apachi.starlinknav.setup.CheckState
import tools.apachi.starlinknav.setup.Detail
import tools.apachi.starlinknav.setup.DishFindings
import tools.apachi.starlinknav.setup.SetupCheck
import tools.apachi.starlinknav.setup.SetupChecks
import tools.apachi.starlinknav.setup.SetupItem

/** Latest findings, refreshed by the activity while it is in front; the page and the badge read them. */
object SetupState {
    val phone = MutableStateFlow<Map<SetupItem, SetupCheck>>(emptyMap())
    val dish = MutableStateFlow<DishFindings?>(null)
    val googleAccuracy = MutableStateFlow<Boolean?>(null)

    fun checks(ctx: Context): List<SetupCheck> = SetupChecks.combine(ctx, phone.value, dish.value, googleAccuracy.value)

    fun missingRequired(ctx: Context): Int = checks(ctx).count { it.item.required && it.state == CheckState.NEEDED }
}

/**
 * "How to enable it" on the Mock GPS page: every setting the phone needs, each with its live state and a button that
 * opens the exact settings page. When everything required is done only the steps still open are listed (the rest
 * behind "Show all steps").
 */
@Composable
fun SetupChecklist() {
    val ctx = LocalContext.current
    val phone by SetupState.phone.collectAsStateWithLifecycle()
    val dish by SetupState.dish.collectAsStateWithLifecycle()
    val googleAccuracy by SetupState.googleAccuracy.collectAsStateWithLifecycle()
    // Delivery comes from the running mock.
    val mock by Live.mock.collectAsStateWithLifecycle()
    val checks = remember(phone, dish, mock, googleAccuracy) { SetupChecks.combine(ctx, phone, dish, googleAccuracy) }
    val required = checks.filter { it.item.required }
    val done = required.count { it.state == CheckState.OK }
    var askedPermission by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedPermission = true
        SetupState.phone.value = SetupChecks.phone(ctx)
    }
    val appName = stringResource(R.string.app_name)
    val allDone = done == required.size
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = if (allDone && !showAll) checks.filter { it.state != CheckState.OK } else checks

    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Section(stringResource(R.string.setup_title)) {
            Text(pluralStringResource(R.plurals.setup_progress, required.size, done, required.size), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            // A plain "N of M" and nothing more: no streaks, no praise.
            LinearProgressIndicator(progress = { done / required.size.toFloat() }, modifier = Modifier.fillMaxWidth())
            if (allDone) {
                Hint(stringResource(if (mock.enabled) R.string.setup_all_done_on else R.string.setup_all_done))
                ActionRow { SecondaryButton(stringResource(if (showAll) R.string.setup_hide_done else R.string.setup_show_all), onClick = { showAll = !showAll }) }
            }
        }
        for (check in shown) key(check.item.name) {
            SetupCard(check) {
                when (check.item) {
                    SetupItem.LOCATION_PERMISSION -> {
                        Hint(stringResource(R.string.setup_location_permission_why))
                        if (check.state != CheckState.OK) {
                            if (askedPermission) Hint(stringResource(R.string.setup_location_permission_denied))
                            ActionRow {
                                PrimaryButton(stepLabel(1, R.string.setup_action_allow), onClick = {
                                    permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                })
                                SecondaryButton(stepLabel(2, R.string.setup_action_app_settings), onClick = { open(ctx, appDetails(ctx)) })
                            }
                        }
                    }
                    SetupItem.LOCATION_ON -> {
                        Hint(stringResource(R.string.setup_location_on_why))
                        if (check.state != CheckState.OK) ActionRow {
                            PrimaryButton(stringResource(R.string.setup_action_location_settings), onClick = { open(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
                        }
                    }
                    SetupItem.DEVELOPER_OPTIONS -> {
                        val hidden = check.detail == Detail.DeveloperOptionsHidden
                        Hint(stringResource(R.string.setup_developer_why))
                        if (hidden) {
                            Hint(stringResource(R.string.setup_developer_hidden, appName))
                        } else if (check.state != CheckState.OK) {
                            Hint(stringResource(R.string.setup_developer_how))
                        }
                        if (check.state != CheckState.OK) ActionRow {
                            // One button is just the step; two are numbered in the order to do them.
                            PrimaryButton(if (hidden) stepLabel(1, R.string.setup_action_about_phone) else stringResource(R.string.setup_action_about_phone), onClick = { open(ctx, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) })
                            if (hidden) SecondaryButton(stepLabel(2, R.string.setup_action_developer_options), onClick = { open(ctx, SetupChecks.mockLocationAppSettings()) })
                        }
                    }
                    SetupItem.MOCK_APP -> {
                        Hint(stringResource(R.string.setup_mock_app_why, appName))
                        if (check.state != CheckState.OK) ActionRow {
                            PrimaryButton(stringResource(R.string.setup_action_mock_app), onClick = { open(ctx, SetupChecks.mockLocationAppSettings()) })
                        }
                    }
                    SetupItem.DISH_WIFI -> {
                        Hint(stringResource(R.string.setup_dish_wifi_why))
                        if (check.detail == Detail.WifiButNoDish) {
                            Hint(stringResource(R.string.setup_dish_wifi_no_dish))
                            VpnBindingNotice()
                        }
                        if (check.state != CheckState.OK) ActionRow {
                            // The Wi-Fi panel (a sheet over the app) is Android 10; before it the full Wi-Fi settings screen.
                            val wifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                            PrimaryButton(stringResource(R.string.setup_action_wifi), onClick = { open(ctx, Intent(wifi)) })
                        }
                    }
                    SetupItem.DISH_LOCATION -> DishLocationRow(check, appName)
                    SetupItem.GOOGLE_ACCURACY -> {
                        val fallback = App.instance.settings.value.networkFallback
                        Hint(stringResource(if (fallback) R.string.setup_google_accuracy_on_why else R.string.setup_google_accuracy_why))
                        if (check.state == CheckState.UNKNOWN && mock.enabled) Hint(stringResource(R.string.setup_google_accuracy_unknown))
                        if (check.state != CheckState.OK) ActionRow {
                            PrimaryButton(stringResource(R.string.setup_action_location_settings), onClick = { open(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
                        }
                    }
                    SetupItem.NOTIFICATIONS -> {
                        Hint(stringResource(R.string.setup_notifications_why))
                        if (check.detail == Detail.AlertsChannelOff) Hint(stringResource(R.string.setup_notifications_channel_off))
                        if (check.state != CheckState.OK) ActionRow {
                            PrimaryButton(stringResource(if (check.detail == Detail.AlertsChannelOff || askedPermission || !SetupChecks.notificationPermissionNeeded) R.string.setup_action_notification_settings else R.string.setup_action_allow), onClick = {
                                if (check.detail == Detail.AlertsChannelOff) {
                                    open(
                                        ctx,
                                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                            .putExtra(Settings.EXTRA_CHANNEL_ID, App.CHANNEL_ALERTS),
                                    )
                                } else if (SetupChecks.notificationPermissionNeeded && !askedPermission) {
                                    permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                } else {
                                    open(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                                }
                            })
                        }
                    }
                    SetupItem.BATTERY -> {
                        Hint(stringResource(if (batteryListScreen) R.string.setup_battery_old_why else R.string.setup_battery_why))
                        if (check.detail == Detail.BackgroundRestricted) Hint(stringResource(R.string.setup_battery_restricted))
                        if (check.state != CheckState.OK) ActionRow {
                            if (batteryListScreen) {
                                // Before Android 12 the app's own battery page has no such choice: the list of battery optimisation does.
                                PrimaryButton(stringResource(R.string.setup_action_battery_settings), onClick = { open(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
                            } else {
                                PrimaryButton(stringResource(R.string.setup_action_app_settings), onClick = { open(ctx, appDetails(ctx)) })
                            }
                        }
                    }
                    SetupItem.MOCK_DELIVERY -> {
                        Hint(stringResource(R.string.setup_delivery_why, appName))
                        when (val d = check.detail) {
                            Detail.MockOff -> Hint(stringResource(R.string.setup_delivery_mock_off))
                            is Detail.DeliveryFailed -> {
                                val gms = stringResource(R.string.mock_source_gms_fused)
                                StatusLine(Status.WARN, stringResource(R.string.setup_delivery_failed, d.sources.joinToString { if (it == "gms_fused") gms else it }))
                            }
                            else -> Unit
                        }
                        if (mock.enabled) DeliveryRow(mock.delivery, rememberMonoNow())
                        if (!FusedMock.IN_BUILD) Hint(stringResource(R.string.setup_delivery_foss))
                    }
                }
            }
        }
    }
}

/**
 * The dish's "location on the local network" setting: what the dish says (on / off / answers position requests anyway /
 * can't tell), a button that asks the dish to switch it on (an explicit tap: the command goes through the command bus, so it
 * is logged, remembered in the capability book and shown with the same "last outcome" as Starlink > Controls), and the
 * dish's answer in plain words. A refusal the book remembers greys the button out until "Try again". Whether a Mini accepts
 * this write from a phone on its Wi-Fi without the Starlink app is not verified, and the texts say so.
 */
@Composable
private fun DishLocationRow(check: SetupCheck, appName: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sender = rememberSender()
    val caps = rememberCapabilities()
    val detail = check.detail as? Detail.DishLocation
    val reading = detail?.reading
    val blocked = caps.unavailable(DishCallKind.ALLOW_LOCAL_LOCATION)
    Hint(stringResource(R.string.setup_dish_location_why, appName))
    // The three refusals of get_location in their own words: the plan's / the firmware's (this setting changes nothing), or
    // the setting off (the reading's sentence says how that was found); then whether the diagnostics' position stands in.
    when {
        detail != null && !detail.reachable -> Hint(stringResource(R.string.local_location_dish_silent))
        detail?.refusal == LocationRefusal.POLICY_DENIED -> Hint(stringResource(R.string.local_location_plan))
        detail?.refusal == LocationRefusal.UNIMPLEMENTED -> Hint(stringResource(R.string.local_location_unimplemented))
        reading != null -> Hint(localLocationSentence(reading))
    }
    if (detail?.diagnosticsPosition == true) Hint(stringResource(R.string.setup_dish_location_via_diagnostics, appName))
    // The last attempt to switch the setting on says nothing against the plan's or the firmware's refusal.
    if (detail?.refusal != LocationRefusal.POLICY_DENIED && detail?.refusal != LocationRefusal.UNIMPLEMENTED) LocalLocationOutcomeLine(showRemembered = true)
    // The button is there while location is not known to be available, the dish answers at all, and enabling the setting
    // can help (not against the plan's or the firmware's refusal).
    val canAsk = detail?.reachable == true && reading?.planRestricted != true && reading?.unimplemented != true &&
        (detail.refusal == null || detail.refusal == LocationRefusal.NOT_ENABLED) && check.state != CheckState.OK
    if (canAsk) {
        ActionRow {
            PrimaryButton(stepLabel(1, R.string.setup_action_allow_on_dish), enabled = blocked == null && !sender.busy, onClick = {
                sender.send(DeviceCall.of(DishCallKind.ALLOW_LOCAL_LOCATION), TAG_LOCAL_LOCATION) { result ->
                    scope.launch {
                        SetupState.dish.value = SetupChecks.dish(ctx)
                        // The dish may take a moment to report the new setting; the periodic check catches up too.
                        if (result.ok) {
                            delay(6_000)
                            SetupState.dish.value = SetupChecks.dish(ctx)
                        }
                    }
                }
            })
            // Not a step: it lets step 1 be tried again after a refusal.
            if (blocked != null) SecondaryButton(stringResource(R.string.setup_action_try_again), onClick = { CapabilityStore.forgetDish() })
            // The opt-in way through the user's own Starlink account; the main way once the dish has refused the LAN write.
            AccountEnableButton(emphasized = blocked != null, step = 2)
            SecondaryButton(stepLabel(3, R.string.setup_action_starlink_app), onClick = { openStarlinkApp(ctx) })
        }
        if (blocked != null) Hint(stringResource(R.string.account_enable_hint_refused))
    }
}

/** One step of the checklist: its title, its state as a chip at the right of the title, then what it needs. */
@Composable
private fun SetupCard(check: SetupCheck, content: @Composable () -> Unit) {
    val (status, label) = when (check.state) {
        CheckState.OK -> Status.OK to R.string.setup_state_ok
        CheckState.NEEDED -> Status.BAD to R.string.setup_state_needed
        CheckState.RECOMMENDED -> Status.WARN to R.string.setup_state_recommended
        CheckState.UNKNOWN -> Status.NEUTRAL to if (check.detail == Detail.DeveloperOptionsHidden) R.string.setup_state_hidden else R.string.setup_state_unknown
    }
    Section(title(check.item), trailing = { StatusPill(status, stringResource(label)) }) {
        content()
    }
}

/** A button of a step with several buttons: its label after its number in the order to do them ("2. App settings"). */
@Composable
internal fun stepLabel(step: Int, @StringRes label: Int): String = stringResource(R.string.setup_step_button, step.toString(), stringResource(label))

@Composable
private fun title(item: SetupItem): String {
    val app = stringResource(R.string.app_name)
    return when (item) {
        SetupItem.LOCATION_PERMISSION -> stringResource(R.string.setup_location_permission_title)
        SetupItem.LOCATION_ON -> stringResource(R.string.setup_location_on_title)
        SetupItem.DEVELOPER_OPTIONS -> stringResource(R.string.setup_developer_title)
        SetupItem.MOCK_APP -> stringResource(R.string.setup_mock_app_title, app)
        SetupItem.DISH_WIFI -> stringResource(R.string.setup_dish_wifi_title)
        SetupItem.DISH_LOCATION -> stringResource(R.string.setup_dish_location_title)
        SetupItem.GOOGLE_ACCURACY -> stringResource(
            if (App.instance.settings.value.networkFallback) R.string.setup_google_accuracy_on_title else R.string.setup_google_accuracy_title,
        )
        SetupItem.NOTIFICATIONS -> stringResource(R.string.setup_notifications_title)
        SetupItem.BATTERY -> stringResource(if (batteryListScreen) R.string.setup_battery_old_title else R.string.setup_battery_title)
        SetupItem.MOCK_DELIVERY -> stringResource(R.string.setup_delivery_title)
    }
}

/**
 * "Unrestricted" in the app's battery page is Android 12 (API 31); before it the exemption is a choice in the list of
 * battery optimisation (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, API 23), and the texts say so.
 */
private val batteryListScreen: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

private fun appDetails(ctx: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))

/** Opens a settings page; some phones lack a page, then the main Settings opens instead. */
private fun open(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

private fun openStarlinkApp(ctx: Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage(SetupChecks.STARLINK_APP)
    if (launch != null) {
        open(ctx, launch)
    } else {
        open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${SetupChecks.STARLINK_APP}")))
    }
}
