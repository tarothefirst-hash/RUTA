package com.ruta.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletLimitTest {
    @Test fun positiveAndSmallDebtCanAccept() {
        assertTrue(WalletLimit.canAccept(500.0))
        assertTrue(WalletLimit.canAccept(0.0))
        assertTrue(WalletLimit.canAccept(-99.99))
    }

    @Test fun atOrBeyondLimitIsBlocked() {
        assertFalse(WalletLimit.canAccept(-100.0))
        assertFalse(WalletLimit.canAccept(-250.0))
    }

    @Test fun roomLeft() {
        assertEquals(100.0, WalletLimit.roomLeft(0.0), 0.001)
        assertEquals(40.0, WalletLimit.roomLeft(-60.0), 0.001)
        assertEquals(0.0, WalletLimit.roomLeft(-300.0), 0.001)
    }

    @Test fun parsesFirebaseTypes() {
        assertEquals(12.0, WalletLimit.parse(12L), 0.001)
        assertEquals(-3.5, WalletLimit.parse(-3.5), 0.001)
        assertEquals(7.0, WalletLimit.parse("7"), 0.001)
        assertEquals(0.0, WalletLimit.parse(null), 0.001)
    }
}
