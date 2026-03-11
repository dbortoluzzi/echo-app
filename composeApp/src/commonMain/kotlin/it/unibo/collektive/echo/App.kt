package it.unibo.collektive.echo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import it.unibo.collektive.echo.location.LocationPermissionHandler
import it.unibo.collektive.echo.ui.Screen
import it.unibo.collektive.echo.ui.TopBar
import it.unibo.collektive.echo.viewmodels.NearbyDevicesViewModel
import kotlin.uuid.ExperimentalUuidApi

/** Root composable that sets up the Material theme, scaffold, and top bar. */
@Composable
@Preview
fun App() {
    MaterialTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { TopBar() },
        ) { innerPadding ->
            CollektiveNearbyDevices(modifier = Modifier.padding(innerPadding))
        }
    }
}

/** Handles location permission flow and initialises the [NearbyDevicesViewModel] once granted. */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun CollektiveNearbyDevices(modifier: Modifier) {
    var viewModel by remember { mutableStateOf<NearbyDevicesViewModel?>(null) }

    // Platform-specific location service initialization
    LocationPermissionHandler(
        onPermissionGranted = { locationService ->
            if (viewModel == null) {
                viewModel = NearbyDevicesViewModel(locationService = locationService)
            }
        },
        onPermissionDenied = {
            // GPS is mandatory - app cannot function without location
            viewModel = null
        },
    )

    viewModel?.let { vm ->
//        val devices by vm.dataFlow.collectAsState()
        val connection by vm.connectionFlow.collectAsState()
//        val currentLocation by vm.currentLocationFlow.collectAsState()
//        val locationError by vm.locationErrorFlow.collectAsState()
        val uuid = vm.deviceId

        LaunchedEffect(Unit) {
            // Start location tracking first - GPS is mandatory
            vm.startLocationTracking()
            // Wait for GPS location to be available, then start Collektive program
            vm.startCollektiveProgram()
        }

        DisposableEffect(vm) {
            onDispose {
                vm.cleanup()
            }
        }

        Screen(
            modifier = modifier,
            connection,
            uuid,
            vm,
        )
    }
}
