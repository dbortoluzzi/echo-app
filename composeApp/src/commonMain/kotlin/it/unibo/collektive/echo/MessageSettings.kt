package it.unibo.collektive.echo

data class MessageSettings(
    val ttlSeconds: Double = DEFAULT_MAX_TIME,
    val maxDistanceMeters: Double = DEFAULT_MAX_DISTANCE,
)

expect fun loadMessageSettings(): MessageSettings

expect fun saveMessageSettings(settings: MessageSettings)
