package com.stellar.app.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observer position from the platform LocationManager (no Play Services
 * dependency). Astronomy only needs coarse accuracy: 1 km of position
 * error moves the sky by well under an arc-minute.
 */
class LocationProvider(private val context: Context) : LocationListener {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) return

        // Seed with the freshest cached fix.
        var best: Location? = null
        for (provider in locationManager.getProviders(true)) {
            val last = runCatching {
                locationManager.getLastKnownLocation(provider)
            }.getOrNull() ?: continue
            if (best == null || last.time > best.time) best = last
        }
        if (best != null) _location.value = best

        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )) {
            if (locationManager.allProviders.contains(provider)) {
                runCatching {
                    locationManager.requestLocationUpdates(
                        provider, 30_000L, 500f, this
                    )
                }
            }
        }
    }

    fun stop() {
        locationManager.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        _location.value = location
    }

    @Deprecated("Deprecated in API 29, still required for older devices")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit
}
