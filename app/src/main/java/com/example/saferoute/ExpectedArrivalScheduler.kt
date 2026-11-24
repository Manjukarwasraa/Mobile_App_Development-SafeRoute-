package com.example.saferoute

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Independent AlarmManager state for expected-arrival confirmations. */
internal object ExpectedArrivalScheduler {
    private const val PREFS = "expected_arrival_monitoring"
    private const val TAG = "ExpectedArrivalScheduler"
    private const val TIMEOUT_MS = 60_000L
    private val stateLock = Any()

    internal data class State(
        val uid: String,
        val journeyId: String,
        val expectedAt: Long,
        val token: String,
        val status: String,
        val deadlineAt: Long
    )

    fun currentState(context: Context): State? = synchronized(stateLock) { readState(context) }

    fun ensureScheduled(context: Context, uid: String, journeyId: String, expectedAt: Long?) {
        if (uid.isBlank() || journeyId.isBlank() || expectedAt == null || expectedAt <= 0L) return
        synchronized(stateLock) {
            val current = readState(context)
            if (current?.uid == uid && current.journeyId == journeyId &&
                current.status in listOf("scheduled", "pending", "missed", "arrived")
            ) {
                val keepExisting = current.status != "scheduled" ||
                    abs(current.expectedAt - expectedAt) <= 1000L ||
                    current.expectedAt > expectedAt
                if (keepExisting) {
                    restoreLocked(context, current)
                    return
                }
            }
            current?.let {
                cancelAlarm(context, it, ACTION_ARRIVAL)
                cancelAlarm(context, it, ACTION_TIMEOUT)
                ArrivalNotifications.cancel(context, it.token)
            }
            val next = State(uid, journeyId, expectedAt, UUID.randomUUID().toString(), "scheduled", 0L)
            saveState(context, next)
            schedule(context, next, ACTION_ARRIVAL, expectedAt)
        }
    }

    fun cancelForJourney(context: Context, uid: String, journeyId: String) {
        synchronized(stateLock) {
            val state = readState(context) ?: return
            if (state.uid != uid || state.journeyId != journeyId) return
            cancelAlarm(context, state, ACTION_ARRIVAL)
            cancelAlarm(context, state, ACTION_TIMEOUT)
            ArrivalNotifications.cancel(context, state.token)
            if (state.status != "arrived") saveState(context, state.copy(status = "cancelled", deadlineAt = 0L))
        }
    }

    fun cancelForUser(context: Context, uid: String) {
        synchronized(stateLock) {
            val state = readState(context) ?: return
            if (state.uid != uid) return
            cancelAlarm(context, state, ACTION_ARRIVAL)
            cancelAlarm(context, state, ACTION_TIMEOUT)
            ArrivalNotifications.cancel(context, state.token)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    fun restore(context: Context) {
        val signedInUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        val stateToTimeout: State? = synchronized(stateLock) {
            val state = readState(context) ?: return
            if (signedInUid != state.uid) return
            restoreLocked(context, state)
        }
        if (stateToTimeout != null) {
            handleTimeout(context, stateToTimeout.uid, stateToTimeout.journeyId, stateToTimeout.token)
        }
    }

    fun restoreForUser(context: Context, uid: String) {
        if (uid.isBlank()) return
        val stateToTimeout: State? = synchronized(stateLock) {
            val state = readState(context) ?: return
            if (state.uid != uid) return
            restoreLocked(context, state)
        }
        if (stateToTimeout != null) {
            handleTimeout(context, stateToTimeout.uid, stateToTimeout.journeyId, stateToTimeout.token)
        }
    }

    private fun restoreLocked(context: Context, state: State): State? {
        when (state.status) {
            "scheduled" -> schedule(context, state, ACTION_ARRIVAL, state.expectedAt)
            "pending" -> {
                if (System.currentTimeMillis() >= state.deadlineAt) {
                    return state
                } else {
                    schedule(context, state, ACTION_TIMEOUT, state.deadlineAt)
                    ArrivalNotifications.showPrompt(context, state)
                }
            }
            "missed" -> {
                if (!ExpectedArrivalEvents.hasVisibleListener()) {
                    ArrivalNotifications.showMissed(context, state)
                }
            }
        }
        return null
    }

    fun handleArrival(context: Context, uid: String, journeyId: String, token: String) {
        val signedInUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        val pending: State = synchronized(stateLock) {
            val state = readState(context) ?: return
            if (signedInUid != null && signedInUid != uid) return
            if (state.uid != uid || state.journeyId != journeyId || state.token != token || state.status != "scheduled") return
            if (System.currentTimeMillis() < state.expectedAt) {
                schedule(context, state, ACTION_ARRIVAL, state.expectedAt)
                return
            }
            val next = state.copy(status = "pending", deadlineAt = System.currentTimeMillis() + TIMEOUT_MS)
            saveState(context, next)
            schedule(context, next, ACTION_TIMEOUT, next.deadlineAt)
            next
        }
        recordEvent(pending, "expected_arrival_reached", "prompted", "${token}_reached")
        updateJourneyArrivalFields(
            uid,
            journeyId,
            mapOf(
                "arrivalConfirmationStatus" to "pending",
                "arrivalPromptedAt" to FieldValue.serverTimestamp()
            )
        )
        ArrivalNotifications.showPrompt(context, pending)
        ExpectedArrivalEvents.dispatchPrompt(uid, journeyId, pending.token)
    }

    fun handleTimeout(context: Context, uid: String, journeyId: String, token: String) {
        val signedInUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        val missed: State = synchronized(stateLock) {
            val state = readState(context) ?: return
            if (signedInUid != null && signedInUid != uid) return
            if (state.uid != uid || state.journeyId != journeyId || state.token != token || state.status != "pending") return
            if (System.currentTimeMillis() < state.deadlineAt) {
                schedule(context, state, ACTION_TIMEOUT, state.deadlineAt)
                return
            }
            cancelAlarm(context, state, ACTION_TIMEOUT)
            val next = state.copy(status = "missed")
            saveState(context, next)
            next
        }
        recordEvent(missed, "arrival_confirmation_missed", "missed", "${token}_missed")
        updateJourneyArrivalFields(
            uid,
            journeyId,
            mapOf(
                "arrivalConfirmationStatus" to "missed",
                "arrivalMissedAt" to FieldValue.serverTimestamp()
            )
        )
        ArrivalNotifications.cancel(context, token)
        if (!ExpectedArrivalEvents.dispatchMissed(uid, journeyId, token)) {
            ArrivalNotifications.showMissed(context, missed)
        }
        setRefreshPending(context, uid)
    }

    fun confirmArrival(
        context: Context,
        uid: String,
        journeyId: String,
        token: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val arrived: State = synchronized(stateLock) {
            val state = readState(context) ?: run {
                onComplete?.invoke(false)
                return
            }
            if (state.uid != uid || state.journeyId != journeyId || state.token != token ||
                state.status !in listOf("pending", "missed")
            ) {
                onComplete?.invoke(false)
                return
            }
            cancelAlarm(context, state, ACTION_ARRIVAL)
            cancelAlarm(context, state, ACTION_TIMEOUT)
            ArrivalNotifications.cancel(context, token)
            val next = state.copy(status = "arrived", deadlineAt = 0L)
            saveState(context, next)
            next
        }

        SafetyReminderScheduler.cancelForJourney(context, uid, journeyId)
        recordEvent(arrived, "arrival_confirmation_received", "yes", "${token}_confirmed")
        setRefreshPending(context, uid)
        ExpectedArrivalEvents.dispatchConfirmed(uid, journeyId, token)

        val journeyRef = FirebaseFirestore.getInstance().collection("users").document(uid)
            .collection("journeys").document(journeyId)
        val completionUpdates = mapOf(
            "status" to "completed",
            "completedAt" to FieldValue.serverTimestamp(),
            "arrivalConfirmationStatus" to "arrived",
            "arrivalConfirmedAt" to FieldValue.serverTimestamp()
        )
        FirebaseFirestore.getInstance().runTransaction { transaction ->
            val journey = transaction.get(journeyRef)
            if (!journey.exists()) throw IllegalStateException("Journey no longer exists")
            val status = journey.getString("status")
            if (status == "completed") return@runTransaction false
            if (status != "active") throw IllegalStateException("Journey is no longer active")
            transaction.update(journeyRef, completionUpdates)
            true
        }.addOnSuccessListener {
            onComplete?.invoke(true)
        }.addOnFailureListener { exception ->
            val code = (exception as? FirebaseFirestoreException)?.code
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
            ) {
                // Queue the completion update offline so Firestore syncs it when connectivity returns.
                journeyRef.update(completionUpdates)
                    .addOnFailureListener { updateErr ->
                        Log.w(TAG, "Queued arrival confirmation sync warning", updateErr)
                    }
                onComplete?.invoke(true)
            } else {
                Log.e(TAG, "Arrival confirmation could not be saved", exception)
                synchronized(stateLock) {
                    val latest = readState(context)
                    if (latest?.token == token) {
                        saveState(context, latest.copy(status = "cancelled", deadlineAt = 0L))
                    }
                }
                onComplete?.invoke(false)
            }
        }
    }

    fun extend(context: Context, uid: String, journeyId: String, token: String, minutes: Int, result: (Boolean) -> Unit) {
        if (minutes <= 0) {
            result(false)
            return
        }
        val newExpectedAt = System.currentTimeMillis() + minutes * 60_000L
        val extended: State = synchronized(stateLock) {
            val state = readState(context)
            if (state == null || state.uid != uid || state.journeyId != journeyId || state.token != token ||
                state.status != "pending" || System.currentTimeMillis() >= state.deadlineAt
            ) {
                result(false)
                return
            }
            cancelAlarm(context, state, ACTION_TIMEOUT)
            ArrivalNotifications.cancel(context, token)
            val next = state.copy(
                expectedAt = newExpectedAt,
                token = UUID.randomUUID().toString(),
                status = "scheduled",
                deadlineAt = 0L
            )
            saveState(context, next)
            schedule(context, next, ACTION_ARRIVAL, newExpectedAt)
            next
        }

        recordEvent(
            extended,
            "arrival_time_extended",
            "extended",
            "${extended.token}_extended",
            mapOf("extensionMinutes" to minutes)
        )
        setRefreshPending(context, uid)

        val finished = AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())
        val offlineFallback = Runnable {
            if (finished.compareAndSet(false, true)) {
                result(true)
            }
        }
        mainHandler.postDelayed(offlineFallback, 2500L)

        val journeyRef = FirebaseFirestore.getInstance().collection("users").document(uid)
            .collection("journeys").document(journeyId)
        journeyRef.update(
            mapOf(
                "expectedArrival" to Timestamp(Date(newExpectedAt)),
                "arrivalConfirmationStatus" to "extended",
                "lastArrivalExtendedAt" to FieldValue.serverTimestamp()
            )
        ).addOnSuccessListener {
            mainHandler.removeCallbacks(offlineFallback)
            if (finished.compareAndSet(false, true)) {
                result(true)
            }
        }.addOnFailureListener { exception ->
            mainHandler.removeCallbacks(offlineFallback)
            Log.e(TAG, "Arrival extension could not be saved", exception)
            val code = (exception as? FirebaseFirestoreException)?.code
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
            ) {
                if (finished.compareAndSet(false, true)) result(true)
            } else {
                if (finished.compareAndSet(false, true)) result(false)
            }
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
    enum class EventType { PROMPT, MISSED, CONFIRMED }

    data class Event(
        val type: EventType,
        val uid: String,
        val journeyId: String,
        val token: String
    )

    @Volatile
    private var listener: ((Event) -> Unit)? = null

    fun register(callback: (Event) -> Unit) {
        listener = callback
    }

    fun unregister() {
        listener = null
    }

    fun hasVisibleListener(): Boolean = listener != null

    fun dispatchPrompt(uid: String, journeyId: String, token: String): Boolean =
        dispatch(Event(EventType.PROMPT, uid, journeyId, token))

    fun dispatchMissed(uid: String, journeyId: String, token: String): Boolean =
        dispatch(Event(EventType.MISSED, uid, journeyId, token))

    fun dispatchConfirmed(uid: String, journeyId: String, token: String): Boolean =
        dispatch(Event(EventType.CONFIRMED, uid, journeyId, token))

    private fun dispatch(event: Event): Boolean {
        val currentListener = listener ?: return false
        Handler(Looper.getMainLooper()).post {
            if (listener === currentListener) currentListener(event)
        }
        return true
    }
}
