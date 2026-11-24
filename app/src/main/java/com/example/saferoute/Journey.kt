package com.example.saferoute

import com.google.firebase.Timestamp

/**
 * Journey — data class representing one travel journey saved by a user.
 *
 * This class is a simple container for journey information. It does not
 * contain any logic — it just holds data. This is the same pattern used
 * by Contact.kt in this project.
 *
 * Every property has a default value. This is required by Firebase Firestore:
 * when Firestore reads a document and converts it into a Kotlin object, it
 * calls the no-argument constructor. Without default values, that conversion
 * would crash with an error. So every property must have a sensible default.
 *
 * Firestore path:  users/{uid}/journeys/{documentId}
 *
 * Fields stored in Firestore (inside the document):
 *   from, to, travelMode, expectedArrival, status,
 *   companionName, companionPhone, vehicleNumber, startedAt, completedAt,
 *   safetyCheckIntervalMinutes
 *
 * Fields NOT stored in Firestore:
 *   documentId — this is the Firestore document's own ID (doc.id), not a
 *   field inside the document. It is populated manually after reading:
 *     document.toObject(Journey::class.java)?.copy(documentId = document.id)
 *
 *   Passwords, location coordinates (those are generated fresh at alert time),
 *   or any sensitive authentication data.
 */
data class Journey(

    /**
     * documentId — the Firestore document ID for this journey.
     *
     * Type: String
     * Default: empty string
     *
     * Why needed: when the user taps "Complete Journey", we need to tell
     * Firestore exactly which document to update. Firestore generates this
     * ID automatically when we save the journey. We store it here so we
     * can find the document again later.
     *
     * Important: this field is NOT a field inside the Firestore document.
     * It is the document's own ID (doc.id). It must be assigned manually
     * after reading, like this:
     *   document.toObject(Journey::class.java)?.copy(documentId = document.id)
     *
     * Firestore will never fill this property automatically.
     */
    val documentId: String = "",

    /**
     * from — the user's starting location, entered as plain text.
     *
     * Type: String
     * Default: empty string
     * Example: "Home, Sector 15, Noida"
     *
     * Why String, not GPS coordinates: the user types this themselves.
     * We do not auto-detect their location for this field.
     */
    val from: String = "",

    /**
     * to — the user's destination, entered as plain text.
     *
     * Type: String
     * Default: empty string
     * Example: "Indira Gandhi International Airport, Terminal 2"
     */
    val to: String = "",

    /**
     * travelMode — how the user is travelling.
     *
     * Type: String
     * Default: empty string
     * Examples: "Cab", "Bus", "Auto Rickshaw", "Personal Vehicle", "Train", "Other"
     *
     * We store it as a plain String (not an enum) so it is easy to display
     * and easy to save to Firestore without any conversion.
     */
    val travelMode: String = "",

    /**
     * expectedArrival — the time the user expects to reach their destination.
     *
     * Type: com.google.firebase.Timestamp
     * Default: null (the user may not have selected a time yet)
     *
     * Why Timestamp and not a String: Firestore stores timestamps natively.
     * Using Timestamp lets us display it correctly in the user's time zone
     * by converting it to a Java Date when we need to show it on screen.
     *
     * Why nullable: when this Journey object is first created as an empty
     * object (e.g. before a Firestore document is read), there is no arrival
     * time yet. Null means "not set".
     */
    val expectedArrival: Timestamp? = null,

    /**
     * status — whether this journey is currently active or has been completed.
     *
     * Type: String
     * Default: "active"
     * Allowed values: "active" or "completed"
     *
     * Why a String and not a Boolean: a String is easier to understand when
     * you look at the Firestore Console — you can read "active" or "completed"
     * directly. A boolean true/false is less clear.
     *
     * Business rule: a user can only have ONE document with status = "active"
     * at a time. This is enforced in JourneyActivity before saving a new journey.
     * When completed, the document is updated to "completed" — it is never deleted.
     */
    val status: String = "active",

    /**
     * companionName — the name of the driver or travel companion.
     *
     * Type: String
     * Default: empty string
     *
     * This field is OPTIONAL. If the user leaves it blank, we store an empty
     * string. We never store null for optional text fields because comparing
     * an empty string is simpler than checking for null everywhere in the code.
     */
    val companionName: String = "",

    /**
     * companionPhone — the phone number of the driver or travel companion.
     *
     * Type: String
     * Default: empty string
     *
     * This field is OPTIONAL. It is validated only when the user has typed
     * something — if it is left blank, no validation runs. The minimum check
     * is 7 digits (same rule used in ContactsActivity).
     */
    val companionPhone: String = "",

    /**
     * vehicleNumber — the vehicle registration number (number plate).
     *
     * Type: String
     * Default: empty string
     * Example: "MH 12 AB 1234"
     *
     * This field is OPTIONAL. No format validation is applied — vehicle
     * number formats vary too much across states and countries.
     */
    val vehicleNumber: String = "",

    /**
     * startedAt — the exact date and time when the journey was started.
     *
     * Type: com.google.firebase.Timestamp
     * Default: null
     *
     * Set to Timestamp.now() when the user taps "Start Journey".
     * Used to display "Started At: 10:30 AM" on the active journey screen.
     */
    val startedAt: Timestamp? = null,

    /**
     * completedAt — the exact date and time when the journey was completed.
     *
     * Type: com.google.firebase.Timestamp
     * Default: null
     *
     * Null until the user taps "Mark as Completed". At that point,
     * JourneyActivity sets this to Timestamp.now() and updates the
     * Firestore document. The document is never deleted — only updated.
     * This satisfies condition 4 of the approved requirements.
     */
    val completedAt: Timestamp? = null,

    /** Reminder interval selected for safety check-ins, in minutes. */
    val safetyCheckIntervalMinutes: Int = 15
)
