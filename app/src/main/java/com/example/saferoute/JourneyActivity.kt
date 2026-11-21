package com.example.saferoute

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    // A null value means the user has not selected a date and time yet.
    private var arrivalCalendar: Calendar? = null

    private enum class AlertType {
        LOW_BATTERY,
        SAFETY_HELP
    }

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
            } else if (granted && currentJourney?.status == "active") {
                retrieveCurrentLocation()
            } else {
                showLocationMessage(getString(R.string.location_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journey)

        firebaseAuth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

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
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        syncBatteryMonitoring()
    }

    override fun onPause() {
        isActivityResumed = false
        stopBatteryMonitoring()
        super.onPause()
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
                    showJourneyForm()
                } else {
                    showJourney(selectedJourney)
                }
            }
            .addOnFailureListener { exception ->
                progressBar.visibility = android.view.View.GONE
                val detail = exception.localizedMessage?.takeIf { it.isNotBlank() }
                val message = detail ?: getString(R.string.error_journey_load_failed)
                showLoadError(message)
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
            "completed" -> getString(R.string.journey_status_completed)
            else -> getString(R.string.journey_status_active)
        }

        findViewById<TextView>(R.id.tvActiveFrom).text = journey.from
        findViewById<TextView>(R.id.tvActiveTo).text = journey.to
        findViewById<TextView>(R.id.tvActiveMode).text = journey.travelMode
        findViewById<TextView>(R.id.tvActiveExpected).text = formatTimestamp(journey.expectedArrival)
        findViewById<TextView>(R.id.tvActiveStartedAt).text =
            journey.startedAt?.let { formatTimestamp(it) } ?: "—"

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
    }

    private fun getSavedLocationLink(journeyId: String): String? =
        getSharedPreferences(PREFERENCES_LAST_LOCATION, MODE_PRIVATE)
            .getString(LAST_LOCATION_KEY_PREFIX + journeyId, null)

    /** Shows the two manual choices; the safe choice never opens an alert share. */
    private fun showSafetyCheckDialog() {
        if (currentJourney?.status != "active") return

        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.safety_check_question))
            .setPositiveButton(getString(R.string.safety_check_safe)) { _, _ ->
                Toast.makeText(
                    this,
                    getString(R.string.safety_check_safe_message),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(getString(R.string.safety_check_help)) { _, _ ->
                beginEmergencyShare(AlertType.SAFETY_HELP)
            }
            .show()
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
                    if (name.isBlank() || phone.isBlank()) null else "$name — $phone"
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

                val message = if (alertType == AlertType.LOW_BATTERY) {
                    val batteryPercent = latestBatteryPercent.takeIf { it >= 0 }
                        ?: batteryPreferences().getInt(PREF_LAST_BATTERY_PERCENT, LOW_BATTERY_LIMIT)
                    getString(
                        R.string.low_battery_alert_message,
                        batteryPercent,
                        journey.from,
                        journey.to,
                        locationText
                    )
                } else {
                    getString(
                        R.string.safety_help_alert_message,
                        journey.from,
                        journey.to,
                        locationText
                    )
                } + getString(R.string.alert_contacts_heading, contactsText)

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                }
                setAlertShareInProgress(false)
                try {
                    startActivity(
                        Intent.createChooser(sendIntent, getString(R.string.alert_share_chooser_title))
                    )
                } catch (_: ActivityNotFoundException) {
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
                    updateJourneyStatus(journey, "active", requestAlreadyInProgress = true)
                }
            }
            .addOnFailureListener { exception ->
                setUpdating(false)
                showUpdateError(exception)
            }
    }

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
                if (newStatus == "active") resetBatteryAlertForNewJourney()
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

    /** Checks required fields and gives feedback without saving or starting services. */
    private fun validateForm() {
        fromLayout.error = if (editFrom.text.isNullOrBlank()) getString(R.string.error_journey_from_empty) else null
        toLayout.error = if (editTo.text.isNullOrBlank()) getString(R.string.error_journey_to_empty) else null
        modeLayout.error = if (dropdownTravelMode.text.isNullOrBlank()) getString(R.string.error_journey_mode_empty) else null
        arrivalLayout.error = if (arrivalCalendar == null) getString(R.string.error_journey_time_empty) else null

        val phone = editCompanionPhone.text?.toString()?.filter { it.isDigit() }.orEmpty()
        val phoneIsValid = editCompanionPhone.text.isNullOrBlank() || phone.length >= 7
        editCompanionPhone.error = if (phoneIsValid) null else getString(R.string.error_companion_phone_invalid)

        if (editFrom.text.isNullOrBlank()) {
            editFrom.requestFocus()
            return
        }
        if (editTo.text.isNullOrBlank()) {
            editTo.requestFocus()
            return
        }
        if (dropdownTravelMode.text.isNullOrBlank() || arrivalCalendar == null || !phoneIsValid) return

        // Collect the form values in the existing model before saving.
        val journeyDetails = Journey(
            from = editFrom.text?.toString()?.trim().orEmpty(),
            to = editTo.text?.toString()?.trim().orEmpty(),
            travelMode = dropdownTravelMode.text.toString(),
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

    private companion object {
        const val LOW_BATTERY_LIMIT = 15
        const val PREFERENCES_BATTERY_ALERTS = "journey_battery_alerts"
        const val PREF_ALERTED_JOURNEY_ID = "alerted_journey_id"
        const val PREF_VISIBLE_WARNING_JOURNEY_ID = "visible_warning_journey_id"
        const val PREF_LAST_BATTERY_PERCENT = "last_battery_percent"
        const val PREFERENCES_LAST_LOCATION = "journey_last_location"
        const val LAST_LOCATION_KEY_PREFIX = "location_link_"
    }
}
