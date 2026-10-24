package tools.apachi.starlinknav.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.DishProbe
import tools.apachi.starlinknav.service.CapabilityStore
import tools.apachi.starlinknav.service.DishClients
import tools.apachi.starlinknav.service.Devices
import tools.apachi.starlinknav.service.RouterClients
import tools.apachi.starlinknav.service.RouterSource

/** What one probe run found, kept in English like the report it feeds. */
private class ProbeRun(
    val dish: List<DishCallResult>,
    val dishEndpoint: String,
    val router: List<DishCallResult>,
    val routerEndpoint: String?,
    /** No router address (not on the Starlink Wi-Fi, none typed): the router was not probed. */
    val routerSkipped: Boolean,
    /** What answers on the router's extra port 9005, one line per transport. */
    val survey: List<String>,
)

/**
 * The Probe card of the Connection sub-tab: every read-only call of the dish and, when its address is known, of the router, once, with the
 * outcome class and the device's own words, as a report to copy. Sends no command. Router passwords, keys and secrets
 * never reach the report (the results are redacted). Every outcome also updates the capability book.
 */
@Composable
fun ProbeSection() {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    var run by remember { mutableStateOf<ProbeRun?>(null) }
    var running by remember { mutableStateOf(false) }
    var coords by rememberSaveable { mutableStateOf(false) }
    var ids by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Section(stringResource(R.string.dish_tab_probe)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            PrimaryButton(stringResource(if (running) R.string.probe_running else R.string.probe_run), {
                scope.launch {
                    running = true
                    val s = settings
                    val result = withContext(Dispatchers.IO) {
                        val devices = Devices.shared(ctx)
                        val wifi = devices.wifi()
                        val client = devices.dish(s, wifi)
                        val dish = DishProbe.run(client, stopWhenUnreachable = true)
                        val router = devices.router(s, wifi)
                        val routerResults = router?.let { DishProbe.runRouter(it.client, stopWhenUnreachable = true) }.orEmpty()
                        val survey = if (router != null && routerResults.any { it.httpStatus != 0 }) {
                            val (host, _) = tools.apachi.starlinknav.core.dish.RouterClient.parseAddress(router.target.address.orEmpty())
                            runCatching { DishProbe.surveyRouterPort(RouterClients.http(devices.routerNetwork(router.target, s, wifi)), host) }.getOrDefault(emptyList())
                        } else {
                            emptyList()
                        }
                        CapabilityStore.recordAll(dish + routerResults)
                        CapabilityStore.flush()
                        ProbeRun(
                            dish, "${client.endpoint} via ${DishClients.describe(s, wifi)}", routerResults,
                            router?.let { "${it.client.endpoint} (${if (it.target.source == RouterSource.MANUAL) "typed under Connection" else "Wi-Fi gateway"})" },
                            routerSkipped = router == null, survey = survey,
                        )
                    }
                    run = result
                    running = false
                }
            }, enabled = !running)
        }
        Row(Modifier.heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.probe_include_coordinates), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = coords, onCheckedChange = { coords = it })
        }
        Row(Modifier.heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.probe_include_ids), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = ids, onCheckedChange = { ids = it })
        }
        Hint(stringResource(if (ids) R.string.probe_ids_on else R.string.probe_ids_off))
        val r = run
        // The app's dish client stamps calls with elapsedRealtimeNanos.
        val report = r?.let {
            DishProbe.report(
                it.dish, it.dishEndpoint, coords, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtimeNanos(),
                routerResults = it.router, routerEndpoint = it.routerEndpoint, includeIdentifiers = ids,
            ) + it.survey.joinToString("\n", prefix = if (it.survey.isEmpty()) "" else "\n")
        }
        if (report == null) {
            // The source names are identifiers: they stay as they are in every language.
            Hint(stringResource(R.string.probe_hint, "AUTO, GPS, STARLINK, GNC_FUSED"))
        } else {
            if (r.routerSkipped) Hint(stringResource(R.string.probe_router_skipped))
            SecondaryButton(stringResource(R.string.probe_copy_report), {
                val clip = ClipData.newPlainText(resources.getString(R.string.probe_clip_label), report)
                // With a position or identifiers in it: no clipboard preview, and keyboards keep it out of their history.
                // (Android 13 and later; before it the clipboard has no preview and no such flag.)
                if ((coords || ids) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                }
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            })
            SelectionContainer {
                Text(
                    report,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    lineHeight = 14.sp,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}
