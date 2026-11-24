package com.example.saferoute

import android.os.Handler
import android.os.Looper

/** Delivers local reminder and timeout events to JourneyActivity while it is visible. */
object SafetyCheckEvents {
    @Volatile
    private var listener: ((SafetyCheckEvent) -> Unit)? = null

    data class SafetyCheckEvent(
        val checkInId: String,
        val journeyId: String,
        val emergency: Boolean
    )

    fun register(callback: (SafetyCheckEvent) -> Unit) {
        listener = callback
    }

    fun unregister() {
        listener = null
    }

    fun hasVisibleListener(): Boolean = listener != null

    fun dispatchReminder(checkInId: String, journeyId: String): Boolean =
        dispatch(SafetyCheckEvent(checkInId, journeyId, emergency = false))

    fun dispatchEmergency(checkInId: String, journeyId: String): Boolean =
        dispatch(SafetyCheckEvent(checkInId, journeyId, emergency = true))

    private fun dispatch(event: SafetyCheckEvent): Boolean {
        val currentListener = listener ?: return false
        Handler(Looper.getMainLooper()).post {
            if (listener === currentListener) currentListener(event)
        }
        return true
    }
}
