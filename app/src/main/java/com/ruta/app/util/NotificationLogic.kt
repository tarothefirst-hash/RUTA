package com.ruta.app.util

/** Pure rules for the in-app notification list and the closed-app pop-ups. */
object NotificationLogic {

    data class Item(
        val id: String,
        val type: String,
        val title: String,
        val body: String,
        val timestamp: Long,
        val read: Boolean
    )

    fun unreadCount(items: Collection<Item>): Int = items.count { !it.read }

    /**
     * Pop a system notification only for items the user has not read, that are newer than
     * the last one already shown (so a service restart never replays old ones), and not
     * while the app is open on screen (the bell badge already tells them).
     */
    fun shouldPop(item: Item, lastShownAt: Long, appInForeground: Boolean): Boolean =
        !item.read && item.timestamp > lastShownAt && !appInForeground

    fun relativeTime(timestamp: Long, now: Long): String {
        val diff = (now - timestamp).coerceAtLeast(0L)
        val min = diff / 60_000L
        return when {
            min < 1 -> "Just now"
            min < 60 -> "$min min ago"
            min < 60 * 24 -> "${min / 60} h ago"
            min < 60 * 24 * 7 -> "${min / (60 * 24)} d ago"
            else -> "Over a week ago"
        }
    }

    /** Stable key so the same booking event can never create two notifications. */
    fun bookingEventKey(bookingId: String, status: String): String = "bk_${bookingId}_$status"

    /** Title/body for a booking status change, or null when it deserves no notification. */
    fun bookingEventText(status: String): Pair<String, String>? = when (status) {
        "ACCEPTED" -> "Driver on the way" to "A driver accepted your ride and is heading to your pickup."
        "ARRIVED" -> "Your driver has arrived" to "Meet your driver at the pickup point."
        "IN_PROGRESS" -> "Trip started" to "Enjoy your ride. You can follow it live in the app."
        "COMPLETED" -> "Trip completed" to "Thanks for riding with RUTA."
        "CANCELLED" -> "Ride cancelled" to "Your ride was cancelled."
        else -> null
    }
}
