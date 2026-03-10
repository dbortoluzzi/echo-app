package it.unibo.collektive.echo.location

import androidx.compose.runtime.Composable

/**
 * Platform-specific composable that requests location permission and invokes
 * [onPermissionGranted] with a [LocationService] on success, or [onPermissionDenied] on failure.
 */
@Composable
expect fun LocationPermissionHandler(onPermissionGranted: (LocationService) -> Unit, onPermissionDenied: () -> Unit)
