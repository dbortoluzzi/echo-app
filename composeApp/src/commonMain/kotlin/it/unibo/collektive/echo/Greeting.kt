package it.unibo.collektive.echo

/** Simple greeting helper that includes the current platform name. */
class Greeting {
    private val platform = getPlatform()

    /** Returns a greeting string containing the platform name. */
    fun greet(): String = "Hello, ${platform.name}!"
}
