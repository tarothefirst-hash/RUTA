package com.ruta.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure receipt content: numbers, wording and the email HTML. No Android or Firebase here. */
object ReceiptLogic {

    data class Receipt(
        val receiptNo: String,
        val bookingId: String,
        val dateMillis: Long,
        val passengerName: String,
        val driverName: String,
        val vehicle: String,
        val pickup: String,
        val dropoff: String,
        val serviceType: String,
        val distanceKm: Double,
        val fareBeforeDiscount: Double,
        val discount: Double,
        val total: Double,
        val completed: Boolean
    )

    fun receiptNo(bookingId: String): String = "RUTA-" + bookingId.takeLast(8).uppercase(Locale.US)

    fun peso(amount: Double): String = "₱" + String.format(Locale.US, "%,.2f", amount)

    /** Settled shared fare when there is one, otherwise the quoted fare. */
    fun total(fare: Double, finalFare: Double): Double = if (finalFare > 0.0) finalFare else fare

    fun fromFields(
        bookingId: String,
        f: Map<String, Any?>,
        driverName: String,
        vehicle: String
    ): Receipt {
        fun num(k: String) = (f[k] as? Number)?.toDouble() ?: 0.0
        fun str(k: String) = (f[k] as? String).orEmpty()
        val total = total(num("fare"), num("finalFare"))
        val discount = num("discountAmount").coerceAtLeast(0.0)
        val shared = (f["isShared"] as? Boolean) == true
        val before = num("fareBeforeDiscount").takeIf { it > 0.0 } ?: (total + discount)
        return Receipt(
            receiptNo = receiptNo(bookingId),
            bookingId = bookingId,
            dateMillis = (f["createdAt"] as? Number)?.toLong() ?: (f["timestamp"] as? Number)?.toLong() ?: 0L,
            passengerName = str("passengerName").ifEmpty { "Passenger" },
            driverName = driverName.ifEmpty { str("driverName").ifEmpty { "Your driver" } },
            vehicle = vehicle.ifEmpty { listOf(str("vehicleModel"), str("plateNumber")).filter { it.isNotEmpty() }.joinToString(" • ") },
            pickup = str("pickupAddress").ifEmpty { "N/A" },
            dropoff = str("dropoffAddress").ifEmpty { "N/A" },
            serviceType = if (shared) "Rideshare" else "Solo",
            distanceKm = num("distanceMeters") / 1000.0,
            fareBeforeDiscount = before,
            discount = discount,
            total = total,
            completed = str("status") == "COMPLETED"
        )
    }

    fun dateText(millis: Long, zoneId: String = "Asia/Manila"): String {
        if (millis <= 0L) return "N/A"
        val f = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US)
        f.timeZone = TimeZone.getTimeZone(zoneId)
        return f.format(Date(millis))
    }

    fun subject(r: Receipt): String = "Your RUTA receipt ${r.receiptNo} • ${peso(r.total)}"

    /** Plain text for the in-app receipt dialog. */
    fun plainText(r: Receipt): String = buildString {
        appendLine("Receipt ${r.receiptNo}")
        appendLine(dateText(r.dateMillis))
        appendLine()
        appendLine("From: ${r.pickup}")
        appendLine("To: ${r.dropoff}")
        if (r.distanceKm > 0.0) appendLine("Distance: ${String.format(Locale.US, "%.1f", r.distanceKm)} km")
        appendLine("Type: ${r.serviceType}")
        appendLine("Driver: ${r.driverName}" + if (r.vehicle.isNotEmpty()) " (${r.vehicle})" else "")
        appendLine()
        if (r.discount > 0.0) {
            appendLine("Fare: ${peso(r.fareBeforeDiscount)}")
            appendLine("ID discount: -${peso(r.discount)}")
        }
        append("Total: ${peso(r.total)}")
    }

    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun html(r: Receipt): String {
        fun row(label: String, value: String) =
            """<tr><td style="padding:6px 0;color:#868e96;font-size:13px;">${escape(label)}</td><td style="padding:6px 0;text-align:right;color:#1a1d1e;font-size:14px;">${escape(value)}</td></tr>"""
        val discountRows = if (r.discount > 0.0)
            row("Fare", peso(r.fareBeforeDiscount)) + row("ID discount", "-" + peso(r.discount)) else ""
        return """
            <!DOCTYPE html><html><body style="font-family:Arial,sans-serif;background:#f8f9fa;padding:20px;">
            <div style="max-width:520px;margin:0 auto;background:#fff;padding:24px;border-radius:12px;">
              <h2 style="color:#6200EE;margin:0 0 4px;">RUTA</h2>
              <p style="color:#495057;margin:0 0 16px;">Thanks for riding, ${escape(r.passengerName)}. Here is your receipt.</p>
              <table style="width:100%;border-collapse:collapse;">
                ${row("Receipt no.", r.receiptNo)}
                ${row("Date", dateText(r.dateMillis))}
                ${row("From", r.pickup)}
                ${row("To", r.dropoff)}
                ${row("Type", r.serviceType)}
                ${row("Driver", r.driverName + if (r.vehicle.isNotEmpty()) " (${r.vehicle})" else "")}
                $discountRows
                <tr><td style="padding:12px 0 0;font-weight:bold;border-top:1px solid #dee2e6;">Total</td>
                    <td style="padding:12px 0 0;text-align:right;font-weight:bold;font-size:18px;border-top:1px solid #dee2e6;">${escape(peso(r.total))}</td></tr>
              </table>
              <p style="color:#868e96;font-size:12px;margin-top:20px;">This is an electronic receipt generated by the RUTA app. Keep it for your records.</p>
            </div></body></html>
        """.trimIndent()
    }
}
