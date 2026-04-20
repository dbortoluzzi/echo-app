package it.unibo.collektive.echo.network.mqtt

import java.net.Inet4Address
import java.net.InetAddress

internal actual fun resolveIpv4Candidates(host: String): List<String> = runCatching {
    InetAddress.getAllByName(host)
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .distinct()
}.getOrElse { emptyList() }
