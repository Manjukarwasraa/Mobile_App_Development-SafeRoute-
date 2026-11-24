package com.example.saferoute

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.util.UUID

/** Stores the single active user's reminder state and schedules local alarms. */
internal object SafetyReminderScheduler {
    private const val PREFS_NAME = "local_safety_reminders"
    private const val TAG = "SafetyReminderScheduler"
    private const val KEY_UID = "uid"
    private const val KEY_JOURNEY_ID = "journey_id"
    private const val KEY_INTERVAL = "interval_minutes"
    private const val KEY_GENERATION = "generation"
    private const val KEY_SEQUENCE = "sequence"
    private const val KEY_NEXT_CHECK_ID = "next_check_id"
    private const val KEY_NEXT_REMINDER_AT = "next_reminder_at"
    private const val KEY_CURRENT_CHECK_ID = "current_check_id"
    private const val KEY_CURRENT_CREATED_AT = "current_created_at"
    private const val KEY_DEADLINE_AT = "deadline_at"
    private const val KEY_CURRENT_STATUS = "current_status"
    private const val KEY_PAUSED_REASON = "paused_reason"
    private const val KEY_EMERGENCY_CHECK_ID = "emergency_check_id"

    private val stateLock = Any()

    internal data class State(
        val uid: String,
        val journeyId: String,
        val intervalMinutes: Int,
        val generation: Int,
        val sequence: Int,
        val nextCheckInId: String,
        val nextReminderAt: Long,
        val currentCheckInId: String,
        val currentCreatedAt: Long,
        val deadlineAt: Long,
        val currentStatus: String,
        val pausedReason: String,
        val emergencyCheckInId: String
    )

    internal enum class ResponseResult { RECORDED, TOO_LATE, NOT_ACTIVE }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager(context).canScheduleExactAlarms()
    }

    fun notificationsAllowed(context: Context): Boolean =
        SafetyReminderNotifications.notificationsAllowed(context)

    fun ensureMonitoring(context: Context, uid: String, journeyId: String, intervalMinutes: Int) {
        if (uid.isBlank() || journeyId.isBlank()) return
        synchronized(stateLock) {
            val existing = readState(context)
            if (existing?.uid == uid && existing.journeyId == journeyId) {
                val safeInterval = intervalMinutes.takeIf { it == 5 || it == 10 || it == 15 } ?: 15
                val current = if (existing.intervalMinutes != safeInterval) {
                    val updated = existing.copy(intervalMinutes = safeInterval)
                    writeState(context, updated)
                    updated
                } else existing
                if (current.pausedReason == "notification_permission") {
                    if (notificationsAllowed(context)) scheduleNext(context, current.copy(pausedReason = ""))
                    return
                }
                if (current.pausedReason.isNotBlank()) return
                if (current.currentStatus == "pending" && current.currentCheckInId.isNotBlank()) {
                    scheduleTimeout(context, current)
                } else if (current.nextReminderAt > 0L && current.nextCheckInId.isNotBlank()) {
                    scheduleReminder(context, current)
                } else {
                    scheduleNext(context, current)
                }
                return
            }

            existing?.let { cancelAlarms(context, it) }
            val safeInterval = intervalMinutes.takeIf { it == 5 || it == 10 || it == 15 } ?: 15
            val initial = State(
                uid = uid,
                journeyId = journeyId,
                intervalMinutes = safeInterval,
                generation = (existing?.generation ?: 0) + 1,
                sequence = 1,
                nextCheckInId = UUID.randomUUID().toString(),
                nextReminderAt = System.currentTimeMillis() + safeInterval * 60_000L,
                currentCheckInId = "",
                currentCreatedAt = 0L,
                deadlineAt = 0L,
                currentStatus = "",
                pausedReason = "",
                emergencyCheckInId = ""
            )
            writeState(context, initial)
            scheduleReminder(context, initial)
        }
    }

    fun cancelForJourney(context: Context, uid: String, journeyId: String) {
        synchronized(stateLock) {
            val state = readState(context) ?: return
            if (state.uid != uid || state.journeyId != journeyId) return
            cancelAlarms(context, state)
            clearState(context)
            if (state.currentCheckInId.isNotBlank()) SafetyReminderNotifications.cancel(context, state.currentCheckInId)
            if (state.emergencyCheckInId.isNotBlank()) {
                SafetyReminderNotifications.cancel(context, state.emergencyCheckInId)
            }
        }
    }

    fun cancelForUser(context: Context, uid: String) {
        synchronized(stateLock) {
            val state = readState(context) ?: return
            if (state.uid != uid) return
            cancelAlarms(context, state)
            clearState(context)
            if (state.currentCheckInId.isNotBlank()) SafetyReminderNotifications.cancel(context, state.currentCheckInId)
            if (state.emergencyCheckInId.isNotBlank()) SafetyReminderNotifications.cancel(context, state.emergencyCheckInId)
        }
    }

    fun resumeIfNotificationPermissionGranted(context: Context, uid: String) {
        if (!notificationsAllowed(context)) return
        synchronized(stateLock) {
            val state = readState(context) ?: return
            if (state.uid == uid && state.pausedReason == "notification_permission") {
                scheduleNext(context, state.copy(pausedReason = ""))
            }
        }
    }

    fun resumeAfterHelp(context: Context, uid: String, journeyId: String): Boolean {
        synchronized(stateLock) {
            val state = readState(context) ?: return false
            if (state.uid != uid || state.journeyId != journeyId || state.pausedReason != "user_requested_help") return false
            scheduleNext(context, state.copy(pausedReason = ""))
            return true
        }
    }

    fun currentState(context: Context): State? = synchronized(stateLock) { readState(context) }

    fun pendingCheckIn(context: Context, uid: String, checkInId: String): State? {
        val state = currentState(context) ?: return null
        return state.takeIf {
            it.uid == uid && it.currentCheckInId == checkInId && it.currentStatus == "pending"
        }
    }

    fun pendingEmergency(context: Context, uid: String): State? {
        val state = currentState(context) ?: return null
        return state.takeIf { it.uid == uid && it.emergencyCheckInId.isNotBlank() }
    }

    /** Called by the reminder receiver before it displays a prompt or notification. */
    fun beginCheckIn(context: Context, uid: String, journeyId: String, checkInId: String): State? {
        synchronized(stateLock) {
            val state = readState(context) ?: return null
            if (state.uid != uid || state.journeyId != journeyId || state.nextCheckInId != checkInId || state.pausedReason.isNotBlank()) {
                return null
            }
            val now = System.currentTimeMillis()
            if (state.nextReminderAt > now) {
                scheduleReminder(context, state)
                return null
            }
            if (!notificationsAllowed(context) && !SafetyCheckEvents.hasVisibleListener()) {
                writeState(context, state.copy(nextCheckInId = "", nextReminderAt = 0L, pausedReason = "notification_permission"))
                return null
            }

            val deadline = now + SAFETY_RESPONSE_WINDOW_MS
            val pending = state.copy(
                nextCheckInId = "",
                nextReminderAt = 0L,
                currentCheckInId = checkInId,
                currentCreatedAt = now,
                deadlineAt = deadline,
                currentStatus = "pending"
            )
            writeState(context, pending)
            scheduleTimeout(context, pending)
            return pending
        }
    }

    fun respond(context: Context, uid: String, journeyId: String, checkInId: String, response: String): ResponseResult {
        if (response != "safe" && response != "help") return ResponseResult.NOT_ACTIVE
        synchronized(stateLock) {
            val state = readState(context) ?: return ResponseResult.NOT_ACTIVE
            if (state.uid != uid || state.journeyId != journeyId || state.currentCheckInId != checkInId || state.currentStatus != "pending") {
                return ResponseResult.NOT_ACTIVE
            }
            if (System.currentTimeMillis() >= state.deadlineAt) {
                expirePendingCheckIn(context, state)
                return ResponseResult.TOO_LATE
            }

            val respondedAt = System.currentTimeMillis()
            cancelTimeout(context, state)
            saveCheckIn(
                context,
                state,
                response = response,
                status = "responded",
                respondedAt = respondedAt,
                emergencyTriggered = false
            )
            val finished = state.copy(
                currentCheckInId = "",
                currentCreatedAt = 0L,
                deadlineAt = 0L,
                currentStatus = ""
            )
            if (response == "safe") {
                scheduleNext(context, finished.copy(pausedReason = ""))
            } else {
                writeState(context, finished.copy(pausedReason = "user_requested_help"))
            }
            return ResponseResult.RECORDED
        }
    }

    /** Returns true only for the first transition from pending to timed out. */
    fun expireIfDue(context: Context, uid: String, journeyId: String, checkInId: String): Boolean {
        synchronized(stateLock) {
            val state = readState(context) ?: return false
            if (state.uid != uid || state.journeyId != journeyId || state.currentCheckInId != checkInId || state.currentStatus != "pending") return false
            if (System.currentTimeMillis() < state.deadlineAt) {
                scheduleTimeout(context, state)
                return false
            }
            expirePendingCheckIn(context, state)
            return true
        }
    }

    /** Recreates the one pending alarm after a reboot, app update, or permission grant. */
    fun restore(context: Context) {
        synchronized(stateLock) {
            val state = readState(context) ?: return
            if (state.currentStatus == "pending" && state.currentCheckInId.isNotBlank()) {
                if (System.currentTimeMillis() >= state.deadlineAt) {
                    expirePendingCheckIn(context, state)
                    if (!SafetyCheckEvents.hasVisibleListener()) {
                        SafetyReminderNotifications.showEmergency(context, state.uid, state.journeyId, state.currentCheckInId)
                    }
                } else {
                    scheduleTimeout(context, state)
                    if (!SafetyCheckEvents.hasVisibleListener()) {
                        SafetyReminderNotifications.showCheckIn(context, state.uid, state.journeyId, state.currentCheckInId)
                    }
                }
            } else if (state.pausedReason.isBlank() && state.nextCheckInId.isNotBlank()) {
                scheduleReminder(context, state)
            } else if (state.pausedReason == "emergency_triggered" && state.emergencyCheckInId.isNotBlank()) {
                SafetyReminderNotifications.showEmergency(context, state.uid, state.journeyId, state.emergencyCheckInId)
            }
        }
    }

    fun checkInStatus(context: Context, checkInId: String): String? {
        val state = currentState(context) ?: return null
        return when {
            state.currentCheckInId == checkInId -> state.currentStatus
            state.emergencyCheckInId == checkInId -> "timed_out"
            else -> null
        }
    }

    private fun expirePendingCheckIn(context: Context, state: State) {
        cancelTimeout(context, state)
        val emergency = state.copy(
            currentCheckInId = "",
            currentCreatedAt = 0L,
            deadlineAt = 0L,
            currentStatus = "",
            nextCheckInId = "",
            nextReminderAt = 0L,
            pausedReason = "emergency_triggered",
            emergencyCheckInId = state.currentCheckInId
        )
        writeState(context, emergency)
        saveCheckIn(
            context,
            state,
            response = "no_response",
            status = "timed_out",
            respondedAt = System.currentTimeMillis(),
            emergencyTriggered = true,
            alertReason = "missed_check_in"
        )
    }

    private fun scheduleNext(context: Context, previous: State) {
        val next = previous.copy(
            sequence = previous.sequence + 1,
            nextCheckInId = UUID.randomUUID().toString(),
            nextReminderAt = System.currentTimeMillis() + previous.intervalMinutes * 60_000L,
            currentCheckInId = "",
            currentCreatedAt = 0L,
            deadlineAt = 0L,
            currentStatus = "",
            pausedReason = "",
            emergencyCheckInId = ""
        )
        writeState(context, next)
        scheduleReminder(context, next)
    }

    private fun saveCheckIn(
        context: Context,
        state: State,
        response: String,
        status: String,
        respondedAt: Long,
        emergencyTriggered: Boolean,
        alertReason: String? = null
    ) {
        SafetyCheckInSync.save(
            context = context,
            uid = state.uid,
            journeyId = state.journeyId,
            checkInId = state.currentCheckInId,
            response = response,
            status = status,
            createdAtMillis = state.currentCreatedAt,
            deadlineAtMillis = state.deadlineAt,
            respondedAtMillis = respondedAt,
            emergencyTriggered = emergencyTriggered,
            alertReason = alertReason
        )
    }

    private fun scheduleReminder(context: Context, state: State) {
        scheduleAlarm(
            context,
            SafetyReminderReceiver.ACTION_REMINDER,
            state.uid,
            state.journeyId,
            state.nextCheckInId,
            state.nextReminderAt
        )
    }

    private fun scheduleTimeout(context: Context, state: State) {
        scheduleAlarm(
            context,
            SafetyReminderReceiver.ACTION_TIMEOUT,
            state.uid,
            state.journeyId,
            state.currentCheckInId,
            state.deadlineAt
        )
    }

    private fun scheduleAlarm(context: Context, action: String, uid: String, journeyId: String, checkInId: String, at: Long) {
        if (checkInId.isBlank() || at <= 0L) return
        val intent = alarmIntent(context, action, uid, journeyId, checkInId)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            checkInId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = at.coerceAtLeast(System.currentTimeMillis() + 500L)
        val alarms = alarmManager(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (canScheduleExactAlarms(context)) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } else {
                alarms.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (exception: SecurityException) {
            Log.w(TAG, "Exact reminder access was unavailable; scheduling a flexible alarm", exception)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun cancelAlarms(context: Context, state: State) {
        if (state.nextCheckInId.isNotBlank()) cancelAlarm(context, SafetyReminderReceiver.ACTION_REMINDER, state.uid, state.journeyId, state.nextCheckInId)
        if (state.currentCheckInId.isNotBlank()) cancelTimeout(context, state)
    }

    private fun cancelTimeout(context: Context, state: State) {
        if (state.currentCheckInId.isNotBlank()) {
            cancelAlarm(context, SafetyReminderReceiver.ACTION_TIMEOUT, state.uid, state.journeyId, state.currentCheckInId)
        }
    }

    private fun cancelAlarm(context: Context, action: String, uid: String, journeyId: String, checkInId: String) {
        val intent = alarmIntent(context, action, uid, journeyId, checkInId)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            checkInId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager(context).cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun alarmIntent(context: Context, action: String, uid: String, journeyId: String, checkInId: String) =
        Intent(context, SafetyReminderReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("saferoute://alarm/$action/$uid/$journeyId/$checkInId")
            putExtra(EXTRA_UID, uid)
            putExtra(EXTRA_JOURNEY_ID, journeyId)
            putExtra(EXTRA_CHECK_IN_ID, checkInId)
        }

    private fun readState(context: Context): State? {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uid = p.getString(KEY_UID, null) ?: return null
        val journeyId = p.getString(KEY_JOURNEY_ID, null) ?: return null
        return State(
            uid = uid,
            journeyId = journeyId,
            intervalMinutes = p.getInt(KEY_INTERVAL, 15),
            generation = p.getInt(KEY_GENERATION, 1),
            sequence = p.getInt(KEY_SEQUENCE, 1),
            nextCheckInId = p.getString(KEY_NEXT_CHECK_ID, "").orEmpty(),
            nextReminderAt = p.getLong(KEY_NEXT_REMINDER_AT, 0L),
            currentCheckInId = p.getString(KEY_CURRENT_CHECK_ID, "").orEmpty(),
            currentCreatedAt = p.getLong(KEY_CURRENT_CREATED_AT, 0L),
            deadlineAt = p.getLong(KEY_DEADLINE_AT, 0L),
            currentStatus = p.getString(KEY_CURRENT_STATUS, "").orEmpty(),
            pausedReason = p.getString(KEY_PAUSED_REASON, "").orEmpty(),
            emergencyCheckInId = p.getString(KEY_EMERGENCY_CHECK_ID, "").orEmpty()
        )
    }

    private fun writeState(context: Context, s: State) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_UID, s.uid)
            .putString(KEY_JOURNEY_ID, s.journeyId)
            .putInt(KEY_INTERVAL, s.intervalMinutes)
            .putInt(KEY_GENERATION, s.generation)
            .putInt(KEY_SEQUENCE, s.sequence)
            .putString(KEY_NEXT_CHECK_ID, s.nextCheckInId)
            .putLong(KEY_NEXT_REMINDER_AT, s.nextReminderAt)
            .putString(KEY_CURRENT_CHECK_ID, s.currentCheckInId)
            .putLong(KEY_CURRENT_CREATED_AT, s.currentCreatedAt)
            .putLong(KEY_DEADLINE_AT, s.deadlineAt)
            .putString(KEY_CURRENT_STATUS, s.currentStatus)
            .putString(KEY_PAUSED_REASON, s.pausedReason)
            .putString(KEY_EMERGENCY_CHECK_ID, s.emergencyCheckInId)
            .commit()
    }

    private fun clearState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    const val EXTRA_UID = "safety_uid"
    const val EXTRA_JOURNEY_ID = "safety_journey_id"
    const val EXTRA_CHECK_IN_ID = "safety_check_in_id"
    const val SAFETY_RESPONSE_WINDOW_MS = 60_000L
}
