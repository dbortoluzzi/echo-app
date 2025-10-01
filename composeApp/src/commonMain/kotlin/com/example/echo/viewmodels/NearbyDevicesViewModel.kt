package com.example.echo.viewmodels

import com.diamondedge.logging.logging
import com.example.echo.DEFAULT_MAX_DISTANCE
import com.example.echo.DEFAULT_MAX_TIME
import com.example.echo.gossip.chatMultipleSources
import com.example.echo.location.Location
import com.example.echo.location.LocationError
import com.example.echo.location.LocationService
import com.example.echo.models.ChatMessage
import com.example.echo.network.MqttMailbox
import it.unibo.collektive.Collektive
import it.unibo.collektive.aggregate.api.neighboring
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class NearbyDevicesViewModel(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val locationService: LocationService? = null
) {
    val log = logging("VIEWMODEL")

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @OptIn(ExperimentalUuidApi::class)
    private val _dataFlow = MutableStateFlow<Set<Uuid>>(emptySet())

    @OptIn(ExperimentalUuidApi::class)
    val dataFlow: StateFlow<Set<Uuid>> = _dataFlow.asStateFlow()

    private val _connectionFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionFlow: StateFlow<ConnectionState> = _connectionFlow.asStateFlow()

    private val _messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messagesFlow: StateFlow<List<ChatMessage>> = _messagesFlow.asStateFlow()

    private val _isSendingFlow = MutableStateFlow(false)
    val isSendingFlow: StateFlow<Boolean> = _isSendingFlow.asStateFlow()

    private val _sendingCounterFlow = MutableStateFlow(0)
    val sendingCounterFlow: StateFlow<Int> = _sendingCounterFlow.asStateFlow()

    private val _currentLocationFlow = MutableStateFlow<Location?>(null)
    val currentLocationFlow: StateFlow<Location?> = _currentLocationFlow.asStateFlow()

    private val _locationErrorFlow = MutableStateFlow<LocationError?>(null)
    val locationErrorFlow: StateFlow<LocationError?> = _locationErrorFlow.asStateFlow()

    @OptIn(ExperimentalUuidApi::class)
    val deviceId = Uuid.random()
    private var currentMessage: String = ""
    @OptIn(ExperimentalUuidApi::class)
    private var currentMessageId: Uuid = Uuid.random()
    private var messageStartTime: Long = 0L
    private var messageLifeTime: Double = DEFAULT_MAX_TIME
    private var maxDistance: Double = DEFAULT_MAX_DISTANCE

    enum class ConnectionState {
        CONNECTED,
        DISCONNECTED,
        SENDING, // New state for when sending messages
    }

    @OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
    private suspend fun collektiveProgram(): Collektive<Uuid, Pair<Set<Uuid>, List<ChatMessage>>> = Collektive(
        deviceId,
        MqttMailbox(deviceId, host = "broker.hivemq.com", dispatcher = dispatcher),
    ) {
        // Get neighboring devices
        val neighborMap = neighboring(localId)
        val neighbors = neighborMap.neighbors.toSet()

        // Get current time
        val currentTime = Clock.System.now().epochSeconds.toDouble()

        // Determine if this device is a source (has a message to send)
        val isSource = currentMessage.isNotEmpty() &&
            (currentTime - messageStartTime) <= messageLifeTime

        log.i { isSource }

        if (isSource) {
            log.i { "Device is source: sending message '$currentMessage' (${currentTime - messageStartTime}s elapsed)" }
        }

        // Create distance field with default distances for neighbors
        val distances = neighborMap.map { 100.0 }
        log.i { distances }

        // Collect all messages from all potential sources using chatMultipleSources
        val allSourceMessages = chatMultipleSources(
            distances = distances,
            isSource = isSource,
            currentTime = currentTime - messageStartTime,
            content = currentMessage,
            messageId = currentMessageId,
            lifeTime = messageLifeTime,
            maxDistance = maxDistance,
        )

        log.i { "Messages: ${allSourceMessages.size}, sources: ${allSourceMessages.keys}" }

        // Convert messages to ChatMessage objects
        val chatMessages = allSourceMessages.mapNotNull { (senderId, message) ->
            if (message.content.isNotEmpty()) {
                log.i {
                    "Received gossip message from $senderId: '${message.content}' at distance ${message.distanceFromSource}"
                }
                ChatMessage(
                    text = message.content,
                    sender = senderId,
                    messageId = message.messageId,
                    distanceFromSource = message.distanceFromSource,
                )
            } else {
                null
            }
        }

        log.i { "Final messages: ${chatMessages.size} total, senders: ${chatMessages.map { it.sender }}" }

        Pair(neighbors, chatMessages)
    }

    @OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
    fun startCollektiveProgram() {
        scope.launch {
            log.i { "Starting Collektive program..." }
            _connectionFlow.value = ConnectionState.CONNECTED
            val program = collektiveProgram()
            log.i { "Collektive program started..." }
            while (isActive) {
                val (newDevices, newMessages) = program.cycle()
                _dataFlow.value = newDevices

                // Update messages, only add new unique messages based on messageId
                val currentMessages = _messagesFlow.value.toMutableList()
                newMessages.forEach { newMessage ->
                    // Check if we already have this message (same messageId)
                    val isDuplicate = currentMessages.any { existing ->
                        existing.messageId == newMessage.messageId
                    }
                    
                    if (!isDuplicate) {
                        log.i { "Adding NEW message to UI: '${newMessage.text}' from ${newMessage.sender} (ID: ${newMessage.messageId})" }
                        currentMessages.add(newMessage)
                    } else {
                        log.i { "Skipping duplicate message: '${newMessage.text}' from ${newMessage.sender} (ID: ${newMessage.messageId})" }
                    }
                }
                _messagesFlow.value = currentMessages

                // Check if current message has expired
                val currentTime = Clock.System.now().epochSeconds.toDouble()
                if (currentMessage.isNotEmpty() && (currentTime - messageStartTime) > messageLifeTime) {
                    stopSendingMessage()
                }

                delay(1.seconds)
            }
        }
    }

    @OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
    fun sendMessage(message: String, lifeTime: Double = DEFAULT_MAX_TIME, maxDistanceMeters: Double = DEFAULT_MAX_DISTANCE) {
        scope.launch {
            log.i { "Message: '$message'" }
            _isSendingFlow.value = true
            _connectionFlow.value = ConnectionState.SENDING
            _sendingCounterFlow.value = 0

            currentMessage = message
            currentMessageId = Uuid.random()
            messageStartTime = Clock.System.now().epochSeconds
            messageLifeTime = lifeTime
            maxDistance = maxDistanceMeters

            // Add the message to local messages immediately
            val localMessage = ChatMessage(
                text = message,
                sender = deviceId,
                messageId = currentMessageId,
                distanceFromSource = 0.0,
            )
            val currentMessages = _messagesFlow.value.toMutableList()
            currentMessages.add(localMessage)
            _messagesFlow.value = currentMessages

            // Start counter for sending duration
            startSendingCounter(lifeTime.toInt())
        }
    }

    private fun startSendingCounter(durationSeconds: Int) {
        scope.launch {
            // Start with the full duration and count down
            _sendingCounterFlow.value = durationSeconds

            for (i in durationSeconds - 1 downTo 0) {
                delay(1000) // Wait 1 second
                if (_isSendingFlow.value) { // Only continue if still sending
                    _sendingCounterFlow.value = i
                } else {
                    break // Stop if sending was cancelled
                }
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun stopSendingMessage() {
        log.i { "Message lifetime expired, stopping transmission" }
        currentMessage = ""
        messageStartTime = 0L
        _isSendingFlow.value = false
        _connectionFlow.value = ConnectionState.CONNECTED
        _sendingCounterFlow.value = 0
    }

    fun updateMessageParameters(lifeTimeSeconds: Double, maxDistanceMeters: Double) {
        messageLifeTime = lifeTimeSeconds
        maxDistance = maxDistanceMeters
    }

    // Location Services
    fun startLocationTracking() {
        locationService?.let { service ->
            scope.launch {
                log.i { "Starting location tracking..." }
                
                // Give some time for permissions to be processed
                delay(1000)
                
                try {
                    // Get initial location
                    val initialLocation = service.getCurrentLocation()
                    _currentLocationFlow.value = initialLocation
                    _locationErrorFlow.value = null
                    
                    if (initialLocation != null) {
                        log.i { "Initial location: ${initialLocation.latitude}, ${initialLocation.longitude} (accuracy: ${initialLocation.accuracy}m)" }
                    } else {
                        log.w { "Initial location is null" }
                    }

                    // Start continuous location updates
                    service.startLocationUpdates { location ->
                        _currentLocationFlow.value = location
                        log.i { "Location update: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)" }
                    }
                } catch (e: LocationError) {
                    _locationErrorFlow.value = e
                    log.e { "Location error: ${e::class.simpleName}" }
                    when (e) {
                        is LocationError.PermissionDenied -> log.e { "Location permission denied - check device settings or grant permission" }
                        is LocationError.LocationDisabled -> log.e { "Location services disabled - enable in device settings" }
                        is LocationError.ServiceUnavailable -> log.e { "Location service unavailable" }
                        is LocationError.Unknown -> log.e { "Unknown location error: ${e.cause?.message}" }
                    }
                } catch (e: Exception) {
                    _locationErrorFlow.value = LocationError.Unknown(e)
                    log.e { "Unexpected location error: ${e.message}" }
                }
            }
        } ?: run {
            log.w { "Location service not available" }
        }
    }

    fun stopLocationTracking() {
        locationService?.stopLocationUpdates()
        log.i { "Location tracking stopped" }
    }

    fun getCurrentLocation() {
        locationService?.let { service ->
            scope.launch {
                try {
                    val location = service.getCurrentLocation()
                    _currentLocationFlow.value = location
                    _locationErrorFlow.value = null
                    
                    if (location != null) {
                        log.i { "Current location: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)" }
                    } else {
                        log.w { "Unable to get current location" }
                    }
                } catch (e: LocationError) {
                    _locationErrorFlow.value = e
                    log.e { "Failed to get current location: ${e::class.simpleName}" }
                } catch (e: Exception) {
                    _locationErrorFlow.value = LocationError.Unknown(e)
                    log.e { "Unexpected error getting location: ${e.message}" }
                }
            }
        } ?: run {
            log.w { "Location service not available" }
        }
    }

    /**
     * Calculate distance between two locations using Haversine formula
     * @param lat1 Latitude of first location
     * @param lon1 Longitude of first location  
     * @param lat2 Latitude of second location
     * @param lon2 Longitude of second location
     * @return Distance in meters
     */
//    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
//        val earthRadius = 6371000.0 // Earth radius in meters
//
//        val dLat = Math.toRadians(lat2 - lat1)
//        val dLon = Math.toRadians(lon2 - lon1)
//
//        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
//        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
//
//        return earthRadius * c
//    }

    fun testLocationFunctionality() {
        scope.launch {
            log.i { "Testing location functionality..." }
            
            locationService?.let { service ->
                try {
                    log.i { "Location enabled: ${service.isLocationEnabled()}" }
                    
                    // Wait a bit for permissions to be processed
                    delay(2000)
                    
                    val location = service.getCurrentLocation()
                    if (location != null) {
                        log.i { "Test location: ${location.latitude}, ${location.longitude}" }
                        log.i { "Accuracy: ${location.accuracy}m" }
                        log.i { "Timestamp: ${location.timestamp}" }
                    } else {
                        log.w { "No location available" }
                    }
                    
                    // Test location updates for 30 seconds
                    log.i { "Starting location updates test for 30 seconds..." }
                    service.startLocationUpdates { loc ->
                        log.i { "LOCATION: ${loc.latitude}, ${loc.longitude} (${loc.accuracy}m accuracy)" }
                    }
                    
                    delay(30000) // 30 seconds
                    service.stopLocationUpdates()
                    log.i { "Location updates test completed" }
                    
                } catch (e: LocationError) {
                    log.e { "Location test failed: ${e::class.simpleName}" }
                    when (e) {
                        is LocationError.PermissionDenied -> log.e { "Location permission denied - check device settings" }
                        is LocationError.LocationDisabled -> log.e { "Location services disabled - enable in device settings" }
                        is LocationError.ServiceUnavailable -> log.e { "Location service unavailable" }
                        is LocationError.Unknown -> log.e { "Unknown location error: ${e.cause?.message}" }
                    }
                } catch (e: Exception) {
                    log.e { "Location test error: ${e.message}" }
                }
            } ?: run {
                log.w { "Location service not available for testing" }
            }
        }
    }

    fun cleanup() {
        stopLocationTracking()
    }
}
