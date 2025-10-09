package com.example.echo.viewmodels

import com.diamondedge.logging.logging
import com.example.echo.DEFAULT_MAX_DISTANCE
import com.example.echo.DEFAULT_MAX_TIME
import com.example.echo.gossip.chatMultipleSources
import com.example.echo.location.Location
import com.example.echo.location.LocationError
import com.example.echo.location.LocationService
import com.example.echo.models.ChatMessage
import com.example.echo.network.mqtt.MqttMailbox
import it.unibo.collektive.Collektive
import it.unibo.collektive.aggregate.Field
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
import kotlin.math.PI
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

/**
 * ViewModel for managing nearby devices, location tracking [locationService], and message sending.
 * 
 */
class NearbyDevicesViewModel(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val locationService: LocationService,
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
        SENDING, 
    }

    private var mqttMailbox: MqttMailbox? = null

    /**
     * Collektive program using MQTT mailbox and GPS location for proximity-based messaging.
     */
    @OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
    private suspend fun collektiveProgram(): Collektive<Uuid, Pair<Set<Uuid>, List<ChatMessage>>> {
        // Wait for GPS location to be available before creating MQTT mailbox
        val initialLocation = _currentLocationFlow.value
            ?: throw IllegalStateException("GPS location is required but not available for MQTT initialization")

        return Collektive(
            deviceId,
            MqttMailbox(
                deviceId = deviceId,
                initialLocation = initialLocation,
                host = "broker.hivemq.com",
                dispatcher = dispatcher,
            ).also { mqttMailbox = it },
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
                log.i {
                    "Device is source: sending message '$currentMessage' (${currentTime - messageStartTime}s elapsed)"
                }
            }

            // Calculate distances to neighboring devices using GPS coordinates
            val distances = calculateNeighborDistances(neighborMap)
            val currentLocation = _currentLocationFlow.value!! // GPS is mandatory, should never be null

            log.i { "Current GPS location: ${currentLocation.latitude}, ${currentLocation.longitude}" }


            log.i { "GPS-based distances calculated: ${distances.toMap()}" }

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
    }

    /**
     * Starts the Collektive program after ensuring GPS location is available.
     */
    @OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
    fun startCollektiveProgram() {
        scope.launch {
            log.i { "Starting Collektive program..." }

            // Wait for GPS location to be available - GPS is mandatory
            while (_currentLocationFlow.value == null) {
                log.i { "Waiting for GPS location before starting Collektive program..." }
                delay(500) // Check every 500ms
            }

            _connectionFlow.value = ConnectionState.CONNECTED
            val program = collektiveProgram()
            log.i { "Collektive program started with GPS location: ${_currentLocationFlow.value}" }
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
                        log.i {
                            "Adding NEW message to UI: '${newMessage.text}' from ${newMessage.sender} (ID: ${newMessage.messageId})"
                        }
                        currentMessages.add(newMessage)
                    } else {
                        log.i {
                            "Skipping duplicate message: '${newMessage.text}' from ${newMessage.sender} (ID: ${newMessage.messageId})"
                        }
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

    /**
     * Send message with content [message], a [lifeTime] and a maximum distance propagation [maxDistanceMeters].
     * 
     */
    @OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
    fun sendMessage(
        message: String,
        lifeTime: Double = DEFAULT_MAX_TIME,
        maxDistanceMeters: Double = DEFAULT_MAX_DISTANCE,
    ) {
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

    /**
     * Start a countdown timer [durationSeconds] for the sending duration, updating every second.
     */
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

    /**
     * Stop sending the current message when its lifetime expires.
     */
    @OptIn(ExperimentalTime::class)
    private fun stopSendingMessage() {
        log.i { "Message lifetime expired, stopping transmission" }
        currentMessage = ""
        messageStartTime = 0L
        _isSendingFlow.value = false
        _connectionFlow.value = ConnectionState.CONNECTED
        _sendingCounterFlow.value = 0
    }

    /**
     * Update message parameters for future messages with new [lifeTimeSeconds] and [maxDistanceMeters].
     */
    fun updateMessageParameters(lifeTimeSeconds: Double, maxDistanceMeters: Double) {
        messageLifeTime = lifeTimeSeconds
        maxDistance = maxDistanceMeters
    }

    /**
     * Start GPS location tracking, updating location flow and MQTT mailbox.
     */
    fun startLocationTracking() {
        scope.launch {
            log.i { "Starting location tracking..." }
            // Give some time for permissions to be processed
            delay(1000)

            try {
                // Get initial location
                val initialLocation = locationService.getCurrentLocation()
                    ?: throw LocationError.ServiceUnavailable

                _currentLocationFlow.value = initialLocation
                _locationErrorFlow.value = null

                // Update MQTT mailbox with initial location
                mqttMailbox?.updateCurrentLocation(initialLocation)

                log.i {
                    "Initial location: ${initialLocation.latitude}, ${initialLocation.longitude} (accuracy: ${initialLocation.accuracy}m)"
                }

                // Start continuous location updates
                locationService.startLocationUpdates { location ->
                    _currentLocationFlow.value = location
                    // Update MQTT mailbox with new location for sharing with neighbors
                    mqttMailbox?.updateCurrentLocation(location)
                    log.i {
                        "Location update: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)"
                    }
                }
            } catch (e: LocationError) {
                _locationErrorFlow.value = e
                log.e { "Location error - GPS is mandatory for this app: ${e::class.simpleName}" }
                when (e) {
                    is LocationError.PermissionDenied -> log.e {
                        "GPS permission denied - app cannot function without location access"
                    }
                    is LocationError.LocationDisabled -> log.e {
                        "GPS services disabled - app requires GPS to be enabled"
                    }
                    is LocationError.ServiceUnavailable -> log.e { "GPS service unavailable - app cannot function" }
                    is LocationError.Unknown -> log.e { "Unknown GPS error: ${e.cause?.message}" }
                }
                throw e // Re-throw since GPS is mandatory
            } catch (e: Exception) {
                _locationErrorFlow.value = LocationError.Unknown(e)
                log.e { "Unexpected GPS error: ${e.message}" }
                throw LocationError.Unknown(e)
            }
        }
    }

    /**
     * Calculate distance between two locations using Haversine formula.
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // Earth radius in meters

        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2).pow(2) + cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    /**
     * Calculate distances to all neighboring devices [neighborMap] using GPS coordinates shared via MQTT.
     * Excludes the device itself and only processes neighbors with GPS data available.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun calculateNeighborDistances(neighborMap: Field<Uuid, *>): Field<Uuid, Double> {
        val currentLocation = _currentLocationFlow.value
            ?: throw IllegalStateException("GPS location is required but not available")
        val mailbox = mqttMailbox
            ?: throw IllegalStateException("MQTT mailbox is required but not available")

        // Filter out this device itself and neighbors without GPS data
        val actualNeighbors = neighborMap.neighbors.filter { id ->
            // Skip if this is the same device
            if (id == deviceId) {
                log.d { "Excluding self ($id) from neighbor calculations" }
                return@filter false
            }

            // Skip if no GPS data available yet
            val hasLocation = mailbox.getNeighborLocation(id) != null
            if (!hasLocation) {
                log.d { "Neighbor $id GPS data not available yet - excluding from distance calculation" }
            }
            hasLocation
        }

        log.i {
            "Processing ${actualNeighbors.size} actual neighbors with GPS data out of ${neighborMap.neighbors.size} total discovered devices"
        }

        // Create a map of distances for valid neighbors only
        val distancesMap = mutableMapOf<Uuid, Double>()

        actualNeighbors.forEach { neighborId ->
            val neighborLocation = mailbox.getNeighborLocation(neighborId)!! // Safe because we filtered

            val distance = calculateDistance(
                currentLocation.latitude,
                currentLocation.longitude,
                neighborLocation.latitude,
                neighborLocation.longitude,
            )
            log.d { "Distance to neighbor $neighborId: ${distance}m" }
            distancesMap[neighborId] = distance
        }

        // Map all neighbors to their distances, using a very large distance for invalid neighbors
        // The gossip algorithm will naturally ignore neighbors beyond maxDistance
        return neighborMap.map { neighborId ->
            distancesMap[neighborId.id] ?: Double.MAX_VALUE
        }
    }

    /**
     * Cleanup resources when ViewModel is no longer needed.
     */
    fun cleanup() {
        stopLocationTracking()
    }
    /**
     * Stop GPS location tracking.
     */
    fun stopLocationTracking() {
        locationService.stopLocationUpdates()
        log.i { "GPS tracking stopped" }
    }
}
