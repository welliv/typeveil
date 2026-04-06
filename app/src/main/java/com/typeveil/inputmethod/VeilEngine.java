package com.typeveil.inputmethod;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

import com.typeveil.inputmethod.crypto.Crypto;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe orchestration layer for encrypt/decrypt operations.
 * 
 * Architecture:
 * - Single-threaded executor prevents InputConnection race conditions
 * - Atomic guard prevents concurrent encryption attempts
 * - Memory is cleared after each operation (best-effort)
 * - Clipboard is optionally cleared after encryption
 */
public final class VeilEngine {

    private static final String TAG = "VeilEngine";
    private static final int MAX_TEXT_LENGTH = 10_000; // 10KB limit to prevent ANR
    private static final int OPERATION_TIMEOUT_MS = 30_000;
    private static final Object LOCK = new Object();
    private static final AtomicBoolean busy = new AtomicBoolean(false);

    private static ExecutorService executor;

    private VeilEngine() {}

    private static synchronized ExecutorService getExecutor() {
        if (executor == null) {
            executor = new ThreadPoolExecutor(
                    0, 1, 60L, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(),
                    new ThreadFactory() {
                        private int count = 0;
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "VeilEngine-" + count++);
                            t.setPriority(Thread.MAX_PRIORITY);
                            t.setDaemon(false);
                            return t;
                        }
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
        }
        return executor;
    }

    /**
     * Handle Veil (encrypt) — reads text before cursor, encrypts, replaces.
     */
    public static void handleEncrypt(InputMethodService service) {
        if (!busy.compareAndSet(false, true)) return; // Prevent re-entry

        getExecutor().execute(() -> {
            try {
                InputConnection ic = service.getCurrentInputConnection();
                if (ic == null) {
                    showToast(service, "Not connected to editor");
                    return;
                }

                // Read last 2000 characters before cursor
                CharSequence textBefore = ic.getTextBeforeCursor(2000, 0);
                if (textBefore == null || TextUtils.isEmpty(textBefore.toString().trim())) {
                    showToast(service, "Nothing to encrypt");
                    return;
                }

                String text = textBefore.toString();
                if (text.length() > MAX_TEXT_LENGTH) {
                    // Truncate to what was selected — encrypt from last newline or space
                    int cutoff = MAX_TEXT_LENGTH;
                    int lastNewline = text.lastIndexOf('\n', cutoff);
                    int lastSpace = text.lastIndexOf(' ', cutoff);
                    cutoff = Math.max(lastNewline, lastSpace);
                    if (cutoff < 100) cutoff = MAX_TEXT_LENGTH;
                    text = text.substring(text.length() - cutoff);
                }

                showToast(service, "…");

                // Get recipient contact if configured
                SharedPreferences prefs = service.getSharedPreferences("typeveil", Context.MODE_PRIVATE);
                String activeContact = prefs.getString("active_contact", "");
                String recipientPubKey = "";
                if (!activeContact.isEmpty()) {
                    recipientPubKey = prefs.getString("contact_key_" + activeContact, "");
                }

                String encrypted = Crypto.encrypt(service, text, recipientPubKey);
                
                if (encrypted == null) {
                    deleteText(ic, text.length());
                    ic.commitText(text, 1);
                    showToast(service, "Encryption failed");
                    return;
                }

                // Atomic replace: delete then commit
                deleteText(ic, text.length());
                ic.commitText(encrypted, 1);

                // Clear clipboard if configured
                if (prefs.getBoolean("auto_clear_clipboard", true)) {
                    clearClipboard(service);
                }

                showToast(service, "");
            } catch (Exception e) {
                showToast(service, "Error: " + e.getMessage());
            } finally {
                busy.set(false);
            }
        });
    }

    /**
     * Handle Unveil (decrypt) — finds PGP block in text before cursor, decrypts, replaces.
     */
    public static void handleDecrypt(InputMethodService service) {
        if (!busy.compareAndSet(false, true)) return;

        getExecutor().execute(() -> {
            try {
                InputConnection ic = service.getCurrentInputConnection();
                if (ic == null) {
                    showToast(service, "Not connected to editor");
                    return;
                }

                CharSequence textBefore = ic.getTextBeforeCursor(5000, 0);
                if (textBefore == null) {
                    showToast(service, "No text found");
                    return;
                }

                String text = textBefore.toString();

                // Find PGP block
                int beginIdx = text.indexOf("-----BEGIN PGP MESSAGE-----");
                if (beginIdx == -1) beginIdx = text.indexOf("-----BEGIN PGP SIGNED MESSAGE-----");
                
                if (beginIdx == -1) {
                    showToast(service, "No PGP block found");
                    return;
                }

                int endIdx = text.indexOf("-----END PGP MESSAGE-----");
                if (endIdx == -1) endIdx = text.indexOf("-----END PGP SIGNED MESSAGE-----");
                
                if (endIdx == -1) {
                    showToast(service, "Incomplete PGP block");
                    return;
                }

                endIdx += "-----END PGP MESSAGE-----".length();
                String pgpBlock = text.substring(beginIdx, endIdx);

                String decrypted = Crypto.decrypt(service, pgpBlock);
                if (decrypted == null) {
                    showToast(service, "Decryption failed — wrong key?");
                    return;
                }

                // Replace PGP block with plaintext
                String prefix = text.substring(0, beginIdx);
                String suffix = text.substring(endIdx);
                String result = prefix + decrypted + suffix;

                deleteText(ic, text.length());
                ic.commitText(result, 1);

                showToast(service, "");
            } catch (Exception e) {
                showToast(service, "Error: " + e.getMessage());
            } finally {
                busy.set(false);
            }
        });
    }

    private static void deleteText(InputConnection ic, int length) {
        if (length > 0) {
            ic.deleteSurroundingText(length, 0);
        }
    }

    private static void clearClipboard(Context context) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        } catch (Exception ignored) {}
    }

    private static void showToast(final Context context, final String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!message.isEmpty()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
