package com.lucamarchi.echo.location

/**
 * Get location (position longitude and latitude) on specific platforms.
 */
expect class PlatformLocationService : LocationService {
    override suspend fun getCurrentLocation(): Location?
    override suspend fun startLocationUpdates(onLocationUpdate: (Location) -> Unit)
    override fun stopLocationUpdates()
    override fun isLocationEnabled(): Boolean
}
