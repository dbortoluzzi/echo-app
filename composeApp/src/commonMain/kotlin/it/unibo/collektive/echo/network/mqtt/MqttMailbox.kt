package it.unibo.collektive.echo.network.mqtt

import com.diamondedge.logging.logging
import io.github.davidepianca98.MQTTClient
import io.github.davidepianca98.mqtt.MQTTVersion
import io.github.davidepianca98.mqtt.Subscription
import io.github.davidepianca98.mqtt.packets.Qos
import io.github.davidepianca98.mqtt.packets.mqttv5.ReasonCode
import io.github.davidepianca98.mqtt.packets.mqttv5.SubscriptionOptions
import it.unibo.collektive.echo.MQTT_HOST
import it.unibo.collektive.echo.PORT_NUMBER_BROKER
import it.unibo.collektive.echo.WEBSOCKET_ENDPOINT
import it.unibo.collektive.echo.location.Location
import it.unibo.collektive.echo.models.DeviceLocation
import it.unibo.collektive.echo.models.DeviceLocationHeartbeat
import it.unibo.collektive.echo.network.AbstractSerializerMailbox
import it.unibo.collektive.networking.Message
import it.unibo.collektive.networking.SerializedMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A mailbox that uses MQTT as the underlying transport.
 */
@OptIn(ExperimentalUuidApi::class)
class MqttMailbox private constructor(
    private val deviceId: Uuid,
    private val host: String,
    private val port: Int,
    private val serializer: SerialFormat,
    private val retentionTime: Duration,
    private val dispatcher: CoroutineDispatcher,
    initialLocation: Location, // GPS is mandatory - must be provided at creation
) : AbstractSerializerMailbox<Uuid>(deviceId, serializer, retentionTime) {
    private val internalScope = CoroutineScope(dispatcher)
    private var mqttClient: MQTTClient? = null

    /** Logger instance for MQTT mailbox diagnostics. */
    val log = logging("MQTT-MAILBOX")

    // Store current location and neighbor locations - GPS is mandatory
    private var currentLocation: Location = initialLocation // GPS location is required and non-null
    private val neighborLocations = mutableMapOf<Uuid, Location>()

    // Dedicated JSON serializer for location heartbeat messages
    private val jsonSerializer = Json { ignoreUnknownKeys = true }

    /**
     * Initialize the MQTT client and connect to the broker.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun initializeMqttClient() {
        mqttClient = MQTTClient(
            MQTTVersion.MQTT5,
            host,
            port,
            webSocket = WEBSOCKET_ENDPOINT,
            tls = null,
        ) { message ->
            // Handle incoming messages and heartbeat
            val topic = message.topicName
            val payload = message.payload?.toByteArray()

            when {
                topic.startsWith(HEARTBEAT_PREFIX) -> {
                    // Handle heartbeat with location information
                    val neighborDeviceId = Uuid.Companion.parse(topic.split("/").last())
                    addNeighbor(neighborDeviceId)

                    // Try to parse location information from heartbeat payload
                    if (payload != null && payload.isNotEmpty()) {
                        try {
                            val heartbeat =
                                jsonSerializer.decodeFromString<DeviceLocationHeartbeat>(
                                    payload.decodeToString(),
                                )
                            // Save the neighbor's location - this was missing!
                            heartbeat.location?.toLocation()?.let { neighborLocation ->
                                neighborLocations[neighborDeviceId] = neighborLocation
                                log.d {
                                    "Received GPS location from neighbor $neighborDeviceId: " +
                                        "${neighborLocation.latitude}, ${neighborLocation.longitude}"
                                }
                            }
                        } catch (
                            @Suppress("TooGenericExceptionCaught")
                            e: RuntimeException,
                        ) {
                            log.w { "Failed to parse location from heartbeat: ${e.message}" }
                        }
                    }
                }

                topic == deviceTopic(deviceId) -> {
                    // Handle device messages
                    if (payload != null) {
                        try {
                            val deserialized = serializer.decodeSerialMessage<Uuid>(payload)
                            log.d { "Received message from ${deserialized.senderId}" }
                            deliverableReceived(deserialized)
                        } catch (exception: SerializationException) {
                            log.e { "Failed to deserialize message from $topic: ${exception.message}" }
                        }
                    }
                }
            }
        }

        log.i { "Connected to the broker" }

        // Subscribe to topics
        val subscriptions = listOf(
            // heartbeat with Quality of service -> 0 (fire and forget)
            Subscription(HEARTBEAT_WILD_CARD, SubscriptionOptions(Qos.AT_MOST_ONCE)),
            // deviceId message with Quality of service -> 1 (requiring a PUBACK acknowledgment)
            Subscription(deviceTopic(deviceId), SubscriptionOptions(Qos.AT_LEAST_ONCE)),
        )
        mqttClient?.subscribe(subscriptions)

        // Start background tasks
        internalScope.launch(dispatcher) { sendHeartbeatPulse() }
        internalScope.launch { cleanHeartbeatPulse() }
        internalScope.launch(dispatcher) { mqttClient?.run() }
    }

    /**
     * Sends a heartbeat pulse with the current device location.
     */
    @OptIn(ExperimentalUnsignedTypes::class, ExperimentalTime::class)
    private suspend fun sendHeartbeatPulse() {
        // Create heartbeat with location information - GPS is mandatory and always available
        val heartbeat = DeviceLocationHeartbeat(
            deviceId = deviceId.toString(),
            location = DeviceLocation.Companion.fromLocation(currentLocation),
            timestamp = Clock.System.now().epochSeconds,
        )

        val payload = try {
            val json = jsonSerializer.encodeToString(heartbeat)
            json.encodeToByteArray().toUByteArray()
        } catch (e: SerializationException) {
            log.e { "Failed to serialize heartbeat with GPS location: ${e.message}" }
            throw IllegalStateException("Cannot serialize GPS location data - app cannot function", e)
        }

        mqttClient?.publish(
            retain = false,
            qos = Qos.AT_MOST_ONCE,
            topic = heartbeatTopic(deviceId),
            payload = payload,
        )
        delay(1.seconds)
        sendHeartbeatPulse()
    }

    /**
     * Cleans up old heartbeat messages and neighbors.
     */
    private suspend fun cleanHeartbeatPulse() {
        cleanupNeighbors(retentionTime)
        delay(retentionTime)
        cleanHeartbeatPulse()
    }

    /**
     * Closes the MQTT connection and cleans up resources.
     */
    override suspend fun close() {
        log.i { "Disconnecting from the broker..." }
        internalScope.cancel()
        mqttClient?.disconnect(ReasonCode.DISCONNECT_WITH_WILL_MESSAGE)
        mqttClient = null
        log.i("MqttMailbox") { "Disconnected from the broker" }
    }

    /**
     * Sends a [message] to a specific receiver [receiverId].
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    override fun onDeliverableReceived(receiverId: Uuid, message: Message<Uuid, Any?>) {
        require(message is SerializedMessage<Uuid>)
        log.i("MqttMailbox") { "Sending message to $receiverId from $deviceId" }
        internalScope.launch(dispatcher) {
            mqttClient?.publish(
                retain = false,
                qos = Qos.AT_LEAST_ONCE,
                topic = deviceTopic(receiverId),
                payload = serializer.encodeSerialMessage(message).toUByteArray(),
            )
        }
    }

    /**
     * Update the current device [location] for sharing with neighbors.
     * GPS location is mandatory for the app to function.
     */
    fun updateCurrentLocation(location: Location) {
        currentLocation = location
        log.d { "Updated device location: ${location.latitude}, ${location.longitude}" }
    }

    /**
     * Get the location of a specific neighbor device [neighborId].
     */
    fun getNeighborLocation(neighborId: Uuid): Location? = neighborLocations[neighborId]

    /**
     * Companion object to create a new instance of [MqttMailbox].
     */
    companion object {
        /**
         * Create a new instance of [MqttMailbox].
         * GPS location is mandatory and must be provided.
         */
        suspend operator fun invoke(
            deviceId: Uuid,
            initialLocation: Location,
            host: String = MQTT_HOST,
            port: Int = PORT_NUMBER_BROKER,
            serializer: SerialFormat = Json.Default,
            retentionTime: Duration = 5.seconds,
            dispatcher: CoroutineDispatcher,
        ): MqttMailbox = coroutineScope {
            MqttMailbox(
                deviceId,
                host,
                port,
                serializer,
                retentionTime,
                dispatcher,
                initialLocation,
            ).apply {
                initializeMqttClient()
            }
        }

        private const val APP_NAMESPACE = "Echo"
        private const val HEARTBEAT_PREFIX = "$APP_NAMESPACE/heartbeat"
        private const val HEARTBEAT_WILD_CARD = "$HEARTBEAT_PREFIX/+"
        private fun deviceTopic(deviceId: Uuid) = "$APP_NAMESPACE/device/$deviceId"
        private fun heartbeatTopic(deviceId: Uuid) = "$HEARTBEAT_PREFIX/$deviceId"
    }
}
