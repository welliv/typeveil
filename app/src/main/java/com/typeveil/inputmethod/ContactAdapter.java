package com.typeveil.inputmethod;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.ViewHolder> {

    private final List<ContactManager.ContactInfo> contacts;
    private final OnContactClickListener clickListener;
    private final OnContactRemoveListener removeListener;

    public interface OnContactClickListener {
        void onClick(ContactManager.ContactInfo contact);
    }

    public interface OnContactRemoveListener {
        void onRemove(ContactManager.ContactInfo contact);
    }

    public ContactAdapter(List<ContactManager.ContactInfo> contacts,
                          OnContactClickListener clickListener,
                          OnContactRemoveListener removeListener) {
        this.contacts = contacts;
        this.clickListener = clickListener;
        this.removeListener = removeListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ContactManager.ContactInfo contact = contacts.get(position);
        holder.tvHandle.setText(contact.handle);
        holder.tvFingerprint.setText(contact.fingerprint.isEmpty() ? "— —" :
                contact.fingerprint.substring(0, Math.min(8, contact.fingerprint.length())));

        holder.itemView.setOnClickListener(v -> clickListener.onClick(contact));
        holder.btnRemove.setOnClickListener(v -> removeListener.onRemove(contact));
    }

    @Override
    public int getItemCount() {
        return contacts.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvHandle;
        TextView tvFingerprint;
        ImageButton btnRemove;

        ViewHolder(View itemView) {
            super(itemView);
            tvHandle = itemView.findViewById(R.id.contact_handle);
            tvFingerprint = itemView.findViewById(R.id.contact_fingerprint);
            btnRemove = itemView.findViewById(R.id.btn_remove_contact);
        }
    }
}
