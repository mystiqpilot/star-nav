package tools.apachi.starlinknav.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.DishClient
import tools.apachi.starlinknav.core.dish.RouterClient
import tools.apachi.starlinknav.core.dish.TransportMode
import tools.apachi.starlinknav.service.Devices
import tools.apachi.starlinknav.service.WifiBinding

/**
 * The Connection sub-tab of the Starlink page: where the dish and the router are (address, transport, Wi-Fi binding),
 * then the probe that checks what they answer. These were Settings; they sit with the devices they are about.
 */
@Composable
fun ConnectionScreen() {
    val settings = App.instance.settings
    val s by settings.flow.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Section(stringResource(R.string.config_dish_title)) {
            TextSetting(stringResource(R.string.config_dish_address), s.dishHost, KeyboardType.Uri, valid = DishClient::isValidAddress) { v ->
                settings.update { it.copy(dishHost = v.trim()) }
            }
            Text(stringResource(R.string.config_transport))
            TransportSelector(s.transport) { m -> settings.update { it.copy(transport = m) } }
            Hint(stringResource(R.string.config_transport_hint))
            SwitchSetting(stringResource(R.string.config_bind_wifi), s.bindToWifi) { v -> settings.update { it.copy(bindToWifi = v) } }
            Hint(stringResource(R.string.config_bind_wifi_hint))
            if (s.bindToWifi) VpnBindingNotice()
        }
        Section(stringResource(R.string.config_router_title)) {
            TextSetting(
                stringResource(R.string.config_router_address), s.routerHost, KeyboardType.Uri,
                valid = { it.isBlank() || RouterClient.isValidAddress(it.trim()) },
            ) { v -> settings.update { it.copy(routerHost = v.trim()) } }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            // Asked every 3 s, but only while the app is in front.
            val gateway by produceState<String?>(null, lifecycle) {
                val devices = Devices.shared(ctx)
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        value = withContext(Dispatchers.IO) {
                            val wifi = devices.wifi()
                            // Keeps the VPN notice above current while nothing else opens a socket.
                            if (wifi != null) runCatching { WifiBinding.mayBind(wifi) }
                            devices.gateway(wifi)
                        }
                        delay(3_000)
                    }
                }
            }
            KeyValueRow(stringResource(R.string.config_router_gateway), gateway ?: stringResource(R.string.router_address_none))
            Hint(stringResource(R.string.config_router_hint))
        }
        ProbeSection()
    }
}

/**
 * auto / gRPC-Web / gRPC. Three segments in a row while the labels fit; at a large font scale "gRPC-Web" no longer fits its
 * segment (it was cut to "gRPC-Wel" at 1.5), so the choices stack as radio rows then.
 */
@Composable
private fun TransportSelector(current: TransportMode, onSelect: (TransportMode) -> Unit) {
    val modes = TransportMode.entries
    val autoLabel = stringResource(R.string.config_transport_auto)
    fun label(m: TransportMode) = when (m) { TransportMode.AUTO -> autoLabel; TransportMode.WEB -> "gRPC-Web"; TransportMode.H2C -> "gRPC" }
    if (LocalDensity.current.fontScale > STACK_FONT_SCALE) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            for (m in modes) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Space.touch).selectable(selected = current == m, role = Role.RadioButton, onClick = { onSelect(m) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current == m, onClick = null)
                    Text(label(m), Modifier.padding(start = Space.m))
                }
            }
        }
    } else {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = current == m,
                    onClick = { onSelect(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    // No check mark: with it the selected label no longer fits one line.
                    icon = {},
                ) { Text(label(m), maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** Above this font scale the three transport segments stack (1.3 still fits, 1.5 does not). */
private const val STACK_FONT_SCALE = 1.4f
