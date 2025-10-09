package com.example.echo.location

/**
 * Factory function to create a platform-specific LocationService instance.
 */
expect fun createLocationService(): LocationService
