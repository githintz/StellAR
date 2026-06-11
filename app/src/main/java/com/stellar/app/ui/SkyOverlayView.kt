package com.stellar.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.stellar.app.astronomy.Coordinates
import com.stellar.app.data.SkyData
import com.stellar.app.data.SkyObject
import com.stellar.app.rendering.SkyStateBridge
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Canvas layer above the GL sky: object labels, constellation names,
 * cardinal directions, the selection marker and the off-screen guidance
 * arrow. Also performs tap hit-testing against the searchable objects.
 *
 * Projection matrices are produced by the GL renderer each frame and read
 * here through [SkyStateBridge].
 */
class SkyOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    var bridge: SkyStateBridge? = null

    var skyData: SkyData? = null
        set(value) {
            field = value
            rebuildLabelLists()
        }

    var showLabels = true
    var showConstellations = true
    var nightMode = false
    var selected: SkyObject? = null
    var onObjectTapped: ((SkyObject?) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val starLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
    }
    private val planetLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * density
        isFakeBoldText = true
    }
    private val constellationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val arrowPath = Path()

    private val mvpEq = FloatArray(16)
    private val vpEnu = FloatArray(16)
    private val vec = FloatArray(3)
    private val screen = FloatArray(3)

    /** Indices into the star catalogue worth labelling (named and bright). */
    private var labelStars = IntArray(0)

    private var downX = 0f
    private var downY = 0f

    private val cardinals = arrayOf("N", "E", "S", "W")

    private fun rebuildLabelLists() {
        val data = skyData ?: run { labelStars = IntArray(0); return }
        val stars = data.stars
        val list = ArrayList<Int>(64)
        for (i in 0 until stars.size) {
            if (stars.name[i].isNotEmpty() && stars.mag[i] <= 2.1f) list.add(i)
        }
        labelStars = list.toIntArray()
    }

    // ---- frame loop ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ---- projection ----

    /**
     * Projects a direction vector through [m]. Returns true when the point
     * is on screen; [screen] always receives a usable position (mirrored
     * behind-the-viewer positions included) for guidance arrows.
     */
    private fun project(m: FloatArray, x: Float, y: Float, z: Float): Boolean {
        val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val cw = m[3] * x + m[7] * y + m[11] * z + m[15]

        val behind = cw <= 0f
        val w = if (behind) -cw else cw
        if (w < 1e-6f) return false
        var ndcX = cx / w
        var ndcY = cy / w
        if (behind) {
            ndcX = -ndcX
            ndcY = -ndcY
        }
        screen[0] = (ndcX * 0.5f + 0.5f) * width
        screen[1] = (0.5f - ndcY * 0.5f) * height
        screen[2] = if (behind) 0f else 1f
        return !behind && ndcX >= -1f && ndcX <= 1f && ndcY >= -1f && ndcY <= 1f
    }

    private fun projectEquatorial(raDeg: Double, decDeg: Double): Boolean {
        Coordinates.equatorialToUnitVector(raDeg, decDeg, vec)
        return project(mvpEq, vec[0], vec[1], vec[2])
    }

    /** Current RA/Dec of an object; solar-system bodies use the ephemeris. */
    private fun positionOf(obj: SkyObject): Pair<Double, Double> {
        if (obj.isSolarSystem) {
            bridge?.planetSnapshot()?.firstOrNull { it.name == obj.name }?.let {
                return it.raDeg to it.decDeg
            }
        }
        return obj.raDeg to obj.decDeg
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bridge = bridge ?: return
        if (!bridge.copyFrame(mvpEq, vpEnu)) return

        applyColors()

        drawCardinals(canvas)

        val data = skyData
        if (data != null && showLabels) {
            drawStarLabels(canvas, data)
        }
        if (data != null && showConstellations) {
            drawConstellationLabels(canvas, data)
        }
        drawPlanetLabels(canvas, bridge)
        selected?.let { drawSelection(canvas, it) }
    }

    private fun applyColors() {
        if (nightMode) {
            starLabelPaint.color = 0xCCFF5544.toInt()
            planetLabelPaint.color = 0xEEFF6655.toInt()
            constellationPaint.color = 0x88FF4433.toInt()
            cardinalPaint.color = 0xCCFF5544.toInt()
            markerPaint.color = 0xEEFF5544.toInt()
            arrowPaint.color = 0xEEFF5544.toInt()
        } else {
            starLabelPaint.color = 0xCCFFFFFF.toInt()
            planetLabelPaint.color = 0xEEFFE082.toInt()
            constellationPaint.color = 0x9990A8E0.toInt()
            cardinalPaint.color = 0xCCFFD54F.toInt()
            markerPaint.color = 0xEE80D8FF.toInt()
            arrowPaint.color = 0xEE80D8FF.toInt()
        }
    }

    private fun drawCardinals(canvas: Canvas) {
        for (i in cardinals.indices) {
            val az = i * 90.0 * Coordinates.DEG_TO_RAD
            // ENU unit vector on the horizon.
            val e = sin(az).toFloat()
            val n = cos(az).toFloat()
            if (project(vpEnu, e, n, 0f)) {
                canvas.drawText(cardinals[i], screen[0], screen[1], cardinalPaint)
            }
        }
    }

    private fun drawStarLabels(canvas: Canvas, data: SkyData) {
        val stars = data.stars
        val dx = 7f * density
        for (i in labelStars) {
            if (projectEquatorial(stars.raDeg[i], stars.decDeg[i])) {
                canvas.drawText(stars.name[i], screen[0] + dx, screen[1] - dx, starLabelPaint)
            }
        }
    }

    private fun drawConstellationLabels(canvas: Canvas, data: SkyData) {
        for (c in data.constellations) {
            if (projectEquatorial(c.labelRaDeg, c.labelDecDeg)) {
                canvas.drawText(c.name, screen[0], screen[1], constellationPaint)
            }
        }
    }

    private fun drawPlanetLabels(canvas: Canvas, bridge: SkyStateBridge) {
        if (!showLabels) return
        val dx = 9f * density
        for (p in bridge.planetSnapshot()) {
            if (projectEquatorial(p.raDeg, p.decDeg)) {
                canvas.drawText(p.name, screen[0] + dx, screen[1] - dx, planetLabelPaint)
            }
        }
    }

    private fun drawSelection(canvas: Canvas, obj: SkyObject) {
        val (ra, dec) = positionOf(obj)
        val visible = projectEquatorial(ra, dec)
        if (visible) {
            canvas.drawCircle(screen[0], screen[1], 16f * density, markerPaint)
            canvas.drawText(
                obj.name,
                screen[0] + 20f * density,
                screen[1] + 5f * density,
                planetLabelPaint
            )
        } else {
            // Guidance arrow at the screen edge pointing toward the object.
            val cx = width / 2f
            val cy = height / 2f
            var dirX = screen[0] - cx
            var dirY = screen[1] - cy
            val len = hypot(dirX, dirY)
            if (len < 1f) return
            dirX /= len
            dirY /= len
            val radius = min(cx, cy) - 48f * density
            val ax = cx + dirX * radius
            val ay = cy + dirY * radius
            val angle = atan2(dirY, dirX)

            arrowPath.reset()
            val s = 14f * density
            arrowPath.moveTo(
                ax + cos(angle) * s,
                ay + sin(angle) * s
            )
            arrowPath.lineTo(
                ax + cos(angle + 2.5f) * s,
                ay + sin(angle + 2.5f) * s
            )
            arrowPath.lineTo(
                ax + cos(angle - 2.5f) * s,
                ay + sin(angle - 2.5f) * s
            )
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
            canvas.drawText(
                obj.name,
                ax - dirX * 30f * density,
                ay - dirY * 30f * density,
                planetLabelPaint
            )
        }
    }

    // ---- interaction ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val slop = 12f * density
                if (hypot(event.x - downX, event.y - downY) < slop) {
                    performClick()
                    handleTap(event.x, event.y)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val data = skyData ?: return
        if (bridge?.frameValid != true) return

        val threshold = 36f * density
        var best: SkyObject? = null
        var bestDist = threshold

        for (obj in data.searchIndex) {
            val (ra, dec) = positionOf(obj)
            if (!projectEquatorial(ra, dec)) continue
            val d = hypot(screen[0] - x, screen[1] - y)
            if (d < bestDist) {
                bestDist = d
                best = obj
            }
        }
        onObjectTapped?.invoke(best)
    }
}
