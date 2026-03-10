@file:Suppress("MatchingDeclarationName")

package it.unibo.collektive.echo

import android.os.Build

/** Android [Platform] implementation providing the device SDK version. */
class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

/** Returns the Android-specific [Platform] implementation. */
actual fun getPlatform(): Platform = AndroidPlatform()
