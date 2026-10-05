package com.ruta.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import com.bumptech.glide.Glide
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.databinding.ActivityAdminHomeBinding
import com.ruta.app.model.BookingModel
import com.ruta.app.model.User
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AdminHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminHomeBinding
    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()
    private val dbRef by lazy { FirebaseDatabase.getInstance().reference }
    private var usersListener: ValueEventListener? = null
    private var bookingsListener: ValueEventListener? = null
    private var discountRequestsListener: ValueEventListener? = null
    private var walletRequestsListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupNavigationDrawer()
        startDataListeners()
        binding.btnAddDriver.setOnClickListener { showCreateDriverDialog() }
        binding.btnViewAllApprovals.setOnClickListener { showSection(R.id.nav_driver_approvals) }
        binding.btnAdminLogout.setOnClickListener { performLogout() }
    }

    private fun showCreateDriverDialog() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_create_driver, null)
        dialog.setContentView(view)

        val txtError = view.findViewById<TextView>(R.id.txtCreateDriverError)

        view.findViewById<Button>(R.id.btnSubmitCreateDriver).setOnClickListener {
            val firstName = view.findViewById<EditText>(R.id.edtDriverFirstName).text.toString().trim()
            val lastName = view.findViewById<EditText>(R.id.edtDriverLastName).text.toString().trim()
            val email = view.findViewById<EditText>(R.id.edtDriverEmail).text.toString().trim()
            val phone = view.findViewById<EditText>(R.id.edtDriverPhone).text.toString().trim()
            val password = view.findViewById<EditText>(R.id.edtDriverPassword).text.toString().trim()
            val vehicleModel = view.findViewById<EditText>(R.id.edtDriverVehicleModel).text.toString().trim()
            val plateNumber = view.findViewById<EditText>(R.id.edtDriverPlateNumber).text.toString().trim()
            val vehicleColor = view.findViewById<EditText>(R.id.edtDriverVehicleColor).text.toString().trim()

            if (firstName.isEmpty() || lastName.isEmpty() || email.isEmpty() || password.length < 6 ||
                vehicleModel.isEmpty() || plateNumber.isEmpty() || vehicleColor.isEmpty()) {
                txtError.text = "Fill in every field — password needs at least 6 characters."
                txtError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            createDriverAccount(firstName, lastName, email, phone, password, vehicleModel, plateNumber, vehicleColor) { success, message ->
                if (success) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                } else {
                    txtError.text = message
                    txtError.visibility = View.VISIBLE
                }
            }
        }

        dialog.show()
    }

    private fun createDriverAccount(
        firstName: String, lastName: String, email: String, phone: String,
        password: String, vehicleModel: String, plateNumber: String, vehicleColor: String,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        val secondaryAppName = "DriverCreation_${System.currentTimeMillis()}"
        val secondaryApp = FirebaseApp.initializeApp(this, FirebaseApp.getInstance().options, secondaryAppName)
        val secondaryAuth = FirebaseAuth.getInstance(secondaryApp)

        secondaryAuth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid
                if (uid == null) {
                    onComplete(false, "Failed to create driver account.")
                    secondaryApp.delete()
                    return@addOnSuccessListener
                }

                val userMap = hashMapOf<String, Any>(
                    "uid" to uid,
                    "firstName" to firstName,
                    "lastName" to lastName,
                    "name" to "$firstName $lastName".trim(),
                    "email" to email,
                    "phone" to phone,
                    "role" to "DRIVER",
                    "isActive" to false,
                    "isApproved" to false,
                    "isOnline" to false,
                    "documentsSubmitted" to false,
                    "vehicleModel" to vehicleModel,
                    "plateNumber" to plateNumber,
                    "vehicleColor" to vehicleColor,
                    "walletBalance" to 100.0,
                    "createdAt" to System.currentTimeMillis()
                )

                database.child("users").child(uid).setValue(userMap)
                    .addOnSuccessListener {
                        onComplete(true, "Driver account created — pending your approval below.")
                    }
                    .addOnFailureListener { e ->
                        onComplete(false, "Account created but profile save failed: ${e.message}")
                    }
                    .addOnCompleteListener {
                        secondaryAuth.signOut()
                        secondaryApp.delete()
                    }
            }
            .addOnFailureListener { e ->
                onComplete(false, e.message ?: "Failed to create account.")
                secondaryApp.delete()
            }
    }

    private fun setupHeader() {
        val sdf = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US)
        binding.txtCurrentDate.text = sdf.format(Calendar.getInstance().time).uppercase()
        binding.txtGreeting.text = "Good morning, Admin."
    }

    private fun setupNavigationDrawer() {
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        val headerView = binding.navView.getHeaderView(0)
        val txtAdminName = headerView.findViewById<TextView>(R.id.txtAdminName)

        val currentUid = auth.currentUser?.uid
        if (currentUid != null) {
            database.child("users").child(currentUid).child("name").get().addOnSuccessListener {
                val name = it.getValue(String::class.java)
                if (!name.isNullOrEmpty()) {
                    txtAdminName.text = name
                }
            }
        }

        binding.navView.setNavigationItemSelectedListener { item ->
            showSection(item.itemId)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }
    private fun showSection(id: Int) {
        binding.layoutDashboard.visibility = View.GONE
        binding.layoutRiders.visibility = View.GONE
        binding.layoutApprovals.visibility = View.GONE
        binding.layoutTrips.visibility = View.GONE
        binding.layoutSettings.visibility = View.GONE
        binding.layoutDriverWallet?.visibility = View.GONE

        when (id) {
            R.id.nav_dashboard -> {
                binding.layoutDashboard.visibility = View.VISIBLE
                binding.txtToolbarTitle.text = "Dashboard"
            }
            R.id.nav_riders -> {
                binding.layoutRiders.visibility = View.VISIBLE
                binding.txtToolbarTitle.text = "ID Confirmation"
                loadPendingDiscountRequests()
            }
            R.id.nav_driver_approvals -> {
                binding.layoutApprovals.visibility = View.VISIBLE
                binding.txtToolbarTitle.text = "Driver Approvals"
            }
            R.id.nav_trips -> {
                binding.layoutTrips.visibility = View.VISIBLE
                binding.txtToolbarTitle.text = "Trips & Activity"
            }
            R.id.nav_admin_wallet -> {
                binding.layoutDriverWallet?.visibility = View.VISIBLE
                binding.txtToolbarTitle.text = "Top-ups & Cash-ins"
                loadPendingWalletRequests()
            }
            R.id.nav_settings -> {
                binding.layoutSettings.visibility = View.VISIBLE
                binding.txtToolbarTitle.text = "Settings"
            }
        }
    }
    private fun loadPendingDiscountRequests() {
        // Attach this listener once — the query never changes, so re-attaching on every
        // section switch would just stack up listeners that are never removed.
        if (discountRequestsListener != null) return
        binding.layoutFullRiderList.removeAllViews()

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isFinishing || isDestroyed) return
                binding.layoutFullRiderList.removeAllViews()
                val inflater = LayoutInflater.from(this@AdminHomeActivity)

                if (!snapshot.exists()) {
                    val empty = TextView(this@AdminHomeActivity).apply {
                        text = "No pending ID submissions."
                        setTextColor(Color.GRAY)
                        setPadding(0, 32, 0, 32)
                    }
                    binding.layoutFullRiderList.addView(empty)
                    return
                }

                for (reqSnap in snapshot.children) {
                    val requestId = reqSnap.key ?: continue
                    val userId = reqSnap.child("userId").getValue(String::class.java) ?: continue
                    val userName = reqSnap.child("userName").getValue(String::class.java) ?: "Passenger"
                    val discountType = reqSnap.child("discountType").getValue(String::class.java) ?: "N/A"
                    val frontUrl = reqSnap.child("idFrontUrl").getValue(String::class.java)
                    val backUrl = reqSnap.child("idBackUrl").getValue(String::class.java)

                    val view = inflater.inflate(R.layout.item_booking_history, binding.layoutFullRiderList, false)
                    view.findViewById<TextView>(R.id.txtHistoryPickup).text = "Applicant: $userName"
                    view.findViewById<TextView>(R.id.txtHistoryDropoff).text = "Discount Type: $discountType"
                    view.findViewById<TextView>(R.id.txtHistoryFare).visibility = View.GONE
                    view.findViewById<TextView>(R.id.txtHistoryServiceType).visibility = View.GONE
                    view.findViewById<TextView>(R.id.txtHistoryDate).text = "Pending Review"
                    view.findViewById<TextView>(R.id.txtHistoryDate).setTextColor(Color.parseColor("#EF4444"))

                    val innerLayout = view.findViewById<TextView>(R.id.txtHistoryPickup).parent as LinearLayout

                    addDocumentReviewButton(innerLayout, "View Front ID", frontUrl)
                    addDocumentReviewButton(innerLayout, "View Back ID", backUrl)

                    val btnApprove = Button(this@AdminHomeActivity).apply {
                        text = "APPROVE DISCOUNT"
                        setBackgroundColor(Color.parseColor("#10B981"))
                        setTextColor(Color.WHITE)
                        textSize = 12f
                        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 120)
                        params.setMargins(0, 12, 0, 0)
                        layoutParams = params
                    }
                    btnApprove.setOnClickListener { approveDiscountRequest(requestId, userId) }

                    val btnReject = Button(this@AdminHomeActivity).apply {
                        text = "REJECT"
                        setBackgroundColor(Color.parseColor("#F3F4F6"))
                        setTextColor(Color.parseColor("#EF4444"))
                        textSize = 12f
                        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        params.setMargins(0, 8, 0, 0)
                        layoutParams = params
                    }
                    btnReject.setOnClickListener { rejectDiscountRequest(requestId) }

                    innerLayout.addView(btnApprove)
                    innerLayout.addView(btnReject)

                    binding.layoutFullRiderList.addView(view)
                    addDivider(binding.layoutFullRiderList)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        discountRequestsListener = listener
        database.child("discount_requests").orderByChild("status").equalTo("PENDING")
            .addValueEventListener(listener)
    }

    private fun approveDiscountRequest(requestId: String, userId: String) {
        // Claim the request atomically. Only a request still in PENDING may be approved —
        // if it is already APPROVED (discount granted) or already REJECTED this aborts, so a
        // rejected request can never be flipped back to APPROVED and re-grant the discount.
        val requestStatusRef = database.child("discount_requests").child(requestId).child("status")
        requestStatusRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(mutableData: MutableData): Transaction.Result {
                if (mutableData.getValue(String::class.java) != "PENDING") {
                    return Transaction.abort()
                }
                mutableData.value = "APPROVED"
                return Transaction.success(mutableData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                if (!committed) {
                    if (error != null) {
                        Toast.makeText(this@AdminHomeActivity, "Failed to approve request: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                // 2. Grant the discount now that the request is locked in, writing the request
                //    status and the user flag together so they can never disagree.
                val updates = mapOf<String, Any>(
                    "discount_requests/$requestId/status" to "APPROVED",
                    "users/$userId/hasDiscount" to true
                )
                database.updateChildren(updates)
                    .addOnSuccessListener { Toast.makeText(this@AdminHomeActivity, "Discount approved.", Toast.LENGTH_SHORT).show() }
                    .addOnFailureListener { e ->
                        // Grant never landed — release the claim so the request is not left
                        // APPROVED with no discount applied.
                        requestStatusRef.setValue("PENDING")
                        Toast.makeText(this@AdminHomeActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        })
    }

    private fun rejectDiscountRequest(requestId: String) {
        // Claim the request atomically. Only a request still in PENDING may be rejected —
        // if it is already APPROVED (discount granted) or already REJECTED this aborts, so a
        // reject can never flip an approved request to REJECTED while the discount stays on.
        val requestStatusRef = database.child("discount_requests").child(requestId).child("status")
        requestStatusRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(mutableData: MutableData): Transaction.Result {
                if (mutableData.getValue(String::class.java) != "PENDING") {
                    return Transaction.abort()
                }
                mutableData.value = "REJECTED"
                return Transaction.success(mutableData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                if (committed) {
                    Toast.makeText(this@AdminHomeActivity, "Application rejected.", Toast.LENGTH_SHORT).show()
                } else if (error != null) {
                    Toast.makeText(this@AdminHomeActivity, "Failed to reject request: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }
    private fun loadPendingWalletRequests() {
        // Attach this listener once — the query never changes, so re-attaching on every
        // section switch would just stack up listeners that are never removed.
        if (walletRequestsListener != null) return
        val container = findViewById<LinearLayout>(R.id.layoutWalletRequestsList) ?: return
        container.removeAllViews()

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isFinishing || isDestroyed) return
                container.removeAllViews()

                if (!snapshot.exists()) {
                    val emptyTv = TextView(this@AdminHomeActivity).apply {
                        text = "No pending cash-in requests."
                        setTextColor(Color.GRAY)
                        setPadding(16, 32, 16, 32)
                    }
                    container.addView(emptyTv)
                    return
                }

                for (reqSnap in snapshot.children) {
                    val requestId = reqSnap.key ?: reqSnap.child("requestId").value?.toString() ?: continue
                    val driverUid = reqSnap.child("driverUid").value?.toString()
                        ?: reqSnap.child("driverId").value?.toString() ?: ""
                    val driverName = reqSnap.child("driverName").value?.toString() ?: "Driver"
                    val amount = reqSnap.child("amount").value?.toString()?.toDoubleOrNull() ?: 0.0
                    val receiptUrl = reqSnap.child("receiptUrl").value?.toString() ?: ""

                    val itemView = layoutInflater.inflate(R.layout.item_wallet_request, container, false)

                    val txtDriverName = itemView.findViewById<TextView>(R.id.txtDriverName)
                    val txtAmount = itemView.findViewById<TextView>(R.id.txtAmount)
                    val imgReceipt = itemView.findViewById<ImageView>(R.id.imgReceiptThumbnail)
                    val btnApprove = itemView.findViewById<Button>(R.id.btnApprove)
                    val btnReject = itemView.findViewById<Button>(R.id.btnReject)

                    txtDriverName.text = driverName
                    txtAmount.text = "₱%.2f".format(amount)

                    if (receiptUrl.isNotEmpty()) {
                        imgReceipt.visibility = View.VISIBLE
                        loadReceiptImage(imgReceipt, receiptUrl)
                        imgReceipt.setOnClickListener {
                            showFullReceiptDialog(receiptUrl)
                        }
                    } else {
                        imgReceipt.visibility = View.GONE
                    }

                    btnApprove.setOnClickListener {
                        approveCashIn(requestId, driverUid, amount)
                    }

                    btnReject.setOnClickListener {
                        rejectCashIn(requestId)
                    }

                    container.addView(itemView)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@AdminHomeActivity, "Failed to load requests: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
        walletRequestsListener = listener
        dbRef.child("wallet_requests").orderByChild("status").equalTo("PENDING")
            .addValueEventListener(listener)
    }

    private fun showFullReceiptDialog(imageUrl: String) {
        val builder = AlertDialog.Builder(this)
        val imageView = ImageView(this).apply {
            adjustViewBounds = true
            setPadding(16, 16, 16, 16)
        }
        loadReceiptImage(imageView, imageUrl)
        builder.setView(imageView)
        builder.setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    // Receipts arrive as a real URL, a data-URI, or raw base64 (no prefix). Decode the
    // base64 shapes in-process and let Glide handle only real URLs.
    private fun loadReceiptImage(imageView: ImageView, value: String) {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            Glide.with(this).load(value).into(imageView)
            return
        }

        try {
            val cleanBase64 = value.substringAfter("base64,")
            val decodedBytes = android.util.Base64.decode(cleanBase64, android.util.Base64.NO_WRAP)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)

            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                Toast.makeText(this, "Could not render image bytes.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error decoding image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun approveCashIn(requestId: String, driverUid: String, amount: Double) {
        if (driverUid.isEmpty()) {
            Toast.makeText(this, "Error: Invalid driver ID", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Claim the request atomically. Only a request still in PENDING may be approved —
        //    if it is already APPROVED (credited) or already REJECTED this aborts, so a
        //    double tap, a replay, or an approve after a reject can never credit the request.
        val requestStatusRef = dbRef.child("wallet_requests").child(requestId).child("status")
        requestStatusRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(mutableData: MutableData): Transaction.Result {
                if (mutableData.getValue(String::class.java) != "PENDING") {
                    return Transaction.abort()
                }
                mutableData.value = "APPROVED"
                return Transaction.success(mutableData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                if (!committed) {
                    // Aborted because it was not PENDING (no-op), or the claim failed.
                    if (error != null) {
                        Toast.makeText(this@AdminHomeActivity, "Failed to claim request: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                // 2. Credit the driver's wallet exactly once, now that the request is locked in.
                val driverWalletRef = dbRef.child("users").child(driverUid).child("walletBalance")
                driverWalletRef.runTransaction(object : Transaction.Handler {
                    override fun doTransaction(mutableData: MutableData): Transaction.Result {
                        val currentBalance = mutableData.getValue(Double::class.java) ?: 0.0
                        mutableData.value = currentBalance + amount
                        return Transaction.success(mutableData)
                    }

                    override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                        if (committed) {
                            Toast.makeText(this@AdminHomeActivity, "Cash-in approved! Added ₱$amount to balance.", Toast.LENGTH_SHORT).show()
                        } else {
                            // Balance never moved — release the claim so the request is not left
                            // APPROVED with no money credited.
                            requestStatusRef.setValue("PENDING")
                            Toast.makeText(this@AdminHomeActivity, "Failed to update balance: ${error?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
        })
    }

    private fun rejectCashIn(requestId: String) {
        // Claim the request atomically. Only a request still in PENDING may be rejected —
        // if it is already APPROVED (credited) or already REJECTED this aborts, so a double
        // tap (or a second admin device) can never overwrite an approved request.
        val requestStatusRef = dbRef.child("wallet_requests").child(requestId).child("status")
        requestStatusRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(mutableData: MutableData): Transaction.Result {
                if (mutableData.getValue(String::class.java) != "PENDING") {
                    return Transaction.abort()
                }
                mutableData.value = "REJECTED"
                return Transaction.success(mutableData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                if (committed) {
                    Toast.makeText(this@AdminHomeActivity, "Cash-in rejected.", Toast.LENGTH_SHORT).show()
                } else if (error != null) {
                    Toast.makeText(this@AdminHomeActivity, "Failed to reject request: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun startDataListeners() {
        val usersListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var totalRiders = 0
                var activeDrivers = 0
                var pendingApprovals = 0

                val allRiders = mutableListOf<User>()
                val pendingDrivers = mutableListOf<DataSnapshot>()

                for (userSnap in snapshot.children) {
                    val role = userSnap.child("role").getValue(String::class.java) ?: "PASSENGER"
                    val isApproved = userSnap.child("isApproved").getValue(Boolean::class.java) ?: false

                    if (role == "PASSENGER") {
                        totalRiders++
                        userSnap.getValue(User::class.java)?.let { allRiders.add(it) }
                    } else if (role == "DRIVER") {
                        if (isApproved) {
                            val isOnline = userSnap.child("isOnline").getValue(Boolean::class.java) ?: false
                            if (isOnline) activeDrivers++
                        } else {
                            pendingApprovals++
                            pendingDrivers.add(userSnap)
                        }
                    }
                }

                binding.txtTotalRiders.text = totalRiders.toString()
                binding.txtActiveDrivers.text = activeDrivers.toString()
                binding.txtPendingReview.text = pendingApprovals.toString()


                updateApprovalList(pendingDrivers)

                binding.txtQueueEmpty.visibility = if (pendingDrivers.isEmpty()) View.VISIBLE else View.GONE
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        this.usersListener = usersListener
        database.child("users").addValueEventListener(usersListener)

        val bookingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var tripsToday = 0
                val bookings = mutableListOf<Pair<Long, BookingModel>>()
                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                }.timeInMillis

                for (bookingSnap in snapshot.children) {
                    val booking = bookingSnap.getValue(BookingModel::class.java)
                    if (booking != null) {
                        val bookedAt = bookingTime(bookingSnap)
                        bookings.add(bookedAt to booking)
                        if (bookedAt >= todayStart) tripsToday++
                    }
                }
                binding.txtTripsToday.text = tripsToday.toString()
                updateTripFeed(bookings.sortedByDescending { it.first }.take(15).map { it.second })
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        this.bookingsListener = bookingsListener
        database.child("bookings").addValueEventListener(bookingsListener)
    }

    // Every booking writer now sets "createdAt". The "timestamp" fallback is kept only so
    // records created before that unification still show the right date.
    private fun bookingTime(snapshot: DataSnapshot): Long {
        return snapshot.child("createdAt").getValue(Long::class.java)
            ?: snapshot.child("timestamp").getValue(Long::class.java)
            ?: 0L
    }

    private fun updateApprovalList(drivers: List<DataSnapshot>) {
        binding.layoutApprovalList.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (driverSnap in drivers) {
            val uid = driverSnap.key ?: continue
            val firstName = driverSnap.child("firstName").getValue(String::class.java) ?: ""
            val lastName = driverSnap.child("lastName").getValue(String::class.java) ?: ""
            val email = driverSnap.child("email").getValue(String::class.java) ?: ""
            val submitted = driverSnap.child("documentsSubmitted").getValue(Boolean::class.java) ?: false
            val licenseUrl = driverSnap.child("licenseUrl").getValue(String::class.java)
            val regUrl = driverSnap.child("registrationUrl").getValue(String::class.java)
            val nbiUrl = driverSnap.child("nbiClearanceUrl").getValue(String::class.java)

            val view = inflater.inflate(R.layout.item_booking_history, binding.layoutApprovalList, false)

            view.findViewById<TextView>(R.id.txtHistoryPickup).text = "Applicant: $firstName $lastName"
            view.findViewById<TextView>(R.id.txtHistoryDropoff).text = "Email: $email"
            view.findViewById<TextView>(R.id.txtHistoryFare).visibility = View.GONE
            view.findViewById<TextView>(R.id.txtHistoryServiceType).visibility = View.GONE

            val txtStatus = view.findViewById<TextView>(R.id.txtHistoryDate)
            val innerLayout = view.findViewById<TextView>(R.id.txtHistoryPickup).parent as LinearLayout

            if (submitted) {
                txtStatus.text = "Documents Submitted — Ready for Review"
                txtStatus.setTextColor(Color.parseColor("#10B981"))

                addDocumentReviewButton(innerLayout, "View Driver's License", licenseUrl)
                addDocumentReviewButton(innerLayout, "View OR/CR", regUrl)
                addDocumentReviewButton(innerLayout, "View NBI Clearance", nbiUrl)

                val btnApprove = Button(this).apply {
                    text = "APPROVE DRIVER"
                    setBackgroundColor(Color.parseColor("#10B981"))
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 120)
                    params.setMargins(0, 24, 0, 0)
                    layoutParams = params
                }
                btnApprove.setOnClickListener { approveDriver(uid) }
                innerLayout.addView(btnApprove)
            } else {
                txtStatus.text = "Pending Requirements Submission"
                txtStatus.setTextColor(Color.parseColor("#EF4444"))
            }

            binding.layoutApprovalList.addView(view)
        }
    }

    private fun addDocumentReviewButton(parent: LinearLayout, label: String, dataUrl: String?) {
        if (dataUrl.isNullOrEmpty()) return

        val btn = Button(this).apply {
            text = label
            setBackgroundColor(Color.parseColor("#EEF2FF"))
            setTextColor(Color.parseColor("#4338CA"))
            textSize = 12f
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 8, 0, 0)
            layoutParams = params
        }
        btn.setOnClickListener {
            showImagePreviewDialog(label, dataUrl)
        }
        parent.addView(btn)
    }

    private fun showImagePreviewDialog(title: String, dataUrl: String) {
        val imageView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (350 * resources.displayMetrics.density).toInt()
            )
        }

        try {
            val cleanBase64 = dataUrl.substringAfter("base64,")
            val decodedBytes = android.util.Base64.decode(cleanBase64, android.util.Base64.NO_WRAP)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)

            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                Toast.makeText(this, "Could not render image bytes.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error decoding image: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(imageView)
            .setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun updateTripFeed(bookings: List<BookingModel>) {
        binding.layoutTripFeed.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (booking in bookings) {
            val view = inflater.inflate(R.layout.item_booking_history, binding.layoutTripFeed, false)
            val txtStatus = view.findViewById<TextView>(R.id.txtHistoryStatus)
            txtStatus.text = booking.status

            view.findViewById<TextView>(R.id.txtHistoryPickup).text = "From: ${booking.pickupAddress}"
            view.findViewById<TextView>(R.id.txtHistoryDropoff).text = "To: ${booking.dropoffAddress}"
            view.findViewById<TextView>(R.id.txtHistoryFare).text = "₱${if (booking.finalFare > 0.0) booking.finalFare else booking.fare}"

            binding.layoutTripFeed.addView(view)
            addDivider(binding.layoutTripFeed)
        }
    }

    private fun approveDriver(uid: String) {
        val updates = mapOf<String, Any>(
            "isApproved" to true,
            "isActive" to true
        )

        database.child("users").child(uid).updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(this, "Driver approved successfully!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Approval failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroy() {
        usersListener?.let { database.child("users").removeEventListener(it) }
        bookingsListener?.let { database.child("bookings").removeEventListener(it) }
        discountRequestsListener?.let {
            database.child("discount_requests").orderByChild("status").equalTo("PENDING").removeEventListener(it)
        }
        walletRequestsListener?.let {
            dbRef.child("wallet_requests").orderByChild("status").equalTo("PENDING").removeEventListener(it)
        }
        super.onDestroy()
    }

    private fun performLogout() {
        auth.signOut()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun addDivider(parent: LinearLayout) {
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
            setBackgroundColor(Color.parseColor("#F3F4F6"))
        }
        parent.addView(divider)
    }
}