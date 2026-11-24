package com.example.saferoute

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Source
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JourneyHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "JourneyHistory"
    }

    private lateinit var progressBar: ProgressBar
    private lateinit var messageText: TextView
    private lateinit var journeyList: LinearLayout
    private lateinit var journeyCountText: TextView
    private lateinit var emptyState: View
    private lateinit var summaryCard: View
    private lateinit var timelineDetails: View
    private lateinit var timelineProgress: ProgressBar
    private lateinit var timelineMessage: TextView
    private lateinit var timelineEmpty: TextView
    private lateinit var timelineEvents: LinearLayout
    private lateinit var timelineRoute: TextView
    private var selectedTimelineJourney: Journey? = null
    private var historyIsEmpty = false
    private var historyUserId: String? = null

    private enum class TimelineKind { STARTED, REMINDER, SAFE, HELP, MISSED, COMPLETED }

    private data class TimelineEvent(
        val kind: TimelineKind,
        val timestamp: Timestamp?,
        val order: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journey_history)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbarJourneyHistory)
        toolbar.setNavigationOnClickListener { showHistoryOverviewOrFinish() }
        progressBar = findViewById(R.id.progressJourneyHistory)
        messageText = findViewById(R.id.tvJourneyHistoryMessage)
        journeyList = findViewById(R.id.listJourneyHistory)
        journeyCountText = findViewById(R.id.tvJourneyHistoryCount)
        emptyState = findViewById(R.id.layoutJourneyHistoryEmpty)
        summaryCard = findViewById(R.id.cardJourneyHistorySummary)
        timelineDetails = findViewById(R.id.layoutJourneyTimelineDetails)
        timelineProgress = findViewById(R.id.progressJourneyTimeline)
        timelineMessage = findViewById(R.id.tvJourneyTimelineMessage)
        timelineEmpty = findViewById(R.id.tvJourneyTimelineEmpty)
        timelineEvents = findViewById(R.id.listJourneyTimelineEvents)
        timelineRoute = findViewById(R.id.tvTimelineJourneyRoute)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = showHistoryOverviewOrFinish()
        })

        loadJourneyHistory()
    }

    override fun onResume() {
        super.onResume()
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid != historyUserId) {
            historyUserId = currentUid
            journeyList.removeAllViews()
            journeyCountText.text = getString(R.string.journey_history_count, 0)
            historyIsEmpty = false
            selectedTimelineJourney = null
            timelineDetails.visibility = View.GONE
            journeyList.visibility = View.VISIBLE
            summaryCard.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            messageText.visibility = View.GONE
            if (currentUid == null) {
                showMessage(getString(R.string.journey_history_load_failed))
            } else {
                loadJourneyHistory()
            }
        }
    }

    private fun loadJourneyHistory() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            showMessage(getString(R.string.journey_history_load_failed))
            return
        }
        historyUserId = userId

        progressBar.visibility = View.VISIBLE
        messageText.text = getString(R.string.journey_history_loading)
        messageText.visibility = View.VISIBLE
        journeyList.removeAllViews()
        emptyState.visibility = View.GONE

        FirebaseFirestore.getInstance()
            .collection("users")
            .document(userId)
            .collection("journeys")
            .whereEqualTo("status", "completed")
            .get()
            .addOnSuccessListener { snapshot ->
                if (FirebaseAuth.getInstance().currentUser?.uid != userId) return@addOnSuccessListener
                progressBar.visibility = View.GONE
                messageText.visibility = View.GONE
                val journeys = snapshot.documents.mapNotNull { document ->
                    document.toObject(Journey::class.java)?.copy(documentId = document.id)
                }
                    .sortedByDescending { journey ->
                        (journey.completedAt ?: journey.startedAt)?.toDate()?.time ?: 0L
                    }

                journeyCountText.text = getString(R.string.journey_history_count, journeys.size)
                historyIsEmpty = journeys.isEmpty()
                if (journeys.isEmpty()) {
                    emptyState.visibility = View.VISIBLE
                } else {
                    journeys.forEach { journey -> addJourneyCard(journey) }
                }
            }
            .addOnFailureListener { exception ->
                progressBar.visibility = View.GONE
                val errorCode = (exception as? FirebaseFirestoreException)?.code?.name
                    ?: exception.javaClass.simpleName
                val details = exception.localizedMessage
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.error_generic)
                Log.e("JourneyHistory", "Firestore read failed ($errorCode): $details", exception)
                showMessage("Could not load journey history ($errorCode): $details")
            }
    }

    private fun addJourneyCard(journey: Journey) {
        val card = layoutInflater.inflate(R.layout.item_journey_history, journeyList, false)

        val journeyDate = journey.completedAt ?: journey.startedAt
        val formattedDate = journeyDate?.toDate()?.let(::formatDate)
            ?: getString(R.string.journey_history_date_unavailable)

        card.findViewById<TextView>(R.id.tvHistoryFrom).text = journey.from
        card.findViewById<TextView>(R.id.tvHistoryTo).text = journey.to
        card.findViewById<TextView>(R.id.tvHistoryMode).text = journey.travelMode
        card.findViewById<TextView>(R.id.tvHistoryDate).text = "🕒  $formattedDate"
        card.findViewById<TextView>(R.id.tvHistoryStatus).text =
            journey.status.replaceFirstChar { it.uppercase() }
        val deleteButton = card.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteJourney)
        deleteButton.setOnClickListener { showDeleteConfirmation(journey, deleteButton) }
        card.isClickable = true
        card.isFocusable = true
        card.contentDescription = getString(R.string.journey_history_open_timeline, journey.from, journey.to)
        card.setOnClickListener { showTimeline(journey) }
        journeyList.addView(card)
    }

    private fun showDeleteConfirmation(
        journey: Journey,
        deleteButton: com.google.android.material.button.MaterialButton
    ) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_journey_title))
            .setMessage(getString(R.string.delete_journey_confirmation))
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                deleteButton.isEnabled = false
                deleteButton.text = getString(R.string.delete_journey_in_progress)
                deleteCompletedJourney(journey, deleteButton)
            }
            .show()
    }

    private fun deleteCompletedJourney(
        journey: Journey,
        deleteButton: com.google.android.material.button.MaterialButton
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null || uid != historyUserId) {
            finishDeleteWithError(deleteButton, getString(R.string.journey_history_load_failed))
            return
        }

        val firestore = FirebaseFirestore.getInstance()
        val journeyRef = firestore.collection("users").document(uid)
            .collection("journeys").document(journey.documentId)
        journeyRef.get(Source.SERVER)
            .addOnSuccessListener { document ->
                if (FirebaseAuth.getInstance().currentUser?.uid != uid) return@addOnSuccessListener
                if (document.exists() && document.getString("status") != "completed") {
                    finishDeleteWithError(deleteButton, getString(R.string.delete_journey_completed_only))
                    return@addOnSuccessListener
                }

                firestore.collection("users").document(uid)
                    .collection("safetyCheckIns")
                    .whereEqualTo("journeyId", journey.documentId)
                    .get(Source.SERVER)
                    .addOnSuccessListener { snapshot ->
                        val matchingRecords = snapshot.documents.filter {
                            it.getString("journeyId") == journey.documentId
                        }
                        deleteSafetyRecords(matchingRecords, 0, deleteButton) {
                            journeyRef.get(Source.SERVER)
                                .addOnSuccessListener { latestJourney ->
                                    if (latestJourney.exists() && latestJourney.getString("status") != "completed") {
                                        finishDeleteWithError(deleteButton, getString(R.string.delete_journey_completed_only))
                                        return@addOnSuccessListener
                                    }
                                    journeyRef.delete()
                                        .addOnSuccessListener {
                                            if (FirebaseAuth.getInstance().currentUser?.uid != uid) return@addOnSuccessListener
                                            ExpectedArrivalScheduler.cancelForJourney(this, uid, journey.documentId)
                                            SafetyReminderScheduler.cancelForJourney(this, uid, journey.documentId)
                                            deleteButton.isEnabled = false
                                            journeyList.removeAllViews()
                                            loadJourneyHistory()
                                            com.google.android.material.snackbar.Snackbar.make(
                                                journeyList,
                                                getString(R.string.delete_journey_success),
                                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                                            ).show()
                                        }
                                        .addOnFailureListener { exception ->
                                            finishDeleteWithError(deleteButton, deletionErrorMessage(exception))
                                        }
                                }
                                .addOnFailureListener { exception ->
                                    finishDeleteWithError(deleteButton, deletionErrorMessage(exception))
                                }
                        }
                    }
                    .addOnFailureListener { exception ->
                        finishDeleteWithError(deleteButton, deletionErrorMessage(exception))
                    }
            }
            .addOnFailureListener { exception ->
                finishDeleteWithError(deleteButton, deletionErrorMessage(exception))
            }
    }

    private fun deleteSafetyRecords(
        records: List<DocumentSnapshot>,
        startIndex: Int,
        deleteButton: com.google.android.material.button.MaterialButton,
        onComplete: () -> Unit
    ) {
        if (startIndex >= records.size) {
            onComplete()
            return
        }
        val endIndex = minOf(startIndex + 450, records.size)
        val batch = FirebaseFirestore.getInstance().batch()
        records.subList(startIndex, endIndex).forEach { record -> batch.delete(record.reference) }
        batch.commit()
            .addOnSuccessListener { deleteSafetyRecords(records, endIndex, deleteButton, onComplete) }
            .addOnFailureListener { exception ->
                finishDeleteWithError(deleteButton, deletionErrorMessage(exception))
            }
    }

    private fun finishDeleteWithError(
        deleteButton: com.google.android.material.button.MaterialButton,
        message: String
    ) {
        deleteButton.isEnabled = true
        deleteButton.text = getString(R.string.btn_delete)
        com.google.android.material.snackbar.Snackbar.make(
            journeyList,
            message,
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).show()
    }

    private fun deletionErrorMessage(exception: Exception): String =
        getString(R.string.delete_journey_failed, exception.localizedMessage ?: getString(R.string.error_generic))

    private fun showTimeline(journey: Journey) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null || uid != historyUserId) {
            selectedTimelineJourney = null
            summaryCard.visibility = View.GONE
            emptyState.visibility = View.GONE
            journeyList.visibility = View.GONE
            messageText.visibility = View.GONE
            timelineEvents.removeAllViews()
            timelineEmpty.visibility = View.GONE
            timelineRoute.text = ""
            showTimelineError(getString(R.string.journey_history_load_failed))
            return
        }

        selectedTimelineJourney = journey
        summaryCard.visibility = View.GONE
        emptyState.visibility = View.GONE
        journeyList.visibility = View.GONE
        messageText.visibility = View.GONE
        timelineDetails.visibility = View.VISIBLE
        timelineRoute.text = getString(R.string.safety_timeline_route, journey.from, journey.to)
        timelineEvents.removeAllViews()
        timelineEmpty.visibility = View.GONE
        timelineMessage.text = getString(R.string.safety_timeline_loading)
        timelineMessage.visibility = View.VISIBLE
        timelineProgress.visibility = View.VISIBLE

        val events = mutableListOf<TimelineEvent>()
        events.add(TimelineEvent(TimelineKind.STARTED, journey.startedAt, 0))
        if (journey.status == "completed") {
            events.add(TimelineEvent(TimelineKind.COMPLETED, journey.completedAt, 5))
        }

        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .collection("safetyCheckIns")
            .whereEqualTo("journeyId", journey.documentId)
            .get()
            .addOnSuccessListener { snapshot ->
                if (selectedTimelineJourney?.documentId != journey.documentId) return@addOnSuccessListener
                if (FirebaseAuth.getInstance().currentUser?.uid != uid) {
                    timelineProgress.visibility = View.GONE
                    timelineMessage.text = getString(R.string.journey_history_load_failed)
                    timelineMessage.visibility = View.VISIBLE
                    return@addOnSuccessListener
                }

                var checkInEventCount = 0
                snapshot.documents.forEach { document ->
                    if (document.getString("journeyId") != journey.documentId) return@forEach
                    val checkInId = document.getString("checkInId")
                    val reminderAt = document.getTimestamp("createdAt")
                    // Automatic check-ins have a stable ID and their trigger time in createdAt.
                    // Older/manual records without both fields must not be assigned a guessed time.
                    if (!checkInId.isNullOrBlank() && reminderAt != null) {
                        events.add(TimelineEvent(TimelineKind.REMINDER, reminderAt, 1))
                    }

                    val response = document.getString("response")?.lowercase(Locale.ROOT)
                    val responseAt = document.getTimestamp("respondedAt")
                        ?: document.getTimestamp("recordedAt")
                    val responseKind = when (response) {
                        "safe" -> TimelineKind.SAFE
                        "help" -> TimelineKind.HELP
                        "no_response" -> TimelineKind.MISSED
                        else -> null
                    }
                    if (responseKind != null) {
                        checkInEventCount++
                        events.add(TimelineEvent(responseKind, responseAt, 2))
                    }
                }

                timelineProgress.visibility = View.GONE
                timelineMessage.visibility = View.GONE
                timelineEmpty.visibility = if (checkInEventCount == 0) View.VISIBLE else View.GONE
                renderTimeline(events)
            }
            .addOnFailureListener { exception ->
                if (selectedTimelineJourney?.documentId != journey.documentId) return@addOnFailureListener
                val code = (exception as? FirebaseFirestoreException)?.code?.name ?: exception.javaClass.simpleName
                Log.e(TAG, "Could not load safety timeline for journey ${journey.documentId} ($code)", exception)
                timelineProgress.visibility = View.GONE
                timelineMessage.text = getString(R.string.safety_timeline_load_failed)
                timelineMessage.visibility = View.VISIBLE
                timelineEmpty.visibility = View.GONE
                renderTimeline(events)
            }
    }

    private fun renderTimeline(events: List<TimelineEvent>) {
        timelineEvents.removeAllViews()
        val orderedEvents = events.sortedWith(
            compareBy<TimelineEvent> { it.timestamp?.toDate()?.time ?: Long.MAX_VALUE }
                .thenBy { it.order }
        )
        orderedEvents.forEachIndexed { index, event ->
            val row = layoutInflater.inflate(R.layout.item_safety_timeline_event, timelineEvents, false)
            val icon = row.findViewById<TextView>(R.id.tvTimelineEventIcon)
            val connector = row.findViewById<View>(R.id.viewTimelineConnector)
            val title = row.findViewById<TextView>(R.id.tvTimelineEventTitle)
            val time = row.findViewById<TextView>(R.id.tvTimelineEventTime)
            val description = row.findViewById<TextView>(R.id.tvTimelineEventDescription)

            val eventText = when (event.kind) {
                TimelineKind.STARTED -> Triple("▶", R.string.safety_timeline_started_title, R.string.safety_timeline_started_description)
                TimelineKind.REMINDER -> Triple("•", R.string.safety_timeline_reminder_title, R.string.safety_timeline_reminder_description)
                TimelineKind.SAFE -> Triple("✓", R.string.safety_timeline_safe_title, R.string.safety_timeline_safe_description)
                TimelineKind.HELP -> Triple("!", R.string.safety_timeline_help_title, R.string.safety_timeline_help_description)
                TimelineKind.MISSED -> Triple("×", R.string.safety_timeline_missed_title, R.string.safety_timeline_missed_description)
                TimelineKind.COMPLETED -> Triple("■", R.string.safety_timeline_completed_title, R.string.safety_timeline_completed_description)
            }
            val color = when (event.kind) {
                TimelineKind.STARTED -> ContextCompat.getColor(this, R.color.profileBlue)
                TimelineKind.REMINDER -> ContextCompat.getColor(this, R.color.colorWarning)
                TimelineKind.SAFE -> ContextCompat.getColor(this, R.color.colorSuccess)
                TimelineKind.HELP, TimelineKind.MISSED -> ContextCompat.getColor(this, R.color.colorError)
                TimelineKind.COMPLETED -> ContextCompat.getColor(this, R.color.profileNavy)
            }
            icon.text = eventText.first
            icon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            title.text = getString(eventText.second)
            time.text = event.timestamp?.toDate()?.let(::formatDate)
                ?: getString(R.string.safety_timeline_time_unavailable)
            description.text = getString(eventText.third)
            connector.visibility = if (index == orderedEvents.lastIndex) View.INVISIBLE else View.VISIBLE
            timelineEvents.addView(row)
        }
    }

    private fun showTimelineError(message: String) {
        timelineDetails.visibility = View.VISIBLE
        timelineMessage.text = message
        timelineMessage.visibility = View.VISIBLE
        timelineProgress.visibility = View.GONE
    }

    private fun showHistoryOverviewOrFinish() {
        if (timelineDetails.visibility == View.VISIBLE) {
            selectedTimelineJourney = null
            timelineDetails.visibility = View.GONE
            summaryCard.visibility = View.VISIBLE
            emptyState.visibility = if (historyIsEmpty) View.VISIBLE else View.GONE
            journeyList.visibility = if (historyIsEmpty) View.GONE else View.VISIBLE
            return
        }
        finish()
    }

    private fun formatDate(date: Date): String =
        SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(date)

    private fun showMessage(message: String) {
        messageText.text = message
        messageText.visibility = View.VISIBLE
    }

}
