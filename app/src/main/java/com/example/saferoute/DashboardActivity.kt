package com.example.saferoute

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * DashboardActivity — SafeRoute Home Screen.
 *
 * Features implemented here:
 *  1. Loads the logged-in user's name from Firestore → shows "Hello, [Name]".
 *  2. SOS button — full location sharing workflow (Step 6):
 *       a. Confirmation dialog
 *       b. Location permission check + runtime request
 *       c. GPS coordinates via FusedLocationProviderClient
 *       d. Google Maps link generation
 *       e. Emergency contacts loaded from Firestore
 *       f. Emergency message built with name, contacts, location link
 *       g. Android share sheet opened — user completes the send action
 *  3. Emergency Contacts card → ContactsActivity
 *  4. Profile card → placeholder Snackbar
 *  5. Logout → clears session, returns to Login
 *
 * IMPORTANT: The app never claims a message was sent. It opens the share
 * sheet — the user must select an app and complete the action themselves.
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    // Views
    private lateinit var tvUserName: TextView
    private lateinit var tvSosStatus: TextView
    private lateinit var btnSos: MaterialButton
    private lateinit var cardContacts: MaterialCardView
    private lateinit var cardJourney: MaterialCardView
    private lateinit var cardProfile: MaterialCardView
    private lateinit var tvLogout: TextView

    /**
     * Runtime location permission launcher.
     * Registered as a property so it is ready before onCreate.
     * Called after the user responds to the permission dialog.
     */
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                // Permission just granted — proceed with location fetch
                fetchLocationAndShare()
            } else {
                // User denied — explain clearly and reset status
                setStatus(getString(R.string.sos_ready))
                Snackbar.make(
                    btnSos,
                    getString(R.string.sos_permission_denied),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        firebaseAuth = FirebaseAuth.getInstance()
        firestore    = FirebaseFirestore.getInstance()

        // Bind views
        tvUserName   = findViewById(R.id.tvUserName)
        tvSosStatus  = findViewById(R.id.tvSosStatus)
        btnSos       = findViewById(R.id.btnSos)
        cardContacts = findViewById(R.id.cardContacts)
        cardJourney = findViewById(R.id.cardJourney)
        cardProfile  = findViewById(R.id.cardProfile)
        tvLogout     = findViewById(R.id.tvLogout)

        loadUserName()

        // ── SOS Button ────────────────────────────────────────────────────
        btnSos.setOnClickListener {
            showSosConfirmationDialog()
        }

        // ── Navigation Cards ──────────────────────────────────────────────
        cardContacts.setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }

        // Open the local journey setup form.
        cardJourney.setOnClickListener {
            startActivity(Intent(this, JourneyActivity::class.java))
        }

        cardProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // ── Logout ────────────────────────────────────────────────────────
        tvLogout.setOnClickListener { logout() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SOS WORKFLOW
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Step 1 — Show confirmation dialog.
     *
     * We ask before doing anything so the user cannot trigger SOS by accident.
     * The message explains that a share sheet will open and that the user
     * must complete the send action themselves.
     */
    private fun showSosConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sos_dialog_title))
            .setMessage(getString(R.string.sos_dialog_message))
            .setPositiveButton(getString(R.string.sos_dialog_confirm)) { _, _ ->
                checkLocationPermissionAndProceed()
            }
            .setNegativeButton(getString(R.string.sos_dialog_cancel), null)
            .show()
    }

    /**
     * Step 2 — Check location permission.
     *
     * We check ACCESS_FINE_LOCATION (precise GPS).
     * If already granted → proceed immediately.
     * If not → launch the system permission dialog.
     *
     * We do NOT check shouldShowRequestPermissionRationale here because
     * for an emergency feature, we always want to ask — the user should
     * understand why it is needed from the confirmation dialog they just saw.
     */
    private fun checkLocationPermissionAndProceed() {
        val permission = Manifest.permission.ACCESS_FINE_LOCATION

        if (ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            fetchLocationAndShare()
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    /**
     * Step 3 — Get current GPS location using FusedLocationProviderClient.
     *
     * FusedLocationProviderClient is the recommended Android API for getting
     * location. It is more battery-efficient than using GPS directly.
     *
     * Priority.PRIORITY_HIGH_ACCURACY requests GPS-level precision.
     * getCurrentLocation() makes a single one-shot request (not continuous).
     *
     * The @SuppressLint is needed because the permission check is done in
     * checkLocationPermissionAndProceed() just before this call — the linter
     * cannot see that the check already happened.
     */
    @androidx.annotation.RequiresPermission(
        anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
    )
    private fun fetchLocationAndShare() {
        setStatus(getString(R.string.sos_getting_location))
        btnSos.isEnabled = false

        val fusedClient = LocationServices.getFusedLocationProviderClient(this)

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L   // 5 second interval (single request — not continuous)
        ).build()

        fusedClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            null    // CancellationToken — null means no cancellation
        )
        .addOnSuccessListener { location ->
            btnSos.isEnabled = true

            if (location == null) {
                // Location is null when GPS is off or no fix was obtained
                setStatus(getString(R.string.sos_ready))
                Snackbar.make(
                    btnSos,
                    getString(R.string.sos_location_failed),
                    Snackbar.LENGTH_LONG
                ).show()
                return@addOnSuccessListener
            }

            // Build a Google Maps link from the coordinates
            // Format: https://maps.google.com/?q=lat,lng
            val mapsLink = "https://maps.google.com/?q=${location.latitude},${location.longitude}"

            // Move to Step 4 — load contacts, then share
            loadContactsAndShare(mapsLink)
        }
        .addOnFailureListener { exception ->
            btnSos.isEnabled = true
            setStatus(getString(R.string.sos_ready))
            Snackbar.make(
                btnSos,
                getString(R.string.sos_location_failed) + " (${exception.message})",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Step 4 — Load emergency contacts from Firestore.
     *
     * We need the contacts to include their names and phone numbers
     * in the emergency message. If none exist, we stop and prompt the
     * user to add contacts first.
     *
     * Path: users/{uid}/contacts
     */
    private fun loadContactsAndShare(mapsLink: String) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        setStatus(getString(R.string.sos_loading_contacts))

        firestore.collection("users")
            .document(uid)
            .collection("contacts")
            .get()
            .addOnSuccessListener { snapshot ->
                val contacts = snapshot.documents.mapNotNull { doc ->
                    val name  = doc.getString("name")  ?: return@mapNotNull null
                    val phone = doc.getString("phone") ?: return@mapNotNull null
                    "$name — $phone"
                }

                if (contacts.isEmpty()) {
                    // No contacts saved — cannot send a meaningful alert
                    setStatus(getString(R.string.sos_ready))
                    showNoContactsDialog()
                    return@addOnSuccessListener
                }

                // Step 5 — build and share the message
                buildAndShareMessage(mapsLink, contacts)
            }
            .addOnFailureListener {
                setStatus(getString(R.string.sos_ready))
                Snackbar.make(
                    btnSos,
                    getString(R.string.error_network),
                    Snackbar.LENGTH_LONG
                ).show()
            }
    }

    /**
     * Step 5 — Build the emergency message and open the share sheet.
     *
     * The message includes:
     *  - The user's name (loaded earlier from Firestore)
     *  - The Google Maps link
     *
     * The share sheet (ACTION_SEND with type text/plain) lets the user
     * choose WhatsApp, SMS, Gmail, Telegram, or any app that handles text.
     *
     * IMPORTANT: We do NOT claim the message was sent. We open the share
     * sheet — the user must tap Send inside the chosen app themselves.
     * The status text and Snackbar explain this explicitly.
     */
    private fun buildAndShareMessage(mapsLink: String, contacts: List<String>) {
        val userName = tvUserName.text.toString().ifEmpty {
            firebaseAuth.currentUser?.email ?: "Unknown"
        }

        // Format the contacts list as a bulleted string
        val contactsText = contacts.joinToString(separator = "\n") { "• $it" }

        // Build the full emergency message
        val message = getString(R.string.sos_share_message, userName, mapsLink) +
                "\n\nEmergency Contacts:\n$contactsText"

        // Open Android share sheet
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }

        startActivity(
            Intent.createChooser(shareIntent, getString(R.string.sos_share_title))
        )

        // Reset status after share sheet opens
        setStatus(getString(R.string.sos_ready))
    }

    /**
     * Shown when the user taps SOS but has no emergency contacts saved.
     * Offers to navigate to ContactsActivity directly.
     */
    private fun showNoContactsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sos_no_contacts_title))
            .setMessage(getString(R.string.sos_no_contacts_message))
            .setPositiveButton(getString(R.string.sos_no_contacts_action)) { _, _ ->
                startActivity(Intent(this, ContactsActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // USER PROFILE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Loads the user's name from Firestore and shows it in the header.
     * Falls back to email if the Firestore document is missing.
     */
    private fun loadUserName() {
        val uid = firebaseAuth.currentUser?.uid ?: run { logout(); return }

        firestore.collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { document ->
                val name = document?.getString("name") ?: ""
                tvUserName.text = if (name.isNotEmpty()) name
                                  else firebaseAuth.currentUser?.email ?: ""
            }
            .addOnFailureListener {
                tvUserName.text = firebaseAuth.currentUser?.email ?: ""
            }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    /** Updates the status text below the SOS button. */
    private fun setStatus(message: String) {
        tvSosStatus.text = message
    }

    /** Signs out and clears back stack back to Login. */
    private fun logout() {
        firebaseAuth.signOut()
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
