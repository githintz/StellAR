package com.stellar.app.rendering

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.stellar.app.astronomy.AstroTime
import com.stellar.app.astronomy.Coordinates
import com.stellar.app.astronomy.PlanetPosition
import com.stellar.app.astronomy.Planets
import com.stellar.app.data.SkyData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/**
 * Renders the celestial sphere over the transparent camera view.
 *
 * Pipeline per vertex (a J2000 unit vector):
 *   clip = Projection * View(sensor) * Rz(declination) * EquatorialToEnu(lat, LST)
 *
 * Stars and planets are soft point sprites with additive blending;
 * constellation figures are line strips. Labels and hit-testing live in
 * the Canvas overlay, fed through [SkyStateBridge].
 */
class SkyRenderer(private val bridge: SkyStateBridge) : GLSurfaceView.Renderer {

    private companion object {
        const val TAG = "SkyRenderer"
        const val FLOATS_PER_STAR = 7 // x,y,z, r,g,b, size
        const val EPHEMERIS_REFRESH_MS = 2_000L

        const val POINT_VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform float uSizeScale;
            attribute vec3 aPos;
            attribute vec3 aColor;
            attribute float aSize;
            varying vec3 vColor;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                gl_PointSize = aSize * uSizeScale;
                vColor = aColor;
            }
        """

        const val POINT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vColor;
            uniform vec4 uTint;
            void main() {
                vec2 c = gl_PointCoord - vec2(0.5);
                float r = length(c) * 2.0;
                float a = max(0.0, 1.0 - r);
                a = a * a;
                gl_FragColor = vec4(vColor * uTint.rgb, a * uTint.a);
            }
        """

        const val LINE_VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPos;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        const val LINE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }

    @Volatile private var skyData: SkyData? = null
    @Volatile private var dataDirty = false

    // GL handles
    private var pointProgram = 0
    private var pointMvp = 0
    private var pointSizeScale = 0
    private var pointTint = 0
    private var pointPos = 0
    private var pointColor = 0
    private var pointSize = 0

    private var lineProgram = 0
    private var lineMvp = 0
    private var lineColor = 0
    private var linePos = 0

    private var starVbo = 0
    private var lineVbo = 0
    private var starCount = 0
    private var lineVertexCount = 0

    private var planetBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(16 * FLOATS_PER_STAR * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var planetCount = 0
    private var lastEphemerisMillis = 0L
    private var cachedPlanets: List<PlanetPosition> = emptyList()

    private var width = 1
    private var height = 1

    // Scratch matrices
    private val model = FloatArray(16)
    private val declination = FloatArray(16)
    private val modelMagnetic = FloatArray(16)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val vpTrueEnu = FloatArray(16)
    private val mvp = FloatArray(16)

    /** Safe to call from any thread; buffers are rebuilt on the GL thread. */
    fun setSkyData(data: SkyData) {
        skyData = data
        dataDirty = true
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

        pointProgram = buildProgram(POINT_VERTEX_SHADER, POINT_FRAGMENT_SHADER)
        pointMvp = GLES20.glGetUniformLocation(pointProgram, "uMvp")
        pointSizeScale = GLES20.glGetUniformLocation(pointProgram, "uSizeScale")
        pointTint = GLES20.glGetUniformLocation(pointProgram, "uTint")
        pointPos = GLES20.glGetAttribLocation(pointProgram, "aPos")
        pointColor = GLES20.glGetAttribLocation(pointProgram, "aColor")
        pointSize = GLES20.glGetAttribLocation(pointProgram, "aSize")

        lineProgram = buildProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        lineMvp = GLES20.glGetUniformLocation(lineProgram, "uMvp")
        lineColor = GLES20.glGetUniformLocation(lineProgram, "uColor")
        linePos = GLES20.glGetAttribLocation(lineProgram, "aPos")

        // Context may have been lost and recreated: force buffer rebuild.
        starVbo = 0
        lineVbo = 0
        if (skyData != null) dataDirty = true
        lastEphemerisMillis = 0L
    }

    override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
        width = max(1, w)
        height = max(1, h)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (dataDirty) {
            skyData?.let { buildStaticBuffers(it) }
            dataDirty = false
        }

        // --- matrices ---
        val simMillis = bridge.simTimeMillis()
        val jd = AstroTime.julianDay(simMillis)
        val lstDeg = AstroTime.lstDegrees(jd, bridge.longitudeDeg)

        Coordinates.equatorialToEnuMatrix(bridge.latitudeDeg, lstDeg, model)
        Matrix.setRotateM(declination, 0, bridge.magneticDeclinationDeg, 0f, 0f, 1f)
        Matrix.multiplyMM(modelMagnetic, 0, declination, 0, model, 0)

        bridge.copyViewMatrix(view)
        val aspect = width.toFloat() / height.toFloat()
        val fovY = if (aspect < 1f) bridge.fovLongAxisDeg else bridge.fovShortAxisDeg
        Matrix.perspectiveM(proj, 0, fovY, aspect, 0.1f, 10f)

        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        Matrix.multiplyMM(vpTrueEnu, 0, viewProj, 0, declination, 0)
        Matrix.multiplyMM(mvp, 0, viewProj, 0, modelMagnetic, 0)
        bridge.publishFrame(mvp, vpTrueEnu, width, height)

        refreshEphemeris(simMillis, jd)

        val night = bridge.nightMode

        // --- constellation lines ---
        if (bridge.showConstellations && lineVertexCount > 0) {
            GLES20.glUseProgram(lineProgram)
            GLES20.glUniformMatrix4fv(lineMvp, 1, false, mvp, 0)
            if (night) {
                GLES20.glUniform4f(lineColor, 0.55f, 0.10f, 0.10f, 0.45f)
            } else {
                GLES20.glUniform4f(lineColor, 0.35f, 0.55f, 0.85f, 0.45f)
            }
            GLES20.glLineWidth(max(1f, bridge.densityScale))
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, lineVbo)
            GLES20.glEnableVertexAttribArray(linePos)
            GLES20.glVertexAttribPointer(linePos, 3, GLES20.GL_FLOAT, false, 12, 0)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineVertexCount)
            GLES20.glDisableVertexAttribArray(linePos)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        // --- stars ---
        if (starCount > 0) {
            GLES20.glUseProgram(pointProgram)
            GLES20.glUniformMatrix4fv(pointMvp, 1, false, mvp, 0)
            GLES20.glUniform1f(pointSizeScale, bridge.densityScale * 0.8f)
            if (night) {
                GLES20.glUniform4f(pointTint, 1f, 0.25f, 0.2f, 1f)
            } else {
                GLES20.glUniform4f(pointTint, 1f, 1f, 1f, 1f)
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, starVbo)
            enablePointAttribs(vbo = true)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starCount)
            disablePointAttribs()
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        // --- planets, Sun, Moon ---
        if (planetCount > 0) {
            GLES20.glUseProgram(pointProgram)
            GLES20.glUniformMatrix4fv(pointMvp, 1, false, mvp, 0)
            GLES20.glUniform1f(pointSizeScale, bridge.densityScale * 0.8f)
            if (bridge.nightMode) {
                GLES20.glUniform4f(pointTint, 1f, 0.25f, 0.2f, 1f)
            } else {
                GLES20.glUniform4f(pointTint, 1f, 1f, 1f, 1f)
            }
            planetBuffer.position(0)
            enablePointAttribs(vbo = false)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, planetCount)
            disablePointAttribs()
        }
    }

    private fun enablePointAttribs(vbo: Boolean) {
        val stride = FLOATS_PER_STAR * 4
        GLES20.glEnableVertexAttribArray(pointPos)
        GLES20.glEnableVertexAttribArray(pointColor)
        GLES20.glEnableVertexAttribArray(pointSize)
        if (vbo) {
            GLES20.glVertexAttribPointer(pointPos, 3, GLES20.GL_FLOAT, false, stride, 0)
            GLES20.glVertexAttribPointer(pointColor, 3, GLES20.GL_FLOAT, false, stride, 12)
            GLES20.glVertexAttribPointer(pointSize, 1, GLES20.GL_FLOAT, false, stride, 24)
        } else {
            planetBuffer.position(0)
            GLES20.glVertexAttribPointer(pointPos, 3, GLES20.GL_FLOAT, false, stride, planetBuffer)
            planetBuffer.position(3)
            GLES20.glVertexAttribPointer(pointColor, 3, GLES20.GL_FLOAT, false, stride, planetBuffer)
            planetBuffer.position(6)
            GLES20.glVertexAttribPointer(pointSize, 1, GLES20.GL_FLOAT, false, stride, planetBuffer)
        }
    }

    private fun disablePointAttribs() {
        GLES20.glDisableVertexAttribArray(pointPos)
        GLES20.glDisableVertexAttribArray(pointColor)
        GLES20.glDisableVertexAttribArray(pointSize)
    }

    /** Recomputes solar-system positions every couple of seconds. */
    private fun refreshEphemeris(simMillis: Long, jd: Double) {
        // abs() so scrubbing time backwards also triggers a refresh.
        if (cachedPlanets.isNotEmpty() &&
            kotlin.math.abs(simMillis - lastEphemerisMillis) < EPHEMERIS_REFRESH_MS
        ) return
        lastEphemerisMillis = simMillis

        cachedPlanets = Planets.compute(jd)
        bridge.publishPlanets(cachedPlanets)

        planetBuffer.clear()
        val v = FloatArray(3)
        val rgb = FloatArray(3)
        planetCount = 0
        for (p in cachedPlanets) {
            Coordinates.equatorialToUnitVector(p.raDeg, p.decDeg, v)
            planetColor(p.name, rgb)
            planetBuffer.put(v[0]).put(v[1]).put(v[2])
            planetBuffer.put(rgb[0]).put(rgb[1]).put(rgb[2])
            planetBuffer.put(planetPointSize(p.name))
            planetCount++
        }
        planetBuffer.flip()
    }

    private fun planetPointSize(name: String): Float = when (name) {
        "Sun", "Moon" -> 26f
        "Venus", "Jupiter" -> 14f
        "Saturn", "Mars", "Mercury" -> 11f
        else -> 7f
    }

    private fun planetColor(name: String, out: FloatArray) {
        val c = when (name) {
            "Sun" -> floatArrayOf(1f, 0.93f, 0.65f)
            "Moon" -> floatArrayOf(0.92f, 0.93f, 0.97f)
            "Mercury" -> floatArrayOf(0.88f, 0.83f, 0.78f)
            "Venus" -> floatArrayOf(1f, 0.98f, 0.88f)
            "Mars" -> floatArrayOf(1f, 0.55f, 0.35f)
            "Jupiter" -> floatArrayOf(1f, 0.90f, 0.72f)
            "Saturn" -> floatArrayOf(1f, 0.94f, 0.78f)
            "Uranus" -> floatArrayOf(0.70f, 0.90f, 1f)
            else -> floatArrayOf(0.55f, 0.70f, 1f)
        }
        c.copyInto(out)
    }

    private fun buildStaticBuffers(data: SkyData) {
        // ---- stars: position + colour + size ----
        val stars = data.stars
        starCount = stars.size
        val starFloats = FloatArray(starCount * FLOATS_PER_STAR)
        val rgb = FloatArray(3)
        var o = 0
        for (i in 0 until starCount) {
            Coordinates.equatorialToUnitVector(stars.raDeg[i], stars.decDeg[i], starFloats, o)
            StarColor.fromBv(stars.bv[i], rgb, 0)
            val mag = stars.mag[i]
            // Fainter stars: smaller and dimmer.
            val brightness = (1.0f - 0.11f * (mag + 1.5f)).coerceIn(0.30f, 1f)
            starFloats[o + 3] = rgb[0] * brightness
            starFloats[o + 4] = rgb[1] * brightness
            starFloats[o + 5] = rgb[2] * brightness
            starFloats[o + 6] = (12.5f - 1.9f * mag).coerceIn(2.2f, 17f)
            o += FLOATS_PER_STAR
        }
        starVbo = uploadVbo(starFloats, starVbo)

        // ---- constellation line segments ----
        var segments = 0
        for (c in data.constellations) {
            for (line in c.lines) segments += line.size / 2 - 1
        }
        lineVertexCount = segments * 2
        val lineFloats = FloatArray(lineVertexCount * 3)
        o = 0
        for (c in data.constellations) {
            for (line in c.lines) {
                val points = line.size / 2
                for (k in 0 until points - 1) {
                    Coordinates.equatorialToUnitVector(line[k * 2], line[k * 2 + 1], lineFloats, o)
                    Coordinates.equatorialToUnitVector(
                        line[(k + 1) * 2], line[(k + 1) * 2 + 1], lineFloats, o + 3
                    )
                    o += 6
                }
            }
        }
        lineVbo = uploadVbo(lineFloats, lineVbo)
        Log.i(TAG, "Buffers built: $starCount stars, $segments line segments")
    }

    private fun uploadVbo(floats: FloatArray, existing: Int): Int {
        val handle: Int
        if (existing != 0) {
            handle = existing
        } else {
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            handle = ids[0]
        }
        val buf = ByteBuffer.allocateDirect(floats.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(floats).flip()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, handle)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, floats.size * 4, buf, GLES20.GL_STATIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return handle
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program))
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
        }
        return shader
    }
}
