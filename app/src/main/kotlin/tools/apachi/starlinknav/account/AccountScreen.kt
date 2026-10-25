package tools.apachi.starlinknav.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tools.apachi.starlinknav.BuildConfig
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.account.CloudOutcome
import tools.apachi.starlinknav.core.account.Ownership
import tools.apachi.starlinknav.core.dish.LocalLocationState
import tools.apachi.starlinknav.service.CapabilityStore
import tools.apachi.starlinknav.service.CommandBus
import tools.apachi.starlinknav.ui.ActionRow
import tools.apachi.starlinknav.ui.Hint
import tools.apachi.starlinknav.ui.PrimaryButton
import tools.apachi.starlinknav.ui.SecondaryButton
import tools.apachi.starlinknav.ui.SecureWindow
import tools.apachi.starlinknav.ui.Space
import tools.apachi.starlinknav.ui.StateView
import tools.apachi.starlinknav.ui.Status
import tools.apachi.starlinknav.ui.StatusLine
import tools.apachi.starlinknav.ui.stepLabel
import tools.apachi.starlinknav.ui.UiState
import tools.apachi.starlinknav.ui.TAG_LOCAL_LOCATION

/*
 * The screens of the opt-in "enable through my Starlink account" operation, laid over the whole app (see AppRoot). The
 * operation starts from a tap on a button (AccountEnableButton) and shows, in this order and no other: the consent screen,
 * Starlink's own sign-in page, a progress line, the result. `StarlinkAccountManager` decides the order; nothing here reaches
 * Starlink by itself.
 */

/**
 * The entry point: "Enable via my Starlink account". [emphasized] when the dish has just refused the change over the local
 * network; [step] numbers it among the buttons of a setup step. A tap only reads the dish's id over the LAN and opens the
 * consent screen; nothing goes to Starlink yet.
 */
@Composable
fun AccountEnableButton(emphasized: Boolean, enabled: Boolean = true, step: Int? = null) {
    val ctx = LocalContext.current
    val busy by StarlinkAccountManager.ui.collectAsStateWithLifecycle()
    val on = enabled && busy.stage == AccountStage.IDLE
    val label = if (step != null) stepLabel(step, R.string.account_enable_button) else stringResource(R.string.account_enable_button)
    if (emphasized) {
        PrimaryButton(label, { StarlinkAccountManager.open(ctx) }, enabled = on)
    } else {
        SecondaryButton(label, { StarlinkAccountManager.open(ctx) }, enabled = on)
    }
}

/** Shown over the app while an operation is going on; draws nothing when idle. */
@Composable
fun AccountOverlay() {
    val ctx = LocalContext.current
    val ui by StarlinkAccountManager.ui.collectAsStateWithLifecycle()
    if (ui.stage == AccountStage.IDLE) return
    // Back leaves the operation where it can be left; while the calls run it is ignored.
    BackHandler { StarlinkAccountManager.cancel(ctx) }
    // The sign-in page holds the password being typed: keep it out of screenshots, screen recordings and Recents (release
    // builds; a debug build leaves it capturable so the page can be checked on an emulator).
    SecureWindow(secure = ui.stage == AccountStage.SIGN_IN && !BuildConfig.DEBUG)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            when (ui.stage) {
                AccountStage.READING_DISH -> Progress(stringResource(R.string.account_reading_dish))
                AccountStage.NO_DISH -> Message(stringResource(R.string.account_no_dish), close = { StarlinkAccountManager.cancel(ctx) })
                AccountStage.CONSENT -> Consent()
                AccountStage.SIGN_IN -> SignIn()
                AccountStage.WORKING -> Progress(stringResource(R.string.account_working))
                AccountStage.CHECKING -> Progress(stringResource(R.string.account_checking))
                AccountStage.DONE -> Done(ui)
                AccountStage.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun Progress(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StateView(UiState.Loading(text)) }
}

@Composable
private fun Message(text: String, close: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.l, Alignment.CenterVertically)) {
        Text(text)
        PrimaryButton(stringResource(R.string.account_close), close)
    }
}

/** The consent: what happens, what the app sees and does not, the only cloud contact, the unofficial interface. */
@Composable
private fun Consent() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(stringResource(R.string.account_consent_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.account_consent_1))
        Text(stringResource(R.string.account_consent_2))
        Text(stringResource(R.string.account_consent_3))
        StatusLine(Status.WARN, stringResource(R.string.account_consent_4))
        ActionRow {
            PrimaryButton(stringResource(R.string.account_consent_continue), { StarlinkAccountManager.accept(ctx) })
            SecondaryButton(stringResource(R.string.common_cancel), { StarlinkAccountManager.cancel(ctx) })
        }
    }
}

/** Starlink's own sign-in page. The buttons are the app's; the page is Starlink's. */
@Composable
private fun SignIn() {
    val ctx = LocalContext.current
    var notYet by remember { mutableStateOf(false) }
    val page = remember { StarlinkSignInPage(ctx) { StarlinkAccountManager.onSignedIn(it) } }
    DisposableEffect(page) { onDispose { page.dispose() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.account_signin_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // Safety valve if the page does not announce the end of the sign-in by itself.
            SecondaryButton(stringResource(R.string.account_signin_done), { notYet = !page.captureNow() })
            SecondaryButton(stringResource(R.string.common_cancel), { StarlinkAccountManager.cancel(ctx) })
        }
        Box(Modifier.padding(horizontal = Space.m)) {
            Hint(stringResource(if (notYet) R.string.account_signin_not_yet else R.string.account_signin_hint))
        }
        AndroidView(factory = { page.view }, modifier = Modifier.weight(1f).fillMaxWidth().padding(top = Space.s))
    }
}

@Composable
private fun Done(ui: AccountUi) {
    val result = ui.result
    val reading = ui.reading
    val on = reading?.state == LocalLocationState.ON || reading?.state == LocalLocationState.ANSWERS_ANYWAY
    // The setting is on now whichever route did it: the old "refused" memory of the LAN attempt no longer applies.
    LaunchedEffect(on) {
        if (on) {
            CapabilityStore.forgetDish()
            CommandBus.forgetLast(TAG_LOCAL_LOCATION)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(stringResource(R.string.account_consent_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        result?.email?.let { Hint(stringResource(R.string.account_signed_in_as, it)) }
        val outcome = result?.outcome
        val (text, status) = when (outcome) {
            is CloudOutcome.Ok -> stringResource(R.string.account_result_ok) to Status.OK
            is CloudOutcome.SessionExpired -> stringResource(R.string.account_result_session, outcome.detail) to Status.BAD
            is CloudOutcome.DeviceOffline -> stringResource(R.string.account_result_offline) to Status.WARN
            is CloudOutcome.Refused -> stringResource(R.string.account_result_refused, refusalText(outcome)) to Status.BAD
            is CloudOutcome.NoAnswer -> stringResource(R.string.account_result_noanswer) to Status.WARN
            is CloudOutcome.Other -> stringResource(R.string.account_result_other, outcome.detail) to Status.BAD
            CloudOutcome.InvalidTarget -> stringResource(R.string.account_result_invalid) to Status.BAD
            null -> "" to Status.NEUTRAL
        }
        if (outcome != null) StatusLine(status, text)
        if (result?.ownership == Ownership.MISMATCH) StatusLine(Status.WARN, stringResource(R.string.account_owner_mismatch))
        if (ui.result?.outcome is CloudOutcome.Ok || ui.result?.outcome is CloudOutcome.NoAnswer) {
            when {
                on -> StatusLine(Status.OK, stringResource(R.string.account_reread_on))
                reading?.state == LocalLocationState.OFF -> StatusLine(Status.WARN, stringResource(R.string.account_reread_not_yet))
                else -> StatusLine(Status.NEUTRAL, stringResource(R.string.account_reread_unknown))
            }
        }
        PrimaryButton(stringResource(R.string.account_close), { StarlinkAccountManager.dismiss() })
    }
}

private fun refusalText(r: CloudOutcome.Refused): String = r.message.ifEmpty { "gRPC ${r.grpcStatus ?: "-"}" }
