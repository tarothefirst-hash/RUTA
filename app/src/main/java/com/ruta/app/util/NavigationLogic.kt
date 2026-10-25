package com.ruta.app.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure turn-by-turn logic for the driver navigation screen (no Android types, so it is
 * unit-testable on the JVM). The Android layer feeds it Directions steps and GPS fixes;
 * it decides which maneuver is next, how far away it is, and what the voice should say.
 */
object NavigationLogic {

    /** One Directions API step: [maneuver] is the action taken at the START of the step. */
    data class Step(
        val startLat: Double, val startLng: Double,
        val endLat: Double, val endLng: Double,
        val distanceMeters: Int,
        val maneuver: String,
        val instructionHtml: String
    )

    /** A point on the road where the driver has to do something. */
    data class Maneuver(val lat: Double, val lng: Double, val type: String, val instruction: String)

    enum class Prompt { FAR, NEAR, NOW }

    data class Update(
        val index: Int,
        val maneuver: Maneuver?,
        val distanceMeters: Double,
        val prompt: Prompt?,
        val arrived: Boolean
    )

    const val FAR_METERS = 300.0
    const val NEAR_METERS = 100.0
    const val NOW_METERS = 45.0
    const val REACHED_METERS = 20.0

    /** Spoken once when guidance starts ("Head north on ..."), or null when unknown. */
    fun departText(steps: List<Step>): String? =
        steps.firstOrNull()?.let { stripHtml(it.instructionHtml) }?.takeIf { it.isNotBlank() }

    /**
     * Turns the step list into the sequence of maneuvers to announce. Step 0 is "head ..."
     * (handled by [departText]); every later step starts with a maneuver; the very end of
     * the last step is the arrival.
     */
    fun buildManeuvers(steps: List<Step>): List<Maneuver> {
        if (steps.isEmpty()) return emptyList()
        val list = mutableListOf<Maneuver>()
        for (i in 1 until steps.size) {
            val s = steps[i]
            list.add(Maneuver(s.startLat, s.startLng, s.maneuver, stripHtml(s.instructionHtml)))
        }
        val last = steps.last()
        list.add(Maneuver(last.endLat, last.endLng, "arrive", "You have arrived"))
        return list
    }

    fun stripHtml(html: String): String {
        var s = html
            .replace(Regex("<div[^>]*>", RegexOption.IGNORE_CASE), ". ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("\\s+\\."), ".").replace(Regex("\\.{2,}"), ".")
        return s
    }

    /** A big arrow glyph for the on-screen banner. */
    fun arrowFor(type: String): String = when {
        type.startsWith("uturn") -> "⮌"
        type.contains("sharp-left") -> "⬉"
        type.contains("sharp-right") -> "⬈"
        type.contains("slight-left") || type == "keep-left" || type == "fork-left" || type == "ramp-left" -> "↖"
        type.contains("slight-right") || type == "keep-right" || type == "fork-right" || type == "ramp-right" -> "↗"
        type.contains("left") -> "←"
        type.contains("right") -> "→"
        type.startsWith("roundabout") -> "⟳"
        type == "arrive" -> "⚑"
        else -> "↑"
    }

    fun formatDistance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return if (m >= 1000.0) {
            val km = (m / 100.0).roundToInt() / 10.0
            "$km km"
        } else {
            val rounded = if (m >= 100.0) ((m / 10.0).roundToInt() * 10) else ((m / 5.0).roundToInt() * 5)
            "$rounded m"
        }
    }

    /** "300 meters", "50 meters", "1.2 kilometers" - rounded the way a person would say it. */
    fun spokenDistance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return if (m >= 1000.0) {
            val km = (m / 100.0).roundToInt() / 10.0
            if (km == 1.0) "1 kilometer" else "$km kilometers"
        } else {
            val step = if (m >= 100.0) 50 else 10
            val rounded = ((m / step).roundToInt() * step).coerceAtLeast(10)
            "$rounded meters"
        }
    }

    fun fallbackInstruction(type: String): String = when {
        type.startsWith("uturn") -> "Make a U-turn"
        type.contains("left") && type.contains("sharp") -> "Take a sharp left"
        type.contains("right") && type.contains("sharp") -> "Take a sharp right"
        type.contains("slight-left") || type == "keep-left" -> "Keep left"
        type.contains("slight-right") || type == "keep-right" -> "Keep right"
        type.contains("left") -> "Turn left"
        type.contains("right") -> "Turn right"
        type.startsWith("roundabout") -> "Enter the roundabout"
        type == "merge" -> "Merge"
        type == "arrive" -> "You have arrived"
        else -> "Continue straight"
    }

    /** What the voice says for [maneuver] at the given [prompt] stage. */
    fun phraseFor(maneuver: Maneuver, distanceMeters: Double, prompt: Prompt): String {
        val text = maneuver.instruction.ifBlank { fallbackInstruction(maneuver.type) }
        if (maneuver.type == "arrive") {
            return when (prompt) {
                Prompt.NOW -> "You have arrived at your stop"
                else -> "Your stop is in ${spokenDistance(distanceMeters)}"
            }
        }
        return when (prompt) {
            Prompt.NOW -> text
            else -> "In ${spokenDistance(distanceMeters)}, ${text.replaceFirstChar { it.lowercase() }}"
        }
    }

    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Follows the driver along [maneuvers]. Each prompt stage (300 m / 100 m / now) fires
     * at most once per maneuver, and when the first fix already sits inside a stage only
     * the tightest one fires (no "in 300 m" right before "now").
     */
    class Tracker(private val maneuvers: List<Maneuver>) {
        private var index = 0
        private var lastPrompt: Prompt? = null

        val currentIndex: Int get() = index
        val isFinished: Boolean get() = index >= maneuvers.size

        fun update(lat: Double, lng: Double): Update {
            // Skip any maneuver the driver has already reached.
            while (index < maneuvers.size) {
                val m = maneuvers[index]
                if (haversine(lat, lng, m.lat, m.lng) <= REACHED_METERS) {
                    val wasLast = index == maneuvers.size - 1
                    index++
                    lastPrompt = null
                    if (wasLast) return Update(index, m, 0.0, null, arrived = true)
                } else break
            }
            val m = maneuvers.getOrNull(index) ?: return Update(index, null, 0.0, null, true)
            val d = haversine(lat, lng, m.lat, m.lng)
            val stage = when {
                d <= NOW_METERS -> Prompt.NOW
                d <= NEAR_METERS -> Prompt.NEAR
                d <= FAR_METERS -> Prompt.FAR
                else -> null
            }
            val fire = if (stage != null && (lastPrompt == null || stage.ordinal > lastPrompt!!.ordinal)) stage else null
            if (fire != null) lastPrompt = fire
            return Update(index, m, d, fire, arrived = false)
        }
    }
}
