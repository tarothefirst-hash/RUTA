package com.ruta.app.ui.driver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.firebase.auth.FirebaseAuth
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.ruta.app.R
import com.ruta.app.databinding.FragmentDriverHomeBinding
import com.ruta.app.driver.DriverLocationService
import com.ruta.app.model.Booking
import com.ruta.app.ui.ChatActivity
import com.ruta.app.ui.DirectionsHelper
import com.ruta.app.util.DeviationApproval
import com.google.firebase.database.ServerValue
import com.ruta.app.ui.TrackableTarget
import com.ruta.app.util.EmergencyContactManager
import com.ruta.app.util.MapMarkerUtils
import com.ruta.app.util.MapsApi
import com.ruta.app.util.NoShowManager
import com.ruta.app.util.RideshareFareCalculator
import com.ruta.app.util.RideshareManager
import com.ruta.app.util.RideshareMapStyle
import com.ruta.app.util.RideshareRouteOptimizer
import com.ruta.app.util.RouteDeviationManager
import com.ruta.app.util.WalletManager
import java.util.Locale
import java.util.concurrent.Executors
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.ruta.app.util.BookingClaim
import com.ruta.app.util.PendingRequestRanker
class DriverHomeFragment : Fragment(), OnMapReadyCallback {

    companion object {
        private const val PICKUP_RADIUS_METERS = 100.0
        private const val DROPOFF_RADIUS_METERS = 60.0
    }

    private enum class DriverRideState {
        IDLE,
        REQUEST_RECEIVED,
        ACCEPTED,
        IN_PROGRESS
    }

    private var _binding: FragmentDriverHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private lateinit var database: DatabaseReference
    private var syncOffsetMs = 0L
    private lateinit var auth: FirebaseAuth

    private var driverMarker: Marker? = null
    private var pickupMarker: Marker? = null
    private var dropoffMarker: Marker? = null
    private var trackedContactMarker: Marker? = null

    private var activePolyline: Polyline? = null
    /** bookingId -> passenger name for the shared trip on screen, for map pin captions. */
    private val groupPassengerNames = mutableMapOf<String, String>()
    private var rawRoutePoints: List<LatLng> = emptyList()

    private var currentLatLng: LatLng? = null
    private var activeBookingId: String? = null
    private var currentBooking: Booking? = null
    private var currentRideState = DriverRideState.IDLE

    private var pendingRequestsListener: ValueEventListener? = null
    private var activeBookingListener: ValueEventListener? = null
    private var alertListener: ValueEventListener? = null

    private var isOnline = false
    private val locationPermissionCode = 1002
    private val executor = Executors.newSingleThreadExecutor()
    private val declinedBookingIds = mutableSetOf<String>()
    private val declinedGroupIds = mutableSetOf<String>()
    private var wasDeviated = false

    // ---- Navigation / reroute state ----
    private var navigator: DriverNavigator? = null
    private var followMode = true
    private var lastNavKey: String? = null
    private var lastFix: Location? = null
    private var deviatedAt = 0L
    private var suppressDeviationUntil = 0L
    private var returnPromptShown = false
    private val deviationResponses = mutableMapOf<String, String?>()
    private val deviationListeners = mutableMapOf<String, ValueEventListener>()
    private val deviationHandler = Handler(Looper.getMainLooper())
    private var alertTrackRef: DatabaseReference? = null
    private var alertTrackListener: ValueEventListener? = null
    private var driverSosSent = false
    private var lastPersistedIds = "\u0000" // forces the first write to overwrite any stale value

    private val targetsList = mutableListOf<TrackableTarget>()
    private var currentTargetIndex = 0
    private var contactLocationRef: DatabaseReference? = null
    private var contactLocationListener: ValueEventListener? = null
    private var activeEmergencyUid: String? = null
    private var isBlinking = false
    private val blinkHandler = Handler(Looper.getMainLooper())
    private var isSheetMinimized = false

    private var currentGroupStops: List<RideshareManager.RideshareStop> = emptyList()
    private var currentStopIndex: Int = 0
    private var arrivalHandledThisStop: Boolean = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            val b = _binding ?: return

            val target = currentPickupTarget()
            if (target == null || target.first != timerBookingId) {
                stopPickupTimer()
                return
            }

            val left = timerArrivedAt + NoShowManager.WAIT_MS - System.currentTimeMillis()

            b.txtPickupTimer.visibility = View.VISIBLE
            if (left > 0) {
                b.txtPickupTimer.text = String.format(
                    Locale.US,
                    "Waiting for passenger %d:%02d",
                    left / 60000,
                    (left / 1000) % 60
                )
                b.btnReportNoShow.visibility = View.GONE
            } else {
                b.txtPickupTimer.text = "Wait time is over"
                b.btnReportNoShow.visibility = View.VISIBLE
            }
            timerHandler.postDelayed(this, 1000)
        }
    }
    private var timerBookingId: String? = null
    private var timerArrivedAt = 0L
    private var noShowHandling = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = FirebaseDatabase.getInstance().reference
        FirebaseDatabase.getInstance().getReference(".info/serverTimeOffset")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { syncOffsetMs = s.getValue(Long::class.java) ?: 0L }
                override fun onCancelled(e: DatabaseError) {}
            })
        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        WindowCompat.setDecorFitsSystemWindows(requireActivity().window, false)

        val mapFragment = childFragmentManager.findFragmentById(R.id.driverMap) as SupportMapFragment?
        mapFragment?.getMapAsync(this)

        setupOnlineSwitch()
        setupLocationCallback()
        setupBookingActionListeners()
        setupSheetAndTargetControls()
        setupNavigationUi()

        val myUid = auth.currentUser?.uid
        if (myUid != null) {
            targetsList.clear()
            targetsList.add(TrackableTarget(myUid, "Me", isSelf = true))
            loadTrustedContacts(myUid)
            startListeningForEmergencyAlerts(myUid)
        }
    }

    override fun onResume() {
        super.onResume()
        val savedOnlineState = requireContext().getSharedPreferences("DRIVER_SESSION", Context.MODE_PRIVATE)
            .getBoolean("IS_ONLINE", false)
        if (savedOnlineState) {
            _binding?.switchOnline?.isChecked = true
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = true
        mMap.setOnCameraMoveStartedListener { reason ->
            // The driver dragged the map: stop auto-follow until they tap recenter.
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) followMode = false
        }
        checkLocationPermission()
    }

    private fun setupSheetAndTargetControls() {
        val toggleCard = {
            val b = _binding
            if (b != null) {
                isSheetMinimized = !isSheetMinimized
                b.layoutCardContent.visibility = if (isSheetMinimized) View.GONE else View.VISIBLE
                b.btnMinimizeCard.setImageResource(
                    if (isSheetMinimized) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
                )
            }
        }

        binding.btnMinimizeCard.setOnClickListener { toggleCard() }
        binding.layoutCardHeader.setOnClickListener { toggleCard() }

        binding.btnMyLocation.setOnClickListener {
            followMode = true
            val selfIndex = targetsList.indexOfFirst { it.isSelf }
            if (selfIndex != -1) {
                switchTarget(selfIndex)
            }
            val fix = lastFix
            val here = currentLatLng
            if (lastNavKey != null && fix != null && here != null) followCamera(fix, here)
            else recenterMapOnCurrentState()
        }

        binding.btnPrevContact.setOnClickListener {
            if (currentRideState == DriverRideState.ACCEPTED || currentRideState == DriverRideState.IN_PROGRESS) {
                Toast.makeText(context, "Locked to active ride route.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (targetsList.isNotEmpty()) {
                val prev = if (currentTargetIndex - 1 < 0) targetsList.size - 1 else currentTargetIndex - 1
                switchTarget(prev)
            }
        }

        binding.btnNextContact.setOnClickListener {
            if (currentRideState == DriverRideState.ACCEPTED || currentRideState == DriverRideState.IN_PROGRESS) {
                Toast.makeText(context, "Locked to active ride route.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (targetsList.isNotEmpty()) {
                val next = (currentTargetIndex + 1) % targetsList.size
                switchTarget(next)
            }
        }
    }

    private fun recenterMapOnCurrentState() {
        if (!::mMap.isInitialized) return

        if (currentRideState == DriverRideState.ACCEPTED || currentRideState == DriverRideState.IN_PROGRESS) {
            val curBooking = currentBooking
            if (curBooking != null) {
                if (currentRideState == DriverRideState.ACCEPTED) {
                    drawDriverToPickupRoute(curBooking)
                } else {
                    drawDriverToDropoffRoute(curBooking)
                }
                return
            }
        }

        val target = targetsList.getOrNull(currentTargetIndex)
        if (target == null || target.isSelf) {
            currentLatLng?.let { pos ->
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 16f))
            }
        } else {
            trackedContactMarker?.position?.let { pos ->
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 16f))
            }
        }
    }

    private fun loadTrustedContacts(myUid: String) {
        database.child("users").child(myUid).child("trustedContacts").get()
            .addOnSuccessListener { snapshot ->
                snapshot.children.mapNotNull { it.key }.forEach { contactUid ->
                    if (targetsList.none { it.uid == contactUid }) {
                        database.child("users").child(contactUid).child("name").get()
                            .addOnSuccessListener { nameSnap ->
                                val name = nameSnap.getValue(String::class.java)?.ifEmpty { null } ?: "Trusted Contact"
                                targetsList.add(TrackableTarget(contactUid, name))
                            }
                    }
                }
            }
    }

    private fun switchTarget(index: Int) {
        if (index !in targetsList.indices || !::mMap.isInitialized) return
        currentTargetIndex = index
        val target = targetsList[index]

        _binding?.txtCurrentTargetIndicator?.text = "Pin: ${target.name}"
        detachContactListener()
        detachAlertTracking()
        _binding?.btnBackToMyScreen?.visibility = if (target.isSelf) View.GONE else View.VISIBLE
        if (!target.isSelf) followMode = false

        if (target.isSelf) {
            currentLatLng?.let { pos ->
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 16f))
            }
        } else {
            startTrackingContactOnMap(target)
        }
    }

    private fun startTrackingContactOnMap(target: TrackableTarget) {
        contactLocationRef = database.child("users").child(target.uid).child("liveLocation")
        contactLocationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lat = snapshot.child("lat").getValue(Double::class.java)
                val lng = snapshot.child("lng").getValue(Double::class.java)

                if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                    val pos = LatLng(lat, lng)
                    updateContactMarkerOnMap(pos, target.name, target.uid)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        contactLocationRef?.addValueEventListener(contactLocationListener!!)
    }

    private fun updateContactMarkerOnMap(pos: LatLng, name: String, uid: String) {
        if (!::mMap.isInitialized) return

        val isEmergencyTarget = (uid == activeEmergencyUid)
        val hue = if (isEmergencyTarget) BitmapDescriptorFactory.HUE_RED else BitmapDescriptorFactory.HUE_VIOLET

        if (trackedContactMarker == null) {
            trackedContactMarker = mMap.addMarker(
                MarkerOptions()
                    .position(pos)
                    .title(if (isEmergencyTarget) "🚨 EMERGENCY: $name" else name)
                    .icon(BitmapDescriptorFactory.defaultMarker(hue))
            )
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 16f))
        } else {
            trackedContactMarker?.position = pos
            trackedContactMarker?.setIcon(BitmapDescriptorFactory.defaultMarker(hue))
        }

        if (isEmergencyTarget && !isBlinking) {
            startBlinkingMarker()
        }
    }

    private fun startListeningForEmergencyAlerts(myUid: String) {
        alertListener = EmergencyContactManager.listenForIncomingAlertsDetailed(database, myUid) { alert ->
            if (!isAdded || _binding == null) return@listenForIncomingAlertsDetailed
            showIncomingAlert(alert)
        }
    }

    /**
     * An alert from someone who lists this driver as a trusted contact. It is only a warning:
     * a banner + sound, the driver's own map, route and voice guidance stay exactly as they
     * are. "Track on map" is opt-in, and "Back to my screen" undoes it.
     */
    private fun showIncomingAlert(a: EmergencyContactManager.IncomingAlert) {
        val b = _binding ?: return
        triggerAlertSoundAndVibration()

        b.txtAlertTitle.text = "🚨 Emergency alert from ${a.senderName}"
        b.txtAlertBody.text = buildString {
            append("Vehicle: ${a.vehicleInfo}")
            if (a.pickupAddress != "N/A") append("\nFrom: ${a.pickupAddress}")
            if (a.dropoffAddress != "N/A") append("\nTo: ${a.dropoffAddress}")
            if (currentRideState == DriverRideState.ACCEPTED || currentRideState == DriverRideState.IN_PROGRESS) {
                append("\nYour current trip and navigation are not affected.")
            }
        }
        b.cardAlertBanner.visibility = View.VISIBLE
        b.btnAlertTrack.setOnClickListener {
            b.cardAlertBanner.visibility = View.GONE
            trackAlert(a)
        }
        navigator?.speak("Emergency alert received from ${a.senderName}.", force = true)
    }

    /** Opt-in: follow the alert sender's vehicle on the map until "Back to my screen". */
    private fun trackAlert(a: EmergencyContactManager.IncomingAlert) {
        if (!::mMap.isInitialized) return
        detachContactListener()
        detachAlertTracking()
        val uid = a.senderUid.ifEmpty { "alert" }
        activeEmergencyUid = uid
        followMode = false
        _binding?.btnBackToMyScreen?.visibility = View.VISIBLE
        _binding?.txtCurrentTargetIndicator?.text = "Pin: ${a.senderName}"

        if (a.lat != 0.0 || a.lng != 0.0) {
            updateContactMarkerOnMap(LatLng(a.lat, a.lng), a.senderName, uid)
        }
        if (a.bookingId.isNotEmpty()) {
            val ref = database.child("bookings").child(a.bookingId)
            val l = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val lat = snapshot.child("driverLat").getValue(Double::class.java)
                    val lng = snapshot.child("driverLng").getValue(Double::class.java)
                    if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                        updateContactMarkerOnMap(LatLng(lat, lng), a.senderName, uid)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            alertTrackRef = ref
            alertTrackListener = l
            ref.addValueEventListener(l)
        }
    }

    private fun detachAlertTracking() {
        alertTrackListener?.let { alertTrackRef?.removeEventListener(it) }
        alertTrackListener = null
        alertTrackRef = null
        if (activeEmergencyUid != null) {
            stopBlinkingMarker()
            trackedContactMarker?.remove()
            trackedContactMarker = null
            activeEmergencyUid = null
        }
    }

    private fun backToMyScreen() {
        detachAlertTracking()
        _binding?.btnBackToMyScreen?.visibility = View.GONE
        followMode = true
        val selfIndex = targetsList.indexOfFirst { it.isSelf }
        if (selfIndex != -1) switchTarget(selfIndex)
        val fix = lastFix
        val here = currentLatLng
        if (fix != null && here != null && lastNavKey != null) followCamera(fix, here)
        else here?.let { mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 16f)) }
    }

    private fun triggerAlertSoundAndVibration() {
        val ctx = context ?: return
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(ctx, notificationUri)
            ringtone?.play()
        } catch (_: Exception) {}

        try {
            val pattern = longArrayOf(0, 500, 200, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(pattern, -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {}
    }

    private fun startBlinkingMarker() {
        isBlinking = true
        val runnable = object : Runnable {
            var visible = true
            override fun run() {
                trackedContactMarker?.isVisible = visible
                visible = !visible
                if (isBlinking) {
                    blinkHandler.postDelayed(this, 500)
                }
            }
        }
        blinkHandler.post(runnable)
    }

    private fun stopBlinkingMarker() {
        isBlinking = false
        blinkHandler.removeCallbacksAndMessages(null)
        trackedContactMarker?.isVisible = true
    }

    private fun detachContactListener() {
        stopBlinkingMarker()
        contactLocationListener?.let { contactLocationRef?.removeEventListener(it) }
        contactLocationListener = null
        contactLocationRef = null
        trackedContactMarker?.remove()
        trackedContactMarker = null
    }

    private fun setupOnlineSwitch() {
        binding.switchOnline.setOnCheckedChangeListener { switchView, isChecked ->
            if (isChecked) {
                val uid = auth.currentUser?.uid ?: return@setOnCheckedChangeListener

                database.child("users").child(uid).get()
                    .addOnSuccessListener { snap ->
                        val isActive = snap.child("isActive").getValue(Boolean::class.java) ?: true
                        val isApproved = snap.child("isApproved").getValue(Boolean::class.java) ?: false

                        if (!isApproved || !isActive) {
                            switchView.setOnCheckedChangeListener(null)
                            switchView.isChecked = false
                            setupOnlineSwitch()

                            if (!isApproved) {
                                val intent = Intent(requireContext(), DriverDocumentUploadActivity::class.java)
                                startActivity(intent)
                            } else {
                                Toast.makeText(context, "Your account has been deactivated by an admin.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            goOnline()
                        }
                    }
                    .addOnFailureListener {
                        switchView.setOnCheckedChangeListener(null)
                        switchView.isChecked = false
                        setupOnlineSwitch()

                        Toast.makeText(context, "Couldn't verify your account status — try again.", Toast.LENGTH_SHORT).show()
                    }
            } else {
                if (currentRideState == DriverRideState.ACCEPTED || currentRideState == DriverRideState.IN_PROGRESS) {
                    switchView.setOnCheckedChangeListener(null)
                    switchView.isChecked = true
                    setupOnlineSwitch()

                    Toast.makeText(
                        requireContext(),
                        "Cannot go offline during an active trip.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnCheckedChangeListener
                }

                goOffline()
            }
        }
    }

    private fun goOnline() {
        isOnline = true
        requireContext().getSharedPreferences("DRIVER_SESSION", Context.MODE_PRIVATE)
            .edit().putBoolean("IS_ONLINE", true).apply()
        updateDriverOnlineStatus(true)
        _binding?.txtDriverStatus?.text = "Status: Online"

        DriverLocationService.startService(requireContext())

        startLocationUpdates()
        checkAndRestoreActiveDriverSession()
    }

    private fun goOffline() {
        isOnline = false
        requireContext().getSharedPreferences("DRIVER_SESSION", Context.MODE_PRIVATE)
            .edit().putBoolean("IS_ONLINE", false).apply()
        updateDriverOnlineStatus(false)
        _binding?.txtDriverStatus?.text = "Status: Offline"

        DriverLocationService.stopService(requireContext())

        stopLocationUpdates()
        stopListeningForRequests()
        clearRouteAndMarkers()
        updateUIForState(DriverRideState.IDLE)
        declinedBookingIds.clear()
        declinedGroupIds.clear()
    }

    private fun getFormattedServiceTitle(booking: Booking): String {
        val serviceType = booking.serviceType ?: ""
        return when {
            serviceType.equals("SOLO", ignoreCase = true) -> "Solo Ride"
            serviceType.equals("SHARED", ignoreCase = true) -> "Shared Ride"
            else -> if (serviceType.isNotEmpty()) serviceType else "Solo Ride"
        }
    }

    /** The rider's own photo (initials if none): the driver sees it on the trip card while it is their stop. */
    private fun showPassengerAvatar(passengerId: String, name: String) {
        val img = _binding?.imgPassengerAvatar ?: return
        val px = (44 * resources.displayMetrics.density).toInt()
        com.ruta.app.util.StoragePhotos.showAvatar(img, null, name, px)
        if (passengerId.isEmpty()) return
        database.child("users").child(passengerId).child("profilePhoto").get().addOnSuccessListener { s ->
            val still = _binding?.imgPassengerAvatar ?: return@addOnSuccessListener
            if (still !== img) return@addOnSuccessListener
            com.ruta.app.util.StoragePhotos.showAvatar(img, s.getValue(String::class.java), name, px)
        }
    }

    private fun bindPassengerInfoToView(booking: Booking, appendTitle: String = "") {
        val b = _binding ?: return
        if (!isAdded) return

        val serviceTitle = getFormattedServiceTitle(booking)
        val nameText = booking.passengerName.ifEmpty { "Passenger" }
        showPassengerAvatar(booking.passengerId, nameText)
        val discounted = booking.hasDiscount && !booking.isShared
        val discountTag = if (discounted) " • 20% DISCOUNT (ID)" else ""
        b.txtPassengerName.text = if (appendTitle.isNotEmpty()) "$nameText ($appendTitle)" else "$nameText\n$serviceTitle$discountTag"

        b.txtPickupLocation.text = "Pickup: ${booking.pickupAddress.ifEmpty { "N/A" }}"
        b.txtDropoffLocation.text = "Dropoff: ${booking.dropoffAddress.ifEmpty { "N/A" }}"

        val finalFare = booking.fare
        val originalFare = if (booking.fareBeforeDiscount > 0.0) booking.fareBeforeDiscount else (finalFare + booking.discountAmount)

        if (booking.discountAmount > 0.0) {
            val htmlText = String.format(
                Locale.getDefault(),
                "Estimated Fare: <s>₱%.2f</s> <b>₱%.2f</b> <font color='#16A34A'>(₱%.2f off)</font>",
                originalFare,
                finalFare,
                booking.discountAmount
            )
            b.txtEstimatedFare.text = Html.fromHtml(htmlText, Html.FROM_HTML_MODE_COMPACT)
        } else {
            b.txtEstimatedFare.text = String.format(Locale.getDefault(), "Estimated Fare: ₱%.2f", finalFare)
            if (discounted) b.txtEstimatedFare.append("  (20% ID discount applied)")
        }

        if (booking.noShowFee > 0.0) {
            val feeNotice = String.format(
                Locale.getDefault(),
                "\n+ ₱%.2f unpaid no-show fee (collect from passenger)",
                booking.noShowFee
            )
            b.txtEstimatedFare.append(feeNotice)
        }

        val commission = WalletManager.commissionFor(finalFare, discounted)
        val netEarnings = finalFare - commission

        b.txtWalletBalance.text = String.format(
            Locale.getDefault(),
            "Deduction: -₱%.2f (%d%%) • Take-Home: ₱%.2f",
            commission,
            if (discounted) 4 else 10,
            netEarnings
        )

        val passengerId = booking.passengerId
        if (passengerId.isNotEmpty()) {
            database.child("users").child(passengerId).get().addOnSuccessListener { snapshot ->
                if (_binding == null || !isAdded) return@addOnSuccessListener
                val phone = snapshot.child("phone").value?.toString()
                    ?: snapshot.child("phoneNumber").value?.toString()
                    ?: ""
                if (phone.isNotEmpty()) {
                    _binding?.txtPassengerName?.text = "${_binding?.txtPassengerName?.text}\nContact: $phone"
                }
            }
        }
    }

    private fun checkAndRestoreActiveDriverSession() {
        val driverId = auth.currentUser?.uid ?: return

        // get() asks the server first. A one-time listener could answer from the on-disk cache,
        // which after an app restart still held the booking from BEFORE it was accepted.
        database.child("bookings")
            .orderByChild("driverId")
            .equalTo(driverId)
            .get()
            .addOnSuccessListener { snapshot ->
                    if (_binding == null || !isAdded) return@addOnSuccessListener

                    var restoredBooking: Booking? = null
                    var restoredBookingId: String? = null
                    var restoredStatus = ""

                    for (child in snapshot.children) {
                        val status = child.child("status").getValue(String::class.java) ?: ""
                        if (status == "ACCEPTED" || status == "IN_PROGRESS") {
                            restoredBooking = child.getValue(Booking::class.java)
                            restoredBookingId = child.key
                            restoredStatus = status
                            break
                        }
                    }

                    if (restoredBooking != null) {
                        activeBookingId = restoredBookingId
                        currentBooking = restoredBooking

                        val tripGroupId = restoredBooking.tripGroupId

                        if (!tripGroupId.isNullOrEmpty()) {
                            database.child("tripGroups").child(tripGroupId).get()
                                .addOnSuccessListener { groupSnap ->
                                    if (_binding == null || !isAdded) return@addOnSuccessListener
                                    val savedIndex = groupSnap.child("currentStopIndex").getValue(Int::class.java) ?: 0
                                    loadGroupStopsLocally(tripGroupId) { stops ->
                                        currentGroupStops = stops
                                        currentStopIndex = savedIndex.coerceIn(0, (stops.size - 1).coerceAtLeast(0))
                                        updateUIForState(DriverRideState.IN_PROGRESS)
                                        updateCardForCurrentStop()
                                        drawGroupRoutePreviewOnly(stops)
                                    }
                                }
                        } else {
                            bindPassengerInfoToView(restoredBooking)
                            if (restoredStatus == "ACCEPTED") {
                                updateUIForState(DriverRideState.ACCEPTED)
                                if (currentLatLng != null) drawDriverToPickupRoute(restoredBooking)
                            } else if (restoredStatus == "IN_PROGRESS") {
                                updateUIForState(DriverRideState.IN_PROGRESS)
                                if (currentLatLng != null) drawDriverToDropoffRoute(restoredBooking)
                            }
                        }

                        if (!restoredBookingId.isNullOrEmpty()) {
                            monitorSingleBookingForCancellation(restoredBookingId)
                        }
                    } else {
                        listenForPendingRideRequests()
                    }
            }
            .addOnFailureListener {
                if (_binding != null && isAdded) listenForPendingRideRequests()
            }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    updateDriverPinAndProgress(location)
                }
            }
        }
    }

    private fun updateDriverPinAndProgress(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)
        currentLatLng = latLng

        if (driverMarker == null) {
            driverMarker = mMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Your Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        } else {
            driverMarker?.position = latLng
        }

        if (activePolyline == null && currentBooking != null) {
            val booking = currentBooking!!
            if (currentRideState == DriverRideState.ACCEPTED) {
                drawDriverToPickupRoute(booking)
            } else if (currentRideState == DriverRideState.IN_PROGRESS) {
                drawDriverToDropoffRoute(booking)
            }
        }

        if (rawRoutePoints.isNotEmpty()) {
            trimPassedRoutePoints(latLng)
        }

        val isTripActive = (currentRideState == DriverRideState.IN_PROGRESS) ||
                (!currentBooking?.tripGroupId.isNullOrEmpty() && currentRideState == DriverRideState.ACCEPTED)

        if (isTripActive && rawRoutePoints.isNotEmpty() && System.currentTimeMillis() >= suppressDeviationUntil) {
            val isDeviated = RouteDeviationManager.isDriverDeviated(location, rawRoutePoints, activeBookingId ?: "")
            if (isDeviated && !wasDeviated) {
                wasDeviated = true
                flagTripAsDeviated()
                onDeviationStarted()
            } else if (!isDeviated && wasDeviated) {
                wasDeviated = false
                clearTripDeviation()
                onBackOnRoute()
            }
        }

        lastFix = location
        updateNavigation(location, latLng)

        checkArrivalAtPickup(latLng)

        val currentUserId = auth.currentUser?.uid ?: return
        val bearing = if (location.hasBearing()) location.bearing.toDouble() else 0.0
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0

        val locationMap = hashMapOf<String, Any>(
            "lat" to location.latitude,
            "lng" to location.longitude,
            "bearing" to bearing,
            "speed" to speed,
            "updatedAt" to ServerValue.TIMESTAMP
        )
        database.child("drivers").child(currentUserId).updateChildren(locationMap)

        // Live location goes to EVERY passenger still on the trip (a shared ride used to
        // update only the lead booking, so the second passenger saw a frozen driver).
        val liveIds = if (currentRideState == DriverRideState.ACCEPTED ||
            currentRideState == DriverRideState.IN_PROGRESS) bookingIdsOnTrip() else emptyList()
        if (liveIds.isNotEmpty()) {
            val driverLocMap = hashMapOf<String, Any>(
                "driverLat" to location.latitude,
                "driverLng" to location.longitude,
                "driverBearing" to bearing,
                "driverSpeed" to speed,
                "driverUpdatedAt" to ServerValue.TIMESTAMP
            )
            liveIds.forEach { id -> database.child("bookings").child(id).updateChildren(driverLocMap) }
        }
        persistActiveBookingIds(liveIds)
    }

    /** Bookings that still have a passenger to serve (shared trips: from the current stop on). */
    private fun bookingIdsOnTrip(): List<String> {
        val booking = currentBooking
        if (booking != null && !booking.tripGroupId.isNullOrEmpty() && currentGroupStops.isNotEmpty()) {
            return currentGroupStops.drop(currentStopIndex).map { it.bookingId }.distinct()
        }
        return listOfNotNull(activeBookingId)
    }

    /**
     * Lets DriverLocationService keep the passengers' live map moving if this screen is
     * ever destroyed while a trip is running (e.g. the driver opens another tab).
     */
    private fun persistActiveBookingIds(ids: List<String>) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("DRIVER_SESSION", Context.MODE_PRIVATE)
        val joined = ids.joinToString(",")
        val edit = prefs.edit().putLong("SCREEN_LIVE_AT", System.currentTimeMillis())
        if (joined != lastPersistedIds) {
            lastPersistedIds = joined
            edit.putString("ACTIVE_BOOKING_IDS", joined)
        }
        edit.apply()
    }

    // ------------------------------------------------------------------
    // Navigation UI (turn banner, voice, follow camera)
    // ------------------------------------------------------------------

    private fun setupNavigationUi() {
        navigator = DriverNavigator(
            requireContext(),
            onBanner = { arrow, distance, instruction ->
                _binding?.let {
                    it.cardNavBanner.visibility = View.VISIBLE
                    it.txtNavArrow.text = arrow
                    it.txtNavDistance.text = distance
                    it.txtNavInstruction.text = instruction
                }
            },
            onIdle = { _binding?.cardNavBanner?.visibility = View.GONE }
        )
        updateMuteIcon()
        binding.btnMuteVoice.setOnClickListener {
            val nav = navigator ?: return@setOnClickListener
            nav.setMuted(!nav.muted)
            updateMuteIcon()
            Toast.makeText(context, if (nav.muted) "Voice guidance muted" else "Voice guidance on", Toast.LENGTH_SHORT).show()
        }
        binding.btnBackToMyScreen.setOnClickListener { backToMyScreen() }
        binding.btnDriverSos.setOnClickListener { confirmDriverSos() }
        binding.btnAlertDismiss.setOnClickListener { binding.cardAlertBanner.visibility = View.GONE }
    }

    /** Driver emergency: asks first, then alerts the DRIVER's own trusted contacts (never the riders). */
    private fun confirmDriverSos() {
        val bookingId = activeBookingId ?: bookingIdsOnTrip().firstOrNull()
        if (bookingId == null) {
            Toast.makeText(context, "No active trip to report.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("🚨 Emergency")
            .setMessage("Notify your trusted contacts with your live location and vehicle details?")
            .setPositiveButton("Notify") { _, _ ->
                driverSosSent = true
                // An emergency means no automatic reroute while it is active.
                resetDeviationFlow()
                EmergencyContactManager.sendEmergencyAlertForBooking(database, bookingId, isDriver = true) { n ->
                    if (_binding == null) return@sendEmergencyAlertForBooking
                    Toast.makeText(
                        context,
                        if (n > 0) "Alert sent to $n trusted contact${if (n == 1) "" else "s"}."
                        else "No trusted contacts yet. Add one from your Profile.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateMuteIcon() {
        val muted = navigator?.muted == true
        _binding?.btnMuteVoice?.setImageResource(
            if (muted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off
        )
    }

    /** Where the current leg of guidance should end. */
    private fun navTarget(): LatLng? {
        val booking = currentBooking ?: return null
        if (!booking.tripGroupId.isNullOrEmpty()) return currentGroupStops.getOrNull(currentStopIndex)?.location
        return when (currentRideState) {
            DriverRideState.ACCEPTED -> LatLng(booking.pickupLat, booking.pickupLng)
            DriverRideState.IN_PROGRESS -> LatLng(booking.dropoffLat, booking.dropoffLng)
            else -> null
        }
    }

    private fun navKey(): String? {
        val booking = currentBooking ?: return null
        if (currentRideState != DriverRideState.ACCEPTED && currentRideState != DriverRideState.IN_PROGRESS) return null
        if (!booking.tripGroupId.isNullOrEmpty()) {
            val stop = currentGroupStops.getOrNull(currentStopIndex) ?: return null
            return "G:${stop.bookingId}:${stop.type}:$currentStopIndex"
        }
        return "S:${activeBookingId}:$currentRideState"
    }

    private fun updateNavigation(location: Location, latLng: LatLng) {
        val b = _binding ?: return
        val key = navKey()
        if (key == null) {
            if (lastNavKey != null) {
                lastNavKey = null
                navigator?.stop()
            }
            return
        }
        if (key != lastNavKey) {
            lastNavKey = key
            navTarget()?.let { navigator?.startLeg(latLng, it) }
        }
        navigator?.onLocation(latLng.latitude, latLng.longitude)

        val kmh = if (location.hasSpeed()) Math.round(location.speed * 3.6f) else 0
        b.txtNavSpeed.text = "$kmh\nkm/h"
        followCamera(location, latLng)
    }

    private fun followCamera(location: Location, latLng: LatLng) {
        if (!followMode || !::mMap.isInitialized) return
        val target = targetsList.getOrNull(currentTargetIndex)
        if (target != null && !target.isSelf) return
        val bearing = if (location.hasBearing() && location.speed > 1.5f) location.bearing
        else mMap.cameraPosition.bearing
        mMap.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(latLng).zoom(17.5f).tilt(45f).bearing(bearing).build()
            ),
            900,
            null
        )
    }

    // ------------------------------------------------------------------
    // Deviation -> passenger approval -> reroute (or keep the original route)
    // ------------------------------------------------------------------

    private fun onDeviationStarted() {
        deviatedAt = System.currentTimeMillis()
        returnPromptShown = false
        deviationResponses.clear()
        showDeviationStatus("⚠ You are off the route. Waiting for passenger approval. Follow the original route if it is not approved.")
        navigator?.speak("You are off route. Waiting for passenger approval.", force = true)

        detachDeviationListeners()
        bookingIdsOnTrip().forEach { id ->
            val ref = database.child("bookings").child(id)
            // Forget any answer left over from an earlier deviation, then listen for the new one.
            ref.child("deviationResponse").removeValue()
            ref.child("deviationResponseAt").removeValue()
            val l = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    deviationResponses[id] = snapshot.getValue(String::class.java)
                    evaluateDeviation(fromTimer = false)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            deviationListeners[id] = l
            ref.child("deviationResponse").addValueEventListener(l)
        }
        deviationHandler.removeCallbacksAndMessages(null)
        deviationHandler.postDelayed({ evaluateDeviation(fromTimer = true) }, 90_000L)
    }

    private fun evaluateDeviation(fromTimer: Boolean) {
        if (!wasDeviated || _binding == null || driverSosSent) return
        when (DeviationApproval.resolve(deviationResponses.values)) {
            DeviationApproval.Outcome.APPROVED -> applyApprovedReroute()
            DeviationApproval.Outcome.BLOCKED ->
                showReturnToRoute("🚨 A passenger reported an emergency, so the reroute was denied. Return to the original route (shown in red).")
            DeviationApproval.Outcome.PENDING ->
                if (fromTimer && DeviationApproval.timedOut(deviatedAt, System.currentTimeMillis())) {
                    showReturnToRoute("No passenger approval. Return to the original route (shown in red).")
                }
        }
    }

    private fun showReturnToRoute(message: String) {
        if (returnPromptShown) return
        returnPromptShown = true
        showDeviationStatus(message)
        setReturnRouteHighlight(true)
        navigator?.speak("Reroute not approved. Please return to the original route.", force = true)
    }

    /** Approved: new line from where the driver is now to the next stop, old line replaced. */
    private fun applyApprovedReroute() {
        val ids = bookingIdsOnTrip()
        wasDeviated = false
        detachDeviationListeners()
        deviationHandler.removeCallbacksAndMessages(null)
        suppressDeviationUntil = System.currentTimeMillis() + 10_000L
        ids.forEach { RouteDeviationManager.clearTripDeviationState(it) }
        clearTripDeviation(rerouted = true)
        setReturnRouteHighlight(false)

        showDeviationStatus("✔ Reroute approved. New route calculated.", autoHideMs = 6000L)
        navigator?.speak("Rerouting.", force = true)

        val here = currentLatLng ?: return
        if (!currentBooking?.tripGroupId.isNullOrEmpty()) {
            drawGroupRoutePreviewOnly(currentGroupStops)
        } else {
            navTarget()?.let { drawRoute(here, it) }
        }
        navTarget()?.let { navigator?.startLeg(here, it, announceStart = false) }
    }

    private fun onBackOnRoute() {
        detachDeviationListeners()
        deviationHandler.removeCallbacksAndMessages(null)
        setReturnRouteHighlight(false)
        if (returnPromptShown) navigator?.speak("You are back on the route.", force = true)
        showDeviationStatus("✔ Back on the route.", autoHideMs = 4000L)
    }

    private fun detachDeviationListeners() {
        deviationListeners.forEach { (id, l) ->
            database.child("bookings").child(id).child("deviationResponse").removeEventListener(l)
        }
        deviationListeners.clear()
    }

    private fun resetDeviationFlow() {
        detachDeviationListeners()
        deviationHandler.removeCallbacksAndMessages(null)
        suppressDeviationUntil = 0L
        returnPromptShown = false
        _binding?.cardDeviationStatus?.visibility = View.GONE
    }

    private fun showDeviationStatus(message: String, autoHideMs: Long = 0L) {
        val b = _binding ?: return
        b.txtDeviationStatus.text = message
        b.cardDeviationStatus.visibility = View.VISIBLE
        if (autoHideMs > 0) {
            deviationHandler.postDelayed({ _binding?.cardDeviationStatus?.visibility = View.GONE }, autoHideMs)
        }
    }

    private fun setReturnRouteHighlight(on: Boolean) {
        // Shared trips draw coloured legs plus an invisible trim line, so only single trips recolour.
        if (!currentBooking?.tripGroupId.isNullOrEmpty()) return
        activePolyline?.color = Color.parseColor(if (on) "#E53935" else "#6D5EFC")
    }

    private fun currentPickupTarget(): Pair<String, LatLng>? {
        val booking = currentBooking ?: return null
        if (booking.tripGroupId.isNullOrEmpty()) {
            if (currentRideState != DriverRideState.ACCEPTED) return null
            val id = activeBookingId ?: return null
            return id to LatLng(booking.pickupLat, booking.pickupLng)
        }
        val stop = currentGroupStops.getOrNull(currentStopIndex) ?: return null
        if (stop.type != RideshareManager.StopType.PICKUP) return null
        return stop.bookingId to stop.location
    }

    private fun checkArrivalAtPickup(driverLoc: LatLng) {
        if (arrivalHandledThisStop) return
        val (bookingId, pickupLoc) = currentPickupTarget() ?: return
        if (distanceMeters(driverLoc, pickupLoc) > PICKUP_RADIUS_METERS) return
        arrivalHandledThisStop = true
        registerArrival(bookingId)
    }

    private fun registerArrival(bookingId: String) {
        database.child("bookings").child(bookingId).child("arrivedAt")
            .runTransaction(object : Transaction.Handler {
                override fun doTransaction(d: MutableData): Transaction.Result {
                    if ((d.getValue(Long::class.java) ?: 0L) == 0L) d.value = System.currentTimeMillis()
                    return Transaction.success(d)
                }

                override fun onComplete(e: DatabaseError?, committed: Boolean, snap: DataSnapshot?) {
                    val at = snap?.getValue(Long::class.java) ?: return
                    if (_binding != null) startPickupTimer(bookingId, at)
                }
            })
    }

    private fun startPickupTimer(bookingId: String, arrivedAt: Long) {
        if (currentPickupTarget()?.first != bookingId) return
        timerBookingId = bookingId
        timerArrivedAt = arrivedAt
        timerHandler.removeCallbacks(timerTick)
        timerTick.run()
    }

    private fun onReportNoShowTapped() {
        val bookingId = timerBookingId ?: return
        val (targetId, pickupLoc) = currentPickupTarget() ?: return
        if (targetId != bookingId) return
        if (System.currentTimeMillis() < timerArrivedAt + NoShowManager.WAIT_MS) return

        val loc = currentLatLng
        if (loc == null) {
            Toast.makeText(context, "Waiting for GPS fix — try again shortly.", Toast.LENGTH_SHORT).show()
            return
        }
        if (distanceMeters(loc, pickupLoc) > PICKUP_RADIUS_METERS) {
            Toast.makeText(context, "You must be at the pickup point to report a no-show.", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Mark as no-show?")
            .setMessage("The booking will be cancelled and the passenger charged a no-show fee. You'll be credited in your Ruta wallet.")
            .setPositiveButton("Confirm") { _, _ -> processNoShow(bookingId) }
            .setNegativeButton("Wait longer", null)
            .show()
    }

    private fun processNoShow(bookingId: String) {
        val driverId = auth.currentUser?.uid ?: return
        val isShared = !currentBooking?.tripGroupId.isNullOrEmpty()

        val coPassengers: Set<String> = if (isShared) {
            currentGroupStops.drop(currentStopIndex)
                .filter { it.type == RideshareManager.StopType.PICKUP && it.bookingId != bookingId }
                .map { it.bookingId }.toSet()
        } else emptySet()
        val removed = coPassengers + bookingId

        noShowHandling = true
        _binding?.btnReportNoShow?.isEnabled = false

        NoShowManager.reportNoShow(database, driverId, bookingId, coPassengers) { ok ->
            if (_binding == null) { noShowHandling = false; return@reportNoShow }
            _binding?.btnReportNoShow?.isEnabled = true

            if (!ok) {
                noShowHandling = false
                Toast.makeText(context, "This booking was already updated.", Toast.LENGTH_SHORT).show()
                return@reportNoShow
            }

            stopPickupTimer()
            Toast.makeText(context, "No-show recorded. You'll be credited in your wallet.", Toast.LENGTH_LONG).show()

            if (!isShared) {
                noShowHandling = false
                return@reportNoShow
            }

            currentGroupStops = currentGroupStops.filter { it.bookingId !in removed }
            arrivalHandledThisStop = false

            if (currentGroupStops.getOrNull(currentStopIndex) == null) {
                resetAfterNoShow()
            } else {
                updateCardForCurrentStop()
                drawGroupRoutePreviewOnly(currentGroupStops)
            }
            noShowHandling = false
        }
    }

    private fun resetAfterNoShow() {
        stopListeningForRequests()
        clearRouteAndMarkers()
        activeBookingId?.let { RouteDeviationManager.clearTripDeviationState(it) }
        activeBookingId = null
        currentBooking = null
        currentGroupStops = emptyList()
        currentStopIndex = 0
        updateUIForState(DriverRideState.IDLE)
        listenForPendingRideRequests()
    }

    private fun stopPickupTimer() {
        timerHandler.removeCallbacks(timerTick)
        timerBookingId = null
        timerArrivedAt = 0L
        _binding?.let {
            it.txtPickupTimer.visibility = View.GONE
            it.btnReportNoShow.visibility = View.GONE
        }
    }

    private fun flagTripAsDeviated() {
        val deviationUpdates = hashMapOf<String, Any>(
            "routeStatus" to "DEVIATED",
            "lastDeviatedLat" to (currentLatLng?.latitude ?: 0.0),
            "lastDeviatedLng" to (currentLatLng?.longitude ?: 0.0),
            "deviatedTimestamp" to System.currentTimeMillis()
        )
        bookingIdsOnTrip().forEach { id ->
            database.child("bookings").child(id).updateChildren(deviationUpdates)
        }
    }

    /** Back to NORMAL; [rerouted] also stamps `reroutedAt` so passenger maps redraw the new line once. */
    private fun clearTripDeviation(rerouted: Boolean = false) {
        val updates = hashMapOf<String, Any>("routeStatus" to "NORMAL")
        if (rerouted) updates["reroutedAt"] = System.currentTimeMillis()
        bookingIdsOnTrip().forEach { id ->
            val ref = database.child("bookings").child(id)
            ref.updateChildren(updates)
            ref.child("deviationResponse").removeValue()
            ref.child("deviationResponseAt").removeValue()
        }
    }

    private fun trimPassedRoutePoints(driverPos: LatLng) {
        if (rawRoutePoints.size <= 1) return

        var closestIndex = 0
        var minDistance = Float.MAX_VALUE

        for (i in rawRoutePoints.indices) {
            val results = FloatArray(1)
            Location.distanceBetween(
                driverPos.latitude, driverPos.longitude,
                rawRoutePoints[i].latitude, rawRoutePoints[i].longitude,
                results
            )
            if (results[0] < minDistance) {
                minDistance = results[0]
                closestIndex = i
            }
        }

        if (closestIndex > 0 && minDistance < 50) {
            val remainingPoints = mutableListOf(driverPos)
            remainingPoints.addAll(rawRoutePoints.subList(closestIndex, rawRoutePoints.size))
            rawRoutePoints = remainingPoints

            activePolyline?.points = rawRoutePoints
        }
    }

    /**
     * Bumped only when the map is cleared (ride accepted, picked up, cancelled). A route answer
     * that was asked for before the clear is dropped, so a slow old preview can no longer draw
     * over the new ride. Several requests within the same stage all apply, newest answer wins.
     */
    @Volatile private var routeGeneration = 0

    private fun drawRoute(origin: LatLng, destination: LatLng) {
        val gen = routeGeneration
        executor.execute {
            val apiKey = MapsApi.key(requireContext())
            val urlString = DirectionsHelper.getDirectionsUrl(origin, destination, apiKey)
            val jsonResponse = DirectionsHelper.downloadUrl(urlString)
            val pathPoints = DirectionsHelper.parseDirections(jsonResponse)

            activity?.runOnUiThread {
                if (pathPoints.isNotEmpty() && _binding != null && gen == routeGeneration) {
                    activePolyline?.remove()
                    rawRoutePoints = pathPoints

                    val polylineOptions = PolylineOptions()
                        .addAll(pathPoints)
                        .width(12f)
                        .color(Color.parseColor("#6D5EFC"))
                        .geodesic(true)

                    activePolyline = mMap.addPolyline(polylineOptions)
                }
            }
        }
    }

    private fun setupBookingActionListeners() {
        binding.btnAcceptRide.setOnClickListener {
            val driverId = auth.currentUser?.uid ?: return@setOnClickListener
            val tripGroupId = currentBooking?.tripGroupId
            if (!tripGroupId.isNullOrEmpty()) acceptRideGroup(driverId) else acceptCurrentRide()
        }
        binding.btnReportNoShow.setOnClickListener { onReportNoShowTapped() }
        binding.btnDeclineRide.setOnClickListener { declineCurrentRide() }
        binding.btnConfirmPickup.setOnClickListener { onConfirmStopTapped() }
        binding.btnCompleteRide.setOnClickListener { attemptCompleteRide() }
        binding.btnChatWithPassenger.setOnClickListener { openChatWithPassenger() }
    }

    private fun onConfirmStopTapped() {
        val booking = currentBooking ?: return
        if (booking.tripGroupId.isNullOrEmpty()) {
            confirmPassengerPickup()
        } else {
            confirmSharedStop(isAuto = false)
        }
    }

    private fun openChatWithPassenger() {
        val booking = currentBooking ?: return
        val intent = Intent(requireContext(), ChatActivity::class.java).apply {
            putExtra("BOOKING_ID", booking.bookingId)
            putExtra("RECEIVER_ID", booking.passengerId)
            putExtra("RECEIVER_NAME", booking.passengerName)
        }
        startActivity(intent)
    }

    private fun showGroupRequestPreview(booking: Booking) {
        val tripGroupId = booking.tripGroupId ?: return

        database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (_binding == null || !isAdded) return

                    val group = snapshot.children.mapNotNull { it.getValue(Booking::class.java) }
                        .filter { it.status != "COMPLETED" && it.status != "CANCELLED" }

                    if (group.isEmpty()) {
                        updateUIForState(DriverRideState.IDLE)
                        clearRouteAndMarkers()
                        activeBookingId = null
                        currentBooking = null
                        return
                    }

                    if (group.size < 2) {
                        bindPassengerInfoToView(booking, "Shared Ride")
                        previewBookingRoute(booking)
                        return
                    }

                    val infoList = group.joinToString("\n---\n") { item ->
                        "• ${item.passengerName.ifEmpty { "Passenger" }}: Pickup at ${item.pickupAddress.ifEmpty { "N/A" }}"
                    }

                    val totalFare = group.sumOf { it.fare }
                    val totalCommission = group.sumOf { WalletManager.commissionFor(it.fare, false) }
                    val totalNetEarnings = totalFare - totalCommission

                    _binding?.txtPassengerName?.text = "Shared Ride (${group.size} Pax)"
                    _binding?.txtPickupLocation?.text = infoList
                    _binding?.txtDropoffLocation?.text = "Dropoffs: ${group.joinToString(" -> ") { it.dropoffAddress.ifEmpty { "N/A" } }}"

                    _binding?.txtEstimatedFare?.text = String.format(
                        Locale.getDefault(), "Estimated Fare: ₱%.2f (combined)", totalFare
                    )

                    _binding?.txtWalletBalance?.text = String.format(
                        Locale.getDefault(),
                        "Deduction: -₱%.2f • Take-Home: ₱%.2f",
                        totalCommission,
                        totalNetEarnings
                    )

                    previewGroupRoute(tripGroupId, group)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun previewGroupRoute(tripGroupId: String, group: List<Booking>) {
        if (::mMap.isInitialized) {
            mMap.clear()
            driverMarker = null
        }
        activePolyline?.remove()
        activePolyline = null

        val bounds = LatLngBounds.Builder()
        val candidates = group.map {
            RideshareManager.RideshareCandidate(
                it.bookingId, it.passengerId,
                LatLng(it.pickupLat, it.pickupLng), LatLng(it.dropoffLat, it.dropoffLng)
            )
        }
        val anchor = currentLatLng ?: candidates.first().pickup
        val sequencedStops = RideshareManager.Sequencer.sequenceStops(anchor, candidates)

        var pCount = 1
        var dCount = 1

        sequencedStops.forEach { stop ->
            val isPickup = stop.type == RideshareManager.StopType.PICKUP
            val tag = if (isPickup) "P${pCount++}" else "D${dCount++}"
            val color = if (isPickup) Color.parseColor("#4CAF50") else Color.parseColor("#E53935")

            mMap.addMarker(
                MarkerOptions()
                    .position(stop.location)
                    .title("Stop $tag")
                    .icon(MapMarkerUtils.createNumberedMarker(requireContext(), tag, color))
            )
            bounds.include(stop.location)
        }

        currentLatLng?.let { driverLoc ->
            driverMarker = mMap.addMarker(
                MarkerOptions()
                    .position(driverLoc)
                    .title("Your Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
            bounds.include(driverLoc)
        }

        try {
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateUIForState(state: DriverRideState) {
        val b = _binding ?: return
        currentRideState = state
        val isTripActive = (state == DriverRideState.ACCEPTED || state == DriverRideState.IN_PROGRESS)

        b.switchOnline.isEnabled = !isTripActive

        if (isTripActive) {
            val selfIndex = targetsList.indexOfFirst { it.isSelf }
            if (selfIndex != -1) currentTargetIndex = selfIndex
            b.txtCurrentTargetIndicator.text = "Pin: Active Ride"
        } else {
            val target = targetsList.getOrNull(currentTargetIndex)
            b.txtCurrentTargetIndicator.text = "Pin: ${target?.name ?: "Me"}"
        }


        when (state) {
            DriverRideState.IDLE -> {
                b.cardIncomingRequest.visibility = View.GONE
                b.btnAcceptRide.visibility = View.VISIBLE
                b.btnDeclineRide.visibility = View.VISIBLE
                b.btnConfirmPickup.visibility = View.GONE
                b.btnCompleteRide.visibility = View.GONE
                b.btnChatWithPassenger.visibility = View.GONE
            }
            DriverRideState.REQUEST_RECEIVED -> {
                b.cardIncomingRequest.visibility = View.VISIBLE
                b.btnAcceptRide.visibility = View.VISIBLE
                b.btnDeclineRide.visibility = View.VISIBLE
                b.btnConfirmPickup.visibility = View.GONE
                b.btnCompleteRide.visibility = View.GONE
                b.btnChatWithPassenger.visibility = View.GONE
            }
            DriverRideState.ACCEPTED -> {
                b.cardIncomingRequest.visibility = View.VISIBLE
                b.btnAcceptRide.visibility = View.GONE
                b.btnDeclineRide.visibility = View.GONE
                b.btnConfirmPickup.visibility = View.VISIBLE
                b.btnCompleteRide.visibility = View.GONE
                b.btnChatWithPassenger.visibility = View.VISIBLE
            }
            DriverRideState.IN_PROGRESS -> {
                b.cardIncomingRequest.visibility = View.VISIBLE
                b.btnAcceptRide.visibility = View.GONE
                b.btnDeclineRide.visibility = View.GONE
                b.btnConfirmPickup.visibility = View.GONE
                b.btnCompleteRide.visibility = View.VISIBLE
                b.btnChatWithPassenger.visibility = View.VISIBLE
            }
        }

        // Placed AFTER the state switch so it sees the chat button's new visibility
        // (it used to read the previous state, which made "Chat with passenger" overlap
        // the prev/next pin selector).
        val selectorParams = b.layoutTargetSelector.layoutParams as RelativeLayout.LayoutParams
        selectorParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        selectorParams.removeRule(RelativeLayout.ABOVE)

        if (isTripActive && b.btnChatWithPassenger.visibility == View.VISIBLE) {
            selectorParams.addRule(RelativeLayout.ABOVE, R.id.btnChatWithPassenger)
        } else if (state != DriverRideState.IDLE) {
            selectorParams.addRule(RelativeLayout.ABOVE, R.id.cardIncomingRequest)
        } else {
            selectorParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            selectorParams.bottomMargin = (16 * resources.displayMetrics.density).toInt()
        }
        b.layoutTargetSelector.layoutParams = selectorParams

        b.btnDriverSos.visibility = if (isTripActive) View.VISIBLE else View.GONE
    }

    private fun acceptCurrentRide() {
        val bookingId = activeBookingId
        val driverId = auth.currentUser?.uid ?: return
        if (bookingId.isNullOrBlank()) {
            Toast.makeText(context, "Failed to accept ride: missing booking reference.", Toast.LENGTH_SHORT).show()
            return
        }

        // Do NOT switch to ACCEPTED yet: the screen only moves once the claim is won,
        // so a driver who loses a race never sees a ride that belongs to someone else.
        binding.btnAcceptRide.isEnabled = false

        database.child("users").child(driverId).get().addOnSuccessListener { snapshot ->
            if (_binding == null || !isAdded) return@addOnSuccessListener
            if (walletBlocksAccept(snapshot.child("walletBalance").value)) return@addOnSuccessListener
            val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
            val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""
            val driverName = "$firstName $lastName".trim().ifEmpty {
                snapshot.child("name").getValue(String::class.java) ?: "Driver"
            }

            val vehicleModel = snapshot.child("vehicleModel").getValue(String::class.java) ?: ""
            val plateNumber = snapshot.child("plateNumber").getValue(String::class.java) ?: ""
            val vehicleColor = snapshot.child("vehicleColor").getValue(String::class.java) ?: ""
            val driverPhone = snapshot.child("phoneNumber").getValue(String::class.java)
                ?: snapshot.child("phone").getValue(String::class.java) ?: ""

            val updates = hashMapOf<String, Any>(
                "status" to "ACCEPTED",
                "driverId" to driverId,
                "driverName" to driverName,
                "driverPhone" to driverPhone,
                "vehicleModel" to vehicleModel,
                "plateNumber" to plateNumber,
                "vehicleColor" to vehicleColor,
                "acceptedAt" to System.currentTimeMillis()
            )

            Log.d("SYNC", "SENT ACCEPTED t=${System.currentTimeMillis() + syncOffsetMs}")
            // First driver wins: the claim is a transaction on the booking, so only
            // one driver can ever get WON for the same booking.
            BookingClaim.claim(database.child("bookings").child(bookingId), driverId, updates) { outcome ->
                if (_binding == null || !isAdded) return@claim
                when (outcome) {
                    BookingClaim.Outcome.WON -> {
                        binding.btnAcceptRide.isEnabled = true
                        updateUIForState(DriverRideState.ACCEPTED)
                        Toast.makeText(context, "Ride accepted!", Toast.LENGTH_SHORT).show()
                        clearRouteAndMarkers()
                        arrivalHandledThisStop = false

                        val booking = currentBooking ?: return@claim

                        if (currentLatLng == null) {
                            if (ActivityCompat.checkSelfPermission(
                                    requireContext(),
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                                    if (loc != null) {
                                        currentLatLng = LatLng(loc.latitude, loc.longitude)
                                        drawDriverToPickupRoute(booking)
                                    }
                                }
                            }
                        } else {
                            drawDriverToPickupRoute(booking)
                        }
                    }
                    BookingClaim.Outcome.TAKEN -> onRideNoLongerAvailable(bookingId, taken = true)
                    BookingClaim.Outcome.UNAVAILABLE -> onRideNoLongerAvailable(bookingId, taken = false)
                    BookingClaim.Outcome.ERROR -> onAcceptFailed("Failed to accept ride. Check your connection and try again.")
                }
            }
        }.addOnFailureListener { e ->
            onAcceptFailed("Failed to accept ride: ${e.message}")
        }
    }

    /**
     * The booking was taken by another driver (or cancelled) before our claim
     * landed. Nothing was written for this driver, so just drop the request and
     * move on to the next nearest one.
     */
    private fun onRideNoLongerAvailable(bookingId: String, taken: Boolean) {
        Toast.makeText(
            context,
            if (taken) "Ride already taken by another driver." else "This ride is no longer available.",
            Toast.LENGTH_LONG
        ).show()

        declinedBookingIds.add(bookingId)
        currentBooking?.tripGroupId?.takeIf { it.isNotEmpty() }?.let { declinedGroupIds.add(it) }

        stopListeningForRequests()
        clearRouteAndMarkers()
        activeBookingId = null
        currentBooking = null
        binding.btnAcceptRide.isEnabled = true
        updateUIForState(DriverRideState.IDLE)
        listenForPendingRideRequests()
    }

    /**
     * Drivers can accept rides while their wallet is below zero, but only down to -₱100.
     * Returns true (and tells the driver to top up) when the limit is reached.
     */
    private fun walletBlocksAccept(rawBalance: Any?): Boolean {
        val balance = com.ruta.app.util.WalletLimit.parse(rawBalance)
        if (com.ruta.app.util.WalletLimit.canAccept(balance)) return false
        _binding?.btnAcceptRide?.isEnabled = true
        updateUIForState(DriverRideState.REQUEST_RECEIVED)
        context?.let {
            androidx.appcompat.app.AlertDialog.Builder(it)
                .setTitle("Top up to keep accepting rides")
                .setMessage(
                    String.format(
                        java.util.Locale.US,
                        "Your wallet is at ₱%.2f. Drivers can go down to -₱%.0f before topping up. Open the Wallet tab and cash in, then you can accept bookings again.",
                        balance, com.ruta.app.util.WalletLimit.MAX_DEBT
                    )
                )
                .setPositiveButton("OK", null)
                .show()
        }
        return true
    }

    /** A real failure (network, permission), not a lost race: keep the request so the driver can retry. */
    private fun onAcceptFailed(message: String) {
        if (_binding == null) return
        binding.btnAcceptRide.isEnabled = true
        updateUIForState(DriverRideState.REQUEST_RECEIVED)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun acceptRideGroup(driverId: String) {
        val booking = currentBooking ?: return
        val tripGroupId = booking.tripGroupId

        if (tripGroupId.isNullOrEmpty()) return

        // Block double taps while the claim is in flight.
        binding.btnAcceptRide.isEnabled = false

        database.child("users").child(driverId).get().addOnSuccessListener { userSnapshot ->
            if (_binding == null || !isAdded) return@addOnSuccessListener
            if (walletBlocksAccept(userSnapshot.child("walletBalance").value)) return@addOnSuccessListener
            val firstName = userSnapshot.child("firstName").getValue(String::class.java) ?: ""
            val lastName = userSnapshot.child("lastName").getValue(String::class.java) ?: ""
            val driverName = "$firstName $lastName".trim().ifEmpty {
                userSnapshot.child("name").getValue(String::class.java) ?: "Driver"
            }

            val vehicleModel = userSnapshot.child("vehicleModel").getValue(String::class.java) ?: ""
            val plateNumber = userSnapshot.child("plateNumber").getValue(String::class.java) ?: ""
            val vehicleColor = userSnapshot.child("vehicleColor").getValue(String::class.java) ?: ""
            val driverPhone = userSnapshot.child("phoneNumber").getValue(String::class.java)
                ?: userSnapshot.child("phone").getValue(String::class.java) ?: ""

            val updates = hashMapOf<String, Any>(
                "status" to "ACCEPTED",
                "driverId" to driverId,
                "driverName" to driverName,
                "driverPhone" to driverPhone,
                "vehicleModel" to vehicleModel,
                "plateNumber" to plateNumber,
                "vehicleColor" to vehicleColor,
                "acceptedAt" to System.currentTimeMillis()
            )

            database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (_binding == null || !isAdded) return

                        val groupRefs = snapshot.children
                            .mapNotNull { c -> c.key?.let { key -> key to c.ref } }
                            .toMap()
                        val leadKey = BookingClaim.leadBookingKey(groupRefs.keys)
                        val leadRef = leadKey?.let { groupRefs[it] }
                        if (leadKey == null || leadRef == null) {
                            onRideNoLongerAvailable(activeBookingId.orEmpty(), taken = false)
                            return
                        }

                        // Every driver contends on the same lead booking, so exactly one
                        // driver wins the whole group (never one booking each).
                        BookingClaim.claim(leadRef, driverId, updates) { outcome ->
                            if (_binding == null || !isAdded) return@claim
                            when (outcome) {
                                BookingClaim.Outcome.WON -> {
                                    // Won the group: hand the remaining bookings to this driver too.
                                    for ((key, ref) in groupRefs) {
                                        if (key == leadKey) continue
                                        BookingClaim.claim(ref, driverId, updates) { o ->
                                            // UNAVAILABLE is normal (a co-passenger already cancelled).
                                            if (o == BookingClaim.Outcome.TAKEN || o == BookingClaim.Outcome.ERROR) {
                                                context?.let {
                                                    Toast.makeText(it, "A co-passenger booking could not be assigned.", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }

                                    binding.btnAcceptRide.isEnabled = true
                                    currentStopIndex = 0
                                    arrivalHandledThisStop = false
                                    updateUIForState(DriverRideState.ACCEPTED)

                                    loadGroupStopsLocally(tripGroupId) { stops ->
                                        currentGroupStops = stops
                                        updateCardForCurrentStop()
                                        drawGroupRoutePreviewOnly(stops)
                                    }
                                }
                                BookingClaim.Outcome.TAKEN -> onRideNoLongerAvailable(leadKey, taken = true)
                                BookingClaim.Outcome.UNAVAILABLE -> onRideNoLongerAvailable(leadKey, taken = false)
                                BookingClaim.Outcome.ERROR -> onAcceptFailed("Failed to accept trip group. Check your connection and try again.")
                            }
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {
                        onAcceptFailed("Failed to accept trip group: ${error.message}")
                    }
                })
        }.addOnFailureListener { e ->
            onAcceptFailed("Failed to accept trip group: ${e.message}")
        }
    }

    private fun loadGroupStopsLocally(
        tripGroupId: String,
        onReady: (List<RideshareManager.RideshareStop>) -> Unit
    ) {
        database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (_binding == null || !isAdded) return
                    val group = snapshot.children.mapNotNull { it.getValue(Booking::class.java) }
                        .filter { it.status != "COMPLETED" && it.status != "CANCELLED" }

                    if (group.isEmpty()) {
                        finishSharedTrip(tripGroupId)
                        return
                    }
                    val candidates = group.map {
                        RideshareManager.RideshareCandidate(
                            it.bookingId, it.passengerId,
                            LatLng(it.pickupLat, it.pickupLng), LatLng(it.dropoffLat, it.dropoffLng)
                        )
                    }
                    val anchor = currentLatLng ?: candidates.first().pickup
                    val bookingsMap = group.associateBy { it.bookingId }
                    group.forEach { groupPassengerNames[it.bookingId] = it.passengerName }

                    // A confirmed rideshare match carries the stop order its fare was
                    // quoted on. Drive that order, so the route the driver follows is the
                    // route the passengers were quoted. Anything that does not line up
                    // exactly (older groups, a passenger already dropped off) keeps the
                    // original sequencing.
                    database.child("tripGroups").child(tripGroupId).child("stopOrder").get()
                        .addOnCompleteListener { orderTask ->
                            val locked = if (orderTask.isSuccessful) lockedStops(orderTask.result, bookingsMap.keys) else null
                            val sequencedStops = locked
                                ?: RideshareManager.Sequencer.sequenceStops(anchor, candidates)

                            syncSequenceToFirebase(tripGroupId, sequencedStops, bookingsMap)
                            onReady(sequencedStops)
                        }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    /** The stored stop order if it covers exactly the bookings still in the group, else null. */
    private fun lockedStops(
        stopOrder: DataSnapshot,
        remainingBookingIds: Set<String>
    ): List<RideshareManager.RideshareStop>? {
        val stops = stopOrder.children.mapNotNull { st ->
            RideshareManager.RideshareStop(
                bookingId = st.child("bookingId").getValue(String::class.java) ?: return@mapNotNull null,
                type = try {
                    RideshareManager.StopType.valueOf(st.child("type").getValue(String::class.java) ?: return@mapNotNull null)
                } catch (e: IllegalArgumentException) {
                    return@mapNotNull null
                },
                location = LatLng(
                    st.child("lat").getValue(Double::class.java) ?: return@mapNotNull null,
                    st.child("lng").getValue(Double::class.java) ?: return@mapNotNull null
                )
            )
        }
        if (stops.isEmpty()) return null
        if (stops.size != remainingBookingIds.size * 2) return null
        if (stops.map { it.bookingId }.toSet() != remainingBookingIds) return null
        return stops
    }

    private fun syncSequenceToFirebase(
        tripGroupId: String,
        sortedStops: List<RideshareManager.RideshareStop>,
        bookingsMap: Map<String, Booking> = emptyMap()
    ) {
        var pCount = 1
        var dCount = 1

        val sequenceData = sortedStops.mapIndexed { index, stop ->
            val isPickup = stop.type == RideshareManager.StopType.PICKUP
            val label = if (isPickup) "P${pCount++}" else "D${dCount++}"
            val passengerId = bookingsMap[stop.bookingId]?.passengerId ?: ""

            mapOf(
                "bookingId" to stop.bookingId,
                "passengerId" to passengerId,
                "label" to label,
                "sequenceOrder" to index + 1,
                "isPickup" to isPickup
            )
        }

        database.child("tripGroups").child(tripGroupId).child("stops").setValue(sequenceData)
    }

    private fun drawGroupRoutePreviewOnly(stops: List<RideshareManager.RideshareStop>) {
        val driverLoc = currentLatLng ?: return
        val apiKey = MapsApi.key(requireContext())

        executor.execute {
            val remainingStops = if (currentStopIndex in stops.indices) stops.subList(currentStopIndex, stops.size) else stops
            val finalRoute = RideshareRouteOptimizer.buildFinalDriverRoute(driverLoc, remainingStops, apiKey)
            activity?.runOnUiThread {
                if (_binding == null || !isAdded) return@runOnUiThread

                if (::mMap.isInitialized) {
                    mMap.clear()
                    driverMarker = null
                }

                // One colour per passenger, matching the passenger's own screen: the
                // lower booking id is lavender, the other is blue (shared legs show both).
                val riderIds = stops.map { it.bookingId }.distinct().sorted()
                val primaryId = riderIds.firstOrNull() ?: ""
                val lavender = ContextCompat.getColor(requireContext(), R.color.ruta_primary)
                val blue = ContextCompat.getColor(requireContext(), R.color.ruta_copassenger)
                val idle = ContextCompat.getColor(requireContext(), R.color.ruta_route_idle)

                if (finalRoute != null) {
                    rawRoutePoints = finalRoute.polyline
                    activePolyline?.remove()

                    RideshareMapStyle.drawSegmentedRoute(
                        map = mMap,
                        route = finalRoute,
                        passengerIds = riderIds.toSet(),
                        primaryBookingId = primaryId,
                        primaryColor = lavender,
                        secondaryColor = blue,
                        idleColor = idle
                    )

                    // Invisible full-route line: the live "trim behind the driver" logic
                    // keeps working on it while the coloured legs above show the route.
                    activePolyline = mMap.addPolyline(
                        PolylineOptions()
                            .addAll(finalRoute.polyline)
                            .width(2f)
                            .color(Color.TRANSPARENT)
                    )
                }

                stops.forEachIndexed { index, stop ->
                    val isPickup = stop.type == RideshareManager.StopType.PICKUP
                    val first = groupPassengerNames[stop.bookingId]
                        ?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: "Rider"
                    val color = if (stop.bookingId == primaryId) lavender else blue
                    val pin = MapMarkerUtils.createPassengerPin(
                        requireContext(), if (isPickup) "P" else "D", first, color, isDropoff = !isPickup
                    )
                    val marker = mMap.addMarker(
                        MarkerOptions()
                            .position(stop.location)
                            .title("${index + 1}. $first • ${if (isPickup) "Pickup" else "Drop-off"}")
                            .icon(pin.icon)
                            .anchor(0.5f, pin.anchorY)
                            .zIndex(3f)
                    )
                    if (index == currentStopIndex) marker?.showInfoWindow()
                }

                driverMarker = mMap.addMarker(
                    MarkerOptions()
                        .position(driverLoc)
                        .title("Your Location")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                )
            }
        }
    }

    private fun drawDriverToPickupRoute(booking: Booking) {
        val driverLoc = currentLatLng ?: return
        val pickupLatLng = LatLng(booking.pickupLat, booking.pickupLng)

        pickupMarker?.remove()
        pickupMarker = mMap.addMarker(
            MarkerOptions()
                .position(pickupLatLng)
                .title("Pickup Point (P1)")
                .icon(MapMarkerUtils.createNumberedMarker(requireContext(), "P1", Color.parseColor("#4CAF50")))
        )

        drawRoute(driverLoc, pickupLatLng)

        try {
            val bounds = LatLngBounds.Builder()
                .include(driverLoc)
                .include(pickupLatLng)
                .build()
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        } catch (e: Exception) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(driverLoc, 15f))
        }
    }

    private fun drawDriverToDropoffRoute(booking: Booking) {
        val driverLoc = currentLatLng ?: return
        val dropoffLatLng = LatLng(booking.dropoffLat, booking.dropoffLng)

        dropoffMarker?.remove()
        dropoffMarker = mMap.addMarker(
            MarkerOptions()
                .position(dropoffLatLng)
                .title("Dropoff Point (D1)")
                .icon(MapMarkerUtils.createNumberedMarker(requireContext(), "D1", Color.parseColor("#E53935")))
        )

        drawRoute(driverLoc, dropoffLatLng)

        try {
            val bounds = LatLngBounds.Builder()
                .include(driverLoc)
                .include(dropoffLatLng)
                .build()
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        } catch (e: Exception) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(driverLoc, 15f))
        }
    }

    private fun confirmPassengerPickup() {
        stopPickupTimer()
        val bookingId = activeBookingId ?: return

        updateUIForState(DriverRideState.IN_PROGRESS)

        Log.d("SYNC", "SENT IN_PROGRESS t=${System.currentTimeMillis() + syncOffsetMs}")
        database.child("bookings").child(bookingId).child("status").setValue("IN_PROGRESS")
            .addOnSuccessListener {
                if (_binding == null || !isAdded) return@addOnSuccessListener
                Toast.makeText(context, "Passenger Picked Up! Trip Started.", Toast.LENGTH_SHORT).show()
                clearRouteAndMarkers()

                val booking = currentBooking
                if (booking != null) {
                    drawDriverToDropoffRoute(booking)
                }
            }
            .addOnFailureListener { e ->
                if (_binding == null || !isAdded) return@addOnFailureListener
                updateUIForState(DriverRideState.ACCEPTED)
                Toast.makeText(context, "Failed to start trip: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun confirmSharedStop(isAuto: Boolean = false) {
        val stops = currentGroupStops
        if (stops.isEmpty()) {
            val tripGroupId = currentBooking?.tripGroupId
            if (!tripGroupId.isNullOrEmpty()) {
                loadGroupStopsLocally(tripGroupId) { rebuilt ->
                    currentGroupStops = rebuilt
                    currentStopIndex = 0
                    updateCardForCurrentStop()
                }
            } else {
                Toast.makeText(context, "No shared route loaded — try re-accepting the ride.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (currentStopIndex !in stops.indices) return
        val stop = stops[currentStopIndex]

        if (stop.type == RideshareManager.StopType.DROPOFF) {
            val driverLoc = currentLatLng
            if (driverLoc == null) {
                Toast.makeText(context, "Waiting for GPS fix — try again shortly.", Toast.LENGTH_SHORT).show()
                return
            }

            val dist = distanceMeters(driverLoc, stop.location)
            if (dist > DROPOFF_RADIUS_METERS) {
                Toast.makeText(context, "You're ${dist.toInt()}m from this drop-off — move closer to confirm.", Toast.LENGTH_LONG).show()
                return
            }
        }

        val newStatus = if (stop.type == RideshareManager.StopType.PICKUP) "IN_PROGRESS" else "COMPLETED"
        database.child("bookings").child(stop.bookingId).child("status").setValue(newStatus)
            .addOnSuccessListener {
                stopPickupTimer()
                advanceToNextStop()
            }
            .addOnFailureListener { err ->
                Toast.makeText(context, "Failed to update status: ${err.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun advanceToNextStop() {
        arrivalHandledThisStop = false
        currentStopIndex++

        val tripGroupId = currentBooking?.tripGroupId ?: return

        database.child("tripGroups").child(tripGroupId).child("currentStopIndex").setValue(currentStopIndex)
            .addOnCompleteListener {
                if (_binding == null || !isAdded) return@addOnCompleteListener

                if (currentStopIndex >= currentGroupStops.size) {
                    database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                if (_binding == null || !isAdded) return
                                val activeBookingsExist = snapshot.children.any { child ->
                                    val status = child.child("status").getValue(String::class.java)
                                    status != "COMPLETED" && status != "CANCELLED"
                                }

                                if (!activeBookingsExist) {
                                    finishSharedTrip(tripGroupId)
                                } else {
                                    loadGroupStopsLocally(tripGroupId) { refreshedStops ->
                                        currentGroupStops = refreshedStops
                                        updateCardForCurrentStop()
                                        if (currentGroupStops.isNotEmpty()) {
                                            drawGroupRoutePreviewOnly(currentGroupStops)
                                        }
                                    }
                                }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                finishSharedTrip(tripGroupId)
                            }
                        })
                } else {
                    updateCardForCurrentStop()
                    if (currentGroupStops.isNotEmpty()) {
                        drawGroupRoutePreviewOnly(currentGroupStops)
                    }
                }
            }
    }

    private fun updateCardForCurrentStop() {
        val stops = currentGroupStops
        if (currentStopIndex !in stops.indices) return
        val stop = stops[currentStopIndex]
        val isPickup = stop.type == RideshareManager.StopType.PICKUP
        val label = if (isPickup) "Pickup" else "Dropoff"

        database.child("bookings").child(stop.bookingId).get().addOnSuccessListener { snap ->
            if (_binding == null || !isAdded) return@addOnSuccessListener

            val status = snap.child("status").getValue(String::class.java) ?: ""
            if (status == "COMPLETED" || status == "CANCELLED") {
                advanceToNextStop()
                return@addOnSuccessListener
            }

            val name = snap.child("passengerName").getValue(String::class.java)?.ifEmpty { null } ?: "Passenger"
            val passengerId = snap.child("passengerId").getValue(String::class.java) ?: ""
            val address = if (isPickup)
                snap.child("pickupAddress").getValue(String::class.java) ?: "N/A"
            else
                snap.child("dropoffAddress").getValue(String::class.java) ?: "N/A"

            showPassengerAvatar(passengerId, name)
            if (passengerId.isNotEmpty()) {
                database.child("users").child(passengerId).get().addOnSuccessListener { userSnap ->
                    if (_binding == null || !isAdded) return@addOnSuccessListener
                    val phone = userSnap.child("phone").value?.toString()
                        ?: userSnap.child("phoneNumber").value?.toString()
                        ?: ""
                    val phoneText = if (phone.isNotEmpty()) "\nContact: $phone" else ""
                    _binding?.txtPassengerName?.text = "$name (Stop ${currentStopIndex + 1} of ${stops.size})$phoneText"
                }
            } else {
                _binding?.txtPassengerName?.text = "$name — Stop ${currentStopIndex + 1} of ${stops.size}"
            }

            _binding?.txtPickupLocation?.text = if (isPickup) "Pickup: $address" else ""
            _binding?.txtDropoffLocation?.text = if (!isPickup) "Dropoff: $address" else ""
        }

        val b = _binding ?: return
        b.btnAcceptRide.visibility = View.GONE
        b.btnDeclineRide.visibility = View.GONE
        b.btnCompleteRide.visibility = View.GONE
        b.btnConfirmPickup.visibility = View.VISIBLE
        b.btnConfirmPickup.text = "Confirm $label"
        b.btnChatWithPassenger.visibility = View.VISIBLE
    }

    private fun finishSharedTrip(tripGroupId: String) {
        calculateAndSaveSharedFares(tripGroupId)
        Toast.makeText(context, "Ride Completed!", Toast.LENGTH_SHORT).show()

        stopListeningForRequests()
        clearRouteAndMarkers()
        val finishedBookingId = activeBookingId
        activeBookingId = null
        if (finishedBookingId != null) {
            RouteDeviationManager.clearTripDeviationState(finishedBookingId)
        }
        currentBooking = null
        currentGroupStops = emptyList()
        currentStopIndex = 0
        updateUIForState(DriverRideState.IDLE)
        listenForPendingRideRequests()
    }

    private fun attemptCompleteRide() {
        val booking = currentBooking ?: return
        val driverLoc = currentLatLng
        if (driverLoc == null) {
            Toast.makeText(context, "Waiting for GPS fix — try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }

        val destination = LatLng(booking.dropoffLat, booking.dropoffLng)
        val distance = distanceMeters(driverLoc, destination)

        if (distance > DROPOFF_RADIUS_METERS) {
            Toast.makeText(context, "You're ${distance.toInt()}m from the drop-off — move closer to complete the ride.", Toast.LENGTH_LONG).show()
            return
        }

        completeCurrentRide()
    }

    private fun completeCurrentRide() {
        val bookingId = activeBookingId ?: return
        val booking = currentBooking
        val driverId = auth.currentUser?.uid

        Log.d("SYNC", "SENT COMPLETED t=${System.currentTimeMillis() + syncOffsetMs}")
        database.child("bookings").child(bookingId).child("status").setValue("COMPLETED")
            .addOnSuccessListener {
                if (booking != null && driverId != null) {
                    WalletManager.deductCommission(database, driverId, booking.fare, booking.hasDiscount, bookingId = bookingId)
                    NoShowManager.settleFeeOnCompletion(database, driverId, bookingId)
                }
                Toast.makeText(context, "Ride Completed!", Toast.LENGTH_SHORT).show()
                stopListeningForRequests()
                clearRouteAndMarkers()
                activeBookingId = null
                RouteDeviationManager.clearTripDeviationState(bookingId)
                currentBooking = null
                updateUIForState(DriverRideState.IDLE)
                listenForPendingRideRequests()
            }
    }

    /** One passenger's payout, either the locked quote or a fresh calculation. */
    private data class FareLine(
        val bookingId: String,
        val total: Double,
        val base: Double,
        val solo: Double,
        val shared: Double,
        val detour: Double,
        val credit: Double
    )

    private fun calculateAndSaveSharedFares(tripGroupId: String) {
        val apiKey = MapsApi.key(requireContext())

        database.child("bookings").orderByChild("tripGroupId").equalTo(tripGroupId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val group = snapshot.children.mapNotNull { it.getValue(Booking::class.java) }
                    if (group.size != 2) return

                    val a = group[0]
                    val b = group[1]

                    // The fare was quoted and locked when both passengers confirmed the
                    // match (tripGroups/<id>/quote). Pay out exactly that; only fall back
                    // to recomputing for older groups that have no stored quote.
                    database.child("tripGroups").child(tripGroupId).child("quote").get()
                        .addOnCompleteListener { quoteTask ->
                            val quote = if (quoteTask.isSuccessful) quoteTask.result else null

                            fun locked(id: String): FareLine? {
                                val q = quote?.child(id) ?: return null
                                val total = q.child("total").getValue(Double::class.java) ?: return null
                                return FareLine(
                                    bookingId = id,
                                    total = total,
                                    base = q.child("base").getValue(Double::class.java) ?: 0.0,
                                    solo = q.child("solo").getValue(Double::class.java) ?: 0.0,
                                    shared = q.child("shared").getValue(Double::class.java) ?: 0.0,
                                    detour = q.child("detour").getValue(Double::class.java) ?: 0.0,
                                    credit = q.child("credit").getValue(Double::class.java) ?: 0.0
                                )
                            }

                            val lockedA = locked(a.bookingId)
                            val lockedB = locked(b.bookingId)

                            executor.execute {
                                val driverId = auth.currentUser?.uid ?: return@execute

                                val lines: List<FareLine> = if (lockedA != null && lockedB != null) {
                                    listOf(lockedA, lockedB)
                                } else {
                                    val candA = RideshareManager.RideshareCandidate(
                                        a.bookingId, a.passengerId,
                                        LatLng(a.pickupLat, a.pickupLng), LatLng(a.dropoffLat, a.dropoffLng)
                                    )
                                    val candB = RideshareManager.RideshareCandidate(
                                        b.bookingId, b.passengerId,
                                        LatLng(b.pickupLat, b.pickupLng), LatLng(b.dropoffLat, b.dropoffLng)
                                    )
                                    val route = RideshareRouteOptimizer.findOptimalSequence(candA, candB, apiKey)
                                        ?: return@execute
                                    val breakdown = RideshareFareCalculator.calculate(
                                        route = route,
                                        bookingIdA = a.bookingId,
                                        bookingIdB = b.bookingId,
                                        directDistanceA = a.distanceMeters,
                                        directDurationA = a.durationSeconds,
                                        directDistanceB = b.distanceMeters,
                                        directDurationB = b.durationSeconds,
                                        surgeMultiplier = a.surgeMultiplier,
                                        hasDiscountA = false, // Shared rides are solo-discount-exempt
                                        hasDiscountB = false
                                    )
                                    listOf(breakdown.passengerA, breakdown.passengerB).map { f ->
                                        FareLine(
                                            f.bookingId, f.total, f.baseFare, f.soloCharge,
                                            f.sharedCharge, f.detourSurcharge, f.inconvenienceCredit
                                        )
                                    }
                                }

                                lines.forEach { f ->
                                    // 1. Deduct commission per passenger (hardcoded false for shared discount)
                                    WalletManager.deductCommission(database, driverId, f.total, false, bookingId = f.bookingId)

                                    // 2. Persist exact fare breakdown to Firebase
                                    database.child("bookings").child(f.bookingId).updateChildren(
                                        mapOf(
                                            "finalFare" to f.total,
                                            "fareBase" to f.base,
                                            "fareSoloCharge" to f.solo,
                                            "fareSharedCharge" to f.shared,
                                            "fareDetourSurcharge" to f.detour,
                                            "fareInconvenienceCredit" to f.credit,
                                            "hasDiscount" to false,
                                            "discountAmount" to 0.0
                                        )
                                    )
                                    NoShowManager.settleFeeOnCompletion(database, driverId, f.bookingId)
                                }
                            }
                        }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun declineCurrentRide() {
        val booking = currentBooking
        activeBookingId?.let { declinedBookingIds.add(it) }
        booking?.tripGroupId?.takeIf { it.isNotEmpty() }?.let { declinedGroupIds.add(it) }

        val driverId = auth.currentUser?.uid
        val id = activeBookingId
        if (driverId != null && id != null) {
            database.child("bookings").child(id).child("declinedBy").child(driverId).setValue(true)
        }

        stopListeningForRequests()
        clearRouteAndMarkers()
        activeBookingId = null
        currentBooking = null
        updateUIForState(DriverRideState.IDLE)
        listenForPendingRideRequests()
    }

    private fun clearRouteAndMarkers() {
        routeGeneration++
        activePolyline?.remove()
        activePolyline = null
        rawRoutePoints = emptyList()
        wasDeviated = false
        resetDeviationFlow()
        arrivalHandledThisStop = false
        driverSosSent = false

        pickupMarker?.remove()
        pickupMarker = null

        dropoffMarker?.remove()
        dropoffMarker = null

        if (::mMap.isInitialized) {
            mMap.clear()

            currentLatLng?.let { latLng ->
                driverMarker = mMap.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title("Your Location")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                )
            }
        }
    }

    private fun listenForPendingRideRequests() {
        val bookingsRef = database.child("bookings")

        pendingRequestsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isOnline || _binding == null || !isAdded) return
                if (currentRideState == DriverRideState.ACCEPTED || currentRideState == DriverRideState.IN_PROGRESS) return

                val driverLoc = currentLatLng ?: return

                val eligible = mutableListOf<Pair<String, Booking>>()
                for (child in snapshot.children) {
                    val status = child.child("status").getValue(String::class.java)
                    if (status == "REQUESTED" || status == "MATCHED") {
                        val key = child.key ?: continue
                        val groupId = child.child("tripGroupId").getValue(String::class.java)

                        if (key in declinedBookingIds) continue
                        if (!groupId.isNullOrEmpty() && groupId in declinedGroupIds) continue
                        if (child.child("declinedBy").hasChild(auth.currentUser?.uid ?: "_")) continue

                        val booking = child.getValue(Booking::class.java)
                        if (booking != null) eligible.add(key to booking)
                    }
                }

                // Nearest Neighbor: offer the request whose pickup is closest to this driver.
                val bookingsByKey = eligible.toMap()
                val nearestKey = PendingRequestRanker.nearest(
                    driverLoc,
                    eligible.map { (key, b) ->
                        PendingRequestRanker.Candidate(key, LatLng(b.pickupLat, b.pickupLng))
                    },
                    { a, b -> distanceMeters(a, b) }
                )?.bookingKey
                val nearest = nearestKey?.let { key -> bookingsByKey[key]?.let { key to it } }

                if (nearest != null) {
                    val nearestBookingKey = nearest.first
                    val nearestBooking = nearest.second
                    if (activeBookingId != nearestBookingKey) {
                        activeBookingId = nearestBookingKey
                        currentBooking = nearestBooking

                        val isGroup = !nearestBooking.tripGroupId.isNullOrEmpty()
                        if (isGroup) {
                            showGroupRequestPreview(nearestBooking)
                        } else {
                            bindPassengerInfoToView(nearestBooking)
                            previewBookingRoute(nearestBooking)
                        }

                        updateUIForState(DriverRideState.REQUEST_RECEIVED)
                        monitorSingleBookingForCancellation(nearestBookingKey)
                    }
                } else {
                    if (currentRideState == DriverRideState.REQUEST_RECEIVED) {
                        updateUIForState(DriverRideState.IDLE)
                        clearRouteAndMarkers()
                        activeBookingId = null
                        currentBooking = null
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(context, "Realtime sync error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }

        bookingsRef.addValueEventListener(pendingRequestsListener!!)
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val results = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results[0].toDouble()
    }

    private fun previewBookingRoute(booking: Booking) {
        val pLat = booking.pickupLat
        val pLng = booking.pickupLng
        val dLat = booking.dropoffLat
        val dLng = booking.dropoffLng

        val pickupLatLng = LatLng(pLat, pLng)
        val dropoffLatLng = LatLng(dLat, dLng)

        clearRouteAndMarkers()

        pickupMarker = mMap.addMarker(
            MarkerOptions().position(pickupLatLng).title("Pickup Point (P1)")
                .icon(MapMarkerUtils.createNumberedMarker(requireContext(), "P1", Color.parseColor("#4CAF50")))
        )
        dropoffMarker = mMap.addMarker(
            MarkerOptions().position(dropoffLatLng).title("Dropoff Point (D1)")
                .icon(MapMarkerUtils.createNumberedMarker(requireContext(), "D1", Color.parseColor("#E53935")))
        )

        drawRoute(pickupLatLng, dropoffLatLng)

        try {
            val bounds = LatLngBounds.Builder()
                .include(pickupLatLng)
                .include(dropoffLatLng)

            currentLatLng?.let { bounds.include(it) }

            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkLocationPermission()
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1500)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            mMap.isMyLocationEnabled = true
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    updateDriverPinAndProgress(location)
                }
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                locationPermissionCode
            )
        }
    }

    private fun updateDriverOnlineStatus(online: Boolean) {
        val currentUserId = auth.currentUser?.uid ?: return
        database.child("users").child(currentUserId).child("isOnline").setValue(online)
    }

    private fun stopListeningForRequests() {
        pendingRequestsListener?.let {
            database.child("bookings").removeEventListener(it)
            pendingRequestsListener = null
        }
        activeBookingListener?.let {
            activeBookingId?.let { id -> database.child("bookings").child(id).removeEventListener(it) }
            activeBookingListener = null
        }
    }

    private fun monitorSingleBookingForCancellation(bookingId: String) {
        activeBookingListener?.let {
            database.child("bookings").child(bookingId).removeEventListener(it)
        }

        val singleBookingRef = database.child("bookings").child(bookingId)

        activeBookingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null || !isAdded) return
                val status = snapshot.child("status").getValue(String::class.java) ?: return

                if (status == "CANCELLED" || status == "COMPLETED") {
                    val finishedTripGroupId = snapshot.child("tripGroupId").getValue(String::class.java)

                    if (noShowHandling && !finishedTripGroupId.isNullOrEmpty()) return

                    // Dropping off (or losing) ONE passenger of a shared trip must not end the
                    // whole trip: the other passenger is still in the car. Only reset the screen
                    // once every booking in the group is finished.
                    val teardown: () -> Unit = {

                        if (status == "CANCELLED") {
                            val reason = snapshot.child("cancelReason").getValue(String::class.java)
                            val msg = if (reason == NoShowManager.REASON_NO_SHOW) {
                                "No-show recorded. You'll be credited in your wallet."
                            } else {
                                "Passenger cancelled the ride request."
                            }
                            context?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
                        }

                        stopListeningForRequests()
                        clearRouteAndMarkers()
                        val finishedBookingId = activeBookingId
                        activeBookingId = null

                        if (finishedBookingId != null) {
                            if (finishedTripGroupId.isNullOrEmpty()) {
                                RouteDeviationManager.clearTripDeviationState(finishedBookingId)
                            } else {
                                database.child("bookings").orderByChild("tripGroupId").equalTo(finishedTripGroupId)
                                    .addListenerForSingleValueEvent(object : ValueEventListener {
                                        override fun onDataChange(groupSnapshot: DataSnapshot) {
                                            val activeBookingsExist = groupSnapshot.children.any { child ->
                                                val childStatus = child.child("status").getValue(String::class.java)
                                                childStatus != "COMPLETED" && childStatus != "CANCELLED"
                                            }

                                            if (!activeBookingsExist) {
                                                RouteDeviationManager.clearTripDeviationState(finishedBookingId)
                                            }
                                        }

                                        override fun onCancelled(error: DatabaseError) {}
                                    })
                            }
                        }

                        currentBooking = null
                        updateUIForState(DriverRideState.IDLE)
                        listenForPendingRideRequests()
                    }

                    if (finishedTripGroupId.isNullOrEmpty()) {
                        teardown()
                        return
                    }

                    database.child("bookings").orderByChild("tripGroupId").equalTo(finishedTripGroupId)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(groupSnap: DataSnapshot) {
                                if (_binding == null || !isAdded) return
                                val othersStillActive = groupSnap.children.any { child ->
                                    val childStatus = child.child("status").getValue(String::class.java)
                                    child.key != bookingId && childStatus != "COMPLETED" && childStatus != "CANCELLED"
                                }

                                if (!othersStillActive) {
                                    teardown()
                                    return
                                }

                                // Keep going with the remaining passenger(s).
                                if (status == "CANCELLED") {
                                    context?.let {
                                        Toast.makeText(it, "A passenger cancelled. Continuing with the other passenger.", Toast.LENGTH_LONG).show()
                                    }
                                    loadGroupStopsLocally(finishedTripGroupId) { rebuilt ->
                                        currentGroupStops = rebuilt
                                        currentStopIndex = 0
                                        updateCardForCurrentStop()
                                        if (rebuilt.isNotEmpty()) drawGroupRoutePreviewOnly(rebuilt)
                                    }
                                }
                                // COMPLETED: the shared-stop flow (advanceToNextStop) carries on by itself.
                            }

                            override fun onCancelled(error: DatabaseError) {}
                        })
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        singleBookingRef.addValueEventListener(activeBookingListener!!)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        detachContactListener()
        detachAlertTracking()
        detachDeviationListeners()
        deviationHandler.removeCallbacksAndMessages(null)
        navigator?.shutdown()
        navigator = null
        stopLocationUpdates()
        stopListeningForRequests()
        val myUid = auth.currentUser?.uid
        if (myUid != null && alertListener != null) {
            database.child("emergencyAlerts").child(myUid).removeEventListener(alertListener!!)
        }
        _binding = null
    }
}