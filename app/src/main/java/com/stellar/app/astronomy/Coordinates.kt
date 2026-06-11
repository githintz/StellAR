package com.stellar.app.astronomy

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Position on the celestial sphere (J2000 equatorial frame), degrees. */
data class Equatorial(val raDeg: Double, val decDeg: Double)

/**
 * Topocentric horizontal coordinates, degrees.
 * Azimuth is measured from true north, increasing eastward.
 */
data class Horizontal(val azDeg: Double, val altDeg: Double)

object Coordinates {

    const val DEG_TO_RAD = Math.PI / 180.0
    const val RAD_TO_DEG = 180.0 / Math.PI

    /**
     * Equatorial -> horizontal conversion for an observer at
     * [latDeg] with local sidereal time [lstDeg].
     */
    fun equatorialToHorizontal(
        raDeg: Double,
        decDeg: Double,
        latDeg: Double,
        lstDeg: Double
    ): Horizontal {
        val h = (lstDeg - raDeg) * DEG_TO_RAD // hour angle
        val dec = decDeg * DEG_TO_RAD
        val lat = latDeg * DEG_TO_RAD

        // East-North-Up unit vector of the object.
        val east = -cos(dec) * sin(h)
        val north = cos(lat) * sin(dec) - sin(lat) * cos(dec) * cos(h)
        val up = sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(h)

        val az = AstroTime.normalizeDegrees(atan2(east, north) * RAD_TO_DEG)
        val alt = asin(up.coerceIn(-1.0, 1.0)) * RAD_TO_DEG
        return Horizontal(az, alt)
    }

    /**
     * Unit vector of an equatorial position:
     * x toward (ra=0, dec=0), y toward (ra=90, dec=0), z toward the NCP.
     * Writes x,y,z into [out] at [offset].
     */
    fun equatorialToUnitVector(
        raDeg: Double,
        decDeg: Double,
        out: FloatArray,
        offset: Int = 0
    ) {
        val ra = raDeg * DEG_TO_RAD
        val dec = decDeg * DEG_TO_RAD
        val cd = cos(dec)
        out[offset] = (cd * cos(ra)).toFloat()
        out[offset + 1] = (cd * sin(ra)).toFloat()
        out[offset + 2] = sin(dec).toFloat()
    }

    /**
     * 4x4 column-major matrix mapping J2000 equatorial unit vectors to the
     * local East-North-Up frame for latitude [latDeg] and sidereal time
     * [lstDeg]. Used as the "model" matrix when rendering the sky.
     */
    fun equatorialToEnuMatrix(latDeg: Double, lstDeg: Double, out: FloatArray) {
        val lat = latDeg * DEG_TO_RAD
        val lst = lstDeg * DEG_TO_RAD
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val sinLst = sin(lst)
        val cosLst = cos(lst)

        // M = Horizon(lat) * Rz(-lst), expanded.
        // Horizon(lat) rows: E=(0,1,0)  N=(-sinLat,0,cosLat)  U=(cosLat,0,sinLat)
        // Column-major storage: out[col * 4 + row].
        out[0] = (-sinLst).toFloat()           // row E, col x
        out[1] = (-sinLat * cosLst).toFloat()  // row N, col x
        out[2] = (cosLat * cosLst).toFloat()   // row U, col x
        out[3] = 0f
        out[4] = cosLst.toFloat()              // row E, col y
        out[5] = (-sinLat * sinLst).toFloat()  // row N, col y
        out[6] = (cosLat * sinLst).toFloat()   // row U, col y
        out[7] = 0f
        out[8] = 0f
        out[9] = cosLat.toFloat()
        out[10] = sinLat.toFloat()
        out[11] = 0f
        out[12] = 0f
        out[13] = 0f
        out[14] = 0f
        out[15] = 1f
    }
}
