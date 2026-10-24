package tools.apachi.starlinknav.service

import android.content.Context
import tools.apachi.starlinknav.BuildConfig
import tools.apachi.starlinknav.core.dish.DebugDataBuilder
import tools.apachi.starlinknav.core.dish.DishProbe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The Debug data export: one read-only survey of the dish and (when it answers) the router, built into the JSON
 * snapshot of [DebugDataBuilder] and staged in the share folder. Only on an explicit tap; sends no command.
 *
 * Never in the file: Wi-Fi passwords and keys (the builder redacts router answers and checks the finished document),
 * and any position (the diagnostics' location is zeroed, whatever the options). Device ids, addresses, SSIDs and
 * client names only when [includeIdentifiers] is on (default off).
 */
object DebugExport {
    private const val PREFIX = "starnav-debugdata-"

    /** Blocking (network): call from an IO dispatcher. Throws when the document could not be built or written. */
    fun run(context: Context, includeIdentifiers: Boolean): File {
        val devices = Devices.shared(context)
        val dish = DishProbe.run(devices.dish(), stopWhenUnreachable = true)
        // The router's answers as they came: the builder redacts them itself and needs the real secrets to look for.
        val router = devices.router()?.let { DishProbe.runRouterUnredacted(it.client, stopWhenUnreachable = true) }.orEmpty()
        CapabilityStore.recordAll(dish + router)
        CapabilityStore.flush()
        val json = DebugDataBuilder.toJson(
            dish, router, DebugDataBuilder.Options(appVersion = BuildConfig.VERSION_NAME, removeIdentifiers = !includeIdentifiers),
        )
        val dir = SessionStore.exportsDir(context)
        dir.listFiles { f -> f.name.startsWith(PREFIX) && f.lastModified() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1) }
            ?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        return File(dir, "$PREFIX$stamp.json").also { it.writeText(json, Charsets.UTF_8) }
    }
}
