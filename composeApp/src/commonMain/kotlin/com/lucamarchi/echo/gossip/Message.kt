package com.lucamarchi.echo.gossip

import com.lucamarchi.echo.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Represents a message with [content] propagating in space,
 * along with its [distanceFromSource], unique [messageId], and [maxDistance] set by sender.
 */
@Serializable
@OptIn(ExperimentalUuidApi::class)
data class Message(
    val content: String,
    val distanceFromSource: Double,
    @Serializable(with = UuidSerializer::class)
    val messageId: Uuid,
    val maxDistance: Double,
)
