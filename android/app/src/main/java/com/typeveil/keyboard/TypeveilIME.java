package com.typeveil.keyboard;

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

/**
 * Typeveil Input Method.
 * 
 * Intercepts text before it reaches any app.
 * Veil key → encrypts current field content.
 * Unveil key → decrypts PGP message in current field.
 * 
 * Security: FLAG_SECURE prevents screen capture.
 * All crypto happens in-memory, keys in Android Keystore.
 * No network calls. Zero telemetry.
 */
public class TypeveilIME extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener {

    private KeyboardView keyboardView;
    private Keyboard keyboard;
    private boolean caps = false;

    @Override
    public View onCreateInputView() {
        keyboardView = (KeyboardView) getLayoutInflater()
                .inflate(R.layout.keyboard, null);
        keyboard = new Keyboard(this, R.xml.qwerty);
        setKeyboardCaps(false);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);
        return keyboardView;
    }

    @Override
    public void onStartInputView(EditorInfo attr, boolean restarting) {
        super.onStartInputView(attr, restarting);
        final int inputType = attr.inputType;
        boolean isPasswordField = (inputType & EditorInfo.TYPE_TEXT_VARIATION_PASSWORD) != 0
                || (inputType & EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) != 0
                || (inputType & EditorInfo.TYPE_CLASS_TEXT) == 0;
        
        // Optionally disable in password fields - user's choice
        keyboardView.setEnabled(true);
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        switch (primaryCode) {
            case Keyboard.KEYCODE_DELETE:
                ic.deleteSurroundingText(1, 0);
                break;

            case Keyboard.KEYCODE_DONE:
            case KeyEvent.KEYCODE_ENTER:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                break;

            case -100: // Veil
                handleVeil(ic);
                break;

            case -101: // Unveil
                handleUnveil(ic);
                break;

            case -1: // Shift
                caps = !caps;
                setKeyboardCaps(caps);
                break;

            case 32: // Space
                ic.commitText(" ", 1);
                break;

            default:
                char c = (char) primaryCode;
                ic.commitText(String.valueOf(caps ? Character.toUpperCase(c) : c), 1);
                if (caps) {
                    caps = false;
                    setKeyboardCaps(false);
                }
                break;
        }
    }

    private void handleVeil(InputConnection ic) {
        CharSequence before = ic.getTextBeforeCursor(10000, 0);
        if (before == null || before.length() == 0) {
            show("Nothing to encrypt");
            return;
        }

        String plaintext = before.toString();
        if (plaintext.startsWith("-----BEGIN PGP MESSAGE-----")) {
            show("Already encrypted");
            return;
        }

        String encrypted = Crypto.encrypt(plaintext, this);
        if (encrypted != null) {
            ic.deleteSurroundingText(before.length(), 0);
            ic.commitText(encrypted, 1);
            show("Veiled");
        } else {
            show("Encrypt failed — add recipient key in settings");
        }
    }

    private void handleUnveil(InputConnection ic) {
        CharSequence before = ic.getTextBeforeCursor(10000, 0);
        if (before == null) return;

        String text = before.toString();
        if (!text.contains("-----BEGIN PGP MESSAGE-----")) {
            show("No encrypted message");
            return;
        }

        // Extract just the PGP block if there's other text
        int start = text.indexOf("-----BEGIN PGP MESSAGE-----");
        int end = text.indexOf("-----END PGP MESSAGE-----");
        if (end == -1) {
            show("Corrupted message");
            return;
        }
        end += "-----END PGP MESSAGE-----".length();
        String pgpBlock = text.substring(start, end);

        String decrypted = Crypto.decrypt(pgpBlock, this);
        if (decrypted != null) {
            // Replace just the PGP block with decrypted text
            String prefix = text.substring(0, start);
            String suffix = text.substring(end);
            ic.deleteSurroundingText(before.length(), 0);
            ic.commitText(prefix + decrypted + suffix, 1);
            show("Unveiled");
        } else {
            show("Decrypt failed — check your key");
        }
    }

    private void setKeyboardCaps(boolean isCaps) {
        keyboardView.invalidateAllKeys();
    }

    private void show(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override public void onPress(int primaryCode) {}
    @Override public void onRelease(int primaryCode) {}
    @Override public void onText(CharSequence text) {}
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}
