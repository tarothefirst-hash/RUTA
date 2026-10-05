package com.ruta.app.util

import android.content.Context
import android.content.pm.PackageManager

/**
 * Single source of truth for the Google Maps / Places API key.
 *
 * The key is injected at build time by the secrets-gradle-plugin into the
 * AndroidManifest placeholder [META_DATA_NAME]. Reading it back from the
 * merged manifest means there is exactly one place the key lives, and no
 * literal copy in Kotlin or in res/values/strings.xml.
 */
object MapsApi {

    private const val META_DATA_NAME = "com.google.android.geo.API_KEY"

    fun key(context: Context): String {
        return try {
            val info = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA
            )
            info.metaData?.getString(META_DATA_NAME).orEmpty()
        } catch (e: Exception) {
            ""
        }
    }
}
