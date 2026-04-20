package it.unibo.collektive.echo.network.mqtt

/**
 * Resolves all IPv4 addresses for [host] on the current platform.
 *
 * Returns an empty list when resolution fails or when the platform has no IPv4 result.
 */
internal expect fun resolveIpv4Candidates(host: String): List<String>
