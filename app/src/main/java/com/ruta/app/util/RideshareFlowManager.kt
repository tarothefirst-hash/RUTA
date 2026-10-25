package com.ruta.app.util

import android.os.Handler
import android.os.Looper
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Query
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.ruta.app.util.RideshareMatchLogic.CONFIRM_WINDOW_MS
import com.ruta.app.util.RideshareMatchLogic.Resolution
import java.util.concurrent.Executors

/**
 * Drives one passenger's rideshare booking from "looking for a co-passenger" to a
 * confirmed match.
 *
 * Steps (all state lives on the bookings in Firebase, so both phones agree and a
 * restarted app picks up where it was):
 *
 *  1. SEARCH. The booking sits in status MATCHING for up to five minutes
 *     (`searchExpiresAt`). When the time is up the screen is told
 *     ([Event.SearchExpired]) so it can ask "Switch to Solo-Ride?". Nothing is
 *     switched automatically.
 *  2. PROPOSE. When a compatible passenger is found, one phone claims BOTH
 *     bookings (`matchProposalId`, lower booking id first so two phones racing for
 *     each other cannot deadlock), works out the stop order and the fare for each
 *     passenger, and stores them in `tripGroups/<pairKey>`. Both bookings then go
 *     to `matchPhase = CONFIRM` with a 60 second `matchExpiresAt`.
 *  3. CONFIRM. Each passenger sees the co-passenger's details and both fares and
 *     presses Confirm ([confirmMatch]) or Decline ([declineMatch]). When both have
 *     confirmed, one write turns both bookings MATCHED, stamps the trip group id,
 *     and LOCKS the quoted fare onto each booking.
 *  4. If either declines, or 60 seconds pass without both confirming, the proposal
 *     is dropped, the two are marked "never pair again" (`matchExcluded`), and both
 *     go back to step 1 with their search time unchanged.
 *
 * The status stays MATCHING during steps 1-3 on purpose: drivers only see
 * REQUESTED/MATCHED bookings, and the existing database rules already let a
 * passenger write the other passenger's booking while it is MATCHING.
 */
class RideshareFlowManager(
    private val myBookingId: String,
    private val myPassengerId: String,
    private val myPickup: LatLng,
    private val myDropoff: LatLng,
    private val apiKey: String,
    private val onEvent: (Event) -> Unit
) {

    // ---------------------------------------------------------------------
    // What the screen is told
    // ---------------------------------------------------------------------

    sealed class Event {
        /** Still looking. [expiresAtLocalMs] is when the search window ends, on this phone's clock. */
        data class Searching(val expiresAtLocalMs: Long) : Event()

        /** A co-passenger was found; show details and fares and ask for confirmation. */
        data class ProposalReady(val proposal: Proposal) : Event()

        /** The proposal on screen is gone; the passenger is searching again. */
        data class ProposalEnded(val reason: EndReason) : Event()

        /** Both confirmed. The booking is now MATCHED with a locked fare. */
        data class Matched(val tripGroupId: String) : Event()

        /** The five minutes are up with no match. The screen should ask about solo. */
        object SearchExpired : Event()

        /** The booking is now a solo booking. */
        object SwitchedToSolo : Event()

        /** The booking was cancelled or finished elsewhere. */
        object BookingEnded : Event()
    }

    enum class EndReason { DECLINED_BY_ME, DECLINED_BY_PARTNER, TIMED_OUT, FAILED }

    /** The pieces of one passenger's locked fare. */
    data class FareParts(
        val total: Double,
        val base: Double,
        val solo: Double,
        val shared: Double,
        val detour: Double,
        val credit: Double
    )

    data class Proposal(
        val pairKey: String,
        val partnerBookingId: String,
        val partnerName: String,
        val partnerPhone: String,
        val partnerPickupAddress: String,
        val partnerDropoffAddress: String,
        val partnerPickup: LatLng,
        val partnerDropoff: LatLng,
        val myFare: FareParts,
        val partnerFare: FareParts,
        val stopOrder: List<RideshareManager.RideshareStop>,
        val expiresAtLocalMs: Long,
        val myConfirmed: Boolean,
        val partnerConfirmed: Boolean
    )

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private companion object {
        /** How long a claim may sit without becoming a proposal before it is released. */
        const val STALE_CLAIM_MS = 20_000L
    }

    private val database = FirebaseDatabase.getInstance().reference
    private val ownRef = database.child("bookings").child(myBookingId)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var started = false
    private var stopped = false
    private var serverOffsetMs = 0L
    private var offsetKnown = false
    private var initialisingExpiry = false

    private var offsetListener: ValueEventListener? = null
    private var ownListener: ValueEventListener? = null
    private var poolQuery: Query? = null
    private var poolListener: ValueEventListener? = null
    private var partnerListener: ValueEventListener? = null
    private var partnerRefBookingId: String? = null

    private var lastOwn: DataSnapshot? = null
    private var lastPool: DataSnapshot? = null

    private var ownStatus: String = ""
    private var ownProposalKey: String? = null
    private var excluded: Set<String> = emptySet()
    private var ownDistance = 0
    private var ownDuration = 0
    private var ownSurge = 1.0
    private var searchExpiresAtServerMs = 0L
    private var expiryPrompted = false

    private var activeProposalKey: String? = null
    private var activePartnerId: String? = null
    private var activeExpiresAtServerMs = 0L
    private var currentProposal: Proposal? = null
    private var iDeclined = false
    private var loadingProposal = false
    private var finalizing = false

    private var proposing = false
    private var cooldownUntilLocalMs = 0L

    private var searchTimer: Runnable? = null
    private var proposalTimer: Runnable? = null
    private var retryTimer: Runnable? = null

    // A claim that never turned into a proposal (the other phone died mid-way).
    private var staleClaimKey: String? = null
    private var staleClaimTimer: Runnable? = null

    private fun serverNow(): Long = System.currentTimeMillis() + serverOffsetMs
    private fun toLocal(serverMs: Long): Long = serverMs - serverOffsetMs

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        stopped = false

        val offsetRef = database.root.child(".info").child("serverTimeOffset")
        offsetListener = offsetRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val offset = snapshot.getValue(Long::class.java) ?: 0L
                if (!offsetKnown || offset != serverOffsetMs) {
                    offsetKnown = true
                    serverOffsetMs = offset
                    lastOwn?.let { handleOwn(it) }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        ownListener = ownRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastOwn = snapshot
                handleOwn(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        val query = database.child("bookings").orderByChild("status").equalTo("MATCHING")
        poolQuery = query
        poolListener = query.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastPool = snapshot
                maybePropose()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun stop() {
        stopped = true
        started = false
        cancelTimers()
        retryTimer?.let { handler.removeCallbacks(it) }
        retryTimer = null

        offsetListener?.let { database.root.child(".info").child("serverTimeOffset").removeEventListener(it) }
        offsetListener = null
        ownListener?.let { ownRef.removeEventListener(it) }
        ownListener = null
        poolListener?.let { poolQuery?.removeEventListener(it) }
        poolListener = null
        poolQuery = null
        detachPartnerListener()
        executor.shutdownNow()
    }

    // ---------------------------------------------------------------------
    // Actions the screen can take
    // ---------------------------------------------------------------------

    /** "Confirm Passenger Match". Only this passenger's own booking is written. */
    fun confirmMatch() {
        if (activeProposalKey == null) return
        ownRef.child("matchConfirmed").setValue(true)
    }

    /** "Decline". Both passengers go back to searching and are never paired again. */
    fun declineMatch() {
        cancelProposal(EndReason.DECLINED_BY_ME, touchPartner = true)
    }

    /** "No, keep searching" at the solo prompt: another full search window. */
    fun keepSearching() {
        expiryPrompted = false
        ownRef.child("searchExpiresAt").setValue(serverNow() + RideshareMatchLogic.SEARCH_WINDOW_MS)
    }

    /**
     * "Confirm Solo Ride": turns this same booking into a solo booking, so nobody
     * has to type their locations again. [fare] is the figure the passenger saw on
     * the solo summary.
     */
    fun switchToSolo(
        fare: Double,
        fareBeforeDiscount: Double,
        discountAmount: Double,
        onDone: (Boolean) -> Unit
    ) {
        val updates = hashMapOf<String, Any?>(
            "status" to "REQUESTED",
            "isShared" to false,
            "serviceType" to "REGULAR",
            "maxPassengersAllowed" to 1,
            "fare" to fare,
            "fareBeforeDiscount" to fareBeforeDiscount,
            "discountAmount" to discountAmount,
            "tripGroupId" to null,
            "matchProposalId" to null,
            "matchPartnerId" to null,
            "matchPhase" to null,
            "matchExpiresAt" to null,
            "matchConfirmed" to null,
            "searchExpiresAt" to null,
            "switchedToSoloAt" to serverNow()
        )
        ownRef.updateChildren(updates)
            .addOnSuccessListener { onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    // ---------------------------------------------------------------------
    // Own booking
    // ---------------------------------------------------------------------

    private fun handleOwn(s: DataSnapshot) {
        if (stopped) return
        if (!s.exists()) {
            emit(Event.BookingEnded)
            stop()
            return
        }

        ownStatus = s.child("status").getValue(String::class.java) ?: ""
        ownDistance = s.child("distanceMeters").getValue(Int::class.java) ?: 0
        ownDuration = s.child("durationSeconds").getValue(Int::class.java) ?: 0
        ownSurge = s.child("surgeMultiplier").getValue(Double::class.java) ?: 1.0

        when (ownStatus) {
            "MATCHING" -> handleMatching(s)

            "MATCHED" -> {
                val groupId = s.child("tripGroupId").getValue(String::class.java) ?: ""
                cancelTimers()
                emit(Event.Matched(groupId))
                stop()
            }

            "REQUESTED", "SEARCHING" -> {
                cancelTimers()
                emit(Event.SwitchedToSolo)
                stop()
            }

            "CANCELLED", "COMPLETED" -> {
                cancelTimers()
                emit(Event.BookingEnded)
                stop()
            }
        }
    }

    private fun handleMatching(s: DataSnapshot) {
        excluded = s.child("matchExcluded").children.mapNotNull { it.key }.toSet()

        // The search window is stored on the booking in SERVER time, so both phones and
        // a restarted app agree on it. A booking that has none yet gets one now.
        val storedExpiry = s.child("searchExpiresAt").getValue(Long::class.java)
        if (storedExpiry != null) {
            if (storedExpiry != searchExpiresAtServerMs) {
                searchExpiresAtServerMs = storedExpiry
                expiryPrompted = false
            }
        } else {
            if (searchExpiresAtServerMs == 0L) {
                searchExpiresAtServerMs = serverNow() + RideshareMatchLogic.SEARCH_WINDOW_MS
            }
            initialiseSearchExpiry()
        }

        val key = s.child("matchProposalId").getValue(String::class.java)
        val phase = s.child("matchPhase").getValue(String::class.java)
        val partnerId = s.child("matchPartnerId").getValue(String::class.java)
        ownProposalKey = key

        if (key != null && phase == "CONFIRM" && partnerId != null) {
            handleProposalState(key, partnerId, s)
            return
        }

        // No ready proposal on this booking (searching, or claimed and still being prepared).
        if (key != null && activeProposalKey == null) armStaleClaimCheck(key) else clearStaleClaimCheck()

        if (activeProposalKey != null) {
            val reason = when {
                iDeclined -> EndReason.DECLINED_BY_ME
                serverNow() >= activeExpiresAtServerMs -> EndReason.TIMED_OUT
                else -> EndReason.DECLINED_BY_PARTNER
            }
            endProposalLocally(reason)
        }

        emit(Event.Searching(toLocal(searchExpiresAtServerMs)))
        scheduleSearchTimer()
        maybePropose()
    }

    /** Writes the search deadline once, only if the booking does not have one yet. */
    private fun initialiseSearchExpiry() {
        if (initialisingExpiry || !offsetKnown) return
        initialisingExpiry = true
        val deadline = serverNow() + RideshareMatchLogic.SEARCH_WINDOW_MS
        ownRef.child("searchExpiresAt").runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                if (data.value != null) return Transaction.abort()
                data.value = deadline
                return Transaction.success(data)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                initialisingExpiry = false
            }
        })
    }

    // ---------------------------------------------------------------------
    // Recovering a booking that was claimed but never got a proposal
    // ---------------------------------------------------------------------

    private fun armStaleClaimCheck(key: String) {
        if (staleClaimKey == key) return
        clearStaleClaimCheck()
        staleClaimKey = key
        val r = Runnable {
            staleClaimKey = null
            if (stopped) return@Runnable
            val stillStuck = lastOwn?.child("matchProposalId")?.getValue(String::class.java) == key &&
                lastOwn?.child("matchPhase")?.getValue(String::class.java) == null
            if (!stillStuck) return@Runnable
            if (proposing) {
                // We are the one preparing it; give it more time.
                armStaleClaimCheck(key)
            } else {
                release(myBookingId, key)
            }
        }
        staleClaimTimer = r
        handler.postDelayed(r, STALE_CLAIM_MS)
    }

    private fun clearStaleClaimCheck() {
        staleClaimTimer?.let { handler.removeCallbacks(it) }
        staleClaimTimer = null
        staleClaimKey = null
    }

    // ---------------------------------------------------------------------
    // Search timer ("No Co-Passenger available")
    // ---------------------------------------------------------------------

    private fun scheduleSearchTimer() {
        searchTimer?.let { handler.removeCallbacks(it) }
        if (activeProposalKey != null) return
        val delay = (toLocal(searchExpiresAtServerMs) - System.currentTimeMillis()).coerceAtLeast(0L)
        val r = Runnable { onSearchTimerFired() }
        searchTimer = r
        handler.postDelayed(r, delay)
    }

    private fun onSearchTimerFired() {
        if (stopped || expiryPrompted) return
        if (ownStatus != "MATCHING") return
        // Someone is mid-proposal with us: let that finish first. The timer is
        // re-armed when the proposal ends.
        if (activeProposalKey != null || ownProposalKey != null) return
        expiryPrompted = true
        emit(Event.SearchExpired)
    }

    // ---------------------------------------------------------------------
    // Finding and proposing a match
    // ---------------------------------------------------------------------

    private fun maybePropose() {
        if (stopped || proposing) return
        if (ownStatus != "MATCHING" || ownProposalKey != null || activeProposalKey != null) return
        if (serverNow() >= searchExpiresAtServerMs) return

        val wait = cooldownUntilLocalMs - System.currentTimeMillis()
        if (wait > 0) {
            retryTimer?.let { handler.removeCallbacks(it) }
            val r = Runnable { maybePropose() }
            retryTimer = r
            handler.postDelayed(r, wait)
            return
        }

        val pool = lastPool ?: return
        val me = RideshareManager.RideshareCandidate(myBookingId, myPassengerId, myPickup, myDropoff)

        val candidates = pool.children.mapNotNull { child ->
            val id = child.key ?: return@mapNotNull null
            val eligible = RideshareMatchLogic.isEligiblePartner(
                myBookingId = myBookingId,
                candidateId = id,
                candidateIsShared = child.child("isShared").getValue(Boolean::class.java) == true,
                candidateInProposal = child.child("matchProposalId").exists(),
                excludedPartners = excluded
            )
            if (!eligible) return@mapNotNull null

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

        val match = RideshareManager.Matcher.findBestMatch(newRequest = me, pendingPool = candidates) ?: return
        tryPropose(match)
    }

    private fun tryPropose(other: RideshareManager.RideshareCandidate) {
        proposing = true
        val key = RideshareMatchLogic.pairKey(myBookingId, other.bookingId)
        val (first, second) = RideshareMatchLogic.claimOrder(myBookingId, other.bookingId)

        claim(first, key) { gotFirst ->
            if (!gotFirst) {
                endProposing(failed = true)
                return@claim
            }
            claim(second, key) { gotSecond ->
                if (!gotSecond) {
                    release(first, key)
                    endProposing(failed = true)
                    return@claim
                }
                buildProposal(other, key)
            }
        }
    }

    private fun endProposing(failed: Boolean) {
        proposing = false
        if (failed) cooldownUntilLocalMs = System.currentTimeMillis() + 2_000L
    }

    /** Claims one booking for this pair. Succeeds only if nobody else holds it. */
    private fun claim(bookingId: String, key: String, onResult: (Boolean) -> Unit) {
        database.child("bookings").child(bookingId).child("matchProposalId")
            .runTransaction(object : Transaction.Handler {
                override fun doTransaction(data: MutableData): Transaction.Result {
                    val current = data.getValue(String::class.java)
                    return if (current == null || current == key) {
                        data.value = key
                        Transaction.success(data)
                    } else {
                        Transaction.abort()
                    }
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                    onResult(error == null && committed)
                }
            })
    }

    private fun release(bookingId: String, key: String) {
        database.child("bookings").child(bookingId).child("matchProposalId")
            .runTransaction(object : Transaction.Handler {
                override fun doTransaction(data: MutableData): Transaction.Result {
                    if (data.getValue(String::class.java) != key) return Transaction.abort()
                    data.value = null
                    return Transaction.success(data)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {}
            })
    }

    private fun releaseBoth(otherId: String, key: String) {
        release(myBookingId, key)
        release(otherId, key)
    }

    private fun buildProposal(other: RideshareManager.RideshareCandidate, key: String) {
        database.child("bookings").child(other.bookingId).get()
            .addOnSuccessListener { ps ->
                if (stopped) return@addOnSuccessListener

                val status = ps.child("status").getValue(String::class.java)
                val shared = ps.child("isShared").getValue(Boolean::class.java) == true
                if (status != "MATCHING" || !shared || ownStatus != "MATCHING") {
                    releaseBoth(other.bookingId, key)
                    endProposing(failed = true)
                    return@addOnSuccessListener
                }

                val otherDistance = ps.child("distanceMeters").getValue(Int::class.java) ?: 0
                val otherDuration = ps.child("durationSeconds").getValue(Int::class.java) ?: 0
                val me = RideshareManager.RideshareCandidate(myBookingId, myPassengerId, myPickup, myDropoff)

                try {
                    executor.execute {
                        val route = RideshareRouteOptimizer.findOptimalSequence(me, other, apiKey)
                        if (route == null) {
                            handler.post {
                                if (stopped) return@post
                                releaseBoth(other.bookingId, key)
                                endProposing(failed = true)
                            }
                            return@execute
                        }

                        val breakdown = RideshareFareCalculator.calculate(
                            route = route,
                            bookingIdA = myBookingId,
                            bookingIdB = other.bookingId,
                            directDistanceA = if (ownDistance > 0) ownDistance else route.totalDistanceMeters,
                            directDurationA = if (ownDuration > 0) ownDuration else route.totalDurationSeconds,
                            directDistanceB = if (otherDistance > 0) otherDistance else route.totalDistanceMeters,
                            directDurationB = if (otherDuration > 0) otherDuration else route.totalDurationSeconds,
                            surgeMultiplier = ownSurge,
                            hasDiscountA = false, // shared rides are discount-exempt
                            hasDiscountB = false
                        )

                        handler.post {
                            if (stopped) return@post
                            persistProposal(other.bookingId, key, route, breakdown)
                        }
                    }
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    // stop() was called while we were reading the partner.
                }
            }
            .addOnFailureListener {
                releaseBoth(other.bookingId, key)
                endProposing(failed = true)
            }
    }

    private fun parts(f: RideshareFareCalculator.PassengerFare) = mapOf(
        "total" to f.total,
        "base" to f.baseFare,
        "solo" to f.soloCharge,
        "shared" to f.sharedCharge,
        "detour" to f.detourSurcharge,
        "credit" to f.inconvenienceCredit
    )

    private fun persistProposal(
        otherId: String,
        key: String,
        route: RideshareRouteOptimizer.SequencedRoute,
        breakdown: RideshareFareCalculator.FareBreakdown
    ) {
        val now = serverNow()
        val tripGroup = mapOf(
            "bookingIds" to listOf(myBookingId, otherId),
            "stopOrder" to route.orderedStops.map { stop ->
                mapOf(
                    "bookingId" to stop.bookingId,
                    "type" to stop.type.name,
                    "lat" to stop.location.latitude,
                    "lng" to stop.location.longitude
                )
            },
            "plannedDistanceMeters" to route.totalDistanceMeters,
            "plannedDurationSeconds" to route.totalDurationSeconds,
            "quote" to mapOf(
                myBookingId to parts(breakdown.passengerA),
                otherId to parts(breakdown.passengerB)
            ),
            "createdAt" to now
        )

        database.child("tripGroups").child(key).setValue(tripGroup)
            .addOnSuccessListener {
                val expiresAt = now + CONFIRM_WINDOW_MS
                val updates = hashMapOf<String, Any?>()
                for ((id, partner) in listOf(myBookingId to otherId, otherId to myBookingId)) {
                    updates["bookings/$id/matchPartnerId"] = partner
                    updates["bookings/$id/matchPhase"] = "CONFIRM"
                    updates["bookings/$id/matchExpiresAt"] = expiresAt
                    updates["bookings/$id/matchConfirmed"] = false
                }
                database.updateChildren(updates)
                    .addOnSuccessListener { endProposing(failed = false) }
                    .addOnFailureListener {
                        releaseBoth(otherId, key)
                        endProposing(failed = true)
                    }
            }
            .addOnFailureListener {
                releaseBoth(otherId, key)
                endProposing(failed = true)
            }
    }

    // ---------------------------------------------------------------------
    // A proposal is on this booking: show it and wait for both confirmations
    // ---------------------------------------------------------------------

    private fun handleProposalState(key: String, partnerId: String, s: DataSnapshot) {
        searchTimer?.let { handler.removeCallbacks(it) }

        val myConfirmed = s.child("matchConfirmed").getValue(Boolean::class.java) == true
        val expiresAt = s.child("matchExpiresAt").getValue(Long::class.java) ?: 0L

        if (activeProposalKey != key) {
            if (activeProposalKey != null) resetProposalState()
            clearStaleClaimCheck()
            activeProposalKey = key
            activePartnerId = partnerId
            activeExpiresAtServerMs = expiresAt
            iDeclined = false
            currentProposal = null
            loadProposalDetails(key, partnerId, expiresAt, myConfirmed)
            return
        }

        activeExpiresAtServerMs = expiresAt
        currentProposal = currentProposal?.copy(
            myConfirmed = myConfirmed,
            expiresAtLocalMs = toLocal(expiresAt)
        )
        currentProposal?.let { emit(Event.ProposalReady(it)) }
        evaluateResolution()
    }

    private fun loadProposalDetails(key: String, partnerId: String, expiresAt: Long, myConfirmed: Boolean) {
        if (loadingProposal) return
        loadingProposal = true

        database.child("bookings").child(partnerId).get()
            .addOnSuccessListener { ps ->
                database.child("tripGroups").child(key).get()
                    .addOnSuccessListener { gs ->
                        loadingProposal = false
                        if (stopped || activeProposalKey != key) return@addOnSuccessListener

                        val proposal = buildProposalFromSnapshots(key, partnerId, ps, gs, expiresAt, myConfirmed)
                        if (proposal == null) {
                            cancelProposal(EndReason.FAILED, touchPartner = true)
                            return@addOnSuccessListener
                        }
                        currentProposal = proposal
                        attachPartnerListener(partnerId, key)
                        emit(Event.ProposalReady(proposal))
                        evaluateResolution()
                    }
                    .addOnFailureListener {
                        loadingProposal = false
                        cancelProposal(EndReason.FAILED, touchPartner = true)
                    }
            }
            .addOnFailureListener {
                loadingProposal = false
                cancelProposal(EndReason.FAILED, touchPartner = true)
            }
    }

    private fun readParts(q: DataSnapshot): FareParts? {
        val total = q.child("total").getValue(Double::class.java) ?: return null
        if (total <= 0.0) return null
        return FareParts(
            total = total,
            base = q.child("base").getValue(Double::class.java) ?: 0.0,
            solo = q.child("solo").getValue(Double::class.java) ?: 0.0,
            shared = q.child("shared").getValue(Double::class.java) ?: 0.0,
            detour = q.child("detour").getValue(Double::class.java) ?: 0.0,
            credit = q.child("credit").getValue(Double::class.java) ?: 0.0
        )
    }

    private fun buildProposalFromSnapshots(
        key: String,
        partnerId: String,
        ps: DataSnapshot,
        gs: DataSnapshot,
        expiresAt: Long,
        myConfirmed: Boolean
    ): Proposal? {
        val myParts = readParts(gs.child("quote").child(myBookingId)) ?: return null
        val partnerParts = readParts(gs.child("quote").child(partnerId)) ?: return null

        val stops = gs.child("stopOrder").children.mapNotNull { st ->
            val bookingId = st.child("bookingId").getValue(String::class.java) ?: return@mapNotNull null
            val type = st.child("type").getValue(String::class.java) ?: return@mapNotNull null
            val lat = st.child("lat").getValue(Double::class.java) ?: return@mapNotNull null
            val lng = st.child("lng").getValue(Double::class.java) ?: return@mapNotNull null
            RideshareManager.RideshareStop(bookingId, RideshareManager.StopType.valueOf(type), LatLng(lat, lng))
        }

        val pLat = ps.child("pickupLat").getValue(Double::class.java) ?: return null
        val pLng = ps.child("pickupLng").getValue(Double::class.java) ?: return null
        val dLat = ps.child("dropoffLat").getValue(Double::class.java) ?: return null
        val dLng = ps.child("dropoffLng").getValue(Double::class.java) ?: return null

        return Proposal(
            pairKey = key,
            partnerBookingId = partnerId,
            partnerName = ps.child("passengerName").getValue(String::class.java)?.ifBlank { null } ?: "Co-passenger",
            partnerPhone = ps.child("passengerPhone").getValue(String::class.java) ?: "",
            partnerPickupAddress = ps.child("pickupAddress").getValue(String::class.java) ?: "",
            partnerDropoffAddress = ps.child("dropoffAddress").getValue(String::class.java) ?: "",
            partnerPickup = LatLng(pLat, pLng),
            partnerDropoff = LatLng(dLat, dLng),
            myFare = myParts,
            partnerFare = partnerParts,
            stopOrder = stops,
            expiresAtLocalMs = toLocal(expiresAt),
            myConfirmed = myConfirmed,
            partnerConfirmed = ps.child("matchConfirmed").getValue(Boolean::class.java) == true
        )
    }

    private fun attachPartnerListener(partnerId: String, key: String) {
        detachPartnerListener()
        partnerRefBookingId = partnerId
        val ref = database.child("bookings").child(partnerId)
        partnerListener = ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(ps: DataSnapshot) {
                if (stopped || activeProposalKey != key) return

                val status = ps.child("status").getValue(String::class.java)
                val partnerKey = ps.child("matchProposalId").getValue(String::class.java)

                // Both finalised: our own booking listener delivers the Matched event.
                if (status == "MATCHED") return

                val stillInProposal = ps.exists() && status == "MATCHING" && partnerKey == key
                if (!stillInProposal) {
                    // Co-passenger declined, cancelled or switched to solo. Their side
                    // may already be cleared; make sure ours is.
                    cancelProposal(EndReason.DECLINED_BY_PARTNER, touchPartner = false)
                    return
                }

                val partnerConfirmed = ps.child("matchConfirmed").getValue(Boolean::class.java) == true
                currentProposal = currentProposal?.copy(partnerConfirmed = partnerConfirmed)
                currentProposal?.let { emit(Event.ProposalReady(it)) }
                evaluateResolution()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun detachPartnerListener() {
        val id = partnerRefBookingId
        val l = partnerListener
        if (id != null && l != null) {
            database.child("bookings").child(id).removeEventListener(l)
        }
        partnerListener = null
        partnerRefBookingId = null
    }

    private fun evaluateResolution() {
        val p = currentProposal ?: return
        val partnerId = activePartnerId ?: return
        val now = serverNow()

        proposalTimer?.let { handler.removeCallbacks(it) }
        proposalTimer = null

        when (RideshareMatchLogic.resolveConfirmation(p.myConfirmed, p.partnerConfirmed, now, activeExpiresAtServerMs)) {
            Resolution.BOTH_CONFIRMED -> finalizeMatch(p)

            Resolution.EXPIRED -> {
                if (RideshareMatchLogic.shouldCancelExpiredProposal(myBookingId, partnerId, now, activeExpiresAtServerMs)) {
                    cancelProposal(EndReason.TIMED_OUT, touchPartner = true)
                } else {
                    scheduleProposalCheck(activeExpiresAtServerMs + RideshareMatchLogic.FALLBACK_GRACE_MS - now)
                }
            }

            Resolution.WAIT -> scheduleProposalCheck(activeExpiresAtServerMs - now)
        }
    }

    private fun scheduleProposalCheck(delayMs: Long) {
        val r = Runnable { evaluateResolution() }
        proposalTimer = r
        handler.postDelayed(r, delayMs.coerceAtLeast(0L) + 50L)
    }

    /**
     * Both confirmed: one write turns both bookings MATCHED and locks each
     * passenger's quoted fare. If the other phone got there first the write is
     * refused; that is fine, our own booking listener reports the match.
     */
    private fun finalizeMatch(p: Proposal) {
        if (finalizing) return
        finalizing = true

        val updates = hashMapOf<String, Any?>()
        for ((id, f) in listOf(myBookingId to p.myFare, p.partnerBookingId to p.partnerFare)) {
            updates["bookings/$id/status"] = "MATCHED"
            updates["bookings/$id/tripGroupId"] = p.pairKey
            updates["bookings/$id/matchPhase"] = "MATCHED"
            updates["bookings/$id/fare"] = f.total
            updates["bookings/$id/fareBeforeDiscount"] = f.total
            updates["bookings/$id/discountAmount"] = 0.0
            updates["bookings/$id/quotedFare"] = f.total
            updates["bookings/$id/fareLocked"] = true
            updates["bookings/$id/fareBase"] = f.base
            updates["bookings/$id/fareSoloCharge"] = f.solo
            updates["bookings/$id/fareSharedCharge"] = f.shared
            updates["bookings/$id/fareDetourSurcharge"] = f.detour
            updates["bookings/$id/fareInconvenienceCredit"] = f.credit
        }

        database.updateChildren(updates).addOnFailureListener {
            finalizing = false
            // If it was a real failure (network) rather than the other phone winning,
            // try again shortly. If the booking is already MATCHED this is a no-op.
            if (!stopped && ownStatus == "MATCHING") {
                val r = Runnable { evaluateResolution() }
                proposalTimer = r
                handler.postDelayed(r, 2_000L)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Ending a proposal
    // ---------------------------------------------------------------------

    /**
     * Drops the proposal: clears the match fields, marks the two as "never pair
     * again" and sends both back to searching. A new proposal started in the
     * meantime is never touched (checked against the current proposal id).
     *
     * [touchPartner] is false when the co-passenger has already left (cancelled or
     * moved on), because their booking can no longer be written by us.
     */
    private fun cancelProposal(reason: EndReason, touchPartner: Boolean) {
        val key = activeProposalKey ?: return
        val partnerId = activePartnerId ?: return
        if (lastOwn?.child("matchProposalId")?.getValue(String::class.java) != key) return

        if (reason == EndReason.DECLINED_BY_ME) iDeclined = true

        val ids = if (touchPartner) listOf(myBookingId, partnerId) else listOf(myBookingId)
        val updates = hashMapOf<String, Any?>()
        for (id in ids) {
            updates["bookings/$id/matchProposalId"] = null
            updates["bookings/$id/matchPartnerId"] = null
            updates["bookings/$id/matchPhase"] = null
            updates["bookings/$id/matchExpiresAt"] = null
            updates["bookings/$id/matchConfirmed"] = null
        }
        updates["bookings/$myBookingId/matchExcluded/$partnerId"] = true
        if (touchPartner) updates["bookings/$partnerId/matchExcluded/$myBookingId"] = true
        if (touchPartner) updates["tripGroups/$key"] = null

        cooldownUntilLocalMs = System.currentTimeMillis() + 3_000L

        database.updateChildren(updates).addOnFailureListener {
            // The partner's booking was no longer writable (it moved on). Clear just ours.
            if (touchPartner && !stopped) cancelProposal(reason, touchPartner = false)
        }
    }

    private fun endProposalLocally(reason: EndReason) {
        resetProposalState()
        emit(Event.ProposalEnded(reason))
    }

    /** Forgets the proposal on this phone without telling the screen. */
    private fun resetProposalState() {
        proposalTimer?.let { handler.removeCallbacks(it) }
        proposalTimer = null
        detachPartnerListener()
        activeProposalKey = null
        activePartnerId = null
        currentProposal = null
        loadingProposal = false
        finalizing = false
        iDeclined = false
    }

    private fun cancelTimers() {
        clearStaleClaimCheck()
        searchTimer?.let { handler.removeCallbacks(it) }
        searchTimer = null
        proposalTimer?.let { handler.removeCallbacks(it) }
        proposalTimer = null
    }

    private fun emit(event: Event) {
        if (!stopped || event is Event.Matched || event is Event.SwitchedToSolo || event is Event.BookingEnded) {
            onEvent(event)
        }
    }
}
