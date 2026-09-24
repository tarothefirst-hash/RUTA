package com.ruta.app.util

import android.os.Handler
import android.os.Looper
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.Executors

/**
 * RideshareFlowManager
 * --------------------
 * Owns the ENTIRE rideshare booking path, start to finish, so the regular booking
 * path in LocationSelectionActivity stays dumb and simple (write booking -> status
 * REQUESTED -> done, no matching at all).
 */
class RideshareFlowManager(
    private val myBookingId: String,
    private val myPassengerId: String,
    private val myPickup: LatLng,
    private val myDropoff: LatLng,
    private val apiKey: String,
    private val maxPickupMinutesApart: Double = 10.0,
    private val maxDropoffMinutesApart: Double = 10.0,
    private val matchingWindowMillis: Long = 300_000L,
    private val onStateChanged: (RideshareState) -> Unit = {}
) {

    enum class RideshareState {
        SEARCHING_FOR_CO_PASSENGER,
        MATCHED,
        NO_MATCH_PROCEEDING_SOLO
    }

    private val database = FirebaseDatabase.getInstance().reference
    private val handler = Handler(Looper.getMainLooper())

    private var matchingListener: ValueEventListener? = null
    private var timeoutRunnable: Runnable? = null
    private var finished = false

    fun start() {
        if (matchingListener != null) return
        onStateChanged(RideshareState.SEARCHING_FOR_CO_PASSENGER)

        val myCandidate = RideshareManager.RideshareCandidate(
            bookingId = myBookingId,
            passengerId = myPassengerId,
            pickup = myPickup,
            dropoff = myDropoff
        )

        matchingListener = database.child("bookings")
            .orderByChild("status").equalTo("MATCHING")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (finished) return

                    val pool = snapshot.children.mapNotNull { child ->
                        val id = child.key ?: return@mapNotNull null
                        if (id == myBookingId) return@mapNotNull null

                        val isShared = child.child("isShared").getValue(Boolean::class.java) == true
                        if (!isShared) return@mapNotNull null

                        val pLat = child.child("pickupLat").getValue(Double::class.java) ?: return@mapNotNull null
                        val pLng = child.child("pickupLng").getValue(Double::class.java) ?: return@mapNotNull null
                        val dLat = child.child("dropoffLat").getValue(Double::class.java) ?: return@mapNotNull null
                        val dLng = child.child("dropoffLng").getValue(Double::class.java) ?: return@mapNotNull null

                        RideshareManager.RideshareCandidate(
                            bookingId = id,
                            passengerId = child.child("passengerId").getValue(String::class.java) ?: "",
                            pickup = LatLng(pLat, pLng),
                            dropoff = LatLng(dLat, dLng)
                        )
                    }

                    android.util.Log.d("RUTA_MATCH", "Found ${pool.size} candidate(s) in MATCHING pool.")

                    val match = RideshareManager.Matcher.findBestMatch(
                        newRequest = myCandidate,
                        pendingPool = pool
                    )

                    if (match != null) {
                        android.util.Log.d("RUTA_MATCH", "Match found with bookingId: ${match.bookingId}")
                        lockInMatch(match.bookingId)
                    } else {
                        android.util.Log.d("RUTA_MATCH", "No compatible match in pool of size ${pool.size}")
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })

        timeoutRunnable = Runnable { promoteToSoloIfStillMatching() }
        handler.postDelayed(timeoutRunnable!!, matchingWindowMillis)
    }

    private fun lockInMatch(otherBookingId: String) {
        if (finished) return

        val pairKey = if (myBookingId < otherBookingId) {
            "${myBookingId}_${otherBookingId}"
        } else {
            "${otherBookingId}_${myBookingId}"
        }

        val lockRef = database.child("matchLocks").child(pairKey)

        lockRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(mutableData: MutableData): Transaction.Result {
                if (mutableData.value != null) {
                    return Transaction.abort()
                }
                mutableData.value = true
                return Transaction.success(mutableData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                currentData: DataSnapshot?
            ) {
                if (error != null) {
                    android.util.Log.e("RUTA_MATCH", "Lock transaction error: ${error.message}")
                    return
                }

                if (committed) {
                    android.util.Log.d("RUTA_MATCH", "Lock acquired! Flipping status to MATCHED...")

                    finished = true
                    stop()

                    val updates = hashMapOf<String, Any>(
                        "bookings/$myBookingId/status" to "MATCHED",
                        "bookings/$otherBookingId/status" to "MATCHED",
                        "bookings/$myBookingId/tripGroupId" to pairKey,
                        "bookings/$otherBookingId/tripGroupId" to pairKey
                    )

                    database.updateChildren(updates).addOnSuccessListener {
                        android.util.Log.d("RUTA_MATCH", "Firebase status successfully set to MATCHED!")
                        onStateChanged(RideshareState.MATCHED)
                        solveAndPersistRoute(otherBookingId, pairKey)
                    }.addOnFailureListener { e ->
                        android.util.Log.e("RUTA_MATCH", "Failed to update booking status: ${e.message}")
                    }
                } else {
                    android.util.Log.d("RUTA_MATCH", "Lock acquisition aborted (already claimed).")
                }
            }
        })
    }

    private fun solveAndPersistRoute(otherBookingId: String, groupId: String) {
        // Run the ENTIRE read and optimization off the UI thread
        Executors.newSingleThreadExecutor().execute {
            database.child("bookings").child(otherBookingId).get()
                .addOnSuccessListener { snap ->
                    val otherLatP = snap.child("pickupLat").getValue(Double::class.java) ?: return@addOnSuccessListener
                    val otherLngP = snap.child("pickupLng").getValue(Double::class.java) ?: return@addOnSuccessListener
                    val otherLatD = snap.child("dropoffLat").getValue(Double::class.java) ?: return@addOnSuccessListener
                    val otherLngD = snap.child("dropoffLng").getValue(Double::class.java) ?: return@addOnSuccessListener
                    val otherPassengerId = snap.child("passengerId").getValue(String::class.java) ?: ""

                    val me = RideshareManager.RideshareCandidate(
                        myBookingId,
                        myPassengerId,
                        myPickup,
                        myDropoff
                    )

                    val other = RideshareManager.RideshareCandidate(
                        bookingId = otherBookingId,
                        passengerId = otherPassengerId,
                        pickup = LatLng(otherLatP, otherLngP),
                        dropoff = LatLng(otherLatD, otherLngD)
                    )

                    val winner = RideshareRouteOptimizer.findOptimalSequence(me, other, apiKey)
                        ?: return@addOnSuccessListener

                    val tripGroupData = mapOf(
                        "bookingIds" to listOf(myBookingId, otherBookingId),
                        "stopOrder" to winner.orderedStops.map { stop ->
                            mapOf(
                                "bookingId" to stop.bookingId,
                                "type" to stop.type.name,
                                "lat" to stop.location.latitude,
                                "lng" to stop.location.longitude
                            )
                        },
                        "plannedDistanceMeters" to winner.totalDistanceMeters,
                        "plannedDurationSeconds" to winner.totalDurationSeconds,
                        "createdAt" to System.currentTimeMillis()
                    )

                    database.child("tripGroups").child(groupId).setValue(tripGroupData)
                }
        }
    }

    private fun promoteToSoloIfStillMatching() {
        if (finished) return

        database.child("bookings").child(myBookingId).child("status").get()
            .addOnSuccessListener { snap ->
                if (snap.getValue(String::class.java) == "MATCHING") {
                    database.child("bookings").child(myBookingId)
                        .updateChildren(mapOf("status" to "REQUESTED"))

                    finished = true
                    onStateChanged(RideshareState.NO_MATCH_PROCEEDING_SOLO)
                    stop()
                }
            }
    }

    fun stop() {
        matchingListener?.let {
            database.child("bookings").orderByChild("status").equalTo("MATCHING")
                .removeEventListener(it)
        }
        matchingListener = null
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    fun cancelIfStillMatching() {
        database.child("bookings").child(myBookingId).child("status").get()
            .addOnSuccessListener { snap ->
                if (snap.getValue(String::class.java) == "MATCHING") {
                    database.child("bookings").child(myBookingId)
                        .updateChildren(mapOf("status" to "CANCELLED"))
                }
            }
        finished = true
        stop()
    }
}