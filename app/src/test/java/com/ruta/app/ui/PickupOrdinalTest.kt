package com.ruta.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [pickupPositionOf], the pure ordinal computation extracted from
 * PassengerHomeActivity's trip-sequence listener.
 *
 * No Android or Firebase types are involved — the function consumes plain stop-record
 * maps and returns a plain value — so these run on the local JVM with the existing
 * JUnit4 dependency. They pin the exact bug that was fixed: the old implementation
 * reported the raw `sequenceOrder`, which counts drop-offs too, so a passenger whose
 * pickup followed an interleaved drop-off was announced as Stop #3 instead of #2.
 */
class PickupOrdinalTest {

    private fun stop(
        passengerId: String?,
        label: String = "",
        isPickup: Boolean = true
    ): Map<String, Any?> = mapOf(
        "passengerId" to passengerId,
        "label" to label,
        "isPickup" to isPickup
    )

    // Normal case — the only pickup, so the passenger is Stop #1.
    @Test
    fun singlePassengerFirstPickup_isOrdinalOne() {
        val sequence = listOf(stop("passengerA", "P1", isPickup = true))

        val position = pickupPositionOf(sequence, "passengerA")

        assertEquals(1, position?.ordinal)
        assertEquals("P1", position?.label)
    }

    // The exact regression: order is pickupA, dropoffA, pickupB. Drop-offs must not
    // advance the counter, so passengerB is the SECOND pickup — the old code read
    // `sequenceOrder` = 3 here and announced Stop #3.
    @Test
    fun interleavedDropoff_secondPassenger_isOrdinalTwo() {
        val sequence = listOf(
            stop("passengerA", "P1", isPickup = true),
            stop("passengerA", "D1", isPickup = false),
            stop("passengerB", "P2", isPickup = true)
        )

        assertEquals(2, pickupPositionOf(sequence, "passengerB")?.ordinal)
        assertEquals("P2", pickupPositionOf(sequence, "passengerB")?.label)
    }

    // A passenger present only as a drop-off (no pickup entry) yields null: production
    // then shows no Toast, matching the pre-existing contract.
    @Test
    fun passengerWithNoPickupEntry_returnsNull() {
        val sequence = listOf(
            stop("passengerA", "P1", isPickup = true),
            stop("passengerB", "D1", isPickup = false)
        )

        assertNull(pickupPositionOf(sequence, "passengerB"))
    }

    // Empty stop list — null, so the listener returns without announcing.
    @Test
    fun emptySequence_returnsNull() {
        assertNull(pickupPositionOf(emptyList(), "passengerA"))
    }

    // A second pickup entry for the same passenger: the walk stops at the FIRST match,
    // so the ordinal is the first pickup's position, not the second one's.
    @Test
    fun duplicatePickupEntry_firstMatchWins() {
        val sequence = listOf(
            stop("passengerA", "P1", isPickup = true),
            stop("passengerB", "P1", isPickup = true),
            stop("passengerA", "P2", isPickup = true)
        )

        assertEquals(1, pickupPositionOf(sequence, "passengerA")?.ordinal)
    }

    // Only drop-offs — nobody has a pickup position, so null.
    @Test
    fun dropoffsOnly_returnsNull() {
        val sequence = listOf(
            stop("passengerA", "D1", isPickup = false),
            stop("passengerB", "D2", isPickup = false)
        )

        assertNull(pickupPositionOf(sequence, "passengerA"))
    }

    // Missing `isPickup` defaults to true (the same default the production reader uses),
    // so an entry without the flag still counts as a pickup.
    @Test
    fun missingIsPickupFlag_defaultsToPickup() {
        val sequence = listOf(mapOf<String, Any?>("passengerId" to "passengerA", "label" to "P1"))

        assertEquals(1, pickupPositionOf(sequence, "passengerA")?.ordinal)
    }
}
