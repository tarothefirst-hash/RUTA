package com.ruta.app.util

import com.ruta.app.util.NotificationLogic.Item
import org.junit.Assert.*
import org.junit.Test

class NotificationLogicTest {
    private fun item(ts: Long, read: Boolean = false) = Item("i", "t", "T", "B", ts, read)

    @Test fun unreadCounts() =
        assertEquals(2, NotificationLogic.unreadCount(listOf(item(1), item(2), item(3, true))))

    @Test fun popsOnlyNewUnreadWhileAppIsClosed() {
        assertTrue(NotificationLogic.shouldPop(item(100), lastShownAt = 50, appInForeground = false))
        assertFalse(NotificationLogic.shouldPop(item(40), 50, false))      // already shown earlier
        assertFalse(NotificationLogic.shouldPop(item(100, true), 50, false)) // already read
        assertFalse(NotificationLogic.shouldPop(item(100), 50, true))      // app is open
    }

    @Test fun relativeTimes() {
        val now = 10_000_000L
        assertEquals("Just now", NotificationLogic.relativeTime(now - 20_000, now))
        assertEquals("5 min ago", NotificationLogic.relativeTime(now - 5 * 60_000, now))
        assertEquals("3 h ago", NotificationLogic.relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2 d ago", NotificationLogic.relativeTime(now - 2 * 86_400_000L, now))
    }

    @Test fun bookingEventKeyIsStable() =
        assertEquals("bk_abc_ACCEPTED", NotificationLogic.bookingEventKey("abc", "ACCEPTED"))

    @Test fun onlyMeaningfulStatusesNotify() {
        assertNotNull(NotificationLogic.bookingEventText("ACCEPTED"))
        assertNotNull(NotificationLogic.bookingEventText("COMPLETED"))
        assertNull(NotificationLogic.bookingEventText("MATCHING"))
        assertNull(NotificationLogic.bookingEventText("REQUESTED"))
    }
}
