package tools.apachi.starlinknav.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.CallOutcome
import tools.apachi.starlinknav.core.dish.CallTarget
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.core.dish.RouterCommand
import tools.apachi.starlinknav.core.dish.RouterConfigRead
import tools.apachi.starlinknav.core.dish.WifiAfterwards
import tools.apachi.starlinknav.service.CommandBus
import tools.apachi.starlinknav.service.DeviceCall
import tools.apachi.starlinknav.service.DishFeed

/** A line shown above the router's Wi-Fi and control cards after something went through, or did not start. */
enum class RouterNotice(@StringRes val text: Int, val offersWifiSettings: Boolean = false) {
    /** The name or password changed: join the network again, under its new name or with its new password. */
    JOIN_AGAIN(R.string.rnotice_join_again, offersWifiSettings = true),

    /** A change that can drop the phone from the Wi-Fi (DNS, LAN subnet, hidden name, a band off): rejoin if it did not by itself. */
    MAY_DROP(R.string.rnotice_may_drop, offersWifiSettings = true),

    /** Bypass mode is on: the Wi-Fi is gone and only the reset button on the Mini brings it back. */
    WIFI_OFF(R.string.rnotice_wifi_off),

    /** The router's current Wi-Fi settings could not be read first, so nothing was sent. */
    NO_CONFIG(R.string.rnotice_no_config),

    /** The change is not valid for the router's current settings (for example it has no guest network), so nothing was sent. */
    REFUSED_LOCALLY(R.string.rnotice_refused_locally),
    ;

    /** The change went through (it is not a "nothing was sent" notice). */
    val afterChange: Boolean get() = this != NO_CONFIG && this != REFUSED_LOCALLY

    companion object {
        /** The notice for a change that went through; null for one that leaves the phone alone. */
        fun after(command: RouterCommand): RouterNotice? = when ((command as? RouterCommand.SetConfig)?.afterwards) {
            WifiAfterwards.WIFI_OFF -> WIFI_OFF
            WifiAfterwards.JOIN_AGAIN_NEW_CREDENTIALS -> JOIN_AGAIN
            WifiAfterwards.MAY_DROP -> MAY_DROP
            WifiAfterwards.NOTHING, null -> null
        }
    }
}

/**
 * Sends the router's commands for the Router sub-tab, one at a time, through the [CommandBus] (so the service logs them
 * when it runs, with the router's answer redacted). A change of the Wi-Fi settings never starts from what is on the
 * screen: it first reads the router's config again (unredacted, in memory), builds the command from that read
 * ([RouterConfigRead]) and sends it, so a change made elsewhere meanwhile is not overwritten. Afterwards the screen is
 * refreshed from the router. The command itself outlives this screen (the bus runs it); the steps around it do not.
 */
@Stable
class RouterActions(
    private val context: Context,
    private val feed: DishFeed,
    private val scope: CoroutineScope,
    private val pending: State<Set<CallTarget>>,
) {
    private var running by mutableStateOf(false)

    /** A router command of this screen, or of an earlier one that is gone, is on its way. */
    val busy: Boolean get() = running || CallTarget.ROUTER in pending.value

    var notice by mutableStateOf<RouterNotice?>(null)
        private set

    fun dismissNotice() {
        notice = null
    }

    /** A command that needs nothing from the router's config (reboot, update check, speed test, factory reset). */
    fun send(call: DeviceCall, tag: String, then: (DishCallResult) -> Unit = {}) = start {
        CommandBus.launch(context, call, tag)?.await()?.also(then)
    }

    /**
     * A command built from a fresh read of the router's config. [build] may refuse (an `IllegalArgumentException`, for
     * example "no guest network"): nothing is sent then. What the phone has to do afterwards comes from the command built
     * ([RouterNotice.after]).
     */
    fun sendFromConfig(tag: String, then: (DishCallResult) -> Unit = {}, build: (RouterConfigRead) -> RouterCommand) = start {
        val base = freshConfig()
        if (base == null) {
            notice = RouterNotice.NO_CONFIG
            return@start null
        }
        val command = try {
            build(base)
        } catch (e: IllegalArgumentException) {
            notice = RouterNotice.REFUSED_LOCALLY
            return@start null
        }
        CommandBus.launch(context, DeviceCall.of(command), tag)?.await()?.also { result ->
            if (result.ok || result.outcome == CallOutcome.SENT_NO_ANSWER) notice = RouterNotice.after(command)
            then(result)
        }
    }

    /**
     * The router's config as it is now, unredacted: from `wifi_get_config`, else from the status, which carries the same config
     * (the screens show it from there when the dedicated call is not answered, so a write must be able to start from it too).
     */
    private suspend fun freshConfig(): RouterConfigRead? =
        RouterConfigRead.of(feed.readNow(DishCallKind.ROUTER_CONFIG).result) ?: RouterConfigRead.of(feed.readNow(DishCallKind.ROUTER_STATUS).result)

    private fun start(block: suspend () -> DishCallResult?) {
        if (busy) return
        running = true
        notice = notice?.takeIf { it.afterChange }
        scope.launch {
            try {
                val result = block()
                if (result != null && result.ok) refresh()
            } finally {
                running = false
            }
        }
    }

    /** Reads what a change may have moved, after the router had a moment to apply it. */
    private suspend fun refresh() {
        delay(1_000)
        for (kind in listOf(DishCallKind.ROUTER_CONFIG, DishCallKind.ROUTER_STATUS, DishCallKind.ROUTER_CLIENTS)) feed.readNow(kind)
    }
}

@Composable
fun rememberRouterActions(feed: DishFeed): RouterActions {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val pending = CommandBus.pending.collectAsStateWithLifecycle()
    return remember(feed, scope) { RouterActions(context, feed, scope, pending) }
}

/** The line of [RouterActions.notice], with the way back to the Wi-Fi settings where that helps. */
@Composable
fun RouterNoticeCard(actions: RouterActions) {
    val notice = actions.notice ?: return
    val context = LocalContext.current
    Section(stringResource(R.string.rnotice_title)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(notice.text), style = MaterialTheme.typography.bodyMedium, color = if (notice.offersWifiSettings) StarTheme.colors.warn else StarTheme.colors.bad)
            if (notice.offersWifiSettings) {
                SecondaryButton(stringResource(R.string.rnotice_open_wifi), { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
            }
            LowButton(stringResource(R.string.common_dismiss), actions::dismissNotice)
        }
    }
}
