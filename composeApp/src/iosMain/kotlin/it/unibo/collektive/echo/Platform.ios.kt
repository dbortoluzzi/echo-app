@file:Suppress("MatchingDeclarationName")

package it.unibo.collektive.echo

import platform.UIKit.UIDevice

/** iOS [Platform] implementation providing the device OS name and version. */
class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

/** Returns the iOS-specific [Platform] implementation. */
actual fun getPlatform(): Platform = IOSPlatform()
