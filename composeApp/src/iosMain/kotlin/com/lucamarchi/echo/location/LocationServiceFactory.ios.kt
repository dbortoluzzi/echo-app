package com.lucamarchi.echo.location

actual fun createLocationService(): LocationService = PlatformLocationService()
