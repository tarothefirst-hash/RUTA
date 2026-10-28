package com.ruta.app.util

import java.util.Calendar
import java.util.TimeZone

/** Adds up the commission RUTA kept from drivers (`commissions/{id}` entries). */
object EarningsLogic {

    data class Entry(val driverId: String, val commission: Double, val timestamp: Long)

    data class Summary(
        val today: Double, val week: Double, val month: Double, val allTime: Double,
        val count: Int, val byDriver: List<Pair<String, Double>>
    )

    /** Start of the calendar day / week (Monday) / month containing [now] in [tz]. */
    fun periodStarts(now: Long, tz: TimeZone): Triple<Long, Long, Long> {
        fun cal() = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val day = cal().timeInMillis
        val week = cal().apply {
            val back = (get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
            add(Calendar.DAY_OF_MONTH, -back)
        }.timeInMillis
        val month = cal().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        return Triple(day, week, month)
    }

    fun summarize(entries: List<Entry>, now: Long, tz: TimeZone = TimeZone.getDefault()): Summary {
        val (day, week, month) = periodStarts(now, tz)
        var t = 0.0; var w = 0.0; var m = 0.0; var all = 0.0
        val per = HashMap<String, Double>()
        for (e in entries) {
            if (e.commission <= 0.0) continue
            all += e.commission
            if (e.timestamp >= day) t += e.commission
            if (e.timestamp >= week) w += e.commission
            if (e.timestamp >= month) m += e.commission
            per[e.driverId] = (per[e.driverId] ?: 0.0) + e.commission
        }
        return Summary(t, w, m, all, entries.count { it.commission > 0.0 },
            per.entries.sortedByDescending { it.value }.map { it.key to it.value })
    }
}
