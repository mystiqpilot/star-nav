package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.DishAlerts
import tools.apachi.starlinknav.core.dish.proto.DishGetHistoryResponse
import tools.apachi.starlinknav.core.dish.proto.DishGetObstructionMapResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class TelemetryTest {
    @Test
    fun historyUnrollsTheRingBuffer() {
        // Buffer of 5, 7 samples written (values = sample number): slots hold 5,6,2,3,4.
        val h = DishHistory.of(DishGetHistoryResponse(current = 7, pop_ping_latency_ms = listOf(5f, 6f, 2f, 3f, 4f)))
        assertEquals(listOf(2f, 3f, 4f, 5f, 6f), h.latencyMs)
        // Not wrapped yet: only the written part.
        val young = DishHistory.of(DishGetHistoryResponse(current = 3, pop_ping_latency_ms = listOf(0f, 1f, 2f, 0f, 0f)))
        assertEquals(listOf(0f, 1f, 2f), young.latencyMs)
        assertEquals(emptyList(), young.powerInW)
    }

    @Test
    fun activeAlertsAndSkyCells() {
        assertEquals(listOf("thermal_throttle", "is_heating"), DishAlerts(thermal_throttle = true, is_heating = true).active())
        val map = DishGetObstructionMapResponse(num_rows = 2, num_cols = 2, snr = listOf(-1f, 0f, 0.4f, 1f))
        assertEquals(
            listOf(listOf(SkyCell.NO_DATA, SkyCell.BLOCKED), listOf(SkyCell.REDUCED, SkyCell.CLEAR)),
            map.cells(),
        )
        assertEquals(1.0 / 3, map.blockedFraction()!!, 1e-9)
    }
}
