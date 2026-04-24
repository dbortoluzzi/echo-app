@file:Suppress("MagicNumber")

package it.unibo.collektive.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.unibo.collektive.echo.MAX_DISTANCE
import it.unibo.collektive.echo.MAX_TIME
import it.unibo.collektive.echo.MIN_DISTANCE
import it.unibo.collektive.echo.MIN_TIME
import it.unibo.collektive.echo.models.ChatMessage
import it.unibo.collektive.echo.viewmodels.NearbyDevicesViewModel
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val ConnectedColor = Color(0xFF4CAF50)
private val SendingColor = Color(0xFFFF9800)
private val DisconnectedColor = Color(0xFFF44336)
private const val BACKGROUND_ALPHA = 0.1f
private const val TIMESTAMP_DISPLAY_LENGTH = 16
private const val SHORT_ID_HEAD_LENGTH = 6
private const val SHORT_ID_TAIL_LENGTH = 4

/** Main screen composable showing connection status, chat messages, and message input controls. */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun Screen(
    modifier: Modifier,
    connection: NearbyDevicesViewModel.ConnectionState,
    uuid: Uuid,
    viewModel: NearbyDevicesViewModel,
) {
    var messageText by remember { mutableStateOf("") }
    var selectedSenderId by remember { mutableStateOf<Uuid?>(null) }

    val listState = rememberLazyListState()

    // Collect messages and sending state from ViewModel
    val messages by viewModel.messagesFlow.collectAsState()
    val isSending by viewModel.isSendingFlow.collectAsState()
    val sendingCounter by viewModel.sendingCounterFlow.collectAsState()
    val discoveredDevices by viewModel.dataFlow.collectAsState()
    val currentLocation by viewModel.currentLocationFlow.collectAsState()
    val connectionErrorMessage by viewModel.connectionErrorMessageFlow.collectAsState()
    val ttlSeconds by viewModel.messageLifeTimeFlow.collectAsState()
    val maxDistanceMeters by viewModel.maxDistanceFlow.collectAsState()

    // Auto-scroll when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(maxOf(0, messages.size - 1))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        // Connection Status Indicator
        ConnectionStatusCard(
            connection = connection,
            isSending = isSending,
            sendingCounter = sendingCounter,
            discoveredDevicesCount = discoveredDevices.size,
            currentLocation = currentLocation,
            connectionErrorMessage = connectionErrorMessage,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = "Max Distance: ${maxDistanceMeters.toInt()}m",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = maxDistanceMeters.toFloat(),
                    onValueChange = {
                        viewModel.updateMessageParameters(
                            lifeTimeSeconds = ttlSeconds,
                            maxDistanceMeters = it.toDouble(),
                        )
                    },
                    valueRange = MIN_DISTANCE.toFloat()..MAX_DISTANCE.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Message Lifetime: ${ttlSeconds.toInt()}s",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = ttlSeconds.toFloat(),
                    onValueChange = {
                        viewModel.updateMessageParameters(
                            lifeTimeSeconds = it.toDouble(),
                            maxDistanceMeters = maxDistanceMeters,
                        )
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
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages) { message ->
                    MessageItem(
                        message = message,
                        uuid = uuid,
                        onSenderIdClick = { selectedSenderId = it },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                placeholder = { Text("Write a message...") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                enabled = !isSending,
            )

            Spacer(modifier = Modifier.width(6.dp))

            Box {
                FloatingActionButton(
                    onClick = {
                        if (messageText.isNotBlank() && !isSending) {
                            viewModel.sendMessage(
                                message = messageText,
                                lifeTime = ttlSeconds,
                                maxDistanceMeters = maxDistanceMeters,
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

    selectedSenderId?.let { senderId ->
        AlertDialog(
            onDismissRequest = { selectedSenderId = null },
            title = { Text(text = "Sender UUID") },
            text = { Text(text = senderId.toString()) },
            confirmButton = {
                TextButton(onClick = { selectedSenderId = null }) {
                    Text("OK")
                }
            },
        )
    }
}

private fun connectionColor(state: NearbyDevicesViewModel.ConnectionState): Color = when (state) {
    NearbyDevicesViewModel.ConnectionState.CONNECTED -> ConnectedColor
    NearbyDevicesViewModel.ConnectionState.SENDING -> SendingColor
    NearbyDevicesViewModel.ConnectionState.DISCONNECTED -> DisconnectedColor
}

private fun connectionLabel(state: NearbyDevicesViewModel.ConnectionState): String = when (state) {
    NearbyDevicesViewModel.ConnectionState.CONNECTED -> "Connected"
    NearbyDevicesViewModel.ConnectionState.SENDING -> "Sending Message..."
    NearbyDevicesViewModel.ConnectionState.DISCONNECTED -> "Disconnected"
}

/** Card displaying the current MQTT connection state, device count, and GPS coordinates. */
@Composable
fun ConnectionStatusCard(
    connection: NearbyDevicesViewModel.ConnectionState,
    isSending: Boolean,
    sendingCounter: Int = 0,
    discoveredDevicesCount: Int = 0,
    currentLocation: it.unibo.collektive.echo.location.Location? = null,
    connectionErrorMessage: String? = null,
) {
    val stateColor = connectionColor(connection)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = stateColor.copy(alpha = BACKGROUND_ALPHA),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                modifier = Modifier.size(12.dp),
                shape = CircleShape,
                color = stateColor,
            ) {}

            Column {
                Text(
                    text = connectionLabel(connection),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = stateColor,
                )

                Text(
                    text = "Discovered devices: $discoveredDevicesCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )

                if (!connectionErrorMessage.isNullOrBlank()) {
                    Text(
                        text = connectionErrorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = DisconnectedColor,
                    )
                }

                // GPS Status indicator
                currentLocation?.let { location ->
                    Text(
                        text = "📍 GPS: ${location.latitude}, ${location.longitude}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ConnectedColor,
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
                    color = SendingColor,
                )
            }
        }
    }
}

/** Displays a single chat message bubble, aligned right for sent messages and left for received ones. */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun MessageItem(
    message: ChatMessage,
    uuid: Uuid,
    onSenderIdClick: (Uuid) -> Unit,
) {
    val isLocalMessage = message.sender == uuid
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isLocalMessage) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 220.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isLocalMessage) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isLocalMessage) 16.dp else 4.dp,
                bottomEnd = if (isLocalMessage) 4.dp else 16.dp,
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (isLocalMessage) {
                        "You"
                    } else {
                        "Id: ${message.sender.toShortLabel()}"
                    },
                    color = if (isLocalMessage) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (isLocalMessage) {
                        Modifier
                    } else {
                        Modifier.clickable { onSenderIdClick(message.sender) }
                    },
                )
                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = message.text,
                    color = if (isLocalMessage) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 13.sp,
                )

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = message.timestamp.toString().take(TIMESTAMP_DISPLAY_LENGTH),
                        color = if (isLocalMessage) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        },
                        fontSize = 10.sp,
                    )

                    if (message.distanceFromSource > 0) {
                        Text(
                            text = "${message.distanceFromSource.toInt()}m",
                            color = if (isLocalMessage) {
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

@OptIn(ExperimentalUuidApi::class)
private fun Uuid.toShortLabel(): String {
    val value = toString()
    return if (value.length > SHORT_ID_HEAD_LENGTH + SHORT_ID_TAIL_LENGTH) {
        "${value.take(SHORT_ID_HEAD_LENGTH)}...${value.takeLast(SHORT_ID_TAIL_LENGTH)}"
    } else {
        value
    }
}
