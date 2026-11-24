package com.example.saferoute

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Controls the existing journey form. Journey details stay on this screen for now. */
class JourneyActivity : AppCompatActivity() {

    private lateinit var fromLayout: TextInputLayout
    private lateinit var toLayout: TextInputLayout
    private lateinit var modeLayout: TextInputLayout
    private lateinit var arrivalLayout: TextInputLayout
    private lateinit var editFrom: TextInputEditText
    private lateinit var editTo: TextInputEditText
    private lateinit var editArrival: TextInputEditText
    private lateinit var dropdownTravelMode: AutoCompleteTextView
    private lateinit var dropdownSafetyCheckInterval: AutoCompleteTextView
    private lateinit var editCompanionPhone: TextInputEditText
    private lateinit var editCompanionName: TextInputEditText
    private lateinit var editVehicleNumber: TextInputEditText
    private lateinit var startJourneyButton: MaterialButton
    private lateinit var journeyActionButton: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var activeJourneyCard: MaterialCardView
    private lateinit var journeyFormCard: MaterialCardView
    private lateinit var journeyStatusText: TextView
    private lateinit var activeJourneyTitle: TextView
    private lateinit var activeStatusText: TextView
    private lateinit var currentLocationLayout: android.view.View
    private lateinit var currentLocationMessage: TextView
    private lateinit var locationCoordinates: TextView
    private lateinit var locationProgress: ProgressBar
    private lateinit var getLocationButton: MaterialButton
    private lateinit var openMapsButton: MaterialButton
    private lateinit var lowBatteryLayout: android.view.View
    private lateinit var lowBatteryWarning: TextView
    private lateinit var shareLowBatteryButton: MaterialButton
    private lateinit var safetyCheckButton: MaterialButton

    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private var currentJourney: Journey? = null

    private var isSaving = false
    private var isUpdating = false
    private var currentLocationLink: String? = null
    private var lastSuccessfulLocationLink: String? = null
    private var pendingAlertType: AlertType? = null
    private var latestBatteryPercent = -1
    private var batteryInformationUnavailableShown = false
    private var isPreparingAlertShare = false
    private var isActivityResumed = false
    private var batteryReceiverRegistered = false
    private var pendingSafetyCheck: Pair<String, String>? = null
    private var pendingEmergencyCheck: Pair<String, String>? = null
    private var loadingNotificationJourney = false
    private var pendingAutomaticHelpResume = false
    private var automaticHelpShareLaunched = false
    private var automaticSafetyDialogShowing = false
    private var automaticEmergencyDialogShowing = false
    private var pendingReadinessJourney: Journey? = null
    private var readinessDialog: AlertDialog? = null
    private var isCheckingReadiness = false
    private var pendingArrivalEmergencyJourneyId: String? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            refreshJourneyProgress()
            progressHandler.postDelayed(this, 1000L)
        }
    }

    // A null value means the user has not selected a date and time yet.
    private var arrivalCalendar: Calendar? = null

    private enum class AlertType {
        LOW_BATTERY,
        SAFETY_HELP,
        ARRIVAL_MISSED
    }

    private enum class ReadinessLevel { READY, REQUIRED, OPTIONAL }
    private enum class ReadinessAction {
        CONTACTS,
        RETRY_CONTACTS,
        LOCATION_PERMISSION,
        NOTIFICATIONS,
        EXACT_ALARMS,
        LOCATION_SETTINGS
    }

    private data class ReadinessCheck(
        val title: String,
        val details: String,
        val level: ReadinessLevel,
        val actionLabel: String? = null,
        val action: ReadinessAction? = null
    )

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) updateBatteryWarning(intent)
        }
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                hasLocationPermission()

            if (pendingAlertType != null) {
                val alertType = pendingAlertType
                pendingAlertType = null
                if (alertType != null) {
                    if (granted) findFreshLocationForAlert(alertType)
                    else continueAlertWithLastLocation(alertType)
                }
            } else if (pendingReadinessJourney != null) {
                val journey = pendingReadinessJourney
                if (!granted) Toast.makeText(this, getString(R.string.readiness_location_permission_required), Toast.LENGTH_LONG).show()
                if (journey != null) showSafetyReadiness(journey)
            } else if (granted && currentJourney?.status == "active") {
                retrieveCurrentLocation()
            } else {
                showLocationMessage(getString(R.string.location_permission_denied))
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val uid = firebaseAuth.currentUser?.uid ?: return@registerForActivityResult
            if (granted) {
                SafetyReminderScheduler.resumeIfNotificationPermissionGranted(this, uid)
            } else {
                Toast.makeText(this, getString(R.string.safety_notifications_disabled), Toast.LENGTH_LONG).show()
            }
            pendingReadinessJourney?.let { showSafetyReadiness(it) }
        }

    private val emergencyShareLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (pendingAutomaticHelpResume && automaticHelpShareLaunched) {
                automaticHelpShareLaunched = false
                showResumeSafetyMonitoringDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journey)

        firebaseAuth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        val isEmergencyIntent = intent.getBooleanExtra(EXTRA_EMERGENCY_ALERT, false)
        val initialCheckInId = intent.getStringExtra(EXTRA_CHECK_IN_ID)
        val initialJourneyId = intent.getStringExtra(EXTRA_JOURNEY_ID)
        pendingSafetyCheck = if (!isEmergencyIntent) {
            initialCheckInId?.let { checkInId -> initialJourneyId?.let { journeyId -> checkInId to journeyId } }
        } else null
        pendingEmergencyCheck = if (isEmergencyIntent) {
            initialCheckInId?.let { checkInId -> initialJourneyId?.let { journeyId -> checkInId to journeyId } }
        } else null
        pendingArrivalEmergencyJourneyId = intent.getStringExtra(EXTRA_ARRIVAL_MISSED_JOURNEY_ID)

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        fromLayout = findViewById(R.id.layoutFrom)
        toLayout = findViewById(R.id.layoutTo)
        modeLayout = findViewById(R.id.layoutTravelMode)
        arrivalLayout = findViewById(R.id.layoutExpectedArrival)
        editFrom = findViewById(R.id.editFrom)
        editTo = findViewById(R.id.editTo)
        editArrival = findViewById(R.id.editExpectedArrival)
        dropdownTravelMode = findViewById(R.id.dropdownTravelMode)
        dropdownSafetyCheckInterval = findViewById(R.id.dropdownSafetyCheckInterval)
        editCompanionName = findViewById(R.id.editCompanionName)
        editCompanionPhone = findViewById(R.id.editCompanionPhone)
        editVehicleNumber = findViewById(R.id.editVehicleNumber)
        startJourneyButton = findViewById(R.id.btnStartJourney)
        journeyActionButton = findViewById(R.id.btnCompleteJourney)
        progressBar = findViewById(R.id.progressBar)
        activeJourneyCard = findViewById(R.id.cardActiveJourney)
        journeyFormCard = findViewById(R.id.cardJourneyForm)
        journeyStatusText = findViewById(R.id.tvJourneyStatus)
        activeJourneyTitle = findViewById(R.id.tvActiveJourneyTitle)
        activeStatusText = findViewById(R.id.tvActiveStatus)
        currentLocationLayout = findViewById(R.id.layoutCurrentLocation)
        currentLocationMessage = findViewById(R.id.tvCurrentLocationMessage)
        locationCoordinates = findViewById(R.id.tvLocationCoordinates)
        locationProgress = findViewById(R.id.progressLocation)
        getLocationButton = findViewById(R.id.btnGetCurrentLocation)
        openMapsButton = findViewById(R.id.btnOpenInMaps)
        lowBatteryLayout = findViewById(R.id.layoutLowBatteryWarning)
        lowBatteryWarning = findViewById(R.id.tvLowBatteryWarning)
        shareLowBatteryButton = findViewById(R.id.btnShareLowBatteryAlert)
        safetyCheckButton = findViewById(R.id.btnSafetyCheck)

        // Keep the form hidden until Firestore confirms there is no active journey.
        journeyFormCard.visibility = android.view.View.GONE
        activeJourneyCard.visibility = android.view.View.GONE
        progressBar.visibility = android.view.View.GONE
        journeyStatusText.visibility = android.view.View.GONE

        val travelModes = listOf("Cab", "Personal Vehicle", "Other")
        dropdownTravelMode.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, travelModes)
        )
        val safetyIntervals = listOf(5, 10, 15)
        dropdownSafetyCheckInterval.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                safetyIntervals.map { getString(R.string.safety_check_interval_option, it) }
            )
        )
        dropdownSafetyCheckInterval.setText(
            getString(R.string.safety_check_interval_option, DEFAULT_SAFETY_CHECK_INTERVAL_MINUTES),
            false
        )

        editArrival.setOnClickListener { chooseArrivalDate() }
        arrivalLayout.setEndIconOnClickListener { chooseArrivalDate() }
        startJourneyButton.setOnClickListener {
            validateForm()
        }
        journeyActionButton.setOnClickListener { confirmJourneyAction() }
        getLocationButton.setOnClickListener { requestCurrentLocation() }
        openMapsButton.setOnClickListener { openCurrentLocationInMaps() }
        shareLowBatteryButton.setOnClickListener {
            beginEmergencyShare(AlertType.LOW_BATTERY)
        }
        safetyCheckButton.setOnClickListener { showSafetyCheckDialog() }

        loadLatestJourney()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        ExpectedArrivalEvents.register { uid, journeyId ->
            if (firebaseAuth.currentUser?.uid == uid && currentJourney?.documentId == journeyId) loadLatestJourney()
        }
        if (firebaseAuth.currentUser?.uid?.let { ExpectedArrivalScheduler.consumeRefresh(this, it) } == true) {
            loadLatestJourney()
        }
        SafetyCheckEvents.register { event ->
            if (event.emergency) {
                pendingEmergencyCheck = event.checkInId to event.journeyId
                openPendingEmergency()
            } else {
                pendingSafetyCheck = event.checkInId to event.journeyId
                openPendingSafetyCheck()
            }
        }
        firebaseAuth.currentUser?.uid?.let { SafetyCheckInSync.flushPending(this, it) }
        currentJourney?.takeIf { it.status == "active" }?.let {
            SafetyReminderScheduler.resumeIfNotificationPermissionGranted(this, firebaseAuth.currentUser?.uid.orEmpty())
        }
        SafetyReminderScheduler.restore(this)
        firebaseAuth.currentUser?.uid?.let { uid ->
            SafetyReminderScheduler.pendingEmergency(this, uid)?.let { state ->
                pendingEmergencyCheck = state.emergencyCheckInId to state.journeyId
            }
            SafetyReminderScheduler.currentState(this)
                ?.takeIf { it.uid == uid && it.currentStatus == "pending" }
                ?.let { state -> pendingSafetyCheck = state.currentCheckInId to state.journeyId }
        }
        openPendingSafetyCheck()
        openPendingEmergency()
        startJourneyProgressUpdates()
        syncBatteryMonitoring()
        pendingReadinessJourney?.let { journey ->
            if (readinessDialog?.isShowing != true && !isCheckingReadiness) showSafetyReadiness(journey)
        }
    }

    override fun onPause() {
        isActivityResumed = false
        ExpectedArrivalEvents.unregister()
        SafetyCheckEvents.unregister()
        progressHandler.removeCallbacks(progressTicker)
        stopBatteryMonitoring()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingArrivalEmergencyJourneyId = intent.getStringExtra(EXTRA_ARRIVAL_MISSED_JOURNEY_ID)
        if (intent.getBooleanExtra(EXTRA_REFRESH_JOURNEY, false)) loadLatestJourney()
        val checkInId = intent.getStringExtra(EXTRA_CHECK_IN_ID)
        val journeyId = intent.getStringExtra(EXTRA_JOURNEY_ID)
        if (checkInId != null && journeyId != null) {
            if (intent.getBooleanExtra(EXTRA_EMERGENCY_ALERT, false)) {
                pendingEmergencyCheck = checkInId to journeyId
                openPendingEmergency()
            } else {
                pendingSafetyCheck = checkInId to journeyId
                openPendingSafetyCheck()
            }
        }
    }

    /** Loads only this signed-in user's journeys and prefers active over planned. */
    private fun loadLatestJourney() {
        val userId = firebaseAuth.currentUser?.uid
        if (userId == null) {
            showLoadError("Please sign in again to view your journeys.")
            return
        }

        progressBar.visibility = android.view.View.VISIBLE
        journeyStatusText.text = getString(R.string.journey_loading)
        journeyStatusText.visibility = android.view.View.VISIBLE
        journeyFormCard.visibility = android.view.View.GONE
        activeJourneyCard.visibility = android.view.View.GONE

        firestore.collection("users")
            .document(userId)
            .collection("journeys")
            .get()
            .addOnSuccessListener { snapshot ->
                val journeys = snapshot.documents.mapNotNull { document ->
                    document.toObject(Journey::class.java)
                        ?.copy(documentId = document.id)
                }

                // Active journeys always take priority. Expected arrival is used
                // to choose consistently when multiple planned items are present.
                val selectedJourney = journeys
                    .filter { it.status == "active" }
                    .maxByOrNull { it.startedAt?.seconds ?: it.expectedArrival?.seconds ?: 0L }
                    ?: journeys
                        .filter { it.status == "planned" }
                        .maxByOrNull { it.expectedArrival?.seconds ?: 0L }
                    ?: journeys
                        .filter { it.status == "completed" }
                        .maxByOrNull { it.completedAt?.seconds ?: it.expectedArrival?.seconds ?: 0L }

                progressBar.visibility = android.view.View.GONE
                currentJourney = selectedJourney
                if (selectedJourney == null) {
                    firebaseAuth.currentUser?.uid?.let { SafetyReminderScheduler.cancelForUser(this, it) }
                    showJourneyForm()
                } else {
                    showJourney(selectedJourney)
                }
                openPendingSafetyCheck()
                openPendingEmergency()
            }
            .addOnFailureListener { exception ->
                progressBar.visibility = android.view.View.GONE
                val detail = exception.localizedMessage?.takeIf { it.isNotBlank() }
                val message = detail ?: getString(R.string.error_journey_load_failed)
                showLoadError(message)
            }
    }

    private fun openPendingSafetyCheck() {
        val pending = pendingSafetyCheck ?: return
        if (loadingNotificationJourney) return
        val (checkInId, journeyId) = pending
        val visibleJourney = currentJourney
        if (visibleJourney?.documentId == journeyId && visibleJourney.status == "active") {
            pendingSafetyCheck = null
            when (SafetyReminderScheduler.checkInStatus(this, checkInId)) {
                "pending" -> showAutomaticSafetyCheckDialog(checkInId, journeyId)
                "timed_out" -> showEmergencyAlert(checkInId, journeyId)
            }
            return
        }

        val uid = firebaseAuth.currentUser?.uid ?: return
        loadingNotificationJourney = true
        firestore.collection("users").document(uid).collection("journeys").document(journeyId)
            .get()
            .addOnSuccessListener { document ->
                loadingNotificationJourney = false
                val journey = document.toObject(Journey::class.java)?.copy(documentId = document.id)
                if (journey?.status == "active") {
                    currentJourney = journey
                    showJourney(journey)
                    pendingSafetyCheck = null
                    when (SafetyReminderScheduler.checkInStatus(this, checkInId)) {
                        "pending" -> showAutomaticSafetyCheckDialog(checkInId, journeyId)
                        "timed_out" -> showEmergencyAlert(checkInId, journeyId)
                    }
                } else {
                    pendingSafetyCheck = null
                    Toast.makeText(this, getString(R.string.safety_check_no_longer_active), Toast.LENGTH_LONG).show()
                    loadLatestJourney()
                }
            }
            .addOnFailureListener { exception ->
                loadingNotificationJourney = false
                pendingSafetyCheck = null
                Log.e(TAG, "Could not open the journey from a safety notification", exception)
                Snackbar.make(safetyCheckButton, getString(R.string.safety_check_open_failed), Snackbar.LENGTH_LONG).show()
            }
    }

    private fun openPendingEmergency() {
        val pending = pendingEmergencyCheck ?: return
        val (checkInId, journeyId) = pending
        val uid = firebaseAuth.currentUser?.uid ?: return
        val state = SafetyReminderScheduler.pendingEmergency(this, uid) ?: run {
            pendingEmergencyCheck = null
            return
        }
        if (state.journeyId != journeyId || state.emergencyCheckInId != checkInId) return
        val journey = currentJourney
        if (journey?.documentId == journeyId && journey.status == "active") {
            pendingEmergencyCheck = null
            showEmergencyAlert(checkInId, journeyId)
            return
        }
        if (loadingNotificationJourney) return
        loadingNotificationJourney = true
        firestore.collection("users").document(uid).collection("journeys").document(journeyId)
            .get()
            .addOnSuccessListener { document ->
                loadingNotificationJourney = false
                val loaded = document.toObject(Journey::class.java)?.copy(documentId = document.id)
                if (loaded?.status == "active") {
                    currentJourney = loaded
                    showJourney(loaded)
                    pendingEmergencyCheck = null
                    showEmergencyAlert(checkInId, journeyId)
                } else {
                    pendingEmergencyCheck = null
                    loadLatestJourney()
                }
            }
            .addOnFailureListener { exception ->
                loadingNotificationJourney = false
                Log.e(TAG, "Could not load journey for the missed check-in alert", exception)
            }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Shows the existing setup form when there is no planned or active journey. */
    private fun showJourneyForm() {
        currentJourney = null
        activeJourneyCard.visibility = android.view.View.GONE
        journeyFormCard.visibility = android.view.View.VISIBLE
        journeyStatusText.text = getString(R.string.journey_none_active)
        journeyStatusText.visibility = android.view.View.VISIBLE
        lowBatteryLayout.visibility = android.view.View.GONE
        safetyCheckButton.visibility = android.view.View.GONE
        syncBatteryMonitoring()
    }

    /** Fills the existing read-only card and selects its action from its status. */
    private fun showJourney(journey: Journey) {
        val uid = firebaseAuth.currentUser?.uid
        if (journey.status == "active" && uid != null) {
            SafetyReminderScheduler.ensureMonitoring(this, uid, journey.documentId, journey.safetyCheckIntervalMinutes)
            ExpectedArrivalScheduler.ensureScheduled(this, uid, journey.documentId, journey.expectedArrival?.toDate()?.time)
            SafetyCheckInSync.flushPending(this, uid)
            if (!SafetyReminderScheduler.canScheduleExactAlarms(this)) {
                Toast.makeText(this, getString(R.string.safety_reminders_may_be_delayed), Toast.LENGTH_LONG).show()
            }
        } else if (uid != null) {
            SafetyReminderScheduler.cancelForUser(this, uid)
            if (journey.status == "completed") ExpectedArrivalScheduler.cancelForJourney(this, uid, journey.documentId)
        }
        journeyFormCard.visibility =
            if (journey.status == "completed") android.view.View.VISIBLE else android.view.View.GONE
        activeJourneyCard.visibility = android.view.View.VISIBLE
        currentLocationLayout.visibility =
            if (journey.status == "active") android.view.View.VISIBLE else android.view.View.GONE
        safetyCheckButton.visibility =
            if (journey.status == "active") android.view.View.VISIBLE else android.view.View.GONE
        if (journey.status == "active") {
            lastSuccessfulLocationLink = getSavedLocationLink(journey.documentId)
            restoreLowBatteryWarning(journey)
        } else {
            lowBatteryLayout.visibility = android.view.View.GONE
        }
        journeyStatusText.visibility =
            if (journey.status == "active") android.view.View.VISIBLE else android.view.View.GONE

        activeJourneyTitle.text = when (journey.status) {
            "planned" -> getString(R.string.title_journey) + " — Planned"
            "completed" -> getString(R.string.title_journey) + " — Completed"
            else -> getString(R.string.label_active_journey)
        }
        activeStatusText.text = when (journey.status) {
            "planned" -> getString(R.string.journey_status_planned)
            "completed" -> getString(R.string.label_completed_journey)
            else -> getString(R.string.journey_status_active)
        }

        findViewById<TextView>(R.id.tvActiveFrom).text = journey.from
        findViewById<TextView>(R.id.tvActiveTo).text = journey.to
        findViewById<TextView>(R.id.tvActiveMode).text = journey.travelMode
        findViewById<TextView>(R.id.tvActiveExpected).text = formatTimestamp(journey.expectedArrival)
        findViewById<TextView>(R.id.tvActiveSafetyCheckInterval).text =
            getString(R.string.safety_check_interval_display, journey.safetyCheckIntervalMinutes)
        findViewById<TextView>(R.id.tvActiveStartedAt).text =
            journey.startedAt?.let { formatTimestamp(it) } ?: "—"
        loadLastSafetyResponse(journey)
        refreshJourneyProgress()

        val hasCompanion = journey.companionName.isNotBlank()
        val hasPhone = journey.companionPhone.isNotBlank()
        val hasVehicle = journey.vehicleNumber.isNotBlank()
        findViewById<TextView>(R.id.tvNoCompanion).visibility =
            if (!hasCompanion && !hasPhone && !hasVehicle) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.rowCompanionName).visibility =
            if (hasCompanion) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.rowCompanionPhone).visibility =
            if (hasPhone) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.rowVehicleNumber).visibility =
            if (hasVehicle) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<TextView>(R.id.tvActiveCompanionName).text = journey.companionName
        findViewById<TextView>(R.id.tvActiveCompanionPhone).text = journey.companionPhone
        findViewById<TextView>(R.id.tvActiveVehicleNumber).text = journey.vehicleNumber

        when (journey.status) {
            "planned" -> {
                journeyActionButton.text = getString(R.string.btn_start_journey)
                journeyActionButton.visibility = android.view.View.VISIBLE
                journeyStatusText.visibility = android.view.View.GONE
            }
            "active" -> {
                journeyActionButton.text = getString(R.string.btn_end_journey)
                journeyActionButton.visibility = android.view.View.VISIBLE
                journeyStatusText.text = getString(R.string.journey_already_active)
                journeyStatusText.visibility = android.view.View.VISIBLE
            }
            else -> journeyActionButton.visibility = android.view.View.GONE
        }
        syncBatteryMonitoring()
        if (journey.status == "active" && pendingArrivalEmergencyJourneyId == journey.documentId) {
            pendingArrivalEmergencyJourneyId = null
            showArrivalMissedSharePrompt()
        }
    }

    private fun startJourneyProgressUpdates() {
        progressHandler.removeCallbacks(progressTicker)
        refreshJourneyProgress()
        progressHandler.postDelayed(progressTicker, 1000L)
    }

    private fun refreshJourneyProgress() {
        val journey = currentJourney ?: return
        val arrivalCountdown = findViewById<TextView>(R.id.tvJourneyArrivalCountdown)
        val arrivalStatus = findViewById<TextView>(R.id.tvJourneyArrivalStatus)
        val arrivalState = ExpectedArrivalScheduler.currentState(this)
            ?.takeIf { it.uid == firebaseAuth.currentUser?.uid && it.journeyId == journey.documentId }
        val expectedAt = arrivalState?.expectedAt ?: journey.expectedArrival?.toDate()?.time
        arrivalCountdown.text = if (journey.status == "active" && expectedAt != null) {
            getString(
                R.string.journey_progress_arrival_remaining,
                formatCountdown((expectedAt - System.currentTimeMillis()).coerceAtLeast(0L)),
                formatTimestamp(Timestamp(Date(expectedAt)))
            )
        } else getString(R.string.journey_progress_arrival_unavailable)
        arrivalStatus.text = when {
            journey.status == "completed" && arrivalState?.status == "arrived" -> getString(R.string.journey_progress_arrival_arrived)
            journey.status == "completed" -> getString(R.string.journey_progress_arrival_journey_completed)
            arrivalState?.status == "pending" -> getString(
                R.string.journey_progress_arrival_pending,
                formatCountdown((arrivalState.deadlineAt - System.currentTimeMillis()).coerceAtLeast(0L))
            )
            arrivalState?.status == "missed" -> getString(R.string.journey_progress_arrival_missed)
            arrivalState?.status == "arrived" -> getString(R.string.journey_progress_arrival_arrived)
            arrivalState?.status == "cancelled" -> getString(R.string.journey_progress_arrival_cancelled)
            arrivalState?.status == "scheduled" -> getString(R.string.journey_progress_arrival_waiting)
            else -> getString(R.string.journey_progress_arrival_waiting)
        }
        val startedAtMillis = journey.startedAt?.toDate()?.time
        val completedAtMillis = if (journey.status == "completed") journey.completedAt?.toDate()?.time else null
        if (startedAtMillis == null || (journey.status == "completed" && completedAtMillis == null)) {
            findViewById<TextView>(R.id.tvJourneyDuration).text =
                getString(R.string.journey_progress_duration_unavailable)
        } else {
            val endMillis = completedAtMillis ?: System.currentTimeMillis()
            val elapsed = (endMillis - startedAtMillis).coerceAtLeast(0L)
            findViewById<TextView>(R.id.tvJourneyDuration).text =
                getString(R.string.journey_progress_duration_value, formatElapsedDuration(elapsed))
        }

        val nextCheckView = findViewById<TextView>(R.id.tvJourneyNextCheck)
        if (journey.status != "active") {
            nextCheckView.text = getString(R.string.journey_progress_next_unavailable)
            return
        }

        val uid = firebaseAuth.currentUser?.uid
        val state = SafetyReminderScheduler.currentState(this)
        if (uid == null || state?.uid != uid || state.journeyId != journey.documentId) {
            nextCheckView.text = getString(R.string.journey_progress_next_unavailable)
            return
        }

        nextCheckView.text = when {
            state.currentStatus == "pending" && state.deadlineAt > 0L -> {
                val remaining = (state.deadlineAt - System.currentTimeMillis()).coerceAtLeast(0L)
                getString(
                    R.string.journey_progress_deadline,
                    formatTimestamp(Timestamp(Date(state.deadlineAt))),
                    formatCountdown(remaining)
                )
            }
            state.pausedReason == "user_requested_help" ->
                getString(R.string.journey_progress_next_paused_help)
            state.pausedReason == "emergency_triggered" ->
                getString(R.string.journey_progress_next_paused_emergency)
            state.pausedReason == "notification_permission" ->
                getString(R.string.journey_progress_next_paused_notifications)
            state.nextCheckInId.isNotBlank() && state.nextReminderAt > 0L -> {
                val remaining = (state.nextReminderAt - System.currentTimeMillis()).coerceAtLeast(0L)
                getString(
                    R.string.journey_progress_next_scheduled,
                    formatCountdown(remaining),
                    formatTimestamp(Timestamp(Date(state.nextReminderAt)))
                )
            }
            else -> getString(R.string.journey_progress_next_unavailable)
        }
    }

    private fun loadLastSafetyResponse(journey: Journey) {
        val responseView = findViewById<TextView>(R.id.tvJourneyLastResponse)
        responseView.text = getString(R.string.journey_progress_last_loading)
        val uid = firebaseAuth.currentUser?.uid
        if (uid == null || journey.documentId.isBlank()) {
            responseView.text = getString(R.string.journey_progress_last_unavailable)
            return
        }

        firestore.collection("users").document(uid).collection("safetyCheckIns")
            .get()
            .addOnSuccessListener { snapshot ->
                if (firebaseAuth.currentUser?.uid != uid || currentJourney?.documentId != journey.documentId) return@addOnSuccessListener
                val latestResponse = snapshot.documents.mapNotNull { document ->
                    if (document.getString("journeyId") != journey.documentId) return@mapNotNull null
                    val response = document.getString("response")?.lowercase(Locale.ROOT) ?: return@mapNotNull null
                    val label = when (response) {
                        "safe" -> getString(R.string.journey_progress_response_safe)
                        "help" -> getString(R.string.journey_progress_response_help)
                        "no_response" -> getString(R.string.journey_progress_response_no_response)
                        else -> return@mapNotNull null
                    }
                    val timestamp = document.getTimestamp("respondedAt")
                        ?: document.getTimestamp("recordedAt")
                        ?: document.getTimestamp("createdAt")
                    Triple(label, timestamp, timestamp?.seconds ?: Long.MIN_VALUE)
                }.maxByOrNull { it.third }

                responseView.text = if (latestResponse == null) {
                    getString(R.string.journey_progress_last_empty)
                } else {
                    val time = latestResponse.second?.let(::formatTimestamp)
                        ?: getString(R.string.journey_progress_time_unavailable)
                    getString(R.string.journey_progress_last_response, latestResponse.first, time)
                }
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "Could not load the last safety response for this journey", exception)
                if (firebaseAuth.currentUser?.uid == uid && currentJourney?.documentId == journey.documentId) {
                    responseView.text = getString(R.string.journey_progress_last_unavailable)
                }
            }
    }

    private fun formatElapsedDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000L
        val days = totalSeconds / 86_400L
        val hours = (totalSeconds % 86_400L) / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            else -> "${minutes}m ${seconds}s"
        }
    }

    private fun formatCountdown(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "${hours}h ${minutes}m ${seconds}s" else "${minutes}m ${seconds}s"
    }

    /** Location can be requested only for the active journey shown on screen. */
    private fun requestCurrentLocation() {
        if (currentJourney?.status != "active") {
            showLocationMessage(getString(R.string.location_active_journey_only))
            return
        }

        if (hasLocationPermission()) {
            retrieveCurrentLocation()
            return
        }

        // Explain the one-time, user-requested location use before Android asks.
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.location_permission_title))
            .setMessage(getString(R.string.location_permission_message))
            .setNegativeButton(getString(R.string.sos_dialog_cancel), null)
            .setPositiveButton(getString(R.string.location_permission_continue)) { _, _ ->
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            }
            .show()
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    /** Uses a single Fused Location request; this is not continuous tracking. */
    @androidx.annotation.RequiresPermission(
        anyOf = [
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ]
    )
    private fun retrieveCurrentLocation() {
        if (currentJourney?.status != "active" || !hasLocationPermission()) return

        if (!areLocationServicesEnabled()) {
            showLocationMessage(getString(R.string.location_services_disabled))
            return
        }

        getLocationButton.isEnabled = false
        openMapsButton.visibility = android.view.View.GONE
        locationCoordinates.visibility = android.view.View.GONE
        currentLocationLink = null
        currentLocationMessage.text = getString(R.string.location_fetching)
        locationProgress.visibility = android.view.View.VISIBLE

        val priority = if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(priority, null)
            .addOnSuccessListener { location ->
                locationProgress.visibility = android.view.View.GONE
                getLocationButton.isEnabled = true

                if (location == null) {
                    showLocationMessage(getString(R.string.location_unavailable))
                    return@addOnSuccessListener
                }

                val latitude = String.format(Locale.US, "%.6f", location.latitude)
                val longitude = String.format(Locale.US, "%.6f", location.longitude)
                currentLocationMessage.text = getString(R.string.location_retrieved)
                locationCoordinates.text = getString(
                    R.string.location_coordinates,
                    latitude,
                    longitude
                )
                locationCoordinates.visibility = android.view.View.VISIBLE
                currentLocationLink =
                    "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                rememberLocationLink(currentLocationLink!!)
                openMapsButton.visibility = android.view.View.VISIBLE
            }
            .addOnFailureListener {
                getLocationButton.isEnabled = true
                showLocationMessage(getString(R.string.location_unavailable))
            }
    }

    private fun areLocationServicesEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    /** Registers the battery receiver only while this active-journey screen is visible. */
    private fun syncBatteryMonitoring() {
        val shouldMonitor = isActivityResumed && currentJourney?.status == "active"
        if (!shouldMonitor) {
            stopBatteryMonitoring()
            return
        }
        if (batteryReceiverRegistered) return

        val stickyBatteryIntent = ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        batteryReceiverRegistered = true
        // The sticky intent gives us the battery level immediately on registration.
        stickyBatteryIntent?.let { updateBatteryWarning(it) }
    }

    private fun stopBatteryMonitoring() {
        if (!batteryReceiverRegistered) return
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {
            // It may already have been removed by Android during Activity shutdown.
        }
        batteryReceiverRegistered = false
    }

    /** Checks each battery broadcast but presents a warning only once per journey. */
    private fun updateBatteryWarning(intent: Intent) {
        val journey = currentJourney ?: return
        if (journey.status != "active") return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            if (!batteryInformationUnavailableShown) {
                batteryInformationUnavailableShown = true
                Snackbar.make(
                    journeyActionButton,
                    getString(R.string.battery_information_unavailable),
                    Snackbar.LENGTH_LONG
                ).show()
            }
            return
        }

        val percent = (level * 100f / scale).roundToInt()
        latestBatteryPercent = percent
        val preferences = batteryPreferences()
        preferences.edit().putInt(PREF_LAST_BATTERY_PERCENT, percent).apply()

        if (percent <= LOW_BATTERY_LIMIT) {
            val alreadyAlerted = preferences.getString(PREF_ALERTED_JOURNEY_ID, null) == journey.documentId
            val warningStillVisible = preferences.getString(PREF_VISIBLE_WARNING_JOURNEY_ID, null) == journey.documentId

            if (!alreadyAlerted) {
                // Store this journey ID so another battery broadcast cannot repeat the alert.
                preferences.edit()
                    .putString(PREF_ALERTED_JOURNEY_ID, journey.documentId)
                    .putString(PREF_VISIBLE_WARNING_JOURNEY_ID, journey.documentId)
                    .apply()
                showLowBatteryWarning(percent)
            } else if (warningStillVisible) {
                showLowBatteryWarning(percent)
            }
        } else {
            // Hide after charging above the threshold, but keep the once-per-trip flag.
            if (preferences.getString(PREF_VISIBLE_WARNING_JOURNEY_ID, null) == journey.documentId) {
                preferences.edit().remove(PREF_VISIBLE_WARNING_JOURNEY_ID).apply()
            }
            lowBatteryLayout.visibility = android.view.View.GONE
        }
    }

    private fun showLowBatteryWarning(percent: Int) {
        lowBatteryWarning.text = getString(R.string.low_battery_warning, percent)
        lowBatteryLayout.visibility = android.view.View.VISIBLE
    }

    private fun restoreLowBatteryWarning(journey: Journey) {
        val visibleJourneyId = batteryPreferences()
            .getString(PREF_VISIBLE_WARNING_JOURNEY_ID, null)
        if (visibleJourneyId == journey.documentId) {
            val percent = latestBatteryPercent.takeIf { it >= 0 }
                ?: batteryPreferences().getInt(PREF_LAST_BATTERY_PERCENT, LOW_BATTERY_LIMIT)
            showLowBatteryWarning(percent)
        } else {
            lowBatteryLayout.visibility = android.view.View.GONE
        }
    }

    private fun resetBatteryAlertForNewJourney() {
        batteryPreferences().edit()
            .remove(PREF_ALERTED_JOURNEY_ID)
            .remove(PREF_VISIBLE_WARNING_JOURNEY_ID)
            .apply()
        lowBatteryLayout.visibility = android.view.View.GONE
    }

    private fun batteryPreferences() =
        getSharedPreferences(PREFERENCES_BATTERY_ALERTS, MODE_PRIVATE)

    private fun showLocationMessage(message: String) {
        locationProgress.visibility = android.view.View.GONE
        getLocationButton.isEnabled = true
        locationCoordinates.visibility = android.view.View.GONE
        openMapsButton.visibility = android.view.View.GONE
        currentLocationLink = null
        currentLocationMessage.text = message
    }

    private fun openCurrentLocationInMaps() {
        if (currentJourney?.status != "active") return
        val link = currentLocationLink ?: return

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                getString(R.string.location_maps_unavailable),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun rememberLocationLink(link: String) {
        val journeyId = currentJourney?.documentId ?: return
        lastSuccessfulLocationLink = link
        getSharedPreferences(PREFERENCES_LAST_LOCATION, MODE_PRIVATE)
            .edit()
            .putString(LAST_LOCATION_KEY_PREFIX + journeyId, link)
            .apply()
        val userId = firebaseAuth.currentUser?.uid ?: return
        firestore.collection("users").document(userId).collection("journeys").document(journeyId)
            .update(
                mapOf(
                    "lastLocationLink" to link,
                    "lastLocationRecordedAt" to FieldValue.serverTimestamp()
                )
            )
            .addOnFailureListener { exception ->
                Log.e(TAG, "Could not save the last one-shot location for emergency alerts", exception)
            }
    }

    private fun getSavedLocationLink(journeyId: String): String? =
        getSharedPreferences(PREFERENCES_LAST_LOCATION, MODE_PRIVATE)
            .getString(LAST_LOCATION_KEY_PREFIX + journeyId, null)

    /** Shows the two manual choices and records an unanswered check after the timeout. */
    private fun showSafetyCheckDialog() {
        val journey = currentJourney?.takeIf { it.status == "active" } ?: return
        var responseRecorded = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.safety_check_question))
            .setPositiveButton(getString(R.string.safety_check_safe), null)
            .setNegativeButton(getString(R.string.safety_check_help), null)
            .create()

        val responseTimer = object : CountDownTimer(SAFETY_CHECK_TIMEOUT_MS, SAFETY_CHECK_TIMEOUT_MS) {
            override fun onTick(millisUntilFinished: Long) = Unit

            override fun onFinish() {
                if (!responseRecorded && dialog.isShowing) {
                    responseRecorded = true
                    recordSafetyCheckIn("no_response", journey.documentId)
                    dialog.dismiss()
                }
            }
        }

        dialog.setOnShowListener {
            responseTimer.start()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (!responseRecorded) {
                    responseRecorded = true
                    responseTimer.cancel()
                    dialog.dismiss()
                    recordSafetyCheckIn("safe", journey.documentId)
                }
            }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                if (!responseRecorded) {
                    responseRecorded = true
                    responseTimer.cancel()
                    dialog.dismiss()
                    recordSafetyCheckIn("help", journey.documentId)
                    beginEmergencyShare(AlertType.SAFETY_HELP)
                }
            }
        }
        dialog.setOnCancelListener {
            responseTimer.cancel()
            if (!responseRecorded) {
                responseRecorded = true
                recordSafetyCheckIn("no_response", journey.documentId)
            }
        }
        dialog.setOnDismissListener { responseTimer.cancel() }
        dialog.show()
    }

    /** Displays a locally scheduled check-in with its persisted 60-second deadline. */
    private fun showAutomaticSafetyCheckDialog(checkInId: String, journeyId: String) {
        if (automaticSafetyDialogShowing) return
        val journey = currentJourney?.takeIf { it.status == "active" && it.documentId == journeyId } ?: return
        val uid = firebaseAuth.currentUser?.uid ?: return
        val pending = SafetyReminderScheduler.pendingCheckIn(this, uid, checkInId) ?: return
        if (System.currentTimeMillis() >= pending.deadlineAt) {
            if (SafetyReminderScheduler.expireIfDue(this, uid, journeyId, checkInId)) {
                showEmergencyAlert(checkInId, journeyId)
            }
            return
        }
        val countdown = TextView(this).apply {
            setPadding(48, 16, 48, 8)
            textSize = 15f
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.safety_check_auto_title))
            .setMessage(getString(R.string.safety_check_auto_message, journey.to))
            .setView(countdown)
            .setPositiveButton(getString(R.string.safety_check_safe), null)
            .setNegativeButton(getString(R.string.safety_check_help), null)
            .create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            val timer = object : CountDownTimer((pending.deadlineAt - System.currentTimeMillis()).coerceAtLeast(0L), 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    countdown.text = getString(R.string.safety_check_countdown, (millisUntilFinished + 999L) / 1000L)
                }

                override fun onFinish() {
                    dialog.dismiss()
                    if (isActivityResumed && SafetyReminderScheduler.expireIfDue(this@JourneyActivity, uid, journeyId, checkInId)) {
                        showEmergencyAlert(checkInId, journeyId)
                    }
                }
            }.start()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                timer.cancel()
                handleAutomaticSafetyResponse(dialog, checkInId, journeyId, "safe")
            }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                timer.cancel()
                handleAutomaticSafetyResponse(dialog, checkInId, journeyId, "help")
            }
            dialog.setOnDismissListener {
                timer.cancel()
                automaticSafetyDialogShowing = false
            }
        }
        automaticSafetyDialogShowing = true
        dialog.show()
        try {
            RingtoneManager.getRingtone(
                this,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )?.play()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not play the safety check-in sound", exception)
        }
    }

    private fun handleAutomaticSafetyResponse(
        dialog: androidx.appcompat.app.AlertDialog,
        checkInId: String,
        journeyId: String,
        response: String
    ) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        val result = SafetyReminderScheduler.respond(this, uid, journeyId, checkInId, response)
        dialog.dismiss()
        when (result) {
            SafetyReminderScheduler.ResponseResult.RECORDED -> if (response == "safe") {
                currentJourney?.takeIf { it.documentId == journeyId }?.let(::loadLastSafetyResponse)
                Toast.makeText(this, getString(R.string.safety_check_recorded), Toast.LENGTH_LONG).show()
            } else {
                currentJourney?.takeIf { it.documentId == journeyId }?.let(::loadLastSafetyResponse)
                Toast.makeText(this, getString(R.string.safety_check_help_recorded), Toast.LENGTH_SHORT).show()
                pendingAutomaticHelpResume = true
                beginEmergencyShare(AlertType.SAFETY_HELP)
            }
            SafetyReminderScheduler.ResponseResult.TOO_LATE -> {
                currentJourney?.takeIf { it.documentId == journeyId }?.let(::loadLastSafetyResponse)
                Toast.makeText(this, getString(R.string.safety_check_response_too_late), Toast.LENGTH_LONG).show()
                showEmergencyAlert(checkInId, journeyId)
            }
            SafetyReminderScheduler.ResponseResult.NOT_ACTIVE ->
                Snackbar.make(safetyCheckButton, getString(R.string.safety_check_record_failed), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun showEmergencyAlert(checkInId: String, journeyId: String) {
        if (automaticEmergencyDialogShowing) return
        val uid = firebaseAuth.currentUser?.uid ?: return
        val state = SafetyReminderScheduler.pendingEmergency(this, uid) ?: return
        if (state.journeyId != journeyId || state.emergencyCheckInId != checkInId) return
        currentJourney?.takeIf { it.documentId == journeyId }?.let(::loadLastSafetyResponse)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.safety_missed_alert_title))
            .setMessage(getString(R.string.safety_missed_alert_message))
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setPositiveButton(getString(R.string.btn_share_emergency_alert)) { _, _ ->
                beginEmergencyShare(AlertType.SAFETY_HELP)
            }
            .create()
        dialog.setOnDismissListener { automaticEmergencyDialogShowing = false }
        automaticEmergencyDialogShowing = true
        dialog.show()
    }

    private fun showArrivalMissedSharePrompt() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.arrival_missed_notification_title))
            .setMessage(getString(R.string.arrival_missed_share_prompt))
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setPositiveButton(getString(R.string.arrival_action_share)) { _, _ ->
                beginEmergencyShare(AlertType.ARRIVAL_MISSED)
            }
            .show()
    }

    private fun showResumeSafetyMonitoringDialog() {
        val journey = currentJourney?.takeIf { it.status == "active" } ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.safety_resume_title))
            .setMessage(getString(R.string.safety_resume_message))
            .setNegativeButton(getString(R.string.safety_resume_keep_paused)) { _, _ ->
                pendingAutomaticHelpResume = false
            }
            .setPositiveButton(getString(R.string.safety_resume_continue)) { _, _ ->
                val uid = firebaseAuth.currentUser?.uid ?: return@setPositiveButton
                firestore.collection("users").document(uid).collection("journeys")
                    .document(journey.documentId)
                    .get()
                    .addOnSuccessListener { document ->
                        if (document.getString("status") == "active" &&
                            SafetyReminderScheduler.resumeAfterHelp(this, uid, journey.documentId)) {
                            pendingAutomaticHelpResume = false
                            Toast.makeText(this, getString(R.string.safety_resume_success), Toast.LENGTH_LONG).show()
                        } else {
                            pendingAutomaticHelpResume = false
                            SafetyReminderScheduler.cancelForJourney(this, uid, journey.documentId)
                            loadLatestJourney()
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "Could not verify that the journey is still active", exception)
                        Snackbar.make(safetyCheckButton, getString(R.string.safety_resume_failed), Snackbar.LENGTH_LONG).show()
                    }
            }
            .show()
    }

    /** Stores one check-in under the signed-in user's account for later review. */
    private fun recordSafetyCheckIn(response: String, journeyId: String) {
        val userId = firebaseAuth.currentUser?.uid
        if (userId == null) {
            Snackbar.make(
                safetyCheckButton,
                getString(R.string.alert_sign_in_required),
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        firestore.collection("users")
            .document(userId)
            .collection("safetyCheckIns")
            .add(
                mapOf(
                    "response" to response,
                    "recordedAt" to FieldValue.serverTimestamp(),
                    "journeyId" to journeyId
                )
            )
            .addOnSuccessListener {
                currentJourney?.takeIf { it.documentId == journeyId }?.let(::loadLastSafetyResponse)
                val message = when (response) {
                    "safe" -> getString(R.string.safety_check_recorded)
                    "no_response" -> getString(R.string.safety_check_no_response_recorded)
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { exception ->
                val detail = exception.localizedMessage?.takeIf { it.isNotBlank() }
                val message = if (detail == null) {
                    getString(R.string.safety_check_record_failed)
                } else {
                    "${getString(R.string.safety_check_record_failed)} ($detail)"
                }
                Snackbar.make(safetyCheckButton, message, Snackbar.LENGTH_LONG).show()
            }
    }

    /** Begins a user-requested emergency share and tries to refresh location first. */
    private fun beginEmergencyShare(alertType: AlertType) {
        if (currentJourney?.status != "active") return
        if (hasLocationPermission()) {
            findFreshLocationForAlert(alertType)
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.location_permission_title))
            .setMessage(getString(R.string.location_permission_alert_message))
            .setNegativeButton(getString(R.string.btn_share_without_location)) { _, _ ->
                continueAlertWithLastLocation(alertType)
            }
            .setPositiveButton(getString(R.string.location_permission_continue)) { _, _ ->
                pendingAlertType = alertType
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            }
            .show()
    }

    /** Gets a fresh one-shot location, then falls back to saved location if needed. */
    @androidx.annotation.RequiresPermission(
        anyOf = [
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ]
    )
    private fun findFreshLocationForAlert(alertType: AlertType) {
        if (isPreparingAlertShare) return
        val journey = currentJourney
        if (journey?.status != "active") return
        if (!hasLocationPermission()) {
            continueAlertWithLastLocation(alertType)
            return
        }
        if (!areLocationServicesEnabled()) {
            continueAlertWithLastLocation(alertType)
            return
        }

        setAlertShareInProgress(true)
        locationProgress.visibility = android.view.View.VISIBLE
        getLocationButton.isEnabled = false
        currentLocationMessage.text = getString(R.string.location_fetching)
        val priority = if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

        val locationClient = LocationServices.getFusedLocationProviderClient(this)
        locationClient.getCurrentLocation(priority, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val link = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                    rememberLocationLink(link)
                    currentLocationLink = link
                    currentLocationMessage.text = getString(R.string.location_retrieved)
                    locationCoordinates.text = getString(
                        R.string.location_coordinates,
                        String.format(Locale.US, "%.6f", location.latitude),
                        String.format(Locale.US, "%.6f", location.longitude)
                    )
                    locationCoordinates.visibility = android.view.View.VISIBLE
                    openMapsButton.visibility = android.view.View.VISIBLE
                    locationProgress.visibility = android.view.View.GONE
                    getLocationButton.isEnabled = true
                    loadContactsAndShare(alertType, link, isLastLocation = false)
                } else {
                    // A cached fused location may still be available when a new fix is not.
                    tryLastFusedLocation(locationClient, journey, alertType)
                }
            }
            .addOnFailureListener {
                tryLastFusedLocation(locationClient, journey, alertType)
            }
    }

    private fun tryLastFusedLocation(
        locationClient: com.google.android.gms.location.FusedLocationProviderClient,
        journey: Journey,
        alertType: AlertType
    ) {
        locationClient.lastLocation
            .addOnSuccessListener { lastLocation ->
                val lastLink = if (lastLocation != null) {
                    "https://maps.google.com/?q=${lastLocation.latitude},${lastLocation.longitude}"
                        .also { rememberLocationLink(it) }
                } else {
                    getLastKnownLocationLink(journey)
                }
                continueWithLocationResult(alertType, lastLink, isLastLocation = true)
            }
            .addOnFailureListener {
                continueWithLocationResult(
                    alertType,
                    getLastKnownLocationLink(journey),
                    isLastLocation = true
                )
            }
    }

    private fun continueAlertWithLastLocation(alertType: AlertType) {
        if (isPreparingAlertShare) return
        val journey = currentJourney
        if (journey?.status != "active") return
        setAlertShareInProgress(true)
        val link = getLastKnownLocationLink(journey)
        if (link != null) {
            loadContactsAndShare(alertType, link, isLastLocation = true)
        } else {
            askToShareWithoutLocation(alertType)
        }
    }

    private fun continueWithLocationResult(
        alertType: AlertType,
        locationLink: String?,
        isLastLocation: Boolean
    ) {
        locationProgress.visibility = android.view.View.GONE
        getLocationButton.isEnabled = true
        if (locationLink == null) {
            askToShareWithoutLocation(alertType)
        } else {
            currentLocationMessage.text = getString(R.string.location_last_used)
            loadContactsAndShare(alertType, locationLink, isLastLocation)
        }
    }

    private fun askToShareWithoutLocation(alertType: AlertType) {
        locationProgress.visibility = android.view.View.GONE
        getLocationButton.isEnabled = true
        currentLocationMessage.text = getString(R.string.location_unavailable)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.location_unavailable_title))
            .setMessage(getString(R.string.location_missing_for_alert))
            .setNegativeButton(getString(R.string.sos_dialog_cancel)) { _, _ ->
                setAlertShareInProgress(false)
            }
            .setPositiveButton(getString(R.string.btn_share_without_location)) { _, _ ->
                loadContactsAndShare(alertType, null, isLastLocation = false)
            }
            .show()
    }

    private fun setAlertShareInProgress(inProgress: Boolean) {
        isPreparingAlertShare = inProgress
        shareLowBatteryButton.isEnabled = !inProgress
        safetyCheckButton.isEnabled = !inProgress
    }

    private fun getLastKnownLocationLink(journey: Journey): String? =
        lastSuccessfulLocationLink ?: getSavedLocationLink(journey.documentId)

    /** Reads the same per-user contacts used by the existing SOS feature. */
    private fun loadContactsAndShare(
        alertType: AlertType,
        locationLink: String?,
        isLastLocation: Boolean
    ) {
        val userId = firebaseAuth.currentUser?.uid
        if (userId == null) {
            setAlertShareInProgress(false)
            Snackbar.make(
                journeyActionButton,
                getString(R.string.alert_sign_in_required),
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        firestore.collection("users")
            .document(userId)
            .collection("contacts")
            .get()
            .addOnSuccessListener { snapshot ->
                val contacts = snapshot.documents.mapNotNull { document ->
                    val name = document.getString("name")?.trim().orEmpty()
                    val phone = document.getString("phone")?.trim().orEmpty()
                    if (name.isBlank() || phone.isBlank()) null
                    else if (document.getBoolean("isPrimary") == true) {
                        getString(R.string.primary_contact_message_entry, name, phone)
                    } else "$name — $phone"
                }
                if (contacts.isEmpty()) {
                    setAlertShareInProgress(false)
                    showNoEmergencyContactsDialog()
                    return@addOnSuccessListener
                }

                val journey = currentJourney
                if (journey?.status != "active") {
                    setAlertShareInProgress(false)
                    return@addOnSuccessListener
                }
                val locationText = if (locationLink == null) {
                    getString(R.string.alert_no_location)
                } else if (isLastLocation) {
                    getString(R.string.alert_last_location, locationLink)
                } else {
                    getString(R.string.alert_current_location, locationLink)
                }
                val contactsText = contacts.joinToString(separator = "\n") { "• $it" }

                val message = when (alertType) {
                    AlertType.LOW_BATTERY -> {
                        val batteryPercent = latestBatteryPercent.takeIf { it >= 0 }
                            ?: batteryPreferences().getInt(PREF_LAST_BATTERY_PERCENT, LOW_BATTERY_LIMIT)
                        getString(R.string.low_battery_alert_message, batteryPercent, journey.from, journey.to, locationText)
                    }
                    AlertType.SAFETY_HELP -> getString(R.string.safety_help_alert_message, journey.from, journey.to, locationText)
                    AlertType.ARRIVAL_MISSED -> getString(R.string.arrival_missed_share_message, journey.from, journey.to, locationText)
                } + getString(R.string.alert_contacts_heading, contactsText)

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                }
                setAlertShareInProgress(false)
                try {
                    automaticHelpShareLaunched = pendingAutomaticHelpResume
                    emergencyShareLauncher.launch(
                        Intent.createChooser(sendIntent, getString(R.string.alert_share_chooser_title))
                    )
                } catch (_: ActivityNotFoundException) {
                    automaticHelpShareLaunched = false
                    Snackbar.make(
                        journeyActionButton,
                        getString(R.string.alert_no_sharing_app),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
            .addOnFailureListener { exception ->
                setAlertShareInProgress(false)
                val detail = exception.localizedMessage?.takeIf { it.isNotBlank() }
                Snackbar.make(
                    journeyActionButton,
                    detail ?: getString(R.string.alert_contacts_load_failed),
                    Snackbar.LENGTH_LONG
                ).show()
            }
    }

    private fun showNoEmergencyContactsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sos_no_contacts_title))
            .setMessage(getString(R.string.sos_no_contacts_message))
            .setPositiveButton(getString(R.string.sos_no_contacts_action)) { _, _ ->
                startActivity(Intent(this, ContactsActivity::class.java))
            }
            .setNegativeButton(getString(R.string.sos_dialog_cancel), null)
            .show()
    }

    private fun formatTimestamp(timestamp: Timestamp?): String {
        if (timestamp == null) return "—"
        return SimpleDateFormat("EEE, dd MMM yyyy, hh:mm a", Locale.getDefault())
            .format(timestamp.toDate())
    }

    /** Asks for confirmation before starting or ending the selected journey. */
    private fun confirmJourneyAction() {
        val journey = currentJourney ?: return
        if (journey.status == "planned") {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.journey_start_confirmation_title))
                .setMessage(getString(R.string.journey_start_confirmation_message, journey.to))
                .setNegativeButton(getString(R.string.sos_dialog_cancel), null)
                .setPositiveButton(getString(R.string.btn_start_journey)) { _, _ ->
                    startPlannedJourney(journey)
                }
                .show()
        } else if (journey.status == "active") {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.btn_end_journey))
                .setMessage(getString(R.string.journey_end_confirmation_message))
                .setNegativeButton(getString(R.string.sos_dialog_cancel), null)
                .setPositiveButton(getString(R.string.btn_end_journey)) { _, _ ->
                    updateJourneyStatus(journey, "completed")
                }
                .show()
        }
    }

    /** Checks for an active journey before updating this planned document. */
    private fun startPlannedJourney(journey: Journey) {
        val userId = firebaseAuth.currentUser?.uid ?: return
        setUpdating(true)
        firestore.collection("users")
            .document(userId)
            .collection("journeys")
            .whereEqualTo("status", "active")
            .get()
            .addOnSuccessListener { activeJourneys ->
                val otherActiveJourneyExists = activeJourneys.documents.any {
                    it.id != journey.documentId
                }
                if (otherActiveJourneyExists) {
                    setUpdating(false)
                    Snackbar.make(
                        journeyActionButton,
                        getString(R.string.journey_already_active),
                        Snackbar.LENGTH_LONG
                    ).show()
                    loadLatestJourney()
                } else {
                    setUpdating(false)
                    showSafetyReadiness(journey)
                }
            }
            .addOnFailureListener { exception ->
                setUpdating(false)
                showUpdateError(exception)
            }
    }

    private fun showSafetyReadiness(journey: Journey) {
        pendingReadinessJourney = journey
        if (isCheckingReadiness || readinessDialog?.isShowing == true) return
        isCheckingReadiness = true
        setUpdating(true)
        checkEmergencyContacts { hasContact, error ->
            isCheckingReadiness = false
            setUpdating(false)
            if (firebaseAuth.currentUser?.uid == null || pendingReadinessJourney?.documentId != journey.documentId) return@checkEmergencyContacts
            displaySafetyReadiness(journey, buildReadinessChecks(journey, hasContact, error != null))
        }
    }

    private fun checkEmergencyContacts(result: (Boolean?, Exception?) -> Unit) {
        val userId = firebaseAuth.currentUser?.uid
        if (userId == null) {
            result(null, IllegalStateException("No signed-in user"))
            return
        }
        firestore.collection("users").document(userId).collection("contacts")
            .get()
            .addOnSuccessListener { snapshot ->
                val hasUsableContact = snapshot.documents.any { document ->
                    !document.getString("name").isNullOrBlank() && !document.getString("phone").isNullOrBlank()
                }
                result(hasUsableContact, null)
            }
            .addOnFailureListener { exception -> result(null, exception) }
    }

    private fun buildReadinessChecks(
        journey: Journey,
        hasContact: Boolean?,
        contactsCouldNotBeChecked: Boolean
    ): List<ReadinessCheck> {
        val contactCheck = when (hasContact) {
            true -> ReadinessCheck(
                getString(R.string.readiness_contacts_title),
                getString(R.string.readiness_contacts_ready),
                ReadinessLevel.READY
            )
            false -> ReadinessCheck(
                getString(R.string.readiness_contacts_title),
                getString(R.string.readiness_contacts_missing),
                ReadinessLevel.REQUIRED,
                getString(R.string.readiness_add_contacts),
                ReadinessAction.CONTACTS
            )
            null -> ReadinessCheck(
                getString(R.string.readiness_contacts_title),
                if (contactsCouldNotBeChecked) getString(R.string.readiness_contacts_unavailable) else getString(R.string.readiness_contacts_missing),
                ReadinessLevel.REQUIRED,
                if (contactsCouldNotBeChecked) getString(R.string.readiness_retry) else getString(R.string.readiness_add_contacts),
                if (contactsCouldNotBeChecked) ReadinessAction.RETRY_CONTACTS else ReadinessAction.CONTACTS
            )
        }

        val locationGranted = hasLocationPermission()
        val notificationPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled() && notificationPermissionGranted
        val intervalValid = journey.safetyCheckIntervalMinutes in listOf(5, 10, 15)
        val exactAlarmAvailable = SafetyReminderScheduler.canScheduleExactAlarms(this)
        val locationServicesEnabled = areLocationServicesEnabled()

        val notificationAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionGranted) {
            getString(R.string.readiness_allow_notifications)
        } else {
            getString(R.string.readiness_open_notification_settings)
        }

        return listOf(
            contactCheck,
            ReadinessCheck(
                getString(R.string.readiness_location_permission_title),
                if (locationGranted) getString(R.string.readiness_location_permission_ready) else getString(R.string.readiness_location_permission_required),
                if (locationGranted) ReadinessLevel.READY else ReadinessLevel.REQUIRED,
                if (locationGranted) null else getString(R.string.readiness_grant_permission),
                if (locationGranted) null else ReadinessAction.LOCATION_PERMISSION
            ),
            ReadinessCheck(
                getString(R.string.readiness_notifications_title),
                if (notificationsEnabled) getString(R.string.readiness_notifications_ready) else getString(R.string.readiness_notifications_required),
                if (notificationsEnabled) ReadinessLevel.READY else ReadinessLevel.REQUIRED,
                if (notificationsEnabled) null else notificationAction,
                if (notificationsEnabled) null else ReadinessAction.NOTIFICATIONS
            ),
            ReadinessCheck(
                getString(R.string.readiness_interval_title),
                if (intervalValid) getString(R.string.readiness_interval_ready, journey.safetyCheckIntervalMinutes)
                else getString(R.string.readiness_interval_invalid),
                if (intervalValid) ReadinessLevel.READY else ReadinessLevel.REQUIRED
            ),
            ReadinessCheck(
                getString(R.string.readiness_exact_alarm_title),
                if (exactAlarmAvailable) getString(R.string.readiness_exact_alarm_ready) else getString(R.string.readiness_exact_alarm_optional),
                if (exactAlarmAvailable) ReadinessLevel.READY else ReadinessLevel.OPTIONAL,
                if (exactAlarmAvailable) null else getString(R.string.readiness_open_alarm_settings),
                if (exactAlarmAvailable) null else ReadinessAction.EXACT_ALARMS
            ),
            ReadinessCheck(
                getString(R.string.readiness_location_services_title),
                if (locationServicesEnabled) getString(R.string.readiness_location_services_ready) else getString(R.string.readiness_location_services_optional),
                if (locationServicesEnabled) ReadinessLevel.READY else ReadinessLevel.OPTIONAL,
                if (locationServicesEnabled) null else getString(R.string.readiness_open_location_settings),
                if (locationServicesEnabled) null else ReadinessAction.LOCATION_SETTINGS
            )
        )
    }

    private fun displaySafetyReadiness(journey: Journey, checks: List<ReadinessCheck>) {
        val hasRequiredIssue = checks.any { it.level == ReadinessLevel.REQUIRED }
        val hasOptionalIssue = checks.any { it.level == ReadinessLevel.OPTIONAL }
        val finalTitle = when {
            hasRequiredIssue -> getString(R.string.readiness_not_ready)
            hasOptionalIssue -> getString(R.string.readiness_almost_ready)
            else -> getString(R.string.readiness_ready)
        }
        val finalDetails = when {
            hasRequiredIssue -> getString(R.string.readiness_not_ready_details)
            hasOptionalIssue -> getString(R.string.readiness_almost_ready_details)
            else -> getString(R.string.readiness_ready_details)
        }
        val finalColor = when {
            hasRequiredIssue -> ContextCompat.getColor(this, R.color.colorError)
            hasOptionalIssue -> ContextCompat.getColor(this, R.color.colorWarning)
            else -> ContextCompat.getColor(this, R.color.colorSuccess)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val summary = TextView(this).apply {
            text = "$finalTitle\n$finalDetails"
            textSize = 15f
            setTextColor(finalColor)
            setPadding(dp(8), dp(10), dp(8), dp(14))
        }
        content.addView(summary)
        checks.forEach { check -> content.addView(createReadinessRow(check, journey)) }

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(content)
        }
        val maxHeight = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        scroll.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            maxHeight
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.readiness_title))
            .setView(scroll)
            .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                pendingReadinessJourney = null
                readinessDialog = null
            }
            .setPositiveButton(getString(R.string.btn_start_journey), null)
            .create()
        dialog.setOnCancelListener {
            pendingReadinessJourney = null
            readinessDialog = null
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = !hasRequiredIssue
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (!hasRequiredIssue) {
                    dialog.dismiss()
                    readinessDialog = null
                    recheckReadinessBeforeStarting(journey)
                }
            }
        }
        readinessDialog = dialog
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = !hasRequiredIssue
    }

    private fun createReadinessRow(check: ReadinessCheck, journey: Journey): View {
        val color = when (check.level) {
            ReadinessLevel.READY -> ContextCompat.getColor(this, R.color.colorSuccess)
            ReadinessLevel.REQUIRED -> ContextCompat.getColor(this, R.color.colorError)
            ReadinessLevel.OPTIONAL -> ContextCompat.getColor(this, R.color.colorWarning)
        }
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@JourneyActivity, R.color.profileLightBlue)
            setCardBackgroundColor(ContextCompat.getColor(this@JourneyActivity, R.color.colorSurface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val indicator = TextView(this).apply {
            text = when (check.level) {
                ReadinessLevel.READY -> "✓"
                ReadinessLevel.REQUIRED -> "!"
                ReadinessLevel.OPTIONAL -> "•"
            }
            textSize = 17f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) }
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = check.title
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@JourneyActivity, R.color.profileNavy))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        textColumn.addView(TextView(this).apply {
            text = check.details
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@JourneyActivity, R.color.colorTextSecondary))
            setPadding(0, dp(3), 0, 0)
        })
        textColumn.addView(TextView(this).apply {
            text = when (check.level) {
                ReadinessLevel.READY -> getString(R.string.readiness_status_ready)
                ReadinessLevel.REQUIRED -> getString(R.string.readiness_status_required)
                ReadinessLevel.OPTIONAL -> getString(R.string.readiness_status_optional)
            }
            textSize = 11f
            setTextColor(color)
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(indicator)
        row.addView(textColumn)
        if (check.action != null && check.actionLabel != null) {
            val actionButton = MaterialButton(this).apply {
                text = check.actionLabel
                textSize = 11f
                minHeight = dp(40)
                minimumHeight = dp(40)
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    readinessDialog?.dismiss()
                    readinessDialog = null
                    performReadinessAction(journey, check.action)
                }
            }
            row.addView(actionButton)
        }
        card.addView(row)
        return card
    }

    private fun performReadinessAction(journey: Journey, action: ReadinessAction) {
        pendingReadinessJourney = journey
        when (action) {
            ReadinessAction.CONTACTS -> startActivity(Intent(this, ContactsActivity::class.java))
            ReadinessAction.RETRY_CONTACTS -> showSafetyReadiness(journey)
            ReadinessAction.LOCATION_PERMISSION -> locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            )
            ReadinessAction.NOTIFICATIONS -> {
                val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (permissionMissing) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openReadinessSettings(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
            }
            ReadinessAction.EXACT_ALARMS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    openReadinessSettings(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                } else {
                    showSafetyReadiness(journey)
                }
            }
            ReadinessAction.LOCATION_SETTINGS -> openReadinessSettings(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
        }
    }

    private fun openReadinessSettings(primary: Intent, fallback: Intent) {
        try {
            startActivity(primary)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(fallback)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.readiness_settings_unavailable), Toast.LENGTH_LONG).show()
                pendingReadinessJourney?.let { showSafetyReadiness(it) }
            }
        }
    }

    private fun recheckReadinessBeforeStarting(journey: Journey) {
        pendingReadinessJourney = journey
        isCheckingReadiness = true
        setUpdating(true)
        checkEmergencyContacts { hasContact, error ->
            if (firebaseAuth.currentUser?.uid == null || pendingReadinessJourney?.documentId != journey.documentId) {
                isCheckingReadiness = false
                setUpdating(false)
                return@checkEmergencyContacts
            }
            val checks = buildReadinessChecks(journey, hasContact, error != null)
            if (checks.any { it.level == ReadinessLevel.REQUIRED }) {
                isCheckingReadiness = false
                setUpdating(false)
                displaySafetyReadiness(journey, checks)
                return@checkEmergencyContacts
            }

            val userId = firebaseAuth.currentUser?.uid ?: return@checkEmergencyContacts
            firestore.collection("users").document(userId).collection("journeys")
                .whereEqualTo("status", "active")
                .get()
                .addOnSuccessListener { activeJourneys ->
                    isCheckingReadiness = false
                    setUpdating(false)
                    if (activeJourneys.documents.any { it.id != journey.documentId }) {
                        pendingReadinessJourney = null
                        Snackbar.make(journeyActionButton, getString(R.string.journey_already_active), Snackbar.LENGTH_LONG).show()
                        loadLatestJourney()
                        return@addOnSuccessListener
                    }
                    val hasWarnings = checks.any { it.level == ReadinessLevel.OPTIONAL }
                    pendingReadinessJourney = null
                    if (hasWarnings) {
                        Toast.makeText(this, getString(R.string.readiness_starting_with_warnings), Toast.LENGTH_LONG).show()
                    }
                    updateJourneyStatus(journey, "active", requestAlreadyInProgress = true)
                }
                .addOnFailureListener { exception ->
                    isCheckingReadiness = false
                    pendingReadinessJourney = null
                    setUpdating(false)
                    showUpdateError(exception)
                }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Updates the selected document in place with a server-generated time. */
    private fun updateJourneyStatus(
        journey: Journey,
        newStatus: String,
        requestAlreadyInProgress: Boolean = false
    ) {
        if (isUpdating && !requestAlreadyInProgress) return
        val userId = firebaseAuth.currentUser?.uid ?: return
        setUpdating(true)

        val updates = if (newStatus == "active") {
            mapOf(
                "status" to "active",
                "startedAt" to FieldValue.serverTimestamp()
            )
        } else {
            mapOf(
                "status" to "completed",
                "completedAt" to FieldValue.serverTimestamp()
            )
        }

        firestore.collection("users")
            .document(userId)
            .collection("journeys")
            .document(journey.documentId)
            .update(updates)
            .addOnSuccessListener {
                setUpdating(false)
                currentJourney = journey.copy(
                    status = newStatus,
                    startedAt = if (newStatus == "active") Timestamp.now() else journey.startedAt,
                    completedAt = if (newStatus == "completed") Timestamp.now() else journey.completedAt
                )
                if (newStatus == "active") {
                    resetBatteryAlertForNewJourney()
                    SafetyReminderScheduler.ensureMonitoring(
                        this,
                        userId,
                        journey.documentId,
                        journey.safetyCheckIntervalMinutes
                    )
                }
                if (newStatus == "completed") {
                    ExpectedArrivalScheduler.cancelForJourney(this, userId, journey.documentId)
                    SafetyReminderScheduler.cancelForJourney(this, userId, journey.documentId)
                }
                syncBatteryMonitoring()
                val message = if (newStatus == "active") {
                    getString(R.string.journey_started_success)
                } else {
                    getString(R.string.journey_completed_success)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                loadLatestJourney()
            }
            .addOnFailureListener { exception ->
                setUpdating(false)
                showUpdateError(exception)
            }
    }

    private fun setUpdating(updating: Boolean) {
        isUpdating = updating
        progressBar.visibility = if (updating) android.view.View.VISIBLE else android.view.View.GONE
        journeyActionButton.isEnabled = !updating
        if (updating) {
            journeyStatusText.text = getString(R.string.journey_update_in_progress)
            journeyStatusText.visibility = android.view.View.VISIBLE
        }
    }

    private fun showUpdateError(exception: Exception) {
        val detail = exception.localizedMessage?.takeIf { it.isNotBlank() }
        val message = detail ?: getString(R.string.error_journey_complete_failed)
        Snackbar.make(journeyActionButton, message, Snackbar.LENGTH_LONG).show()
        currentJourney?.let { showJourney(it) }
    }

    private fun showLoadError(message: String) {
        currentJourney = null
        progressBar.visibility = android.view.View.GONE
        activeJourneyCard.visibility = android.view.View.GONE
        journeyFormCard.visibility = android.view.View.GONE
        lowBatteryLayout.visibility = android.view.View.GONE
        safetyCheckButton.visibility = android.view.View.GONE
        journeyStatusText.text = message
        journeyStatusText.visibility = android.view.View.VISIBLE
        syncBatteryMonitoring()
    }

    /** Opens a calendar, then a clock, and displays both selections together. */
    private fun chooseArrivalDate() {
        val now = Calendar.getInstance()
        val selected = arrivalCalendar ?: (now.clone() as Calendar)

        DatePickerDialog(
            this,
            { _, year, month, day ->
                val chosenDate = (arrivalCalendar ?: (Calendar.getInstance())).clone() as Calendar
                chosenDate.set(Calendar.YEAR, year)
                chosenDate.set(Calendar.MONTH, month)
                chosenDate.set(Calendar.DAY_OF_MONTH, day)

                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        chosenDate.set(Calendar.HOUR_OF_DAY, hour)
                        chosenDate.set(Calendar.MINUTE, minute)
                        chosenDate.set(Calendar.SECOND, 0)
                        arrivalCalendar = chosenDate
                        editArrival.setText(
                            SimpleDateFormat("EEE, dd MMM yyyy 'at' hh:mm a", Locale.getDefault())
                                .format(chosenDate.time)
                        )
                        arrivalLayout.error = null
                    },
                    selected.get(Calendar.HOUR_OF_DAY),
                    selected.get(Calendar.MINUTE),
                    false
                ).show()
            },
            selected.get(Calendar.YEAR),
            selected.get(Calendar.MONTH),
            selected.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = now.timeInMillis
        }.show()
    }

    private fun selectedSafetyCheckIntervalMinutes(): Int? =
        dropdownSafetyCheckInterval.text.toString()
            .substringBefore(" ")
            .toIntOrNull()
            ?.takeIf { it == 5 || it == 10 || it == 15 }

    /** Checks required fields and gives feedback without saving or starting services. */
    private fun validateForm() {
        fromLayout.error = if (editFrom.text.isNullOrBlank()) getString(R.string.error_journey_from_empty) else null
        toLayout.error = if (editTo.text.isNullOrBlank()) getString(R.string.error_journey_to_empty) else null
        modeLayout.error = if (dropdownTravelMode.text.isNullOrBlank()) getString(R.string.error_journey_mode_empty) else null
        arrivalLayout.error = if (arrivalCalendar == null) getString(R.string.error_journey_time_empty) else null

        val phone = editCompanionPhone.text?.toString()?.filter { it.isDigit() }.orEmpty()
        val phoneIsValid = editCompanionPhone.text.isNullOrBlank() || phone.length >= 7
        editCompanionPhone.error = if (phoneIsValid) null else getString(R.string.error_companion_phone_invalid)
        val selectedInterval = selectedSafetyCheckIntervalMinutes()
        dropdownSafetyCheckInterval.error = if (selectedInterval == null) getString(R.string.error_safety_interval_invalid) else null

        if (editFrom.text.isNullOrBlank()) {
            editFrom.requestFocus()
            return
        }
        if (editTo.text.isNullOrBlank()) {
            editTo.requestFocus()
            return
        }
        if (dropdownTravelMode.text.isNullOrBlank() || arrivalCalendar == null || !phoneIsValid || selectedInterval == null) return

        // Collect the form values in the existing model before saving.
        val journeyDetails = Journey(
            from = editFrom.text?.toString()?.trim().orEmpty(),
            to = editTo.text?.toString()?.trim().orEmpty(),
            travelMode = dropdownTravelMode.text.toString(),
            safetyCheckIntervalMinutes = selectedInterval,
            expectedArrival = Timestamp(arrivalCalendar!!.time),
            companionName = editCompanionName.text?.toString()?.trim().orEmpty(),
            companionPhone = editCompanionPhone.text?.toString()?.trim().orEmpty(),
            vehicleNumber = editVehicleNumber.text?.toString()?.trim().orEmpty()
        )

        saveJourney(journeyDetails)
    }

    /** Saves one planned journey under the currently signed-in user's account. */
    private fun saveJourney(journey: Journey) {
        if (isSaving) return

        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            Snackbar.make(
                startJourneyButton,
                "Please sign in again before saving your journey.",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        isSaving = true
        startJourneyButton.isEnabled = false
        progressBar.visibility = android.view.View.VISIBLE

        // Map only the requested fields; documentId is the Firestore document ID.
        val journeyData = mapOf<String, Any?>(
            "from" to journey.from,
            "to" to journey.to,
            "travelMode" to journey.travelMode,
            "safetyCheckIntervalMinutes" to journey.safetyCheckIntervalMinutes,
            "expectedArrival" to journey.expectedArrival,
            "companionName" to journey.companionName,
            "companionPhone" to journey.companionPhone,
            "vehicleNumber" to journey.vehicleNumber,
            "status" to "planned",
            "startedAt" to null,
            "completedAt" to null
        )

        FirebaseFirestore.getInstance()
            .collection("users")
            .document(userId)
            .collection("journeys")
            .add(journeyData)
            .addOnSuccessListener {
                progressBar.visibility = android.view.View.GONE
                Toast.makeText(
                    this,
                    "Journey saved successfully.",
                    Toast.LENGTH_LONG
                ).show()
                // Returning to the dashboard also prevents an accidental resave.
                finish()
            }
            .addOnFailureListener { exception ->
                isSaving = false
                startJourneyButton.isEnabled = true
                progressBar.visibility = android.view.View.GONE

                val detail = exception.localizedMessage
                    ?.takeIf { it.isNotBlank() }
                val message = if (detail == null) {
                    getString(R.string.error_journey_save_failed)
                } else {
                    "${getString(R.string.error_journey_save_failed)} ($detail)"
                }
                Snackbar.make(startJourneyButton, message, Snackbar.LENGTH_LONG).show()
            }
    }

    companion object {
        const val EXTRA_CHECK_IN_ID = "extra_safety_check_in_id"
        const val EXTRA_JOURNEY_ID = "extra_safety_journey_id"
        const val EXTRA_EMERGENCY_ALERT = "safety_emergency_alert"
        const val EXTRA_ARRIVAL_MISSED_JOURNEY_ID = "expected_arrival_missed_journey_id"
        const val EXTRA_REFRESH_JOURNEY = "refresh_journey_after_arrival_action"
        private const val TAG = "JourneyActivity"
        const val DEFAULT_SAFETY_CHECK_INTERVAL_MINUTES = 15
        const val SAFETY_CHECK_TIMEOUT_MS = 60_000L
        const val LOW_BATTERY_LIMIT = 15
        const val PREFERENCES_BATTERY_ALERTS = "journey_battery_alerts"
        const val PREF_ALERTED_JOURNEY_ID = "alerted_journey_id"
        const val PREF_VISIBLE_WARNING_JOURNEY_ID = "visible_warning_journey_id"
        const val PREF_LAST_BATTERY_PERCENT = "last_battery_percent"
        const val PREFERENCES_LAST_LOCATION = "journey_last_location"
        const val LAST_LOCATION_KEY_PREFIX = "location_link_"
    }
}
