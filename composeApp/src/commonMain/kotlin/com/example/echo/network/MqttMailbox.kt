package com.example.echo.network

import com.diamondedge.logging.logging
import com.example.echo.PORT_NUMBER_BROKER
import io.github.davidepianca98.MQTTClient
import io.github.davidepianca98.mqtt.MQTTVersion
import io.github.davidepianca98.mqtt.Subscription
import io.github.davidepianca98.mqtt.packets.Qos
import io.github.davidepianca98.mqtt.packets.mqttv5.ReasonCode
import io.github.davidepianca98.mqtt.packets.mqttv5.SubscriptionOptions
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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
) : AbstractSerializerMailbox<Uuid>(deviceId, serializer, retentionTime) {
    private val internalScope = CoroutineScope(dispatcher)
    private var mqttClient: MQTTClient? = null
    val log = logging("MQTT-MAILBOX")

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun initializeMqttClient() {
        mqttClient = MQTTClient(
            MQTTVersion.MQTT5,
            host,
            port,
            null,
        ) { message ->
            // Handle incoming messages and heartbeat
            val topic = message.topicName
            val payload = message.payload?.toByteArray()

            when {
                topic.startsWith(HEARTBEAT_PREFIX) -> {
                    // Handle heartbeat
                    val neighborDeviceId = Uuid.parse(topic.split("/").last())
                    addNeighbor(neighborDeviceId)
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

    @OptIn(ExperimentalUnsignedTypes::class)
    private suspend fun sendHeartbeatPulse() {
        mqttClient?.publish(
            retain = false,
            qos = Qos.AT_MOST_ONCE,
            topic = heartbeatTopic(deviceId),
            payload = byteArrayOf().toUByteArray(),
        )
        delay(1.seconds)
        sendHeartbeatPulse()
    }

    private suspend fun cleanHeartbeatPulse() {
        cleanupNeighbors(retentionTime)
        delay(retentionTime)
        cleanHeartbeatPulse()
    }

    override suspend fun close() {
        log.i { "Disconnecting from the broker..." }
        internalScope.cancel()
        mqttClient?.disconnect(ReasonCode.DISCONNECT_WITH_WILL_MESSAGE)
        mqttClient = null
        log.i("MqttMailbox") { "Disconnected from the broker" }
    }

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
     * Companion object to create a new instance of [MqttMailbox].
     */
    companion object {
        /**
         * Create a new instance of [MqttMailbox].
         */
        suspend operator fun invoke(
            deviceId: Uuid,
            host: String,
            port: Int = PORT_NUMBER_BROKER,
            serializer: SerialFormat = Json,
            retentionTime: Duration = 5.seconds,
            dispatcher: CoroutineDispatcher,
        ): MqttMailbox = coroutineScope {
            MqttMailbox(deviceId, host, port, serializer, retentionTime, dispatcher).apply {
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
