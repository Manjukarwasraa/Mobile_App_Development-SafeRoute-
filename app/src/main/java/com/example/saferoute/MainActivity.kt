package com.example.saferoute

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

/**
 * MainActivity — Authentication Router.
 *
 * This activity has NO layout. It simply checks whether the user
 * is already logged in and redirects immediately:
 *
 *   User IS logged in  → DashboardActivity
 *   User NOT logged in → LoginActivity
 *
 * This runs every time the app is launched, so returning users go
 * straight to the dashboard without seeing the login screen again.
 */

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val currentUser = FirebaseAuth.getInstance().currentUser

        val destination = if (currentUser != null) {
            DashboardActivity::class.java
        } else {
            LoginActivity::class.java
        }

        startActivity(Intent(this, destination))
        finish()
    }
}
