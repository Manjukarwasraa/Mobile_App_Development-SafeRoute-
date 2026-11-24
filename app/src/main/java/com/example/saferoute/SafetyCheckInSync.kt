package com.example.saferoute

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject
import java.util.Date

/** Keeps automatic check-in writes on disk until Firestore confirms they were saved. */
internal object SafetyCheckInSync {
    private const val PREFS_NAME = "pending_safety_check_ins"
    private const val RECORD_PREFIX = "record_"
    private const val TAG = "SafetyCheckInSync"

    fun save(
        context: Context,
        uid: String,
        journeyId: String,
        checkInId: String,
        response: String,
        status: String,
        createdAtMillis: Long,
        deadlineAtMillis: Long,
        respondedAtMillis: Long,
        emergencyTriggered: Boolean,
        alertReason: String? = null
    ) {
        val record = JSONObject()
            .put("uid", uid)
            .put("journeyId", journeyId)
            .put("checkInId", checkInId)
            .put("response", response)
            .put("status", status)
            .put("createdAtMillis", createdAtMillis)
            .put("deadlineAtMillis", deadlineAtMillis)
            .put("respondedAtMillis", respondedAtMillis)
            .put("emergencyTriggered", emergencyTriggered)
            .put("alertReason", alertReason ?: JSONObject.NULL)
            .put("version", System.nanoTime())

        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = recordKey(uid, checkInId)
        preferences.edit().putString(key, record.toString()).commit()
        syncRecord(context, key, record.toString())
    }

    fun flushPending(context: Context, uid: String) {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferences.all
            .filterKeys { it.startsWith("$RECORD_PREFIX$uid/") }
            .forEach { (key, value) ->
                val json = value as? String ?: return@forEach
                syncRecord(context, key, json)
            }
    }

    private fun syncRecord(context: Context, key: String, json: String) {
        val record = try {
            JSONObject(json)
        } catch (exception: Exception) {
            Log.e(TAG, "A pending safety check-in could not be read", exception)
            return
        }
        val uid = record.optString("uid")
        val journeyId = record.optString("journeyId")
        val checkInId = record.optString("checkInId")
        if (uid.isBlank() || journeyId.isBlank() || checkInId.isBlank()) return

        val createdAt = record.optLong("createdAtMillis")
        val deadlineAt = record.optLong("deadlineAtMillis")
        val respondedAt = record.optLong("respondedAtMillis")
        val fields = mutableMapOf<String, Any?>(
            "journeyId" to journeyId,
            "checkInId" to checkInId,
            "response" to record.optString("response"),
            "status" to record.optString("status"),
            "createdAt" to Timestamp(Date(createdAt)),
            "recordedAt" to Timestamp(Date(if (respondedAt > 0) respondedAt else createdAt)),
            "deadlineAt" to Timestamp(Date(deadlineAt)),
            "emergencyTriggered" to record.optBoolean("emergencyTriggered")
        )
        if (respondedAt > 0) fields["respondedAt"] = Timestamp(Date(respondedAt))
        if (!record.isNull("alertReason")) fields["alertReason"] = record.optString("alertReason")

        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .collection("safetyCheckIns")
            .document(checkInId)
            .set(fields, SetOptions.merge())
            .addOnSuccessListener {
                val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val latest = preferences.getString(key, null)
                if (latest == json) {
                    preferences.edit().remove(key).apply()
                } else if (latest != null) {
                    syncRecord(context, key, latest)
                }
            }
            .addOnFailureListener { exception ->
                // Keep the local copy. JourneyActivity will retry when the user returns online.
                Log.w(TAG, "Check-in $checkInId remains queued for Firestore sync", exception)
            }
    }

    private fun recordKey(uid: String, checkInId: String) = "$RECORD_PREFIX$uid/$checkInId"
}
