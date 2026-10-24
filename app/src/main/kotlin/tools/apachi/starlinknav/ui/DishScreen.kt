package tools.apachi.starlinknav.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.service.CapabilityStore
import tools.apachi.starlinknav.service.DishFeed

/** The sub-tabs of the Starlink page, and the reads each one wants while it is on screen. */
enum class DishTab(@StringRes val label: Int, val kinds: Set<DishCallKind>) {
    OVERVIEW(R.string.dish_tab_overview, setOf(DishCallKind.STATUS, DishCallKind.DIAGNOSTICS, DishCallKind.HISTORY)),
    HISTORY(R.string.dish_tab_history, setOf(DishCallKind.HISTORY)),
    SKY(R.string.dish_tab_sky, setOf(DishCallKind.STATUS, DishCallKind.OBSTRUCTION_MAP)),
    CONTROLS(R.string.dish_tab_controls, setOf(DishCallKind.STATUS)),

    // The Router sub-tab names its own reads: they depend on the part of it that is open (see RouterSection).
    ROUTER(R.string.dish_tab_router, emptySet()),

    // Diagnostics shows what the location service holds (no reads of its own); Connection is settings plus the probe.
    DIAGNOSTICS(R.string.dish_tab_diagnostics, emptySet()),
    CONNECTION(R.string.dish_tab_connection, emptySet()),
}

/**
 * The Starlink page: Overview, History, Sky, Controls, Router, Diagnostics and Connection (settings + probe). One [DishFeed] polls what the open sub-tab needs,
 * only while the screen is shown and the app is in front (status about every second, history 5 s, the obstruction
 * map 30 s, ...); leaving the tab or the app stops it.
 */
@Composable
fun DishScreen() {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableIntStateOf(DishTab.OVERVIEW.ordinal) }
    val tab = DishTab.entries[selected.coerceIn(DishTab.entries.indices)]
    var routerSelected by rememberSaveable { mutableIntStateOf(RouterSection.STATUS.ordinal) }
    val routerSection = RouterSection.entries[routerSelected.coerceIn(RouterSection.entries.indices)]
    val kinds = if (tab == DishTab.ROUTER) routerSection.kinds else tab.kinds
    // Back from a sub-tab returns to Overview first.
    BackHandler(enabled = tab != DishTab.OVERVIEW) { selected = DishTab.OVERVIEW.ordinal }
    val feed = remember { DishFeed(context) }
    val wanted = remember { MutableStateFlow(kinds) }
    LaunchedEffect(kinds) { wanted.value = kinds }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, feed) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { feed.run(wanted) }
    }
    // The capability book is saved on a timer while answers come in; this writes what is left.
    DisposableEffect(Unit) { onDispose { CapabilityStore.flush() } }

    Column(Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = Space.s) {
            for (t in DishTab.entries) {
                Tab(selected = t == tab, onClick = { selected = t.ordinal }, text = { Text(stringResource(t.label), maxLines = 1) })
            }
        }
        Box(Modifier.weight(1f).padding(top = Space.xs)) {
            // The one motion here: a short fade, so a change of sub-tab reads as a change of content.
            Crossfade(tab, animationSpec = tween(150), label = "dish-tab") { shown ->
            when (shown) {
                DishTab.OVERVIEW -> DishOverview(feed)
                DishTab.HISTORY -> DishHistoryTab(feed)
                DishTab.SKY -> DishSky(feed)
                DishTab.CONTROLS -> DishControls(feed)
                DishTab.ROUTER -> DishRouterTab(feed, routerSection) { routerSelected = it.ordinal }
                DishTab.DIAGNOSTICS -> DiagnosticsScreen()
                DishTab.CONNECTION -> ConnectionScreen()
            }
            }
        }
    }
}
