package tools.apachi.starlinknav.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val SEC = 1_000_000_000L

/** Synthetic sentences at public reference points (Greenwich, Sydney); nothing from a recorded drive. */
class NmeaFixesTest {
    @Test
    fun rmcAndGgaGiveThePositionInDegrees() {
        val rmc = assertNotNull(NmeaFixes.positionOf("\$GNRMC,174000.50,A,5128.60000,N,00000.00000,E,20.0,45.0,261026,,,A*6B"))
        assertEquals(51.0 + 28.6 / 60.0, rmc.lat, 1e-9)
        assertEquals(0.0, rmc.lon, 1e-9)
        assertEquals(17 * 3_600_000L + 40 * 60_000L + 500L, rmc.todMs)
        assertNull(rmc.hdop)
        // south and west are negative; a GGA carries its HDOP
        val gga = assertNotNull(NmeaFixes.positionOf("\$GPGGA,120000.00,3352.1200,S,15112.3600,E,1,08,0.9,10.0,M,30.0,M,,*xx"))
        assertEquals(-(33.0 + 52.12 / 60.0), gga.lat, 1e-9)
        assertEquals(151.0 + 12.36 / 60.0, gga.lon, 1e-9)
        assertEquals(0.9, assertNotNull(gga.hdop), 1e-9)
        val west = assertNotNull(NmeaFixes.positionOf("\$GPRMC,120000.00,A,3352.1200,S,15112.3600,W,0.0,0.0,010126,,,A*xx"))
        assertEquals(-(151.0 + 12.36 / 60.0), west.lon, 1e-9)
        // differential and RTK qualities are fixes too; a line ending after the checksum does no harm
        assertNotNull(NmeaFixes.positionOf("\$GPGGA,120000.00,3352.1200,S,15112.3600,E,2,08,0.9,10.0,M,30.0,M,3,0120*xx\r\n"))
        assertNotNull(NmeaFixes.positionOf("\$GPGGA,120000.00,3352.1200,S,15112.3600,E,4,08,0.9,10.0,M,30.0,M,,*xx"))
    }

    @Test
    fun sentencesWithoutAFixOrOfOtherKindsGiveNothing() {
        assertNull(NmeaFixes.positionOf("\$GNRMC,174000.50,V,,,,,,,261026,,,N*xx"), "RMC without a fix")
        assertNull(NmeaFixes.positionOf("\$GNGGA,174000.50,,,,,0,00,,,M,,M,,*xx"), "GGA without a fix")
        assertNull(NmeaFixes.positionOf("\$GNGGA,174000.50,5128.6,N,00000.0,E,6,04,2.0,10.0,M,30.0,M,,*xx"), "estimated (dead reckoning) is no fix")
        assertNull(NmeaFixes.positionOf("\$GNRMC,,A,5128.6,N,00000.0,E,20.0,45.0,261026,,,A*xx"), "no time")
        assertNull(NmeaFixes.positionOf("\$GNRMC,174000.50,A,5128.6,X,00000.0,E,20.0,45.0,261026,,,A*xx"), "bad hemisphere")
        assertNull(NmeaFixes.positionOf("\$GNRMC,174000.50,A,9928.6,N,00000.0,E,20.0,45.0,261026,,,A*xx"), "off the globe")
        assertNull(NmeaFixes.positionOf("\$GNRMC,174000.50,A,51.4767,N,0.0,E,20.0,45.0,261026,,,A*xx"), "degrees where minutes belong")
        assertNull(NmeaFixes.positionOf("\$GPGSA,A,3,01,02,,,,,,,,,,,1.5,0.9,1.2*xx"))
        assertNull(NmeaFixes.positionOf("\$GPGSV,3,1,12,01,40,083,46*xx"))
        assertNull(NmeaFixes.positionOf("\$PGLOR,1,FIX,1.0,1.0*xx"))
        assertNull(NmeaFixes.positionOf("garbage"))
        assertNull(NmeaFixes.positionOf(""))
        // the HDOP of a GGA and a GSA; nothing from the rest
        assertEquals(0.9, assertNotNull(NmeaFixes.hdopOf("\$GPGGA,120000.00,3352.1200,S,15112.3600,E,1,08,0.9,10.0,M,30.0,M,,*xx")), 1e-9)
        assertEquals(0.9, assertNotNull(NmeaFixes.hdopOf("\$GPGSA,A,3,01,02,,,,,,,,,,,1.5,0.9,1.2*xx")), 1e-9)
        assertEquals(1.1, assertNotNull(NmeaFixes.hdopOf("\$GNGSA,A,3,01,02,03,04,,,,,,,,,2.0,1.1,1.7,1*xx")), 1e-9, "NMEA 4.1 GSA with a system id")
        assertNull(NmeaFixes.hdopOf("\$GPGSA,A,1,,,,,,,,,,,,,,,*xx"))
        assertNull(NmeaFixes.hdopOf("\$GNRMC,174000.50,A,5128.6,N,00000.0,E,20.0,45.0,261026,,,A*xx"))
    }

    @Test
    fun oneFixPerEpochWithTheAccuracyFromTheLatestHdop() {
        val feed = NmeaFixes()
        var t = 100 * SEC
        // GGA then RMC of the same epoch: one fix, told the 15 m floor (HDOP 0.8 × 5 m is less)
        val first = assertNotNull(feed.onNmea("\$GNGGA,174000.50,5128.60000,N,00000.00000,E,1,12,0.8,130.0,M,30.0,M,,*5A", t))
        assertEquals(51.0 + 28.6 / 60.0, first.lat, 1e-9)
        assertEquals(NmeaFixes.FLOOR_M, first.accuracyM, 1e-9)
        assertEquals(t, first.atNs)
        assertNull(feed.onNmea("\$GNRMC,174000.50,A,5128.60000,N,00000.00000,E,20.0,45.0,261026,,,A*6B", t + 20_000_000L), "the second sentence of the epoch")
        assertNull(feed.onNmea("\$GPGSV,3,1,12,01,40,083,46*xx", t + 30_000_000L))
        // the next epoch's RMC alone is a fix; the HDOP of 2 s ago still applies
        t += SEC
        val second = assertNotNull(feed.onNmea("\$GNRMC,174001.50,A,5128.61000,N,00000.00000,E,20.0,45.0,261026,,,A*6B", t))
        assertEquals(51.0 + 28.61 / 60.0, second.lat, 1e-9)
        assertEquals(NmeaFixes.FLOOR_M, second.accuracyM, 1e-9)
        // a worse geometry (GSA HDOP 4.0) widens the told accuracy to 20 m
        t += SEC
        assertNull(feed.onNmea("\$GPGSA,A,3,01,02,,,,,,,,,,,5.0,4.0,3.0*xx", t))
        val wide = assertNotNull(feed.onNmea("\$GNRMC,174002.50,A,5128.62000,N,00000.00000,E,20.0,45.0,261026,,,A*6B", t + 10_000_000L))
        assertEquals(4.0 * NmeaFixes.UERE_M, wide.accuracyM, 1e-9)
        // an HDOP older than 5 s no longer counts: back to the floor
        t += 6 * SEC
        val later = assertNotNull(feed.onNmea("\$GNRMC,174008.50,A,5128.68000,N,00000.00000,E,20.0,45.0,261026,,,A*6B", t))
        assertEquals(NmeaFixes.FLOOR_M, later.accuracyM, 1e-9)
        // a sentence without a fix changes nothing and is not a fix
        assertNull(feed.onNmea("\$GNRMC,174009.50,V,,,,,,,261026,,,N*xx", t + SEC))
        assertEquals(NmeaFixes.FLOOR_M, NmeaFixes.accuracyM(null), 1e-9)
        assertEquals(NmeaFixes.FLOOR_M, NmeaFixes.accuracyM(2.9), 1e-9)
        assertEquals(17.5, NmeaFixes.accuracyM(3.5), 1e-9)
    }
}
