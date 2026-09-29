package com.ruta.app.util

import android.location.Location
import com.google.android.gms.maps.model.LatLng
import kotlin.math.*
import android.util.Log

/**
 * RideshareManager
 * -----------------
 * Main utility container for RUTA's rideshare and route management logic.
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
    // 1. MATCHING
    // ---------------------------------------------------------------------------
    object Matcher {
        private const val MAX_PICKUP_DETOUR_METERS = 6000.0
        private const val MAX_DROPOFF_DETOUR_METERS = 6000.0
        private const val MAX_DIRECTION_ANGLE_DEGREES = 45.0

        fun isCompatible(a: RideshareCandidate, b: RideshareCandidate): Boolean {
            Log.d("RUTA_MATCH", "Checking compatibility between Booking A: ${a.bookingId} and Booking B: ${b.bookingId}")

            if (a.passengerId == b.passengerId) {
                Log.d("RUTA_MATCH", "REJECTED: Same passengerId (${a.passengerId})")
                return false
            }

            val bearingA = bearing(a.pickup, a.dropoff)
            val bearingB = bearing(b.pickup, b.dropoff)
            val diffBearing = angleDifference(bearingA, bearingB)
            Log.d("RUTA_MATCH", "Bearing A: $bearingA, Bearing B: $bearingB, Diff: $diffBearing (Max: $MAX_DIRECTION_ANGLE_DEGREES)")

            if (diffBearing > MAX_DIRECTION_ANGLE_DEGREES) {
                Log.d("RUTA_MATCH", "REJECTED: Direction angle difference too large")
                return false
            }

            val pickupGap = distanceMeters(a.pickup, b.pickup)
            Log.d("RUTA_MATCH", "Pickup distance gap: ${pickupGap}m (Max: ${MAX_PICKUP_DETOUR_METERS}m)")
            if (pickupGap > MAX_PICKUP_DETOUR_METERS) {
                Log.d("RUTA_MATCH", "REJECTED: Pickup gap too far")
                return false
            }

            val dropoffGap = distanceMeters(a.dropoff, b.dropoff)
            Log.d("RUTA_MATCH", "Dropoff distance gap: ${dropoffGap}m (Max: ${MAX_DROPOFF_DETOUR_METERS}m)")
            if (dropoffGap > MAX_DROPOFF_DETOUR_METERS) {
                Log.d("RUTA_MATCH", "REJECTED: Dropoff gap too far")
                return false
            }

            Log.d("RUTA_MATCH", "SUCCESS: Booking A and B are COMPATIBLE!")
            return true
        }

        fun findBestMatch(newRequest: RideshareCandidate, pendingPool: List<RideshareCandidate>): RideshareCandidate? {
            return pendingPool
                .filter { isCompatible(newRequest, it) }
                .minByOrNull {
                    distanceMeters(newRequest.pickup, it.pickup) + distanceMeters(newRequest.dropoff, it.dropoff)
                }
        }

        fun findAllCompatible(newRequest: RideshareCandidate, pendingPool: List<RideshareCandidate>): List<RideshareCandidate> {
            return pendingPool.filter { isCompatible(newRequest, it) }
        }

        private fun bearing(from: LatLng, to: LatLng): Double {
            val lat1 = Math.toRadians(from.latitude)
            val lat2 = Math.toRadians(to.latitude)
            val dLng = Math.toRadians(to.longitude - from.longitude)

            val y = sin(dLng) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        private fun angleDifference(b1: Double, b2: Double): Double {
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
    // 2. STOP SEQUENCING (GLOBAL PATH OPTIMIZATION)
    // ---------------------------------------------------------------------------
    object Sequencer {

        fun sequenceStops(driverStart: LatLng, candidates: List<RideshareCandidate>): List<RideshareStop> {
            if (candidates.isEmpty()) return emptyList()

            // Single candidate fallback
            if (candidates.size == 1) {
                val c = candidates.first()
                return listOf(
                    RideshareStop(c.bookingId, StopType.PICKUP, c.pickup),
                    RideshareStop(c.bookingId, StopType.DROPOFF, c.dropoff)
                )
            }

            // 2-Passenger shared ride path evaluation
            if (candidates.size == 2) {
                val a = candidates[0]
                val b = candidates[1]

                val pA = RideshareStop(a.bookingId, StopType.PICKUP, a.pickup)
                val dA = RideshareStop(a.bookingId, StopType.DROPOFF, a.dropoff)
                val pB = RideshareStop(b.bookingId, StopType.PICKUP, b.pickup)
                val dB = RideshareStop(b.bookingId, StopType.DROPOFF, b.dropoff)

                // 4 Valid logical permutations where Pickups precede Dropoffs
                val validPermutations = listOf(
                    listOf(pA, pB, dA, dB),
                    listOf(pA, pB, dB, dA),
                    listOf(pA, dA, pB, dB),
                    listOf(pB, pA, dB, dA),
                    listOf(pB, pA, dA, dB),
                    listOf(pB, dB, pA, dA)
                )

                return validPermutations.minByOrNull { perm ->
                    calculateTotalTripDistance(driverStart, perm)
                } ?: validPermutations.first()
            }

            // Fallback strategy for 3+ passengers: Global insertion approach
            val stops = mutableListOf<RideshareStop>()
            val remainingPickups = candidates.toMutableList()
            val awaitingDropoff = mutableListOf<RideshareCandidate>()
            var current = driverStart

            while (remainingPickups.isNotEmpty() || awaitingDropoff.isNotEmpty()) {
                val nearestPickup = remainingPickups.minByOrNull { distanceMeters(current, it.pickup) }
                val nearestDropoff = awaitingDropoff.minByOrNull { distanceMeters(current, it.dropoff) }

                val pickupDist = nearestPickup?.let { distanceMeters(current, it.pickup) } ?: Double.MAX_VALUE
                val dropoffDist = nearestDropoff?.let { distanceMeters(current, it.dropoff) } ?: Double.MAX_VALUE

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

        private fun calculateTotalTripDistance(start: LatLng, path: List<RideshareStop>): Double {
            var total = 0.0
            var prev = start
            for (stop in path) {
                total += distanceMeters(prev, stop.location)
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

    // ---------------------------------------------------------------------------
    // 3. FAIR FARE SPLITTING
    // ---------------------------------------------------------------------------
    object FareSplitter {
        fun splitByOnboardDuration(
            totalSharedFare: Double,
            stops: List<RideshareStop>,
            legDurationsSeconds: List<Int>
        ): Map<String, Double> {
            require(stops.size == legDurationsSeconds.size) {
                "legDurationsSeconds must have exactly one entry per stop"
            }

            val active = mutableSetOf<String>()
            val creditedSeconds = mutableMapOf<String, Double>()
            var totalWeightedSeconds = 0.0

            for (i in stops.indices) {
                val legSeconds = legDurationsSeconds[i].toDouble()

                if (active.isNotEmpty()) {
                    val perRiderShare = legSeconds / active.size
                    active.forEach { id ->
                        creditedSeconds[id] = (creditedSeconds[id] ?: 0.0) + perRiderShare
                    }
                    totalWeightedSeconds += legSeconds
                }

                when (stops[i].type) {
                    StopType.PICKUP -> active.add(stops[i].bookingId)
                    StopType.DROPOFF -> active.remove(stops[i].bookingId)
                }
            }

            if (totalWeightedSeconds <= 0.0) {
                val distinctIds = stops.map { it.bookingId }.distinct()
                val equalShare = totalSharedFare / distinctIds.size
                return distinctIds.associateWith { round2(equalShare) }
            }

            return creditedSeconds.mapValues { (_, seconds) ->
                round2(totalSharedFare * (seconds / totalWeightedSeconds))
            }
        }

        private fun round2(value: Double): Double = round(value * 100.0) / 100.0
    }

    // ---------------------------------------------------------------------------
    // 4. COMBINED ROUTE BUILDER
    // ---------------------------------------------------------------------------
    object RouteBuilder {
        fun combineLegs(legPolylines: List<List<LatLng>>): List<LatLng> {
            val combined = mutableListOf<LatLng>()
            for (leg in legPolylines) {
                if (leg.isEmpty()) continue
                if (combined.isNotEmpty() && combined.last() == leg.first()) {
                    combined.addAll(leg.drop(1))
                } else {
                    combined.addAll(leg)
                }
            }
            return combined
        }
    }
}