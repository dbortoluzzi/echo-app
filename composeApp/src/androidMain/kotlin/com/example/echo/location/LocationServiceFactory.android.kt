package com.example.echo.location

import android.content.Context

actual fun createLocationService(): LocationService =
    throw IllegalStateException("Use createLocationService(context: Context) for Android")

fun createLocationService(context: Context): LocationService = PlatformLocationService(context)
