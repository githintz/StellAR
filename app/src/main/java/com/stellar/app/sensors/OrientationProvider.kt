package com.stellar.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.view.Surface
import com.stellar.app.rendering.SkyStateBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns the ROTATION_VECTOR sensor (the OS-level fusion of gyroscope,
 * accelerometer and magnetometer) into an OpenGL view matrix.
 *
 * An additional quaternion low-pass filter (nlerp) removes residual
 * jitter so labels don't swim. The world frame of the sensor is
 * East-North(magnetic)-Up; the renderer compensates magnetic declination
 * separately so the sky aligns with true north.
 */
class OrientationProvider(
    context: Context,
    private val bridge: SkyStateBridge
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** SensorManager.SENSOR_STATUS_* of the underlying magnetometer fusion. */
    private val _accuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
    val accuracy: StateFlow<Int> = _accuracy

    val hasRotationSensor: Boolean get() = rotationSensor != null

    /** Set from the activity whenever the display rotation changes. */
    @Volatile var displayRotation: Int = Surface.ROTATION_0

    /** 0 = no smoothing, 1 = frozen. */
    private val smoothing = 0.75f

    private val smoothedQ = FloatArray(4)
    private var hasSample = false

    private val rotation = FloatArray(16)
    private val remapped = FloatArray(16)
    private val view = FloatArray(16)

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        hasSample = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        // Rotation vector -> unit quaternion (x, y, z, w).
        val q = FloatArray(4)
        q[0] = event.values[0]
        q[1] = event.values[1]
        q[2] = event.values[2]
        q[3] = if (event.values.size > 3) {
            event.values[3]
        } else {
            val s = 1f - (q[0] * q[0] + q[1] * q[1] + q[2] * q[2])
            if (s > 0f) sqrt(s) else 0f
        }

        if (!hasSample) {
            System.arraycopy(q, 0, smoothedQ, 0, 4)
            hasSample = true
        } else {
            // nlerp toward the new sample, picking the shorter arc.
            var dot = 0f
            for (i in 0..3) dot += smoothedQ[i] * q[i]
            val sign = if (dot < 0f) -1f else 1f
            var norm = 0f
            for (i in 0..3) {
                smoothedQ[i] = smoothing * smoothedQ[i] + (1f - smoothing) * sign * q[i]
                norm += smoothedQ[i] * smoothedQ[i]
            }
            norm = sqrt(norm)
            if (norm > 1e-6f) for (i in 0..3) smoothedQ[i] /= norm
        }

        // Quaternion -> rotation matrix (device frame -> world ENU frame).
        SensorManager.getRotationMatrixFromVector(rotation, smoothedQ)

        // Express the matrix in display (not device-natural) axes so the
        // overlay stays aligned when the UI rotates.
        when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotation, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotation, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotation, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped
            )
            else -> System.arraycopy(rotation, 0, remapped, 0, 16)
        }

        // The rotation matrix maps device -> world. The GL view matrix is the
        // inverse (world -> eye); for a pure rotation that is the transpose.
        // The back camera looks along -Z of the device, matching OpenGL's
        // eye convention, so no extra remap is needed.
        Matrix.transposeM(view, 0, remapped, 0)
        bridge.setViewMatrix(view)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_ROTATION_VECTOR ||
            sensor.type == Sensor.TYPE_MAGNETIC_FIELD
        ) {
            _accuracy.value = abs(accuracy)
        }
    }
}
