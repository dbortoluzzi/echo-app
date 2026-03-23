package it.unibo.collektive.echo.network.mqtt

import java.net.Inet4Address
import java.net.InetAddress

internal actual fun resolveIpv4Candidates(host: String): List<String> = try {
    InetAddress.getAllByName(host)
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress }
        .distinct()
} catch (
    @Suppress("TooGenericExceptionCaught")
    _: Exception,
) {
    emptyList()
}
