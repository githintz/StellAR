package com.stellar.app.astronomy

import org.junit.Assert.assertEquals
import org.junit.Test

class AstroTimeTest {

    @Test
    fun `julian day of J2000 epoch`() {
        // 2000-01-01T12:00:00Z
        val epochMillis = 946_728_000_000L
        assertEquals(2451545.0, AstroTime.julianDay(epochMillis), 1e-9)
    }

    @Test
    fun `julian day of unix epoch`() {
        assertEquals(2440587.5, AstroTime.julianDay(0L), 1e-9)
    }

    @Test
    fun `gmst at J2000 matches almanac`() {
        // Known value: 18h 41m 50.548s = 280.46062 degrees.
        assertEquals(280.46062, AstroTime.gmstDegrees(2451545.0), 0.001)
    }

    @Test
    fun `lst adds east longitude`() {
        val jd = 2451545.0
        val gmst = AstroTime.gmstDegrees(jd)
        assertEquals(
            AstroTime.normalizeDegrees(gmst + 30.0),
            AstroTime.lstDegrees(jd, 30.0),
            1e-9
        )
    }

    @Test
    fun `degree normalization`() {
        assertEquals(330.0, AstroTime.normalizeDegrees(-30.0), 1e-12)
        assertEquals(10.0, AstroTime.normalizeDegrees(730.0), 1e-12)
        assertEquals(0.0, AstroTime.normalizeDegrees(360.0), 1e-12)
    }
}
