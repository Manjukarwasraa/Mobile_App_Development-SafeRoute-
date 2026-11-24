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

internal object ArrivalNotifications {
    private const val CHANNEL_ID = "expected_arrival_monitoring"
    private const val CHANNEL_NAME = "Expected arrival checks"
    private const val NOTIFICATION_TAG = "expected_arrival"
    private const val TAG = "ArrivalNotifications"

    fun showPrompt(context: Context, state: ExpectedArrivalScheduler.State) {
        createChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notificationId = state.token.hashCode()
        val promptContentIntent = PendingIntent.getActivity(
            context,
            state.token.hashCode() xor 303,
            Intent(context, JourneyActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(JourneyActivity.EXTRA_JOURNEY_ID, state.journeyId)
                putExtra(ExpectedArrivalScheduler.EXTRA_TOKEN, state.token)
                data = Uri.parse("saferoute://arrival/prompt/${state.uid}/${state.journeyId}/${state.token}")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val yesIntent = receiverPendingIntent(context, state, ExpectedArrivalScheduler.ACTION_CONFIRM)
        val extensionIntent = PendingIntent.getActivity(
            context,
            state.token.hashCode() xor 101,
            Intent(context, ArrivalExtensionActivity::class.java).apply {
                putExtra(ExpectedArrivalScheduler.EXTRA_UID, state.uid)
                putExtra(ExpectedArrivalScheduler.EXTRA_JOURNEY_ID, state.journeyId)
                putExtra(ExpectedArrivalScheduler.EXTRA_TOKEN, state.token)
                data = Uri.parse("saferoute://arrival/extend/${state.uid}/${state.journeyId}/${state.token}")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_safety)
            .setContentTitle(context.getString(R.string.arrival_notification_title))
            .setContentText(context.getString(R.string.arrival_notification_message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.arrival_notification_message)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(promptContentIntent)
            .addAction(R.drawable.ic_notification_safety, context.getString(R.string.arrival_action_yes), yesIntent)
            .addAction(R.drawable.ic_notification_safety, context.getString(R.string.arrival_action_not_yet), extensionIntent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        }
        post(context, notificationId, builder.build())
    }

    fun showMissed(context: Context, state: ExpectedArrivalScheduler.State) {
        createChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val openIntent = PendingIntent.getActivity(
            context,
            state.token.hashCode() xor 202,
            Intent(context, JourneyActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(JourneyActivity.EXTRA_JOURNEY_ID, state.journeyId)
                data = Uri.parse("saferoute://arrival/missed/${state.uid}/${state.journeyId}/${state.token}")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = context.getString(R.string.arrival_missed_notification_message)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_safety)
            .setContentTitle(context.getString(R.string.arrival_missed_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_notification_safety, context.getString(R.string.arrival_action_share), openIntent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        }
        post(context, state.token.hashCode(), builder.build())
    }

    fun cancel(context: Context, token: String) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, token.hashCode())
    }

    private fun post(context: Context, notificationId: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, notificationId, notification)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Notification permission is unavailable", exception)
        }
    }

    private fun receiverPendingIntent(context: Context, state: ExpectedArrivalScheduler.State, action: String) =
        PendingIntent.getBroadcast(
            context,
            state.token.hashCode() xor action.hashCode(),
            Intent(context, ExpectedArrivalReceiver::class.java).apply {
                this.action = action
                putExtra(ExpectedArrivalScheduler.EXTRA_UID, state.uid)
                putExtra(ExpectedArrivalScheduler.EXTRA_JOURNEY_ID, state.journeyId)
                putExtra(ExpectedArrivalScheduler.EXTRA_TOKEN, state.token)
                data = Uri.parse("saferoute://arrival-action/$action/${state.uid}/${state.journeyId}/${state.token}")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.arrival_notification_channel_description)
            setSound(sound, audio)
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
