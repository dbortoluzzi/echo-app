package it.unibo.collektive.echo.location

/** Creates the iOS-specific [PlatformLocationService] implementation. */
actual fun createLocationService(): LocationService = PlatformLocationService()
