package com.lucamarchi.echo

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
