package com.stellar.app.astronomy

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geocentric apparent place of a solar-system body. */
data class PlanetPosition(
    val name: String,
    val raDeg: Double,
    val decDeg: Double,
    val mag: Float
)

/**
 * Low-precision solar-system ephemeris.
 *
 * Planets use the Keplerian mean elements from E.M. Standish,
 * "Approximate Positions of the Planets" (JPL), valid 1800-2050 with
 * errors of a few arc-minutes - far below what phone sensors can resolve.
 * The Moon uses the low-precision series from the Astronomical Almanac
 * (~0.3 deg). Positions are returned in the J2000 equatorial frame
 * (precession since J2000 is ignored, <0.5 deg for decades around now).
 *
 * Improvement path: swap in VSOP87 / ELP2000 truncations behind the same
 * [compute] signature for arc-second accuracy.
 */
object Planets {

    private const val DEG = Coordinates.DEG_TO_RAD

    // name, a (au), e, I (deg), L (deg), longPeri (deg), longNode (deg)
    // followed by their rates per Julian century.
    private class Elements(
        val name: String,
        val a0: Double, val aDot: Double,
        val e0: Double, val eDot: Double,
        val i0: Double, val iDot: Double,
        val l0: Double, val lDot: Double,
        val w0: Double, val wDot: Double,   // longitude of perihelion
        val o0: Double, val oDot: Double,   // longitude of ascending node
        val mag: Float                       // typical apparent magnitude
    )

    private val EARTH = Elements(
        "Earth",
        1.00000261, 0.00000562,
        0.01671123, -0.00004392,
        -0.00001531, -0.01294668,
        100.46457166, 35999.37244981,
        102.93768193, 0.32327364,
        0.0, 0.0,
        0f
    )

    private val PLANETS = listOf(
        Elements(
            "Mercury",
            0.38709927, 0.00000037,
            0.20563593, 0.00001906,
            7.00497902, -0.00594749,
            252.25032350, 149472.67411175,
            77.45779628, 0.16047689,
            48.33076593, -0.12534081,
            0.2f
        ),
        Elements(
            "Venus",
            0.72333566, 0.00000390,
            0.00677672, -0.00004107,
            3.39467605, -0.00078890,
            181.97909950, 58517.81538729,
            131.60246718, 0.00268329,
            76.67984255, -0.27769418,
            -4.1f
        ),
        Elements(
            "Mars",
            1.52371034, 0.00001847,
            0.09339410, 0.00007882,
            1.84969142, -0.00813131,
            -4.55343205, 19140.30268499,
            -23.94362959, 0.44441088,
            49.55953891, -0.29257343,
            0.7f
        ),
        Elements(
            "Jupiter",
            5.20288700, -0.00011607,
            0.04838624, -0.00013253,
            1.30439695, -0.00183714,
            34.39644051, 3034.74612775,
            14.72847983, 0.21252668,
            100.47390909, 0.20469106,
            -2.2f
        ),
        Elements(
            "Saturn",
            9.53667594, -0.00125060,
            0.05386179, -0.00050991,
            2.48599187, 0.00193609,
            49.95424423, 1222.49362201,
            92.59887831, -0.41897216,
            113.66242448, -0.28867794,
            0.5f
        ),
        Elements(
            "Uranus",
            19.18916464, -0.00196176,
            0.04725744, -0.00004397,
            0.77263783, -0.00242939,
            313.23810451, 428.48202785,
            170.95427630, 0.40805281,
            74.01692503, 0.04240589,
            5.7f
        ),
        Elements(
            "Neptune",
            30.06992276, 0.00026291,
            0.00859048, 0.00005105,
            1.77004347, 0.00035372,
            -55.12002969, 218.45945325,
            44.96476227, -0.32241464,
            131.78422574, -0.00508664,
            7.9f
        )
    )

    /** All bodies (Sun, Moon, seven planets) for Julian Day [jd]. */
    fun compute(jd: Double): List<PlanetPosition> {
        val t = AstroTime.julianCenturies(jd)
        val earth = heliocentric(EARTH, t)

        val result = ArrayList<PlanetPosition>(9)

        // The Sun is at the origin: its geocentric vector is -earth.
        result.add(toEquatorial("Sun", -earth[0], -earth[1], -earth[2], -26.7f, t))
        result.add(moon(t))

        for (p in PLANETS) {
            val h = heliocentric(p, t)
            result.add(
                toEquatorial(
                    p.name,
                    h[0] - earth[0], h[1] - earth[1], h[2] - earth[2],
                    p.mag, t
                )
            )
        }
        return result
    }

    /** Heliocentric ecliptic-of-J2000 rectangular coordinates (au). */
    private fun heliocentric(el: Elements, t: Double): DoubleArray {
        val a = el.a0 + el.aDot * t
        val e = el.e0 + el.eDot * t
        val i = (el.i0 + el.iDot * t) * DEG
        val l = el.l0 + el.lDot * t
        val w = el.w0 + el.wDot * t          // longitude of perihelion
        val o = (el.o0 + el.oDot * t) * DEG  // longitude of ascending node

        val argPeri = (w * DEG) - o          // argument of perihelion
        var m = (l - w) % 360.0              // mean anomaly, degrees
        if (m > 180.0) m -= 360.0
        if (m < -180.0) m += 360.0
        val mRad = m * DEG

        // Kepler's equation, Newton iteration.
        var ecc = mRad + e * sin(mRad)
        repeat(6) {
            val delta = (ecc - e * sin(ecc) - mRad) / (1.0 - e * cos(ecc))
            ecc -= delta
        }

        // Position in the orbital plane.
        val xp = a * (cos(ecc) - e)
        val yp = a * sqrt(1.0 - e * e) * sin(ecc)

        // Rotate to the ecliptic frame: Rz(node) * Rx(incl) * Rz(argPeri).
        val cw = cos(argPeri); val sw = sin(argPeri)
        val co = cos(o); val so = sin(o)
        val ci = cos(i); val si = sin(i)

        val x = (cw * co - sw * so * ci) * xp + (-sw * co - cw * so * ci) * yp
        val y = (cw * so + sw * co * ci) * xp + (-sw * so + cw * co * ci) * yp
        val z = (sw * si) * xp + (cw * si) * yp
        return doubleArrayOf(x, y, z)
    }

    /** Geocentric ecliptic rectangular -> RA/Dec. */
    private fun toEquatorial(
        name: String,
        x: Double,
        y: Double,
        z: Double,
        mag: Float,
        t: Double
    ): PlanetPosition {
        val eps = (23.439291 - 0.0130042 * t) * DEG
        val ce = cos(eps); val se = sin(eps)
        val xe = x
        val ye = y * ce - z * se
        val ze = y * se + z * ce
        val r = sqrt(xe * xe + ye * ye + ze * ze)
        val ra = AstroTime.normalizeDegrees(atan2(ye, xe) * Coordinates.RAD_TO_DEG)
        val dec = asin((ze / r).coerceIn(-1.0, 1.0)) * Coordinates.RAD_TO_DEG
        return PlanetPosition(name, ra, dec, mag)
    }

    /**
     * Low-precision geocentric Moon (Astronomical Almanac), ~0.3 deg.
     */
    private fun moon(t: Double): PlanetPosition {
        fun sinDeg(d: Double) = sin(d * DEG)

        val lambda = 218.32 + 481267.8813 * t +
            6.29 * sinDeg(134.9 + 477198.85 * t) -
            1.27 * sinDeg(259.2 - 413335.38 * t) +
            0.66 * sinDeg(235.7 + 890534.23 * t) +
            0.21 * sinDeg(269.9 + 954397.70 * t) -
            0.19 * sinDeg(357.5 + 35999.05 * t) -
            0.11 * sinDeg(186.6 + 966404.05 * t)

        val beta = 5.13 * sinDeg(93.3 + 483202.03 * t) +
            0.28 * sinDeg(228.2 + 960400.87 * t) -
            0.28 * sinDeg(318.3 + 6003.18 * t) -
            0.17 * sinDeg(217.6 - 407332.20 * t)

        val lRad = lambda * DEG
        val bRad = beta * DEG
        val x = cos(bRad) * cos(lRad)
        val y = cos(bRad) * sin(lRad)
        val z = sin(bRad)
        return toEquatorial("Moon", x, y, z, -12.7f, t)
    }
}
