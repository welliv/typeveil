package com.typeveil.inputmethod;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ContactManager — stores and retrieves PGP contacts.
 * 
 * Storage: SharedPreferences
 * Structure:
 * - "contacts" = comma-separated list of handles
 * - "contact_key_<handle>" = armored PGP public key
 * - "contact_fp_<handle>" = fingerprint (last 8 chars)
 * - "active_contact" = currently selected recipient handle
 * 
 * Thread-safe with synchronized blocks (sufficient for IME context).
 */
public final class ContactManager {

    private static final String PREFS = "typeveil_contacts";
    private static final String KEY_CONTACTS = "contacts";
    private static final String KEY_ACTIVE = "active_contact";
    private static final String KEY_PREFIX = "contact_key_";
    private static final String KEY_FP_PREFIX = "contact_fp_";

    private ContactManager() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Add or update a contact.
     * 
     * @param handle  Unique identifier (e.g. "alice@domain.com")
     * @param pgpKey  Full armored PGP public key
     * @param fingerprint  Optional fingerprint for display
     */
    public static synchronized void addContact(Context ctx, String handle, String pgpKey, String fingerprint) {
        if (TextUtils.isEmpty(handle) || TextUtils.isEmpty(pgpKey)) return;

        SharedPreferences.Editor ed = prefs(ctx).edit();

        // Update contact list
        Set<String> contacts = prefs(ctx).getStringSet(KEY_CONTACTS, new HashSet<>());
        contacts.add(handle);
        ed.putStringSet(KEY_CONTACTS, contacts);

        // Store key and fingerprint
        ed.putString(KEY_PREFIX + handle, pgpKey);
        if (fingerprint != null) {
            ed.putString(KEY_FP_PREFIX + handle, fingerprint);
        } else {
            // Compute fingerprint from key
            ed.putString(KEY_FP_PREFIX + handle, computeFingerprint(pgpKey));
        }

        ed.apply();
    }

    /**
     * Remove a contact by handle.
     */
    public static synchronized void removeContact(Context ctx, String handle) {
        SharedPreferences.Editor ed = prefs(ctx).edit();

        Set<String> contacts = prefs(ctx).getStringSet(KEY_CONTACTS, new HashSet<>());
        contacts.remove(handle);
        ed.putStringSet(KEY_CONTACTS, contacts);

        ed.remove(KEY_PREFIX + handle);
        ed.remove(KEY_FP_PREFIX + handle);

        // If active contact was removed, clear active
        String active = prefs(ctx).getString(KEY_ACTIVE, "");
        if (handle.equals(active)) {
            ed.remove(KEY_ACTIVE);
        }

        ed.apply();
    }

    /**
     * Get all contacts as ordered list of ContactInfo.
     */
    public static synchronized List<ContactInfo> getContacts(Context ctx) {
        Set<String> handles = prefs(ctx).getStringSet(KEY_CONTACTS, new HashSet<>());
        List<ContactInfo> result = new ArrayList<>();
        for (String handle : handles) {
            String key = prefs(ctx).getString(KEY_PREFIX + handle, "");
            String fp = prefs(ctx).getString(KEY_FP_PREFIX + handle, "");
            if (!key.isEmpty()) {
                result.add(new ContactInfo(handle, key, fp));
            }
        }
        return result;
    }

    /**
     * Get PGP public key for a contact.
     */
    public static synchronized String getContactKey(Context ctx, String handle) {
        return prefs(ctx).getString(KEY_PREFIX + handle, "");
    }

    /**
     * Set active contact for encryption.
     */
    public static synchronized void setActiveContact(Context ctx, String handle) {
        prefs(ctx).edit().putString(KEY_ACTIVE, handle).apply();
    }

    /**
     * Get active contact handle.
     */
    public static synchronized String getActiveContact(Context ctx) {
        return prefs(ctx).getString(KEY_ACTIVE, "");
    }

    /**
     * Check if a contact exists.
     */
    public static synchronized boolean hasContact(Context ctx, String handle) {
        return prefs(ctx).contains(KEY_PREFIX + handle);
    }

    private static String computeFingerprint(String pgpKey) {
        // Simple hash of the key for display (not cryptographic)
        if (pgpKey == null || pgpKey.isEmpty()) return "";
        return Integer.toHexString(pgpKey.hashCode()).toUpperCase().substring(0, Math.min(8, pgpKey.hashCode() > 0 ? 8 : 7));
    }

    /**
     * Immutable contact data class.
     */
    public static final class ContactInfo {
        public final String handle;
        public final String pgpKey;
        public final String fingerprint;

        public ContactInfo(String handle, String pgpKey, String fingerprint) {
            this.handle = handle;
            this.pgpKey = pgpKey;
            this.fingerprint = fingerprint;
        }
    }
}
