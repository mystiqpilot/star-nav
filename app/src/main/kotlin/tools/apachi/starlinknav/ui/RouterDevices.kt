package tools.apachi.starlinknav.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.ClientList
import tools.apachi.starlinknav.core.dish.CommandRisk
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.RouterCommand
import tools.apachi.starlinknav.core.dish.proto.WifiClient
import tools.apachi.starlinknav.service.CommandBus

/**
 * The devices on the router's Wi-Fi and cable: name, address, band, signal, link rates and usage; this phone marked when its
 * address is among them. A device opens to show more and to be renamed or paused. Pausing the phone itself is not offered:
 * it would cut the app off from the router and the dish.
 */
@Composable
fun RouterDevicesSection(data: RouterData, env: RouterEnv, actions: RouterActions) {
    // The last list seen stays on a failed read (the first one after the app comes back often fails): an open rename or
    // pause dialog and what was typed into it survive a switch to another app.
    val last = remember { LastSeen<List<WifiClient>>() }
    data.clients?.let { last.value = it }
    val clients = data.clients ?: last.value
    if (data.clients == null) FeedProblem(data.problemFor(data.clientsSample), Device.ROUTER, env.target.address)
    if (clients == null) return
    val sorted = remember(clients) { ClientList.sorted(clients) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val online = sorted.count { ClientList.state(it) == ClientList.State.ONLINE }
    val paused = sorted.count { ClientList.state(it) == ClientList.State.PAUSED }
    Section(stringResource(R.string.rd_title)) {
        Text(stringResource(R.string.rd_summary, online.toString(), paused.toString(), (sorted.size - online - paused).toString()), style = MaterialTheme.typography.bodyMedium, color = StarTheme.colors.muted)
        if (sorted.isEmpty()) Text(stringResource(R.string.rd_none), style = MaterialTheme.typography.bodyMedium)
        sorted.forEachIndexed { i, c ->
            if (i > 0) HorizontalDivider(color = StarTheme.colors.grid)
            val key = c.mac_address.ifEmpty { "#${c.client_id}" }
            ClientRow(
                c = c, thisPhone = ClientList.isThisPhone(c, env.ownAddresses), expanded = open == key,
                onToggle = { open = if (open == key) null else key }, actions = actions, data = data,
            )
        }
        Hint(stringResource(R.string.rd_hint))
    }
    RouterNoticeCard(actions)
    ControlOutcomeCard(listOf(TAG_RENAME, TAG_PAUSE))
}

private const val TAG_RENAME = "router_client_name"
private const val TAG_PAUSE = "router_client_pause"

/** The last results of the device actions (they have no card of their own, the actions sit inside the rows). */
@Composable
private fun ControlOutcomeCard(tags: List<String>) {
    val last by CommandBus.last.collectAsStateWithLifecycle()
    if (tags.none { it in last }) return
    Section(stringResource(R.string.rd_last_title)) {
        for (tag in tags) CommandOutcomeLine(tag, Device.ROUTER)
    }
}

@Composable
private fun ClientRow(c: WifiClient, thisPhone: Boolean, expanded: Boolean, onToggle: () -> Unit, actions: RouterActions, data: RouterData) {
    val colors = StarTheme.colors
    val state = ClientList.state(c)
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.hair)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(ClientList.displayName(c), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (thisPhone) Text(stringResource(R.string.rd_this_phone), style = MaterialTheme.typography.bodySmall, color = colors.dish, modifier = Modifier.padding(top = 1.dp))
            if (state != ClientList.State.ONLINE) {
                StatusPill(if (state == ClientList.State.PAUSED) Status.WARN else Status.NEUTRAL, stringResource(clientStateRes(state)))
            }
        }
        val parts = listOfNotNull(
            c.ip_address.takeIf { it.isNotEmpty() },
            clientLinkRes(c.iface)?.let { stringResource(it) },
            c.signal_strength.takeIf { it < 0f && it.isFinite() }?.let { stringResource(R.string.rd_dbm, it.f(0)) },
            if (state == ClientList.State.ONLINE && (c.rx_stats?.rate_mbps ?: 0) > 0) {
                stringResource(R.string.rd_rates, (c.rx_stats?.rate_mbps ?: 0).toString(), (c.tx_stats?.rate_mbps ?: 0).toString())
            } else {
                null
            },
        )
        Text(parts.joinToString(" · ").ifEmpty { "-" }, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        if (c.download_mb > 0 || c.upload_mb > 0) {
            Text(stringResource(R.string.rd_usage, bytes(c.download_mb * 1_048_576L), bytes(c.upload_mb * 1_048_576L)), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        if (expanded) ClientDetail(c, thisPhone, actions, data)
    }
}

@Composable
private fun ClientDetail(c: WifiClient, thisPhone: Boolean, actions: RouterActions, data: RouterData) {
    val colors = StarTheme.colors
    val caps = rememberCapabilities(Device.ROUTER)
    val blockedRename = caps.unavailable(DishCallKind.ROUTER_SET_CLIENT_NAME)
    val blockedPause = caps.unavailable(DishCallKind.ROUTER_BLOCK_CLIENT)
    var renaming by remember { mutableStateOf(false) }
    var pausing by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(c.given_name) }
    Column(Modifier.padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.hair)) {
        if (c.name.isNotEmpty() && c.name != ClientList.displayName(c)) KeyValueRow(stringResource(R.string.rd_host_name), c.name)
        KeyValueRow(stringResource(R.string.rd_mac), c.mac_address.ifEmpty { "-" })
        c.ipv6_addresses.firstOrNull()?.let { KeyValueRow(stringResource(R.string.rs_wan_v6), it) }
        if (c.associated_time_s > 0) KeyValueRow(stringResource(R.string.rd_connected_for), duration(c.associated_time_s * 1000L))
        if (c.no_data_idle_s > 0) KeyValueRow(stringResource(R.string.rd_idle), duration(c.no_data_idle_s * 1000L))
        if (c.snr > 0f) KeyValueRow(stringResource(R.string.rd_snr), stringResource(R.string.rd_db, c.snr.f(0)))
        if (c.mode_str.isNotEmpty()) KeyValueRow(stringResource(R.string.rd_mode), c.mode_str)
        c.rx_stats?.let { rx ->
            if (rx.throughput_mbps_last_15s_avg > 0f || (c.tx_stats?.throughput_mbps_last_15s_avg ?: 0f) > 0f) {
                KeyValueRow(
                    stringResource(R.string.rd_throughput),
                    stringResource(R.string.rd_rates_pair, rx.throughput_mbps_last_15s_avg.f(1), (c.tx_stats?.throughput_mbps_last_15s_avg ?: 0f).f(1)),
                )
            }
        }
        c.ping_metrics?.takeIf { it.latency_5m > 0f }?.let {
            KeyValueRow(stringResource(R.string.rd_ping), stringResource(R.string.rd_ping_value, stringResource(R.string.unit_ms, it.latency_5m.f(0)), stringResource(R.string.unit_percent, (it.drop_rate_5m * 100f).f(1))))
        }
        if (c.seconds_until_dhcp_lease_expires > 0f && c.seconds_until_dhcp_lease_expires.isFinite()) {
            KeyValueRow(stringResource(R.string.rd_lease), duration((c.seconds_until_dhcp_lease_expires * 1000f).toLong()))
        }
        Hint(stringResource(R.string.rd_rx_tx_hint))
        if (thisPhone) StatusPill(Status.WARN, stringResource(R.string.rd_pause_self))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.xs)) {
            SecondaryButton(stringResource(R.string.rd_rename), { newName = c.given_name; renaming = true }, enabled = !actions.busy && blockedRename == null && c.mac_address.isNotEmpty())
            val paused = ClientList.state(c) == ClientList.State.PAUSED
            SecondaryButton(stringResource(if (paused) R.string.rd_resume else R.string.rd_pause), { pausing = true }, enabled = !actions.busy && blockedPause == null && c.mac_address.isNotEmpty() && (paused || !thisPhone))
        }
        val reason = blockedRename ?: blockedPause
        if (reason != null) {
            Text(
                stringResource(R.string.controls_unavailable, outcomeSentence(reason.outcome, Device.ROUTER)) + outcomeDetail(reason.outcome, reason.message),
                color = colors.muted, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (renaming) {
        ConfirmDialog(
            title = stringResource(R.string.rd_rename_title, ClientList.displayName(c)),
            text = stringResource(R.string.rd_rename_text),
            confirmLabel = stringResource(R.string.rd_rename_button),
            risk = DishCallKind.ROUTER_SET_CLIENT_NAME.risk ?: CommandRisk.DISRUPTIVE,
            canConfirm = newName.length <= 64,
            onConfirm = {
                renaming = false
                val name = newName.trim()
                actions.sendFromConfig(TAG_RENAME) { base -> RouterCommand.SetClientGivenName(base, c.mac_address, name, c.client_id) }
            },
            onDismiss = { renaming = false },
        ) {
            OutlinedTextField(
                value = newName, onValueChange = { newName = it.take(64) }, singleLine = true,
                label = { Text(stringResource(R.string.rd_rename_field)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (pausing) {
        val resume = ClientList.state(c) == ClientList.State.PAUSED
        ConfirmDialog(
            title = stringResource(if (resume) R.string.rd_resume_title else R.string.rd_pause_title, ClientList.displayName(c)),
            text = stringResource(if (resume) R.string.rd_resume_text else R.string.rd_pause_text),
            confirmLabel = stringResource(if (resume) R.string.rd_resume else R.string.rd_pause),
            // Letting a device back on is the undo of pausing it; pausing is the kind's own risk.
            risk = if (resume) CommandRisk.REVERSIBLE else DishCallKind.ROUTER_BLOCK_CLIENT.risk ?: CommandRisk.DISRUPTIVE,
            onConfirm = {
                pausing = false
                val own = if (thisPhone) c.mac_address else null
                actions.sendFromConfig(TAG_PAUSE) { base -> RouterCommand.BlockClient(base, c.mac_address, block = !resume, clientId = c.client_id, ownMac = own) }
            },
            onDismiss = { pausing = false },
        )
    }
}
