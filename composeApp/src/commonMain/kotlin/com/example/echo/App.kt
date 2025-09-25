package com.example.echo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    val viewModel = remember { NearbyDevicesViewModel() }

    val devices by viewModel.dataFlow.collectAsState()
    val connection by viewModel.connectionFlow.collectAsState()
    val uuid = viewModel.deviceId

    LaunchedEffect(Unit) {
        viewModel.startCollektiveProgram()
    }

    Screen(
        modifier = modifier,
        devices,
        connection,
        uuid,
        viewModel,
    )
}
