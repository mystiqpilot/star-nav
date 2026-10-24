package tools.apachi.starlinknav.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.CallTarget
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DnsPreset
import tools.apachi.starlinknav.core.dish.Redact
import tools.apachi.starlinknav.core.dish.RouterClient
import tools.apachi.starlinknav.core.dish.RouterCommand
import tools.apachi.starlinknav.core.dish.SpeedtestError
import tools.apachi.starlinknav.core.dish.SpeedtestView
import tools.apachi.starlinknav.core.dish.WifiConfigEdit
import tools.apachi.starlinknav.core.dish.WifiInput
import tools.apachi.starlinknav.core.dish.WifiOverview
import tools.apachi.starlinknav.core.dish.proto.WifiConfig
import tools.apachi.starlinknav.service.CapabilityStore
import tools.apachi.starlinknav.service.DeviceCall
import tools.apachi.starlinknav.service.DishFeed

private const val TAG_REBOOT = "router_reboot"
private const val TAG_UPDATE = "router_update"
private const val TAG_DNS = "router_dns"
private const val TAG_LAN = "router_lan"
private const val TAG_SPEED = "router_speedtest"
private const val TAG_BYPASS = "router_bypass"
private const val TAG_RESET = "router_factory_reset"

/**
 * What the app can tell the router to do, each behind a confirmation that says what will happen; the ones that can cut this
 * phone off the router (and with it the dish and the position feed) say so, and the ones that cannot be undone from the app
 * need a word typed. The last answer of each shows under its buttons; a control the router said it does not implement or
 * refuses this client is greyed out with that answer.
 */
@Composable
fun RouterControlsSection(feed: DishFeed, data: RouterData, env: RouterEnv, actions: RouterActions) {
    val config = data.config
    // As on the Wi-Fi section: the last config seen (without passwords) keeps the DNS, LAN and bypass cards, and an open
    // dialog with what was typed into it, through a failed read.
    val last = remember { LastSeen<WifiConfig>() }
    remember(config) { config?.let(Redact::config) }?.let { last.value = it }
    val shown = config ?: last.value
    val overview = remember(shown) { shown?.let(WifiOverview::of) }
    RouterNoticeCard(actions)
    Hint(stringResource(R.string.rc_intro))
    RouterRebootCard(actions)
    RouterUpdateCheckCard(actions)
    if (config == null) FeedProblem(data.problemFor(data.configSample), Device.ROUTER, env.target.address)
    if (overview != null) {
        DnsCard(overview, actions)
        LanCard(overview, actions)
    }
    SpeedtestCard(feed, actions)
    if (overview != null) BypassCard(overview, actions)
    RouterFactoryResetCard(actions)
    RouterForgetCard()
}

// ---- Reboot

@Composable
private fun RouterRebootCard(actions: RouterActions) {
    var confirm by remember { mutableStateOf(false) }
    ControlCard(
        title = stringResource(R.string.rc_reboot_title),
        description = stringResource(R.string.rc_reboot_text),
        kinds = listOf(DishCallKind.ROUTER_REBOOT), device = Device.ROUTER, tag = TAG_REBOOT,
    ) { enabled ->
        SecondaryButton(stringResource(R.string.rc_reboot_button), { confirm = true }, enabled = enabled && !actions.busy)
    }
    if (confirm) {
        val call = DeviceCall.of(RouterCommand.Reboot)
        ConfirmDialog(
            title = stringResource(R.string.rc_reboot_title),
            text = stringResource(R.string.rc_reboot_confirm),
            confirmLabel = stringResource(R.string.rc_reboot_button),
            risk = call.risk,
            onConfirm = {
                confirm = false
                actions.send(call, TAG_REBOOT)
            },
            onDismiss = { confirm = false },
        )
    }
}

// ---- Check for update

@Composable
private fun RouterUpdateCheckCard(actions: RouterActions) {
    var confirm by remember { mutableStateOf(false) }
    ControlCard(
        title = stringResource(R.string.rc_update_title),
        description = stringResource(R.string.rc_update_text),
        kinds = listOf(DishCallKind.ROUTER_UPDATE), device = Device.ROUTER, tag = TAG_UPDATE,
    ) { enabled ->
        SecondaryButton(stringResource(R.string.rc_update_button), { confirm = true }, enabled = enabled && !actions.busy)
    }
    if (confirm) {
        val call = DeviceCall.of(RouterCommand.Update())
        ConfirmDialog(
            title = stringResource(R.string.rc_update_title),
            text = stringResource(R.string.rc_update_confirm),
            confirmLabel = stringResource(R.string.rc_update_button),
            risk = call.risk,
            onConfirm = {
                confirm = false
                actions.send(call, TAG_UPDATE)
            },
            onDismiss = { confirm = false },
        )
    }
}

// ---- DNS and content filter

private const val DNS_NONE = 0
private const val DNS_MALWARE = 1
private const val DNS_MALWARE_ADULT = 2
private const val DNS_CUSTOM = 3

@Composable
private fun DnsCard(o: WifiOverview, actions: RouterActions) {
    var confirm by remember { mutableStateOf(false) }
    // The choice starts from the router's setting and keeps the user's edits until a change goes through.
    var pick by rememberSaveable { mutableIntStateOf(-1) }
    var custom by rememberSaveable { mutableStateOf("") }
    var secure by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val currentChoice = when (o.dnsPreset) {
        DnsPreset.NONE -> DNS_NONE
        DnsPreset.MALWARE -> DNS_MALWARE
        DnsPreset.MALWARE_AND_ADULT -> DNS_MALWARE_ADULT
        null -> DNS_CUSTOM
    }
    val choice = if (pick >= 0) pick else currentChoice
    val wantSecure = secure ?: o.secureDns
    val servers: List<String>? = when (choice) {
        DNS_NONE -> emptyList()
        DNS_MALWARE -> DnsPreset.MALWARE.servers
        DNS_MALWARE_ADULT -> DnsPreset.MALWARE_AND_ADULT.servers
        else -> WifiInput.parseServers(custom)?.takeIf { it.isNotEmpty() }
    }
    val changed = servers != null && (servers != o.nameservers || wantSecure != o.secureDns)
    val labels = listOf(R.string.rc_dns_none, R.string.rc_dns_malware, R.string.rc_dns_adult, R.string.rc_dns_custom)
    ControlCard(
        title = stringResource(R.string.rc_dns_title),
        description = stringResource(R.string.rc_dns_text),
        kinds = listOf(DishCallKind.ROUTER_SET_CONFIG), device = Device.ROUTER, tag = TAG_DNS,
    ) { enabled ->
        KeyValueRow(
            stringResource(R.string.controls_now),
            if (o.nameservers.isEmpty()) stringResource(R.string.rc_dns_none) else o.nameservers.joinToString(" "),
        )
        labels.forEachIndexed { i, label ->
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { pick = i }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = choice == i, enabled = enabled, onClick = { pick = i })
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (choice == DNS_CUSTOM) {
            OutlinedTextField(
                value = custom, onValueChange = { custom = it.take(120) }, enabled = enabled, singleLine = true,
                label = { Text(stringResource(R.string.rc_dns_custom_field)) },
                isError = custom.isNotBlank() && servers == null,
                supportingText = { Text(stringResource(R.string.rc_dns_custom_hint, WifiInput.DNS_MAX.toString())) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rc_dns_secure), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = wantSecure, enabled = enabled, onCheckedChange = { secure = it })
        }
        PrimaryButton(stringResource(R.string.controls_apply), { confirm = true }, enabled = enabled && !actions.busy && changed)
    }
    if (confirm && servers != null) {
        val edit = WifiConfigEdit.SetDns(servers, wantSecure)
        ConfirmDialog(
            title = stringResource(R.string.rc_dns_title),
            text = stringResource(R.string.rc_dns_confirm, if (servers.isEmpty()) stringResource(R.string.rc_dns_none) else servers.joinToString(" ")),
            confirmLabel = stringResource(R.string.controls_apply),
            risk = RouterCommand.SetConfig.riskOf(listOf(edit)),
            onConfirm = {
                confirm = false
                actions.sendFromConfig(TAG_DNS, then = { if (it.ok) { pick = -1; secure = null } }) { base -> RouterCommand.SetConfig(base, listOf(edit)) }
            },
            onDismiss = { confirm = false },
        )
    }
}

// ---- LAN subnet

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanCard(o: WifiOverview, actions: RouterActions) {
    var confirm by remember { mutableStateOf(false) }
    var pick by rememberSaveable { mutableStateOf("") }
    val current = o.main?.ipv4.orEmpty()
    val wanted = pick.trim()
    val valid = WifiInput.isIpv4Address(wanted)
    ControlCard(
        title = stringResource(R.string.rc_lan_title),
        description = stringResource(R.string.rc_lan_text),
        kinds = listOf(DishCallKind.ROUTER_SET_CONFIG), device = Device.ROUTER, tag = TAG_LAN,
    ) { enabled ->
        KeyValueRow(stringResource(R.string.controls_now), current.ifEmpty { "-" })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            for (address in RouterClient.PRESET_ADDRESSES) {
                FilterChip(selected = wanted == address, enabled = enabled && address != current, onClick = { pick = address }, label = { Text(address, style = MaterialTheme.typography.bodyMedium) })
            }
        }
        OutlinedTextField(
            value = pick, onValueChange = { pick = it.take(15) }, enabled = enabled, singleLine = true,
            label = { Text(stringResource(R.string.rc_lan_field)) }, isError = pick.isNotBlank() && !valid,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(stringResource(R.string.controls_apply), { confirm = true }, enabled = enabled && !actions.busy && valid && wanted != current && o.main != null)
    }
    if (confirm && valid) {
        val edit = WifiConfigEdit.SetLanSubnet(wanted)
        ConfirmDialog(
            title = stringResource(R.string.rc_lan_title),
            text = stringResource(R.string.rc_lan_confirm, wanted),
            confirmLabel = stringResource(R.string.controls_apply),
            risk = RouterCommand.SetConfig.riskOf(listOf(edit)), typed = true, word = stringResource(R.string.confirm_word_change),
            onConfirm = {
                confirm = false
                actions.sendFromConfig(TAG_LAN, then = { if (it.ok) pick = "" }) { base -> RouterCommand.SetConfig(base, listOf(edit)) }
            },
            onDismiss = { confirm = false },
        )
    }
}

// ---- Speed test

/**
 * Opt-in: the test moves data at full speed in both directions, which counts against a metered plan. It is started with
 * telemetry off (nothing is reported to Starlink). Its result is read from the router once a second while it runs.
 */
@Composable
private fun SpeedtestCard(feed: DishFeed, actions: RouterActions) {
    var optIn by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<SpeedtestView?>(null) }
    var waiting by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(round) {
        if (round == 0) return@LaunchedEffect
        waiting = true
        view = null
        val started = SystemClock.elapsedRealtime()
        var sawRunning = false
        delay(1_000)
        // Reads once a second for at most SPEED_WATCH_MS, and only while the app is in front (a stop pauses the reads, a
        // return takes them up again; the time limit keeps counting).
        flow {
            while (SystemClock.elapsedRealtime() - started < SPEED_WATCH_MS) {
                emit(feed.readNow(DishCallKind.ROUTER_SPEEDTEST_STATUS))
                delay(1_000)
            }
        }.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).transformWhile { sample ->
            val v = if (sample.result.ok) SpeedtestView.of(sample.result.response?.get_speedtest_status) else null
            if (v != null) {
                view = v
                if (v.running) sawRunning = true
            }
            // Over once the test was seen running and stopped, or failed, or left data behind.
            val over = v != null && !v.running && (sawRunning || v.failed || (v.hasData && SystemClock.elapsedRealtime() - started > 4_000))
            emit(Unit)
            !over
        }.collect { }
        waiting = false
    }
    ControlCard(
        title = stringResource(R.string.rc_speed_title),
        description = stringResource(R.string.rc_speed_text),
        kinds = listOf(DishCallKind.ROUTER_START_SPEEDTEST), device = Device.ROUTER, tag = TAG_SPEED,
    ) { enabled ->
        Row(Modifier.heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rc_speed_optin), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = optIn, enabled = enabled, onCheckedChange = { optIn = it })
        }
        PrimaryButton(stringResource(R.string.rc_speed_button), { confirm = true }, enabled = enabled && optIn && !actions.busy && !waiting)
        val v = view
        if (v != null) {
            val colors = StarTheme.colors
            Text(
                stringResource(
                    when {
                        v.running -> R.string.rc_speed_running
                        v.failed -> R.string.rc_speed_failed
                        else -> R.string.rc_speed_done
                    },
                ),
                color = if (v.failed) colors.bad else colors.muted, style = MaterialTheme.typography.bodyMedium,
            )
            if (v.hasData) {
                Text(
                    stringResource(R.string.rc_speed_line, stringResource(R.string.ov_download), (v.downMbps.lastOrNull() ?: 0f).f(1), (v.downAvg ?: 0f).f(1), (v.downMax ?: 0f).f(1)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.rc_speed_line, stringResource(R.string.ov_upload), (v.upMbps.lastOrNull() ?: 0f).f(1), (v.upAvg ?: 0f).f(1), (v.upMax ?: 0f).f(1)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (v.failed) KeyValueRow(stringResource(R.string.rc_speed_error), listOf(v.downError, v.upError).filter { it > 0 }.joinToString(" ") { SpeedtestError.name(it) }, status = Status.BAD)
        }
    }
    if (confirm) {
        val call = DeviceCall.of(RouterCommand.StartSpeedtest())
        ConfirmDialog(
            title = stringResource(R.string.rc_speed_title),
            text = stringResource(R.string.rc_speed_confirm),
            confirmLabel = stringResource(R.string.rc_speed_button),
            risk = call.risk,
            onConfirm = {
                confirm = false
                actions.send(call, TAG_SPEED) { if (it.ok) round++ }
            },
            onDismiss = { confirm = false },
        )
    }
}

private const val SPEED_WATCH_MS = 60_000L

// ---- Bypass mode

@Composable
private fun BypassCard(o: WifiOverview, actions: RouterActions) {
    var confirm by remember { mutableStateOf(false) }
    val on = o.bypass
    ControlCard(
        title = stringResource(R.string.rc_bypass_title),
        description = stringResource(R.string.rc_bypass_text),
        kinds = listOf(DishCallKind.ROUTER_SET_CONFIG), device = Device.ROUTER, tag = TAG_BYPASS,
    ) { enabled ->
        KeyValueRow(stringResource(R.string.controls_now), stringResource(if (on) R.string.rc_bypass_is_on else R.string.rc_bypass_is_off), status = if (on) Status.BAD else null)
        if (on) {
            PrimaryButton(stringResource(R.string.rc_bypass_leave_button), { confirm = true }, enabled = enabled && !actions.busy)
        } else {
            SecondaryButton(stringResource(R.string.rc_bypass_on_button), { confirm = true }, enabled = enabled && !actions.busy, color = StarTheme.colors.bad)
        }
    }
    if (confirm) {
        val edit = WifiConfigEdit.SetBypassMode(!on)
        ConfirmDialog(
            title = stringResource(R.string.rc_bypass_title),
            text = stringResource(if (on) R.string.rc_bypass_leave_confirm else R.string.rc_bypass_on_confirm),
            confirmLabel = stringResource(if (on) R.string.rc_bypass_leave_button else R.string.rc_bypass_on_button),
            // Switching it on is the one change the app cannot undo: the Wi-Fi it would be undone through is gone.
            risk = RouterCommand.SetConfig.riskOf(listOf(edit)),
            word = stringResource(R.string.confirm_word_bypass),
            acknowledge = if (on) null else stringResource(R.string.rc_bypass_ack),
            onConfirm = {
                confirm = false
                actions.sendFromConfig(TAG_BYPASS) { base -> RouterCommand.SetConfig(base, listOf(edit)) }
            },
            onDismiss = { confirm = false },
        )
    }
}

// ---- Router factory reset

@Composable
private fun RouterFactoryResetCard(actions: RouterActions) {
    var confirm by remember { mutableStateOf(false) }
    ControlCard(
        title = stringResource(R.string.rc_reset_title),
        description = stringResource(R.string.rc_reset_text),
        kinds = listOf(DishCallKind.ROUTER_FACTORY_RESET), device = Device.ROUTER, tag = TAG_RESET,
    ) { enabled ->
        SecondaryButton(stringResource(R.string.rc_reset_button), { confirm = true }, enabled = enabled && !actions.busy, color = StarTheme.colors.bad)
    }
    if (confirm) {
        val call = DeviceCall.of(RouterCommand.FactoryReset)
        ConfirmDialog(
            title = stringResource(R.string.rc_reset_title),
            text = stringResource(R.string.rc_reset_confirm),
            confirmLabel = stringResource(R.string.rc_reset_button),
            risk = call.risk,
            onConfirm = {
                confirm = false
                actions.send(call, TAG_RESET)
            },
            onDismiss = { confirm = false },
        )
    }
}

// ---- Greyed-out controls can be tried again

private val SCREEN_READS = setOf(
    DishCallKind.ROUTER_STATUS, DishCallKind.ROUTER_CLIENTS, DishCallKind.ROUTER_CONFIG, DishCallKind.ROUTER_HISTORY, DishCallKind.ROUTER_SPEEDTEST_STATUS,
)

@Composable
private fun RouterForgetCard() {
    val caps = rememberCapabilities(Device.ROUTER)
    // The commands and the reads this screen makes; a probe-only call the router does not implement is nothing to retry here.
    val shown = DishCallKind.entries.filter { it.target == CallTarget.ROUTER && (it.isCommand || it in SCREEN_READS) }
    val anyGreyed = shown.any { caps.unavailable(it) != null }
    if (!anyGreyed) return
    Section(stringResource(R.string.rc_forget_title)) {
        Hint(stringResource(R.string.rc_forget_text))
        SecondaryButton(stringResource(R.string.rc_forget_button), { CapabilityStore.forgetRouter() })
    }
}
