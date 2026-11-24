package com.example.saferoute

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID

/** Independent AlarmManager state for expected-arrival confirmations. */
internal object ExpectedArrivalScheduler {
    private const val PREFS = "expected_arrival_monitoring"
    private const val TAG = "ExpectedArrivalScheduler"
    private const val TIMEOUT_MS = 60_000L

    internal data class State(
        val uid: String,
        val journeyId: String,
        val expectedAt: Long,
        val token: String,
        val status: String,
        val deadlineAt: Long
    )

    fun currentState(context: Context): State? = readState(context)

    fun ensureScheduled(context: Context, uid: String, journeyId: String, expectedAt: Long?) {
        if (uid.isBlank() || journeyId.isBlank() || expectedAt == null || expectedAt <= 0L) return
        val current = readState(context)
        if (current?.uid == uid && current.journeyId == journeyId && current.expectedAt == expectedAt &&
            current.status in listOf("scheduled", "pending", "missed", "arrived")) {
            restore(context)
            return
        }
        current?.let { cancelAlarm(context, it, ACTION_ARRIVAL); cancelAlarm(context, it, ACTION_TIMEOUT); ArrivalNotifications.cancel(context, it.token) }
        val next = State(uid, journeyId, expectedAt, UUID.randomUUID().toString(), "scheduled", 0L)
        saveState(context, next)
        schedule(context, next, ACTION_ARRIVAL, expectedAt)
    }

    fun cancelForJourney(context: Context, uid: String, journeyId: String) {
        val state = readState(context) ?: return
        if (state.uid != uid || state.journeyId != journeyId) return
        cancelAlarm(context, state, ACTION_ARRIVAL)
        cancelAlarm(context, state, ACTION_TIMEOUT)
        ArrivalNotifications.cancel(context, state.token)
        if (state.status != "arrived") saveState(context, state.copy(status = "cancelled", deadlineAt = 0L))
    }

    fun cancelForUser(context: Context, uid: String) {
        val state = readState(context) ?: return
        if (state.uid != uid) return
        cancelAlarm(context, state, ACTION_ARRIVAL)
        cancelAlarm(context, state, ACTION_TIMEOUT)
        ArrivalNotifications.cancel(context, state.token)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun restore(context: Context) {
        val state = readState(context) ?: return
        when (state.status) {
            "scheduled" -> schedule(context, state, ACTION_ARRIVAL, state.expectedAt)
            "pending" -> {
                if (System.currentTimeMillis() >= state.deadlineAt) handleTimeout(context, state.uid, state.journeyId, state.token)
                else {
                    schedule(context, state, ACTION_TIMEOUT, state.deadlineAt)
                    ArrivalNotifications.showPrompt(context, state)
                }
            }
            "missed" -> ArrivalNotifications.showMissed(context, state)
        }
    }

    fun handleArrival(context: Context, uid: String, journeyId: String, token: String) {
        val state = readState(context) ?: return
        if (state.uid != uid || state.journeyId != journeyId || state.token != token || state.status != "scheduled") return
        if (System.currentTimeMillis() < state.expectedAt) {
            schedule(context, state, ACTION_ARRIVAL, state.expectedAt)
            return
        }
        val pending = state.copy(status = "pending", deadlineAt = System.currentTimeMillis() + TIMEOUT_MS)
        saveState(context, pending)
        schedule(context, pending, ACTION_TIMEOUT, pending.deadlineAt)
        recordEvent(context, pending, "expected_arrival_reached", "prompted", "${token}_reached")
        updateJourneyArrivalFields(uid, journeyId, mapOf(
            "arrivalConfirmationStatus" to "pending",
            "arrivalPromptedAt" to FieldValue.serverTimestamp()
        ))
        ArrivalNotifications.showPrompt(context, pending)
    }

    fun handleTimeout(context: Context, uid: String, journeyId: String, token: String) {
        val state = readState(context) ?: return
        if (state.uid != uid || state.journeyId != journeyId || state.token != token || state.status != "pending") return
        if (System.currentTimeMillis() < state.deadlineAt) {
            schedule(context, state, ACTION_TIMEOUT, state.deadlineAt)
            return
        }
        val missed = state.copy(status = "missed")
        saveState(context, missed)
        recordEvent(context, missed, "arrival_confirmation_missed", "missed", "${token}_missed")
        updateJourneyArrivalFields(uid, journeyId, mapOf(
            "arrivalConfirmationStatus" to "missed",
            "arrivalMissedAt" to FieldValue.serverTimestamp()
        ))
        ArrivalNotifications.showMissed(context, missed)
    }

    fun confirmArrival(context: Context, uid: String, journeyId: String, token: String) {
        val state = readState(context) ?: return
        if (state.uid != uid || state.journeyId != journeyId || state.token != token || state.status !in listOf("pending", "missed")) return
        val journeyRef = FirebaseFirestore.getInstance().collection("users").document(uid)
            .collection("journeys").document(journeyId)
        FirebaseFirestore.getInstance().runTransaction { transaction ->
            val journey = transaction.get(journeyRef)
            if (!journey.exists()) throw IllegalStateException("Journey no longer exists")
            val status = journey.getString("status")
            if (status == "completed") return@runTransaction false
            if (status != "active") throw IllegalStateException("Journey is no longer active")
            transaction.update(journeyRef, mapOf(
                "status" to "completed",
                "completedAt" to FieldValue.serverTimestamp(),
                "arrivalConfirmationStatus" to "arrived",
                "arrivalConfirmedAt" to FieldValue.serverTimestamp()
            ))
            true
        }.addOnSuccessListener { completed ->
            cancelAlarm(context, state, ACTION_TIMEOUT)
            ArrivalNotifications.cancel(context, token)
            if (completed) {
                val arrived = state.copy(status = "arrived", deadlineAt = 0L)
                saveState(context, arrived)
                recordEvent(context, arrived, "arrival_confirmation_received", "yes", "${token}_confirmed")
                SafetyReminderScheduler.cancelForJourney(context, uid, journeyId)
            } else {
                saveState(context, state.copy(status = "cancelled", deadlineAt = 0L))
            }
            setRefreshPending(context, uid)
            ExpectedArrivalEvents.dispatch(uid, journeyId)
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Arrival confirmation could not be saved", exception)
            ArrivalNotifications.showPrompt(context, state)
        }
    }

    fun extend(context: Context, uid: String, journeyId: String, token: String, minutes: Int, result: (Boolean) -> Unit) {
        val state = readState(context)
        if (state == null || state.uid != uid || state.journeyId != journeyId || state.token != token ||
            state.status != "pending" || System.currentTimeMillis() >= state.deadlineAt || minutes <= 0) {
            result(false)
            return
        }
        val newExpectedAt = System.currentTimeMillis() + minutes * 60_000L
        val journeyRef = FirebaseFirestore.getInstance().collection("users").document(uid)
            .collection("journeys").document(journeyId)
        journeyRef.update(mapOf(
            "expectedArrival" to com.google.firebase.Timestamp(java.util.Date(newExpectedAt)),
            "arrivalConfirmationStatus" to "extended",
            "lastArrivalExtendedAt" to FieldValue.serverTimestamp()
        )).addOnSuccessListener {
            cancelAlarm(context, state, ACTION_TIMEOUT)
            ArrivalNotifications.cancel(context, token)
            val extended = state.copy(expectedAt = newExpectedAt, token = UUID.randomUUID().toString(), status = "scheduled", deadlineAt = 0L)
            saveState(context, extended)
            schedule(context, extended, ACTION_ARRIVAL, newExpectedAt)
            recordEvent(context, extended, "arrival_time_extended", "extended", "${extended.token}_extended", mapOf("extensionMinutes" to minutes))
            updateJourneyArrivalFields(uid, journeyId, mapOf("arrivalConfirmationStatus" to "waiting"))
            setRefreshPending(context, uid)
            result(true)
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Arrival extension could not be saved", exception)
            result(false)
        }
    }

    fun consumeRefresh(context: Context, uid: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "refresh_$uid"
        val pending = prefs.getBoolean(key, false)
        if (pending) prefs.edit().remove(key).apply()
        return pending
    }

    private fun updateJourneyArrivalFields(uid: String, journeyId: String, updates: Map<String, Any>) {
        FirebaseFirestore.getInstance().collection("users").document(uid)
            .collection("journeys").document(journeyId).update(updates)
            .addOnFailureListener { Log.w(TAG, "Arrival status is waiting to sync", it) }
    }

    private fun recordEvent(
        context: Context,
        state: State,
        eventType: String,
        responseStatus: String,
        eventKey: String,
        extra: Map<String, Any> = emptyMap()
    ) {
        val data = mutableMapOf<String, Any>(
            "journeyId" to state.journeyId,
            "eventType" to eventType,
            "responseStatus" to responseStatus,
            "recordedAt" to FieldValue.serverTimestamp()
        )
        data.putAll(extra)
        FirebaseFirestore.getInstance().collection("users").document(state.uid)
            .collection("safetyCheckIns").document("arrival_${state.journeyId}_$eventKey")
            .set(data)
            .addOnFailureListener { Log.w(TAG, "Arrival event is waiting to sync", it) }
    }

    private fun setRefreshPending(context: Context, uid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("refresh_$uid", true).apply()
    }

    private fun schedule(context: Context, state: State, action: String, at: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = alarmIntent(context, state, action)
        val triggerAt = at.coerceAtLeast(System.currentTimeMillis() + 500L)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else manager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Exact arrival alarm unavailable; using a flexible alarm", exception)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun alarmIntent(context: Context, state: State, action: String) =
        PendingIntent.getBroadcast(
            context,
            state.token.hashCode() xor action.hashCode(),
            Intent(context, ExpectedArrivalReceiver::class.java).apply {
                this.action = action
                data = Uri.parse("saferoute://arrival/$action/${state.uid}/${state.journeyId}/${state.token}")
                putExtra(EXTRA_UID, state.uid)
                putExtra(EXTRA_JOURNEY_ID, state.journeyId)
                putExtra(EXTRA_TOKEN, state.token)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun cancelAlarm(context: Context, state: State, action: String) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            state.token.hashCode() xor action.hashCode(),
            Intent(context, ExpectedArrivalReceiver::class.java).apply {
                this.action = action
                data = Uri.parse("saferoute://arrival/$action/${state.uid}/${state.journeyId}/${state.token}")
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun saveState(context: Context, state: State) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("uid", state.uid)
            .putString("journeyId", state.journeyId)
            .putLong("expectedAt", state.expectedAt)
            .putString("token", state.token)
            .putString("status", state.status)
            .putLong("deadlineAt", state.deadlineAt)
            .commit()
    }

    private fun readState(context: Context): State? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uid = prefs.getString("uid", null) ?: return null
        val journeyId = prefs.getString("journeyId", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        return State(uid, journeyId, prefs.getLong("expectedAt", 0L), token, prefs.getString("status", "").orEmpty(), prefs.getLong("deadlineAt", 0L))
    }

    const val ACTION_ARRIVAL = "com.example.saferoute.action.EXPECTED_ARRIVAL"
    const val ACTION_TIMEOUT = "com.example.saferoute.action.EXPECTED_ARRIVAL_TIMEOUT"
    const val ACTION_CONFIRM = "com.example.saferoute.action.EXPECTED_ARRIVAL_CONFIRM"
    const val EXTRA_UID = "expected_arrival_uid"
    const val EXTRA_JOURNEY_ID = "expected_arrival_journey_id"
    const val EXTRA_TOKEN = "expected_arrival_token"
}

internal object ExpectedArrivalEvents {
    private var listener: ((String, String) -> Unit)? = null

    fun register(callback: (String, String) -> Unit) {
        listener = callback
    }

    fun unregister() {
        listener = null
    }

    fun dispatch(uid: String, journeyId: String) {
        listener?.invoke(uid, journeyId)
    }
}
