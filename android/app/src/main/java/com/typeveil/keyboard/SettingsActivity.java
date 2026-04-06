package com.typeveil.keyboard;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

/**
 * Typeveil Settings — enable keyboard, generate keys, manage recipients.
 * FLAG_SECURE blocks screenshots and screen recordings.
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        );
        setContentView(R.layout.settings);

        Button enableBtn = findViewById(R.id.btn_enable);
        enableBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            startActivity(intent);
        });

        Button genKeyBtn = findViewById(R.id.btn_gen_key);
        genKeyBtn.setOnClickListener(v -> showPassphraseDialog());

        Button importKeyBtn = findViewById(R.id.btn_import_key);
        importKeyBtn.setOnClickListener(v -> showImportRecipientDialog());

        Button exportKeyBtn = findViewById(R.id.btn_export_key);
        exportKeyBtn.setOnClickListener(v -> showExportKeyDialog());

        updateStatus();
    }

    private void showPassphraseDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Set Passphrase");
        builder.setMessage("Enter a passphrase to protect your private key. You will need this to decrypt received messages.");
        
        EditText passphrase = new EditText(this);
        passphrase.setHint("Enter passphrase");
        passphrase.setInputType(android.text.InputType.TYPE_CLASS_TEXT 
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(passphrase);
        
        builder.setPositiveButton("Generate", (dialog, which) -> {
            String pw = passphrase.getText().toString();
            if (pw.isEmpty()) {
                status("Passphrase required");
                return;
            }
            if (pw.length() < 8) {
                status("Passphrase must be at least 8 characters");
                return;
            }
            String pubKey = Crypto.generateKeyPair(this, pw);
            if (pubKey != null) {
                status("Key pair generated. Share your public key with contacts.");
            } else {
                status("Key generation failed");
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showImportRecipientDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Import Recipient Key");
        builder.setMessage("Paste their PGP public key to encrypt messages for them:");
        
        EditText keyInput = new EditText(this);
        keyInput.setHint("-----BEGIN PGP PUBLIC KEY BLOCK-----");
        keyInput.setMinHeight(200);
        builder.setView(keyInput);
        
        builder.setPositiveButton("Import", (dialog, which) -> {
            String key = keyInput.getText().toString();
            if (Crypto.setRecipientPublicKey(this, key)) {
                status("Recipient key imported and validated");
            } else {
                status("Invalid PGP public key");
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showExportKeyDialog() {
        // Show the user their public key so they can share it
        android.content.SharedPreferences prefs = getSharedPreferences("typeveil_prefs", MODE_PRIVATE);
        String encryptedKey = prefs.getString("enc_privkey", null);
        if (encryptedKey == null) {
            status("No key pair generated yet");
            return;
        }
        // For now, show a placeholder - the public key would need to be stored or regenerated
        status("Export: regenerate keys or share public key manually");
    }

    private void updateStatus() {
        TextView status = findViewById(R.id.tv_status);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        List<InputMethodInfo> methods = imm.getInputMethodList();
        
        boolean enabled = false;
        for (InputMethodInfo info : methods) {
            if (info.getPackageName().equals(getPackageName())) {
                enabled = true;
                break;
            }
        }
        boolean hasKeys = getSharedPreferences("typeveil_prefs", MODE_PRIVATE)
            .getBoolean("has_keypair", false);
        
        if (enabled && hasKeys) {
            status.setText("Typeveil enabled. Keys configured.");
        } else if (enabled) {
            status.setText("Typeveil enabled. Generate a key pair to start encrypting.");
        } else {
            status.setText("Enable Typeveil in Input Method settings");
        }
    }

    private void status(String msg) {
        TextView tv = findViewById(R.id.tv_status);
        if (tv != null) tv.setText(msg);
    }
}
