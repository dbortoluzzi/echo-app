package com.example.echo.gossip

import it.unibo.collektive.aggregate.Field
import it.unibo.collektive.aggregate.api.Aggregate
import it.unibo.collektive.aggregate.api.share
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
    val localDistance = if (isTargetNode) 0.0 else Double.POSITIVE_INFINITY
    val localGossip = GossipGradient(
        distance = localDistance,
        localDistance = localDistance,
        content = localContent,
        path = listOf(localId),
    )

    val distanceMap: Map<Uuid, Double> = distances.toMap()
    val result = share(localGossip) { neighborsGossip: Field<Uuid, GossipGradient<Uuid>> ->
        var bestGossip = localGossip
        val neighbors = neighborsGossip.toMap().keys

        for ((neighborId, neighborGossip) in neighborsGossip.toMap()) {
            val recentPath = neighborGossip.path.asReversed().drop(1)
            val pathIsValid = recentPath.none { it == localId || it in neighbors }
            val nextGossip = if (pathIsValid) neighborGossip else neighborGossip.base(neighborId)
            val totalDistance: Double = nextGossip.distance + distanceMap.getOrElse(neighborId) { nextGossip.distance }
            if (totalDistance < bestGossip.distance && neighborGossip.content.isNotEmpty()) {
                bestGossip = nextGossip.addHop(totalDistance, localGossip.localDistance, localId)
            }
        }
        bestGossip
    }

    /*
    Filter the message before sharing it.
     */
    val message = Message(result.content, result.distance)

    return message.takeIf {
        currentTime <= lifeTime &&
            result.distance < maxDistance &&
            result.distance.isFinite() &&
            result.content.isNotEmpty() &&
            localId != target
    }
}

/**
 * Runs a multi-source proximity chat using [gossipGradient].
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
    lifeTime: Double = 100.0,
    maxDistance: Double = 3000.0,
): Map<Uuid, Message> {
    /*
    Gossip‐share self‐stabilizing of the sources.
     */
    val localSources: Set<Uuid> = if (isSource) setOf(localId) else emptySet()
    val sources: Set<Uuid> = share(localSources) { neighborSets: Field<Uuid, Set<Uuid>> ->
        neighborSets.neighborsValues.fold(localSources) { accumulator, neighborSet ->
            accumulator + neighborSet
        }
    }
    /*
    Compute [gossipGradient] for each source.
     */
    val messages = mutableMapOf<Uuid, Message>()
    for (sourceId in sources) {
        alignedOn(sourceId) {
            val result = gossipGradient(
                distances = distances,
                target = sourceId,
                isSource = localId == sourceId && isSource,
                currentTime = currentTime,
                content = content,
                lifeTime = lifeTime,
                maxDistance = maxDistance,
            )

            result?.let { messages[sourceId] = result }
        }
    }

    return messages
}
