package it.unibo.collektive.echo.gossip

import it.unibo.collektive.aggregate.Field
import it.unibo.collektive.aggregate.api.Aggregate
import it.unibo.collektive.aggregate.api.share
import it.unibo.collektive.aggregate.toMap
import it.unibo.collektive.aggregate.values
import it.unibo.collektive.stdlib.collapse.fold
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Logical **source** identity for aggregate gossip: (origin device, message id). Matches aggregate-programming
 * usage of *source* as propagation origin; message id disambiguates several concurrent sends from the same device.
 * [Pair] is used so `share` and `alignedOn` pivots are serializable on the wire.
 */
@OptIn(ExperimentalUuidApi::class)
internal typealias GossipSource = Pair<Uuid, Uuid>

/**
 * Local outbound burst still within TTL, passed into [chatMultipleSources].
 */
@OptIn(ExperimentalUuidApi::class)
internal data class ActiveGossipSend(
    val messageId: Uuid,
    val content: String,
    val lifeTime: Double,
    val maxDistance: Double,
    val elapsedSeconds: Double,
)

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
    val distanceMap = distances.all.toMap()
    val result = share(localMessage) { neighborMessages: Field<Uuid, Message?> ->
        neighborMessages.neighbors.toMap().entries.fold(localMessage) { bestMessage, (neighborId, neighborMessage) ->
            if (neighborMessage == null || neighborMessage.content.isEmpty()) {
                bestMessage
            } else {
                val distanceToNeighbor = distanceMap.getOrElse(neighborId) { Double.MAX_VALUE }
                val totalDistance = neighborMessage.distanceFromSource + distanceToNeighbor
                // Accept if within the sender's maxDistance and this path improves distance-from-source
                if (totalDistance < neighborMessage.maxDistance) {
                    val updatedMessage = neighborMessage.copy(distanceFromSource = totalDistance)
                    if (bestMessage == null || updatedMessage.distanceFromSource < bestMessage.distanceFromSource) {
                        updatedMessage
                    } else {
                        bestMessage
                    }
                } else {
                    bestMessage
                }
            }
        }
    }

    // Return message for non-source nodes only
    return if (!localNode && result != null && result.content.isNotEmpty()) {
        result
    } else {
        null
    }
}

/**
 * Didactic pattern: multiple concurrent outbound broadcasts (one [GossipSource] per logical source).
 *
 * 1. Each device publishes its active source keys (`sourceId to messageId`) and unions them with neighbors (`share`).
 * 2. Same sort order on every node, then the same `alignedOn` sequence.
 * 3. For each source, `alignedOn(source)` — [GossipSource] is [Pair], a type Collektive supports on the wire.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun Aggregate<Uuid>.chatMultipleSources(
    distances: Field<Uuid, Double>,
    localSends: List<ActiveGossipSend>,
): Map<GossipSource, Message> {
    val localSources: Set<GossipSource> =
        localSends
            .filter { it.elapsedSeconds <= it.lifeTime }
            .map { localId to it.messageId }
            .toSet()

    val sources: Set<GossipSource> =
        share(localSources) { neighborSets: Field<Uuid, Set<GossipSource>> ->
            neighborSets.neighbors.values.fold(localSources) { accumulator, neighborSet ->
                accumulator + neighborSet
            }
        }

    val localSendByMessageId = localSends.associateBy { it.messageId }
    val messages = mutableMapOf<GossipSource, Message>()
    val orderedSources =
        sources.sortedWith(
            compareBy({ it.first.toString() }, { it.second.toString() }),
        )
    for (source in orderedSources) {
        val (sourceId, messageId) = source
        alignedOn(source) {
            val local =
                if (sourceId == localId) {
                    localSendByMessageId[messageId]
                } else {
                    null
                }
            val isSourceActive = local != null && local.elapsedSeconds <= local.lifeTime
            val result =
                gossipChat(
                    distances = distances,
                    sourceId = sourceId,
                    isSource = sourceId == localId && isSourceActive,
                    currentTime = local?.elapsedSeconds ?: 0.0,
                    content = local?.content ?: "",
                    messageId = messageId,
                    lifeTime = local?.lifeTime ?: 0.0,
                    maxDistance = local?.maxDistance ?: 0.0,
                )
            result?.let { messages[source] = it }
        }
    }

    return messages
}
