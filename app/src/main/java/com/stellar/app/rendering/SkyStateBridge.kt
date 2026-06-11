package com.stellar.app.rendering

import android.opengl.Matrix
import com.stellar.app.astronomy.PlanetPosition

/**
 * Thread-safe blackboard between the UI thread (sensors, ViewModel),
 * the GL render thread and the label overlay.
 *
 * Inputs (written by UI/sensor thread, read by GL thread):
 *   - device view matrix from the orientation sensor
 *   - observer location, magnetic declination, simulated-time offset
 *   - display toggles
 *
 * Outputs (written by GL thread each frame, read by overlay):
 *   - MVP matrix for equatorial coordinates
 *   - view-projection matrix for the local ENU frame (horizon/cardinals)
 *   - latest planet ephemeris snapshot
 */
class SkyStateBridge {

    val lock = Any()

    // ---- inputs ----
    private val viewMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    @Volatile var latitudeDeg: Double = 51.4769   // Greenwich until a GPS fix
    @Volatile var longitudeDeg: Double = 0.0
    @Volatile var magneticDeclinationDeg: Float = 0f
    @Volatile var hasRealLocation: Boolean = false

    /** Added to system time; lets the user scrub time. */
    @Volatile var timeOffsetMillis: Long = 0L

    @Volatile var showConstellations: Boolean = true
    @Volatile var nightMode: Boolean = false

    /** Vertical field of view (degrees) along the longer screen axis. */
    @Volatile var fovLongAxisDeg: Float = 67f
    @Volatile var fovShortAxisDeg: Float = 51f

    /** Pixel density scale for point sizes. */
    @Volatile var densityScale: Float = 2f

    // ---- outputs ----
    private val mvpEquatorial = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val vpTrueEnu = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    @Volatile var viewportWidth: Int = 1
    @Volatile var viewportHeight: Int = 1
    @Volatile var frameValid: Boolean = false

    private var planets: List<PlanetPosition> = emptyList()

    fun simTimeMillis(): Long = System.currentTimeMillis() + timeOffsetMillis

    fun setViewMatrix(m: FloatArray) {
        synchronized(lock) { System.arraycopy(m, 0, viewMatrix, 0, 16) }
    }

    fun copyViewMatrix(out: FloatArray) {
        synchronized(lock) { System.arraycopy(viewMatrix, 0, out, 0, 16) }
    }

    fun publishFrame(mvpEq: FloatArray, vpEnu: FloatArray, width: Int, height: Int) {
        synchronized(lock) {
            System.arraycopy(mvpEq, 0, mvpEquatorial, 0, 16)
            System.arraycopy(vpEnu, 0, vpTrueEnu, 0, 16)
        }
        viewportWidth = width
        viewportHeight = height
        frameValid = true
    }

    fun copyFrame(mvpEqOut: FloatArray, vpEnuOut: FloatArray): Boolean {
        if (!frameValid) return false
        synchronized(lock) {
            System.arraycopy(mvpEquatorial, 0, mvpEqOut, 0, 16)
            System.arraycopy(vpTrueEnu, 0, vpEnuOut, 0, 16)
        }
        return true
    }

    fun publishPlanets(list: List<PlanetPosition>) {
        synchronized(lock) { planets = list }
    }

    fun planetSnapshot(): List<PlanetPosition> = synchronized(lock) { planets }
}
