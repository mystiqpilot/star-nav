package tools.apachi.starlinknav.account

import android.content.Context
import android.webkit.WebView
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.apachi.starlinknav.BuildConfig
import tools.apachi.starlinknav.core.account.AccountEnableFlow
import tools.apachi.starlinknav.core.account.AccountResult
import tools.apachi.starlinknav.core.account.CloudEndpoints
import tools.apachi.starlinknav.core.account.CloudOutcome
import tools.apachi.starlinknav.core.account.Ownership
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.LocalLocationReading
import tools.apachi.starlinknav.service.Devices
import tools.apachi.starlinknav.setup.SetupChecks
import tools.apachi.starlinknav.ui.SetupState

enum class AccountStage {
    /** Nothing going on; the overlay is not shown. */
    IDLE,

    /** Asking the dish, over the local network, for its own id (a read). */
    READING_DISH,

    /** The dish gave no usable id: nothing can start. */
    NO_DISH,

    /** The consent screen. Nothing has been sent to Starlink. */
    CONSENT,

    /** Starlink's sign-in page is on screen. */
    SIGN_IN,

    /** Signed in: the cloud calls are running. */
    WORKING,

    /** The write is over: the dish is asked, over the local network, what it reports now. */
    CHECKING,

    /** The result is on screen. */
    DONE,
}

/** What the overlay shows. [result] holds the e-mail of the account for the screen only ([AccountResult.toString] hides it). */
data class AccountUi(
    val stage: AccountStage = AccountStage.IDLE,
    val result: AccountResult? = null,
    val reading: LocalLocationReading? = null,
)

/**
 * The opt-in "enable local location through my Starlink account" operation: the Android half. The sequence and the cloud
 * calls are `core`'s [AccountEnableFlow] (tested there; the cloud cannot be reached without the consent); this object adds the
 * dish's id (a LAN read), the sign-in WebView's life, the wiping of the WebView's data, and the dish re-read afterwards.
 *
 * This is the app's only contact with Starlink's cloud. It starts only from a tap on an entry point (the buttons on the Mock GPS
 * checklist and on Starlink > Controls), never at startup and never by itself. Nothing persists: the cookies live in the flow's
 * in-memory jar and are cleared when the operation ends; the WebView's own data is wiped as soon as the cookies were taken,
 * and again if the operation is left. No state here is saved, and nothing is logged.
 */
object StarlinkAccountManager {
    private val flow = AccountEnableFlow(CloudEndpoints.PRODUCTION, "StarNav/${BuildConfig.VERSION_NAME} (own-dish interop)")
    private val state = MutableStateFlow(AccountUi())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var signedIn: CompletableDeferred<String>? = null

    val ui: StateFlow<AccountUi> = state

    /** An entry point was tapped: read the dish's own id over the LAN, then show the consent screen (still nothing sent to Starlink). */
    fun open(context: Context) {
        if (state.value.stage != AccountStage.IDLE) return
        val app = context.applicationContext
        state.value = AccountUi(AccountStage.READING_DISH)
        job = scope.launch {
            val id = withContext(Dispatchers.IO) { readDishId(app) }
            state.value = AccountUi(if (id != null && flow.begin(id)) AccountStage.CONSENT else AccountStage.NO_DISH)
        }
    }

    /** The user agreed on the consent screen: Starlink's sign-in page opens. The only way to [AccountStage.SIGN_IN]. */
    fun accept(context: Context) {
        if (state.value.stage != AccountStage.CONSENT || !flow.accept()) return
        val app = context.applicationContext
        val deferred = CompletableDeferred<String>()
        signedIn = deferred
        markWebViewDirty(app, true)
        state.value = AccountUi(AccountStage.SIGN_IN)
        job = scope.launch {
            val result = try {
                signInAndEnable(deferred)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only the class: an exception text could carry anything.
                AccountResult(CloudOutcome.Other("error: ${e.javaClass.simpleName}"), null, Ownership.UNKNOWN)
            }
            // The cookies are in the flow's jar (now cleared) and nowhere else: take the WebView's copy away too.
            signedIn = null
            StarlinkSignInPage.wipe(null) { markWebViewDirty(app, false) }
            val worth = result.outcome is CloudOutcome.Ok || result.outcome is CloudOutcome.NoAnswer
            state.value = AccountUi(AccountStage.CHECKING, result)
            val reading = if (worth) recheck(app) else null
            state.value = AccountUi(AccountStage.DONE, result, reading)
        }
    }

    /**
     * Waits for the WebView sign-in ([onSignedIn]), then runs the cloud calls (session check, the dish among the account's, the
     * one write) on an IO thread. The [CloudOutcome] is in the result.
     */
    private suspend fun signInAndEnable(signedIn: CompletableDeferred<String>): AccountResult {
        val cookieText = signedIn.await()
        state.value = AccountUi(AccountStage.WORKING)
        return withContext(Dispatchers.IO) { flow.finishSignIn(cookieText) }
    }

    /** The sign-in page took the session cookies (called by [StarlinkSignInPage] once, on the main thread). */
    fun onSignedIn(cookieText: String) {
        if (state.value.stage == AccountStage.SIGN_IN) signedIn?.complete(cookieText)
    }

    /** Backs out. Before the calls start it wipes the session and the WebView; while they run it does nothing (they finish by themselves). */
    fun cancel(context: Context) {
        val stage = state.value.stage
        if (stage == AccountStage.WORKING || stage == AccountStage.CHECKING) return
        if (stage == AccountStage.DONE) return dismiss()
        job?.cancel()
        signedIn = null
        flow.cancel()
        if (stage == AccountStage.SIGN_IN) StarlinkSignInPage.wipe(null) { markWebViewDirty(context.applicationContext, false) }
        state.value = AccountUi()
    }

    /** Closes the result. */
    fun dismiss() {
        if (state.value.stage != AccountStage.DONE) return
        flow.cancel()
        job = null
        signedIn = null
        state.value = AccountUi()
    }

    /**
     * What a crash or a kill left behind: if the sign-in page was up when the process ended, its cookies may still be in the
     * WebView's store. Called once from [tools.apachi.starlinknav.App.onCreate]; does nothing (and loads no WebView) otherwise.
     */
    fun wipeLeftovers(context: Context) {
        val app = context.applicationContext
        if (!prefs(app).getBoolean(KEY_DIRTY, false)) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val view = try {
                WebView(app)
            } catch (e: Exception) {
                null // no WebView on this phone: nothing was stored either
            }
            StarlinkSignInPage.wipe(view) {
                view?.destroy()
                markWebViewDirty(app, false)
            }
        }
    }

    // ---- the dish, over the LAN

    private fun readDishId(app: Context): String? {
        val dish = Devices.shared(app).dish()
        dish.call(DishCallKind.DEVICE_INFO).response?.get_device_info?.device_info?.id?.takeIf { it.isNotEmpty() }?.let { return it }
        return dish.call(DishCallKind.STATUS).response?.dish_get_status?.device_info?.id?.takeIf { it.isNotEmpty() }
    }

    /** Reads the dish's setting up to three times over about five seconds (the cloud relays it over the satellite link). */
    private suspend fun recheck(app: Context): LocalLocationReading? {
        var reading: LocalLocationReading? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(RECHECK_PAUSE_MS)
            val findings = SetupChecks.dish(app)
            SetupState.dish.value = findings
            reading = findings.location
            if (findings.location.available) break
        }
        return reading
    }

    private fun prefs(app: Context) = app.getSharedPreferences("account", Context.MODE_PRIVATE)

    /** A flag, not a secret: "the sign-in page was up and its data not yet wiped". */
    private fun markWebViewDirty(app: Context, dirty: Boolean) = prefs(app).edit { putBoolean(KEY_DIRTY, dirty) }

    private const val KEY_DIRTY = "webview_dirty"
    private const val RECHECK_PAUSE_MS = 2_500L
}
