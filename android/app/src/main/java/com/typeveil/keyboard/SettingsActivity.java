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

        // BLOCK SCREENSHOTS AND SCREEN RECORDS
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

        Button pasteKeyBtn = findViewById(R.id.btn_paste_key);
        pasteKeyBtn.setOnClickListener(v -> showImportDialog());

        updateStatus();
    }

    private void showPassphraseDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Set Passphrase");
        builder.setMessage("Protect your private key with a passphrase.");
        
        EditText passphrase = new EditText(this);
        passphrase.setHint("Enter passphrase");
        passphrase.setInputType(android.text.InputType.TYPE_CLASS_TEXT 
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(passphrase);
        
        builder.setPositiveButton("Generate", (dialog, which) -> {
            String pw = passphrase.getText().toString();
            if (pw.isEmpty()) {
                status("Passphrase required for security");
                return;
            }
            Crypto.generateKeyPair(this, pw);
            status("Key pair generated. Keep your passphrase safe.");
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showImportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Import Recipient Key");
        builder.setMessage("Paste their PGP public key:");
        
        EditText keyInput = new EditText(this);
        keyInput.setHint("-----BEGIN PGP PUBLIC KEY BLOCK-----");
        keyInput.setMinHeight(200);
        builder.setView(keyInput);
        
        builder.setPositiveButton("Import", (dialog, which) -> {
            String key = keyInput.getText().toString();
            if (key.contains("BEGIN PGP PUBLIC KEY")) {
                Crypto.setRecipientPublicKey(this, key);
                status("Recipient key imported");
            } else {
                status("Invalid public key format");
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
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
        status.setText(enabled ? "Typeveil is enabled" : "Enable Typeveil in Input Method settings");
    }

    private void status(String msg) {
        findViewById(R.id.tv_status);
        ((TextView) findViewById(R.id.tv_status)).setText(msg);
    }
}
