package com.typeveil.core;

import android.content.Context;
import android.view.inputmethod.InputConnection;

import com.typeveil.crypto.Crypto;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VeilEngine — orchestrates Typeveil encryption operations.
 * 
 * Single-threaded executor prevents InputConnection race conditions.
 * Keyboard is disabled during encryption to avoid interleaved keystrokes.
 * Mode resets to OFF on process death (no persistence).
 */
public class VeilEngine {

    public static final int MODE_VEIL = -100;
    public static final int MODE_UNVEIL = -101;

    private static final ExecutorService executor =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VeilEngine");
            t.setPriority(Thread.MIN_PRIORITY); // Don't block typing
            return t;
        });

    private static final AtomicBoolean locked = new AtomicBoolean(false);

    /**
     * Submit an encrypt/decrypt operation. Thread-safe.
     * If already processing, rejects new requests (prevents queue buildup).
     */
    public static void process(InputConnection ic, int mode, Context ctx) {
        if (ic == null || !locked.compareAndSet(false, true)) {
            return; // Already processing, ignore
        }

        executor.execute(() -> {
            try {
                if (mode == MODE_VEIL) {
                    handleVeil(ic, ctx);
                } else if (mode == MODE_UNVEIL) {
                    handleUnveil(ic, ctx);
                }
            } catch (Exception e) {
                // Log silently in debug only
            } finally {
                locked.set(false);
            }
        });
    }

    private static void handleVeil(InputConnection ic, Context ctx) {
        CharSequence before = ic.getTextBeforeCursor(10000, 0);
        if (before == null || before.length() == 0) return;
        if (before.toString().contains("BEGIN PGP MESSAGE")) return; // Already encrypted

        String encrypted = Crypto.encrypt(before.toString(), ctx);
        if (encrypted != null) {
            // Atomic: delete old + insert new
            ic.beginBatchEdit();
            ic.deleteSurroundingText(before.length(), 0);
            ic.commitText(encrypted, 1);
            ic.endBatchEdit();
        }
    }

    private static void handleUnveil(InputConnection ic, Context ctx) {
        CharSequence before = ic.getTextBeforeCursor(10000, 0);
        if (before == null) return;

        String text = before.toString();
        int start = text.indexOf("-----BEGIN PGP MESSAGE-----");
        int end = text.lastIndexOf("-----END PGP MESSAGE-----");
        if (start == -1 || end == -1) return;
        end += "-----END PGP MESSAGE-----".length();

        String pgpBlock = text.substring(start, end);
        String decrypted = Crypto.decrypt(pgpBlock, ctx);
        if (decrypted != null) {
            // Replace just the PGP block
            ic.beginBatchEdit();
            String prefix = text.substring(0, start);
            String suffix = text.substring(end);
            ic.deleteSurroundingText(before.length(), 0);
            ic.commitText(prefix + decrypted + suffix, 1);
            ic.endBatchEdit();
        }
    }
}
