package com.typeveil.keyboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Typeveil Settings — enable keyboard, manage keys, add contacts.
 * FLAG_SECURE blocks screenshots and screen recordings.
 * 
 * Features:
 * - Key generation with passphrase
 * - Add contacts via Nostr handle (NIP-05 + Kind 30078 resolution)
 * - Manual PGP key import as fallback
 * - Contact list management
 * - Public key export/share
 */
public class SettingsActivity extends Activity {

    private LinearLayout contactsContainer;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        );
        setContentView(R.layout.settings);

        // Bind UI elements
        contactsContainer = findViewById(R.id.contacts_container);
        statusText = findViewById(R.id.tv_status);
        Button enableBtn = findViewById(R.id.btn_enable);
        Button genKeyBtn = findViewById(R.id.btn_gen_key);
        Button addContactBtn = findViewById(R.id.btn_add_contact);
        Button importKeyBtn = findViewById(R.id.btn_import_key);
        Button exportKeyBtn = findViewById(R.id.btn_export_key);

        // Enable keyboard
        enableBtn.setOnClickListener(v -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS);
            startActivity(intent);
        });

        // Generate keys
        genKeyBtn.setOnClickListener(v -> showPassphraseDialog());

        // Add contact via Nostr handle
        addContactBtn.setOnClickListener(v -> showAddContactDialog());

        // Manual key import
        importKeyBtn.setOnClickListener(v -> showImportRecipientDialog());

        // Export public key
        exportKeyBtn.setOnClickListener(v -> showExportKeyDialog());

        refreshUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUI();
    }

    private void refreshUI() {
        boolean enabled = isKeyboardEnabled();
        boolean hasKeys = getSharedPreferences("typeveil_prefs", MODE_PRIVATE)
            .getBoolean("has_keypair", false);

        if (enabled && hasKeys) {
            statusText.setText("Typeveil enabled. Keys configured.");
            statusText.setTextColor(getColor(android.R.color.holo_green_light));
        } else if (enabled) {
            statusText.setText("Typeveil enabled. Generate key pair to start.");
            statusText.setTextColor(getColor(android.R.color.holo_orange_light));
        } else {
            statusText.setText("Enable Typeveil in keyboard settings.");
            statusText.setTextColor(getColor(android.R.color.holo_red_light));
        }

        // Render contact list
        renderContacts();
    }

    private boolean isKeyboardEnabled() {
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        for (android.view.inputmethod.InputMethodInfo info : imm.getInputMethodList()) {
            if (info.getPackageName().equals(getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private void renderContacts() {
        contactsContainer.removeAllViews();

        android.content.SharedPreferences prefs =
            getSharedPreferences("typeveil_contacts", MODE_PRIVATE);

        // Get all contact handles
        java.util.Set<String> all = prefs.getAll().keySet();
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No contacts added yet. Add via Nostr handle or import PGP key.");
            empty.setTextColor(0xFF888888);
            empty.setTextSize(14);
            empty.setPadding(32, 16, 32, 16);
            contactsContainer.addView(empty);
            return;
        }

        for (String handle : all) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(32, 12, 32, 12);

            TextView name = new TextView(this);
            name.setText(handle);
            name.setTextColor(0xFFE6EDF3);
            name.setTextSize(16);
            name.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView label = new TextView(this);
            label.setText(prefs.getString(handle, "").length() > 20
                ? "PGP key ✓" : "NIP-05 ✓");
            label.setTextColor(0xFF83C167);
            label.setTextSize(12);

            Button remove = new Button(this);
            remove.setText("✕");
            remove.setBackgroundColor(0x00000000);
            remove.setTextColor(0xFFFF4444);
            remove.setTextSize(18);
            remove.setOnClickListener(v -> {
                prefs.edit().remove(handle).apply();
                renderContacts();
            });

            row.addView(name);
            row.addView(label);
            row.addView(remove);
            contactsContainer.addView(row);
        }
    }

    /**
     * Add contact via Nostr handle.
     * Resolves NIP-05 → fetches PGP key from Kind 30078 event.
     */
    private void showAddContactDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add Contact");
        builder.setMessage("Enter their Nostr handle (e.g., alice@nostr.com)");

        final EditText input = new EditText(this);
        input.setHint("alice@example.com");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        builder.setView(input);

        builder.setPositiveButton("Resolve & Add", (dialog, which) -> {
            String handle = input.getText().toString().trim();
            if (handle.isEmpty() || !handle.contains("@")) {
                setStatus("Invalid handle");
                return;
            }

            setStatus("Resolving " + handle + "...");

            // Run resolution in background
            new Thread(() -> {
                // Try Nostr key resolution first (NIP-05 + Kind 30078)
                String pgpKey = NostrKeyResolver.resolvePgpKey(handle);

                if (pgpKey != null && pgpKey.contains("BEGIN PGP PUBLIC KEY BLOCK")) {
                    // Got PGP key from Nostr, store as contact
                    android.content.SharedPreferences prefs =
                        getSharedPreferences("typeveil_contacts", MODE_PRIVATE);
                    prefs.edit().putString(handle.toLowerCase(), pgpKey).apply();

                    // Also set as default recipient for encryption
                    Crypto.setRecipientPublicKey(this, pgpKey);

                    runOnUiThread(() -> {
                        setStatus("Contact added: " + handle + " (PGP key from Nostr)");
                        renderContacts();
                    });
                } else {
                    // Fall back to manual import guidance
                    String nostrPubkey = NostrKeyResolver.resolvePubkey(handle);
                    if (nostrPubkey != null) {
                        NostrKeyResolver.storeResolvedPubkey(this, handle, nostrPubkey);
                        runOnUiThread(() ->
                            setStatus("Nostr pubkey found. Use resolve-key.js to fetch their PGP key.")
                        );
                    } else {
                        runOnUiThread(() ->
                            setStatus("Handle not found. Try: resolve-key.js " + handle)
                        );
                    }
                }
            }).start();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showPassphraseDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Set Passphrase");
        builder.setMessage("Set a passphrase to protect your private key. You'll need this to decrypt incoming messages.");

        final EditText passphrase = new EditText(this);
        passphrase.setHint("Minimum 8 characters");
        passphrase.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(passphrase);

        builder.setPositiveButton("Generate", (dialog, which) -> {
            String pw = passphrase.getText().toString();
            if (pw.length() < 8) {
                setStatus("Passphrase must be 8+ characters");
                return;
            }
            String pubKey = Crypto.generateKeyPair(this, pw);
            if (pubKey != null) {
                setStatus("Key pair generated. Add contacts or publish to Nostr.");
            } else {
                setStatus("Key generation failed");
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showImportRecipientDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Import PGP Key");
        builder.setMessage("Paste their PGP public key:");

        final EditText keyInput = new EditText(this);
        keyInput.setHint("-----BEGIN PGP PUBLIC KEY BLOCK-----");
        keyInput.setMinHeight(300);
        builder.setView(keyInput);

        builder.setPositiveButton("Import", (dialog, which) -> {
            String key = keyInput.getText().toString().trim();
            if (Crypto.setRecipientPublicKey(this, key)) {
                // Also store as named contact
                android.content.SharedPreferences prefs =
                    getSharedPreferences("typeveil_contacts", MODE_PRIVATE);
                String name = prefs.getString("_last_import_name", "contact-" + System.currentTimeMillis());
                prefs.edit().putString(name.toLowerCase(), key).apply();

                setStatus("PGP key imported and validated");
                renderContacts();
            } else {
                setStatus("Invalid PGP public key");
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showExportKeyDialog() {
        android.content.SharedPreferences prefs =
            getSharedPreferences("typeveil_prefs", MODE_PRIVATE);
        String encrypted = prefs.getString("enc_privkey", null);

        if (encrypted == null) {
            setStatus("No key pair generated yet");
            return;
        }

        // Get the public key by generating a temp one
        String pubKey = prefs.getString("my_pubkey", null);
        if (pubKey != null) {
            // Show share dialog
            android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(android.content.Intent.EXTRA_TEXT, pubKey);
            startActivity(android.content.Intent.createChooser(share, "Share Public Key"));
        } else {
            setStatus("Export: use nostr-tools/publish-key.js to publish your key");
        }
    }

    private void setStatus(String msg) {
        if (statusText != null) {
            statusText.setText(msg);
        }
    }
}
