package tools.apachi.starlinknav.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.SettingsSnapshot
import tools.apachi.starlinknav.core.dish.fix
import tools.apachi.starlinknav.core.nav.ImuState
import tools.apachi.starlinknav.core.nav.InertialState
import tools.apachi.starlinknav.service.InertialUi
import tools.apachi.starlinknav.service.MountSource
import tools.apachi.starlinknav.service.DeliveryUi
import tools.apachi.starlinknav.service.MockUi

/**
 * What the mock has pushed and what apps receive (the Mock GPS page): count and age, the source of the last push, how far
 * it moved the fix, the providers it replaces, Google's fused location, the delivery check.
 */
@Composable
fun MockOutputCard(mock: MockUi, now: Long) {
    val ctx = LocalContext.current
    Section(stringResource(R.string.mock_title)) {
        // Counts of the mock that ran earlier would sit next to "switch the mock on": only while it is on.
        if (mock.enabled) {
            KeyValueTable {
                KeyValueRow(stringResource(R.string.mock_pushed), stringResource(R.string.mock_pushed_value, mock.pushes, age(now, mock.lastPushMonoNs)))
                mock.lastSource?.let { KeyValueRow(stringResource(R.string.mock_source), it) }
                mock.lastShiftM?.let {
                    // The inertial filter's position is no fix moved ahead: its distance from the dish fix it was served at.
                    if (mock.lastSource?.startsWith("inertial:") == true) {
                        KeyValueRow(stringResource(R.string.mock_from_fix), stringResource(R.string.live_unit_m, it.f()))
                    } else {
                        KeyValueRow(stringResource(R.string.mock_moved_ahead), stringResource(R.string.mock_moved_ahead_value, it.f(), mock.lastAgeS?.f(2) ?: "-"))
                    }
                }
                KeyValueRow(stringResource(R.string.mock_providers), mock.providers.joinToString(" · "))
                mock.googleFused?.let {
                    KeyValueRow(stringResource(R.string.mock_google_fused), it, status = if (it == stringResource(R.string.mock_google_fused_on)) Status.OK else Status.WARN)
                }
                DeliveryRow(mock.delivery, now)
            }
        } else {
            Hint(stringResource(R.string.mock_output_off))
        }
        mock.error?.let { StateView(UiState.Error(Status.BAD, it)) }
        Hint(stringResource(R.string.mock_hint))
        ActionRow {
            SecondaryButton(stringResource(R.string.mock_developer_options), onClick = {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
    }
}

/**
 * Whether each location source hands apps the pushed position; stale results (> 10 s) are left out. One row of a
 * [KeyValueTable].
 */
@Composable
fun DeliveryRow(delivery: Map<String, DeliveryUi>, now: Long) {
    val fresh = delivery.filterValues { now - it.atMonoNs <= 10_000_000_000L }
    if (fresh.isEmpty()) {
        KeyValueRow(stringResource(R.string.mock_delivery), stringResource(R.string.mock_delivery_waiting), status = Status.NEUTRAL)
        return
    }
    val gms = stringResource(R.string.mock_source_gms_fused)
    val parts = DELIVERY_ORDER.mapNotNull { key ->
        val d = fresh[key] ?: return@mapNotNull null
        val name = if (key == "gms_fused") gms else key
        if (d.ok) stringResource(R.string.mock_delivery_ok, name) else stringResource(R.string.mock_delivery_off, name, d.offsetM.f(0))
    }
    // Each source carries its own ✓ or ✗, so no status icon in front (it doubled the first tick); the colour sums them up.
    val all = if (fresh.values.all { it.ok }) Status.OK else Status.BAD
    KeyValueRow(stringResource(R.string.mock_delivery), parts.joinToString(" · "), valueColor = all.color())
}

val DELIVERY_ORDER = listOf("gps", "network", "fused", "gms_fused")

/** The inertial filter as the service runs it: state, accuracy, sideways offset, IMU, mount offset, counts. */
@Composable
fun InertialCard(i: InertialUi, settings: SettingsSnapshot, serving: Boolean, now: Long) {
    Section(stringResource(R.string.inertial_title)) {
        KeyValueTable {
            KeyValueRow(
                stringResource(R.string.inertial_correction),
                stringResource(
                    when {
                        !settings.inertialCorrection -> R.string.inertial_correction_off
                        serving -> R.string.inertial_correction_serving
                        else -> R.string.inertial_correction_not_serving
                    },
                ),
                status = if (serving) Status.OK else Status.NEUTRAL,
            )
            if (i.running) {
                KeyValueRow(
                    stringResource(R.string.inertial_state),
                    stringResource(R.string.inertial_state_value, stringResource(inertialStateLabel(i.state)), ((now - i.sinceNs) / 1e9).coerceAtLeast(0.0).f(0)),
                    status = when (i.state) {
                        InertialState.TRACKING -> Status.OK
                        InertialState.NO_OUTPUT -> Status.NEUTRAL
                        else -> Status.WARN
                    },
                )
                // Told to apps only while it serves; with the correction off (or not Auto) apps get the dish fix.
                KeyValueRow(
                    stringResource(if (serving) R.string.inertial_accuracy else R.string.inertial_accuracy_not_served),
                    i.accuracyM?.let { "±" + stringResource(R.string.live_unit_m, it.f(0)) } ?: "-",
                )
                i.sidewaysM?.let { m ->
                    KeyValueRow(
                        stringResource(R.string.inertial_sideways),
                        stringResource(if (m >= 0) R.string.inertial_sideways_right else R.string.inertial_sideways_left, kotlin.math.abs(m).f(1)),
                        status = if (i.state == InertialState.SIDEWAYS_OPEN) Status.WARN else null,
                    )
                }
                KeyValueRow(stringResource(R.string.inertial_imu), stringResource(imuStateLabel(i.imu)), status = if (i.imu == ImuState.HANDLING) Status.WARN else null)
                KeyValueRow(stringResource(R.string.inertial_forward_axis), stringResource(if (i.forwardAxisReady) R.string.inertial_forward_axis_ready else R.string.inertial_forward_axis_learning))
                KeyValueRow(stringResource(R.string.inertial_mount), mountText(i))
                KeyValueRow(stringResource(R.string.inertial_counts), "${i.sidewaysOpenings} · ${i.watchSuspects}")
                KeyValueRow(stringResource(R.string.inertial_rejects), i.gateRejects.toString())
                KeyValueRow(stringResource(R.string.inertial_dead_reckoning), duration((i.deadReckoningS * 1000).toLong()))
                if (i.resets > 0) KeyValueRow(stringResource(R.string.inertial_resets), i.resets.toString(), status = Status.WARN)
                if (i.errors > 0) KeyValueRow(stringResource(R.string.inertial_errors), stringResource(R.string.inertial_errors_value, i.errors, i.lastError ?: "-"), status = Status.BAD)
            }
        }
        if (!i.running) Hint(stringResource(R.string.inertial_not_running)) else Hint(stringResource(R.string.inertial_hint))
    }
}

/** The mount offset in use and where it came from: learned now, learned earlier (when that value was), manual, or not yet. */
@Composable
fun mountText(i: InertialUi): String {
    val deg = i.mountDeg ?: return stringResource(R.string.inertial_mount_none)
    return when (i.mountSource) {
        MountSource.NONE -> stringResource(R.string.inertial_mount_none)
        MountSource.LEARNED_NOW -> stringResource(R.string.inertial_mount_learned_now, signedDeg(deg))
        MountSource.MANUAL -> stringResource(R.string.inertial_mount_manual, signedDeg(deg))
        MountSource.SAVED -> stringResource(R.string.inertial_mount_learned_at, signedDeg(deg), learnedWhen(i.mountSavedAtMs))
    }
}

/** When a mount offset was learned, as a local date and time ("-" when never). */
@Composable
fun learnedWhen(ms: Long): String {
    if (ms <= 0) return "-"
    val ctx = LocalContext.current
    return android.text.format.DateUtils.formatDateTime(
        ctx, ms,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_MONTH,
    )
}

fun signedDeg(d: Double): String = "%+.1f°".format(java.util.Locale.ROOT, d)

fun inertialStateLabel(s: InertialState) = when (s) {
    InertialState.TRACKING -> R.string.inertial_state_tracking
    InertialState.SIDEWAYS_OPEN -> R.string.inertial_state_sideways
    InertialState.DEAD_RECKONING -> R.string.inertial_state_dr
    InertialState.NETWORK_AIDED -> R.string.inertial_state_network
    InertialState.NO_OUTPUT -> R.string.inertial_state_none
}

fun imuStateLabel(s: ImuState) = when (s) {
    ImuState.NONE -> R.string.inertial_imu_none
    ImuState.WARMING -> R.string.inertial_imu_warming
    ImuState.HANDLING -> R.string.inertial_imu_handling
    ImuState.GYRO -> R.string.inertial_imu_gyro
    ImuState.FORWARD -> R.string.inertial_imu_forward
}
