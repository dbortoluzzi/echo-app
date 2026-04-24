package it.unibo.collektive.echo

import platform.Foundation.NSUserDefaults
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val DEVICE_ID_KEY = "stable_device_id"

/**
 * iOS actual implementation that stores the device id in NSUserDefaults.
 */
@OptIn(ExperimentalUuidApi::class)
actual fun loadOrCreateDeviceId(): Uuid {
    val defaults = NSUserDefaults.standardUserDefaults
    val existing = defaults.stringForKey(DEVICE_ID_KEY)
    if (existing != null) {
        return Uuid.parse(existing)
    }
    val created = Uuid.random()
    defaults.setObject(created.toString(), DEVICE_ID_KEY)
    return created
}
