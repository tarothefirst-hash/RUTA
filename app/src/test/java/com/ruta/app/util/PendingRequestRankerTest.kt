package com.ruta.app.util

import com.google.android.gms.maps.model.LatLng
import com.ruta.app.util.PendingRequestRanker.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for the Nearest Neighbor ordering of pending requests on the
 * driver side. Expected orders below were worked out by hand from the
 * coordinates, using the same deterministic Euclidean stand-in distance the
 * other algorithm tests use.
 */
class PendingRequestRankerTest {

    private val euclidean: (LatLng, LatLng) -> Double = { p, q ->
        val dLat = p.latitude - q.latitude
        val dLng = p.longitude - q.longitude
        kotlin.math.sqrt(dLat * dLat + dLng * dLng)
    }

    private val driver = LatLng(0.0, 0.0)

    @Test
    fun ordersRequestsNearestPickupFirst() {
        // Distances from (0,0): near = 1, mid = 3, far = 5 (3-4-5 triangle).
        val far = Candidate("far", LatLng(3.0, 4.0))
        val near = Candidate("near", LatLng(0.0, 1.0))
        val mid = Candidate("mid", LatLng(3.0, 0.0))

        val order = PendingRequestRanker.nearestFirst(driver, listOf(far, near, mid), euclidean)

        assertEquals(listOf("near", "mid", "far"), order.map { it.bookingKey })
    }

    @Test
    fun nearestReturnsTheClosestPickup() {
        val a = Candidate("a", LatLng(10.0, 0.0))
        val b = Candidate("b", LatLng(0.0, 2.0))

        assertEquals("b", PendingRequestRanker.nearest(driver, listOf(a, b), euclidean)?.bookingKey)
    }

    @Test
    fun tieIsBrokenByBookingKeySoTheOrderIsStable() {
        // Both are exactly 1 away.
        val x = Candidate("x", LatLng(1.0, 0.0))
        val w = Candidate("w", LatLng(0.0, 1.0))

        assertEquals("w", PendingRequestRanker.nearest(driver, listOf(x, w), euclidean)?.bookingKey)
        assertEquals("w", PendingRequestRanker.nearest(driver, listOf(w, x), euclidean)?.bookingKey)
    }

    @Test
    fun noRequestsMeansNoNearest() {
        assertNull(PendingRequestRanker.nearest(driver, emptyList(), euclidean))
    }

    @Test
    fun singleRequestIsReturnedAsIs() {
        val only = Candidate("only", LatLng(7.0, 7.0))
        assertEquals("only", PendingRequestRanker.nearest(driver, listOf(only), euclidean)?.bookingKey)
    }
}
