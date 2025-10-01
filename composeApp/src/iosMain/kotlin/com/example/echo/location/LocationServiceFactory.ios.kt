package com.example.echo.location

actual fun createLocationService(): LocationService {
    return PlatformLocationService()
}