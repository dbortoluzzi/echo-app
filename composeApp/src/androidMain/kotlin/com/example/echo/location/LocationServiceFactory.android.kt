package com.example.echo.location

import android.content.Context

actual fun createLocationService(): LocationService {
    // In Android, we need to get the context from somewhere
    // This would typically be injected or retrieved from an Application class
    // For now, we'll return a factory that requires context
    throw IllegalStateException("Use createLocationService(context: Context) for Android")
}

fun createLocationService(context: Context): LocationService {
    return PlatformLocationService(context)
}