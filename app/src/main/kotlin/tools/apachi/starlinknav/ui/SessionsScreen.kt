package tools.apachi.starlinknav.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.apachi.starlinknav.BuildConfig
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.log.SessionExporter
import tools.apachi.starlinknav.core.log.SessionIndex
import tools.apachi.starlinknav.core.log.Sessions
import tools.apachi.starlinknav.core.report.SessionReport
import tools.apachi.starlinknav.core.report.SessionReports
import tools.apachi.starlinknav.service.Live
import tools.apachi.starlinknav.service.NavService
import tools.apachi.starlinknav.service.RecorderUi
import tools.apachi.starlinknav.service.SessionStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class SessionRow(val file: File, val index: SessionIndex?, val recording: Boolean)

@Composable
fun SessionsScreen(onOpenSettings: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val name = open
    if (name == null) SessionList(onOpen = { open = it }, onOpenSettings) else SessionDetail(name, onBack = { open = null })
}

/**
 * The recording controls (they were on Home): "Record this drive" with the running session's time and size, and
 * "Record whenever the mock GPS is on".
 */
@Composable
private fun RecordingCard(rec: RecorderUi, recordWithMock: Boolean) {
    val ctx = LocalContext.current
    val now = rememberMonoNow()
    val hasLocation = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    Section(stringResource(R.string.status_recorder_title)) {
        Row(Modifier.heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_record_drive), Modifier.weight(1f))
            Switch(checked = rec.running, enabled = hasLocation && !rec.stopping, onCheckedChange = { NavService.record(ctx, it) })
        }
        if (rec.running) {
            // `now` ticks twice a second so the time keeps running.
            KeyValueTable {
                rec.sessionId?.let { KeyValueRow(stringResource(R.string.status_session), it) }
                KeyValueRow(stringResource(R.string.status_elapsed), remember(now) { duration(System.currentTimeMillis() - rec.startedWallMs) })
                KeyValueRow(stringResource(R.string.status_written), pluralStringResource(R.plurals.status_written_value, rec.records.asQuantity(), bytes(rec.bytes), rec.records))
                if (rec.dropped > 0) KeyValueRow(stringResource(R.string.status_dropped), rec.dropped.toString(), status = Status.BAD)
            }
        }
        rec.error?.let { StateView(UiState.Error(Status.BAD, it)) }
        if (!hasLocation) Hint(stringResource(R.string.status_location_permission_hint))
        Row(Modifier.heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_record_with_mock), Modifier.weight(1f))
            Switch(checked = recordWithMock, onCheckedChange = { v -> App.instance.settings.update { it.copy(recordWithMock = v) } })
        }
        Hint(stringResource(R.string.home_record_hint))
    }
}

/** Logging is switched off in Settings: no controls, and the way to switch it on. */
@Composable
private fun LoggingOffCard(onOpenSettings: () -> Unit) {
    Section(stringResource(R.string.sessions_logging_off_title)) {
        Text(stringResource(R.string.sessions_logging_off))
        ActionRow { SecondaryButton(stringResource(R.string.sessions_open_settings), onOpenSettings) }
    }
}

@Composable
private fun SessionList(onOpen: (String) -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val rec by Live.recorder.collectAsStateWithLifecycle()
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    val rows by produceState<List<SessionRow>?>(null, rec.sessionFile) {
        value = withContext(Dispatchers.IO) {
            // Retention and the cap apply here too: with logging off no recording starts to enforce them.
            runCatching { SessionStore.prune(ctx, App.instance.settings.value, SessionStore.activeFile(ctx)) }
            SessionStore.list(ctx).map { f ->
                if (f.name == rec.sessionFile) SessionRow(f, null, recording = true)
                else SessionRow(f, runCatching { Sessions.indexOrScan(f) }.getOrNull(), recording = false)
            }
        }
    }
    val list = rows
    // A recording still closing after logging was switched off keeps its controls (the stop switch) until it is done.
    val controls = settings.loggingEnabled || rec.running || rec.stopping
    LazyColumn(contentPadding = PaddingValues(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        if (controls) item(key = "controls") { RecordingCard(rec, settings.recordWithMock && settings.loggingEnabled) }
        if (!settings.loggingEnabled) item(key = "logging_off") { LoggingOffCard(onOpenSettings) }
        when {
            list == null -> item(key = "loading") { StateView(UiState.Loading()) }
            list.isEmpty() -> item(key = "empty") {
                StateView(UiState.Empty(stringResource(if (settings.loggingEnabled) R.string.sessions_empty else R.string.sessions_empty_logging_off)))
            }
            else -> items(list, key = { it.file.name }) { row ->
                val idx = row.index
                Section(
                    title = idx?.let { formatWall(it.startWallMs) } ?: row.file.nameWithoutExtension,
                    onClick = if (!row.recording && idx != null) ({ onOpen(row.file.name) }) else null,
                    trailing = if (!row.recording && idx != null) ({ Chevron() }) else null,
                ) {
                    when {
                        row.recording -> StatusPill(Status.OK, stringResource(R.string.sessions_recording))
                        idx == null -> StatusPill(Status.BAD, stringResource(R.string.sessions_unreadable))
                        else -> KeyValueTable {
                            KeyValueRow(stringResource(R.string.sessions_duration), duration(idx.durationMs))
                            KeyValueRow(stringResource(R.string.sessions_distance), idx.distanceM?.let { stringResource(R.string.live_unit_km, (it / 1000).f(2)) } ?: "-")
                            KeyValueRow(stringResource(R.string.sessions_size), bytes(idx.bytes))
                            KeyValueRow(
                                stringResource(R.string.sessions_dish_track),
                                if (idx.dishTrack.isEmpty()) {
                                    stringResource(R.string.sessions_none)
                                } else {
                                    pluralStringResource(R.plurals.sessions_dish_track_value, idx.dishTrack.size, idx.dishTrack.size, idx.dishTrackKind ?: "-")
                                },
                            )
                            if (idx.truncatedTail) KeyValueRow(stringResource(R.string.sessions_note), stringResource(R.string.sessions_note_truncated_killed), status = Status.WARN, mono = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDetail(name: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    BackHandler(onBack = onBack)
    val file = remember(name) { File(SessionStore.dir(ctx), name) }
    val loaded by produceState<SessionIndex?>(null, name) {
        value = withContext(Dispatchers.IO) { runCatching { Sessions.indexOrScan(file) }.getOrNull() }
    }
    val report by produceState<SessionReport?>(null, name) {
        value = withContext(Dispatchers.IO) { runCatching { SessionReports.loadOrBuild(file) }.getOrNull() }
    }
    var accuracyMode by rememberSaveable { mutableStateOf(true) }
    var cursor by remember(name) { mutableStateOf<Long?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    val settings by App.instance.settings.flow.collectAsStateWithLifecycle()
    val layer = settings.mapLayer
    val idx = loaded
    if (idx == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StateView(UiState.Loading()) }
        return
    }
    val total = (idx.endMonoNs - idx.startMonoNs).coerceAtLeast(1_000_000_000L)
    var fromNs by remember(name) { mutableLongStateOf(idx.startMonoNs) }
    var toNs by remember(name) { mutableLongStateOf(idx.endMonoNs) }
    var exporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val step = 5_000_000_000L

    fun wallOf(t: Long) = idx.startWallMs + (t - idx.startMonoNs) / 1_000_000
    fun setFrom(t: Long) { fromNs = t.coerceIn(idx.startMonoNs, toNs) }
    fun setTo(t: Long) { toNs = t.coerceIn(fromNs, idx.endMonoNs) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LowButton(stringResource(R.string.sessions_back), onBack)
            Text(formatWall(idx.startWallMs), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        SegmentedRow(listOf(true, false), accuracyMode, { accuracyMode = it }) {
            stringResource(if (it) R.string.sessions_mode_accuracy else R.string.sessions_mode_sources)
        }
        val rep = report
        MapLayerBar(layer, onExpand = { fullScreen = true })
        if (fullScreen) {
            FullScreenMap(onClose = { fullScreen = false }) {
                if (accuracyMode && rep != null) {
                    AccuracyMap(rep, fromNs, toNs, cursor, layer, Modifier.fillMaxWidth().weight(1f), interactive = true)
                } else {
                    TrackView(idx.dishTrack, idx.phoneTrack, fromNs, toNs, layer, Modifier.fillMaxWidth().weight(1f), interactive = true)
                }
            }
        }
        if (accuracyMode) {
            if (rep == null) {
                ReportPlaceholder(stringResource(R.string.report_building))
            } else {
                AccuracyMap(rep, fromNs, toNs, cursor, layer, Modifier.fillMaxWidth())
                AccuracyLegend(rep)
                Hint(stringResource(R.string.sessions_map_hint))
            }
        } else {
            TrackView(idx.dishTrack, idx.phoneTrack, fromNs, toNs, layer, Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                LegendItem(StarTheme.colors.dish, stringResource(R.string.common_legend_dish, idx.dishTrackKind?.removePrefix("location:") ?: ""))
                LegendItem(StarTheme.colors.phone, stringResource(R.string.sessions_legend_phone_gps), circle = true)
                LegendItem(StarTheme.colors.good, stringResource(R.string.sessions_legend_start))
                LegendItem(StarTheme.colors.bad, stringResource(R.string.sessions_legend_end))
            }
        }
        RangeSlider(
            // The thumbs sit at the screen edges, where gesture navigation would read a drag as Back.
            modifier = Modifier.systemGestureExclusion(),
            value = ((fromNs - idx.startMonoNs).toFloat() / total)..((toNs - idx.startMonoNs).toFloat() / total),
            onValueChange = { r ->
                fromNs = idx.startMonoNs + (r.start * total).toLong()
                toNs = idx.startMonoNs + (r.endInclusive * total).toLong()
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sessions_start_at, formatTime(wallOf(fromNs))), Modifier.weight(1f))
            LowButton(stringResource(R.string.unit_s, "−5"), { setFrom(fromNs - step) })
            LowButton(stringResource(R.string.unit_s, "+5"), { setFrom(fromNs + step) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sessions_end_at, formatTime(wallOf(toNs))), Modifier.weight(1f))
            LowButton(stringResource(R.string.unit_s, "−5"), { setTo(toNs - step) })
            LowButton(stringResource(R.string.unit_s, "+5"), { setTo(toNs + step) })
        }
        val inWindow = idx.dishTrack.ifEmpty { idx.phoneTrack }.count { it.t in fromNs..toNs }
        Hint(pluralStringResource(R.plurals.sessions_export_hint, inWindow, inWindow, duration((toNs - fromNs) / 1_000_000)))
        ActionRow {
            PrimaryButton(stringResource(if (exporting) R.string.sessions_exporting else R.string.sessions_export_share), enabled = !exporting, onClick = {
                scope.launch {
                    exporting = true
                    message = null
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val offA = (fromNs - idx.startMonoNs) / 1_000_000_000
                            val offB = (toNs - idx.startMonoNs) / 1_000_000_000
                            val out = File(SessionStore.exportsDir(ctx), "starlink-nav-${idx.id}-${offA}s-${offB}s.zip")
                            SessionExporter.export(file, fromNs, toNs, out, "starlink-nav ${BuildConfig.VERSION_NAME}")
                            out
                        }
                    }
                    exporting = false
                    result.onSuccess { zip ->
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.exports", zip)
                        val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(Intent.createChooser(send, resources.getString(R.string.sessions_share_title)))
                        message = resources.getString(R.string.sessions_exported, bytes(resources, zip.length()))
                    }.onFailure { message = resources.getString(R.string.sessions_export_failed, it.message) }
                }
            })
            SecondaryButton(stringResource(R.string.common_delete), { confirmDelete = true }, color = StarTheme.colors.bad)
        }
        message?.let { Hint(it) }
        rep?.let {
            ReportSummarySection(it)
            Section(stringResource(R.string.sessions_along_drive)) { ReportCharts(it, cursor) { t -> cursor = t } }
            InertialReportSection(it)
            CutoffsSection(it)
            RebootsSection(it)
        }
        Section(stringResource(R.string.sessions_info_title)) {
            KeyValueTable {
                KeyValueRow(stringResource(R.string.sessions_duration), duration(idx.durationMs))
                KeyValueRow(stringResource(R.string.sessions_size), bytes(idx.bytes))
                idx.deviceModel?.let { KeyValueRow(stringResource(R.string.sessions_phone), it) }
                idx.dishSoftware?.let { KeyValueRow(stringResource(R.string.sessions_dish_firmware), it) }
                idx.appVersion?.let { KeyValueRow(stringResource(R.string.sessions_app), it) }
                for ((type, n) in idx.records) KeyValueRow(type, n.toString())
                if (idx.truncatedTail) KeyValueRow(stringResource(R.string.sessions_note), stringResource(R.string.sessions_note_truncated), status = Status.WARN, mono = false)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.sessions_delete_title)) },
            text = { Text(stringResource(R.string.sessions_delete_text, formatWall(idx.startWallMs), bytes(idx.bytes))) },
            confirmButton = {
                LowButton(stringResource(R.string.common_delete), {
                    SessionStore.delete(file)
                    confirmDelete = false
                    onBack()
                }, color = StarTheme.colors.bad)
            },
            dismissButton = { LowButton(stringResource(R.string.common_cancel), { confirmDelete = false }) },
        )
    }
}

// Dates follow the app's language (order, month names); the 24-hour clock stays as before.
private fun formatWall(ms: Long): String = localized("yMMMdHm", ms)
private fun formatTime(ms: Long): String = localized("Hms", ms)

private fun localized(skeleton: String, ms: Long): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ms))
}
