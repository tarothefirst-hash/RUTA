package com.ruta.app.util

import org.junit.Assert.*
import org.junit.Test

class ReceiptLogicTest {
    private val base = mapOf<String, Any?>(
        "passengerName" to "Ana <b>Cruz</b>", "pickupAddress" to "A", "dropoffAddress" to "B",
        "fare" to 80.0, "createdAt" to 1_700_000_000_000L, "status" to "COMPLETED", "distanceMeters" to 4200.0
    )

    @Test fun receiptNumberIsStableAndUppercase() =
        assertEquals("RUTA-ABCD1234", ReceiptLogic.receiptNo("-Nxyzabcd1234".lowercase()))

    @Test fun finalFareWinsForSharedRides() {
        assertEquals(55.0, ReceiptLogic.total(80.0, 55.0), 0.001)
        assertEquals(80.0, ReceiptLogic.total(80.0, 0.0), 0.001)
    }

    @Test fun discountIsShownOnlyWhenPresent() {
        val plain = ReceiptLogic.fromFields("b1", base, "Dan", "Vios • ABC123")
        assertEquals(0.0, plain.discount, 0.0)
        assertFalse(ReceiptLogic.plainText(plain).contains("discount"))

        val disc = ReceiptLogic.fromFields("b1", base + mapOf("discountAmount" to 20.0, "fareBeforeDiscount" to 100.0), "Dan", "")
        assertEquals(100.0, disc.fareBeforeDiscount, 0.001)
        assertTrue(ReceiptLogic.plainText(disc).contains("ID discount: -₱20.00"))
    }

    @Test fun sharedLabelAndHtmlEscaping() {
        val r = ReceiptLogic.fromFields("b1", base + ("isShared" to true), "Dan", "")
        assertEquals("Rideshare", r.serviceType)
        val html = ReceiptLogic.html(r)
        assertFalse(html.contains("<b>Cruz"))
        assertTrue(html.contains("&lt;b&gt;"))
    }

    @Test fun pesoFormatting() = assertEquals("₱1,234.50", ReceiptLogic.peso(1234.5))

    @Test fun missingFieldsFallBack() {
        val r = ReceiptLogic.fromFields("b1", emptyMap(), "", "")
        assertEquals("Passenger", r.passengerName)
        assertEquals("N/A", r.pickup)
        assertEquals("Your driver", r.driverName)
    }
}
