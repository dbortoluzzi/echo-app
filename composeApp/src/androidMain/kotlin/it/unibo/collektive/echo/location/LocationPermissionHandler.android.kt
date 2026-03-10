package it.unibo.collektive.echo.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.permissions.DeniedAlwaysException
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.PermissionsControllerFactory
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import dev.icerock.moko.permissions.location.LOCATION

/**
 * Android implementation of [LocationPermissionHandler] that requests location permission
 * via moko-permissions and provides a [LocationService] upon success.
 */
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
        } catch (e: DeniedAlwaysException) {
            println("Android: Location permission permanently denied: ${e.message}")
            onPermissionDenied()
        } catch (e: DeniedException) {
            println("Android: Location permission denied: ${e.message}")
            onPermissionDenied()
        }
    }
}
