package com.stellar.app.ui

import android.app.Application
import android.hardware.GeomagneticField
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stellar.app.data.SkyData
import com.stellar.app.data.SkyObject
import com.stellar.app.data.SkyRepository
import com.stellar.app.rendering.SkyStateBridge
import com.stellar.app.sensors.LocationProvider
import com.stellar.app.sensors.OrientationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Holds all observable UI state and owns the sensor providers and the
 * render-state bridge, keeping MainActivity a thin wiring layer.
 */
class SkyViewModel(application: Application) : AndroidViewModel(application) {

    val bridge = SkyStateBridge()

    val orientationProvider = OrientationProvider(application, bridge)
    private val locationProvider = LocationProvider(application)

    private val _skyData = MutableStateFlow<SkyData?>(null)
    val skyData: StateFlow<SkyData?> = _skyData

    private val _selectedObject = MutableStateFlow<SkyObject?>(null)
    val selectedObject: StateFlow<SkyObject?> = _selectedObject

    private val _showConstellations = MutableStateFlow(true)
    val showConstellations: StateFlow<Boolean> = _showConstellations

    private val _showLabels = MutableStateFlow(true)
    val showLabels: StateFlow<Boolean> = _showLabels

    private val _nightMode = MutableStateFlow(false)
    val nightMode: StateFlow<Boolean> = _nightMode

    private val _timeOffsetMillis = MutableStateFlow(0L)
    val timeOffsetMillis: StateFlow<Long> = _timeOffsetMillis

    val location = locationProvider.location
    val compassAccuracy = orientationProvider.accuracy

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skyData.value = SkyRepository.load(getApplication())
        }
        viewModelScope.launch {
            location.collect { loc ->
                if (loc != null) {
                    bridge.latitudeDeg = loc.latitude
                    bridge.longitudeDeg = loc.longitude
                    bridge.hasRealLocation = true
                    bridge.magneticDeclinationDeg = GeomagneticField(
                        loc.latitude.toFloat(),
                        loc.longitude.toFloat(),
                        loc.altitude.toFloat(),
                        System.currentTimeMillis()
                    ).declination
                }
            }
        }
    }

    /** Called from onResume (and again after permissions are granted). */
    fun startSensors() {
        orientationProvider.start()
        locationProvider.start()
    }

    fun stopSensors() {
        orientationProvider.stop()
        locationProvider.stop()
    }

    fun select(obj: SkyObject?) {
        _selectedObject.value = obj
    }

    fun toggleConstellations() {
        _showConstellations.value = !_showConstellations.value
        bridge.showConstellations = _showConstellations.value
    }

    fun toggleLabels() {
        _showLabels.value = !_showLabels.value
    }

    fun toggleNightMode() {
        _nightMode.value = !_nightMode.value
        bridge.nightMode = _nightMode.value
    }

    fun addTimeOffset(deltaMillis: Long) {
        _timeOffsetMillis.value += deltaMillis
        bridge.timeOffsetMillis = _timeOffsetMillis.value
    }

    fun resetTime() {
        _timeOffsetMillis.value = 0L
        bridge.timeOffsetMillis = 0L
    }

    fun search(query: String): List<SkyObject> {
        val data = _skyData.value ?: return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return data.searchIndex.take(30)
        return data.searchIndex.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.designation.contains(q, ignoreCase = true)
        }.take(30)
    }

    override fun onCleared() {
        stopSensors()
        super.onCleared()
    }
}
