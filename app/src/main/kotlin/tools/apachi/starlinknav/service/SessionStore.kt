package tools.apachi.starlinknav.service

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import tools.apachi.starlinknav.App
import tools.apachi.starlinknav.SettingsSnapshot
import tools.apachi.starlinknav.core.log.Sessions
import tools.apachi.starlinknav.core.log.Slog
import tools.apachi.starlinknav.core.log.SlogWriter
import tools.apachi.starlinknav.core.log.proto.Record
import tools.apachi.starlinknav.core.report.SessionReports
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Session logs live in app-private storage only; exports are staged in cache for the share sheet. */
object SessionStore {
    fun dir(ctx: Context) = File(ctx.filesDir, "sessions").apply { mkdirs() }

    fun exportsDir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    fun newSession(ctx: Context): Pair<String, File> {
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        return id to File(dir(ctx), "$id.${Slog.EXTENSION}")
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".${Slog.EXTENSION}") }?.sortedByDescending { it.name }.orEmpty()

    fun delete(log: File) {
        log.delete()
        Sessions.indexFileFor(log).delete()
        SessionReports.fileFor(log).delete()
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The session being written (or still closing), if any: pruning never deletes it. */
    fun activeFile(ctx: Context): File? = Live.recorder.value.sessionFile?.let { File(dir(ctx), it) }

    /**
     * [prune] off the caller's thread with the current settings. Retention and the cap apply whether or not a recording
     * ever starts (with logging off none does): the app calls it when the process starts and when logging is switched off;
     * the Sessions page prunes as it lists.
     */
    fun pruneAsync(ctx: Context) {
        val app = ctx.applicationContext
        ioScope.launch { runCatching { prune(app, App.instance.settings.value, activeFile(app)) } }
    }

    /** Deletes sessions past the retention age, then oldest-first until under the size cap. */
    fun prune(ctx: Context, s: SettingsSnapshot, active: File?) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(s.retentionDays.toLong())
        val logs = list(ctx).filter { it != active }
        logs.filter { it.lastModified() < cutoff }.forEach(::delete)
        var total = list(ctx).sumOf { it.length() }
        val cap = s.maxStorageMb.toLong() * 1024 * 1024
        for (log in list(ctx).filter { it != active }.sortedBy { it.name }) {
            if (total <= cap) break
            total -= log.length()
            delete(log)
        }
        exportsDir(ctx).listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1) }
            ?.forEach { it.delete() }
    }
}

/** Single consumer writing records off the capture threads; counts what it had to drop. */
class SessionWriter(val file: File, private val scope: CoroutineScope) {
    private val channel = Channel<Record>(capacity = 50_000)
    val dropped = AtomicLong()
    val written = AtomicLong()

    @Volatile
    var bytes = 0L
        private set
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            val w = SlogWriter.create(file)
            try {
                var lastFlush = SystemClock.elapsedRealtime()
                var lastSync = lastFlush
                for (r in channel) {
                    w.write(r)
                    written.incrementAndGet()
                    bytes = w.bytesWritten
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFlush >= 1_000) {
                        w.flush()
                        lastFlush = now
                    }
                    if (now - lastSync >= 10_000) {
                        w.sync()
                        lastSync = now
                    }
                }
            } finally {
                w.close()
            }
        }
    }

    fun submit(record: Record) {
        if (!channel.trySend(record).isSuccess) dropped.incrementAndGet()
    }

    suspend fun close() {
        channel.close()
        job?.join()
    }
}
