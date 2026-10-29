package com.ruta.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteTrimTest {
    private val lats = doubleArrayOf(15.0000, 15.0010, 15.0020, 15.0030)
    private val lngs = DoubleArray(4) { 120.0 }

    @Test fun driverAtStartTrimsNothing() =
        assertEquals(-1, RouteTrim.passedIndex(lats, lngs, 15.0, 120.0))

    @Test fun driverPartWayReturnsNearestIndex() =
        assertEquals(2, RouteTrim.passedIndex(lats, lngs, 15.0020, 120.0))

    @Test fun offRouteTrimsNothing() =
        assertEquals(-1, RouteTrim.passedIndex(lats, lngs, 15.0020, 120.01))

    @Test fun searchLimitIgnoresLaterLoop() =
        assertEquals(-1, RouteTrim.passedIndex(lats, lngs, 15.0030, 120.0, searchLimit = 1))
}
