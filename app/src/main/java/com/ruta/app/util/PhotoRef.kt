package com.ruta.app.util

/**
 * How a photo is referenced in the database. New photos live in Firebase Storage and are
 * stored as "storage:<path>"; photos saved before the switch are inline Base64 (optionally
 * a "data:image/...;base64," URL) and keep working. Pure, so it is unit tested.
 */
sealed class PhotoRef {
    data class Storage(val path: String) : PhotoRef()
    data class Inline(val base64: String) : PhotoRef()
    object None : PhotoRef()

    companion object {
        const val PREFIX = "storage:"

        fun parse(value: String?): PhotoRef {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return None
            if (v.startsWith(PREFIX)) {
                val path = v.removePrefix(PREFIX).trim('/')
                return if (path.isEmpty()) None else Storage(path)
            }
            val b64 = if (v.contains("base64,")) v.substringAfter("base64,") else v
            return if (b64.isBlank()) None else Inline(b64)
        }

        fun toValue(path: String): String = PREFIX + path.trim('/')
    }
}

/** Every Storage path in one place, matching the Storage security rules. */
object PhotoPaths {
    /** Readable by any signed-in user (shown in rides); only the owner may write. */
    fun avatar(uid: String) = "profiles/$uid/avatar.jpg"

    /** Passenger discount ID; owner + admin only. [side] is "front" or "back". */
    fun discountId(uid: String, side: String) = "ids/$uid/$side.jpg"

    /** Driver registration documents; owner + admin only. [key] is license, registration or nbi. */
    fun driverDoc(uid: String, key: String) = "driver_docs/$uid/$key.jpg"

    /** Driver wallet payment proof; owner + admin only. */
    fun receipt(uid: String, requestId: String) = "receipts/$uid/$requestId.jpg"
}
