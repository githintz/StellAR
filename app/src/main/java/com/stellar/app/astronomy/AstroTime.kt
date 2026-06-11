package com.stellar.app.astronomy

/**
 * Time scales used in positional astronomy.
 *
 * Everything works from the Julian Day (JD), a continuous day count since
 * 4713 BC. We treat system time (UTC) as UT1 and ignore delta-T; the error
 * (~minutes of arc at most) is far below the accuracy of consumer sensors.
 */
object AstroTime {

    const val J2000_JD = 2451545.0
    private const val MILLIS_PER_DAY = 86_400_000.0

    /** Unix epoch (1970-01-01T00:00:00Z) expressed as a Julian Day. */
    private const val UNIX_EPOCH_JD = 2440587.5

    /** Julian Day for a Unix timestamp in milliseconds. */
    fun julianDay(epochMillis: Long): Double =
        UNIX_EPOCH_JD + epochMillis / MILLIS_PER_DAY

    /** Julian centuries since J2000.0. */
    fun julianCenturies(jd: Double): Double = (jd - J2000_JD) / 36525.0

    /**
     * Greenwich Mean Sidereal Time in degrees [0, 360), IAU 1982 model.
     */
    fun gmstDegrees(jd: Double): Double {
        val d = jd - J2000_JD
        val t = d / 36525.0
        val gmst = 280.46061837 +
            360.98564736629 * d +
            0.000387933 * t * t -
            t * t * t / 38710000.0
        return normalizeDegrees(gmst)
    }

    /** Local Mean Sidereal Time in degrees for an east-positive longitude. */
    fun lstDegrees(jd: Double, longitudeDeg: Double): Double =
        normalizeDegrees(gmstDegrees(jd) + longitudeDeg)

    /** Normalizes an angle to [0, 360). */
    fun normalizeDegrees(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }
}
