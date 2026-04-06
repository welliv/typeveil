# Typeveil Architecture

## Vision

A keyboard-level encryption engine that uses PGP for cryptography and Nostr for identity. Fork of OpenBoard with a privacy layer. Two independent systems, one seamless experience.

The keyboard encrypts messages **before** any app reads them. No platform—Instagram, WhatsApp, X, Telegram—sees plaintext. The user controls what is revealed.

## Principles

1. **Keyboard-first** — The encryption layer lives at the text source, not in individual apps
2. **Zero infrastructure** — No servers, no databases, no central authority
3. **Independent crypto** — PGP encryption runs entirely on-device with zero network access
4. **Decentralized identity** — Nostr handles public key discovery via the relay network
5. **Fork, don't fork from scratch** — Leverage OpenBoard's proven typing engine, focus on privacy
6. **Upstream everything generic** — Typing improvements go upstream. Privacy stays in integration/.

## System Design

```
┌─────────────────────────────────────────────┐
│              OpenBoard Base                  │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │         LatinIME.java                │    │
│  │  Prediction, gestures, themes, etc.  │    │
│  │  Community-maintained, upstreamed    │    │
│  └──────────────┬──────────────────────┘    │
│                 │                            │
│  ┌──────────────┴──────────────────────┐    │
│  │         VeilEngine.java              │    │
│  │  (integration/core/)                 │    │
│  │                                      │    │
│  │  Veil:  encrypt text → ciphertext    │    │
│  │  Unveil: decrypt → plaintext         │    │
│  └──────────────┬──────────────────────┘    │
│                 │                            │
│  ┌──────────────┴──────────────────────┐    │
│  │         Crypto.java                  │    │
│  │  (integration/crypto/)              │    │
│  │                                      │    │
│  │  OpenPGP / BouncyCastle              │    │
│  │  RSA-2048 + AES-256-GCM              │    │
│  │  Android Keystore (hardware-back)    │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │  NostrKeyResolver.java               │    │
│  │  (integration/nostr/)               │    │
│  │                                      │    │
│  │  NIP-05 → pubkey                    │    │
│  │  Kind 30078 → PGP public key         │    │
│  │                                      │    │
│  │  NO CRYPTO. LOOKUP ONLY.            │    │
│  └─────────────────────────────────────┘    │
│                                             │
└─────────────────────────────────────────────┘
```

## Data Flow: Encryption

```
1. User types message in any app (via OpenBoard)
2. User presses Veil key [-100] → VeilEngine.handle(IC, MODE_VEIL)
3. VeilEngine locks InputConnection, disables keyboard
4. Crypto.encrypt() called with selected recipient's public key
   a. Parse recipient's PGP public key from Preferences
   b. Create JcePublicKeyKeyEncryptionMethodGenerator
   c. Encrypt with AES-256 + integrity packet
   d. Return armored PGP ciphertext
5. VeilEngine commits ciphertext, re-enables keyboard
6. App receives only ciphertext
```

## Data Flow: Decryption

```
1. User receives PGP-encrypted text in any app
2. User presses Unveil key [-101] → VeilEngine.handle(IC, MODE_UNVEIL)
3. VeilEngine extracts PGP block from context
4. Crypto.decrypt() called
   a. Retrieve user's encrypted private key from Preferences
   b. Decrypt with Android Keystore AES-256-GCM
   c. Decrypt PGP private key with stored passphrase
   d. Open PGP ciphertext stream, read plaintext
5. VeilEngine commits plaintext, re-enables keyboard
6. User sees decrypted message
```

## Data Flow: Key Exchange (via Nostr)

```
User A (Sender)                          User B (Recipient)
─────────────                            ────────────────
1. User B generates PGP key pair
2. User B publishes via:
   node publish-key.js --nsec nsec1... pubkey.asc
   └─ Creates Kind 30078 event with PGP block
   └─ Signs with Nostr key
   └─ Publishes to 5+ relays

3. User A adds contact:
   Settings → Add Contact → alice@nostr.com
   └─ NIP-05: HTTPS → .well-known/nostr.json → pubkey
   └─ Kind 30078: Query relays for pubkey → PGP key
   └─ Stored as contact in Typeveil

4. User A selects contact → encrypts message

5. Alice receives → Unveils → reads message
```

## Security Model

### What's Protected
- **In-transit** — Apps receive only ciphertext, never plaintext
- **At-rest** — Private key encrypted with passphrase + Keystore
- **In-memory** — Byte arrays zeroed after cryptographic operations
- **Screen capture** — FLAG_SECURE blocks screenshots and recordings
- **Cloud backup** — `allowBackup="false"` prevents ADB/cloud extraction
- **Clipboard leakage** — Clipboard cleared after encryption operations

### Trust Assumptions
- Android OS is not compromised (root, keyloggers, accessibility services)
- Recipient's private key is secure
- Nostr relays serve valid Kind 30078 events (verified by PGP parsing)
- NIP-05 HTTPS connections use valid TLS certificates

### Threat Model
**Adversary:** The platform (Instagram, X, Telegram, etc.)
**Goal:** Prevent them from reading user messages
**Method:** Encrypt at the keyboard level before any app reads the text

### Out of Scope
- Compromised device (root, malware, keyloggers)
- Recipient-side compromise (decrypt + screenshot)
- Physical access to unlocked device
- Side-channel attacks (power analysis, timing)

## Component Responsibilities

### OpenBoard (Base)
- Keyboard layout, input method, prediction, gestures, themes
- Upstream community, regular releases

### VeilEngine (integration/core/)
- Orchestrates encrypt/decrypt operations
- Manages InputConnection locking (single-threaded executor)
- Handles mode state (VEIL_ACTIVE / VEIL_INACTIVE)
- Contact selection UI integration

### Crypto.java (integration/crypto/)
- All cryptographic operations. PGP key generation, encryption, decryption. Android Keystore integration. Memory hygiene. SharedPreferences for encrypted storage. **Zero network calls.**

### NostrKeyResolver.java (integration/nostr/)
- Lookup-only. Resolves NIP-05 handles to Nostr pubkeys. Queries Kind 30078 events. Caches results with fingerprint verification. **No cryptographic operations.**

### SettingsActivity (integration/settings/)
- User-facing configuration. Key generation dialog, recipient key import/export, Nostr contact management. Runs in isolation from the IME.

### publish-key.js / resolve-key.js (key-exchange/)
- Companion CLI tools. Run on user's computer, not on the device. Handle Nostr relay communication for PGP key publish and discovery.

## Design Decisions

### Why OpenBoard
- Clean AOSP fork with no telemetry or Google dependencies
- Java-based — matches Typeveil crypto codebase exactly
- ~30% less code than GBoard — easy to understand and extend
- Active community, proven typing engine

### Why PGP over NIP-44
- PGP is standard and interoperable — recipients can use GPG, OpenKeychain, etc.
- NIP-44 requires both parties to have Nostr accounts and secp256k1 keys
- PGP works with any recipient — they don't need Typeveil or Nostr

### Why Two Systems
- Crypto (PGP) stays isolated on-device, network-free. Proven model.
- Identity (Nostr) handles the hard part: discovering who to encrypt for.
- Either system works independently. Together they solve PGP's weakest link.

### Why Android Keystore
- Hardware-backed where available (TEE, StrongBox)
- Separation from app-level storage
- Tamper-resistant — even rooted devices require Keystore compromise
- Free, built into Android. No external dependency.

### Why Key 30078
- Arbitrary app data — designed exactly for this use case
- Replaceable events — user can revoke/update their PGP key
- Addressable by (pubkey, d-tag) — deterministic query pattern
- No central registry — decentralized, censorship-resistant
