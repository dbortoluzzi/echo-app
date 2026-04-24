package it.unibo.collektive.echo

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Returns a stable device id persisted across app restarts.
 */
@OptIn(ExperimentalUuidApi::class)
expect fun loadOrCreateDeviceId(): Uuid
