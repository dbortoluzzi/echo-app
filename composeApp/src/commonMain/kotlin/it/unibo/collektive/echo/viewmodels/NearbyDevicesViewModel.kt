package it.unibo.collektive.echo.viewmodels

import com.diamondedge.logging.logging
import it.unibo.collektive.Collektive
import it.unibo.collektive.aggregate.Field
import it.unibo.collektive.aggregate.api.neighboring
import it.unibo.collektive.aggregate.ids
import it.unibo.collektive.aggregate.toMap
import it.unibo.collektive.echo.DEFAULT_MAX_DISTANCE
import it.unibo.collektive.echo.DEFAULT_MAX_TIME
import it.unibo.collektive.echo.MessageSettings
import it.unibo.collektive.echo.MQTT_HOST
import it.unibo.collektive.echo.saveMessageSettings
import it.unibo.collektive.echo.gossip.ActiveGossipSend
import it.unibo.collektive.echo.gossip.chatMultipleSources
import it.unibo.collektive.echo.location.Location
import it.unibo.collektive.echo.location.LocationError
import it.unibo.collektive.echo.location.LocationService
import it.unibo.collektive.echo.models.ChatMessage
import it.unibo.collektive.echo.network.mqtt.MqttMailbox
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
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
    @OptIn(ExperimentalUuidApi::class)
    val deviceId: Uuid,
    initialMessageSettings: MessageSettings = MessageSettings(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val locationService: LocationService,
) {
    /** Logger instance for ViewModel diagnostics. */
    val log = logging("VIEWMODEL")

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @OptIn(ExperimentalUuidApi::class)
    private val _dataFlow = MutableStateFlow<Set<Uuid>>(emptySet())

    /** Flow of discovered device UUIDs. */
    @OptIn(ExperimentalUuidApi::class)
    val dataFlow: StateFlow<Set<Uuid>> = _dataFlow.asStateFlow()

    private val _connectionFlow = MutableStateFlow(ConnectionState.DISCONNECTED)

    /** Flow of the current MQTT connection state. */
    val connectionFlow: StateFlow<ConnectionState> = _connectionFlow.asStateFlow()

    private val _connectionErrorMessageFlow = MutableStateFlow<String?>(null)

    /** Flow of latest human-readable connection error to show in UI. */
    val connectionErrorMessageFlow: StateFlow<String?> = _connectionErrorMessageFlow.asStateFlow()

    private val _messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())

    /** Flow of received and sent chat messages. */
    val messagesFlow: StateFlow<List<ChatMessage>> = _messagesFlow.asStateFlow()

    private val _isSendingFlow = MutableStateFlow(false)

    /** Flow indicating whether at least one outbound message is still within its TTL. */
    val isSendingFlow: StateFlow<Boolean> = _isSendingFlow.asStateFlow()

    private val _sendingCounterFlow = MutableStateFlow(0)

    /** Flow of the maximum remaining seconds among active outbound messages (0 if none). */
    val sendingCounterFlow: StateFlow<Int> = _sendingCounterFlow.asStateFlow()

    private val _messageLifeTimeFlow = MutableStateFlow(initialMessageSettings.ttlSeconds)
    val messageLifeTimeFlow: StateFlow<Double> = _messageLifeTimeFlow.asStateFlow()

    private val _maxDistanceFlow = MutableStateFlow(initialMessageSettings.maxDistanceMeters)
    val maxDistanceFlow: StateFlow<Double> = _maxDistanceFlow.asStateFlow()

    private val _currentLocationFlow = MutableStateFlow<Location?>(null)

    /** Flow of the device's most recent GPS [Location], or `null` if unknown. */
    val currentLocationFlow: StateFlow<Location?> = _currentLocationFlow.asStateFlow()

    private val _locationErrorFlow = MutableStateFlow<LocationError?>(null)

    /** Flow of the latest [LocationError], or `null` when no error is present. */
    val locationErrorFlow: StateFlow<LocationError?> = _locationErrorFlow.asStateFlow()

    /**
     * Outbound messages this device is still broadcasting (TTL from [messageStartTime]).
     * Each Collektive round turns them into [ActiveGossipSend] with `elapsedSeconds` computed there.
     */
    @OptIn(ExperimentalUuidApi::class)
    private data class ActiveBroadcast(
        val messageId: Uuid,
        val content: String,
        val messageStartTime: Long,
        val lifeTime: Double,
        val maxDistance: Double,
    )

    private val _activeBroadcastsFlow = MutableStateFlow<List<ActiveBroadcast>>(emptyList())

    /** Latest slider-chosen TTL and radius; used by [sendMessage] unless you pass overrides explicitly. */
    private var messageLifeTime: Double = initialMessageSettings.ttlSeconds
    private var maxDistance: Double = initialMessageSettings.maxDistanceMeters

    /** Represents the MQTT broker connection state. */
    enum class ConnectionState {
        /** Successfully connected to the broker. */
        CONNECTED,

        /** Not connected to the broker. */
        DISCONNECTED,

        /** Actively broadcasting a message. */
        SENDING,
    }

    /** Constants used by the nearby-devices view model. */
    companion object {
        /** Interval in milliseconds between GPS availability checks. */
        private const val GPS_POLL_INTERVAL_MS = 500L
        private const val MQTT_RETRY_INTERVAL_MS = 3000L

        /** Mean Earth radius in metres, used by the Haversine formula. */
        private const val EARTH_RADIUS_METERS = 6371000.0

        /** Degrees-per-half-circle, used to convert degrees to radians. */
        private const val DEGREES_HALF_CIRCLE = 180.0
    }

    private var mqttMailbox: MqttMailbox? = null

    /**
     * Collektive program using MQTT mailbox and GPS location for proximity-based messaging.
     */
    @OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
    private suspend fun collektiveProgram(): Collektive<Uuid, Pair<Set<Uuid>, List<ChatMessage>>> {
        // Wait for GPS location to be available before creating MQTT mailbox
        val initialLocation = checkNotNull(_currentLocationFlow.value) {
            "GPS location is required but not available for MQTT initialization"
        }

        return Collektive(
            deviceId,
            MqttMailbox(
                deviceId = deviceId,
                initialLocation = initialLocation,
                host = MQTT_HOST,
                dispatcher = dispatcher,
            ).also { mqttMailbox = it },
        ) {
            // Get neighboring devices
            val neighborMap = neighboring(localId)
            val neighbors = neighborMap.neighbors.ids.set

            val nowEpoch = Clock.System.now().epochSeconds
            val broadcasts = _activeBroadcastsFlow.value
            val activeSends =
                broadcasts.map { b ->
                    ActiveGossipSend(
                        messageId = b.messageId,
                        content = b.content,
                        lifeTime = b.lifeTime,
                        maxDistance = b.maxDistance,
                        elapsedSeconds = (nowEpoch - b.messageStartTime).toDouble(),
                    )
                }

            if (broadcasts.isNotEmpty()) {
                log.i { "Device is source: ${broadcasts.size} active broadcast(s)" }
            }

            // Calculate distances to neighboring devices using GPS coordinates
            val distances = calculateNeighborDistances(neighborMap)
            val currentLocation = checkNotNull(_currentLocationFlow.value) {
                "GPS location is required but not available"
            } // GPS is mandatory, should never be null

            log.i { "Current GPS location: ${currentLocation.latitude}, ${currentLocation.longitude}" }

            log.i { "GPS-based distances calculated: ${distances.all.toMap()}" }

            // Collect all messages from all potential sources using chatMultipleSources
            val allSourceMessages = chatMultipleSources(
                distances = distances,
                localSends = activeSends,
            )

            // Convert messages to ChatMessage objects
            val chatMessages = allSourceMessages.mapNotNull { (source, message) ->
                if (message.content.isNotEmpty()) {
                    val (senderId, _) = source
                    log.i {
                        "Received gossip message from $senderId: " +
                            "'${message.content}' at distance ${message.distanceFromSource}"
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
                delay(GPS_POLL_INTERVAL_MS) // Check periodically
            }
            while (isActive) {
                try {
                    val program = collektiveProgram()
                    _connectionErrorMessageFlow.value = null
                    log.i { "Collektive program started with GPS location: ${_currentLocationFlow.value}" }

                    while (isActive) {
                        // Outside the Collektive block: refresh UI state and TTL without mutating the aggregate program.
                        val nowEpoch = Clock.System.now().epochSeconds
                        _activeBroadcastsFlow.value =
                            _activeBroadcastsFlow.value.filter { b ->
                                nowEpoch - b.messageStartTime <= b.lifeTime
                            }
                        val broadcasts = _activeBroadcastsFlow.value
                        _isSendingFlow.value = broadcasts.isNotEmpty()
                        _connectionFlow.value =
                            if (broadcasts.isNotEmpty()) {
                                ConnectionState.SENDING
                            } else {
                                ConnectionState.CONNECTED
                            }
                        _sendingCounterFlow.value =
                            broadcasts.maxOfOrNull { b ->
                                val left = b.lifeTime - (nowEpoch - b.messageStartTime)
                                left.toInt().coerceAtLeast(0)
                            } ?: 0

                        val (newDevices, newMessages) = program.cycle()
                        _dataFlow.value = newDevices

                        val currentMessages = _messagesFlow.value.toMutableList()
                        newMessages.forEach { newMessage ->
                            val isDuplicate = currentMessages.any { existing ->
                                existing.messageId == newMessage.messageId
                            }

                            if (!isDuplicate) {
                                log.i {
                                    "Adding NEW message to UI: '${newMessage.text}' " +
                                        "from ${newMessage.sender} (ID: ${newMessage.messageId})"
                                }
                                currentMessages.add(newMessage)
                            } else {
                                log.i {
                                    "Skipping duplicate message: '${newMessage.text}' " +
                                        "from ${newMessage.sender} (ID: ${newMessage.messageId})"
                                }
                            }
                        }
                        _messagesFlow.value = currentMessages

                        delay(1.seconds)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught")
                    e: Exception,
                ) {
                    _connectionFlow.value = ConnectionState.DISCONNECTED
                    _connectionErrorMessageFlow.value = "Connessione al broker fallita. Riprovo..."
                    log.e { "MQTT connection/setup failed: ${e.message}. Retrying..." }
                    delay(MQTT_RETRY_INTERVAL_MS)
                }
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
        lifeTime: Double = messageLifeTime,
        maxDistanceMeters: Double = maxDistance,
    ) {
        scope.launch {
            log.i { "Message: '$message'" }
            _isSendingFlow.value = true
            _connectionFlow.value = ConnectionState.SENDING
            _sendingCounterFlow.value = 0

            messageLifeTime = lifeTime
            maxDistance = maxDistanceMeters

            val currentMessageId = Uuid.random()
            val messageStartTime = Clock.System.now().epochSeconds
            val broadcast =
                ActiveBroadcast(
                    messageId = currentMessageId,
                    content = message,
                    messageStartTime = messageStartTime,
                    lifeTime = lifeTime,
                    maxDistance = maxDistanceMeters,
                )
            _activeBroadcastsFlow.value = _activeBroadcastsFlow.value + broadcast

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
        }
    }

    /**
     * Updates TTL and max radius for the next [sendMessage] (kept in sync with the UI sliders).
     */
    fun updateMessageParameters(lifeTimeSeconds: Double, maxDistanceMeters: Double) {
        messageLifeTime = lifeTimeSeconds
        maxDistance = maxDistanceMeters
        _messageLifeTimeFlow.value = lifeTimeSeconds
        _maxDistanceFlow.value = maxDistanceMeters
        saveMessageSettings(
            MessageSettings(
                ttlSeconds = lifeTimeSeconds,
                maxDistanceMeters = maxDistanceMeters,
            ),
        )
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
                    "Initial location: ${initialLocation.latitude}, " +
                        "${initialLocation.longitude} (accuracy: ${initialLocation.accuracy}m)"
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
                // Don't re-throw: the error is exposed via locationErrorFlow for the UI.
                // Re-throwing would crash the app, especially on iOS simulator without GPS.
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: RuntimeException,
            ) {
                _locationErrorFlow.value = LocationError.Unknown(e)
                log.e { "Unexpected GPS error: ${e.message}" }
                // Don't re-throw: same reason as above.
            }
        }
    }

    /**
     * Calculate distance between two locations using Haversine formula.
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * PI / DEGREES_HALF_CIRCLE
        val dLon = (lon2 - lon1) * PI / DEGREES_HALF_CIRCLE
        val a = sin(dLat / 2).pow(2) +
            cos(lat1 * PI / DEGREES_HALF_CIRCLE) * cos(lat2 * PI / DEGREES_HALF_CIRCLE) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Calculate distances to all neighboring devices [neighborMap] using GPS coordinates shared via MQTT.
     * Excludes the device itself and only processes neighbors with GPS data available.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun calculateNeighborDistances(neighborMap: Field<Uuid, *>): Field<Uuid, Double> {
        val currentLocation = checkNotNull(_currentLocationFlow.value) {
            "GPS location is required but not available"
        }
        val mailbox = checkNotNull(mqttMailbox) {
            "MQTT mailbox is required but not available"
        }
        // Filter out this device itself and neighbors without GPS data
        val actualNeighbors = neighborMap.neighbors.ids.list.filter { id ->
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
            "Processing ${actualNeighbors.size} actual neighbors with GPS data" +
                " out of ${neighborMap.neighbors.size} total discovered devices"
        }

        // Create a map of distances for valid neighbors only
        val distancesMap = mutableMapOf<Uuid, Double>()

        actualNeighbors.forEach { neighborId ->
            val neighborLocation = checkNotNull(mailbox.getNeighborLocation(neighborId)) {
                "Neighbor $neighborId location should be available after filtering"
            }

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
        val mailbox = mqttMailbox
        mqttMailbox = null
        CoroutineScope(dispatcher).launch {
            mailbox?.close()
        }
        stopLocationTracking()
        scope.cancel()
    }

    /**
     * Stop GPS location tracking.
     */
    fun stopLocationTracking() {
        locationService.stopLocationUpdates()
        log.i { "GPS tracking stopped" }
    }
}
