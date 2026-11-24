package com.example.saferoute

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions

/** Displays and edits the authenticated user's profile stored at users/{uid}. */
class ProfileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var root: View
    private lateinit var layoutName: TextInputLayout
    private lateinit var layoutPhone: TextInputLayout
    private lateinit var editName: TextInputEditText
    private lateinit var editEmail: TextInputEditText
    private lateinit var editPhone: TextInputEditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var editButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var detailsCard: MaterialCardView
    private lateinit var editorCard: MaterialCardView
    private lateinit var editActions: View
    private lateinit var headerInitials: TextView
    private lateinit var headerName: TextView
    private lateinit var headerEmail: TextView
    private lateinit var detailName: TextView
    private lateinit var detailEmail: TextView
    private lateinit var detailPhone: TextView

    private var savedName = ""
    private var savedPhone = ""
    private var profileCanBeEdited = false
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        root = findViewById(R.id.profileRoot)
        layoutName = findViewById(R.id.layoutProfileName)
        layoutPhone = findViewById(R.id.layoutProfilePhone)
        editName = findViewById(R.id.editProfileName)
        editEmail = findViewById(R.id.editProfileEmail)
        editPhone = findViewById(R.id.editProfilePhone)
        status = findViewById(R.id.tvProfileStatus)
        progress = findViewById(R.id.profileProgress)
        editButton = findViewById(R.id.btnEditProfile)
        saveButton = findViewById(R.id.btnSaveProfile)
        cancelButton = findViewById(R.id.btnCancelProfile)
        detailsCard = findViewById(R.id.cardProfileDetails)
        editorCard = findViewById(R.id.cardProfileEditor)
        editActions = findViewById(R.id.profileEditActions)
        headerInitials = findViewById(R.id.tvProfileInitials)
        headerName = findViewById(R.id.tvProfileHeaderName)
        headerEmail = findViewById(R.id.tvProfileHeaderEmail)
        detailName = findViewById(R.id.tvProfileName)
        detailEmail = findViewById(R.id.tvProfileEmail)
        detailPhone = findViewById(R.id.tvProfilePhone)

        findViewById<Toolbar>(R.id.profileToolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialCardView>(R.id.cardProfileContacts).setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnProfileLogout).setOnClickListener {
            confirmLogout()
        }
        editButton.setOnClickListener { setEditing(true) }
        cancelButton.setOnClickListener {
            editName.setText(savedName)
            editPhone.setText(savedPhone)
            layoutName.error = null
            layoutPhone.error = null
            status.visibility = View.GONE
            setEditing(false)
        }
        saveButton.setOnClickListener { saveProfile() }

        val user = auth.currentUser
        if (user == null) {
            showStatus(getString(R.string.profile_sign_in_required))
            root.postDelayed({
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }, 900)
        } else {
            editEmail.setText(user.email.orEmpty())
            editEmail.isEnabled = false
            loadProfile(user.uid)
        }
    }

    private fun loadProfile(uid: String) {
        setLoading(true)
        status.visibility = View.GONE
        firestore.collection(USERS_COLLECTION).document(uid).get()
            .addOnSuccessListener { document ->
                val user = auth.currentUser
                if (!document.exists()) {
                    // A Firestore read of a missing document succeeds. Keep the authenticated
                    // session and let the user complete the same UID-based profile document.
                    savedName = user?.displayName.orEmpty()
                    savedPhone = ""
                    editName.setText(savedName)
                    editPhone.setText(savedPhone)
                    editEmail.setText(user?.email.orEmpty())
                    updateProfileSummary(user?.email.orEmpty())
                    profileCanBeEdited = true
                    showStatus(getString(R.string.profile_missing_editable))
                } else {
                    savedName = document.getString("name") ?: user?.displayName.orEmpty()
                    savedPhone = document.getString("phone").orEmpty()
                    editName.setText(savedName)
                    editPhone.setText(savedPhone)
                    editEmail.setText(document.getString("email") ?: user?.email.orEmpty())
                    updateProfileSummary(editEmail.text?.toString().orEmpty())
                    profileCanBeEdited = true
                }
                editEmail.isEnabled = false
                setLoading(false)
            }
            .addOnFailureListener { exception ->
                setLoading(false)
                logFirestoreFailure("Profile read", exception)
                showStatus(profileErrorMessage(exception))
            }
    }

    private fun saveProfile() {
        val user = auth.currentUser
        if (user == null) {
            showStatus(getString(R.string.profile_sign_in_required))
            return
        }
        val name = editName.text?.toString()?.trim().orEmpty()
        val phone = editPhone.text?.toString()?.trim().orEmpty()
        layoutName.error = null
        layoutPhone.error = null
        if (name.isBlank()) {
            layoutName.error = getString(R.string.error_name_empty)
            editName.requestFocus()
            return
        }
        if (phone.isNotBlank() && phone.filter(Char::isDigit).length < 7) {
            layoutPhone.error = getString(R.string.error_contact_phone_invalid)
            editPhone.requestFocus()
            return
        }

        setLoading(true)
        status.visibility = View.GONE
        val profileRef = firestore.collection(USERS_COLLECTION).document(user.uid)
        firestore.runTransaction { transaction ->
            val existing = transaction.get(profileRef)
            if (existing.exists()) {
                // Preserve email, uid, createdAt and any other fields already on the user.
                transaction.update(profileRef, mapOf("name" to name, "phone" to phone))
            } else {
                // Recover the missing registration write using only the authenticated identity
                // and fields the user has just confirmed in the edit form.
                val newProfile = mutableMapOf<String, Any>(
                    "uid" to user.uid,
                    "name" to name,
                    "phone" to phone,
                    "createdAt" to com.google.firebase.Timestamp.now()
                )
                user.email?.let { newProfile["email"] = it }
                transaction.set(profileRef, newProfile, SetOptions.merge())
            }
            null
        }.addOnSuccessListener {
            savedName = name
            savedPhone = phone
            updateProfileSummary(editEmail.text?.toString().orEmpty())
            profileCanBeEdited = true
            setLoading(false)
            setEditing(false)
            status.visibility = View.GONE
            Snackbar.make(root, getString(R.string.profile_save_success), Snackbar.LENGTH_LONG).show()
        }.addOnFailureListener { exception ->
            setLoading(false)
            logFirestoreFailure("Profile save", exception)
            showStatus(profileErrorMessage(exception))
        }
    }

    private fun logFirestoreFailure(operation: String, exception: Exception) {
        val code = (exception as? FirebaseFirestoreException)?.code?.name ?: "NON_FIRESTORE"
        // Do not include the email address or UID in the log message.
        Log.e(TAG, "$operation failed; Firebase error code=$code", exception)
    }

    private fun profileErrorMessage(exception: Exception): String {
        return when ((exception as? FirebaseFirestoreException)?.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "SafeRoute was denied access to the profile. Check the Firestore security rules."
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                "The profile service is unavailable. Check the connection and retry."
            FirebaseFirestoreException.Code.NOT_FOUND ->
                "The profile record could not be updated. Reload the screen and try again."
            else -> {
                val code = (exception as? FirebaseFirestoreException)?.code?.name
                if (code == null) getString(R.string.profile_load_failed)
                else "Profile operation failed (Firebase error: $code)."
            }
        }
    }

    private fun setEditing(editing: Boolean) {
        editName.isEnabled = editing
        editPhone.isEnabled = editing
        editEmail.isEnabled = false
        detailsCard.visibility = if (editing) View.GONE else View.VISIBLE
        editorCard.visibility = if (editing) View.VISIBLE else View.GONE
        editButton.visibility = if (editing) View.GONE else View.VISIBLE
        editActions.visibility = if (editing) View.VISIBLE else View.GONE
        updateButtons()
    }

    private fun updateProfileSummary(email: String) {
        val displayName = savedName.ifBlank { getString(R.string.profile_name_missing_value) }
        headerName.text = displayName
        headerEmail.text = email
        detailName.text = savedName.ifBlank { getString(R.string.profile_name_missing_value) }
        detailEmail.text = email
        detailPhone.text = savedPhone.ifBlank { getString(R.string.profile_phone_missing_value) }

        val initials = savedName.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
        headerInitials.text = initials.ifBlank { "—" }
    }

    private fun setLoading(loading: Boolean) {
        isLoading = loading
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        updateButtons()
    }

    private fun updateButtons() {
        editButton.isEnabled = profileCanBeEdited && !isLoading
        saveButton.isEnabled = !isLoading
        cancelButton.isEnabled = !isLoading
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_logout_title)
            .setMessage(R.string.profile_logout_message)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.profile_logout_confirm) { _, _ ->
                auth.currentUser?.uid?.let { SafetyReminderScheduler.cancelForUser(this, it) }
                auth.signOut()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .show()
    }

    companion object {
        private const val TAG = "ProfileActivity"
        private const val USERS_COLLECTION = "users"
    }
}
