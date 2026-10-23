package com.ruta.app.util

import android.location.Location
import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

/**
 * RideshareManager
 * Handles passenger matching compatibility (strict 6000m gap & bearing threshold)
 * and global stop sequence optimization.
 */
object RideshareManager {

    enum class StopType { PICKUP, DROPOFF }

    data class RideshareCandidate(
        val bookingId: String,
        val passengerId: String,
        val pickup: LatLng,
        val dropoff: LatLng
    )

    data class RideshareStop(
        val bookingId: String,
        val type: StopType,
        val location: LatLng
    )

    // ---------------------------------------------------------------------------
    // 1. MATCHING LOGIC
    // ---------------------------------------------------------------------------
    object Matcher {
        internal const val MAX_PICKUP_GAP_METERS = 2000.0
        internal const val MAX_DROPOFF_GAP_METERS = 2000.0
        internal const val MAX_DIRECTION_ANGLE_DEGREES = 45.0

        fun isCompatible(a: RideshareCandidate, b: RideshareCandidate): Boolean {
            return isCompatible(
                samePassenger = a.passengerId == b.passengerId,
                bearingDifferenceDegrees = angleDifference(
                    bearing(a.pickup, a.dropoff),
                    bearing(b.pickup, b.dropoff)
                ),
                pickupGapMeters = distanceMeters(a.pickup, b.pickup),
                dropoffGapMeters = distanceMeters(a.dropoff, b.dropoff)
            )
        }

        internal fun isCompatible(
            samePassenger: Boolean,
            bearingDifferenceDegrees: Double,
            pickupGapMeters: Double,
            dropoffGapMeters: Double
        ): Boolean {
            if (samePassenger) return false
            if (bearingDifferenceDegrees > MAX_DIRECTION_ANGLE_DEGREES) return false
            if (pickupGapMeters > MAX_PICKUP_GAP_METERS) return false
            if (dropoffGapMeters > MAX_DROPOFF_GAP_METERS) return false
            return true
        }

        fun findBestMatch(newRequest: RideshareCandidate, pendingPool: List<RideshareCandidate>): RideshareCandidate? {
            return pendingPool
                .filter { isCompatible(newRequest, it) }
                .minByOrNull {
                    distanceMeters(newRequest.pickup, it.pickup) + distanceMeters(newRequest.dropoff, it.dropoff)
                }
        }

        private fun bearing(from: LatLng, to: LatLng): Double {
            val lat1 = Math.toRadians(from.latitude)
            val lat2 = Math.toRadians(to.latitude)
            val dLng = Math.toRadians(to.longitude - from.longitude)

            val y = sin(dLng) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        internal fun angleDifference(b1: Double, b2: Double): Double {
            var delta = abs(b1 - b2)
            if (delta > 180.0) delta = 360.0 - delta
            return delta
        }

        private fun distanceMeters(a: LatLng, b: LatLng): Double {
            val results = FloatArray(1)
            Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
            return results[0].toDouble()
        }
    }

    // ---------------------------------------------------------------------------
    // 2. STOP SEQUENCING (OPTIMIZED ROUTE PATH)
    // ---------------------------------------------------------------------------
    object Sequencer {

        fun sequenceStops(driverStart: LatLng, candidates: List<RideshareCandidate>): List<RideshareStop> =
            sequenceStopsWithDistance(driverStart, candidates) { a, b -> distanceMeters(a, b) }

        internal fun sequenceStopsWithDistance(
            driverStart: LatLng,
            candidates: List<RideshareCandidate>,
            distance: (LatLng, LatLng) -> Double
        ): List<RideshareStop> {
            if (candidates.isEmpty()) return emptyList()

            if (candidates.size == 1) {
                val c = candidates.first()
                return listOf(
                    RideshareStop(c.bookingId, StopType.PICKUP, c.pickup),
                    RideshareStop(c.bookingId, StopType.DROPOFF, c.dropoff)
                )
            }

            if (candidates.size == 2) {
                val a = candidates[0]
                val b = candidates[1]

                val pA = RideshareStop(a.bookingId, StopType.PICKUP, a.pickup)
                val dA = RideshareStop(a.bookingId, StopType.DROPOFF, a.dropoff)
                val pB = RideshareStop(b.bookingId, StopType.PICKUP, b.pickup)
                val dB = RideshareStop(b.bookingId, StopType.DROPOFF, b.dropoff)

                val validPermutations = listOf(
                    listOf(pA, pB, dA, dB),
                    listOf(pA, pB, dB, dA),
                    listOf(pA, dA, pB, dB),
                    listOf(pB, pA, dB, dA),
                    listOf(pB, pA, dA, dB),
                    listOf(pB, dB, pA, dA)
                )

                return validPermutations.minByOrNull { perm ->
                    calculateTotalTripDistance(driverStart, perm, distance)
                } ?: validPermutations.first()
            }

            val stops = mutableListOf<RideshareStop>()
            val remainingPickups = candidates.toMutableList()
            val awaitingDropoff = mutableListOf<RideshareCandidate>()
            var current = driverStart

            while (remainingPickups.isNotEmpty() || awaitingDropoff.isNotEmpty()) {
                val nearestPickup = remainingPickups.minByOrNull { distance(current, it.pickup) }
                val nearestDropoff = awaitingDropoff.minByOrNull { distance(current, it.dropoff) }

                val pickupDist = nearestPickup?.let { distance(current, it.pickup) } ?: Double.MAX_VALUE
                val dropoffDist = nearestDropoff?.let { distance(current, it.dropoff) } ?: Double.MAX_VALUE

                if (nearestPickup != null && pickupDist <= dropoffDist) {
                    stops.add(RideshareStop(nearestPickup.bookingId, StopType.PICKUP, nearestPickup.pickup))
                    current = nearestPickup.pickup
                    remainingPickups.remove(nearestPickup)
                    awaitingDropoff.add(nearestPickup)
                } else if (nearestDropoff != null) {
                    stops.add(RideshareStop(nearestDropoff.bookingId, StopType.DROPOFF, nearestDropoff.dropoff))
                    current = nearestDropoff.dropoff
                    awaitingDropoff.remove(nearestDropoff)
                }
            }

            return stops
        }

        private fun calculateTotalTripDistance(
            start: LatLng,
            path: List<RideshareStop>,
            distance: (LatLng, LatLng) -> Double
        ): Double {
            var total = 0.0
            var prev = start
            for (stop in path) {
                total += distance(prev, stop.location)
                prev = stop.location
            }
            return total
        }

        private fun distanceMeters(a: LatLng, b: LatLng): Double {
            val results = FloatArray(1)
            Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
            return results[0].toDouble()
        }
    }
}