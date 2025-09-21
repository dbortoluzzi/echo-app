package com.example.echo.models

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Represents a chat message in the Echo app.
 */
@OptIn(ExperimentalUuidApi::class)
data class ChatMessage
@OptIn(ExperimentalTime::class)
constructor(
    val text: String,
    val sender: Uuid,
    val timestamp: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
    val distanceFromSource: Double = 0.0,
)

/**
 * Configuration for gossip algorithm parameters.
 */
data class GossipConfig(
    val maxDistance: Double = 1000.0, // meters
    val lifeTime: Double = 60.0, // seconds
)
