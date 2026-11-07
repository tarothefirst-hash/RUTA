package com.ruta.app.util

import org.junit.Assert.*
import org.junit.Test

class TrustedContactPromptTest {
    @Test fun asksOnlyWhenNoContactsAndNotYetAsked() {
        assertTrue(SafetyLogic.shouldAskTrustedContact(0, false))
        assertFalse(SafetyLogic.shouldAskTrustedContact(0, true))
        assertFalse(SafetyLogic.shouldAskTrustedContact(1, false))
        assertFalse(SafetyLogic.shouldAskTrustedContact(3, false))
    }
}
