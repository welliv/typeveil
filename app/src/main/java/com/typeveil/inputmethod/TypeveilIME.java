package com.typeveil.inputmethod;

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.typeveil.inputmethod.keyboard.TypeveilKeyboardView;

import java.util.Arrays;

/**
 * TypeVeil — PGP-encrypted Android keyboard.
 *
 * Architecture:
 * - Extends InputMethodService with custom KeyboardView
 * - VeilEngine handles encrypt/decrypt on background thread
 * - Zero network calls in the IME layer
 * - FLAG_SECURE enforced in onCreateInputView
 */
public class TypeveilIME extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener {

    private static final String TAG = "TypeveilIME";

    private TypeveilKeyboardView keyboardView;
    private Keyboard qwertyKeyboard;
    private Keyboard symbolKeyboard;
    private boolean capsLock = false;
    private boolean symbolMode = false;
    private boolean veilMode = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "TypeVeil IME created");
    }

    @Override
    public View onCreateInputView() {
        // Enforce FLAG_SECURE — no screenshots in keyboard area
        getWindow().getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE);

        keyboardView = (TypeveilKeyboardView) getLayoutInflater()
                .inflate(R.layout.view_keyboard, null);

        // Load keyboards from XML
        qwertyKeyboard = new Keyboard(this, R.xml.qwerty);
        symbolKeyboard = new Keyboard(this, R.xml.qwerty); // Uses same XML, mode switching via code

        keyboardView.setKeyboard(qwertyKeyboard);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);

        return keyboardView;
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        // Reset state for new input field
        capsLock = false;
        symbolMode = false;
        updateKeyboard();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        // Don't reset veilMode — user may want it persistent
    }

    // ==================== KeyboardView.OnKeyboardActionListener ====================

    @Override
    public void onPress(int primaryCode) {}

    @Override
    public void onRelease(int primaryCode) {}

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        Log.d(TAG, "onKey: " + primaryCode);

        // TypeVeil codes
        if (primaryCode == Constants.CODE_VEIL_ENCRYPT) {
            VeilEngine.handleEncrypt(this);
            return;
        }
        if (primaryCode == Constants.CODE_VEIL_DECRYPT) {
            VeilEngine.handleDecrypt(this);
            return;
        }

        // Functional keys
        if (primaryCode == Constants.CODE_SHIFT) {
            capsLock = !capsLock;
            updateKeyboard();
            return;
        }

        if (primaryCode == Constants.CODE_SWITCH_ALPHA_SYMBOL) {
            symbolMode = !symbolMode;
            updateKeyboard();
            return;
        }

        if (primaryCode == Constants.CODE_DELETE) {
            ic.deleteSurroundingText(1, 0);
            return;
        }

        if (primaryCode == Constants.CODE_OUTPUT_TEXT) {
            // Enter — send action if supported, otherwise newline
            if ((ic.getCursorCapsMode(EditorInfo.TYPE_CLASS_TEXT) &
                    EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ENTER);
            } else {
                ic.commitText("\n", 1);
            }
            return;
        }

        if (primaryCode == Constants.CODE_SETTINGS) {
            // Open settings
            startActivity(getPackageManager().getLaunchIntentForPackage(getPackageName())
                    .setClassName(getPackageName(), "com.typeveil.inputmethod.SettingsActivity")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }

        // Character keys
        if (primaryCode == android.inputmethodservice.Keyboard.KEYCODE_SHIFT) {
            capsLock = !capsLock;
            updateKeyboard();
            return;
        }

        if (primaryCode == android.inputmethodservice.Keyboard.KEYCODE_DELETE) {
            ic.deleteSurroundingText(1, 0);
            return;
        }

        if (primaryCode == android.inputmethodservice.Keyboard.KEYCODE_DONE) {
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ENTER);
            return;
        }

        if (primaryCode == android.inputmethodservice.Keyboard.KEYCODE_MODE_CHANGE) {
            symbolMode = !symbolMode;
            updateKeyboard();
            return;
        }

        // Regular character
        if (primaryCode > 0) {
            char c = (char) primaryCode;
            if (capsLock) c = Character.toUpperCase(c);
            ic.commitText(String.valueOf(c), 1);

            // Auto-unshift if not caps lock
            if (!capsLock && Character.isLowerCase((char) primaryCode)) {
                capsLock = false;
            }
        }
    }

    @Override
    public void onText(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
        }
    }

    @Override
    public void swipeLeft() {}

    @Override
    public void swipeRight() {}

    @Override
    public void swipeDown() {}

    @Override
    public void swipeUp() {}

    // ==================== Internals ====================

    private void updateKeyboard() {
        if (symbolMode) {
            keyboardView.setKeyboard(symbolKeyboard);
        } else {
            keyboardView.setKeyboard(qwertyKeyboard);
        }
        keyboardView.setVeilModeActive(veilMode);
    }
}
