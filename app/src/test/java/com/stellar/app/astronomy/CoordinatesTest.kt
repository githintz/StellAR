package com.stellar.app.astronomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinatesTest {

    @Test
    fun `object on the meridian culminates due south`() {
        // Sirius from latitude 40N at the moment it crosses the meridian.
        val ra = 101.2872
        val dec = -16.7161
        val lat = 40.0
        val h = Coordinates.equatorialToHorizontal(ra, dec, lat, lstDeg = ra)
        assertEquals(180.0, h.azDeg, 1e-6)
        assertEquals(90.0 - (lat - dec), h.altDeg, 1e-6)
    }

    @Test
    fun `north celestial pole altitude equals latitude`() {
        val h = Coordinates.equatorialToHorizontal(0.0, 90.0, 52.5, 123.0)
        assertEquals(52.5, h.altDeg, 1e-6)
        assertEquals(0.0, h.azDeg, 1e-6)
    }

    @Test
    fun `equatorial star rises due east`() {
        // Hour angle -90 deg, declination 0: on the horizon, due east.
        val lst = 200.0
        val ra = lst + 90.0
        val h = Coordinates.equatorialToHorizontal(ra, 0.0, 35.0, lst)
        assertEquals(90.0, h.azDeg, 1e-6)
        assertEquals(0.0, h.altDeg, 1e-6)
    }

    @Test
    fun `unit vectors are normalized`() {
        val v = FloatArray(3)
        for (ra in intArrayOf(0, 45, 137, 250, 359)) {
            for (dec in intArrayOf(-89, -30, 0, 30, 89)) {
                Coordinates.equatorialToUnitVector(ra.toDouble(), dec.toDouble(), v)
                val norm = Math.sqrt(
                    (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()
                )
                assertEquals(1.0, norm, 1e-5)
            }
        }
    }

    @Test
    fun `enu matrix agrees with horizontal conversion`() {
        val lat = 47.3
        val lst = 211.7
        val ra = 101.2872
        val dec = -16.7161

        val m = FloatArray(16)
        Coordinates.equatorialToEnuMatrix(lat, lst, m)
        val v = FloatArray(3)
        Coordinates.equatorialToUnitVector(ra, dec, v)

        // Column-major multiply.
        val e = m[0] * v[0] + m[4] * v[1] + m[8] * v[2]
        val n = m[1] * v[0] + m[5] * v[1] + m[9] * v[2]
        val u = m[2] * v[0] + m[6] * v[1] + m[10] * v[2]

        val h = Coordinates.equatorialToHorizontal(ra, dec, lat, lst)
        val az = Math.toDegrees(Math.atan2(e.toDouble(), n.toDouble()))
        val alt = Math.toDegrees(Math.asin(u.toDouble().coerceIn(-1.0, 1.0)))

        assertEquals(h.azDeg, AstroTime.normalizeDegrees(az), 1e-3)
        assertEquals(h.altDeg, alt, 1e-3)
        assertTrue(Math.abs(e * e + n * n + u * u - 1f) < 1e-4f)
    }
}
