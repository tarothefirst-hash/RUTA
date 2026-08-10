package com.ruta.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ruta.app.R
import com.ruta.app.model.LocationItem

class LocationSuggestionsAdapter(
    private val items: List<LocationItem>,
    private val onItemClick: (LocationItem) -> Unit
) : RecyclerView.Adapter<LocationSuggestionsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgIcon: ImageView = view.findViewById(R.id.imgIcon)
        val txtName: TextView = view.findViewById(R.id.txtName)
        val txtAddress: TextView = view.findViewById(R.id.txtAddress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_location_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.txtName.text = item.name
        holder.txtAddress.text = item.address

        if (item.isUseMapOption) {
            holder.imgIcon.setImageResource(android.R.drawable.ic_menu_mapmode)
            holder.txtAddress.visibility = View.GONE
        } else {
            holder.imgIcon.setImageResource(android.R.drawable.ic_menu_mylocation)
            holder.txtAddress.visibility = if (item.address.isNotEmpty()) View.VISIBLE else View.GONE
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size
}