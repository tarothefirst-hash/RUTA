//package com.ruta.app.util
//
//import org.junit.Assert.assertEquals
//import org.junit.Assert.assertFalse
//import org.junit.Assert.assertNotNull
//import org.junit.Assert.assertNull
//import org.junit.Assert.assertTrue
//import org.junit.Test
//
///**
// * JVM unit tests for [FareDisplay].
// *
// * [FareDisplay] is pure Kotlin with no Android framework dependencies, so these run
// * on the local JVM with the plain JUnit4 dependency already declared — no Robolectric
// * and no new dependencies. Labels are supplied as literals here, exactly where the
// * activity supplies them from strings.xml, which is what keeps the helper Android-free.
// */
//class FareDisplayTest {
//
//    // Same shapes as the strings.xml resources, passed as plain literals.
//    private val labels = FareDisplay.Labels(
//        headlineFinal = "Final Fare: %1\$s",
//        detailBase = "Base %1\$s",
//        detailYourRide = "Your ride %1\$s",
//        detailShared = "Shared %1\$s",
//        detailDetour = "Detour %1\$s",
//        detailCredit = "Credit %1\$s",
//        detailAdjustedFrom = "Adjusted from earlier estimate %1\$s",
//        detailFinalising = "Final fare is being finalised…",
//        detailAwaitingFinalShare = "Final share is set when the trip ends • estimated %1\$s",
//        moneyFormat = "₱%1\$.2f"
//    )
//
//    // Case 1 — a settled fare 77.30 = base 45.00 + own ride 20.00 + shared 15.00 - credit 2.70.
//    // The headline carries the final figure and the breakdown carries every non-zero part.
//    @Test
//    fun settledFare_rendersHeadlineAndFullBreakdown() {
//        val rendered = FareDisplay.render(
//            FareDisplay.Snapshot(
//                finalFare = 77.30,
//                fareBase = 45.00,
//                fareSoloCharge = 20.00,
//                fareSharedCharge = 15.00,
//                fareDetourSurcharge = 0.0,
//                fareInconvenienceCredit = 2.70,
//                quotedFare = 77.30,
//                status = FareDisplay.STATUS_COMPLETED,
//                isShared = true
//            ),
//            labels
//        )
//
//        assertNotNull("headline should be set once a final fare exists", rendered.headline)
//        assertTrue(
//            "headline should contain the final fare 77.30, was '${rendered.headline}'",
//            rendered.headline!!.contains("77.30")
//        )
//
//        val detail = rendered.detail
//        assertNotNull("breakdown should be shown", detail)
//        assertTrue("breakdown should contain the base '${detail}'", detail!!.contains("₱45.00"))
//        assertTrue("breakdown should contain the own ride '${detail}'", detail.contains("₱20.00"))
//        assertTrue("breakdown should contain the shared portion '${detail}'", detail.contains("₱15.00"))
//        assertTrue("breakdown should contain the credit '${detail}'", detail.contains("₱2.70"))
//        assertTrue("breakdown should label the shared portion", detail.contains("Shared"))
//    }
//
//    // Case 2 — shared booking reaches a terminal status but the driver has not written
//    // the settled fare yet: show the short finalising message.
//    @Test
//    fun completedSharedWithoutFinalFare_rendersFinalising() {
//        val rendered = FareDisplay.render(
//            FareDisplay.Snapshot(
//                finalFare = 0.0,
//                fareBase = 0.0,
//                fareSoloCharge = 0.0,
//                fareSharedCharge = 0.0,
//                fareDetourSurcharge = 0.0,
//                fareInconvenienceCredit = 0.0,
//                quotedFare = 140.00,
//                status = FareDisplay.STATUS_COMPLETED,
//                isShared = true
//            ),
//            labels
//        )
//
//        assertNull("the quote should stay as the headline", rendered.headline)
//        assertEquals(labels.detailFinalising, rendered.detail)
//    }
//
//    // Case 3 — shared booking still running: keep the quote as the headline and quote the
//    // estimate on the new line, which says the final share is set when the trip ends.
//    @Test
//    fun runningSharedBooking_rendersRunningMessageQuotingEstimate() {
//        val rendered = FareDisplay.render(
//            FareDisplay.Snapshot(
//                finalFare = 0.0,
//                fareBase = 0.0,
//                fareSoloCharge = 0.0,
//                fareSharedCharge = 0.0,
//                fareDetourSurcharge = 0.0,
//                fareInconvenienceCredit = 0.0,
//                quotedFare = 140.00,
//                status = "IN_PROGRESS",
//                isShared = true
//            ),
//            labels
//        )
//
//        assertNull("the running quote must be left as the headline", rendered.headline)
//        val detail = rendered.detail
//        assertNotNull("running shared booking should show the running message", detail)
//        assertTrue("running message should quote the estimate '${detail}'", detail!!.contains("140.00"))
//        assertEquals(
//            String.format(java.util.Locale.US, labels.detailAwaitingFinalShare, "₱140.00"),
//            detail
//        )
//    }
//
//    // Case 4 — the settled fare differs from the quote: append the adjusted-from line.
//    @Test
//    fun finalFareDifferentFromQuote_includesAdjustedFromLine() {
//        val rendered = FareDisplay.render(
//            FareDisplay.Snapshot(
//                finalFare = 77.30,
//                fareBase = 45.00,
//                fareSoloCharge = 20.00,
//                fareSharedCharge = 15.00,
//                fareDetourSurcharge = 0.0,
//                fareInconvenienceCredit = 2.70,
//                quotedFare = 105.00,
//                status = FareDisplay.STATUS_COMPLETED,
//                isShared = true
//            ),
//            labels
//        )
//
//        val detail = rendered.detail
//        assertNotNull(detail)
//        assertTrue(
//            "should quote the earlier estimate '${detail}'",
//            detail!!.contains("Adjusted from earlier estimate ₱105.00")
//        )
//        assertFalse(
//            "the settled headline should not re-quote the old figure",
//            rendered.headline!!.contains("105.00")
//        )
//    }
//
//    // Case 5 — a solo ride never emits a split or shared line.
//    @Test
//    fun soloRide_neverEmitsSharedOrSplitLine() {
//        val rendered = FareDisplay.render(
//            FareDisplay.Snapshot(
//                finalFare = 89.00,
//                fareBase = 45.00,
//                fareSoloCharge = 44.00,
//                fareSharedCharge = 0.0,
//                fareDetourSurcharge = 0.0,
//                fareInconvenienceCredit = 0.0,
//                quotedFare = 89.00,
//                status = FareDisplay.STATUS_COMPLETED,
//                isShared = false
//            ),
//            labels
//        )
//
//        val detail = rendered.detail
//        assertNotNull(detail)
//        assertEquals("Base ₱45.00 + Your ride ₱44.00", detail)
//        assertFalse("no shared portion on a solo ride '${detail}'", detail!!.contains("Shared"))
//        assertFalse("no split wording on a solo ride '${detail}'", detail.contains("split"))
//        assertFalse("no credit on a solo ride '${detail}'", detail.contains("Credit"))
//    }
//
//    // A solo ride that is still running shows no new line at all — the card behaves
//    // exactly as it does today.
//    @Test
//    fun runningSoloBooking_hasNoDetailLine() {
//        val rendered = FareDisplay.render(
//            FareDisplay.Snapshot(
//                finalFare = 0.0,
//                fareBase = 0.0,
//                fareSoloCharge = 0.0,
//                fareSharedCharge = 0.0,
//                fareDetourSurcharge = 0.0,
//                fareInconvenienceCredit = 0.0,
//                quotedFare = 140.00,
//                status = "IN_PROGRESS",
//                isShared = false
//            ),
//            labels
//        )
//
//        assertNull(rendered.headline)
//        assertNull("solo detail line must stay hidden", rendered.detail)
//    }
//
//    // A settled fare whose breakdown fields are all absent/zero (an old record
//    // written before the breakdown keys existed) must still render without crashing.
//    // The absent base is omitted, so the line no longer reads "Base ₱0.00".
//    @Test
//    fun settledFare_withAbsentBase_omitsBaseLineWithoutCrash() {
//        val rendered = FareDisplay.render(
//            FareDisplay.Snapshot(
//                finalFare = 50.00,
//                fareBase = 0.0,
//                fareSoloCharge = 0.0,
//                fareSharedCharge = 0.0,
//                fareDetourSurcharge = 0.0,
//                fareInconvenienceCredit = 0.0,
//                quotedFare = 0.0,
//                status = FareDisplay.STATUS_COMPLETED,
//                isShared = true
//            ),
//            labels
//        )
//
//        assertNotNull(rendered.headline)
//        assertTrue(rendered.headline!!.contains("50.00"))
//        assertEquals("Your ride ₱0.00", rendered.detail)
//        assertFalse("an absent base must not render as Base ₱0.00", rendered.detail!!.contains("Base"))
//    }
//
//    // A cancelled shared trip must never show the finalising message — no final fare
//    // is coming, so the card keeps its current headline and hides the detail line.
//    @Test
//    fun cancelledSharedWithoutFinalFare_emitsNoFinalisingMessage() {
//        val rendered = FareDisplay.render(
//            FareDisplay.Snapshot(
//                finalFare = 0.0,
//                fareBase = 0.0,
//                fareSoloCharge = 0.0,
//                fareSharedCharge = 0.0,
//                fareDetourSurcharge = 0.0,
//                fareInconvenienceCredit = 0.0,
//                quotedFare = 140.00,
//                status = FareDisplay.STATUS_CANCELLED,
//                isShared = true
//            ),
//            labels
//        )
//
//        assertNull("a cancelled trip must keep its headline", rendered.headline)
//        assertNull("a cancelled trip must not promise a final fare", rendered.detail)
//        assertTrue(
//            "the finalising message must never appear on a cancelled trip",
//            rendered.detail != labels.detailFinalising
//        )
//    }
//
//    // The base line is conditional: absent (zero) hides it, present shows it.
//    @Test
//    fun settledFare_zeroBase_omitsBaseLine() {
//        val rendered = FareDisplay.render(
//            FareDisplay.Snapshot(
//                finalFare = 30.00,
//                fareBase = 0.0,
//                fareSoloCharge = 30.00,
//                fareSharedCharge = 0.0,
//                fareDetourSurcharge = 0.0,
//                fareInconvenienceCredit = 0.0,
//                quotedFare = 30.00,
//                status = FareDisplay.STATUS_COMPLETED,
//                isShared = false
//            ),
//            labels
//        )
//
//        assertEquals("Your ride ₱30.00", rendered.detail)
//        assertFalse("no zero base line should be shown '${rendered.detail}'", rendered.detail!!.contains("Base"))
//    }
//
//    @Test
//    fun settledFare_presentBase_showsBaseLine() {
//        val rendered = FareDisplay.render(
//            FareDisplay.Snapshot(
//                finalFare = 55.00,
//                fareBase = 45.00,
//                fareSoloCharge = 10.00,
//                fareSharedCharge = 0.0,
//                fareDetourSurcharge = 0.0,
//                fareInconvenienceCredit = 0.0,
//                quotedFare = 55.00,
//                status = FareDisplay.STATUS_COMPLETED,
//                isShared = false
//            ),
//            labels
//        )
//
//        assertEquals("Base ₱45.00 + Your ride ₱10.00", rendered.detail)
//        assertTrue("a present base must be shown '${rendered.detail}'", rendered.detail!!.contains("Base ₱45.00"))
//    }
//}
