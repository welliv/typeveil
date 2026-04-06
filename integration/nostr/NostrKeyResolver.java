package com.typeveil.nostr;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Nostr resolver for Typeveil — NIP-05 + Kind 30078 key discovery.
 *
 * Purpose: Resolve alice@example.com → PGP public key, fully on-device.
 * The keyboard stays network-free for cryptographic material.
 * Key resolution happens here (HTTPS + WebSocket for relay queries).
 *
 * Flow:
 *   1. HTTPS GET https://{domain}/.well-known/nostr.json?name={local}
 *   2. Extract Nostr pubkey from "names" object
 *   3. WebSocket query relay for Kind 30078 event (PGP pubkey)
 *   4. Return armored PGP public key
 *
 * Uses only Android SDK — no external HTTP or WebSocket libraries.
 */
public class NostrKeyResolver {

    private static final int HTTP_TIMEOUT_MS = 5000;
    private static final int WS_TIMEOUT_MS = 8000;

    private static final String[] DEFAULT_RELAYS = {
        "relay.damus.io",
        "relay.nostr.band",
        "nos.lol",
    };

    /**
     * Full resolution pipeline: handle → NIP-05 → Kind 30078 → PGP key
     * Returns armored PGP public key string, or null on failure.
     */
    public static String resolvePgpKey(String nip05Handle) {
        // Step 1: NIP-05 resolve
        String nostrPubkey = resolvePubkey(nip05Handle);
        if (nostrPubkey == null) return null;

        // Step 2: Query relays for Kind 30078
        return fetchPgpFromRelays(nostrPubkey);
    }

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
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
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
     * Query Nostr relays for Kind 30078 event containing a PGP public key.
     * Returns the armored PGP key content, or null if not found.
     */
    private static String fetchPgpFromRelays(String pubkey) {
        for (String relay : DEFAULT_RELAYS) {
            try {
                String result = queryRelayKinds30078(relay, pubkey, "typeveil-pgp-pubkey");
                if (result != null && result.contains("BEGIN PGP PUBLIC KEY BLOCK")) {
                    return result;
                }
            } catch (Exception ignored) {
                // Try next relay
            }
        }
        return null;
    }

    /**
     * Connect to a single relay via WebSocket, query for Kind 30078 event,
     * and return the event content (armored PGP key).
     * Uses raw TLS socket + HTTP upgrade — no external libraries.
     */
    private static String queryRelayKinds30078(String relayHost, String pubkey, String dTag) {
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            Socket socket = factory.createSocket(relayHost, 443);

            // WebSocket upgrade
            String key = java.util.UUID.randomUUID().toString();
            String upgrade = "GET / HTTP/1.1\r\n"
                + "Host: " + relayHost + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: Typeveil\r\n"
                + "\r\n";

            OutputStream out = socket.getOutputStream();
            out.write(upgrade.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Read upgrade response
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
            for (int i = 0; i < 10; i++) {
                String line = reader.readLine();
                if (line != null && line.trim().isEmpty()) break;
            }

            // Send REQ
            String reqId = "tv_req";
            String filter = "[\"" + reqId + "\",{\"kinds\":[30078],\"authors\":[\"" + pubkey + "\"],\"#d\":[\"" + dTag + "\"],\"limit\":1}]";

            out.write(filter.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();

            // Read response (EVENT frame)
            StringBuilder response = new StringBuilder();
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < WS_TIMEOUT_MS) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) break;
                    response.append(line);
                    if (line.contains("EVENT")) break;
                }
                Thread.sleep(100);
            }

            socket.close();

            // Parse event content from JSON response
            if (response.length() > 0) {
                JSONArray arr = new JSONArray(response.toString().trim());
                if (arr.length() > 0 && arr.get(0).equals("EVENT")) {
                    JSONObject event = arr.getJSONObject(2);
                    if (event.has("content")) {
                        return event.getString("content");
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /**
     * Store a resolved pubkey for later reference.
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
