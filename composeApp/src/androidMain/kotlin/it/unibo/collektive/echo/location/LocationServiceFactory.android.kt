package it.unibo.collektive.echo.location

import android.content.Context

/** Android stub — use [createLocationService] with a [Context] parameter instead. */
actual fun createLocationService(): LocationService = error("Use createLocationService(context: Context) for Android")

/** Creates the Android-specific [PlatformLocationService] backed by the given [context]. */
fun createLocationService(context: Context): LocationService = PlatformLocationService(context)
