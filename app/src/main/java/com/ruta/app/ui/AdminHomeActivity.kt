package com.ruta.app.ui

import com.ruta.app.util.StoragePhotos
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
import com.ruta.app.util.FareConfig
import com.ruta.app.util.FareRates
import com.ruta.app.util.NotificationStore
import com.ruta.app.util.ReviewLogic
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
        setupFareEditor()
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

                var shown = 0
                for (reqSnap in snapshot.children) {
                    val status = reqSnap.child("status").getValue(String::class.java) ?: ""
                    if (status != "PENDING" && status != "PENDING_REVIEW") continue
                    val requestId = reqSnap.key ?: continue
                    val userId = reqSnap.child("userId").getValue(String::class.java)
                        ?: reqSnap.child("uid").getValue(String::class.java) ?: requestId
                    val userName = reqSnap.child("userName").getValue(String::class.java) ?: "Passenger"
                    val discountType = reqSnap.child("discountType").getValue(String::class.java) ?: "N/A"
                    val frontUrl = reqSnap.child("frontRef").getValue(String::class.java)
                        ?: reqSnap.child("frontBase64").getValue(String::class.java)
                        ?: reqSnap.child("idFrontUrl").getValue(String::class.java)
                    val backUrl = reqSnap.child("backRef").getValue(String::class.java)
                        ?: reqSnap.child("backBase64").getValue(String::class.java)
                        ?: reqSnap.child("idBackUrl").getValue(String::class.java)
                    shown++

                    val view = inflater.inflate(R.layout.item_booking_history, binding.layoutFullRiderList, false)
                    view.findViewById<TextView>(R.id.txtHistoryPickup).text = "Applicant: $userName"
                    view.findViewById<TextView>(R.id.txtHistoryDropoff).text = "Discount Type: $discountType"
                    view.findViewById<TextView>(R.id.txtHistoryFare).visibility = View.GONE
                    view.findViewById<TextView>(R.id.txtHistoryServiceType).visibility = View.GONE
                    view.findViewById<TextView>(R.id.txtHistoryDate).text = "Pending Review"
                    view.findViewById<TextView>(R.id.txtHistoryDate).setTextColor(Color.parseColor("#EF4444"))

                    val innerLayout = view.findViewById<TextView>(R.id.txtHistoryPickup).parent as LinearLayout

                    addReviewPanel(
                        innerLayout,
                        listOf(
                            ReviewItem("front", "Front ID", frontUrl),
                            ReviewItem("back", "Back ID", backUrl)
                        ),
                        ReviewLogic.PASSENGER_ID_REASONS
                    ) { decision, reviews ->
                        finalizeDiscountReview(requestId, userId, decision, reviews)
                    }

                    binding.layoutFullRiderList.addView(view)
                    addDivider(binding.layoutFullRiderList)
                }
                if (shown == 0) {
                    val empty = TextView(this@AdminHomeActivity).apply {
                        text = "No pending ID submissions."
                        setTextColor(Color.GRAY)
                        setPadding(0, 32, 0, 32)
                    }
                    binding.layoutFullRiderList.addView(empty)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        discountRequestsListener = listener
        database.child("discount_requests").addValueEventListener(listener)
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

        StoragePhotos.loadBitmap(value) { bitmap ->
            if (bitmap != null) imageView.setImageBitmap(bitmap)
            else Toast.makeText(this, "Could not load this receipt.", Toast.LENGTH_SHORT).show()
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
                val bookings = mutableListOf<Pair<Long, DataSnapshot>>()
                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                }.timeInMillis

                for (bookingSnap in snapshot.children) {
                    val booking = bookingSnap.getValue(BookingModel::class.java)
                    if (booking != null) {
                        val bookedAt = bookingTime(bookingSnap)
                        bookings.add(bookedAt to bookingSnap)
                        if (bookedAt >= todayStart) tripsToday++
                    }
                }
                binding.txtTripsToday.text = tripsToday.toString()
                updateTripFeed(bookings.sortedByDescending { it.first }.take(40).map { it.second })
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
            val photoRef = driverSnap.child("profilePhoto").getValue(String::class.java)

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

                addReviewPanel(
                    innerLayout,
                    listOfNotNull(
                        ReviewItem("license", "Driver's License", licenseUrl, driverSnap.child("docReview").child("license").child("ok").getValue(Boolean::class.java) == true),
                        ReviewItem("registration", "OR/CR", regUrl, driverSnap.child("docReview").child("registration").child("ok").getValue(Boolean::class.java) == true),
                        ReviewItem("nbi", "NBI Clearance", nbiUrl, driverSnap.child("docReview").child("nbi").child("ok").getValue(Boolean::class.java) == true),
                        // Drivers registered before the 1x1 requirement have no photo yet: nothing to check.
                        if (photoRef.isNullOrEmpty()) null else
                            ReviewItem("photo", "1x1 Photo", photoRef, driverSnap.child("docReview").child("photo").child("ok").getValue(Boolean::class.java) == true)
                    ),
                    ReviewLogic.DRIVER_DOC_REASONS
                ) { decision, reviews ->
                    finalizeDriverReview(uid, decision, reviews)
                }
            } else {
                val rejected = driverSnap.child("docReviewStatus").getValue(String::class.java) == "REJECTED"
                txtStatus.text = if (rejected) "Rejected. Waiting for the driver to re-upload the marked documents."
                else "Pending Requirements Submission"
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

        // Storage reference (new) or inline Base64 (old): both handled here.
        StoragePhotos.loadBitmap(dataUrl) { bitmap ->
            if (bitmap != null) imageView.setImageBitmap(bitmap)
            else Toast.makeText(this, "Could not load this photo.", Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(imageView)
            .setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun updateTripFeed(bookings: List<DataSnapshot>) {
        binding.layoutTripFeed.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val dateFmt = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())

        for (snap in bookings) {
            fun str(k: String) = snap.child(k).getValue(String::class.java)
            val view = inflater.inflate(R.layout.item_booking_history, binding.layoutTripFeed, false)
            view.findViewById<TextView>(R.id.txtHistoryStatus).text = str("status") ?: "—"
            val whenMs = bookingTime(snap)
            view.findViewById<TextView>(R.id.txtHistoryDate).text = if (whenMs > 0) dateFmt.format(java.util.Date(whenMs)) else "—"
            view.findViewById<TextView>(R.id.txtHistoryPickup).text = "From: ${str("pickupAddress") ?: "N/A"}"
            view.findViewById<TextView>(R.id.txtHistoryDropoff).text = "To: ${str("dropoffAddress") ?: "N/A"}"
            val fare = snap.child("finalFare").getValue(Double::class.java)?.takeIf { it > 0.0 }
                ?: snap.child("fare").getValue(Double::class.java) ?: 0.0
            view.findViewById<TextView>(R.id.txtHistoryFare).text = "₱${String.format(Locale.US, "%.2f", fare)}"
            // Who rode with whom, at a glance.
            view.findViewById<TextView>(R.id.txtHistoryServiceType).text =
                "Passenger: ${str("passengerName") ?: "—"}  •  Driver: ${str("driverName") ?: "Unassigned"}"
            view.setOnClickListener { showTripDetails(snap) }

            binding.layoutTripFeed.addView(view)
            addDivider(binding.layoutTripFeed)
        }
    }

    /** Full ride record: who rode, who drove, the vehicle, route, fare and timing. */
    private fun showTripDetails(snap: DataSnapshot) {
        fun str(k: String) = snap.child(k).getValue(String::class.java)
        val passengerId = str("passengerId")
        val show = { passengerPhone: String ->
            val dateFmt = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
            fun time(k: String) = snap.child(k).getValue(Long::class.java)?.takeIf { it > 0 }?.let { dateFmt.format(java.util.Date(it)) }
            val fare = snap.child("finalFare").getValue(Double::class.java)?.takeIf { it > 0.0 }
                ?: snap.child("fare").getValue(Double::class.java) ?: 0.0
            val meters = snap.child("distanceMeters").getValue(Long::class.java) ?: 0L
            val secs = snap.child("durationSeconds").getValue(Long::class.java) ?: 0L
            val vehicle = listOfNotNull(str("vehicleColor"), str("vehicleModel"), str("plateNumber")?.let { "• $it" })
                .filter { it.isNotBlank() }.joinToString(" ")
            val text = buildString {
                append("Status: ${str("status") ?: "—"}\n")
                time("createdAt")?.let { append("Booked: $it\n") }
                time("acceptedAt")?.let { append("Accepted: $it\n") }
                append("\nPASSENGER\n${str("passengerName") ?: "—"}\n")
                if (passengerPhone.isNotBlank()) append("$passengerPhone\n")
                append("\nDRIVER\n${str("driverName") ?: "Unassigned"}\n")
                str("driverPhone")?.takeIf { it.isNotBlank() }?.let { append("$it\n") }
                if (vehicle.isNotBlank()) append("$vehicle\n")
                append("\nROUTE\nFrom: ${str("pickupAddress") ?: "N/A"}\nTo: ${str("dropoffAddress") ?: "N/A"}\n")
                if (meters > 0) append("Distance: ${String.format(Locale.US, "%.1f", meters / 1000.0)} km")
                if (secs > 0) append("  •  ${secs / 60} min")
                append("\n\nFARE: ₱${String.format(Locale.US, "%.2f", fare)}")
                if (!str("tripGroupId").isNullOrEmpty()) append("\nShared ride (rideshare)")
            }
            AlertDialog.Builder(this)
                .setTitle("Ride details")
                .setMessage(text)
                .setPositiveButton("Close", null)
                .show()
        }
        if (passengerId.isNullOrEmpty()) {
            show("")
        } else {
            database.child("users").child(passengerId).get().addOnCompleteListener { t ->
                val u = t.result
                val phone = u?.child("phoneNumber")?.getValue(String::class.java)
                    ?: u?.child("phone")?.getValue(String::class.java) ?: ""
                if (!isFinishing && !isDestroyed) show(phone)
            }
        }
    }

    // ------------------------------------------------------------------
    // Fare rates (applies to new bookings only)
    // ------------------------------------------------------------------

    private fun setupFareEditor() {
        val fields = mapOf(
            "soloBase" to R.id.edtSoloBase, "soloPerKm" to R.id.edtSoloPerKm, "soloPerMin" to R.id.edtSoloPerMin,
            "sharedBase" to R.id.edtSharedBase, "sharedPerKm" to R.id.edtSharedPerKm, "sharedPerMin" to R.id.edtSharedPerMin
        )
        fun fill(r: FareRates) {
            val m = r.toMap()
            fields.forEach { (k, id) ->
                val v = (m[k] as Double)
                findViewById<EditText>(id).setText(if (v % 1.0 == 0.0) v.toLong().toString() else v.toString())
            }
        }
        fill(FareConfig.rates)

        database.child("config").child("fare").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) fill(FareRates.fromMap(snapshot.value as? Map<*, *>))
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        fun read(): FareRates? {
            fun v(id: Int) = findViewById<EditText>(id).text.toString().trim().toDoubleOrNull()
            val vals = fields.mapValues { v(it.value) }
            if (vals.values.any { it == null }) return null
            return FareRates(
                vals["soloBase"]!!, vals["soloPerKm"]!!, vals["soloPerMin"]!!,
                vals["sharedBase"]!!, vals["sharedPerKm"]!!, vals["sharedPerMin"]!!
            )
        }

        findViewById<Button>(R.id.btnSaveFares).setOnClickListener {
            val rates = read()
            if (rates == null) {
                Toast.makeText(this, "Fill in every rate with a number.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val error = FareRates.validate(rates)
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Save new fare rates?")
                .setMessage("New bookings will use these rates right away. Fares already quoted or locked will not change.")
                .setPositiveButton("Save") { _, _ ->
                    val data = HashMap<String, Any>(rates.toMap())
                    data["updatedAt"] = System.currentTimeMillis()
                    data["updatedBy"] = auth.currentUser?.uid ?: ""
                    database.child("config").child("fare").setValue(data)
                        .addOnSuccessListener {
                            FareConfig.update(rates)
                            findViewById<TextView>(R.id.txtFareStatus).text =
                                "Saved. New bookings now use these rates."
                            Toast.makeText(this, "Fare rates saved.", Toast.LENGTH_SHORT).show()
                        }
                        .addOnFailureListener { Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_LONG).show() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<Button>(R.id.btnResetFares).setOnClickListener {
            fill(FareRates.DEFAULT)
            Toast.makeText(this, "Standard rates filled in. Tap Save to apply.", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Review checklist (a check or an X per item; every X needs a comment)
    // ------------------------------------------------------------------

    private data class ReviewItem(
        val key: String,
        val label: String,
        val dataUrl: String?,
        val preOk: Boolean = false
    )

    private fun addReviewPanel(
        parent: LinearLayout,
        items: List<ReviewItem>,
        reasons: List<String>,
        onSubmit: (ReviewLogic.Decision, Map<String, ReviewLogic.ItemReview?>) -> Unit
    ) {
        val dp = resources.displayMetrics.density
        val reviews = linkedMapOf<String, ReviewLogic.ItemReview?>()
        items.forEach { reviews[it.key] = if (it.preOk) ReviewLogic.ItemReview(true) else null }

        val submit = Button(this).apply {
            textSize = 12f
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (46 * dp).toInt())
            params.setMargins(0, (14 * dp).toInt(), 0, 0)
            layoutParams = params
        }

        fun refreshSubmit() {
            when (ReviewLogic.decide(reviews)) {
                ReviewLogic.Decision.INCOMPLETE -> {
                    submit.text = "Mark every item ✓ or ✗"
                    submit.setBackgroundColor(Color.parseColor("#E5E7EB"))
                    submit.setTextColor(Color.parseColor("#4B5563"))
                }
                ReviewLogic.Decision.APPROVED -> {
                    submit.text = "APPROVE"
                    submit.setBackgroundColor(Color.parseColor("#10B981"))
                    submit.setTextColor(Color.WHITE)
                }
                ReviewLogic.Decision.REJECTED -> {
                    val n = reviews.values.count { it?.ok == false }
                    submit.text = "REJECT ($n issue${if (n == 1) "" else "s"})"
                    submit.setBackgroundColor(Color.parseColor("#EF4444"))
                    submit.setTextColor(Color.WHITE)
                }
            }
        }

        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, (6 * dp).toInt())
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(this).apply {
                text = item.label
                textSize = 14f
                setTextColor(Color.parseColor("#111827"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!item.dataUrl.isNullOrEmpty()) {
                head.addView(Button(this).apply {
                    text = "View"
                    textSize = 11f
                    setBackgroundColor(Color.parseColor("#EEF2FF"))
                    setTextColor(Color.parseColor("#4338CA"))
                    setOnClickListener { showImagePreviewDialog(item.label, item.dataUrl) }
                })
            } else {
                head.addView(TextView(this).apply {
                    text = "No file"
                    textSize = 11f
                    setTextColor(Color.parseColor("#EF4444"))
                })
            }
            row.addView(head)

            val status = TextView(this).apply { textSize = 12f; setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }
            row.addView(status)

            val good = Button(this).apply {
                text = "✓ Good"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply { marginEnd = (4 * dp).toInt() }
            }
            val bad = Button(this).apply {
                text = "✗ Problem"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply { marginStart = (4 * dp).toInt() }
            }

            fun paint() {
                val r = reviews[item.key]
                when {
                    r == null -> { status.text = "Not reviewed yet"; status.setTextColor(Color.parseColor("#6B7280")) }
                    r.ok -> { status.text = "✓ Looks good"; status.setTextColor(Color.parseColor("#059669")) }
                    else -> { status.text = "✗ ${r.comment}"; status.setTextColor(Color.parseColor("#DC2626")) }
                }
                good.setBackgroundColor(Color.parseColor(if (r?.ok == true) "#10B981" else "#D1FAE5"))
                good.setTextColor(if (r?.ok == true) Color.WHITE else Color.parseColor("#065F46"))
                bad.setBackgroundColor(Color.parseColor(if (r != null && !r.ok) "#EF4444" else "#FEE2E2"))
                bad.setTextColor(if (r != null && !r.ok) Color.WHITE else Color.parseColor("#991B1B"))
            }

            good.setOnClickListener {
                reviews[item.key] = ReviewLogic.ItemReview(true)
                paint(); refreshSubmit()
            }
            bad.setOnClickListener {
                askReason(item.label, reasons) { comment ->
                    reviews[item.key] = ReviewLogic.ItemReview(false, comment)
                    paint(); refreshSubmit()
                }
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(good); addView(bad)
            })
            paint()
            parent.addView(row)
        }

        submit.setOnClickListener {
            val decision = ReviewLogic.decide(reviews)
            if (decision == ReviewLogic.Decision.INCOMPLETE) {
                Toast.makeText(this, "Mark every item ✓ or ✗ (an ✗ needs a reason).", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle(if (decision == ReviewLogic.Decision.APPROVED) "Approve?" else "Send rejection?")
                .setMessage(
                    if (decision == ReviewLogic.Decision.APPROVED) "Everything is marked good."
                    else "The user will see:\n" + ReviewLogic.summary(items.associate { it.key to it.label }, reviews)
                )
                .setPositiveButton("Confirm") { _, _ -> onSubmit(decision, reviews) }
                .setNegativeButton("Back", null)
                .show()
        }
        refreshSubmit()
        parent.addView(submit)
    }

    /** Preset reasons first; "Other..." lets the admin type their own. */
    private fun askReason(label: String, reasons: List<String>, onPicked: (String) -> Unit) {
        val options = (reasons + "Other (type a reason)").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("What is wrong with the $label?")
            .setItems(options) { _, which ->
                if (which < reasons.size) {
                    onPicked(reasons[which])
                } else {
                    val input = EditText(this).apply {
                        hint = "Explain the problem"
                        setPadding(48, 32, 48, 32)
                    }
                    AlertDialog.Builder(this)
                        .setTitle(label)
                        .setView(input)
                        .setPositiveButton("Save") { _, _ ->
                            val text = input.text.toString().trim()
                            if (text.isEmpty()) {
                                Toast.makeText(this, "A reason is required for an ✗.", Toast.LENGTH_SHORT).show()
                            } else onPicked(text)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun finalizeDiscountReview(
        requestId: String,
        userId: String,
        decision: ReviewLogic.Decision,
        reviews: Map<String, ReviewLogic.ItemReview?>
    ) {
        val approve = decision == ReviewLogic.Decision.APPROVED
        val statusRef = database.child("discount_requests").child(requestId).child("status")
        statusRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(mutableData: MutableData): Transaction.Result {
                val v = mutableData.getValue(String::class.java)
                // Only a request still waiting for review can be decided (no flipping later).
                if (v != "PENDING" && v != "PENDING_REVIEW") return Transaction.abort()
                mutableData.value = if (approve) "APPROVED" else "REJECTED"
                return Transaction.success(mutableData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                if (!committed) {
                    if (error != null) Toast.makeText(this@AdminHomeActivity, "Failed: ${error.message}", Toast.LENGTH_SHORT).show()
                    return
                }
                val updates = hashMapOf<String, Any>(
                    "discount_requests/$requestId/review" to ReviewLogic.toMap(reviews),
                    "discount_requests/$requestId/reviewedAt" to System.currentTimeMillis()
                )
                if (approve) updates["users/$userId/hasDiscount"] = true
                database.updateChildren(updates)

                if (approve) {
                    NotificationStore.push(
                        database, userId, NotificationStore.TYPE_ID_REVIEW,
                        "Registration Complete",
                        "Your ID was approved. Enjoy your 20% discount on solo rides."
                    )
                    Toast.makeText(this@AdminHomeActivity, "Discount approved.", Toast.LENGTH_SHORT).show()
                } else {
                    val why = ReviewLogic.summary(mapOf("front" to "Front ID", "back" to "Back ID"), reviews)
                    NotificationStore.push(
                        database, userId, NotificationStore.TYPE_ID_REVIEW,
                        "ID not approved", "$why. Please re-upload the marked photo(s) in Promos."
                    )
                    Toast.makeText(this@AdminHomeActivity, "Application rejected.", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun finalizeDriverReview(
        uid: String,
        decision: ReviewLogic.Decision,
        reviews: Map<String, ReviewLogic.ItemReview?>
    ) {
        val now = System.currentTimeMillis()
        val updates = hashMapOf<String, Any>(
            "docReview" to ReviewLogic.toMap(reviews),
            "docReviewedAt" to now
        )
        val approve = decision == ReviewLogic.Decision.APPROVED
        if (approve) {
            updates["isApproved"] = true
            updates["isActive"] = true
            updates["docReviewStatus"] = "APPROVED"
        } else {
            // Back to "needs documents": the driver re-uploads only what was marked ✗.
            updates["documentsSubmitted"] = false
            updates["docReviewStatus"] = "REJECTED"
        }
        database.child("users").child(uid).updateChildren(updates)
            .addOnSuccessListener {
                if (approve) {
                    NotificationStore.push(
                        database, uid, NotificationStore.TYPE_DRIVER_REVIEW,
                        "You're approved", "Your documents were approved. You can go online and accept rides."
                    )
                    Toast.makeText(this, "Driver approved successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    val why = ReviewLogic.summary(
                        mapOf("license" to "Driver's License", "registration" to "OR/CR", "nbi" to "NBI Clearance", "photo" to "1x1 Photo"), reviews
                    )
                    NotificationStore.push(
                        database, uid, NotificationStore.TYPE_DRIVER_REVIEW,
                        "Documents need changes", "$why. Please re-upload the marked documents."
                    )
                    Toast.makeText(this, "Rejection sent to the driver.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_SHORT).show() }
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
            database.child("discount_requests").removeEventListener(it)
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