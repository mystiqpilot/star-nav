package tools.apachi.starlinknav.core.nav

import java.net.URLEncoder
import java.util.Locale

/**
 * What the Position page shows and hands on, kept out of the UI so it can be tested: how coordinates are written (and
 * copied), the `geo:` link the maps button opens, and the dish card's state.
 */
object PositionFormat {
    /** Decimal degrees with six decimals (about 0.1 m), the same text on screen and on the clipboard. */
    fun latLon(lat: Double, lon: Double): String = String.format(Locale.ROOT, "%.6f, %.6f", lat, lon)

    /**
     * A `geo:` URI that shows a marked point: `geo:LAT,LON?q=LAT,LON(label)`. Maps apps put a pin with the label on it
     * (a bare `geo:LAT,LON` only moves the camera). Brackets in the label would end it early, so they are dropped; the
     * rest is percent-encoded (a space as %20, which every maps app reads, not as `+`).
     */
    fun geoUri(lat: Double, lon: Double, label: String): String {
        val point = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
        val name = URLEncoder.encode(label.filter { it != '(' && it != ')' }.trim(), "UTF-8").replace("+", "%20")
        return if (name.isEmpty()) "geo:$point?q=$point" else "geo:$point?q=$point($name)"
    }
}

/** What the Starlink card says about the dish's position, in the order the checks run. */
enum class DishPositionState {
    /** A recent position. */
    OK,

    /** The dish refuses location (speed-policy disablement): status still answers, no position. */
    CUT_OFF,

    /** `get_location` is refused by the plan's or the firmware's policy ([LocationRefusal.POLICY_DENIED]); the diagnostics may still give the position. */
    POLICY_DENIED,

    /** This firmware or plan does not offer `get_location` ([LocationRefusal.UNIMPLEMENTED]); the diagnostics may still give the position. */
    UNIMPLEMENTED,

    /** Location on the local network is off on the dish ([LocationRefusal.NOT_ENABLED]): the user must enable it. */
    NOT_ENABLED,

    /** The dish answers but gave no usable position lately. */
    NO_FIX,

    /** The dish does not answer at all (Wi-Fi gone, switched off). */
    UNREACHABLE,

    /** The dish does not answer and a restart is under way (a restart command went out, or the planned update restart is due). */
    RESTARTING,
}

/**
 * @param unreachable the dish does not answer: the last status failed in transport, or no answer for a few seconds
 * @param restartingHint a restart is known to be under way (see [DishPositionState.RESTARTING])
 * @param cutoffActive a speed-policy cut-off is running
 * @param refusal `get_location` is refused for one of the three reasons; shown whatever the fix age, because the position
 *   then comes from the diagnostics (fresh [fixAgeS]) or not at all, and the card says which
 * @param fixAgeS age of the latest dish position (AUTO, or the diagnostics standing in); null = none yet
 * @param staleS a position older than this counts as no fix
 */
fun dishPositionState(
    unreachable: Boolean,
    restartingHint: Boolean,
    cutoffActive: Boolean,
    refusal: LocationRefusal?,
    fixAgeS: Double?,
    staleS: Double = 5.0,
): DishPositionState = when {
    unreachable && restartingHint -> DishPositionState.RESTARTING
    unreachable -> DishPositionState.UNREACHABLE
    cutoffActive -> DishPositionState.CUT_OFF
    refusal == LocationRefusal.POLICY_DENIED -> DishPositionState.POLICY_DENIED
    refusal == LocationRefusal.UNIMPLEMENTED -> DishPositionState.UNIMPLEMENTED
    refusal == LocationRefusal.NOT_ENABLED -> DishPositionState.NOT_ENABLED
    fixAgeS == null || fixAgeS > staleS -> DishPositionState.NO_FIX
    else -> DishPositionState.OK
}

/** The three refusal states of [DishPositionState]. */
val DishPositionState.isRefusal: Boolean
    get() = this == DishPositionState.POLICY_DENIED || this == DishPositionState.UNIMPLEMENTED || this == DishPositionState.NOT_ENABLED
