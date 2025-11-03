package com.example.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.echo.DEFAULT_MAX_DISTANCE
import com.example.echo.DEFAULT_MAX_TIME
import com.example.echo.MAX_DISTANCE
import com.example.echo.MAX_TIME
import com.example.echo.MIN_DISTANCE
import com.example.echo.MIN_TIME
import com.example.echo.models.ChatMessage
import com.example.echo.viewmodels.NearbyDevicesViewModel
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Composable
fun Screen(
    modifier: Modifier,
    connection: NearbyDevicesViewModel.ConnectionState,
    uuid: Uuid,
    viewModel: NearbyDevicesViewModel,
) {
    var metersValue by remember { mutableStateOf(DEFAULT_MAX_DISTANCE.toFloat()) }
    var secondsValue by remember { mutableStateOf(DEFAULT_MAX_TIME.toFloat()) }
    var messageText by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    rememberCoroutineScope()

    // Collect messages and sending state from ViewModel
    val messages by viewModel.messagesFlow.collectAsState()
    val isSending by viewModel.isSendingFlow.collectAsState()
    val sendingCounter by viewModel.sendingCounterFlow.collectAsState()
    val discoveredDevices by viewModel.dataFlow.collectAsState()
    val currentLocation by viewModel.currentLocationFlow.collectAsState()

    // Auto-scroll when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(maxOf(0, messages.size - 1))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Connection Status Indicator
        ConnectionStatusCard(
            connection = connection,
            isSending = isSending,
            sendingCounter = sendingCounter,
            discoveredDevicesCount = discoveredDevices.size,
            currentLocation = currentLocation,
        )

        Spacer(Modifier.padding(4.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Max Distance: ${metersValue.toInt()}m",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = metersValue,
                    onValueChange = {
                        metersValue = it
                        viewModel.updateMessageParameters(secondsValue.toDouble(), metersValue.toDouble())
                    },
                    valueRange = MIN_DISTANCE.toFloat()..MAX_DISTANCE.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.padding(4.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Message Lifetime: ${secondsValue.toInt()}s",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = secondsValue,
                    onValueChange = {
                        secondsValue = it
                        viewModel.updateMessageParameters(secondsValue.toDouble(), metersValue.toDouble())
                    },
                    valueRange = MIN_TIME.toFloat()..MAX_TIME.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Messages
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages) { message ->
                    MessageItem(message = message, uuid = uuid)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                placeholder = { Text("Write a message...") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                enabled = !isSending,
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box {
                FloatingActionButton(
                    onClick = {
                        if (messageText.isNotBlank() && !isSending) {
                            viewModel.sendMessage(
                                message = messageText,
                                lifeTime = secondsValue.toDouble(),
                                maxDistanceMeters = metersValue.toDouble(),
                            )
                            messageText = ""
                        }
                    },
                    modifier = Modifier.size(50.dp),
                    containerColor = if (isSending) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ) {
                    if (isSending) {
                        Text(
                            text = "$sendingCounter",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Message",
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    connection: NearbyDevicesViewModel.ConnectionState,
    isSending: Boolean,
    sendingCounter: Int = 0,
    discoveredDevicesCount: Int = 0,
    currentLocation: com.example.echo.location.Location? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (connection) {
                NearbyDevicesViewModel.ConnectionState.CONNECTED -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                NearbyDevicesViewModel.ConnectionState.SENDING -> Color(0xFFFF9800).copy(alpha = 0.1f)
                NearbyDevicesViewModel.ConnectionState.DISCONNECTED -> Color(0xFFF44336).copy(alpha = 0.1f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.size(12.dp),
                shape = CircleShape,
                color = when (connection) {
                    NearbyDevicesViewModel.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                    NearbyDevicesViewModel.ConnectionState.SENDING -> Color(0xFFFF9800)
                    NearbyDevicesViewModel.ConnectionState.DISCONNECTED -> Color(0xFFF44336)
                },
            ) {}

            Column {
                Text(
                    text = when (connection) {
                        NearbyDevicesViewModel.ConnectionState.CONNECTED -> "Connected"
                        NearbyDevicesViewModel.ConnectionState.SENDING -> "Sending Message..."
                        NearbyDevicesViewModel.ConnectionState.DISCONNECTED -> "Disconnected"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = when (connection) {
                        NearbyDevicesViewModel.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                        NearbyDevicesViewModel.ConnectionState.SENDING -> Color(0xFFFF9800)
                        NearbyDevicesViewModel.ConnectionState.DISCONNECTED -> Color(0xFFF44336)
                    },
                )

                Text(
                    text = "Discovered devices: $discoveredDevicesCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )

                // GPS Status indicator
                currentLocation?.let { location ->
                    Text(
                        text = "📍 GPS: ${location.latitude}, ${location.longitude}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50),
                        maxLines = 1,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isSending) {
                Text(
                    text = "${sendingCounter}s",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFFF9800),
                )
            }
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
fun MessageItem(message: ChatMessage, uuid: Uuid) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.sender == uuid) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.sender == uuid) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.sender == uuid) 16.dp else 4.dp,
                bottomEnd = if (message.sender == uuid) 4.dp else 16.dp,
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
            ) {
                Text(
                    text = if (message.sender == uuid) {
                        "You"
                    } else {
                        "Id: ${message.sender}"
                    },
                    color = if (message.sender == uuid) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = message.text,
                    color = if (message.sender == uuid) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 14.sp,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = message.timestamp.toString().take(16),
                        color = if (message.sender == uuid) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        },
                        fontSize = 10.sp,
                    )

                    if (message.distanceFromSource > 0) {
                        Text(
                            text = "${message.distanceFromSource.toInt()}m",
                            color = if (message.sender == uuid) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            },
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}
