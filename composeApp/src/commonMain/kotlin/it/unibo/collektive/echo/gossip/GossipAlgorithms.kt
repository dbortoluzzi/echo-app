package it.unibo.collektive.echo.gossip

import it.unibo.collektive.aggregate.Field
import it.unibo.collektive.aggregate.api.Aggregate
import it.unibo.collektive.aggregate.api.share
import it.unibo.collektive.aggregate.values
import it.unibo.collektive.echo.DEFAULT_MAX_DISTANCE
import it.unibo.collektive.echo.DEFAULT_MAX_TIME
import it.unibo.collektive.stdlib.collapse.fold
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal typealias MessageWithDistance = Pair<Message?, Double>
/**
 * Computes a proximity-based gossip chat message propagation.
 * The algorithm is self-stabilizing and uses distance-based propagation limits.
 *
 * Distances between nodes are calculated using the provided [distances] field.
 * Nodes marked as [isSource] and with an Id [sourceId] initiate the message with [content].
 * Messages [messageId] propagate to neighbors based on distance, up to [maxDistance].
 * Time is tracked with [currentTime] and messages have a lifetime of [lifeTime].
 *
 */
@OptIn(ExperimentalUuidApi::class)
internal fun Aggregate<Uuid>.gossipChat(
    distances: Field<Uuid, Double>,
    sourceId: Uuid,
    isSource: Boolean,
    currentTime: Double,
    content: String,
    messageId: Uuid,
    lifeTime: Double,
    maxDistance: Double,
): Message? {
    // Simple broadcast from source using gradient-like propagation
    val localNode = localId == sourceId
    // Create initial message state
    val localMessage = if (localNode && isSource && currentTime <= lifeTime) {
        Message(content, 0.0, messageId, maxDistance)
    } else {
        null
    }

    // Share messages with neighbors
    val result = share(localMessage) { neighborMessages: Field<Uuid, Message?> ->
        neighborMessages.alignedMap(distances) { _: Uuid, message: Message?, distance: Double ->
            message to distance
        }.neighbors.values.fold(localMessage to 0.0) { bestMessage: MessageWithDistance, fromNeighbor: MessageWithDistance ->
            val (neighborMessage, distanceToNeighbor) = fromNeighbor
            if (neighborMessage == null || neighborMessage.content.isEmpty()) {
                bestMessage
            } else {
                val (bestMessagePayload, bestMessageDistance) = bestMessage
                val totalDistance = neighborMessage.distanceFromSource + distanceToNeighbor
                // Accept message if we don't have one or if this path is better
                // Use the sender's maxDistance to limit propagation
                if (totalDistance < neighborMessage.maxDistance) {
                    val updatedMessage = neighborMessage.copy(distanceFromSource = totalDistance) to distanceToNeighbor
                    if (bestMessagePayload == null || updatedMessage.first.distanceFromSource < bestMessagePayload.distanceFromSource) {
                        updatedMessage
                    } else {
                        bestMessage
                    }
                } else {
                    bestMessage
                }
            }
        }.first
    }

    // Return message for non-source nodes only
    return if (!localNode && result != null && result.content.isNotEmpty()) {
        result
    } else {
        null
    }
}

/**
 * Runs a multi-source proximity chat using [gossipChat].
 *
 * Each node computes its distance to all sources, identified with [isSource].
 * Nodes within [maxDistance] hear the [content],
 * and nodes beyond [maxDistance] are excluded.
 * The messages have a lifetime equal to [lifeTime].
 * Returns a map from source name to the received [Message] with content and distance.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun Aggregate<Uuid>.chatMultipleSources(
    distances: Field<Uuid, Double>,
    isSource: Boolean,
    currentTime: Double,
    content: String = "echo from node $localId",
    messageId: Uuid,
    lifeTime: Double = DEFAULT_MAX_TIME,
    maxDistance: Double = DEFAULT_MAX_DISTANCE,
): Map<Uuid, Message> {
    /*
    Gossip‐share self‐stabilizing of the sources.
     */
    val localSources: Set<Uuid> = if (isSource) setOf(localId) else emptySet()
    val sources: Set<Uuid> = share(localSources) { neighborSets: Field<Uuid, Set<Uuid>> ->
        neighborSets.neighbors.values.fold(localSources) { accumulator, neighborSet ->
            accumulator + neighborSet
        }
    }

    /*
    Compute gossip algorithm for each source.
     */
    val messages = mutableMapOf<Uuid, Message>()
    for (sourceId in sources) {
        alignedOn(sourceId) {
            val result = gossipChat(
                distances = distances,
                sourceId = sourceId,
                isSource = localId == sourceId && isSource,
                currentTime = currentTime,
                content = content,
                messageId = messageId,
                lifeTime = lifeTime,
                maxDistance = maxDistance,
            )

            result?.let { messages[sourceId] = result }
        }
    }

    return messages
}
