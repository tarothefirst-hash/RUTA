package com.ruta.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.util.NotificationLogic
import com.ruta.app.util.NotificationStore

/** The bell on the passenger home screen opens this list. */
class NotificationsActivity : AppCompatActivity() {

    private val database = FirebaseDatabase.getInstance().reference
    private var listener: ValueEventListener? = null
    private var uid: String? = null
    private var current: List<NotificationLogic.Item> = emptyList()
    private val adapter = Adapter { item ->
        uid?.let { NotificationStore.markRead(database, it, item.id) }
        if (item.type == NotificationStore.TYPE_SAFETY) showSafetyDetails(item)
    }

    /** A route-deviation / emergency notification opens large, with the whole story and the driver. */
    private fun showSafetyDetails(item: NotificationLogic.Item) {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        col.addView(TextView(this).apply {
            text = item.body
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#111827"))
        })
        col.addView(TextView(this).apply {
            text = java.text.SimpleDateFormat("EEE, d MMM yyyy • h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date(item.timestamp))
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#6B7280"))
            setPadding(0, (6 * dp).toInt(), 0, (14 * dp).toInt())
        })
        if (item.bookingId.isNotEmpty()) col.addView(AlertBanner.driverInfoRow(this, item.bookingId))

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(item.title)
            .setView(col)
            .setNegativeButton("Close", null)
        if (item.bookingId.isNotEmpty()) {
            builder.setPositiveButton("Track driver") { _, _ ->
                LiveTrackingFragment.requestedBookingId = item.bookingId
                startActivity(
                    android.content.Intent(this, PassengerHomeActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("NAVIGATE_TO", "LIVE_TRACKING")
                        putExtra("BOOKING_ID", item.bookingId)
                    }
                )
                finish()
            }
        }
        builder.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)
        uid = FirebaseAuth.getInstance().currentUser?.uid

        findViewById<View>(R.id.btnNotifBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnMarkAllRead).setOnClickListener {
            uid?.let { NotificationStore.markAllRead(database, it, current) }
        }
        findViewById<RecyclerView>(R.id.recyclerNotifications).apply {
            layoutManager = LinearLayoutManager(this@NotificationsActivity)
            adapter = this@NotificationsActivity.adapter
        }

        val u = uid ?: return
        listener = NotificationStore.listen(database, u) { items ->
            current = items
            adapter.submit(items)
            findViewById<View>(R.id.txtNotifEmpty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroy() {
        val u = uid
        val l = listener
        if (u != null && l != null) NotificationStore.stopListening(database, u, l)
        super.onDestroy()
    }

    private class Adapter(val onClick: (NotificationLogic.Item) -> Unit) :
        RecyclerView.Adapter<Adapter.VH>() {

        private var items: List<NotificationLogic.Item> = emptyList()

        fun submit(list: List<NotificationLogic.Item>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.viewUnreadDot)
            val title: TextView = v.findViewById(R.id.txtNotifTitle)
            val body: TextView = v.findViewById(R.id.txtNotifBody)
            val time: TextView = v.findViewById(R.id.txtNotifTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = items[position]
            holder.title.text = n.title
            holder.body.text = n.body
            holder.time.text = NotificationLogic.relativeTime(n.timestamp, System.currentTimeMillis())
            holder.dot.visibility = if (n.read) View.INVISIBLE else View.VISIBLE
            holder.itemView.setOnClickListener { onClick(n) }
        }

        override fun getItemCount() = items.size
    }
}
