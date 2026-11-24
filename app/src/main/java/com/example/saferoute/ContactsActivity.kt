package com.example.saferoute

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

/**
 * ContactsActivity — Emergency Contacts management screen.
 *
 * Features:
 *  1. Load contacts from Firestore on open (ordered newest first).
 *  2. Validate and add contacts manually (name + phone fields).
 *  3. Pick a contact from the phone's contact directory — no READ_CONTACTS
 *     permission required; Android grants a temporary URI permission via
 *     the system picker.
 *  4. Confirm before deleting; remove document from Firestore on confirm.
 *  5. Empty-state message when no contacts exist.
 *
 * Firestore path:  users/{uid}/contacts/{auto-id}
 * Fields stored:   name, phone, addedAt — unchanged from Step 5.
 */
class ContactsActivity : AppCompatActivity() {

    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    // Views
    private lateinit var toolbar: Toolbar
    private lateinit var layoutContactName: TextInputLayout
    private lateinit var layoutContactPhone: TextInputLayout
    private lateinit var editContactName: TextInputEditText
    private lateinit var editContactPhone: TextInputEditText
    private lateinit var btnAddContact: MaterialButton
    private lateinit var btnChooseFromPhone: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var recyclerContacts: RecyclerView

    private lateinit var adapter: ContactsAdapter
    private val contactList = mutableListOf<Contact>()

    /**
     * Activity Result Launcher for the system contacts picker.
     *
     * Must be registered as a property (before onCreate) — this is a
     * requirement of the Activity Result API lifecycle contract.
     *
     * We use ACTION_PICK + Phone.CONTENT_URI so the system picker shows
     * only contacts that already have a phone number stored, and Android
     * grants our app a one-time URI permission for the selected contact
     * without us needing READ_CONTACTS in the manifest.
     *
     * RESULT_CANCELED (user pressed Back) is silently ignored.
     */
    private val pickContactLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri -> handlePickedContact(uri) }
            }
            // RESULT_CANCELED — do nothing, fields stay unchanged
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)

        firebaseAuth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        bindViews()
        setupToolbar()
        setupRecyclerView()
        loadContacts()

        btnAddContact.setOnClickListener {
            val name = editContactName.text.toString().trim()
            val phone = editContactPhone.text.toString().trim()
            if (validateInputs(name, phone)) {
                addContact(name, phone)
            }
        }

        btnChooseFromPhone.setOnClickListener {
            openPhonePicker()
        }
    }

    private fun bindViews() {
        toolbar            = findViewById(R.id.toolbar)
        layoutContactName  = findViewById(R.id.layoutContactName)
        layoutContactPhone = findViewById(R.id.layoutContactPhone)
        editContactName    = findViewById(R.id.editContactName)
        editContactPhone   = findViewById(R.id.editContactPhone)
        btnAddContact      = findViewById(R.id.btnAddContact)
        btnChooseFromPhone = findViewById(R.id.btnChooseFromPhone)
        progressBar        = findViewById(R.id.progressBar)
        tvEmptyState       = findViewById(R.id.tvEmptyState)
        recyclerContacts   = findViewById(R.id.recyclerContacts)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = ContactsAdapter(
            contactList,
            onDeleteClick = { contact -> showDeleteConfirmation(contact) },
            onPrimaryClick = { contact -> setPrimaryContact(contact) }
        )
        recyclerContacts.layoutManager = LinearLayoutManager(this)
        recyclerContacts.adapter = adapter
    }

    // ─── Phone Picker ────────────────────────────────────────────────────────

    /**
     * Launches the system contacts picker.
     * Using Phone.CONTENT_URI restricts the picker to contacts that have
     * a phone number, so Android never returns a contact with no number.
     * No READ_CONTACTS permission is needed.
     */
    private fun openPhonePicker() {
        val intent = Intent(
            Intent.ACTION_PICK,
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        )
        pickContactLauncher.launch(intent)
    }

    /**
     * Reads name and phone from the URI Android returned.
     *
     * The URI points to one row in the Phone data table.
     * We query DISPLAY_NAME and NUMBER from that single row.
     * cursor?.use {} closes the cursor automatically (even on exception).
     *
     * On success: populates the input fields so the user can review/edit
     * before tapping Add Contact.
     */
    private fun handlePickedContact(contactUri: Uri) {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val cursor = contentResolver.query(contactUri, projection, null, null, null)

        cursor?.use {
            if (it.moveToFirst()) {
                val nameIdx  = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                val name  = if (nameIdx  >= 0) it.getString(nameIdx)  ?: "" else ""
                val phone = if (phoneIdx >= 0) it.getString(phoneIdx) ?: "" else ""

                if (phone.isEmpty()) {
                    showSnackbar(getString(R.string.error_contact_no_phone))
                    return
                }

                // Populate fields — user can edit before saving
                editContactName.setText(name)
                editContactPhone.setText(phone)
                layoutContactName.error  = null
                layoutContactPhone.error = null
                editContactName.setSelection(name.length)
            }
        }
    }

    // ─── Firestore Operations ─────────────────────────────────────────────────

    private fun loadContacts() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        showLoading(true)

        firestore.collection("users").document(uid)
            .collection("contacts")
            .orderBy("addedAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                showLoading(false)
                val contacts = snapshot.documents.mapNotNull { doc ->
                    val name  = doc.getString("name")  ?: return@mapNotNull null
                    val phone = doc.getString("phone") ?: return@mapNotNull null
                    Contact(
                        documentId = doc.id,
                        name = name,
                        phone = phone,
                        isPrimary = doc.getBoolean("isPrimary") == true
                    )
                }
                adapter.updateContacts(contacts)
                updateEmptyState()
            }
            .addOnFailureListener {
                showLoading(false)
                showSnackbar(getString(R.string.error_loading_contacts))
                updateEmptyState()
            }
    }

    private fun validateInputs(name: String, phone: String): Boolean {
        layoutContactName.error  = null
        layoutContactPhone.error = null

        if (name.isEmpty()) {
            layoutContactName.error = getString(R.string.error_contact_name_empty)
            editContactName.requestFocus()
            return false
        }
        if (phone.isEmpty()) {
            layoutContactPhone.error = getString(R.string.error_contact_phone_empty)
            editContactPhone.requestFocus()
            return false
        }
        if (!isValidIndianMobileNumber(phone)) {
            layoutContactPhone.error = getString(R.string.error_contact_phone_invalid)
            editContactPhone.requestFocus()
            return false
        }
        return true
    }

    private fun addContact(name: String, phone: String) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        showLoading(true)
        btnAddContact.isEnabled = false
        val contactsRef = firestore.collection("users").document(uid).collection("contacts")
        contactsRef.get()
            .addOnSuccessListener { snapshot ->
                val normalizedPhone = normalizeIndianMobileNumber(phone)
                val duplicate = snapshot.documents.any { document ->
                    normalizeIndianMobileNumber(document.getString("phone").orEmpty()) == normalizedPhone
                }
                if (duplicate) {
                    showLoading(false)
                    btnAddContact.isEnabled = true
                    layoutContactPhone.error = getString(R.string.error_contact_duplicate)
                    editContactPhone.requestFocus()
                    return@addOnSuccessListener
                }

                val data = hashMapOf(
                    "name" to name,
                    "phone" to phone,
                    "addedAt" to Timestamp.now(),
                    "isPrimary" to false
                )
                contactsRef.add(data)
                    .addOnSuccessListener { docRef ->
                        showLoading(false)
                        btnAddContact.isEnabled = true
                        val newContact = Contact(documentId = docRef.id, name = name, phone = phone)
                        contactList.add(0, newContact)
                        adapter.notifyItemInserted(0)
                        recyclerContacts.scrollToPosition(0)
                        updateEmptyState()
                        editContactName.text?.clear()
                        editContactPhone.text?.clear()
                        editContactName.requestFocus()
                        showSnackbar(getString(R.string.msg_contact_added))
                    }
                    .addOnFailureListener { e ->
                        showLoading(false)
                        btnAddContact.isEnabled = true
                        showSnackbar("Failed to add contact: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                showLoading(false)
                btnAddContact.isEnabled = true
                showSnackbar("Failed to check for duplicate contacts: ${e.message}")
            }
    }

    private fun isValidIndianMobileNumber(value: String): Boolean {
        if (!value.matches(Regex("\\+?[0-9][0-9\\s()-]*"))) return false
        val digits = value.filter { it.isDigit() }
        val nationalNumber = when {
            digits.length == 10 -> digits
            digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
            else -> return false
        }
        return nationalNumber.firstOrNull() in '6'..'9'
    }

    private fun normalizeIndianMobileNumber(value: String): String {
        val digits = value.filter { it.isDigit() }
        return if (digits.length == 12 && digits.startsWith("91")) digits.drop(2) else digits
    }

    private fun setPrimaryContact(contact: Contact) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        showLoading(true)
        val contactsRef = firestore.collection("users").document(uid).collection("contacts")
        contactsRef.get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.documents.none { it.id == contact.documentId }) {
                    showLoading(false)
                    loadContacts()
                    showSnackbar(getString(R.string.error_primary_contact_missing))
                    return@addOnSuccessListener
                }
                val batch = firestore.batch()
                snapshot.documents.forEach { document ->
                    batch.update(document.reference, "isPrimary", document.id == contact.documentId)
                }
                batch.commit()
                    .addOnSuccessListener {
                        showLoading(false)
                        val updatedContacts = contactList.map { it.copy(isPrimary = it.documentId == contact.documentId) }
                        adapter.updateContacts(updatedContacts)
                        showSnackbar(getString(R.string.msg_primary_contact_updated, contact.name))
                    }
                    .addOnFailureListener { exception ->
                        showLoading(false)
                        showSnackbar(getString(R.string.error_primary_contact_update, exception.message.orEmpty()))
                    }
            }
            .addOnFailureListener { exception ->
                showLoading(false)
                showSnackbar(getString(R.string.error_primary_contact_update, exception.message.orEmpty()))
            }
    }

    private fun showDeleteConfirmation(contact: Contact) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.confirm_delete_title))
            .setMessage(getString(R.string.confirm_delete_message, contact.name))
            .setPositiveButton("Delete") { _, _ -> deleteContact(contact) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteContact(contact: Contact) {
        val uid = firebaseAuth.currentUser?.uid ?: return

        firestore.collection("users").document(uid)
            .collection("contacts")
            .document(contact.documentId)
            .delete()
            .addOnSuccessListener {
                val pos = contactList.indexOfFirst { it.documentId == contact.documentId }
                if (pos != -1) {
                    contactList.removeAt(pos)
                    adapter.notifyItemRemoved(pos)
                }
                updateEmptyState()
                showSnackbar(getString(R.string.msg_contact_deleted))
            }
            .addOnFailureListener { e ->
                showSnackbar("Failed to delete contact: ${e.message}")
            }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun updateEmptyState() {
        tvEmptyState.visibility = if (contactList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showLoading(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(recyclerContacts, message, Snackbar.LENGTH_SHORT).show()
    }
}
