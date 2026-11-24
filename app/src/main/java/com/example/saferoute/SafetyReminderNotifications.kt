package com.example.saferoute

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

internal object SafetyReminderNotifications {
    private const val CHANNEL_ID = "journey_safety_reminders"
    private const val CHANNEL_NAME = "Journey safety reminders"
    private const val TAG = "SafetyReminderNotice"
    private const val EXTRA_EMERGENCY = "safety_emergency_alert"

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun showCheckIn(context: Context, uid: String, journeyId: String, checkInId: String) {
        post(context, uid, journeyId, checkInId, emergency = false)
    }

    fun showEmergency(context: Context, uid: String, journeyId: String, checkInId: String) {
        post(context, uid, journeyId, checkInId, emergency = true)
    }

    fun cancel(context: Context, checkInId: String) {
        NotificationManagerCompat.from(context).cancel(checkInId.hashCode())
    }

    private fun post(context: Context, uid: String, journeyId: String, checkInId: String, emergency: Boolean) {
        createChannel(context)
        if (!notificationsAllowed(context)) return

        val intent = Intent(context, JourneyActivity::class.java).apply {
            putExtra(JourneyActivity.EXTRA_CHECK_IN_ID, checkInId)
            putExtra(JourneyActivity.EXTRA_JOURNEY_ID, journeyId)
            putExtra(EXTRA_UID, uid)
            if (emergency) putExtra(EXTRA_EMERGENCY, true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("saferoute://notification/$uid/$journeyId/$checkInId/$emergency")
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            checkInId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (emergency) "SafeRoute emergency alert" else "SafeRoute safety check"
        val body = if (emergency) {
            "No response was recorded. Tap to review and share your emergency message manually."
        } else {
            "SafeRoute: Are you safe? Please confirm your journey."
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_safety)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)

        if (emergency) {
            builder.addAction(
                R.drawable.ic_notification_safety,
                "Share emergency message",
                contentIntent
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        }

        try {
            NotificationManagerCompat.from(context).notify(checkInId.hashCode(), builder.build())
        } catch (exception: SecurityException) {
            Log.w(TAG, "Notification permission was denied", exception)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Sound alerts for automatic journey safety check-ins"
            setSound(sound, attributes)
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private const val EXTRA_UID = SafetyReminderScheduler.EXTRA_UID
}
