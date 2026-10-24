package tools.apachi.starlinknav.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.CallOutcome
import tools.apachi.starlinknav.core.dish.CallTarget
import tools.apachi.starlinknav.core.dish.CapabilityEntry
import tools.apachi.starlinknav.core.dish.CommandRisk
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.DishCallResult
import tools.apachi.starlinknav.service.AppErrors
import tools.apachi.starlinknav.service.CapabilityStore
import tools.apachi.starlinknav.service.CommandBus
import tools.apachi.starlinknav.service.DeviceCall
import tools.apachi.starlinknav.service.FeedSample
import tools.apachi.starlinknav.service.SentCommand
import tools.apachi.starlinknav.service.WifiBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Parts the Dish sub-tabs share (and the Router sub-tab will): the confirmation dialog, the control card with its
 * "last outcome" line and the grey-out from the capability book, the command sender, and the "no answer" note.
 */

/** Which device a control talks to; picks the words ("the dish ..." / "the router ..."). */
enum class Device { DISH, ROUTER }

/** Time of day in the app's language ("14:03:07"), for the last outcome and the event log. */
fun timeOfDay(ms: Long): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "Hms"), locale).format(Date(ms))
}

/**
 * Sends commands from a screen. The command itself runs in the [CommandBus]'s scope, so it is logged, tracked and
 * remembered even when this screen goes away while the dish is still answering (a reboot takes its own card off the
 * screen within a second); the screen only waits for the result. [busy] is true while a command for this device is on
 * its way, whichever screen sent it, so a new screen cannot send a second one on top of it.
 */
@Stable
class CommandSender(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pending: State<Set<CallTarget>>,
    private val target: CallTarget,
) {
    val busy: Boolean get() = target in pending.value

    /** Sends [call] through the [CommandBus] (the service logs and tracks it when it runs); [then] gets the result if this screen is still there. */
    fun send(call: DeviceCall, tag: String = call.kind.label, then: (DishCallResult) -> Unit = {}) {
        val result = CommandBus.launch(context, call, tag) ?: return
        scope.launch { then(result.await()) }
    }
}

@Composable
fun rememberSender(device: Device = Device.DISH): CommandSender {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val pending = CommandBus.pending.collectAsStateWithLifecycle()
    val target = if (device == Device.DISH) CallTarget.DISH else CallTarget.ROUTER
    return remember(context, scope, target) { CommandSender(context, scope, pending, target) }
}

/**
 * Asks before a command goes out and says what will happen. A [CommandRisk.DESTRUCTIVE] command (or any with [typed])
 * also needs [word] typed (the dialog names it), so a stray tap cannot wipe a device or cut the phone off; [acknowledge]
 * adds a box that must be ticked as well (bypass mode). [content] holds the inputs of a dialog that also edits
 * something (a new Wi-Fi name); [canConfirm] keeps the button off until they are valid.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    risk: CommandRisk,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    word: String = stringResource(R.string.confirm_word_reset),
    typed: Boolean = risk == CommandRisk.DESTRUCTIVE,
    acknowledge: String? = null,
    canConfirm: Boolean = true,
    /** The dialog holds a secret (a Wi-Fi password being typed): its window cannot be captured (screenshot, Recents). */
    secure: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var typedText by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    val ok = canConfirm && (!typed || typedText.trim().equals(word, ignoreCase = true)) && (acknowledge == null || acknowledged)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(text)
                content()
                if (acknowledge != null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable { acknowledged = !acknowledged }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                        Text(acknowledge, Modifier.weight(1f))
                    }
                }
                if (typed) {
                    Text(stringResource(R.string.confirm_type_word, word), color = StarTheme.colors.bad, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = typedText,
                        onValueChange = { typedText = it },
                        singleLine = true,
                        // No autocorrect: the keyboard turned "CHANGE" into "CHANGED" when the field lost focus.
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            // A button that opens a dialog ends in "…"; the one that confirms does not.
            LowButton(
                confirmLabel.trimEnd('…', '.', ' '), onConfirm, enabled = ok,
                color = when {
                    !ok || risk == CommandRisk.REVERSIBLE -> Color.Unspecified
                    risk == CommandRisk.DESTRUCTIVE -> StarTheme.colors.bad
                    else -> StarTheme.colors.warn
                },
            )
        },
        dismissButton = { LowButton(stringResource(R.string.common_cancel), onDismiss) },
    )
}

/** What a call came to, in one plain sentence (no time, no device words beyond "the dish" / "the router"). */
@Composable
fun outcomeSentence(outcome: CallOutcome, device: Device): String {
    val dish = device == Device.DISH
    return stringResource(
        when (outcome) {
            CallOutcome.OK -> if (dish) R.string.outcome_ok_dish else R.string.outcome_ok_router
            CallOutcome.UNIMPLEMENTED -> if (dish) R.string.outcome_unimplemented_dish else R.string.outcome_unimplemented_router
            CallOutcome.PERMISSION_DENIED -> if (dish) R.string.outcome_denied_dish else R.string.outcome_denied_router
            CallOutcome.UNAUTHENTICATED -> if (dish) R.string.outcome_unauth_dish else R.string.outcome_unauth_router
            CallOutcome.DISABLED_BY_POLICY -> if (dish) R.string.outcome_policy_dish else R.string.outcome_policy_router
            CallOutcome.PLAN_RESTRICTED -> if (dish) R.string.outcome_plan_dish else R.string.outcome_plan_router
            CallOutcome.UNREACHABLE -> if (dish) R.string.outcome_unreachable_dish else R.string.outcome_unreachable_router
            CallOutcome.SENT_NO_ANSWER -> if (dish) R.string.outcome_no_answer_dish else R.string.outcome_no_answer_router
            CallOutcome.OTHER -> if (dish) R.string.outcome_other_dish else R.string.outcome_other_router
        },
    )
}

/** The device's own words as shown: the failures the app makes itself ([AppErrors]) get a sentence in the app's language. */
@Composable
fun deviceMessage(message: String): String = when (message) {
    AppErrors.NO_ROUTER_ADDRESS -> stringResource(R.string.err_no_router_address)
    else -> message
}

/**
 * The device's own words after the sentence, where they add something: not for a plain refusal, which the sentence
 * already says (the dish's "Permission denied" would repeat it), nor for success.
 */
@Composable
fun outcomeDetail(outcome: CallOutcome, message: String): String = when {
    message.isBlank() -> ""
    outcome == CallOutcome.OK || outcome == CallOutcome.PERMISSION_DENIED || outcome == CallOutcome.UNAUTHENTICATED -> ""
    else -> " — ${deviceMessage(message)}"
}

/** "14:03:07 · Refused by the dish: ... — the dish's own words" */
@Composable
fun SentCommandLine(c: SentCommand, device: Device) {
    val status = when (c.outcome) {
        CallOutcome.OK -> Status.OK
        CallOutcome.UNREACHABLE, CallOutcome.SENT_NO_ANSWER, CallOutcome.DISABLED_BY_POLICY -> Status.WARN
        else -> Status.BAD
    }
    StatusLine(status, "${timeOfDay(c.atWallMs)} · ${outcomeSentence(c.outcome, device)}${outcomeDetail(c.outcome, c.message)}")
}

/** The last result of the control that sent with [tag] in this run of the app; nothing before the first send. */
@Composable
fun CommandOutcomeLine(tag: String, device: Device = Device.DISH) {
    val last by CommandBus.last.collectAsStateWithLifecycle()
    last[tag]?.let { SentCommandLine(it, device) }
}

/** The capability book of [device] as Compose state: which calls it said it will not do. */
@Stable
class Capabilities(private val entries: (DishCallKind) -> CapabilityEntry?) {
    /** The remembered answer when the device will not do [kind] for this client (not implemented, refused, not in the plan). */
    fun unavailable(kind: DishCallKind): CapabilityEntry? = entries(kind)?.takeIf { it.outcome.isUnavailable }

    /** The remembered answer to [kind] of any kind (success too), for a control that shows its last attempt after the app restarted. */
    fun last(kind: DishCallKind): CapabilityEntry? = entries(kind)
}

@Composable
fun rememberCapabilities(device: Device = Device.DISH): Capabilities {
    val book by CapabilityStore.book.collectAsStateWithLifecycle()
    val key by (if (device == Device.DISH) CapabilityStore.dishKey else CapabilityStore.routerKey).collectAsStateWithLifecycle()
    return remember(book, key) { Capabilities { kind -> book.last(key, kind) } }
}

/**
 * A card for one control: what it does, its current state ([content] draws it and the buttons; the buttons are
 * disabled when the device said it will not do this for this client), and the last outcome of the control.
 */
@Composable
fun ControlCard(
    title: String,
    description: String,
    kinds: List<DishCallKind>,
    modifier: Modifier = Modifier,
    device: Device = Device.DISH,
    /** The tag its sends use ([CommandSender.send]); its last outcome shows under the buttons. Null = none. */
    tag: String? = null,
    /** The words for a remembered refusal when the generic outcome sentence is not the honest one for this control. */
    unavailableSentence: (@Composable (CapabilityEntry) -> String)? = null,
    content: @Composable ColumnScope.(enabled: Boolean) -> Unit,
) {
    val caps = rememberCapabilities(device)
    val blocked = kinds.firstNotNullOfOrNull { caps.unavailable(it) }
    Section(title, modifier) {
        Hint(description)
        if (blocked != null) {
            val sentence = unavailableSentence?.invoke(blocked) ?: (outcomeSentence(blocked.outcome, device) + outcomeDetail(blocked.outcome, blocked.message))
            StatusPill(Status.NEUTRAL, stringResource(R.string.controls_unavailable, sentence))
        }
        content(blocked == null)
        if (tag != null) CommandOutcomeLine(tag, device)
    }
}

/**
 * Shown where a read has no answer: still waiting, or why the device did not answer. A device that cannot be reached
 * gets the hint to check the Wi-Fi.
 */
@Composable
fun FeedProblem(sample: FeedSample?, device: Device = Device.DISH, address: String? = null) {
    Section(stringResource(if (device == Device.DISH) R.string.dish_problem_title else R.string.router_problem_title)) {
        if (sample == null) {
            StateView(UiState.Loading(stringResource(R.string.dish_waiting)))
            return@Section
        }
        val r = sample.result
        val message = if (r.outcome == CallOutcome.UNREACHABLE) {
            stringResource(if (device == Device.DISH) R.string.dish_unreachable else R.string.router_unreachable)
        } else {
            outcomeSentence(r.outcome, device)
        }
        val detail = (r.grpcMessage ?: r.error)?.takeIf { it.isNotBlank() }
        StateView(
            UiState.Error(Status.WARN, message, detail?.let { deviceMessage(it).take(240) }, action = {
                if (r.outcome == CallOutcome.UNREACHABLE) {
                    Hint(stringResource(R.string.dish_unreachable_hint, address ?: "-"))
                    VpnBindingNotice()
                }
            }),
        )
    }
}

/** Says that a VPN keeps dish and router sockets off the Wi-Fi network ([WifiBinding.refusedByVpn]); nothing otherwise. */
@Composable
fun VpnBindingNotice() {
    val refused by WifiBinding.refusedByVpn.collectAsStateWithLifecycle()
    if (refused) StatusPill(Status.WARN, stringResource(R.string.wifi_binding_vpn_refused))
}

/** The decoded answer of [kind] when it is a good one. */
fun FeedSample?.okResponse() = this?.result?.takeIf { it.ok }?.response

/** The device a call kind goes to, for the words. */
fun DishCallKind.device(): Device = if (target == CallTarget.ROUTER) Device.ROUTER else Device.DISH
