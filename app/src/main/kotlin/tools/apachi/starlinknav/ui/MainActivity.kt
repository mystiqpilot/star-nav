package tools.apachi.starlinknav.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import tools.apachi.starlinknav.service.NavService
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.account.AccountOverlay
import tools.apachi.starlinknav.setup.CheckState
import tools.apachi.starlinknav.setup.SetupChecks

private const val TAB_FONT_SCALE_CAP = 1.2f

/** The five pages of the bottom bar. The start page is [POSITION]. */
enum class Dest(@StringRes val label: Int, @DrawableRes val icon: Int) {
    POSITION(R.string.tab_position, R.drawable.ic_nav_position),
    MOCK(R.string.tab_mock, R.drawable.ic_nav_mock),
    STARLINK(R.string.tab_starlink, R.drawable.ic_nav_dish),
    SESSIONS(R.string.tab_sessions, R.drawable.ic_nav_sessions),
    SETTINGS(R.string.tab_config, R.drawable.ic_nav_settings),
}

class MainActivity : ComponentActivity() {
    /**
     * The page a launch asked for (the notification and the Quick Settings tile ask for Position, or for Mock GPS while
     * the location permission is missing). An event, not a state: [AppRoot] takes it and clears it, so an Activity that
     * is recreated later (theme, rotation, font scale) does not jump to that page again.
     */
    private val openRequest = MutableStateFlow<Dest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent bars over the app; their icons are light in the dark theme and dark in the
        // light one. A change of theme (the system's or Settings') recreates the activity (Android 12 and later).
        val mode = App.instance.settings.value.themeMode
        applyWindowLook(mode.isDark(resources.configuration.nightModeOn))
        // A recreated Activity (theme, rotation, font scale, process death) is handed the intent that first started it.
        handle(intent, fresh = savedInstanceState == null)
        setContent {
            val theme by App.instance.settings.flow.collectAsStateWithLifecycle()
            val dark = rememberDarkTheme(theme.themeMode)
            // Before Android 12 the system does not know the app's own Light / Dark choice and does not recreate the
            // activity for it: the window background and the bar icons follow the composed look instead.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) LaunchedEffect(dark) { applyWindowLook(dark) }
            AppTheme(dark) { AppRoot(openRequest) }
        }
    }

    /** Transparent system bars with icons suiting the look, and the window background of that look. */
    private fun applyWindowLook(dark: Boolean) {
        val bars = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()) { dark }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        // `auto` turns the system's contrast scrim on; with 3-button navigation that would draw a
        // translucent band over the bar's bottom inset (the old dark style had it off).
        // (The system contrast scrim is API 29; before it there is none to turn off.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        // From Android 12 the window's theme already follows the app's night mode (UiModeManager); before it, set by hand.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) window.setBackgroundDrawable(ColorDrawable(windowBackgroundColor(dark)))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent, fresh = true)
    }

    /**
     * The Quick Settings tile opens the app to start the location service from the foreground; the tile and the
     * notification both ask for the Position page. The extras are acted on once: not again for an intent the system
     * hands over a second time (a recreated Activity, or a launch from Recents, which replays the original intent).
     */
    private fun handle(intent: Intent?, fresh: Boolean) {
        if (intent == null) return
        val start = intent.getBooleanExtra(EXTRA_START_MOCK, false)
        val open = intent.getBooleanExtra(EXTRA_OPEN_POSITION, false)
        intent.removeExtra(EXTRA_START_MOCK)
        intent.removeExtra(EXTRA_OPEN_POSITION)
        val replay = !fresh || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (replay) return
        val permitted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (start && permitted) NavService.mock(this, true)
        // Without the location permission nothing runs on Position: the checklist that asks for it is on Mock GPS.
        if (open) openRequest.value = if (permitted) Dest.POSITION else Dest.MOCK
    }

    companion object {
        const val EXTRA_START_MOCK = "start_mock"
        const val EXTRA_OPEN_POSITION = "open_position"
    }
}

@Composable
private fun AppRoot(openRequest: MutableStateFlow<Dest?>) {
    val ctx = LocalContext.current
    // Open on Mock GPS (its checklist) while anything required on the phone is missing, unless the launch asked for a
    // page (the notification, the tile).
    var dest by rememberSaveable {
        val phone = SetupChecks.phone(ctx)
        val missing = phone.values.any { it.item.required && it.state == CheckState.NEEDED }
        mutableStateOf(openRequest.value ?: if (missing) Dest.MOCK else Dest.POSITION)
    }
    // A page asked for by a launch while the app is running: taken once, then cleared.
    val request by openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        request?.let {
            dest = it
            openRequest.value = null
        }
    }
    val phone by SetupState.phone.collectAsStateWithLifecycle()
    val dish by SetupState.dish.collectAsStateWithLifecycle()
    val googleAccuracy by SetupState.googleAccuracy.collectAsStateWithLifecycle()
    val missing = SetupChecks.combine(ctx, phone, dish, googleAccuracy).count { it.item.required && it.state == CheckState.NEEDED }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                while (true) {
                    SetupState.phone.value = SetupChecks.phone(ctx)
                    delay(1_500)
                }
            }
            launch {
                while (true) {
                    SetupState.googleAccuracy.value = SetupChecks.googleAccuracy(ctx)
                    delay(10_000)
                }
            }
            launch {
                while (true) {
                    SetupState.dish.value = SetupChecks.dish(ctx)
                    // Often while the checklist is open, rarely otherwise.
                    delay(if (dest == Dest.MOCK) 5_000 else 30_000)
                }
            }
        }
    }
    // Back from any other page returns to Position (a page with sub-pages of its own handles Back itself first: its
    // handler is registered after this one).
    BackHandler(enabled = dest != Dest.POSITION) { dest = Dest.POSITION }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                // The light bar (#FCFCFB) is nearly the page (#F9F9F7): a hairline sets it apart.
                NavigationBar(modifier = Modifier.topRing()) {
                    for (d in Dest.entries) {
                        NavigationBarItem(
                            selected = dest == d,
                            onClick = { dest = d },
                            icon = {
                                BadgedBox(badge = { if (d == Dest.MOCK && missing > 0) Badge { Text(missing.toString()) } }) {
                                    Icon(painterResource(d.icon), contentDescription = null)
                                }
                            },
                            // Five tabs: past 1.2x the labels would be cut ("Sessio…"), so the bar's own text stops growing there.
                            label = {
                                val density = LocalDensity.current
                                CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, TAB_FONT_SCALE_CAP))) {
                                    Text(stringResource(d.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // The one motion between pages: a short fade, so a change of page reads as a change of content.
                Crossfade(dest, animationSpec = tween(150), label = "page") { shown ->
                    when (shown) {
                        Dest.POSITION -> PositionScreen(onOpenMock = { dest = Dest.MOCK })
                        Dest.MOCK -> MockScreen()
                        Dest.STARLINK -> DishScreen()
                        Dest.SESSIONS -> SessionsScreen(onOpenSettings = { dest = Dest.SETTINGS })
                        Dest.SETTINGS -> SettingsScreen()
                    }
                }
            }
        }
        // The opt-in Starlink account sign-in, over everything while it runs (nothing when idle).
        AccountOverlay()
    }
}
