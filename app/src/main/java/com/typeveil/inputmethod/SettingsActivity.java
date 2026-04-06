package com.typeveil.inputmethod;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.typeveil.inputmethod.crypto.Crypto;
import com.typeveil.inputmethod.nostr.NostrKeyResolver;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SettingsActivity — manage encryption keys and contacts.
 * 
 * Features:
 * - Toggle Veil mode (auto-encrypt)
 * - Export public key
 * - View key fingerprint
 * - Add/remove contacts with PGP keys
 * - Resolve via Nostr NIP-05
 * - Security settings (clipboard, screenshots)
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("typeveil", Context.MODE_PRIVATE);

        // Ensure crypto keys exist
        try {
            Crypto.ensureKeyPair(this);
        } catch (Exception e) {
            Toast.makeText(this, "Key generation failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        setupVeilToggle();
        setupKeyExport();
        setupFingerprint();
        setupContactList();
    }

    private void setupVeilToggle() {
        com.google.android.material.switchmaterial.SwitchMaterial veilToggle =
                findViewById(R.id.sw_veil_mode);
        if (veilToggle != null) {
            veilToggle.setChecked(prefs.getBoolean("veil_mode", false));
            veilToggle.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean("veil_mode", checked).apply();
            });
        }
    }

    private void setupKeyExport() {
        MaterialButton btnExport = findViewById(R.id.btn_export_key);
        if (btnExport != null) {
            btnExport.setOnClickListener(v -> {
                try {
                    String pubKey = Crypto.getPublicKey(this);
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("TypeVeil Public Key", pubKey));
                    Toast.makeText(this, "Public key copied to clipboard", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void setupFingerprint() {
        TextView tvFingerprint = findViewById(R.id.tv_key_fingerprint);
        if (tvFingerprint != null) {
            try {
                String fp = Crypto.getKeyFingerprint(this);
                // Show last 16 chars for readability
                String shortFp = fp.length() > 16 ? fp.substring(fp.length() - 16) : fp;
                tvFingerprint.setText("Fingerprint: " + shortFp);
            } catch (Exception e) {
                tvFingerprint.setText("Cannot load fingerprint");
            }
        }
    }

    private void setupContactList() {
        RecyclerView rv = findViewById(R.id.rv_contacts);
        if (rv == null) return;

        rv.setLayoutManager(new LinearLayoutManager(this));
        updateContactList(rv);

        MaterialButton btnAdd = findViewById(R.id.btn_add_contact);
        if (btnAdd != null) {
            btnAdd.setOnClickListener(v -> showAddContactDialog());
        }
    }

    private void updateContactList(RecyclerView rv) {
        List<ContactManager.ContactInfo> contacts = ContactManager.getContacts(this);
        ContactAdapter adapter = new ContactAdapter(contacts, this::onContactClick, this::onContactRemove);
        rv.setAdapter(adapter);
    }

    private void onContactClick(ContactManager.ContactInfo contact) {
        ContactManager.setActiveContact(this, contact.handle);
        Toast.makeText(this, "Encrypting for: " + contact.handle, Toast.LENGTH_SHORT).show();
        updateContactList(findViewById(R.id.rv_contacts));
    }

    private void onContactRemove(ContactManager.ContactInfo contact) {
        ContactManager.removeContact(this, contact.handle);
        Toast.makeText(this, "Contact removed", Toast.LENGTH_SHORT).show();
        updateContactList(findViewById(R.id.rv_contacts));
    }

    private void showAddContactDialog() {
        // Show bottom sheet or new activity for adding contacts
        // For simplicity, use an inline form
        View addForm = findViewById(R.id.layout_add_contact);
        if (addForm != null) {
            addForm.setVisibility(addForm.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Called from XML when user taps "Resolve via Nostr"
     */
    @SuppressWarnings("unused")
    public void onResolveNostr(View view) {
        TextInputEditText etHandle = findViewById(R.id.et_contact_handle);
        if (etHandle == null) return;

        String handle = etHandle.getText().toString().trim();
        if (handle.isEmpty()) {
            Toast.makeText(this, "Enter handle first", Toast.LENGTH_SHORT).show();
            return;
        }

        TextView tvStatus = findViewById(R.id.tv_add_status);
        if (tvStatus != null) tvStatus.setText("Resolving via Nostr...");

        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            String nostrPubkey = NostrKeyResolver.resolveNip05(handle);
            if (nostrPubkey == null) {
                runOnUiThread(() -> {
                    if (tvStatus != null) tvStatus.setText("NIP-05 resolution failed");
                    Toast.makeText(this, "Could not resolve " + handle, Toast.LENGTH_SHORT).show();
                });
                return;
            }

            String pgpKey = NostrKeyResolver.resolvePgpFromNostr(nostrPubkey);
            if (pgpKey == null) {
                runOnUiThread(() -> {
                    if (tvStatus != null) tvStatus.setText("No PGP key found for this Nostr user");
                });
                return;
            }

            // Extract PGP block
            int begin = pgpKey.indexOf("-----BEGIN PGP PUBLIC KEY BLOCK-----");
            int end = pgpKey.indexOf("-----END PGP PUBLIC KEY BLOCK-----");
            if (begin == -1 || end == -1) {
                runOnUiThread(() -> {
                    if (tvStatus != null) tvStatus.setText("Invalid PGP key format");
                });
                return;
            }

            String armored = pgpKey.substring(begin, end + "-----END PGP PUBLIC KEY BLOCK-----".length());
            String fingerprint = "";
            try {
                // Could compute from key — for now use hash
                fingerprint = Integer.toHexString(pgpKey.hashCode()).toUpperCase();
            } catch (Exception e) {
                fingerprint = "unknown";
            }

            final String fp = fingerprint.length() > 8 ? fingerprint.substring(0, 8) : fingerprint;
            runOnUiThread(() -> {
                ContactManager.addContact(this, handle, armored, fp);
                if (tvStatus != null) tvStatus.setText("Added: " + handle);
                Toast.makeText(this, "Contact added via Nostr", Toast.LENGTH_SHORT).show();
                updateContactList(findViewById(R.id.rv_contacts));

                // Hide form
                View addForm = findViewById(R.id.layout_add_contact);
                if (addForm != null) addForm.setVisibility(View.GONE);
            });
        });
    }

    /**
     * Called from XML when user taps "Add Contact"
     */
    @SuppressWarnings("unused")
    public void onSaveContact(View view) {
        TextInputEditText etHandle = findViewById(R.id.et_contact_handle);
        TextInputEditText etKey = findViewById(R.id.et_contact_key);
        if (etHandle == null || etKey == null) return;

        String handle = etHandle.getText().toString().trim();
        String key = etKey.getText().toString().trim();

        if (handle.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, "Both handle and key are required", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!key.contains("-----BEGIN PGP")) {
            Toast.makeText(this, "Invalid PGP key block", Toast.LENGTH_SHORT).show();
            return;
        }

        String fp = "";
        try {
            fp = Integer.toHexString(key.hashCode()).toUpperCase().substring(0, 8);
        } catch (Exception e) {
            fp = "unknown";
        }

        ContactManager.addContact(this, handle, key, fp);
        Toast.makeText(this, "Contact added: " + handle, Toast.LENGTH_SHORT).show();

        // Clear form
        etHandle.setText("");
        etKey.setText("");

        updateContactList(findViewById(R.id.rv_contacts));
    }
}
