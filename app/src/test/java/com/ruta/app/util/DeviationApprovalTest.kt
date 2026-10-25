package com.ruta.app.util

import com.ruta.app.util.DeviationApproval.Outcome
import org.junit.Assert.*
import org.junit.Test

class DeviationApprovalTest {
    @Test fun noAnswerIsPending() {
        assertEquals(Outcome.PENDING, DeviationApproval.resolve(emptyList()))
        assertEquals(Outcome.PENDING, DeviationApproval.resolve(listOf(null, null)))
    }

    @Test fun firstOkApprovesForEveryone() {
        assertEquals(Outcome.APPROVED, DeviationApproval.resolve(listOf("OK", null)))
        assertEquals(Outcome.APPROVED, DeviationApproval.resolve(listOf(null, "ok")))
    }

    @Test fun anyEmergencyBlocksEvenAfterOk() {
        assertEquals(Outcome.BLOCKED, DeviationApproval.resolve(listOf("EMERGENCY", null)))
        assertEquals(Outcome.BLOCKED, DeviationApproval.resolve(listOf("OK", "EMERGENCY")))
    }

    @Test fun timeoutWindow() {
        assertFalse(DeviationApproval.timedOut(1_000, 50_000))
        assertTrue(DeviationApproval.timedOut(1_000, 91_000))
        assertFalse(DeviationApproval.timedOut(0, 999_999))
    }
}
