package com.example.saferoute

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException

/**
 * RegisterActivity â€” lets new users create a SafeRoute account.
 *
 * Flow:
 * 1. User fills name, email, password, confirm password.
 * 2. We validate all four fields locally before calling Firebase.
 * 3. Firebase Authentication creates the account (stores hashed password â€” we never see it).
 * 4. On success we save {name, email, createdAt} to Firestore under users/{uid}.
 * 5. Navigate to LoginActivity so the user can sign in.
 *
 * IMPORTANT: Passwords are NEVER written to Firestore.
 */
class RegisterActivity : AppCompatActivity() {

    // Firebase instances
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    // UI references
    private lateinit var layoutName: TextInputLayout
    private lateinit var layoutEmail: TextInputLayout
    private lateinit var layoutPassword: TextInputLayout
    private lateinit var layoutConfirmPassword: TextInputLayout
    private lateinit var editName: TextInputEditText
    private lateinit var editEmail: TextInputEditText
    private lateinit var editPassword: TextInputEditText
    private lateinit var editConfirmPassword: TextInputEditText
    private lateinit var btnRegister: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvGoToLogin: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // Initialise Firebase
        firebaseAuth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // Bind all views
        layoutName = findViewById(R.id.layoutName)
        layoutEmail = findViewById(R.id.layoutEmail)
        layoutPassword = findViewById(R.id.layoutPassword)
        layoutConfirmPassword = findViewById(R.id.layoutConfirmPassword)
        editName = findViewById(R.id.editName)
        editEmail = findViewById(R.id.editEmail)
        editPassword = findViewById(R.id.editPassword)
        editConfirmPassword = findViewById(R.id.editConfirmPassword)
        btnRegister = findViewById(R.id.btnRegister)
        progressBar = findViewById(R.id.progressBar)
        tvGoToLogin = findViewById(R.id.tvGoToLogin)

        // Register button click
        btnRegister.setOnClickListener {
            val name = editName.text.toString().trim()
            val email = editEmail.text.toString().trim()
            val password = editPassword.text.toString()
            val confirmPassword = editConfirmPassword.text.toString()

            if (validateInputs(name, email, password, confirmPassword)) {
                registerUser(name, email, password)
            }
        }

        // Navigate to Login when "Sign In" is tapped
        tvGoToLogin.setOnClickListener {
            goToLogin()
        }
    }

    /**
     * Validates all four fields.
     * Sets an error on the TextInputLayout (shown below the field) and returns false
     * if any field is invalid. Clears old errors first.
     */
    private fun validateInputs(
        name: String,
        email: String,
        password: String,
        confirmPassword: String
    ): Boolean {
        // Clear previous errors
        layoutName.error = null
        layoutEmail.error = null
        layoutPassword.error = null
        layoutConfirmPassword.error = null

        if (name.isEmpty()) {
            layoutName.error = getString(R.string.error_name_empty)
            editName.requestFocus()
            return false
        }

        if (email.isEmpty()) {
            layoutEmail.error = getString(R.string.error_email_empty)
            editEmail.requestFocus()
            return false
        }

        // Basic email format check using Android's built-in pattern
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            layoutEmail.error = getString(R.string.error_email_invalid)
            editEmail.requestFocus()
            return false
        }

        if (password.isEmpty()) {
            layoutPassword.error = getString(R.string.error_password_empty)
            editPassword.requestFocus()
            return false
        }

        if (password.length < 8 ||
            password.none { it.isUpperCase() } ||
            password.none { it.isLowerCase() } ||
            password.none { it.isDigit() } ||
            password.none { !it.isLetterOrDigit() }
        ) {
            layoutPassword.error =
                "Use at least 8 characters, including uppercase and lowercase letters, a number, and a special character."
            editPassword.requestFocus()
            return false
        }

        if (password != confirmPassword) {
            layoutConfirmPassword.error = getString(R.string.error_password_mismatch)
            editConfirmPassword.requestFocus()
            return false
        }

        return true
    }

    /**
     * Calls Firebase Authentication to create the account.
     * On success, saves the user profile to Firestore.
     * Passwords are handled entirely by Firebase â€” we never store them.
     */
    private fun registerUser(name: String, email: String, password: String) {
        showLoading(true)

        // A Firestore write can fail after Auth has already created the account.
        // Allow a retry to reuse the still-authenticated account.
        val signedInUser = firebaseAuth.currentUser
        if (signedInUser != null && signedInUser.email.equals(email, ignoreCase = true)) {
            saveUserProfileToFirestore(signedInUser.uid, name, email)
            return
        }

        firebaseAuth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid ?: run {
                    showLoading(false)
                    showError("Registration failed. Please try again.")
                    return@addOnSuccessListener
                }
                saveUserProfileToFirestore(uid, name, email)
            }
            .addOnFailureListener { exception ->
                showLoading(false)
                showError(getFriendlyErrorMessage(exception))
            }
    }

    /**
     * Saves {name, email, createdAt} to Firestore under users/{uid}.
     * Only public profile data is stored â€” never passwords.
     *
     * Firestore path: users / {uid}
     */
    private fun saveUserProfileToFirestore(uid: String, name: String, email: String) {
        val userDocument = firestore.collection("users").document(uid)
        // Create only if absent; retries must preserve any existing user fields.
        firestore.runTransaction { transaction ->
            val existing = transaction.get(userDocument)
            if (!existing.exists()) {
                transaction.set(
                    userDocument,
                    mapOf(
                        "uid" to uid,
                        "name" to name,
                        "email" to email,
                        "phone" to "",
                        "createdAt" to com.google.firebase.Timestamp.now()
                    )
                )
            }
            null
        }.addOnSuccessListener {
            showLoading(false)
            Snackbar.make(btnRegister, getString(R.string.msg_register_success), Snackbar.LENGTH_SHORT).show()
            btnRegister.postDelayed({ goToLogin() }, 1200)
        }.addOnFailureListener { exception ->
            showLoading(false)
            val code = (exception as? FirebaseFirestoreException)?.code?.name ?: "NON_FIRESTORE"
            Log.e("RegisterActivity", "Profile creation failed; Firebase error code=$code", exception)
            val message = when ((exception as? FirebaseFirestoreException)?.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "Account created, but Firestore denied profile creation. Check security rules and retry."
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                    "Account created, but the profile service is unavailable. Check the connection and retry."
                else -> "Account created, but the profile could not be saved (Firebase error: $code). Retry."
            }
            // Remain here so the user can retry; retry uses the signed-in Auth account above.
            showError(message)
        }
    }

    /**
     * Converts Firebase error codes into plain English messages.
     * FirebaseAuthException gives us an error code we can check.
     */
    private fun getFriendlyErrorMessage(exception: Exception): String {
        return if (exception is FirebaseAuthException) {
            when (exception.errorCode) {
                "ERROR_EMAIL_ALREADY_IN_USE" ->
                    "This email is already registered. Please sign in instead."
                "ERROR_INVALID_EMAIL" ->
                    "The email address is not valid."
                "ERROR_WEAK_PASSWORD" ->
                    "Password is too weak. Use at least 6 characters."
                "ERROR_NETWORK_REQUEST_FAILED" ->
                    getString(R.string.error_network)
                else ->
                    "Registration failed: ${exception.message}"
            }
        } else {
            getString(R.string.error_generic)
        }
    }

    /** Shows or hides the progress bar and disables the button while loading. */
    private fun showLoading(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        btnRegister.isEnabled = !isLoading
    }

    /** Shows a Snackbar error message at the bottom of the screen. */
    private fun showError(message: String) {
        Snackbar.make(btnRegister, message, Snackbar.LENGTH_LONG).show()
    }

    /** Navigates to LoginActivity and removes RegisterActivity from the back stack. */
    private fun goToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        // Clear this activity from the back stack so back button does not return here
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }
}


