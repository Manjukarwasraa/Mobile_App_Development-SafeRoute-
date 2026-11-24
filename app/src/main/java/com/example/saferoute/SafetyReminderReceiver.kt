package com.example.saferoute

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SafetyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val uid = intent.getStringExtra(SafetyReminderScheduler.EXTRA_UID) ?: return
        val journeyId = intent.getStringExtra(SafetyReminderScheduler.EXTRA_JOURNEY_ID) ?: return
        val checkInId = intent.getStringExtra(SafetyReminderScheduler.EXTRA_CHECK_IN_ID) ?: return

        when (intent.action) {
            ACTION_REMINDER -> {
                val checkIn = SafetyReminderScheduler.beginCheckIn(context, uid, journeyId, checkInId) ?: return
                if (!SafetyCheckEvents.dispatchReminder(checkInId, journeyId)) {
                    SafetyReminderNotifications.showCheckIn(context, uid, journeyId, checkInId)
                }
                Log.i(TAG, "Local safety reminder started for journey $journeyId")
            }

            ACTION_TIMEOUT -> {
                if (!SafetyReminderScheduler.expireIfDue(context, uid, journeyId, checkInId)) return
                if (!SafetyCheckEvents.dispatchEmergency(checkInId, journeyId)) {
                    SafetyReminderNotifications.showEmergency(context, uid, journeyId, checkInId)
                }
                Log.i(TAG, "Local check-in timeout recorded for journey $journeyId")
            }
        }
    }

    companion object {
        const val ACTION_REMINDER = "com.example.saferoute.action.SAFETY_REMINDER"
        const val ACTION_TIMEOUT = "com.example.saferoute.action.SAFETY_TIMEOUT"
        private const val TAG = "SafetyReminderReceiver"
    }
}
