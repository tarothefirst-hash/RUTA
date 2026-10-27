package com.ruta.app.util

import com.ruta.app.util.CashInRules.Result
import org.junit.Assert.*
import org.junit.Test

class CashInRulesTest {
    @Test fun acceptsTheWholeRange() {
        assertEquals(Result.Valid(20.0), CashInRules.parse("20"))
        assertEquals(Result.Valid(10_000.0), CashInRules.parse("10,000"))
        assertEquals(Result.Valid(1234.5), CashInRules.parse("₱1,234.50"))
    }

    @Test fun rejectsOutOfRange() {
        assertTrue(CashInRules.parse("19.99") is Result.Invalid)
        assertTrue(CashInRules.parse("10000.01") is Result.Invalid)
        assertTrue(CashInRules.parse("0") is Result.Invalid)
        assertTrue(CashInRules.parse("-50") is Result.Invalid)
    }

    @Test fun rejectsGarbageAndTreatsBlankAsEmpty() {
        assertTrue(CashInRules.parse("abc") is Result.Invalid)
        assertEquals(Result.Empty, CashInRules.parse("  "))
        assertEquals(Result.Empty, CashInRules.parse(null))
    }

    @Test fun roundsToCentavos() =
        assertEquals(Result.Valid(100.13), CashInRules.parse("100.126"))
}
