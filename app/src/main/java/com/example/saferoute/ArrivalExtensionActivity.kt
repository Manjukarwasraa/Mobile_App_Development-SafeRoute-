package com.example.saferoute

import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth

class ArrivalExtensionActivity : AppCompatActivity() {
    private lateinit var progress: ProgressBar
    private lateinit var customLayout: TextInputLayout
    private lateinit var customMinutes: TextInputEditText
    private lateinit var message: TextView
    private var uid: String = ""
    private var journeyId: String = ""
    private var token: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_arrival_extension)

        uid = intent.getStringExtra(ExpectedArrivalScheduler.EXTRA_UID).orEmpty()
        journeyId = intent.getStringExtra(ExpectedArrivalScheduler.EXTRA_JOURNEY_ID).orEmpty()
        token = intent.getStringExtra(ExpectedArrivalScheduler.EXTRA_TOKEN).orEmpty()
        if (uid.isBlank() || journeyId.isBlank() || token.isBlank() || FirebaseAuth.getInstance().currentUser?.uid != uid) {
            finish()
            return
        }

        progress = findViewById(R.id.progressArrivalExtension)
        customLayout = findViewById(R.id.layoutCustomArrivalMinutes)
        customMinutes = findViewById(R.id.editCustomArrivalMinutes)
        message = findViewById(R.id.tvArrivalExtensionMessage)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbarArrivalExtension)
            .setNavigationOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnExtend10Minutes).setOnClickListener { extendBy(10) }
        findViewById<MaterialButton>(R.id.btnExtend15Minutes).setOnClickListener { extendBy(15) }
        findViewById<MaterialButton>(R.id.btnExtend30Minutes).setOnClickListener { extendBy(30) }
        findViewById<MaterialButton>(R.id.btnExtendCustom).setOnClickListener {
            val minutes = customMinutes.text?.toString()?.trim()?.toIntOrNull()
            if (minutes == null || minutes <= 0) {
                customLayout.error = getString(R.string.arrival_custom_invalid)
            } else {
                customLayout.error = null
                extendBy(minutes)
            }
        }
    }

    private fun extendBy(minutes: Int) {
        setBusy(true)
        ExpectedArrivalScheduler.extend(this, uid, journeyId, token, minutes) { updated ->
            if (isFinishing || isDestroyed) return@extend
            setBusy(false)
            if (updated) {
                Toast.makeText(this, getString(R.string.arrival_extension_saved, minutes), Toast.LENGTH_LONG).show()
                startActivity(Intent(this, JourneyActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(JourneyActivity.EXTRA_REFRESH_JOURNEY, true)
                })
                finish()
            } else {
                message.text = getString(R.string.arrival_extension_failed)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<MaterialButton>(R.id.btnExtend10Minutes).isEnabled = !busy
        findViewById<MaterialButton>(R.id.btnExtend15Minutes).isEnabled = !busy
        findViewById<MaterialButton>(R.id.btnExtend30Minutes).isEnabled = !busy
        findViewById<MaterialButton>(R.id.btnExtendCustom).isEnabled = !busy
    }
}
