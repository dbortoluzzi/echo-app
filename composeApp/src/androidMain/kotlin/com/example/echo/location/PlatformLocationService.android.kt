package com.example.echo.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android implementation of PlatformLocationService.
 */
actual class PlatformLocationService(private val context: Context) : LocationService {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private var locationCallback: LocationCallback? = null
    private var locationRequest: LocationRequest? = null

    actual override suspend fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) {
            throw LocationError.PermissionDenied
        }

        if (!isLocationEnabled()) {
            throw LocationError.LocationDisabled
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val cancellationTokenSource = CancellationTokenSource()

                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }

                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token,
                ).addOnSuccessListener { location ->
                    if (location != null) {
                        val echoLocation = Location(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            timestamp = location.time,
                        )
                        continuation.resume(echoLocation)
                    } else {
                        continuation.resume(null)
                    }
                }.addOnFailureListener { exception ->
                    continuation.resumeWithException(LocationError.Unknown(exception))
                }
            } catch (e: SecurityException) {
                continuation.resumeWithException(LocationError.PermissionDenied)
            } catch (e: Exception) {
                continuation.resumeWithException(LocationError.Unknown(e))
            }
        }
    }

    actual override suspend fun startLocationUpdates(onLocationUpdate: (Location) -> Unit) {
        if (!hasLocationPermission()) {
            throw LocationError.PermissionDenied
        }

        if (!isLocationEnabled()) {
            throw LocationError.LocationDisabled
        }

        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                locationResult.lastLocation?.let { location ->
                    val echoLocation = Location(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        timestamp = location.time,
                    )
                    onLocationUpdate(echoLocation)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest!!,
                locationCallback!!,
                Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
            throw LocationError.PermissionDenied
        } catch (e: Exception) {
            throw LocationError.Unknown(e)
        }
    }

    actual override fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient.removeLocationUpdates(callback)
            locationCallback = null
        }
        locationRequest = null
    }

    actual override fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
}
