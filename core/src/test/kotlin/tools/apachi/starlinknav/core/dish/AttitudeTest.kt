package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.Quaternion
import kotlin.test.Test
import kotlin.test.assertEquals

class AttitudeTest {
    /** Status of a Gen 3 Mini (firmware 2026.05.13) on 2026-10-14, reporting boresight -121.1/69.9°. */
    @Test
    fun realMiniQuaternionReproducesReportedBoresight() {
        val at = Quaternion(q_scalar = -0.0573f, q_x = 0.2035f, q_y = 0.9634f, q_z = -0.1646f).attitude()
        assertEquals(-121.1, at.normalAzimuthDeg, 0.1)
        assertEquals(69.9, at.normalElevationDeg, 0.1)
        assertEquals(155.7, at.headingDeg, 0.1)
        assertEquals(-2.5, at.xElevationDeg, 0.1)
    }

    @Test
    fun fakeDishQuaternionAgreesWithItsBoresight() {
        val sim = FakeDishSimulation(tiltDeg = 20.0)
        val status = sim.handle(DishCallKind.STATUS.request(1)).first!!.dish_get_status!!
        val at = status.ned2dish_quaternion!!.attitude()
        assertEquals(70.0, at.normalElevationDeg, 0.01)
        assertEquals(sim.dishHeadingDeg(sim.truth().headingDeg), at.headingDeg, 0.5)
        assertEquals(status.boresight_azimuth_deg.toDouble(), at.normalAzimuthDeg, 0.01)
    }
}
