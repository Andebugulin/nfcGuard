package com.andebugulin.nfcguard.data

import android.content.Context
import android.graphics.drawable.Drawable

/**
 * Name and icon of an installed app by package name, for showing users what
 * a mode blocks. An app uninstalled since it was picked falls back to its
 * package name and no icon rather than disappearing from the list.
 */
object InstalledApps {

    fun label(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    fun icon(context: Context, packageName: String): Drawable? =
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
}
