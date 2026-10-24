package tools.apachi.starlinknav.ui

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.Redact
import tools.apachi.starlinknav.core.dish.RouterCommand
import tools.apachi.starlinknav.core.dish.WifiBand
import tools.apachi.starlinknav.core.dish.WifiConfigEdit
import tools.apachi.starlinknav.core.dish.WifiInput
import tools.apachi.starlinknav.core.dish.WifiNetworkView
import tools.apachi.starlinknav.core.dish.WifiOverview
import tools.apachi.starlinknav.core.dish.proto.WifiConfig

private const val TAG_NAME = "router_wifi_name"
private const val TAG_PASSWORD = "router_wifi_password"
private const val TAG_HIDDEN = "router_wifi_hidden"
private const val TAG_GUEST = "router_wifi_guest"
private const val MASK = "••••••••"

/** Windows with a user of [SecureWindow] and how many: `FLAG_SECURE` stays on until the last of them is gone. Main thread only. */
private val secureUsers = HashMap<Window, Int>()

/**
 * Keeps the activity's window secure while [secure] is true and this is in composition: no screenshot, screen recording or
 * Recents thumbnail then shows what the window holds (a revealed Wi-Fi password). Dialogs opened meanwhile inherit it.
 */
@Composable
fun SecureWindow(secure: Boolean) {
    val window = LocalActivity.current?.window
    DisposableEffect(window, secure) {
        if (window == null || !secure) {
            onDispose { }
        } else {
            secureUsers[window] = (secureUsers[window] ?: 0) + 1
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                val left = (secureUsers[window] ?: 1) - 1
                if (left <= 0) {
                    secureUsers.remove(window)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    secureUsers[window] = left
                }
            }
        }
    }
}

/**
 * The router's Wi-Fi networks (name per band, hidden, guest, security) with the password masked and a reveal button; the
 * password lives in memory only, the window cannot be captured while it is shown, and the reveal switches off when the app
 * pauses or leaves the screen. Under it, the controls for the
 * name (one for both bands or one per band), the password, hiding the name and the guest network. Each change asks first and
 * says that this phone leaves the network and has to join again. A change starts from a fresh read of the router's config.
 */
@Composable
fun RouterWifiSection(data: RouterData, env: RouterEnv, actions: RouterActions) {
    val config = data.config
    // The last config seen, without its passwords: a read that fails (the first one after the app comes back often meets a
    // connection Android closed meanwhile) keeps the cards, and with them an open edit dialog and what was typed into it.
    val last = remember { LastSeen<WifiConfig>() }
    remember(config) { config?.let(Redact::config) }?.let { last.value = it }
    val shown = config ?: last.value
    if (config == null) FeedProblem(data.problemFor(data.configSample), Device.ROUTER, env.target.address)
    if (shown == null) return
    val overview = remember(shown) { WifiOverview.of(shown) }
    var reveal by remember { mutableStateOf(false) }
    // Android takes the Recents thumbnail when the activity pauses, before it stops: hide the password from then on.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { reveal = false }
    // While a password is on the screen, the window cannot be captured (screenshot, screen recording, Recents).
    SecureWindow(reveal)
    RouterNoticeCard(actions)
    if (overview.networks.isEmpty()) Section(stringResource(R.string.rw_main_title)) { Hint(stringResource(R.string.rw_none)) }
    for (net in overview.networks) NetworkCard(net, reveal) { reveal = !reveal }
    WifiNameCard(overview, actions)
    WifiPasswordCard(overview, actions)
    WifiHiddenCard(overview, actions)
    GuestCard(overview, actions)
}

@Composable
private fun NetworkCard(net: WifiNetworkView, reveal: Boolean, onReveal: () -> Unit) {
    val colors = StarTheme.colors
    Section(stringResource(if (net.guest) R.string.rw_guest_title else R.string.rw_main_title)) {
        if (net.ipv4.isNotEmpty()) KeyValueRow(stringResource(R.string.router_address), net.ipv4)
        if (net.guest) KeyValueRow(stringResource(R.string.rw_isolation), stringResource(if (net.clientIsolation) R.string.common_yes else R.string.common_no))
        for (b in net.bands) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(wifiBandText(b.band), style = MaterialTheme.typography.bodyMedium, color = colors.dish)
                if (b.hidden) StatusPill(Status.WARN, stringResource(R.string.rw_hidden_tag))
                if (b.disabled) StatusPill(Status.BAD, stringResource(R.string.rw_off_tag))
            }
            KeyValueRow(stringResource(R.string.rw_name), b.ssid.ifEmpty { "-" })
            KeyValueRow(stringResource(R.string.rw_security), stringResource(wifiAuthRes(b.auth)))
            if (b.auth.hasPassword) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { KeyValueRow(stringResource(R.string.rw_password), if (reveal) b.password ?: "-" else MASK) }
                    LowButton(stringResource(if (reveal) R.string.rw_hide else R.string.rw_show), onReveal)
                }
            }
        }
        if (net.bands.any { it.auth.hasPassword }) Hint(stringResource(R.string.rw_password_hint))
    }
}

/** The last value a section showed, kept across a failed read; plain (not state): it never triggers a recomposition. */
class LastSeen<T>(var value: T? = null)

/** A password field with its own show/hide button. Kept in `remember`, never saved with the screen's state. */
@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { LowButton(stringResource(if (show) R.string.rw_hide else R.string.rw_show), { show = !show }) },
        modifier = modifier,
    )
}

// ---- Name, one for both bands or one per band

@Composable
private fun WifiNameCard(o: WifiOverview, actions: RouterActions) {
    val main = o.main ?: return
    val b2 = main.band(WifiBand.RF_2GHZ)
    val b5 = main.band(WifiBand.RF_5GHZ)
    val canSplit = b2 != null && b5 != null
    var dialog by remember { mutableStateOf(false) }
    var separate by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }
    var n5 by remember { mutableStateOf("") }
    ControlCard(
        title = stringResource(R.string.rw_name_title),
        description = stringResource(R.string.rw_name_text),
        kinds = listOf(DishCallKind.ROUTER_SET_CONFIG),
        device = Device.ROUTER, tag = TAG_NAME,
    ) { enabled ->
        KeyValueRow(
            stringResource(R.string.controls_now),
            if (main.bands.isEmpty()) "-" else if (main.oneName) main.bands.first().ssid else main.bands.joinToString(" / ") { it.ssid },
        )
        KeyValueRow(stringResource(R.string.rw_name_mode), stringResource(if (main.oneName) R.string.rw_name_one else R.string.rw_name_split))
        PrimaryButton(stringResource(R.string.rw_name_button), {
                separate = canSplit && !main.oneName
                all = main.bands.first().ssid
                n2 = b2?.ssid ?: all
                n5 = b5?.ssid ?: all
                dialog = true
            }, enabled = enabled && !actions.busy && main.bands.isNotEmpty())
    }
    if (dialog) {
        val valid = if (separate) WifiInput.nameValid(n2) && WifiInput.nameValid(n5) else WifiInput.nameValid(all)
        val summary = if (separate) "$n2 / $n5" else all
        // An edit with an invalid name cannot be built: the dialog is then asked about the edit it will become, a name change.
        val nameEdits = { if (separate) listOf(WifiConfigEdit.SetNetworkName(n2, WifiBand.RF_2GHZ), WifiConfigEdit.SetNetworkName(n5, WifiBand.RF_5GHZ)) else listOf(WifiConfigEdit.SetNetworkName(all)) }
        ConfirmDialog(
            title = stringResource(R.string.rw_name_title),
            text = stringResource(R.string.rw_name_confirm, summary),
            confirmLabel = stringResource(R.string.rw_apply),
            risk = RouterCommand.SetConfig.riskOf(if (valid) nameEdits() else emptyList()),
            typed = true, word = stringResource(R.string.confirm_word_change), canConfirm = valid,
            onConfirm = {
                dialog = false
                val edits = nameEdits()
                actions.sendFromConfig(TAG_NAME) { base -> RouterCommand.SetConfig(base, edits) }
            },
            onDismiss = { dialog = false },
        ) {
            if (canSplit) {
                Row(Modifier.heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rw_name_separate), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = separate,
                        onCheckedChange = { on ->
                            separate = on
                            if (on) {
                                n2 = all
                                if (n5.isEmpty() || n5 == n2) n5 = WifiInput.withSuffix(all, "-5G")
                            } else {
                                all = n2
                            }
                        },
                    )
                }
            }
            if (separate) {
                NameField(n2, { n2 = it }, stringResource(R.string.rw_name_field_2))
                NameField(n5, { n5 = it }, stringResource(R.string.rw_name_field_5))
            } else {
                NameField(all, { all = it }, stringResource(R.string.rw_name_field))
            }
            if (!valid) StatusPill(Status.BAD, stringResource(R.string.rw_name_invalid))
        }
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(stringResource(R.string.rw_name_bytes, value.encodeToByteArray().size.toString(), WifiInput.NAME_MAX_BYTES.toString())) },
    )
}

// ---- Password

@Composable
private fun WifiPasswordCard(o: WifiOverview, actions: RouterActions) {
    val main = o.main ?: return
    var dialog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    ControlCard(
        title = stringResource(R.string.rw_pw_title),
        description = stringResource(R.string.rw_pw_text),
        kinds = listOf(DishCallKind.ROUTER_SET_CONFIG),
        device = Device.ROUTER, tag = TAG_PASSWORD,
    ) { enabled ->
        val open = main.bands.isNotEmpty() && main.bands.none { it.auth.hasPassword }
        if (open) StatusPill(Status.WARN, stringResource(R.string.rw_pw_open))
        PrimaryButton(stringResource(R.string.rw_pw_button), { password = ""; dialog = true }, enabled = enabled && !actions.busy && main.bands.isNotEmpty())
    }
    if (dialog) {
        val valid = WifiInput.passwordValid(password)
        ConfirmDialog(
            title = stringResource(R.string.rw_pw_title),
            text = stringResource(R.string.rw_pw_confirm),
            confirmLabel = stringResource(R.string.rw_apply),
            risk = RouterCommand.SetConfig.riskOf(if (valid) listOf(WifiConfigEdit.SetPassword(password)) else emptyList()),
            typed = true, word = stringResource(R.string.confirm_word_change), canConfirm = valid, secure = true,
            onConfirm = {
                dialog = false
                val pw = password
                password = ""
                actions.sendFromConfig(TAG_PASSWORD) { base -> RouterCommand.SetConfig(base, listOf(WifiConfigEdit.SetPassword(pw))) }
            },
            onDismiss = { dialog = false; password = "" },
        ) {
            PasswordField(password, { password = it }, stringResource(R.string.rw_pw_field), Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.rw_pw_rule, WifiInput.PASSWORD_MIN.toString(), WifiInput.PASSWORD_MAX.toString()),
                color = if (password.isEmpty() || valid) StarTheme.colors.muted else StarTheme.colors.bad, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ---- Hidden network

@Composable
private fun WifiHiddenCard(o: WifiOverview, actions: RouterActions) {
    val main = o.main ?: return
    var confirm by remember { mutableStateOf(false) }
    val hidden = main.hidden
    ControlCard(
        title = stringResource(R.string.rw_hidden_title),
        description = stringResource(R.string.rw_hidden_text),
        kinds = listOf(DishCallKind.ROUTER_SET_CONFIG),
        device = Device.ROUTER, tag = TAG_HIDDEN,
    ) { enabled ->
        KeyValueRow(stringResource(R.string.controls_now), stringResource(if (hidden) R.string.rw_hidden_now else R.string.rw_visible_now), status = if (hidden) Status.WARN else null)
        SecondaryButton(stringResource(if (hidden) R.string.rw_hidden_show_button else R.string.rw_hidden_hide_button), { confirm = true }, enabled = enabled && !actions.busy && main.bands.isNotEmpty())
    }
    if (confirm) {
        val edits = listOf(WifiConfigEdit.SetHidden(!hidden))
        ConfirmDialog(
            title = stringResource(R.string.rw_hidden_title),
            text = stringResource(if (hidden) R.string.rw_hidden_show_confirm else R.string.rw_hidden_hide_confirm),
            confirmLabel = stringResource(if (hidden) R.string.rw_hidden_show_button else R.string.rw_hidden_hide_button),
            risk = RouterCommand.SetConfig.riskOf(edits),
            onConfirm = {
                confirm = false
                actions.sendFromConfig(TAG_HIDDEN) { base -> RouterCommand.SetConfig(base, edits) }
            },
            onDismiss = { confirm = false },
        )
    }
}

// ---- Guest network

@Composable
private fun GuestCard(o: WifiOverview, actions: RouterActions) {
    var add by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val guest = o.guest
    ControlCard(
        title = stringResource(R.string.rw_guest_card_title),
        description = stringResource(R.string.rw_guest_text),
        kinds = listOf(DishCallKind.ROUTER_SET_CONFIG),
        device = Device.ROUTER, tag = TAG_GUEST,
    ) { enabled ->
        KeyValueRow(stringResource(R.string.controls_now), guest?.bands?.firstOrNull()?.ssid ?: stringResource(R.string.rw_guest_none))
        if (guest == null) {
            PrimaryButton(stringResource(R.string.rw_guest_add_button), { name = ""; password = ""; add = true }, enabled = enabled && !actions.busy)
        } else {
            SecondaryButton(stringResource(R.string.rw_guest_remove_button), { remove = true }, enabled = enabled && !actions.busy)
        }
    }
    if (add) {
        val valid = WifiInput.nameValid(name) && WifiInput.passwordValid(password)
        ConfirmDialog(
            title = stringResource(R.string.rw_guest_card_title),
            text = stringResource(R.string.rw_guest_add_confirm),
            confirmLabel = stringResource(R.string.rw_guest_add_button),
            risk = RouterCommand.SetConfig.riskOf(if (valid) listOf(WifiConfigEdit.AddGuestNetwork(name, password)) else emptyList()),
            canConfirm = valid, secure = true,
            onConfirm = {
                add = false
                val n = name
                val pw = password
                password = ""
                actions.sendFromConfig(TAG_GUEST) { base -> RouterCommand.SetConfig(base, listOf(WifiConfigEdit.AddGuestNetwork(n, pw))) }
            },
            onDismiss = { add = false; password = "" },
        ) {
            NameField(name, { name = it }, stringResource(R.string.rw_guest_name_field))
            PasswordField(password, { password = it }, stringResource(R.string.rw_pw_field), Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.rw_pw_rule, WifiInput.PASSWORD_MIN.toString(), WifiInput.PASSWORD_MAX.toString()),
                color = if (password.isEmpty() || WifiInput.passwordValid(password)) StarTheme.colors.muted else StarTheme.colors.bad, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (remove) {
        ConfirmDialog(
            title = stringResource(R.string.rw_guest_card_title),
            text = stringResource(R.string.rw_guest_remove_confirm),
            confirmLabel = stringResource(R.string.rw_guest_remove_button),
            risk = RouterCommand.SetConfig.riskOf(listOf(WifiConfigEdit.RemoveGuestNetwork)),
            onConfirm = {
                remove = false
                actions.sendFromConfig(TAG_GUEST) { base -> RouterCommand.SetConfig(base, listOf(WifiConfigEdit.RemoveGuestNetwork)) }
            },
            onDismiss = { remove = false },
        )
    }
}
