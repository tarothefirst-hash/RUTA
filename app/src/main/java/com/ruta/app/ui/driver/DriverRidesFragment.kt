package com.ruta.app.ui.driver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.model.BookingModel
import com.ruta.app.ui.BookingHistoryAdapter

class DriverRidesFragment : Fragment() {

    private lateinit var rvDriverRides: RecyclerView
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    private var ridesQuery: Query? = null
    private var ridesListener: ValueEventListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_driver_rides, container, false)
        rvDriverRides = view.findViewById(R.id.rvDriverRides)
        rvDriverRides.layoutManager = LinearLayoutManager(requireContext())

        loadDriverRidesHistory()
        return view
    }

    private fun loadDriverRidesHistory() {
        val currentDriverId = auth.currentUser?.uid ?: return

        val query = database.reference.child("bookings")
            .orderByChild("driverId")
            .equalTo(currentDriverId)
        ridesQuery = query

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val ridesList = mutableListOf<BookingModel>()
                for (child in snapshot.children) {
                    val booking = child.getValue(BookingModel::class.java)
                    if (booking != null) {
                        ridesList.add(booking)
                    }
                }
                // Sort latest bookings to the top
                ridesList.sortByDescending { it.createdAt }
                rvDriverRides.adapter = BookingHistoryAdapter(ridesList) { booking ->
                    Toast.makeText(
                        requireContext(),
                        "Selected ride: ${booking.bookingId}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        "Failed to load rides",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        ridesListener = listener
        query.addValueEventListener(listener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ridesQuery?.let { query ->
            ridesListener?.let { listener -> query.removeEventListener(listener) }
        }
        ridesQuery = null
        ridesListener = null
    }
}