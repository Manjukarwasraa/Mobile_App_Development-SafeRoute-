package com.example.saferoute

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException

/**
 * LoginActivity — authenticates an existing user with Firebase.
 *
 * Flow:
 * 1. User enters email and password.
 * 2. We validate locally first to avoid unnecessary network calls.
 * 3. Firebase Authentication checks the credentials.
 * 4. On success, navigate to DashboardActivity (Step 4).
 * 5. "Forgot Password" sends a password reset email through Firebase.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var firebaseAuth: FirebaseAuth

    private lateinit var layoutEmail: TextInputLayout
    private lateinit var layoutPassword: TextInputLayout
    private lateinit var editEmail: TextInputEditText
    private lateinit var editPassword: TextInputEditText
    private lateinit var btnLogin: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvGoToRegister: TextView
    private lateinit var tvForgotPassword: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        firebaseAuth = FirebaseAuth.getInstance()

        // Bind views
        layoutEmail = findViewById(R.id.layoutEmail)
        layoutPassword = findViewById(R.id.layoutPassword)
        editEmail = findViewById(R.id.editEmail)
        editPassword = findViewById(R.id.editPassword)
        btnLogin = findViewById(R.id.btnLogin)
        progressBar = findViewById(R.id.progressBar)
        tvGoToRegister = findViewById(R.id.tvGoToRegister)
        tvForgotPassword = findViewById(R.id.tvForgotPassword)

        btnLogin.setOnClickListener {
            val email = editEmail.text.toString().trim()
            val password = editPassword.text.toString()

            if (validateInputs(email, password)) {
                loginUser(email, password)
            }
        }

        tvGoToRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }

        tvForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }
    }

    /**
     * Validates email and password before making the network call.
     * Shows errors directly below the relevant field.
     */
    private fun validateInputs(email: String, password: String): Boolean {
        layoutEmail.error = null
        layoutPassword.error = null

        if (email.isEmpty()) {
            layoutEmail.error = getString(R.string.error_email_empty)
            editEmail.requestFocus()
            return false
        }

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

        return true
    }

    /**
     * Signs the user in via Firebase Authentication.
     * On success, navigates to DashboardActivity and clears the back stack
     * so the user cannot go back to Login by pressing the back button.
     */
    private fun loginUser(email: String, password: String) {
        showLoading(true)

        firebaseAuth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                showLoading(false)
                goToDashboard()
            }
            .addOnFailureListener { exception ->
                showLoading(false)
                showError(getFriendlyErrorMessage(exception))
            }
    }

    /**
     * Shows a dialog asking for the user's email then sends a reset link
     * through Firebase Authentication.
     */
    private fun showForgotPasswordDialog() {
        // Pre-fill with whatever is already in the email field
        val currentEmail = editEmail.text.toString().trim()

        // Build a simple input dialog
        val inputLayout = layoutInflater.inflate(R.layout.dialog_forgot_password, null)
        val dialogEmailInput = inputLayout.findViewById<TextInputEditText>(R.id.editDialogEmail)
        if (currentEmail.isNotEmpty()) {
            dialogEmailInput.setText(currentEmail)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Reset Password")
            .setMessage("Enter your email address and we will send you a reset link.")
            .setView(inputLayout)
            .setPositiveButton("Send Reset Link") { _, _ ->
                val resetEmail = dialogEmailInput.text.toString().trim()
                if (resetEmail.isEmpty()) {
                    showError(getString(R.string.error_email_empty))
                } else {
                    sendPasswordResetEmail(resetEmail)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Sends a password reset email via Firebase. */
    private fun sendPasswordResetEmail(email: String) {
        firebaseAuth.sendPasswordResetEmail(email)
            .addOnSuccessListener {
                Snackbar.make(
                    btnLogin,
                    "Password reset link sent to $email",
                    Snackbar.LENGTH_LONG
                ).show()
            }
            .addOnFailureListener { exception ->
                showError(getFriendlyErrorMessage(exception))
            }
    }

    /** Maps Firebase error codes to plain English messages. */
    private fun getFriendlyErrorMessage(exception: Exception): String {
        return if (exception is FirebaseAuthException) {
            when (exception.errorCode) {
                "ERROR_USER_NOT_FOUND" ->
                    "No account found with this email. Please register first."
                "ERROR_WRONG_PASSWORD" ->
                    "Incorrect password. Please try again."
                "ERROR_INVALID_EMAIL" ->
                    "The email address is not valid."
                "ERROR_USER_DISABLED" ->
                    "This account has been disabled. Please contact support."
                "ERROR_TOO_MANY_REQUESTS" ->
                    "Too many failed attempts. Please wait a few minutes and try again."
                "ERROR_NETWORK_REQUEST_FAILED" ->
                    getString(R.string.error_network)
                "ERROR_INVALID_CREDENTIAL" ->
                    "Incorrect email or password. Please check and try again."
                else ->
                    "Sign in failed: ${exception.message}"
            }
        } else {
            getString(R.string.error_generic)
        }
    }

    private fun showLoading(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !isLoading
    }

    private fun showError(message: String) {
        Snackbar.make(btnLogin, message, Snackbar.LENGTH_LONG).show()
    }

    /**
     * Navigates to DashboardActivity after successful login.
     * FLAG_ACTIVITY_NEW_TASK + CLEAR_TASK wipes the entire back stack —
     * the user cannot press Back to return to the Login screen.
     */
    private fun goToDashboard() {
        val intent = Intent(this, DashboardActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
