package it.unibo.collektive.echo.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.icerock.moko.permissions.DeniedAlwaysException
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.PermissionsControllerFactory
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import dev.icerock.moko.permissions.location.LOCATION
import kotlinx.coroutines.delay

private const val PERMISSION_PROCESSING_DELAY_MS = 500L

/**
 * iOS implementation of [LocationPermissionHandler] that requests location permission
 * via moko-permissions and provides a [LocationService] upon success.
 */
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
            delay(PERMISSION_PROCESSING_DELAY_MS)

            val locationService = createLocationService()
            onPermissionGranted(locationService)
        } catch (e: DeniedAlwaysException) {
            println("iOS: Location permission permanently denied via moko-permissions: ${e.message}")
            onPermissionDenied()
        } catch (e: DeniedException) {
            println("iOS: Location permission denied via moko-permissions: ${e.message}")
            onPermissionDenied()
        }
    }
}
