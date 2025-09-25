package com.example.echo.gossip

import it.unibo.collektive.aggregate.Field
import it.unibo.collektive.aggregate.api.Aggregate
import it.unibo.collektive.aggregate.api.share
import kotlin.collections.iterator
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Computes the minimum gradient distance from a source node to all other nodes in a distributed system
 * using gossip-based communication with [GossipGradient].
 *
 * The function iteratively propagates [distances] information across
 * neighbors while avoiding loops in the paths.
 * It stabilizes to the minimal distance once information has been fully shared.
 * Broadcasts a message containing the provided [content] to all nodes within the given [maxDistance]
 * for the defined [lifeTime].
 */
@OptIn(ExperimentalUuidApi::class)
internal fun Aggregate<Uuid>.gossipGradient(
    distances: Field<Uuid, Double>,
    target: Uuid,
    isSource: Boolean,
    currentTime: Double,
    content: String,
    lifeTime: Double,
    maxDistance: Double,
): Message? {
    /*
    Indicate if the current node is the target of the gradient calculation.
     */
    val isTargetNode = localId == target
    /*
    Only broadcast content if this node is both the target of the gradient calculation
    and is a source.
     */
    val localContent = if (isTargetNode && isSource) content else ""
    /*
    If the node is the target of the gradient calculation initialize its distance as 0.0 (from itself).
     */
    val localDistance = if (isTargetNode) 0.0 else Double.MAX_VALUE
    val localGossip = GossipGradient(
        distance = localDistance,
        localDistance = localDistance,
        content = localContent,
        path = listOf(localId),
    )

    val distanceMap = distances.toMap()
    val result = share(localGossip) { neighborsGossip: Field<Uuid, GossipGradient> ->
        var bestGossip = localGossip
        val neighbors = neighborsGossip.toMap().keys

        for ((neighborId, neighborGossip) in neighborsGossip.toMap()) {
            val recentPath = neighborGossip.path.asReversed().drop(1)
            val pathIsValid = recentPath.none { it == localId || it in neighbors }
            val nextGossip = if (pathIsValid) neighborGossip else neighborGossip.base(neighborId)
            val totalDistance: Double = nextGossip.distance + distanceMap.getOrElse(neighborId) { nextGossip.distance }

            // Accept gossip if it has better distance AND has content
            if (totalDistance < bestGossip.distance && neighborGossip.content.isNotEmpty()) {
                bestGossip = nextGossip.addHop(totalDistance, localGossip.localDistance, localId)
            }
        }
        bestGossip
    }

    /*
    Filter the message before sharing it.
    Note: We don't filter out messages from the target node itself, as sources should
    be able to see their own messages for debugging/confirmation purposes.
     */
    val message = Message(result.content, result.distance)

    return message.takeIf {
        currentTime <= lifeTime &&
            result.distance < maxDistance &&
            result.distance.isFinite() &&
            result.content.isNotEmpty()
    }
}

/**
 * Runs a multi-source proximity chat using simple gossip sharing.
 *
 * Each node shares its message if it's a source, and receives messages from other sources.
 * Messages have a lifetime equal to [lifeTime] and are shared within [maxDistance].
 * Returns a map from source name to the received [Message] with content and distance.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun Aggregate<Uuid>.chatMultipleSources(
    distances: Field<Uuid, Double>,
    isSource: Boolean,
    currentTime: Double,
    content: String = "echo from node $localId",
    lifeTime: Double = 100.0,
    maxDistance: Double = 3000.0,
): Map<Uuid, Message> {
    // Create local message if this node is a source and within time limits
    val localMessage = if (isSource && currentTime <= lifeTime) {
        Message(content, 0.0)
    } else {
        null
    }

    // Share messages using simple gossip protocol with actual distances
    val distanceMap = distances.toMap()
    val allMessages: Map<Uuid, Message> = share(
        if (localMessage != null) mapOf(localId to localMessage) else emptyMap(),
    ) { neighborMessages: Field<Uuid, Map<Uuid, Message>> ->
        val result = mutableMapOf<Uuid, Message>()

        // Add our own message if we have one
        localMessage?.let { result[localId] = it }

        // Collect messages from all neighbors
        for ((neighborId, neighborMsgs) in neighborMessages.toMap()) {
            val distanceToNeighbor = distanceMap.getOrElse(neighborId) { Double.MAX_VALUE }

            for ((sourceId, message) in neighborMsgs) {
                // Skip our own messages and check distance constraints
                if (sourceId != localId) {
                    // Calculate total distance: distance from source to neighbor + distance from neighbor to us
                    val totalDistance = message.distanceFromSource + distanceToNeighbor

                    // Only accept messages that are within the maximum distance
                    if (totalDistance < maxDistance) {
                        val updatedMessage = message.copy(
                            distanceFromSource = totalDistance,
                        )

                        // Keep the message with the shortest distance from each source
                        val existing = result[sourceId]
                        if (existing == null || updatedMessage.distanceFromSource < existing.distanceFromSource) {
                            result[sourceId] = updatedMessage
                        }
                    }
                }
            }
        }

        result
    }

    // Return all messages except our own (for consistency with original filtering)
    return allMessages.filterKeys { it != localId }
}
