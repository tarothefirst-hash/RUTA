package com.ruta.app.precision

import com.ruta.app.util.FareCalculator
import com.ruta.app.util.FareConfig
import com.ruta.app.util.FareRates
import com.ruta.app.util.RideshareFareCalculator
import com.ruta.app.util.VehicleType
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.Random

/**
 * SOL: precision of the solo fare algorithm (FareCalculator.calculateFare).
 *
 * The app code is run against an INDEPENDENT calculator written here with exact BigDecimal
 * arithmetic (no floating point drift, round half up = "nearest peso"). Thousands of generated
 * trips, a dense boundary grid, the paper's own cases, property checks and a cross-check against
 * the second solo engine in the app are evaluated, and every case is written to SOL_cases.csv.
 */
class SoloFarePrecisionTest {

    // ---- the specification, declared independently of the app code ----------------------
    private data class Spec(val base: String, val perKm: String, val perMin: String)
    private val paperSpec = Spec("65", "15", "2")
    private val surgeHours = mapOf(7 to "1.25", 8 to "1.25", 17 to "1.30", 18 to "1.30", 19 to "1.30")

    /** Exact fare before rounding, kept as an exact rational. */
    private fun exactFare(s: Spec, meters: Int, seconds: Int, surge: String, discount: Boolean): Exact {
        var v = Exact.trip(s.base, s.perKm, s.perMin, meters, seconds) * BigDecimal(surge)
        if (discount) v *= BigDecimal("0.80")
        return v
    }

    private fun oracle(s: Spec, meters: Int, seconds: Int, surge: String, discount: Boolean): Int =
        exactFare(s, meters, seconds, surge, discount).roundHalfUp()

    private fun isTie(x: Exact): Boolean = x.isTie()

    private fun app(meters: Int, seconds: Int, surge: Double, discount: Boolean): Double =
        FareCalculator.calculateFare(
            distanceKm = meters / 1000.0, durationMin = seconds / 60.0,
            vehicleType = VehicleType.SEDAN, hasDiscount = discount, surgeMultiplierOverride = surge
        )

    private data class Case(val group: String, val meters: Int, val seconds: Int, val surge: String, val discount: Boolean)

    companion object {
        private val csvRows = mutableListOf<List<Any?>>()
        private val sections = linkedMapOf<String, String>()

        @JvmStatic @AfterClass fun flush() {
            PrecisionOut.writeCsv(
                "SOL_cases.csv",
                listOf("group", "meters", "seconds", "surge", "discount", "exact_unrounded", "expected_half_up", "app", "tie", "diff"),
                csvRows
            )
            val md = StringBuilder("## SOL: Solo fare precision\n\n")
            listOf("engine", "paper", "surge", "props", "config").forEach { k -> sections[k]?.let { md.append(it).append("\n") } }
            PrecisionOut.writeSummary("SOL", md.toString())
        }
    }

    private fun cases(): List<Case> {
        val list = mutableListOf<Case>()
        // 1. Dense grid: 0-10 km in 100 m steps, 0-30 min in 1 min steps, every surge level, with/without discount.
        for (m in 0..10_000 step 100) for (min in 0..30) for (sg in listOf("1.0", "1.25", "1.30")) for (d in listOf(false, true))
            list.add(Case("grid", m, min * 60, sg, d))
        // 2. Seeded random trips shaped like real Tarlac trips (0.2-40 km, 1-120 min).
        val rnd = Random(20261107L)
        repeat(20_000) {
            list.add(
                Case(
                    "random", 200 + rnd.nextInt(39_800), 60 + rnd.nextInt(7_140),
                    listOf("1.0", "1.25", "1.30")[rnd.nextInt(3)], rnd.nextBoolean()
                )
            )
        }
        return list
    }

    @Test
    fun solo_fare_matches_independent_calculator() {
        val all = cases()
        var exact = 0; var ties = 0; var tieHalfEvenOk = 0; var tieOtherNeighbour = 0; var mismatches = 0
        val roundingErr = mutableListOf<Double>()
        val worst = mutableListOf<String>()

        all.forEach { c ->
            val exactFare = exactFare(paperSpec, c.meters, c.seconds, c.surge, c.discount)
            val expected = oracle(paperSpec, c.meters, c.seconds, c.surge, c.discount)
            val got = app(c.meters, c.seconds, c.surge.toDouble(), c.discount)
            val tie = isTie(exactFare)
            val diff = got - expected
            csvRows.add(listOf(c.group, c.meters, c.seconds, c.surge, c.discount, exactFare.toDouble(), expected, got, tie, diff))
            roundingErr.add(got - exactFare.toDouble())

            when {
                got == expected.toDouble() -> exact++
                tie -> {
                    ties++
                    // Exact .5 peso: record which neighbour the app picked; never a hidden error.
                    if (got == exactFare.roundHalfEven().toDouble()) tieHalfEvenOk++
                    else if (got == exactFare.floor().toDouble() || got == exactFare.floor() + 1.0) tieOtherNeighbour++
                }
                else -> { mismatches++; if (worst.size < 5) worst.add("${c.group} ${c.meters}m ${c.seconds}s x${c.surge} d=${c.discount}: expected $expected got $got") }
            }
        }

        val st = PrecisionOut.stats(roundingErr)
        sections["engine"] = buildString {
            appendLine("### 1. App vs independent calculator (${all.size} generated cases)\n")
            appendLine("Expected value = exact BigDecimal fare `(base + km x 15 + min x 2) x surge x (1 - 0.20 if discounted)` rounded half up to the nearest peso.\n")
            appendLine(PrecisionOut.table(
                listOf("Measure", "Result"),
                listOf(
                    listOf("Cases run", all.size.toString()),
                    listOf("App equals expected", "$exact (${PrecisionOut.pct(exact, all.size)})"),
                    listOf("Exact .5 peso ties where the app is not at 'round half up'", "$ties (half-even result in $tieHalfEvenOk, the other neighbour in $tieOtherNeighbour)"),
                    listOf("Other mismatches", mismatches.toString()),
                    listOf("Largest deviation of app from the unrounded exact fare", "${PrecisionOut.f(st.maxAbs)} peso"),
                    listOf("Mean absolute deviation (rounding only)", "${PrecisionOut.f(st.meanAbs)} peso"),
                    listOf("RMSE", "${PrecisionOut.f(st.rmse)} peso")
                )
            ))
            if (ties > 0) appendLine("\nFinding: when the exact fare ends in .50 the app does not always round up (for example 112.50 becomes 112, while 'nearest peso' normally means 113). It uses round-half-to-even on a floating point value, so at an exact tie the result depends on floating point noise. $ties tie cases in this run; every one landed on a neighbouring peso, never further.")
        }
        assertEquals("Non-tie mismatches: $worst", 0, mismatches)
        assertEquals("Tie cases further than one peso from the exact fare", ties, tieHalfEvenOk + tieOtherNeighbour)
        // The rounding step can never move the fare by more than half a peso.
        assertTrue(st.maxAbs <= 0.5 + 1e-6)
    }

    @Test
    fun paper_cases_SOL_01_to_04() {
        data class P(val id: String, val m: Int, val s: Int, val surge: String, val d: Boolean, val paper: Int)
        val rows = listOf(
            P("SOL-01", 2400, 480, "1.0", false, 117), P("SOL-02", 2400, 480, "1.0", true, 94),
            P("SOL-03", 5200, 840, "1.25", false, 214), P("SOL-04", 5200, 840, "1.25", true, 171)
        )
        val out = rows.map { p ->
            val got = app(p.m, p.s, p.surge.toDouble(), p.d)
            assertEquals(p.id, p.paper.toDouble(), got, 0.0)
            listOf(p.id, "${p.m / 1000.0} km, ${p.s / 60} min, x${p.surge}, discount ${p.d}", "P${p.paper}", "P${got.toInt()}", "PASS")
        }
        sections["paper"] = "### 2. The paper's SOL-01 to SOL-04 re-run on the current app\n\n" +
            PrecisionOut.table(listOf("Test ID", "Inputs", "Paper expected", "App output", "Status"), out)
    }

    @Test
    fun surge_table_for_all_24_hours() {
        val rows = (0..23).map { h ->
            val expected = (surgeHours[h] ?: "1.0").toDouble()
            val got = FareCalculator.getSurgeMultiplier(h)
            assertEquals("hour $h", expected, got, 0.0)
            listOf(h.toString(), "x$expected", "x$got", "PASS")
        }
        sections["surge"] = "### 3. Time-of-day surge for every hour (0-23)\n\n" +
            PrecisionOut.table(listOf("Hour", "Specified", "App", "Status"), rows)
    }

    @Test
    fun properties_hold_on_random_trips() {
        val rnd = Random(77L)
        var n = 0
        var engineTieDisagree = 0
        val v = linkedMapOf(
            "More distance never lowers the fare" to 0,
            "More time never lowers the fare" to 0,
            "Discounted fare is within 1 peso of 80% of the full fare" to 0,
            "Peak surge never costs less than off-peak" to 0,
            "Fare is never below the minimum (base x surge x discount)" to 0,
            "Fare is a whole peso amount" to 0,
            "Both solo engines in the app agree" to 0
        )
        fun bump(k: String) { v[k] = v.getValue(k) + 1 }
        repeat(20_000) {
            n++
            val m = 200 + rnd.nextInt(39_800); val s = 60 + rnd.nextInt(7_140)
            val sg = listOf(1.0, 1.25, 1.30)[rnd.nextInt(3)]; val d = rnd.nextBoolean()
            val base = app(m, s, sg, d)
            if (app(m + 1 + rnd.nextInt(5_000), s, sg, d) < base) bump("More distance never lowers the fare")
            if (app(m, s + 1 + rnd.nextInt(3_000), sg, d) < base) bump("More time never lowers the fare")
            val full = app(m, s, sg, false)
            if (kotlin.math.abs(app(m, s, sg, true) - 0.8 * full) > 1.0) bump("Discounted fare is within 1 peso of 80% of the full fare")
            if (app(m, s, 1.30, d) < app(m, s, 1.0, d)) bump("Peak surge never costs less than off-peak")
            if (base < kotlin.math.round(65.0 * sg * (if (d) 0.8 else 1.0)) - 1.0) bump("Fare is never below the minimum (base x surge x discount)")
            if (base != kotlin.math.floor(base)) bump("Fare is a whole peso amount")
            val other = RideshareFareCalculator.quoteSoloFare(m, s, sg, d)
            if (other != base) {
                // At an exact .5 peso the two engines can land on different pesos through
                // floating point noise; that is the tie finding, counted separately.
                if (isTie(exactFare(paperSpec, m, s, sg.toString(), d))) engineTieDisagree++ else bump("Both solo engines in the app agree")
            }
        }
        sections["props"] = "### 4. Property checks ($n random trips each)\n\n" +
            PrecisionOut.table(listOf("Property", "Violations", "Status"), v.map { listOf(it.key, it.value.toString(), if (it.value == 0) "PASS" else "FAIL") }) +
            "\nThe two solo engines (FareCalculator and RideshareFareCalculator.quoteSoloFare) landed on different pesos in $engineTieDisagree of the $n trips; every one of those was an exact .5 peso tie, where floating point noise decides the direction.\n"
        v.forEach { (k, c) -> assertEquals(k, 0, c) }
    }

    @Test
    fun follows_admin_edited_rates() {
        val custom = Spec("70", "16", "2.5")
        val rnd = Random(5L)
        var bad = 0
        val n = 3_000
        try {
            FareConfig.update(FareRates(soloBase = 70.0, soloPerKm = 16.0, soloPerMin = 2.5, sharedBase = 35.0, sharedPerKm = 7.5, sharedPerMin = 1.0))
            repeat(n) {
                val m = 200 + rnd.nextInt(39_800); val s = 60 + rnd.nextInt(7_140)
                val sg = listOf("1.0", "1.25", "1.30")[rnd.nextInt(3)]; val d = rnd.nextBoolean()
                val x = exactFare(custom, m, s, sg, d)
                if (x.isTie()) return@repeat
                if (app(m, s, sg.toDouble(), d) != oracle(custom, m, s, sg, d).toDouble()) bad++
            }
        } finally {
            FareConfig.update(FareRates.DEFAULT)
        }
        sections["config"] = "### 5. Admin-edited rates (base 70, P16/km, P2.50/min)\n\n" +
            PrecisionOut.table(listOf("Cases", "Mismatches vs independent calculator", "Status"), listOf(listOf(n.toString(), bad.toString(), if (bad == 0) "PASS" else "FAIL")))
        assertEquals(0, bad)
    }
}
