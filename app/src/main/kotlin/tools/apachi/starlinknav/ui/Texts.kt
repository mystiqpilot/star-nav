package tools.apachi.starlinknav.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tools.apachi.starlinknav.R
import tools.apachi.starlinknav.core.dish.BootReason
import tools.apachi.starlinknav.core.dish.Disablement
import tools.apachi.starlinknav.core.dish.RebootReason
import tools.apachi.starlinknav.core.nav.DishReboot
import tools.apachi.starlinknav.core.nav.RebootCause

// Core keeps these texts in English (CLI, HTML report); the app shows them in its own language.

private fun disablementReasonRes(code: Int): Int? = when (code) {
    0 -> R.string.disablement_unknown_state
    1 -> R.string.disablement_okay
    2 -> R.string.disablement_no_active_account
    3 -> R.string.disablement_too_far_from_service_address
    4 -> R.string.disablement_in_ocean
    6 -> R.string.disablement_blocked_country
    7 -> R.string.disablement_data_overage_sandbox_policy
    8 -> R.string.disablement_cell_is_disabled
    10 -> R.string.disablement_roam_restricted
    11 -> R.string.disablement_unknown_location
    12 -> R.string.disablement_account_disabled
    13 -> R.string.disablement_unsupported_version
    14 -> R.string.disablement_moving_too_fast
    15 -> R.string.disablement_under_aviation_flyover_limits
    16 -> R.string.disablement_blocked_area
    17 -> R.string.disablement_outside_home_region
    else -> null
}

/** Codes from a newer firmware have no resource yet: show the enum name like core's reason() does. */
private fun disablementFallback(code: Int) = Disablement.name(code).lowercase().replace('_', ' ')

/** [Disablement.reason] in the app's language, for notifications and other code outside composition. */
fun disablementReason(ctx: Context, code: Int): String =
    disablementReasonRes(code)?.let { ctx.getString(it) } ?: disablementFallback(code)

/** [Disablement.reason] in the app's language. */
@Composable
fun disablementReason(code: Int): String =
    disablementReasonRes(code)?.let { stringResource(it) } ?: disablementFallback(code)

private fun rebootCauseRes(c: RebootCause): Int = when (c) {
    RebootCause.UPDATE_INSTALLED -> R.string.reboot_cause_update_installed
    RebootCause.STAR_NAV -> R.string.reboot_cause_star_nav
    RebootCause.UPDATE -> R.string.reboot_cause_update
    RebootCause.REQUESTED -> R.string.reboot_cause_requested
    RebootCause.POWER -> R.string.reboot_cause_power
    RebootCause.AUTO_OFF -> R.string.reboot_cause_auto_off
    RebootCause.OVERHEAT -> R.string.reboot_cause_overheat
    RebootCause.NO_NETWORK -> R.string.reboot_cause_no_network
    RebootCause.FAULT -> R.string.reboot_cause_fault
    RebootCause.FACTORY_RESET -> R.string.reboot_cause_factory_reset
    RebootCause.CONFIG -> R.string.reboot_cause_config
    RebootCause.OTHER -> R.string.reboot_cause_other
    RebootCause.NOT_REPORTED -> R.string.reboot_cause_not_reported
}

/** The words for an installed update: it names the version, and says when Star Nav sent the restart that installed it. */
private fun updateInstalledRes(r: DishReboot): Int =
    if (r.commanded) R.string.reboot_cause_update_installed_by_star_nav else R.string.reboot_cause_update_installed

/** Why the dish restarted, in the app's language, for notifications and other code outside composition. */
fun rebootCause(ctx: Context, r: DishReboot): String =
    if (r.cause == RebootCause.UPDATE_INSTALLED) ctx.getString(updateInstalledRes(r), r.softwareAfter.orEmpty())
    else ctx.getString(rebootCauseRes(r.cause))

/** Why the dish restarted, in the app's language (an installed update names its version). */
@Composable
fun rebootCause(r: DishReboot): String =
    if (r.cause == RebootCause.UPDATE_INSTALLED) stringResource(updateInstalledRes(r), r.softwareAfter.orEmpty())
    else stringResource(rebootCauseRes(r.cause))

/** The dish's own code names behind it, e.g. "SWUPDATE_NOW · boot POWER_CYCLE"; null when it gave none. */
fun rebootCodes(r: DishReboot): String? = listOfNotNull(
    r.reason.takeIf { it != 0 }?.let { RebootReason.name(it) },
    r.bootReason?.let { "boot " + BootReason.name(it) },
).joinToString(" · ").ifEmpty { null }

private val ACCURACY_CLASS_TEXTS = intArrayOf(
    R.string.accuracy_class_0, R.string.accuracy_class_1, R.string.accuracy_class_2,
    R.string.accuracy_class_3, R.string.accuracy_class_4,
)

/** One label per accuracy class, in the order of `AccuracyClasses.LABELS`. */
@Composable
fun accuracyClassLabels(): List<String> = ACCURACY_CLASS_TEXTS.map { stringResource(it) }

@Composable
fun accuracyClassLabel(index: Int): String = stringResource(ACCURACY_CLASS_TEXTS[index.coerceIn(ACCURACY_CLASS_TEXTS.indices)])

/** A count for a plurals resource; log counters are Long. */
fun Long.asQuantity(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
