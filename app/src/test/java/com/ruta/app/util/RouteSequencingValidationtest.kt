package com.ruta.app.util

import com.google.android.gms.maps.model.LatLng
import org.junit.Test
import java.net.URL
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Table 39 validation (Route Sequencing).
 * For each set of two passengers:
 *   - APP sequence  = RideshareManager.Sequencer (the real app code) picks its order.
 *   - GOOGLE-optimal = the shortest ROAD distance, by Google Directions, among the same 6 valid orders.
 * Needs: (1) your Maps key below (Directions API enabled), (2) internet.
 * No extra Gradle dependency needed (the Directions answer is read with regex).
 * Run: right-click the class -> Run. Copy the printed table from the Run panel.
 * REMOVE THE KEY from this file afterwards.
 */
class RouteSequencingValidationTest {

    private val KEY = "AIzaSyD_CZcqO8veYRbJxyD9EPNBcmGK3IoeDMQ"
    private val area = ", Tarlac City, Philippines"

    private data class SetDef(val id: String, val driver: String, val p1: String, val d1: String, val p2: String, val d2: String)

    private val sets = listOf(
        SetDef("SEQ-01", "Tarlac City Hall", "Tarlac State University Main Campus", "SM City Tarlac", "Tarlac Provincial Capitol", "Robinsons Place Tarlac"),
        SetDef("SEQ-02", "SM City Tarlac", "St. Sebastian Cathedral", "Tarlac State University Main Campus", "Tarlac City Public Market", "Tarlac Provincial Hospital"),
        SetDef("SEQ-03", "Robinsons Place Tarlac", "Tarlac City Hall", "Matatalaib", "St. Sebastian Cathedral", "San Roque"),
        SetDef("SEQ-04", "Tarlac Provincial Capitol", "Tarlac State University Lucinda Campus", "Luisita", "Tarlac State University Main Campus", "SM City Tarlac"),
        SetDef("SEQ-05", "Tarlac City Public Market", "SM City Tarlac", "San Rafael", "Robinsons Place Tarlac", "Luisita"),
        SetDef("SEQ-06", "Tarlac State University Main Campus", "Tarlac Provincial Hospital", "St. Sebastian Cathedral", "Tarlac City Hall", "Tarlac City Public Market"),
        SetDef("SEQ-07", "Luisita", "Tarlac State University Lucinda Campus", "Robinsons Place Tarlac", "Tarlac State University Main Campus", "Tarlac Provincial Hospital"),
        SetDef("SEQ-08", "St. Sebastian Cathedral", "Matatalaib", "Tarlac State University Main Campus", "San Roque", "SM City Tarlac"),
        SetDef("SEQ-09", "SM City Tarlac", "San Rafael", "Tarlac City Hall", "Luisita", "Tarlac City Public Market"),
        SetDef("SEQ-10", "Robinsons Place Tarlac", "Tarlac Provincial Capitol", "San Roque", "Tarlac City Hall", "Matatalaib")
    )

    private fun hav(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private data class Leg(val meters: Int, val endAddress: String, val start: LatLng, val end: LatLng)

    private val legRegex = Regex(
        "\"distance\"\\s*:\\s*\\{[^}]*?\"value\"\\s*:\\s*(\\d+)\\s*\\}\\s*,\\s*\"duration\"\\s*:\\s*\\{[^}]*\\}\\s*,\\s*\"end_address\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"end_location\"\\s*:\\s*\\{\\s*\"lat\"\\s*:\\s*(-?[\\d.]+)\\s*,\\s*\"lng\"\\s*:\\s*(-?[\\d.]+)\\s*\\}\\s*,\\s*\"start_address\"\\s*:\\s*\"[^\"]*\"\\s*,\\s*\"start_location\"\\s*:\\s*\\{\\s*\"lat\"\\s*:\\s*(-?[\\d.]+)\\s*,\\s*\"lng\"\\s*:\\s*(-?[\\d.]+)"
    )

    /** Calls Google Directions and returns the legs of the first route. */
    private fun directions(origin: String, dest: String, waypoints: List<String>): List<Leg> {
        val wp = if (waypoints.isEmpty()) "" else "&waypoints=" + enc(waypoints.joinToString("|"))
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${enc(origin)}&destination=${enc(dest)}$wp&mode=driving&key=$KEY"
        Thread.sleep(150)
        val text = URL(url).readText()
        val status = Regex("\"status\"\\s*:\\s*\"([A-Z_]+)\"").find(text)?.groupValues?.get(1)
        require(status == "OK") { "Directions error: $status " + Regex("\"error_message\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) }
        // only the first route: cut before the second "legs" if there are alternatives
        val legs = legRegex.findAll(text).map {
            val g = it.groupValues
            Leg(g[1].toInt(), g[2], LatLng(g[5].toDouble(), g[6].toDouble()), LatLng(g[3].toDouble(), g[4].toDouble()))
        }.toList()
        require(legs.isNotEmpty()) { "Could not read legs from the Directions answer:\n" + text.take(600) }
        return legs
    }

    private fun s(p: LatLng) = "${p.latitude},${p.longitude}"

    @Test
    fun table39_routeSequencing() {
   
        val rows = mutableListOf<String>()
        var identical = 0
        var pctSum = 0.0

        for (set in sets) {
            // 1) resolve the five points through Google (also prints what Google understood)
            val first = directions(set.driver + area, set.d2 + area, listOf(set.p1 + area, set.d1 + area, set.p2 + area))
            val lg = first
            require(lg.size == 4) { "Expected 4 legs, got ${lg.size}" }
            val driver = lg[0].start
            val p1 = lg[0].end
            val d1 = lg[1].end
            val p2 = lg[2].end
            val d2 = lg[3].end
            println("[${set.id}] resolved: P1=${lg[0].endAddress} | D1=${lg[1].endAddress} | P2=${lg[2].endAddress} | D2=${lg[3].endAddress}")
            val pickupGap = hav(p1, p2); val dropGap = hav(d1, d2)
            println("[${set.id}] pickup gap=${pickupGap.toInt()} m, drop-off gap=${dropGap.toInt()} m" + if (pickupGap > 2000 || dropGap > 2000) "   <-- WARNING: gap > 2,000 m, replace this set" else "")

            // 2) the real app sequencer
            val a = RideshareManager.RideshareCandidate("A", "pA", p1, d1)
            val b = RideshareManager.RideshareCandidate("B", "pB", p2, d2)
            val appStops = RideshareManager.Sequencer.sequenceStopsWithDistance(driver, listOf(a, b)) { x, y -> hav(x, y) }
            val appSeq = appStops.joinToString("-") { (if (it.type == RideshareManager.StopType.PICKUP) "P" else "D") + (if (it.bookingId == "A") "1" else "2") }

            // 3) the six valid orders, road distance from Google
            val pts = mapOf("P1" to p1, "D1" to d1, "P2" to p2, "D2" to d2)
            val orders = listOf("P1-P2-D1-D2", "P1-P2-D2-D1", "P1-D1-P2-D2", "P2-P1-D2-D1", "P2-P1-D1-D2", "P2-D2-P1-D1")
            val road = orders.associateWith { o ->
                val st = o.split("-").map { pts.getValue(it) }
                val j = directions(s(driver), s(st[3]), listOf(s(st[0]), s(st[1]), s(st[2])))
                j.sumOf { it.meters }
            }
            val best = road.minByOrNull { it.value }!!
            val appRoad = road.getValue(appSeq)
            val extraKm = (appRoad - best.value) / 1000.0
            val pct = (appRoad - best.value) * 100.0 / best.value
            val same = appSeq == best.key
            if (same) identical++
            pctSum += pct
            println("[${set.id}] road m: " + road.entries.joinToString("  ") { "${it.key}=${it.value}" })
            rows.add("${set.id} | 2 P / 2 D | $appSeq | ${best.key} | ${if (same) "Yes" else "No"} | ${"%.2f".format(extraKm)} | ${"%.1f".format(pct)}%")
        }

        println("\n===== TABLE 39 (copy these rows) =====")
        println("Set | Stops (P/D) | App sequence | Google-optimal sequence | Identical? | Extra km | % extra")
        rows.forEach { println(it) }
        println("Summary | - | - | $identical of ${sets.size} identical | - | - | mean ${"%.1f".format(pctSum / sets.size)}%")
    }
}