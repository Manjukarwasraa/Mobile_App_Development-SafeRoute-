package com.example.saferoute

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class ExpectedArrivalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val uid = intent.getStringExtra(ExpectedArrivalScheduler.EXTRA_UID) ?: return
        val journeyId = intent.getStringExtra(ExpectedArrivalScheduler.EXTRA_JOURNEY_ID) ?: return
        val token = intent.getStringExtra(ExpectedArrivalScheduler.EXTRA_TOKEN) ?: return
        when (intent.action) {
            ExpectedArrivalScheduler.ACTION_ARRIVAL ->
                ExpectedArrivalScheduler.handleArrival(context, uid, journeyId, token)
            ExpectedArrivalScheduler.ACTION_TIMEOUT ->
                ExpectedArrivalScheduler.handleTimeout(context, uid, journeyId, token)
            ExpectedArrivalScheduler.ACTION_CONFIRM -> {
                val pendingResult = goAsync()
                val finished = AtomicBoolean(false)
                val finishOnce = Runnable {
                    if (finished.compareAndSet(false, true)) pendingResult.finish()
                }
                val handler = Handler(Looper.getMainLooper())
                handler.postDelayed(finishOnce, 8000L)
                ExpectedArrivalScheduler.confirmArrival(context, uid, journeyId, token) {
                    handler.removeCallbacks(finishOnce)
                    finishOnce.run()
                }
            }
        }
    }
}
