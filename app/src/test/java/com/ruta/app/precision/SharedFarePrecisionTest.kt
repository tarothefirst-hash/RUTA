package com.ruta.app.precision

import com.ruta.app.util.FareConfig
import com.ruta.app.util.FareRates
import com.ruta.app.util.RideshareFareCalculator
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Random
import kotlin.math.abs

/**
 * MSH: precision of the shared-ride fare algorithms.
 *
 *  A. quoteSharedFare: the indicative shared quote.
 *  B. calculateFareBreakdown: the real individual fare computation for two passengers
 *     (solo legs, shared legs, detour surcharge, inconvenience credit, surge, discount).
 *
 * The app is run against an independent calculator written here with exact BigDecimal
 * arithmetic. The oracle never walks the route the way the app does: it works out, for each
 * passenger, the interval of legs they are on board (between their pickup and drop-off stop),
 * so a bug in the app's on-board bookkeeping cannot be copied into the expected value.
 */
class SharedFarePrecisionTest {

    // ---- specification (declared independently of the app) ------------------------------
    private val soloKm = BigDecimal("15");  private val soloMin = BigDecimal("2")
    private val sharedBase = BigDecimal("35")
    private val sharedKm = BigDecimal("7.5"); private val sharedMin = BigDecimal("1")
    private val creditRate = BigDecimal("0.30")
    private val MC = MathContext(40)

    private fun km(m: Int) = BigDecimal(m).divide(BigDecimal(1000), MC)
    private fun min(s: Int) = BigDecimal(s).divide(BigDecimal(60), MC)
    private fun soloCost(m: Int, s: Int) = km(m) * soloKm + min(s) * soloMin
    private fun sharedCost(m: Int, s: Int) = km(m) * sharedKm + min(s) * sharedMin
    private fun cents(x: BigDecimal) = x.setScale(2, RoundingMode.HALF_UP).toDouble()

    // ---- A. the shared quote ------------------------------------------------------------
    private fun quoteExact(m: Int, s: Int, surge: String, discount: Boolean, base: String = "35", perKm: String = "7.5", perMin: String = "1"): Exact {
        var v = Exact.trip(base, perKm, perMin, m, s) * BigDecimal(surge)
        if (discount) v *= BigDecimal("0.80")
        return v
    }

    // ---- B. the breakdown ---------------------------------------------------------------
    /** A trip of two passengers: the order of the four stops and the four legs that reach them. */
    private data class Trip(
        val boardA: Int, val alightA: Int, val boardB: Int, val alightB: Int,
        val legM: List<Int>, val legS: List<Int>,
        val directMA: Int, val directSA: Int, val directMB: Int, val directSB: Int,
        val surge: String, val discA: Boolean, val discB: Boolean
    ) {
        fun stopIds(): List<String> {
            val ids = arrayOfNulls<String>(4)
            ids[boardA] = "A"; ids[alightA] = "A"; ids[boardB] = "B"; ids[alightB] = "B"
            return ids.map { it!! }
        }
        fun stopIsPickup(): List<Boolean> {
            val p = BooleanArray(4); p[boardA] = true; p[boardB] = true
            return p.toList()
        }
    }

    private data class Expected(
        val solo: Double, val shared: Double, val surcharge: Double, val credit: Double,
        val total: Double, val totalExact: BigDecimal
    )
    private data class ExpectedPair(val a: Expected, val b: Expected, val sharedM: Int, val sharedS: Int)

    private fun onLeg(board: Int, alight: Int, leg: Int) = leg in (board + 1)..alight

    private fun oracle(t: Trip): ExpectedPair {
        var soloAm = 0; var soloAs = 0; var soloBm = 0; var soloBs = 0; var shM = 0; var shS = 0
        var rideAm = 0; var rideAs = 0; var rideBm = 0; var rideBs = 0
        for (leg in 0..3) {
            val a = onLeg(t.boardA, t.alightA, leg); val b = onLeg(t.boardB, t.alightB, leg)
            if (a) { rideAm += t.legM[leg]; rideAs += t.legS[leg] }
            if (b) { rideBm += t.legM[leg]; rideBs += t.legS[leg] }
            when {
                a && b -> { shM += t.legM[leg]; shS += t.legS[leg] }
                a -> { soloAm += t.legM[leg]; soloAs += t.legS[leg] }
                b -> { soloBm += t.legM[leg]; soloBs += t.legS[leg] }
            }
        }
        val extraAm = maxOf(0, rideAm - t.directMA); val extraAs = maxOf(0, rideAs - t.directSA)
        val extraBm = maxOf(0, rideBm - t.directMB); val extraBs = maxOf(0, rideBs - t.directSB)
        // A pays for the detour A causes B, and gets back 30% of what B pays for the detour B causes A.
        val surchargeA = soloCost(extraBm, extraBs); val surchargeB = soloCost(extraAm, extraAs)
        val creditA = surchargeB * creditRate; val creditB = surchargeA * creditRate
        val sharedEach = sharedCost(shM, shS)

        fun fare(solo: BigDecimal, sur: BigDecimal, cr: BigDecimal, disc: Boolean): Expected {
            val subtotal = sharedBase + solo + sharedEach + sur - cr
            var total = subtotal.max(sharedBase) * BigDecimal(t.surge)
            if (disc) total *= BigDecimal("0.80")
            return Expected(cents(solo), cents(sharedEach), cents(sur), cents(cr), cents(total), total)
        }
        return ExpectedPair(
            fare(soloCost(soloAm, soloAs), surchargeA, creditA, t.discA),
            fare(soloCost(soloBm, soloBs), surchargeB, creditB, t.discB), shM, shS
        )
    }

    private fun runApp(t: Trip): RideshareFareCalculator.FareBreakdown =
        RideshareFareCalculator.calculateFareBreakdown(
            orderedStopBookingIds = t.stopIds(), stopIsPickup = t.stopIsPickup(),
            legDistancesMeters = t.legM, legDurationsSeconds = t.legS,
            bookingIdA = "A", bookingIdB = "B",
            directDistanceA = t.directMA, directDurationA = t.directSA,
            directDistanceB = t.directMB, directDurationB = t.directSB,
            surgeMultiplier = t.surge.toDouble(), hasDiscountA = t.discA, hasDiscountB = t.discB
        )

    /** All six valid stop orders of two passengers, as (boardA, alightA, boardB, alightB) stop positions. */
    private val orders = listOf(
        listOf(0, 1, 2, 3), // A A B B
        listOf(0, 2, 1, 3), // A B A B
        listOf(0, 3, 1, 2), // A B B A
        listOf(1, 3, 0, 2), // B A B A
        listOf(1, 2, 0, 3), // B A A B
        listOf(2, 3, 0, 1)  // B B A A
    )

    private fun randomTrip(rnd: Random, forceSurge: String? = null): Trip {
        val o = orders[rnd.nextInt(6)]
        val legM = List(4) { 300 + rnd.nextInt(14_700) }
        val legS = List(4) { 60 + rnd.nextInt(2_340) }
        fun ride(b: Int, a: Int, l: List<Int>) = (b + 1..a).sumOf { l[it] }
        val rideAm = ride(o[0], o[1], legM); val rideAs = ride(o[0], o[1], legS)
        val rideBm = ride(o[2], o[3], legM); val rideBs = ride(o[2], o[3], legS)
        // Direct trips are usually shorter than what the shared route makes a passenger ride,
        // sometimes longer, to exercise both sides of the detour rule.
        fun direct(x: Int) = (x * (0.55 + rnd.nextDouble() * 0.75)).toInt()
        return Trip(
            o[0], o[1], o[2], o[3], legM, legS,
            direct(rideAm), direct(rideAs), direct(rideBm), direct(rideBs),
            forceSurge ?: listOf("1.0", "1.25", "1.30")[rnd.nextInt(3)], rnd.nextInt(4) == 0, rnd.nextInt(4) == 0
        )
    }

    companion object {
        private val csvRows = mutableListOf<List<Any?>>()
        private val sections = linkedMapOf<String, String>()

        @JvmStatic @AfterClass fun flush() {
            PrecisionOut.writeCsv(
                "MSH_breakdown_cases.csv",
                listOf("case", "order", "surge", "discA", "discB", "passenger", "expected_total", "app_total", "diff", "expected_solo", "app_solo", "expected_shared", "app_shared", "expected_surcharge", "app_surcharge", "expected_credit", "app_credit"),
                csvRows
            )
            val md = StringBuilder("## MSH: Shared fare precision\n\n")
            listOf("quote", "breakdown", "props", "paper", "config").forEach { k -> sections[k]?.let { md.append(it).append("\n") } }
            PrecisionOut.writeSummary("MSH", md.toString())
        }
    }

    // ============================== tests ====================================================

    @Test
    fun shared_quote_matches_independent_calculator() {
        var n = 0; var exact = 0; var ties = 0; var tieEven = 0; var tieOther = 0; var other = 0
        val rnd = Random(11L)
        val errs = mutableListOf<Double>()
        fun check(m: Int, s: Int, sg: String, d: Boolean) {
            n++
            val x = quoteExact(m, s, sg, d)
            val expected = x.roundHalfUp().toDouble()
            val got = RideshareFareCalculator.quoteSharedFare(m, s, sg.toDouble(), d)
            errs.add(got - x.toDouble())
            when {
                got == expected -> exact++
                x.isTie() -> { ties++; if (got == x.roundHalfEven().toDouble()) tieEven++ else if (got == x.floor().toDouble() || got == x.floor() + 1.0) tieOther++ }
                else -> other++
            }
        }
        for (m in 0..10_000 step 100) for (min in 0..30) for (sg in listOf("1.0", "1.25", "1.30")) for (d in listOf(false, true)) check(m, min * 60, sg, d)
        repeat(20_000) { check(200 + rnd.nextInt(39_800), 60 + rnd.nextInt(7_140), listOf("1.0", "1.25", "1.30")[rnd.nextInt(3)], rnd.nextBoolean()) }
        val st = PrecisionOut.stats(errs)
        sections["quote"] = buildString {
            appendLine("### 1. Shared quote vs independent calculator ($n cases)\n")
            appendLine("Expected = `(35 + km x 7.50 + min x 1.00) x surge`, discount x0.80, rounded half up to the nearest peso.\n")
            appendLine(PrecisionOut.table(listOf("Measure", "Result"), listOf(
                listOf("App equals expected", "$exact (${PrecisionOut.pct(exact, n)})"),
                listOf("Exact .5 peso ties where the app is not at 'round half up'", "$ties (half-even result in $tieEven, the other neighbour in $tieOther)"),
                listOf("Other mismatches", other.toString()),
                listOf("Largest deviation from the unrounded exact fare", "${PrecisionOut.f(st.maxAbs)} peso"),
                listOf("Mean absolute deviation", "${PrecisionOut.f(st.meanAbs)} peso")
            )))
        }
        assertEquals(0, other)
        assertEquals(ties, tieEven + tieOther)
    }

    @Test
    fun breakdown_matches_independent_calculator() {
        val rnd = Random(20261107L)
        val total = 20_000
        var passengers = 0; var exactTotals = 0; var withinCent = 0; var bad = 0
        val totalErr = mutableListOf<Double>()
        val worst = mutableListOf<String>()
        var sharedMismatch = 0
        repeat(total) { i ->
            val t = randomTrip(rnd)
            val e = oracle(t); val g = runApp(t)
            if (g.sharedDistanceMeters != e.sharedM || g.sharedDurationSeconds != e.sharedS) sharedMismatch++
            listOf("A" to (e.a to g.passengerA), "B" to (e.b to g.passengerB)).forEach { (who, pair) ->
                val (exp, app) = pair
                passengers++
                val d = app.total - exp.total
                totalErr.add(app.total - exp.totalExact.toDouble())
                if (abs(d) < 1e-9) exactTotals++
                if (abs(d) <= 0.0100001) withinCent++ else { bad++; if (worst.size < 5) worst.add("case $i $who: expected ${exp.total} got ${app.total}") }
                if (i < 2_000) csvRows.add(listOf(i, "${t.boardA}${t.alightA}${t.boardB}${t.alightB}", t.surge, t.discA, t.discB, who, exp.total, app.total, d,
                    exp.solo, app.soloCharge, exp.shared, app.sharedCharge, exp.surcharge, app.detourSurcharge, exp.credit, app.inconvenienceCredit))
                val comp = listOf(exp.solo - app.soloCharge, exp.shared - app.sharedCharge, exp.surcharge - app.detourSurcharge, exp.credit - app.inconvenienceCredit)
                if (comp.any { abs(it) > 0.0100001 }) { bad++; if (worst.size < 5) worst.add("case $i $who component mismatch $comp") }
            }
        }
        val st = PrecisionOut.stats(totalErr)
        sections["breakdown"] = buildString {
            appendLine("### 2. Individual fare computation vs independent calculator ($total trips, $passengers passenger fares)\n")
            appendLine("Random valid two-passenger trips: all six stop orders, random leg distances and times, random direct distances (detours both ways), all surge levels, ID discounts on either passenger.\n")
            appendLine(PrecisionOut.table(listOf("Measure", "Result"), listOf(
                listOf("Total fare identical to the cent", "$exactTotals (${PrecisionOut.pct(exactTotals, passengers)})"),
                listOf("Total fare within one cent", "$withinCent (${PrecisionOut.pct(withinCent, passengers)})"),
                listOf("Solo, shared, surcharge and credit lines wrong by more than one cent", bad.toString()),
                listOf("Shared distance or time different from the independent value", sharedMismatch.toString()),
                listOf("Largest deviation of app total from the unrounded exact fare", "${PrecisionOut.f(st.maxAbs)} peso"),
                listOf("Mean absolute deviation", "${PrecisionOut.f(st.meanAbs)} peso")
            )))
            appendLine("\nThe first 2,000 trips with every line item are in MSH_breakdown_cases.csv.")
        }
        assertEquals("Mismatches: $worst", 0, bad)
        assertEquals(0, sharedMismatch)
    }

    @Test
    fun properties_hold_on_random_trips() {
        val rnd = Random(404L)
        val keys = listOf(
            "Swapping passenger A and B swaps their fares",
            "Nobody pays less than the minimum (35 x surge, after discount)",
            "A longer leg never lowers the fare of a passenger on it",
            "Together with no detour, a passenger pays no more than solo",
            "The detour surcharge one pays equals the credit 30% the other gets, to the cent"
        )
        val v = keys.associateWith { 0 }.toMutableMap()
        fun bump(k: String) { v[k] = v.getValue(k) + 1 }
        val n = 10_000
        repeat(n) {
            val t = randomTrip(rnd)
            val g = runApp(t)
            // swap A/B labels
            val swapped = t.copy(
                boardA = t.boardB, alightA = t.alightB, boardB = t.boardA, alightB = t.alightA,
                directMA = t.directMB, directSA = t.directSB, directMB = t.directMA, directSB = t.directSA,
                discA = t.discB, discB = t.discA
            )
            val gs = runApp(swapped)
            if (abs(g.passengerA.total - gs.passengerB.total) > 0.0100001 || abs(g.passengerB.total - gs.passengerA.total) > 0.0100001) bump(keys[0])

            val minFare = cents(sharedBase * BigDecimal(t.surge))
            if (g.passengerA.total < minFare * (if (t.discA) 0.8 else 1.0) - 0.0100001 ||
                g.passengerB.total < minFare * (if (t.discB) 0.8 else 1.0) - 0.0100001) bump(keys[1])

            // lengthen a leg A rides: A's fare must not go down
            val legs = (t.boardA + 1..t.alightA).toList()
            val leg = legs[rnd.nextInt(legs.size)]
            val longer = t.copy(legM = t.legM.toMutableList().also { it[leg] += 1000 }, legS = t.legS.toMutableList().also { it[leg] += 120 })
            if (runApp(longer).passengerA.total < g.passengerA.total - 0.0100001) bump(keys[2])

            if (abs(g.passengerA.inconvenienceCredit - g.passengerB.detourSurcharge * 0.30) > 0.0200001 ||
                abs(g.passengerB.inconvenienceCredit - g.passengerA.detourSurcharge * 0.30) > 0.0200001) bump(keys[4])
        }
        // identical trip together, no detour: shared fare <= solo quote of the same trip
        var soloViolations = 0
        repeat(n) {
            val m = 500 + rnd.nextInt(30_000); val s = 120 + rnd.nextInt(5_000)
            val sg = listOf("1.0", "1.25", "1.30")[rnd.nextInt(3)]
            val t = Trip(0, 2, 1, 3, listOf(0, 0, m, 0), listOf(0, 0, s, 0), m, s, m, s, sg, false, false)
            // A and B ride the single leg together (A: stop0->stop2 includes leg 1..2).
            val together = RideshareFareCalculator.calculateFareBreakdown(
                listOf("A", "B", "A", "B"), listOf(true, true, false, false),
                listOf(0, 0, m, 0), listOf(0, 0, s, 0), "A", "B", m, s, m, s, sg.toDouble(), false, false
            )
            val solo = RideshareFareCalculator.quoteSoloFare(m, s, sg.toDouble(), false)
            if (together.passengerA.total > solo + 0.0100001 || together.passengerB.total > solo + 0.0100001) soloViolations++
        }
        v[keys[3]] = soloViolations
        sections["props"] = "### 3. Property checks ($n random trips each)\n\n" +
            PrecisionOut.table(listOf("Property", "Violations", "Status"), keys.map { listOf(it, v.getValue(it).toString(), if (v.getValue(it) == 0) "PASS" else "FAIL") })
        keys.forEach { assertEquals(it, 0, v.getValue(it)) }
    }

    @Test
    fun paper_table46_against_current_app() {
        data class P(val id: String, val m: Int, val s: Int, val surge: String, val paper: Int)
        val rows = listOf(P("MSH-01", 2400, 480, "1.0", 59), P("MSH-02", 5200, 840, "1.25", 107), P("MSH-03", 3800, 720, "1.30", 95))
        val out = rows.map { p ->
            val got = RideshareFareCalculator.quoteSharedFare(p.m, p.s, p.surge.toDouble(), false).toInt()
            val expectedNow = quoteExact(p.m, p.s, p.surge, false).roundHalfUp()
            assertEquals(p.id, expectedNow, got) // app == independent calculator with the current P35 base
            listOf(p.id, "P${p.paper} (base P32.50)", "P$expectedNow (base P35)", "P$got", if (got == p.paper) "same as paper" else "differs from paper")
        }
        sections["paper"] = "### 4. The paper's MSH-01 to MSH-03 on the current app\n\n" +
            "Table 46 of the manuscript computes the shared quote with a P32.50 base (half of P65). The app now prices a shared ride with a P35 base " +
            "(changed in the rideshare fare formula update), so the app and the manuscript no longer agree on these three quotes.\n\n" +
            PrecisionOut.table(listOf("Test ID", "Manuscript expected", "Expected with the app's P35 base", "App output", "Result"), out)
    }

    @Test
    fun follows_admin_edited_rates() {
        val rnd = Random(9L)
        var bad = 0; val n = 2_000
        try {
            FareConfig.update(FareRates(soloBase = 70.0, soloPerKm = 16.0, soloPerMin = 2.5, sharedBase = 40.0, sharedPerKm = 8.0, sharedPerMin = 1.2))
            repeat(n) {
                val m = 200 + rnd.nextInt(39_800); val s = 60 + rnd.nextInt(7_140)
                val sg = listOf("1.0", "1.25", "1.30")[rnd.nextInt(3)]
                val x = quoteExact(m, s, sg, false, "40", "8", "1.2")
                if (x.isTie()) return@repeat
                if (RideshareFareCalculator.quoteSharedFare(m, s, sg.toDouble(), false) != x.roundHalfUp().toDouble()) bad++
            }
        } finally { FareConfig.update(FareRates.DEFAULT) }
        sections["config"] = "### 5. Admin-edited rates (shared base 40, P8/km, P1.20/min)\n\n" +
            PrecisionOut.table(listOf("Cases", "Mismatches vs independent calculator", "Status"), listOf(listOf(n.toString(), bad.toString(), if (bad == 0) "PASS" else "FAIL")))
        assertEquals(0, bad)
    }
}
