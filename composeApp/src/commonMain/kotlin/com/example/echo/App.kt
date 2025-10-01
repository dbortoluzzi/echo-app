package com.example.echo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.echo.location.LocationPermissionHandler
import com.example.echo.ui.Screen
import com.example.echo.ui.TopBar
import com.example.echo.viewmodels.NearbyDevicesViewModel
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.uuid.ExperimentalUuidApi

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
            if (viewModel == null) {
                viewModel = NearbyDevicesViewModel() // Create without location service
            }
        }
    )

    viewModel?.let { vm ->
        val devices by vm.dataFlow.collectAsState()
        val connection by vm.connectionFlow.collectAsState()
        val currentLocation by vm.currentLocationFlow.collectAsState()
        val locationError by vm.locationErrorFlow.collectAsState()
        val uuid = vm.deviceId

        LaunchedEffect(Unit) {
            vm.startCollektiveProgram()
            vm.startLocationTracking() // Start location tracking
            // Test location functionality
            vm.testLocationFunctionality()
        }

        DisposableEffect(vm) {
            onDispose {
                vm.cleanup()
            }
        }

        Screen(
            modifier = modifier,
            devices,
            connection,
            uuid,
            vm,
        )
    }
}
