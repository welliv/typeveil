package com.typeveil.inputmethod.nostr;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves PGP public keys via Nostr NIP-05.
 * 
 * NIP-05 flow:
 * 1. Fetch https://<domain>/.well-known/nostr.json?name=<local-part>
 * 2. Get Nostr pubkey
 * 3. Query relay for Kind 30078 event containing PGP key
 * 
 * Fallback: user pastes PGP key manually.
 * 
 * Network calls ONLY from SettingsActivity. IME never calls this.
 */
public final class NostrKeyResolver {

    private static final String TAG = "NostrKeyResolver";
    private static final int TIMEOUT_MS = 8000;
    private static final String PREFS = "typeveil_nostr_cache";

    // Fallback relays for Kind 30078 queries
    private static final String[] RELAYS = {
        "wss://relay.snort.social",
        "wss://relay.damus.io",
        "wss://nos.lol"
    };

    private NostrKeyResolver() {}

    /**
     * Resolve a NIP-05 handle → Nostr pubkey via HTTP.
     * Returns null on failure (never throws).
     */
    public static String resolveNip05(String nip05handle) {
        try {
            // Parse handle: user@domain.tld
            int idx = nip05handle.indexOf('@');
            if (idx == -1 || !nip05handle.contains(".")) return null;
            
            String localPart = nip05handle.substring(0, idx);
            String domain = nip05handle.substring(idx + 1);

            URL url = new URL("https://" + domain + "/.well-known/nostr.json?name=" + localPart);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");

            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();

            String json = sb.toString();
            // Simple parse: find "names":{"<localPart>":"<pubkey>"}
            String key = "\"names\"";
            int namesIdx = json.indexOf(key);
            if (namesIdx == -1) return null;
            
            String namesBlock = json.substring(namesIdx);
            String nameKey = "\"" + localPart + "\"";
            int nameIdx = namesBlock.indexOf(nameKey);
            if (nameIdx == -1) return null;
            
            // Find the value after the name
            int colonIdx = namesBlock.indexOf(':', nameIdx + nameKey.length());
            int quoteStart = namesBlock.indexOf('"', colonIdx + 1);
            int quoteEnd = namesBlock.indexOf('"', quoteStart + 1);
            if (quoteStart == -1 || quoteEnd == -1) return null;
            return namesBlock.substring(quoteStart + 1, quoteEnd);

        } catch (Exception e) {
            Log.w(TAG, "NIP-05 resolution failed", e);
            return null;
        }
    }

    /**
     * Resolve Nostr pubkey → PGP public key via relay WebSocket.
     * 
     * Sends REQ for Kind 30078 (parameterized replaceable event)
     * with d-tag = "pgp_key".
     * 
     * Returns armored PGP public key or null.
     */
    public static String resolvePgpFromNostr(String nostrPubkey) {
        // WebSocket in Android requires additional libraries.
        // For now, we use HTTP-based relay queries via nosec (nostr REST API proxies)
        // or direct WebSocket with java.net.Socket.
        
        try {
            // Use a relay that supports HTTP queries (nostrostr, relay.nostr.band)
            URL url = new URL("https://relay.nostr.band/api/v1/single?pubkey=" + nostrPubkey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            // Read response - nostr.band returns events as JSONL
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("\"kind\":30078")) {
                    // Extract PGP key from content
                    int contentIdx = line.indexOf("\"content\":\"");
                    if (contentIdx != -1) {
                        int start = contentIdx + "\"content\":\"".length();
                        int end = line.indexOf("\"", start);
                        if (end != -1) {
                            String content = line.substring(start, end)
                                    .replace("\\n", "\n")
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\");
                            if (content.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----")) {
                                sb.append(content);
                            }
                        }
                    }
                }
            }
            br.close();
            conn.disconnect();
            
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            Log.w(TAG, "Nostr PGP resolution failed", e);
            return null;
        }
    }

    /**
     * Async: resolve NIP-05 → Nostr pubkey → PGP key.
     * Returns CompletableFuture<PGP armor or null>.
     */
    @SuppressWarnings("unused")
    public static CompletableFuture<String> resolveAsync(String nip05handle) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        return CompletableFuture.supplyAsync(() -> {
            String nostrPubkey = resolveNip05(nip05handle);
            if (nostrPubkey == null) return null;
            return resolvePgpFromNostr(nostrPubkey);
        }, exec).whenComplete((r, e) -> exec.shutdown());
    }
}
