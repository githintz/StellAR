package com.stellar.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.stellar.app.R
import com.stellar.app.astronomy.AstroTime
import com.stellar.app.astronomy.Coordinates
import com.stellar.app.data.SkyObject
import com.stellar.app.databinding.ActivityMainBinding
import com.stellar.app.rendering.SkyRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MainActivity"
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: SkyViewModel by viewModels()
    private lateinit var renderer: SkyRenderer
    private var cameraStarted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startCamera()
        // Location provider re-checks its own permission.
        viewModel.startSensors()
        updateBanner()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.bridge.densityScale = resources.displayMetrics.density

        setUpGlView()
        setUpOverlay()
        setUpControls()
        observeState()

        requestPermissionsIfNeeded()
    }

    private fun setUpGlView() {
        renderer = SkyRenderer(viewModel.bridge)
        with(binding.skyGlView) {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setRenderer(renderer)
            // Above the camera SurfaceView, below regular views.
            setZOrderMediaOverlay(true)
        }
    }

    private fun setUpOverlay() {
        binding.skyOverlay.bridge = viewModel.bridge
        binding.skyOverlay.onObjectTapped = { viewModel.select(it) }
    }

    private fun setUpControls() {
        binding.chipSearch.setOnClickListener {
            SearchDialogFragment().show(supportFragmentManager, SearchDialogFragment.TAG)
        }
        binding.chipConstellations.setOnClickListener { viewModel.toggleConstellations() }
        binding.chipLabels.setOnClickListener { viewModel.toggleLabels() }
        binding.chipNight.setOnClickListener { viewModel.toggleNightMode() }
        binding.chipTime.setOnClickListener {
            val visible = binding.timeControls.visibility == View.VISIBLE
            binding.timeControls.visibility = if (visible) View.GONE else View.VISIBLE
        }

        binding.btnMinusDay.setOnClickListener { viewModel.addTimeOffset(-DAY_MS) }
        binding.btnMinusHour.setOnClickListener { viewModel.addTimeOffset(-HOUR_MS) }
        binding.btnNow.setOnClickListener { viewModel.resetTime() }
        binding.btnPlusHour.setOnClickListener { viewModel.addTimeOffset(HOUR_MS) }
        binding.btnPlusDay.setOnClickListener { viewModel.addTimeOffset(DAY_MS) }

        binding.infoCard.setOnClickListener { viewModel.select(null) }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.skyData.collect { data ->
                        if (data != null) {
                            renderer.setSkyData(data)
                            binding.skyOverlay.skyData = data
                        }
                    }
                }
                launch {
                    viewModel.selectedObject.collect { obj ->
                        binding.skyOverlay.selected = obj
                        binding.infoCard.visibility =
                            if (obj == null) View.GONE else View.VISIBLE
                        if (obj != null) {
                            binding.infoTitle.text = obj.displayLabel()
                            updateInfoDetails(obj)
                        }
                    }
                }
                launch {
                    viewModel.showConstellations.collect {
                        binding.chipConstellations.isChecked = it
                        binding.skyOverlay.showConstellations = it
                    }
                }
                launch {
                    viewModel.showLabels.collect {
                        binding.chipLabels.isChecked = it
                        binding.skyOverlay.showLabels = it
                    }
                }
                launch {
                    viewModel.nightMode.collect {
                        binding.chipNight.isChecked = it
                        binding.skyOverlay.nightMode = it
                    }
                }
                launch {
                    viewModel.timeOffsetMillis.collect { offset ->
                        binding.simTimeText.text = if (offset == 0L) {
                            getString(R.string.time_now)
                        } else {
                            DateFormat.getDateTimeInstance(
                                DateFormat.MEDIUM, DateFormat.SHORT
                            ).format(Date(viewModel.bridge.simTimeMillis()))
                        }
                    }
                }
                launch { viewModel.location.collect { updateBanner() } }
                launch { viewModel.compassAccuracy.collect { updateBanner() } }
                launch {
                    // Live alt/az refresh for the info card.
                    while (true) {
                        viewModel.selectedObject.value?.let { updateInfoDetails(it) }
                        delay(1000)
                    }
                }
            }
        }
    }

    private fun updateInfoDetails(obj: SkyObject) {
        val bridge = viewModel.bridge
        var ra = obj.raDeg
        var dec = obj.decDeg
        var mag = obj.mag
        if (obj.isSolarSystem) {
            bridge.planetSnapshot().firstOrNull { it.name == obj.name }?.let {
                ra = it.raDeg
                dec = it.decDeg
                mag = it.mag
            }
        }
        val jd = AstroTime.julianDay(bridge.simTimeMillis())
        val lst = AstroTime.lstDegrees(jd, bridge.longitudeDeg)
        val horizontal = Coordinates.equatorialToHorizontal(
            ra, dec, bridge.latitudeDeg, lst
        )

        val typeLine = when (obj.type) {
            SkyObject.Type.CONSTELLATION -> getString(R.string.type_constellation)
            SkyObject.Type.STAR -> getString(R.string.type_star) + ", mag %.1f".format(mag)
            SkyObject.Type.PLANET -> getString(R.string.type_planet) + ", mag %.1f".format(mag)
            SkyObject.Type.MOON -> getString(R.string.type_moon)
            SkyObject.Type.SUN -> getString(R.string.type_sun)
        }
        binding.infoSubtitle.text = typeLine
        binding.infoDetails.text = getString(
            R.string.info_details_format,
            horizontal.altDeg, horizontal.azDeg, ra / 15.0, dec
        )
    }

    private fun updateBanner() {
        val banner = binding.statusBanner
        when {
            !viewModel.orientationProvider.hasRotationSensor -> {
                banner.setText(R.string.banner_no_sensor)
                banner.visibility = View.VISIBLE
            }
            viewModel.compassAccuracy.value <= 1 -> {
                banner.setText(R.string.banner_calibrate)
                banner.visibility = View.VISIBLE
            }
            !viewModel.bridge.hasRealLocation -> {
                banner.setText(R.string.banner_no_location)
                banner.visibility = View.VISIBLE
            }
            else -> banner.visibility = View.GONE
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            startCamera()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startCamera() {
        if (cameraStarted) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview
                )
                applyCameraFov(camera)
                cameraStarted = true
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialisation failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Matches the virtual sky's field of view to the physical camera so
     * the overlay lines up with the real stars. Falls back to typical
     * phone-camera values if the characteristics are unavailable.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCameraFov(camera: Camera) {
        try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val focals = info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )
            val sensor = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            )
            if (focals != null && focals.isNotEmpty() && sensor != null) {
                val f = focals[0].toDouble()
                val longSide = max(sensor.width, sensor.height).toDouble()
                val shortSide = min(sensor.width, sensor.height).toDouble()
                viewModel.bridge.fovLongAxisDeg =
                    Math.toDegrees(2.0 * atan2(longSide / 2.0, f)).toFloat()
                viewModel.bridge.fovShortAxisDeg =
                    Math.toDegrees(2.0 * atan2(shortSide / 2.0, f)).toFloat()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read camera FOV, using defaults", t)
        }
    }

    private fun currentDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        viewModel.orientationProvider.displayRotation = currentDisplayRotation()
    }

    override fun onResume() {
        super.onResume()
        binding.skyGlView.onResume()
        viewModel.orientationProvider.displayRotation = currentDisplayRotation()
        viewModel.startSensors()
        updateBanner()
    }

    override fun onPause() {
        binding.skyGlView.onPause()
        viewModel.stopSensors()
        super.onPause()
    }
}
