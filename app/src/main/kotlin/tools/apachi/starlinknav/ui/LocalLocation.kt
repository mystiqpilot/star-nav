package tools.apachi.starlinknav.ui

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.CallOutcome
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.LocalLocationBasis
import tools.apachi.starlinknav.core.dish.LocalLocationReading
import tools.apachi.starlinknav.core.dish.LocalLocationState
import tools.apachi.starlinknav.core.nav.LocationRefusal
import tools.apachi.starlinknav.service.CommandBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The words for the dish's "location on the local network" setting, shared by the Setup row on the Mock GPS page and the
 * control on Starlink > Controls: what the setting is (on / off / the dish answers anyway / can't tell), and what came of
 * the last attempt to switch it on. Both send the same command with the same tag, so they show the same last outcome.
 */

/** The tag the controls send the enable command with; one "last outcome" for both places. */
const val TAG_LOCAL_LOCATION = "local_location"

/** A few words for the state and the colour to show them in. */
@Composable
fun localLocationValue(r: LocalLocationReading?): Pair<String, Color> {
    val colors = StarTheme.colors
    return when (r?.state) {
        LocalLocationState.ON -> stringResource(R.string.controls_location_allowed) to colors.good
        LocalLocationState.OFF -> stringResource(R.string.controls_location_blocked) to colors.warn
        LocalLocationState.ANSWERS_ANYWAY -> stringResource(R.string.local_location_value_answers) to colors.good
        LocalLocationState.UNKNOWN, null -> stringResource(R.string.local_location_value_unknown) to colors.muted
    }
}

/** The setting was read from the dish and says plainly on or off: the value alone tells it, no explanation needed. */
val LocalLocationReading.isPlain: Boolean
    get() = settingRead && !planRestricted && !unimplemented && (state == LocalLocationState.ON || state == LocalLocationState.OFF)

/** A few words naming a refusal of `get_location`, for the status lines (the cards and the Setup row have whole sentences). */
@StringRes
fun refusalWordsRes(r: LocationRefusal): Int = when (r) {
    LocationRefusal.POLICY_DENIED -> R.string.refusal_policy_short
    LocationRefusal.UNIMPLEMENTED -> R.string.refusal_unimplemented_short
    LocationRefusal.NOT_ENABLED -> R.string.refusal_not_enabled_short
}

/** One sentence that says what the state means and how it was found; never claims more than the dish said. */
@Composable
fun localLocationSentence(r: LocalLocationReading): String = stringResource(
    when {
        r.planRestricted -> R.string.local_location_plan
        r.unimplemented -> R.string.local_location_unimplemented
        r.state == LocalLocationState.ON -> R.string.local_location_reads_on
        r.state == LocalLocationState.OFF && (r.settingRead || r.basis == LocalLocationBasis.DIAGNOSTICS) -> R.string.local_location_reads_off
        r.state == LocalLocationState.OFF -> R.string.local_location_off_inferred
        r.state == LocalLocationState.ANSWERS_ANYWAY && r.settingRead -> R.string.local_location_answers_off
        r.state == LocalLocationState.ANSWERS_ANYWAY -> R.string.local_location_answers_unread
        r.disabledNow -> R.string.setup_dish_location_disabled
        else -> R.string.local_location_unknown
    },
)

/**
 * What a command's outcome means for this control, in plain words. A refusal of the enable command says it may need the
 * Starlink app once: whether a Mini accepts this write from a phone on its Wi-Fi is not verified. Other outcomes (and the
 * other commands that share the tag, such as switching it off) use the generic sentence.
 */
@Composable
fun localLocationOutcomeSentence(kind: DishCallKind, outcome: CallOutcome): String {
    if (kind != DishCallKind.ALLOW_LOCAL_LOCATION) return outcomeSentence(outcome, Device.DISH)
    return when (outcome) {
        CallOutcome.OK -> stringResource(R.string.local_location_result_ok)
        CallOutcome.PERMISSION_DENIED -> stringResource(R.string.local_location_result_denied)
        CallOutcome.UNAUTHENTICATED -> stringResource(R.string.local_location_result_unauth)
        CallOutcome.UNIMPLEMENTED -> stringResource(R.string.local_location_result_unimplemented)
        else -> outcomeSentence(outcome, Device.DISH)
    }
}

/**
 * The last attempt to change this setting: from this run of the app when there was one ([CommandBus.last]), else (with
 * [showRemembered]) the one the capability book remembers from an earlier run. Nothing before the first attempt.
 */
@Composable
fun LocalLocationOutcomeLine(showRemembered: Boolean) {
    val last by CommandBus.last.collectAsStateWithLifecycle()
    val caps = rememberCapabilities()
    val sent = last[TAG_LOCAL_LOCATION]
    if (sent != null) {
        val status = when (sent.outcome) {
            CallOutcome.OK -> Status.OK
            CallOutcome.UNREACHABLE, CallOutcome.SENT_NO_ANSWER, CallOutcome.DISABLED_BY_POLICY -> Status.WARN
            else -> Status.BAD
        }
        StatusPill(status, "${timeOfDay(sent.atWallMs)} · ${localLocationOutcomeSentence(sent.kind, sent.outcome)}${outcomeDetail(sent.outcome, sent.message)}")
        return
    }
    if (!showRemembered) return
    val e = caps.last(DishCallKind.ALLOW_LOCAL_LOCATION) ?: return
    StatusPill(
        if (e.outcome == CallOutcome.OK) Status.OK else Status.NEUTRAL,
        stringResource(
            R.string.local_location_last_attempt, whenText(e.atMs),
            localLocationOutcomeSentence(DishCallKind.ALLOW_LOCAL_LOCATION, e.outcome) + outcomeDetail(e.outcome, e.message),
        ),
    )
}

/** Date and time in the app's language, for an attempt that may be days old. */
private fun whenText(ms: Long): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMdHm"), locale).format(Date(ms))
}
