package com.example.echo.location

import com.diamondedge.logging.logging

/**
 * Example class showing how to use the location services in your application
 */
class LocationExample {
    private val log = logging("LocationExample")
    private var locationService: LocationService? = null

    fun initializeLocationService(service: LocationService) {
        locationService = service
        log.i { "Location service initialized" }
    }

    suspend fun demonstrateLocationFunctionality() {
        locationService?.let { service ->
            try {
                // Check if location is enabled
                val isEnabled = service.isLocationEnabled()
                log.i { "Location services enabled: $isEnabled" }

                if (!isEnabled) {
                    log.w { "Location services are disabled" }
                    return
                }

                // Get current location
                val currentLocation = service.getCurrentLocation()
                currentLocation?.let { location ->
                    log.i { "Current location: ${location.latitude}, ${location.longitude}" }
                    log.i { "Accuracy: ${location.accuracy} meters" }
                    log.i { "Timestamp: ${location.timestamp}" }
                } ?: log.w { "Could not get current location" }

                // Start location updates
                log.i { "Starting location updates..." }
                service.startLocationUpdates { location ->
                    log.i { "Location update: ${location.latitude}, ${location.longitude} (${location.accuracy}m)" }
                }
            } catch (e: LocationError) {
                when (e) {
                    is LocationError.PermissionDenied -> log.e { "Location permission denied" }
                    is LocationError.LocationDisabled -> log.e { "Location services disabled" }
                    is LocationError.ServiceUnavailable -> log.e { "Location service unavailable" }
                    is LocationError.Unknown -> log.e { "Unknown location error: ${e.cause?.message}" }
                }
            }
        } ?: log.w { "Location service not initialized" }
    }

    fun stopLocationUpdates() {
        locationService?.stopLocationUpdates()
        log.i { "Location updates stopped" }
    }
}
