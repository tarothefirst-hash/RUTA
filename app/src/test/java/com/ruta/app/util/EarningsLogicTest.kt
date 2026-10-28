package com.ruta.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EarningsLogicTest {
    private val tz = TimeZone.getTimeZone("Asia/Manila")

    private fun at(y: Int, mo: Int, d: Int, h: Int): Long =
        Calendar.getInstance(tz).apply { clear(); set(y, mo - 1, d, h, 0, 0) }.timeInMillis

    @Test fun splitsPeriods() {
        val now = at(2026, 10, 28, 15) // Wednesday
        val list = listOf(
            EarningsLogic.Entry("a", 10.0, at(2026, 10, 28, 9)),   // today
            EarningsLogic.Entry("a", 20.0, at(2026, 10, 26, 9)),   // Monday: this week
            EarningsLogic.Entry("b", 5.0, at(2026, 10, 25, 23)),   // Sunday: this month only
            EarningsLogic.Entry("b", 100.0, at(2026, 9, 30, 12))   // last month
        )
        val s = EarningsLogic.summarize(list, now, tz)
        assertEquals(10.0, s.today, 0.001)
        assertEquals(30.0, s.week, 0.001)
        assertEquals(35.0, s.month, 0.001)
        assertEquals(135.0, s.allTime, 0.001)
        assertEquals(4, s.count)
        assertEquals("b", s.byDriver.first().first)
    }

    @Test fun ignoresZeroEntries() {
        val s = EarningsLogic.summarize(listOf(EarningsLogic.Entry("a", 0.0, 1L)), 10L, tz)
        assertEquals(0, s.count)
    }
}
