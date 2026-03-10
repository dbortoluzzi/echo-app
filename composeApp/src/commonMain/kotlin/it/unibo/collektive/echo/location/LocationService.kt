package it.unibo.collektive.echo.location

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Location class to store position data.
 *
 * @property latitude the latitude in degrees.
 * @property longitude the longitude in degrees.
 * @property accuracy the estimated horizontal accuracy in metres, or `null` if unavailable.
 * @property timestamp the epoch-second timestamp when the location was recorded.
 */
data class Location
@OptIn(ExperimentalTime::class)
constructor(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val timestamp: Long = Clock.System.now().epochSeconds,
)

/**
 * Interface for location.
 * Used in both iOS and Android.
 */
interface LocationService {
    /** Returns the device's current [Location], or `null` if unavailable. */
    suspend fun getCurrentLocation(): Location?

    /** Starts continuous location updates, invoking [onLocationUpdate] with each new [Location]. */
    suspend fun startLocationUpdates(onLocationUpdate: (Location) -> Unit)

    /** Stops any ongoing location updates. */
    fun stopLocationUpdates()

    /** Returns `true` when location services are enabled on the device. */
    fun isLocationEnabled(): Boolean
}

/** Sealed hierarchy of errors that can occur when obtaining location data. */
sealed class LocationError : Exception() {
    /** The user denied location permission. */
    object PermissionDenied : LocationError()

    /** Location services are disabled on the device. */
    object LocationDisabled : LocationError()

    /** The location provider is unavailable. */
    object ServiceUnavailable : LocationError()

    /** An unknown error occurred with an optional [cause]. */
    data class Unknown(override val cause: Throwable?) : LocationError()
}
