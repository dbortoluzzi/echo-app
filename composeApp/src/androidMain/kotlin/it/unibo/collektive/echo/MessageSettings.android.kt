package it.unibo.collektive.echo

import android.content.Context

private const val TTL_KEY = "message_ttl_seconds"
private const val DISTANCE_KEY = "message_max_distance_meters"

actual fun loadMessageSettings(): MessageSettings {
    val appContext = AndroidContextHolder.requireAppContext()
    val prefs = appContext.getSharedPreferences(ECHO_PREFS_NAME, Context.MODE_PRIVATE)
    return MessageSettings(
        ttlSeconds = prefs.getFloat(TTL_KEY, DEFAULT_MAX_TIME.toFloat()).toDouble(),
        maxDistanceMeters = prefs.getFloat(DISTANCE_KEY, DEFAULT_MAX_DISTANCE.toFloat()).toDouble(),
    )
}

actual fun saveMessageSettings(settings: MessageSettings) {
    val appContext = AndroidContextHolder.requireAppContext()
    val prefs = appContext.getSharedPreferences(ECHO_PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
        .putFloat(TTL_KEY, settings.ttlSeconds.toFloat())
        .putFloat(DISTANCE_KEY, settings.maxDistanceMeters.toFloat())
        .apply()
}
