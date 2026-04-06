package com.typeveil.keyboard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Nostr NIP-05 resolver for Typeveil.
 *
 * Purpose: Resolve alice@example.com → Nostr pubkey.
 * The keyboard itself stays network-free for crypto.
 * This is a lookup-only helper for Settings that uses
 * Android's built-in HTTP (no extra dependencies).
 *
 * Flow:
 *   1. HTTPS GET https://{domain}/.well-known/nostr.json?name={local}
 *   2. Extract pubkey from "names" object
 *   3. Caller uses pubkey to query relays (via nostr-tools CLI)
 *      or imports a PGP key they have separately
 *
 * The relay query for kind 30078 PGP keys lives in nostr-tools/
 * as resolve-key.js — an offline companion, not APK code.
 */
public class NostrKeyResolver {

    private static final int TIMEOUT_MS = 5000;

    /**
     * Resolve a NIP-05 handle to its Nostr public key.
     * Returns hex pubkey string, or null on failure.
     */
    public static String resolvePubkey(String nip05Handle) {
        int atIdx = nip05Handle.lastIndexOf('@');
        if (atIdx <= 0 || atIdx >= nip05Handle.length() - 1) {
            return null;
        }
        String local = nip05Handle.substring(0, atIdx);
        String domain = nip05Handle.substring(atIdx + 1);

        String urlString = "https://" + domain
            + "/.well-known/nostr.json?name=" + local;

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Typeveil");

            if (conn.getResponseCode() != 200) {
                return null;
            }

            String body = readStream(conn.getInputStream());
            JSONObject json = new JSONObject(body);

            if (!json.has("names")) return null;
            JSONObject names = json.getJSONObject("names");
            if (!names.has(local)) return null;

            return names.getString(local);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Store a resolved pubkey for later reference.
     * The CLI tool (resolve-key.js) uses this pubkey to
     * fetch the kind 30078 event from relays.
     */
    public static void storeResolvedPubkey(Context ctx, String handle, String pubkey) {
        ctx.getSharedPreferences("typeveil_nostr_cache", Context.MODE_PRIVATE)
            .edit()
            .putString(handle.toLowerCase(), pubkey)
            .apply();
    }

    public static String getCachedPubkey(Context ctx, String handle) {
        return ctx.getSharedPreferences("typeveil_nostr_cache", Context.MODE_PRIVATE)
            .getString(handle.toLowerCase(), null);
    }

    private static String readStream(InputStream is) throws Exception {
        BufferedReader r = new BufferedReader(
            new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        return sb.toString();
    }
}
