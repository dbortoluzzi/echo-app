package it.unibo.collektive.echo.network.mqtt

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo

@OptIn(ExperimentalForeignApi::class)
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

    val result = allocPointerTo<addrinfo>()
    val status = getaddrinfo(host, null, hints.ptr, result.ptr)
    if (status != 0) {
        return@memScoped emptyList()
    }

    try {
        buildList {
            var current = result.value
            while (current != null) {
                val info = current.pointed
                val addr = info.ai_addr
                if (info.ai_family == AF_INET && addr != null) {
                    val hostBuffer = allocArray<ByteVar>(NI_MAXHOST)
                    val callReturnStatus = getnameinfo(
                        addr,
                        info.ai_addrlen,
                        hostBuffer,
                        NI_MAXHOST.toUInt(),
                        null,
                        0u,
                        NI_NUMERICHOST,
                    )
                    if (callReturnStatus == 0) {
                        add(hostBuffer.toKString())
                    }
                }
                current = info.ai_next
            }
        }.distinct()
    } finally {
        result.value?.let(::freeaddrinfo)
    }
}
