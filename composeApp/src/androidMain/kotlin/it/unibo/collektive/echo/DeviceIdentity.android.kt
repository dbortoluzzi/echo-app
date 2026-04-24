package it.unibo.collektive.echo

import android.content.Context
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val ECHO_PREFS_NAME = "echo_app_prefs"
private const val DEVICE_ID_KEY = "stable_device_id"

internal object AndroidContextHolder {
    lateinit var appContext: Context

    fun requireAppContext(): Context {
        check(::appContext.isInitialized) {
            "Android context not initialized"
        }
        return appContext
    }
}

/**
 * Android actual implementation that stores the device id in SharedPreferences.
 */
@OptIn(ExperimentalUuidApi::class)
actual fun loadOrCreateDeviceId(): Uuid {
    val appContext = AndroidContextHolder.requireAppContext()
    val prefs = appContext.getSharedPreferences(ECHO_PREFS_NAME, Context.MODE_PRIVATE)
    val existing = prefs.getString(DEVICE_ID_KEY, null)
    if (existing != null) {
        return Uuid.parse(existing)
    }
    val created = Uuid.random()
    prefs.edit().putString(DEVICE_ID_KEY, created.toString()).apply()
    return created
}
