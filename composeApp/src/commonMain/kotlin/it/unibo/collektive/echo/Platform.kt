package it.unibo.collektive.echo

/** Abstraction over the host platform, providing the platform [name]. */
interface Platform {
    /** Human-readable name of the current platform (e.g. "Android 14"). */
    val name: String
}

/** Returns the platform-specific [Platform] implementation. */
expect fun getPlatform(): Platform
