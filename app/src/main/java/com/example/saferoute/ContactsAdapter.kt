package com.example.saferoute

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * ContactsAdapter — RecyclerView adapter for the emergency contacts list.
 *
 * Responsibilities:
 *  - Inflate item_contact.xml for each contact in the list.
 *  - Bind the contact's name and phone number to the TextViews.
 *  - Call the onDeleteClick lambda when the Delete button is tapped.
 *
 * The adapter does NOT handle Firestore. It delegates deletion
 * back to ContactsActivity through the onDeleteClick callback.
 *
 * @param contacts       The list of Contact objects to display.
 * @param onDeleteClick  Called with the Contact object when Delete is tapped.
 */
class ContactsAdapter(
    private val contacts: MutableList<Contact>,
    private val onDeleteClick: (Contact) -> Unit,
    private val onPrimaryClick: (Contact) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

    /**
     * ViewHolder — holds references to the views inside one item_contact.xml row.
     * Created once per visible row, then recycled as the user scrolls.
     */
    inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvContactName)
        val tvPhone: TextView = itemView.findViewById(R.id.tvContactPhone)
        val btnDelete: MaterialButton = itemView.findViewById(R.id.btnDeleteContact)
        val tvPrimary: TextView = itemView.findViewById(R.id.tvPrimaryContact)
        val btnPrimary: MaterialButton = itemView.findViewById(R.id.btnPrimaryContact)
    }

    // Called by RecyclerView when it needs a new row view
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    // Called by RecyclerView to fill in data for a specific row position
    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]

        holder.tvName.text = contact.name
        holder.tvPhone.text = contact.phone
        holder.tvPrimary.visibility = if (contact.isPrimary) View.VISIBLE else View.GONE
        holder.btnPrimary.visibility = if (contact.isPrimary) View.GONE else View.VISIBLE

        // When Delete is tapped, pass this contact back to the Activity
        holder.btnDelete.setOnClickListener {
            onDeleteClick(contact)
        }
        holder.btnPrimary.setOnClickListener {
            onPrimaryClick(contact)
        }
    }

    override fun getItemCount(): Int = contacts.size

    /**
     * Replaces the entire list and refreshes the RecyclerView.
     * Called by ContactsActivity when Firestore returns updated data.
     */
    fun updateContacts(newContacts: List<Contact>) {
        contacts.clear()
        contacts.addAll(newContacts)
        notifyDataSetChanged()
    }
}
