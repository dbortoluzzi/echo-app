package it.unibo.collektive.echo

import platform.Foundation.NSUserDefaults

private const val TTL_KEY = "message_ttl_seconds"
private const val DISTANCE_KEY = "message_max_distance_meters"

actual fun loadMessageSettings(): MessageSettings {
    val defaults = NSUserDefaults.standardUserDefaults
    val hasTtl = defaults.objectForKey(TTL_KEY) != null
    val hasDistance = defaults.objectForKey(DISTANCE_KEY) != null
    return MessageSettings(
        ttlSeconds = if (hasTtl) defaults.doubleForKey(TTL_KEY) else DEFAULT_MAX_TIME,
        maxDistanceMeters = if (hasDistance) defaults.doubleForKey(DISTANCE_KEY) else DEFAULT_MAX_DISTANCE,
    )
}

actual fun saveMessageSettings(settings: MessageSettings) {
    val defaults = NSUserDefaults.standardUserDefaults
    defaults.setDouble(settings.ttlSeconds, TTL_KEY)
    defaults.setDouble(settings.maxDistanceMeters, DISTANCE_KEY)
}
