package com.ruta.app.precision

import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Where the precision testers write their data, and small helpers for the numbers in it.
 *
 * Output folder: app/build/precision-reports (override with -Dprecision.out=<folder>).
 * Every tester writes its own CSV of every case plus a markdown summary; the combined
 * PRECISION_REPORT.md is rebuilt from whichever summaries exist.
 */
object PrecisionOut {

    private val order = listOf("SOL", "MSH", "GEO")

    val dir: File by lazy {
        File(System.getProperty("precision.out") ?: "build/precision-reports").also { it.mkdirs() }
    }

    fun writeCsv(name: String, header: List<String>, rows: List<List<Any?>>) {
        File(dir, name).bufferedWriter().use { w ->
            w.appendLine(header.joinToString(","))
            rows.forEach { r -> w.appendLine(r.joinToString(",") { csv(it) }) }
        }
    }

    private fun csv(v: Any?): String {
        val s = when (v) {
            null -> ""
            is Double -> String.format(Locale.US, "%.6f", v)
            else -> v.toString()
        }
        return if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    /** Saves one tester's summary and rebuilds the combined report. */
    fun writeSummary(id: String, markdown: String) {
        File(dir, "${id}_summary.md").writeText(markdown)
        val combined = StringBuilder("# RUTA algorithmic precision report\n\n")
        order.forEach { key ->
            val f = File(dir, "${key}_summary.md")
            if (f.exists()) combined.append(f.readText()).append("\n\n")
        }
        File(dir, "PRECISION_REPORT.md").writeText(combined.toString())
        println("Precision report written to: ${File(dir, "PRECISION_REPORT.md").absolutePath}")
    }

    fun pct(part: Int, whole: Int): String =
        if (whole == 0) "n/a" else String.format(Locale.US, "%.2f%%", 100.0 * part / whole)

    fun f(v: Double, digits: Int = 4): String = String.format(Locale.US, "%.${digits}f", v)

    /** Plain markdown table. */
    fun table(header: List<String>, rows: List<List<String>>): String = buildString {
        appendLine("| " + header.joinToString(" | ") + " |")
        appendLine("|" + header.joinToString("|") { "---" } + "|")
        rows.forEach { appendLine("| " + it.joinToString(" | ") + " |") }
    }

    data class ErrorStats(val n: Int, val maxAbs: Double, val meanAbs: Double, val rmse: Double)

    fun stats(errors: List<Double>): ErrorStats {
        if (errors.isEmpty()) return ErrorStats(0, 0.0, 0.0, 0.0)
        val abs = errors.map { abs(it) }
        return ErrorStats(errors.size, abs.max(), abs.average(), kotlin.math.sqrt(errors.map { it * it }.average()))
    }
}
