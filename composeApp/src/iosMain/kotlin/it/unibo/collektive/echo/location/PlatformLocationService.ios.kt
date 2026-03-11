package it.unibo.collektive.echo.location

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

    private companion object {
        /** Number of milliseconds in one second, used for timestamp conversion. */
        const val MILLIS_PER_SECOND = 1000
    }

    private val locationManager = CLLocationManager()
    private var delegate: CLLocationManagerDelegateImpl? = null
    private var locationUpdateCallback: ((Location) -> Unit)? = null

    init {
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 10.0 // 10 meters
    }

    /** Converts a CoreLocation [CLLocation] to the domain [Location] model. */
    private fun CLLocation.toEchoLocation(): Location = Location(
        latitude = coordinate.useContents { latitude },
        longitude = coordinate.useContents { longitude },
        accuracy = horizontalAccuracy.toFloat(),
        timestamp = (timestamp.timeIntervalSince1970 * MILLIS_PER_SECOND).toLong(),
    )

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
                    if (continuation.isActive) {
                        val location = locations.lastOrNull() as? CLLocation
                        locationManager.stopUpdatingLocation()
                        continuation.resume(location?.toEchoLocation())
                    }
                },
                onLocationError = { error ->
                    if (continuation.isActive) {
                        locationManager.stopUpdatingLocation()
                        when (error.code) {
                            kCLErrorDenied -> continuation.resumeWithException(LocationError.PermissionDenied)

                            kCLErrorLocationUnknown -> continuation.resume(null)

                            else -> continuation.resumeWithException(
                                LocationError.Unknown(RuntimeException(error.localizedDescription)),
                            )
                        }
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
                    onLocationUpdate(location.toEchoLocation())
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

        val granted = when (authStatus) {
            kCLAuthorizationStatusAuthorizedWhenInUse -> {
                println("Location permission: Authorized when in use")
                true
            }

            kCLAuthorizationStatusAuthorizedAlways -> {
                println("Location permission: Authorized always")
                true
            }

            kCLAuthorizationStatusNotDetermined -> {
                println("Location permission: Not determined - requesting permission")
                false
            }

            kCLAuthorizationStatusDenied -> {
                println("Location permission: Denied")
                false
            }

            kCLAuthorizationStatusRestricted -> {
                println("Location permission: Restricted")
                false
            }

            else -> {
                println("Location permission: Unknown status")
                false
            }
        }
        return granted
    }

    /** Requests the iOS "when in use" location authorization from the user. */
    fun requestLocationPermission() {
        println("Requesting iOS location permission...")
        locationManager.requestWhenInUseAuthorization()
    }
}

/** Implementation of [CLLocationManagerDelegateProtocol] that forwards location events to callbacks. */
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
