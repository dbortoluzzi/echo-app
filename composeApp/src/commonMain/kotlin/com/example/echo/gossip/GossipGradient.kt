package com.example.echo.gossip

/**
 * Data structure representing a state in a gossip-based gradient algorithm.
 *
 * This structure is used to propagate and compute the [path] from the source
 * to all the nodes in a distributed system and a [content] message.
 * [localDistance] is the initial local distance of the nodes, and [distance]
 * represents the current estimated distance for the [path].
 */
internal data class GossipGradient<ID : Comparable<ID>>(
    val distance: Double,
    val localDistance: Double,
    val content: String,
    val path: List<ID> = emptyList(),
) {
    /**
     * Reset gossip to start from the local value of the specified node [id].
     */
    fun base(id: ID) = GossipGradient(localDistance, localDistance, content, listOf(id))

    /**
     * Add a new hop [id] to the path, update the distance with [newBest] and the
     * localDistance with [localDistance].
     */
    fun addHop(newBest: Double, localDistance: Double, id: ID) = GossipGradient(
        distance = newBest,
        localDistance = localDistance,
        content = content,
        path = path + id,
    )
}

/**
 * Represents a message with [content] propagating in space,
 * along with its [distanceFromSource].
 */
internal data class Message(val content: String, val distanceFromSource: Double)
