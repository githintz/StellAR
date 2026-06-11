package com.stellar.app.astronomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class PlanetsTest {

    private fun separationDeg(a: PlanetPosition, b: PlanetPosition): Double {
        val d = Coordinates.DEG_TO_RAD
        val cosSep = sin(a.decDeg * d) * sin(b.decDeg * d) +
            cos(a.decDeg * d) * cos(b.decDeg * d) *
            cos((a.raDeg - b.raDeg) * d)
        return acos(cosSep.coerceIn(-1.0, 1.0)) / d
    }

    @Test
    fun `all bodies are present with valid coordinates`() {
        val bodies = Planets.compute(2451545.0)
        val names = bodies.map { it.name }
        for (expected in listOf(
            "Sun", "Moon", "Mercury", "Venus", "Mars",
            "Jupiter", "Saturn", "Uranus", "Neptune"
        )) {
            assertTrue("missing $expected", names.contains(expected))
        }
        for (b in bodies) {
            assertTrue("${b.name} ra=${b.raDeg}", b.raDeg >= 0.0 && b.raDeg < 360.0)
            assertTrue("${b.name} dec=${b.decDeg}", b.decDeg >= -90.0 && b.decDeg <= 90.0)
        }
    }

    @Test
    fun `sun sits at the vernal equinox point in march 2000`() {
        // 2000-03-20 07:35 UTC, the March equinox.
        val jd = 2451623.816
        val sun = Planets.compute(jd).first { it.name == "Sun" }
        assertEquals(0.0, sun.decDeg, 0.5)
        val raError = min(sun.raDeg, 360.0 - sun.raDeg)
        assertTrue("sun ra=${sun.raDeg}", raError < 1.0)
    }

    @Test
    fun `sun reaches maximum declination at june solstice`() {
        // 2000-06-21 02:48 UTC.
        val jd = 2451716.617
        val sun = Planets.compute(jd).first { it.name == "Sun" }
        assertEquals(23.44, sun.decDeg, 0.3)
    }

    @Test
    fun `moon moves about 13 degrees per day`() {
        val day0 = Planets.compute(2451545.0).first { it.name == "Moon" }
        val day1 = Planets.compute(2451546.0).first { it.name == "Moon" }
        val motion = separationDeg(day0, day1)
        assertTrue("moon moved $motion deg", motion in 10.0..17.0)
    }

    @Test
    fun `outer planets barely move in a day`() {
        val day0 = Planets.compute(2451545.0).first { it.name == "Neptune" }
        val day1 = Planets.compute(2451546.0).first { it.name == "Neptune" }
        assertTrue(separationDeg(day0, day1) < 0.1)
    }
}
