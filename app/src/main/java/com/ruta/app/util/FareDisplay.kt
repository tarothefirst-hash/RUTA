package com.ruta.app.util

import java.util.Locale

/**
 * FareDisplay
 * ------------
 * Turns the settled/estimated fare numbers the passenger card reads off a booking
 * snapshot into plain strings. It is deliberately free of Android types so the
 * rendering rules can be pinned by a JVM unit test.
 *
 * All user-visible wording is injected through [Labels], which the activity fills
 * from strings.xml — nothing here hardcodes display copy. The helper only owns the
 * numeric formatting and the decision of which lines to emit.
 *
 * Render rules (keyed on the numbers, not on the status alone):
 *  - a settled [Snapshot.finalFare] (greater than zero) always wins. The driver
 *    writes it after the trip reaches COMPLETED, so a later snapshot update still
 *    refreshes the card with the final figure and its breakdown.
 *  - a COMPLETED shared booking with no settled fare yet -> finalising message.
 *  - a CANCELLED shared booking -> no new line: promising a final fare that is never
 *    coming is false, so the card keeps whatever it shows today.
 *  - shared booking still running -> quote stays the headline, new line explains the
 *    final share is set when the trip ends (quoting the estimate).
 *  - solo booking -> no new line at all; its card behaves exactly as before.
 */
object FareDisplay {

    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_CANCELLED = "CANCELLED"

    private val TERMINAL_STATUSES = setOf(STATUS_COMPLETED, STATUS_CANCELLED)

    /** Raw values read from the booking snapshot. Nothing here is Android-specific. */
    data class Snapshot(
        val finalFare: Double,
        val fareBase: Double,
        val fareSoloCharge: Double,
        val fareSharedCharge: Double,
        val fareDetourSurcharge: Double,
        val fareDiscount: Double = 0.0,
        val fareInconvenienceCredit: Double,
        val quotedFare: Double,
        val status: String,
        val isShared: Boolean,
        /** True once both co-passengers confirmed the match and the quote was locked. */
        val fareLocked: Boolean = false
    )

    /** Display copy supplied by the caller (loaded from strings.xml). */
    data class Labels(
        val headlineFinal: String,
        val detailBase: String,
        val detailYourRide: String,
        val detailShared: String,
        val detailDetour: String,
        val detailCredit: String,
        val detailAdjustedFrom: String,
        val detailFinalising: String,
        val detailAwaitingFinalShare: String,
        val detailDiscount: String,
        val moneyFormat: String,
        /** "Fare locked at %1$s ..." shown once a rideshare match is confirmed. Blank = not used. */
        val detailLockedFare: String = ""
    )

    /**
     * @param headline the line for the big fare TextView, or null when the caller
     *        should leave its existing headline (e.g. the running quote) untouched.
     * @param detail the content for the detail line, or null when it should be hidden.
     */
    data class Rendered(
        val headline: String?,
        val detail: String?
    )

    fun render(snapshot: Snapshot, labels: Labels): Rendered {
        val discountLine = discountLine(snapshot, labels)

        if (snapshot.finalFare > 0.0) {
            val settled = renderSettled(snapshot, labels)
            return settled.copy(
                detail = listOfNotNull(settled.detail, discountLine).joinToString("\n")
            )
        }

        val isTerminal = snapshot.status in TERMINAL_STATUSES
        return when {
            snapshot.isShared && snapshot.status == STATUS_COMPLETED ->
                Rendered(headline = null, detail = labels.detailFinalising)

            snapshot.isShared && !isTerminal && snapshot.fareLocked &&
                snapshot.quotedFare > 0.0 && labels.detailLockedFare.isNotEmpty() ->
                Rendered(
                    headline = null,
                    detail = String.format(
                        Locale.US,
                        labels.detailLockedFare,
                        money(snapshot.quotedFare, labels.moneyFormat)
                    )
                )

            snapshot.isShared && !isTerminal ->
                Rendered(
                    headline = null,
                    detail = String.format(
                        Locale.US,
                        labels.detailAwaitingFinalShare,
                        money(snapshot.quotedFare, labels.moneyFormat)
                    )
                )

            // Solo: only the discount line (if any); null hides the detail view as before
            else -> Rendered(headline = null, detail = discountLine)
        }
    }
    private fun discountLine(s: Snapshot, l: Labels): String? =
        if (!s.isShared && s.status != STATUS_CANCELLED && s.fareDiscount > 0.0)
            String.format(Locale.US, l.detailDiscount, money(s.fareDiscount, l.moneyFormat))
        else null
    private fun renderSettled(snapshot: Snapshot, labels: Labels): Rendered {
        val headline = String.format(
            Locale.US,
            labels.headlineFinal,
            money(snapshot.finalFare, labels.moneyFormat)
        )

        // The passenger's own ride always shows; base and the rest only when non-zero.
        // Records written before fareBase existed have no base, and rendering that as
        // "Base ₱0.00" reads as a bug, so the line is omitted while the value is absent.
        val parts = mutableListOf<String>()
        if (snapshot.fareBase > 0.0) {
            parts.add(String.format(Locale.US, labels.detailBase, money(snapshot.fareBase, labels.moneyFormat)))
        }
        parts.add(String.format(Locale.US, labels.detailYourRide, money(snapshot.fareSoloCharge, labels.moneyFormat)))

        if (snapshot.fareSharedCharge > 0.0) {
            parts.add(String.format(Locale.US, labels.detailShared, money(snapshot.fareSharedCharge, labels.moneyFormat)))
        }
        if (snapshot.fareDetourSurcharge > 0.0) {
            parts.add(String.format(Locale.US, labels.detailDetour, money(snapshot.fareDetourSurcharge, labels.moneyFormat)))
        }

        var detail = parts.joinToString(" + ")

        if (snapshot.fareInconvenienceCredit > 0.0) {
            detail += " - " +
                String.format(Locale.US, labels.detailCredit, money(snapshot.fareInconvenienceCredit, labels.moneyFormat))
        }

        // Quote vs settlement: only meaningful when we actually had a quote.
        if (snapshot.quotedFare > 0.0 && kotlin.math.abs(snapshot.quotedFare - snapshot.finalFare) >= 0.005) {
            detail += "\n" + String.format(
                Locale.US,
                labels.detailAdjustedFrom,
                money(snapshot.quotedFare, labels.moneyFormat)
            )
        }

        return Rendered(headline = headline, detail = detail)
    }

    private fun money(value: Double, format: String): String =
        String.format(Locale.US, format, value)
}
