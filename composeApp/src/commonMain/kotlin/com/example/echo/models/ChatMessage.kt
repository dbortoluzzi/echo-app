package com.example.echo.models

import com.example.echo.serialization.UuidSerializer
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Represents a chat message in the Echo app.
 * Includes the content of the message [text], the sender's unique ID [sender],
 * a unique message ID [messageId], a timestamp [timestamp], and distance from the source [distanceFromSource].
 */
@Serializable
@OptIn(ExperimentalUuidApi::class)
data class ChatMessage
@OptIn(ExperimentalTime::class)
constructor(
    val text: String,
    @Serializable(with = UuidSerializer::class)
    val sender: Uuid,
    @Serializable(with = UuidSerializer::class)
    val messageId: Uuid = Uuid.random(), // Unique ID for each message instance
    val timestamp: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
    val distanceFromSource: Double = 0.0,
)

/**
 * Configuration for gossip algorithm parameters.
 */
@Serializable
data class GossipConfig(
    val maxDistance: Double = 1000.0, // meters
    val lifeTime: Double = 60.0, // seconds
)
