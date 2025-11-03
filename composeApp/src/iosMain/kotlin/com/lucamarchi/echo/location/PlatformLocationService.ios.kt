package com.lucamarchi.echo.location

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS implementation of PlatformLocationService.
 */
@OptIn(ExperimentalForeignApi::class)
actual class PlatformLocationService : LocationService {

    private val locationManager = CLLocationManager()
    private var delegate: CLLocationManagerDelegateImpl? = null
    private var locationUpdateCallback: ((Location) -> Unit)? = null

    init {
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 10.0 // 10 meters
    }

    actual override suspend fun getCurrentLocation(): Location? {
        // Request permission if not already granted
        if (!hasLocationPermission()) {
            requestLocationPermission()
            throw LocationError.PermissionDenied
        }

        if (!isLocationEnabled()) {
            throw LocationError.LocationDisabled
        }

        return suspendCancellableCoroutine { continuation ->
            val delegate = CLLocationManagerDelegateImpl(
                onLocationUpdate = { locations ->
                    val location = locations.lastOrNull() as? CLLocation
                    if (location != null) {
                        val echoLocation = Location(
                            latitude = location.coordinate.useContents { latitude },
                            longitude = location.coordinate.useContents { longitude },
                            accuracy = location.horizontalAccuracy.toFloat(),
                            timestamp = (location.timestamp.timeIntervalSince1970 * 1000).toLong(),
                        )
                        locationManager.stopUpdatingLocation()
                        continuation.resume(echoLocation)
                    } else {
                        continuation.resume(null)
                    }
                },
                onLocationError = { error ->
                    locationManager.stopUpdatingLocation()
                    when (error.code) {
                        kCLErrorDenied -> continuation.resumeWithException(LocationError.PermissionDenied)
                        kCLErrorLocationUnknown -> continuation.resume(null)
                        else -> continuation.resumeWithException(
                            LocationError.Unknown(RuntimeException(error.localizedDescription)),
                        )
                    }
                },
            )

            this.delegate = delegate
            locationManager.delegate = delegate
            locationManager.requestLocation()

            continuation.invokeOnCancellation {
                locationManager.stopUpdatingLocation()
                this.delegate = null
            }
        }
    }

    actual override suspend fun startLocationUpdates(onLocationUpdate: (Location) -> Unit) {
        // Request permission if not already granted
        if (!hasLocationPermission()) {
            requestLocationPermission()
            throw LocationError.PermissionDenied
        }

        if (!isLocationEnabled()) {
            throw LocationError.LocationDisabled
        }

        locationUpdateCallback = onLocationUpdate

        val delegate = CLLocationManagerDelegateImpl(
            onLocationUpdate = { locations ->
                val location = locations.lastOrNull() as? CLLocation
                if (location != null) {
                    val echoLocation = Location(
                        latitude = location.coordinate.useContents { latitude },
                        longitude = location.coordinate.useContents { longitude },
                        accuracy = location.horizontalAccuracy.toFloat(),
                        timestamp = (location.timestamp.timeIntervalSince1970 * 1000).toLong(),
                    )
                    onLocationUpdate(echoLocation)
                }
            },
            onLocationError = { error ->
                when (error.code) {
                    kCLErrorDenied -> throw LocationError.PermissionDenied
                    kCLErrorLocationUnknown -> {
                        // Continue trying, this is just a temporary failure
                    }
                    else -> throw LocationError.Unknown(RuntimeException(error.localizedDescription))
                }
            },
        )

        this.delegate = delegate
        locationManager.delegate = delegate
        locationManager.startUpdatingLocation()
    }

    actual override fun stopLocationUpdates() {
        locationManager.stopUpdatingLocation()
        delegate = null
        locationUpdateCallback = null
    }

    actual override fun isLocationEnabled(): Boolean = CLLocationManager.locationServicesEnabled()

    private fun hasLocationPermission(): Boolean {
        val authStatus = CLLocationManager.authorizationStatus()
        println("iOS Location Authorization Status: $authStatus")

        when (authStatus) {
            kCLAuthorizationStatusAuthorizedWhenInUse -> {
                println("Location permission: Authorized when in use")
                return true
            }
            kCLAuthorizationStatusAuthorizedAlways -> {
                println("Location permission: Authorized always")
                return true
            }
            kCLAuthorizationStatusNotDetermined -> {
                println("Location permission: Not determined - requesting permission")
                return false
            }
            kCLAuthorizationStatusDenied -> {
                println("Location permission: Denied")
                return false
            }
            kCLAuthorizationStatusRestricted -> {
                println("Location permission: Restricted")
                return false
            }
            else -> {
                println("Location permission: Unknown status")
                return false
            }
        }
    }

    fun requestLocationPermission() {
        println("Requesting iOS location permission...")
        locationManager.requestWhenInUseAuthorization()
    }
}

@OptIn(ExperimentalForeignApi::class)
class CLLocationManagerDelegateImpl(
    private val onLocationUpdate: (List<*>) -> Unit,
    private val onLocationError: (NSError) -> Unit,
    private val onAuthorizationChange: ((CLAuthorizationStatus) -> Unit)? = null,
) : NSObject(),
    CLLocationManagerDelegateProtocol {

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        onLocationUpdate(didUpdateLocations)
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        onLocationError(didFailWithError)
    }

    override fun locationManager(manager: CLLocationManager, didChangeAuthorizationStatus: CLAuthorizationStatus) {
        onAuthorizationChange?.invoke(didChangeAuthorizationStatus)
    }
}
