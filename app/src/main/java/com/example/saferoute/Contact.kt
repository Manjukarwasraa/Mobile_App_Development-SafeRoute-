package com.example.saferoute

/**
 * Contact — data class representing a single emergency contact.
 *
 * @param documentId  The Firestore document ID (auto-generated).
 *                    Stored here so we can delete the correct document later.
 * @param name        The contact's display name.
 * @param phone       The contact's phone number as a string.
 *
 * Firestore path: users/{uid}/contacts/{documentId}
 * Fields stored:  name, phone, addedAt (timestamp — written by ContactsActivity)
 *
 * NOTE: This class is intentionally simple. No password or sensitive data is stored.
 */
data class Contact(
    val documentId: String = "",
    val name: String = "",
    val phone: String = "",
    val isPrimary: Boolean = false
)
