package com.example.echo.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.PermissionsControllerFactory
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import dev.icerock.moko.permissions.location.LOCATION

@Composable
actual fun LocationPermissionHandler(onPermissionGranted: (LocationService) -> Unit, onPermissionDenied: () -> Unit) {
    val context = LocalContext.current
    val factory: PermissionsControllerFactory = rememberPermissionsControllerFactory()
    val controller = remember(factory) { factory.createPermissionsController() }

    BindEffect(controller)

    LaunchedEffect(Unit) {
        try {
            controller.providePermission(Permission.LOCATION)
            val locationService = createLocationService(context)
            onPermissionGranted(locationService)
        } catch (e: Exception) {
            onPermissionDenied()
        }
    }
}
