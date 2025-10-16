package com.example.echo.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.PermissionsControllerFactory
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import dev.icerock.moko.permissions.location.LOCATION
import kotlinx.coroutines.delay

@Composable
actual fun LocationPermissionHandler(onPermissionGranted: (LocationService) -> Unit, onPermissionDenied: () -> Unit) {
    val factory: PermissionsControllerFactory = rememberPermissionsControllerFactory()
    val controller = remember(factory) { factory.createPermissionsController() }

    BindEffect(controller)

    LaunchedEffect(Unit) {
        try {
            println("iOS: Requesting location permission...")
            controller.providePermission(Permission.LOCATION)
            println("iOS: Location permission granted via moko-permissions")

            // Give a small delay to ensure permission is fully processed
            delay(500)

            val locationService = createLocationService()
            onPermissionGranted(locationService)
        } catch (e: Exception) {
            println("iOS: Location permission denied via moko-permissions: ${e.message}")
            onPermissionDenied()
        }
    }
}
