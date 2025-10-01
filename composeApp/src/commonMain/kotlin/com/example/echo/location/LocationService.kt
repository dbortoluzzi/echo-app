package com.example.echo.location

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class Location @OptIn(ExperimentalTime::class) constructor(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val timestamp: Long = Clock.System.now().epochSeconds
)

interface LocationService {
    suspend fun getCurrentLocation(): Location?
    suspend fun startLocationUpdates(onLocationUpdate: (Location) -> Unit)
    fun stopLocationUpdates()
    fun isLocationEnabled(): Boolean
}

sealed class LocationError : Exception() {
    object PermissionDenied : LocationError()
    object LocationDisabled : LocationError()
    object ServiceUnavailable : LocationError()
    data class Unknown(override val cause: Throwable?) : LocationError()
}
