package it.unibo.collektive.echo.network.mqtt

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.posix.AF_INET
import platform.posix.INET_ADDRSTRLEN
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.inet_ntop
import platform.posix.sockaddr_in

internal actual fun resolveIpv4Candidates(host: String): List<String> = memScoped {
    val hints = alloc<addrinfo>().apply {
        ai_family = AF_INET
        ai_socktype = 0
        ai_protocol = 0
        ai_flags = 0
        ai_addrlen = 0u
        ai_addr = null
        ai_canonname = null
        ai_next = null
    }

    val resultHolder = alloc<CPointer<addrinfo>?>()
    val status = getaddrinfo(host, null, hints.ptr, resultHolder.ptr)
    if (status != 0) {
        return@memScoped emptyList()
    }

    val ipv4Addresses = mutableListOf<String>()
    var current = resultHolder.value
    while (current != null) {
        val info = current.pointed
        if (info.ai_family == AF_INET && info.ai_addr != null) {
            val sockaddr = info.ai_addr!!.reinterpret<sockaddr_in>().pointed
            val buffer = alloc<ByteVar>(INET_ADDRSTRLEN)
            val converted = inet_ntop(
                AF_INET,
                sockaddr.sin_addr.ptr,
                buffer.ptr,
                INET_ADDRSTRLEN.toUInt(),
            )
            if (converted != null) {
                ipv4Addresses += buffer.toKString()
            }
        }
        current = info.ai_next
    }

    freeaddrinfo(resultHolder.value)
    ipv4Addresses.distinct()
}
