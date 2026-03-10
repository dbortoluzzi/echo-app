package it.unibo.collektive.echo.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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

    /** Configuration constants for Android location updates. */
    companion object {
        /** Interval in milliseconds between location updates. */
        private const val LOCATION_UPDATE_INTERVAL_MS = 5000L

        /** Minimum interval in milliseconds between location updates. */
        private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 2000L
    }

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
                println("Android: Location security exception: ${e.message}")
                continuation.resumeWithException(LocationError.PermissionDenied)
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: RuntimeException,
            ) {
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

        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_INTERVAL_MS)
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
                checkNotNull(locationRequest) { "locationRequest must be initialised" },
                checkNotNull(locationCallback) { "locationCallback must be initialised" },
                Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
            throw LocationError.PermissionDenied
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: RuntimeException,
        ) {
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
