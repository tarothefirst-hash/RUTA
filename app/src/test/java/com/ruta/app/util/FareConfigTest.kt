package com.ruta.app.util

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FareConfigTest {
    @After fun reset() = FareConfig.update(FareRates.DEFAULT)

    @Test fun defaultsMatchTheOriginalPricing() {
        val r = FareRates.DEFAULT
        assertEquals(65.0, r.soloBase, 0.0); assertEquals(15.0, r.soloPerKm, 0.0); assertEquals(2.0, r.soloPerMin, 0.0)
        assertEquals(35.0, r.sharedBase, 0.0); assertEquals(7.5, r.sharedPerKm, 0.0); assertEquals(1.0, r.sharedPerMin, 0.0)
    }

    @Test fun calculatorsFollowTheAdminRates() {
        // 5 km, 10 min, no surge, no discount: 65 + 5*15 + 10*2 = 160
        assertEquals(160.0, FareCalculator.calculateFare(5.0, 10.0, VehicleType.SEDAN, false, 12), 0.0)
        FareConfig.update(FareRates(soloBase = 80.0, soloPerKm = 18.0, soloPerMin = 3.0))
        assertEquals(80.0 + 90.0 + 30.0, FareCalculator.calculateFare(5.0, 10.0, VehicleType.SEDAN, false, 12), 0.0)
        assertEquals(80.0, VehicleType.SEDAN.flagDown, 0.0)
    }

    @Test fun missingOrBadFieldsFallBackToDefaults() {
        val r = FareRates.fromMap(mapOf("soloBase" to 70L, "soloPerKm" to -5.0, "sharedBase" to "x"))
        assertEquals(70.0, r.soloBase, 0.0)
        assertEquals(15.0, r.soloPerKm, 0.0)
        assertEquals(35.0, r.sharedBase, 0.0)
        assertEquals(FareRates.DEFAULT, FareRates.fromMap(null))
    }

    @Test fun validationCatchesTyposAndInvertedShared() {
        assertNull(FareRates.validate(FareRates.DEFAULT))
        assertNotNull(FareRates.validate(FareRates(soloBase = 0.0)))
        assertNotNull(FareRates.validate(FareRates(soloPerKm = 5000.0)))
        assertNotNull(FareRates.validate(FareRates(soloPerKm = 10.0, sharedPerKm = 12.0)))
    }
}
