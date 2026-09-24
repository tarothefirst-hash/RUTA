package com.ruta.app.ui.driver

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ruta.app.R
import com.ruta.app.model.BookingModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookingHistoryAdapter(
    private var bookings: List<BookingModel>,
    private val onItemClick: (BookingModel) -> Unit
) : RecyclerView.Adapter<BookingHistoryAdapter.BookingViewHolder>() {

    class BookingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtDate: TextView = itemView.findViewById(R.id.txtHistoryDate)
        val txtStatus: TextView = itemView.findViewById(R.id.txtHistoryStatus)
        val txtPickup: TextView = itemView.findViewById(R.id.txtHistoryPickup)
        val txtDropoff: TextView = itemView.findViewById(R.id.txtHistoryDropoff)
        val txtFare: TextView = itemView.findViewById(R.id.txtHistoryFare)
        val txtServiceType: TextView = itemView.findViewById(R.id.txtHistoryServiceType)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_booking_history, parent, false)
        return BookingViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookingViewHolder, position: Int) {
        val booking = bookings[position]

        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        holder.txtDate.text = if (booking.createdAt != 0L) sdf.format(Date(booking.createdAt)) else "Recently"

        holder.txtPickup.text = "From: ${booking.pickupAddress ?: "N/A"}"
        holder.txtDropoff.text = "To: ${booking.dropoffAddress ?: "N/A"}"
        holder.txtFare.text = "₱${String.format("%.2f", booking.fare ?: 0.0)}"
        holder.txtServiceType.text = booking.serviceType?.replace("_", " ") ?: "Standard"

        when (booking.status) {
            "COMPLETED" -> {
                holder.txtStatus.text = "COMPLETED"
                holder.txtStatus.setTextColor(Color.parseColor("#4CAF50"))
            }
            "CANCELLED" -> {
                holder.txtStatus.text = "CANCELLED"
                holder.txtStatus.setTextColor(Color.parseColor("#E53935"))
            }
            "IN_PROGRESS", "ACCEPTED", "MATCHED", "ARRIVED" -> {
                holder.txtStatus.text = "ACTIVE TRIP"
                holder.txtStatus.setTextColor(Color.parseColor("#2196F3"))
            }
            else -> {
                holder.txtStatus.text = booking.status ?: "PENDING"
                holder.txtStatus.setTextColor(Color.parseColor("#FF9800"))
            }
        }

        holder.itemView.setOnClickListener {
            onItemClick(booking)
        }
    }

    override fun getItemCount(): Int = bookings.size

    fun updateList(newList: List<BookingModel>) {
        bookings = newList
        notifyDataSetChanged()
    }
}