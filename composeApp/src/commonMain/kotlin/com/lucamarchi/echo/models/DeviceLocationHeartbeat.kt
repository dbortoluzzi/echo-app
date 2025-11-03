package com.lucamarchi.echo.models

import com.lucamarchi.echo.location.Location
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * Data class for sharing device location and heartbeat information via MQTT.
 * Includes the device's unique ID [deviceId], current [location], and a [timestamp] of the last update.
 */
@Serializable
@OptIn(ExperimentalUuidApi::class)
data class DeviceLocationHeartbeat(
    val deviceId: String, // Using String for serialization compatibility
    val location: DeviceLocation?,
    val timestamp: Long,
)

/**
 * Serializable location data for network transmission.
 * Includes [latitude], [longitude], optional [accuracy], and a [timestamp].
 */
@Serializable
data class DeviceLocation(val latitude: Double, val longitude: Double, val accuracy: Float?, val timestamp: Long) {
    fun toLocation(): Location = Location(latitude, longitude, accuracy, timestamp)

    companion object {
        fun fromLocation(location: Location): DeviceLocation = DeviceLocation(
            location.latitude,
            location.longitude,
            location.accuracy,
            location.timestamp,
        )
    }
}
