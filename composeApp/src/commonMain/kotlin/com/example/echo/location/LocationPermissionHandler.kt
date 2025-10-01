package com.example.echo.location

import androidx.compose.runtime.Composable

@Composable
expect fun LocationPermissionHandler(
    onPermissionGranted: (LocationService) -> Unit,
    onPermissionDenied: () -> Unit
)