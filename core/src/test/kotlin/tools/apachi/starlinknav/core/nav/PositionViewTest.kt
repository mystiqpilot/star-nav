package tools.apachi.starlinknav.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class PositionViewTest {
    @Test
    fun coordinatesHaveSixDecimalsWhateverTheLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("uk-UA"))
            assertEquals("50.450100, 30.523400", PositionFormat.latLon(50.4501, 30.5234))
            assertEquals("-33.868820, 151.209296", PositionFormat.latLon(-33.86882, 151.2092955))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun geoUriMarksTheLabelledPoint() {
        assertEquals("geo:50.450100,30.523400?q=50.450100,30.523400(Star%20Nav%20GPS)", PositionFormat.geoUri(50.4501, 30.5234, "Star Nav GPS"))
    }

    @Test
    fun geoUriKeepsTheLabelFromEndingEarlyOrBreakingTheQuery() {
        // Brackets are dropped; & = ? # and non-ASCII letters are percent-encoded.
        assertEquals(
            "geo:-1.000000,2.000000?q=-1.000000,2.000000(a%26b%3Dc%3F%23%20%D0%97%D0%BE%D1%80%D1%8F)",
            PositionFormat.geoUri(-1.0, 2.0, "(a&b=c?# Зоря)"),
        )
    }

    @Test
    fun geoUriWithoutALabelIsAPlainPin() {
        assertEquals("geo:1.500000,2.500000?q=1.500000,2.500000", PositionFormat.geoUri(1.5, 2.5, " () "))
    }

    @Test
    fun dishStateFollowsTheOrderOfTheChecks() {
        fun s(unreachable: Boolean = false, restarting: Boolean = false, cutoff: Boolean = false, refusal: LocationRefusal? = null, age: Double? = 1.0) =
            dishPositionState(unreachable, restarting, cutoff, refusal, age)
        assertEquals(DishPositionState.OK, s())
        assertEquals(DishPositionState.OK, s(age = 5.0))
        assertEquals(DishPositionState.NO_FIX, s(age = 5.1))
        assertEquals(DishPositionState.NO_FIX, s(age = null))
        assertEquals(DishPositionState.CUT_OFF, s(cutoff = true))
        // A cut-off needs a dish that answers; an unreachable one wins, and a known restart is named as one.
        assertEquals(DishPositionState.UNREACHABLE, s(unreachable = true, cutoff = true, age = null))
        assertEquals(DishPositionState.RESTARTING, s(unreachable = true, restarting = true, cutoff = true))
        // The hint alone changes nothing while the dish answers.
        assertEquals(DishPositionState.OK, s(restarting = true))
        // A refusal of get_location is named whatever the fix age: with a fresh one the position comes from the diagnostics
        // (the card says so), without one there is none; a cut-off or a silent dish comes first.
        assertEquals(DishPositionState.POLICY_DENIED, s(refusal = LocationRefusal.POLICY_DENIED))
        assertEquals(DishPositionState.POLICY_DENIED, s(refusal = LocationRefusal.POLICY_DENIED, age = null))
        assertEquals(DishPositionState.UNIMPLEMENTED, s(refusal = LocationRefusal.UNIMPLEMENTED, age = 30.0))
        assertEquals(DishPositionState.NOT_ENABLED, s(refusal = LocationRefusal.NOT_ENABLED))
        assertEquals(DishPositionState.CUT_OFF, s(cutoff = true, refusal = LocationRefusal.NOT_ENABLED))
        assertEquals(DishPositionState.UNREACHABLE, s(unreachable = true, refusal = LocationRefusal.POLICY_DENIED))
        for (st in DishPositionState.entries) {
            assertEquals(st in setOf(DishPositionState.POLICY_DENIED, DishPositionState.UNIMPLEMENTED, DishPositionState.NOT_ENABLED), st.isRefusal, "$st")
        }
    }
}
