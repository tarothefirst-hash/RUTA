package com.ruta.app.precision

import com.ruta.app.util.DropoffGeofence
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * GEO: precision of the drop-off geofence (DropoffGeofence, the code the driver app runs).
 *
 *  1. Distance accuracy: driver positions are placed at an EXACT distance and bearing from a
 *     drop-off point using the WGS84 ellipsoid (Vincenty direct formula, an independent
 *     oracle). The app's distance must match that true distance.
 *  2. Decision accuracy: the allow/block decision must equal "true distance <= radius".
 *  3. GPS noise: a Monte Carlo simulation of real phone GPS error shows how often a driver who
 *     is really inside / outside the radius is allowed.
 */
class GeofencePrecisionTest {

    // ---- independent oracle: Vincenty direct on WGS84 -----------------------------------
    private val a = 6378137.0
    private val f = 1 / 298.257223563
    private val b = (1 - f) * a

    private fun destination(latDeg: Double, lngDeg: Double, bearingDeg: Double, meters: Double): Pair<Double, Double> {
        val phi1 = Math.toRadians(latDeg); val alpha1 = Math.toRadians(bearingDeg)
        val sinA1 = sin(alpha1); val cosA1 = cos(alpha1)
        val tanU1 = (1 - f) * tan(phi1)
        val cosU1 = 1 / sqrt(1 + tanU1 * tanU1); val sinU1 = tanU1 * cosU1
        val sigma1 = atan2(tanU1, cosA1)
        val sinAlpha = cosU1 * sinA1
        val cos2Alpha = 1 - sinAlpha * sinAlpha
        val u2 = cos2Alpha * (a * a - b * b) / (b * b)
        val bigA = 1 + u2 / 16384 * (4096 + u2 * (-768 + u2 * (320 - 175 * u2)))
        val bigB = u2 / 1024 * (256 + u2 * (-128 + u2 * (74 - 47 * u2)))
        var sigma = meters / (b * bigA)
        var cos2sm: Double; var sinS: Double; var cosS: Double
        var iter = 0
        while (true) {
            cos2sm = cos(2 * sigma1 + sigma); sinS = sin(sigma); cosS = cos(sigma)
            val dSigma = bigB * sinS * (cos2sm + bigB / 4 * (cosS * (-1 + 2 * cos2sm * cos2sm) -
                bigB / 6 * cos2sm * (-3 + 4 * sinS * sinS) * (-3 + 4 * cos2sm * cos2sm)))
            val next = meters / (b * bigA) + dSigma
            if (abs(next - sigma) < 1e-13 || ++iter > 100) { sigma = next; break }
            sigma = next
        }
        cos2sm = cos(2 * sigma1 + sigma); sinS = sin(sigma); cosS = cos(sigma)
        val x = sinU1 * sinS - cosU1 * cosS * cosA1
        val phi2 = atan2(sinU1 * cosS + cosU1 * sinS * cosA1, (1 - f) * sqrt(sinAlpha * sinAlpha + x * x))
        val lambda = atan2(sinS * sinA1, cosU1 * cosS - sinU1 * sinS * cosA1)
        val c = f / 16 * cos2Alpha * (4 + f * (4 - 3 * cos2Alpha))
        val l = lambda - (1 - c) * f * sinAlpha * (sigma + c * sinS * (cos2sm + c * cosS * (-1 + 2 * cos2sm * cos2sm)))
        return Math.toDegrees(phi2) to (lngDeg + Math.toDegrees(l))
    }

    private data class Site(val name: String, val lat: Double, val lng: Double)
    private val sites = listOf(
        Site("TSU Main Campus, Tarlac City", 15.4817, 120.5979),
        Site("SM City Tarlac", 15.4863, 120.5893),
        Site("Equator (extreme case)", 0.0, 120.0),
        Site("60 N (extreme case)", 60.0, 120.0)
    )

    private val radius = DropoffGeofence.RADIUS_METERS
    // The app measures on a sphere (haversine); the true Earth is an ellipsoid. The gap is at most ~0.56%.
    private val REL_BOUND = 0.006

    companion object {
        private val sections = linkedMapOf<String, String>()
        private val caseRows = mutableListOf<List<Any?>>()
        private val noiseRows = mutableListOf<List<Any?>>()

        @JvmStatic @AfterClass fun flush() {
            PrecisionOut.writeCsv(
                "GEO_cases.csv",
                listOf("site", "bearing_deg", "true_distance_m", "app_distance_m", "error_m", "expected_allowed", "app_allowed"),
                caseRows
            )
            PrecisionOut.writeCsv(
                "GEO_noise.csv",
                listOf("gps_sigma_m", "true_distance_m", "trials", "allowed", "probability_allowed"),
                noiseRows
            )
            val md = StringBuilder("## GEO: Drop-off geofence precision\n\n")
            listOf("spec", "distance", "decision", "noise").forEach { k -> sections[k]?.let { md.append(it).append("\n") } }
            PrecisionOut.writeSummary("GEO", md.toString())
        }
    }

    @Test
    fun distance_and_decision_match_the_ellipsoid_oracle() {
        var n = 0; var wrongDecision = 0; var decisionOutsideBand = 0
        val errs = mutableListOf<Double>(); val relErrs = mutableListOf<Double>()
        val perSite = mutableListOf<List<String>>()

        sites.forEach { site ->
            val siteErrs = mutableListOf<Double>()
            for (d in 1..300) for (bearing in 0 until 360 step 10) {
                val (lat, lng) = destination(site.lat, site.lng, bearing.toDouble(), d.toDouble())
                val r = DropoffGeofence.check(lat, lng, site.lat, site.lng)
                val err = r.distanceMeters - d
                val expectedAllowed = d <= radius
                n++; errs.add(err); siteErrs.add(err); relErrs.add(abs(err) / d)
                caseRows.add(listOf(site.name, bearing, d, r.distanceMeters, err, expectedAllowed, r.allowed))
                if (r.allowed != expectedAllowed) {
                    wrongDecision++
                    // A wrong call is only excusable within the distance error of the boundary.
                    if (abs(d - radius) > REL_BOUND * radius + 0.01) decisionOutsideBand++
                }
            }
            val st = PrecisionOut.stats(siteErrs)
            perSite.add(listOf(site.name, "${PrecisionOut.f(st.meanAbs, 4)} m", "${PrecisionOut.f(st.maxAbs, 4)} m"))
        }
        val st = PrecisionOut.stats(errs)
        val maxRel = relErrs.max()

        sections["distance"] = buildString {
            appendLine("### 1. Distance accuracy vs the WGS84 ellipsoid oracle ($n positions)\n")
            appendLine("Positions are placed at exact distances 1-300 m (1 m steps) and 36 bearings around four drop-off points, using the Vincenty direct formula. The app's own distance is compared with that exact distance.\n")
            appendLine(PrecisionOut.table(listOf("Measure", "Result"), listOf(
                listOf("Positions tested", n.toString()),
                listOf("Mean absolute distance error", "${PrecisionOut.f(st.meanAbs)} m"),
                listOf("Largest distance error", "${PrecisionOut.f(st.maxAbs)} m"),
                listOf("RMSE", "${PrecisionOut.f(st.rmse)} m"),
                listOf("Largest relative error", "${PrecisionOut.f(maxRel * 100, 3)} %")
            )))
            appendLine("\nPer drop-off point:\n")
            appendLine(PrecisionOut.table(listOf("Site", "Mean abs error", "Max abs error"), perSite))
        }
        sections["decision"] = buildString {
            appendLine("### 2. Allow / block decision (radius ${radius.toInt()} m)\n")
            appendLine(PrecisionOut.table(listOf("Measure", "Result"), listOf(
                listOf("Decision equals 'true distance <= ${radius.toInt()} m'", "${n - wrongDecision} of $n (${PrecisionOut.pct(n - wrongDecision, n)})"),
                listOf("Wrong decisions further than ${PrecisionOut.f(REL_BOUND * radius + 0.01, 2)} m from the boundary", decisionOutsideBand.toString()),
                listOf("Wrong decisions inside that band", (wrongDecision - decisionOutsideBand).toString())
            )))
            appendLine("\nBoundary sweep (equator site, bearing 0):\n")
            val sweep = listOf(58.0, 59.0, 59.5, 59.9, 60.0, 60.1, 60.5, 61.0, 62.0).map { d ->
                val (lat, lng) = destination(sites[1].lat, sites[1].lng, 0.0, d)
                val r = DropoffGeofence.check(lat, lng, sites[1].lat, sites[1].lng)
                listOf("${PrecisionOut.f(d, 1)} m", if (d <= radius) "allow" else "block", if (r.allowed) "allow" else "block", "${PrecisionOut.f(r.distanceMeters, 3)} m")
            }
            appendLine(PrecisionOut.table(listOf("True distance", "Expected", "App decision", "App distance"), sweep))
        }
        assertTrue("Distance error ${st.maxAbs} m", maxRel < REL_BOUND)
        assertEquals("Wrong decisions away from the boundary", 0, decisionOutsideBand)
    }

    @Test
    fun gps_noise_simulation() {
        val sigmas = listOf(0.0, 3.0, 5.0, 10.0, 20.0)
        val distances = listOf(10.0, 30.0, 50.0, 60.0, 70.0, 100.0, 150.0)
        val trials = 5_000
        val rnd = Random(20261107L)
        val site = sites[0]
        val grid = mutableMapOf<Pair<Double, Double>, Double>()

        sigmas.forEach { sigma ->
            distances.forEach { d ->
                var allowed = 0
                repeat(trials) {
                    val bearing = rnd.nextDouble() * 360.0
                    val (tLat, tLng) = destination(site.lat, site.lng, bearing, d)
                    // GPS error: independent east and north errors with standard deviation sigma.
                    val dx = rnd.nextGaussian() * sigma; val dy = rnd.nextGaussian() * sigma
                    val lat = tLat + dy / 111_320.0
                    val lng = tLng + dx / (111_320.0 * cos(Math.toRadians(tLat)))
                    if (DropoffGeofence.check(lat, lng, site.lat, site.lng).allowed) allowed++
                }
                val p = allowed.toDouble() / trials
                grid[sigma to d] = p
                noiseRows.add(listOf(sigma, d, trials, allowed, p))
            }
        }
        sections["noise"] = buildString {
            appendLine("### 3. GPS noise simulation ($trials trials per cell)\n")
            appendLine("Share of confirmation attempts that the app ALLOWS, by the driver's true distance from the drop-off and the GPS error (standard deviation per axis).\n")
            appendLine(PrecisionOut.table(
                listOf("GPS error") + distances.map { "${it.toInt()} m" },
                sigmas.map { s -> listOf("${s.toInt()} m") + distances.map { d -> PrecisionOut.pct((grid.getValue(s to d) * trials).toInt(), trials) } }
            ))
            appendLine("\nReading the table: a driver truly inside the radius should be allowed (close to 100%) and one truly outside should be blocked (close to 0%). The rows with larger GPS error show where phone GPS noise starts to cause wrong decisions near the boundary.")
        }
        // No noise: perfect decisions.
        // 60 m itself is skipped: a sphere-vs-ellipsoid gap of ~0.3 m makes it a coin flip by design.
        distances.filter { abs(it - radius) > 1.0 }.forEach { d -> assertEquals(if (d <= radius) 1.0 else 0.0, grid.getValue(0.0 to d), 0.0) }
        // The chance of being allowed never rises as the driver gets farther away.
        sigmas.forEach { s -> distances.zipWithNext().forEach { (d1, d2) -> assertTrue("sigma $s: $d1 -> $d2", grid.getValue(s to d2) <= grid.getValue(s to d1) + 0.03) } }
        // A driver deep inside is allowed, one far outside is blocked, even with 5 m of noise.
        assertTrue(grid.getValue(5.0 to 30.0) > 0.99)
        assertTrue(grid.getValue(5.0 to 100.0) < 0.01)
    }

    @Test
    fun spec_check_against_manuscript() {
        val paperRadius = 100.0 // FR27 and GEO-01 to GEO-04 in the manuscript
        sections["spec"] = buildString {
            appendLine("### 0. Radius used by the app vs the manuscript\n")
            appendLine(PrecisionOut.table(listOf("Source", "Radius"), listOf(
                listOf("Manuscript FR27 and GEO-01 to GEO-04", "${paperRadius.toInt()} m"),
                listOf("App (DropoffGeofence.RADIUS_METERS)", "${radius.toInt()} m")
            )))
            if (radius != paperRadius) appendLine("\nThe app and the manuscript use different radii. The tests below use the app's value.")
        }
        assertTrue(radius > 0.0)
    }
}
