# Typeveil Architecture

## Vision

A privacy-first Android keyboard that uses PGP for cryptography and Nostr for identity. Two independent systems, one seamless experience.

The keyboard encrypts messages **before** any app reads them. No platform—Instagram, WhatsApp, X, Signal—sees plaintext. The user controls what is revealed.

## Principles

1. **Keyboard-first** — The encryption layer lives at the text source, not in individual apps
2. **Zero infrastructure** — No servers, no databases, no central authority
3. **Independent crypto** — PGP encryption runs entirely on-device with zero network access
4. **Decentralized identity** — Nostr handles public key discovery via the relay network
5. **Layered defense** — Android Keystore + passphrase encryption + OpenPGP + memory hygiene

## System Design

```
┌─────────────────────────────────────────────┐
│              Android Device                 │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │        Typeveil Keyboard             │    │
│  │                                     │    │
│  │  ┌─────────┐      ┌──────────────┐  │    │
│  │  │  Veil    │      │   Unveil     │  │    │
│  │  │  [🔒]   │      │   [🔓]       │  │    │
│  │  └────┬────┘      └─────┬────────┘  │    │
│  │       │                 │            │    │
│  │  ┌────┴─────────────────┴────────┐  │    │
│  │  │         Crypto.java           │  │    │
│  │  │  OpenPGP / BouncyCastle        │  │    │
│  │  │  RSA-2048 + AES-256-GCM        │  │    │
│  │  └──────────────┬────────────────┘  │    │
│  │                 │                    │    │
│  │  ┌──────────────┴────────────────┐  │    │
│  │  │       Android Keystore         │  │    │
│  │  │  AES-256-GCM master key        │  │    │
│  │  │  Hardware-backed where avail.  │  │    │
│  │  └───────────────────────────────┘  │    │
│  │                                     │    │
│  │  NO INTERNET PERMISSION             │    │
│  └─────────────────────────────────────┘    │
│                                             │
└──────────────────┬──────────────────────────┘
                   │ (off-device, companion only)
                   ▼
┌─────────────────────────────────────────────┐
│              Nostr Key Discovery             │
│                                             │
│  NostrKeyResolver.java (lookup only)        │
│  nostr-tools/ (publish-key.js, resolve)    │
│                                             │
│  NIP-05: alice@domain → pubkey via DNS      │
│  NIP-78: Pubkey → Kind 30078 PGP from relays│
│  NIP-19: nprofile QR → contact auto-import  │
│                                             │
└─────────────────────────────────────────────┘
```

## Data Flow: Encryption

```
1. User types message in any app
2. Typeveil captures text via InputConnection
3. User presses Veil key
4. handleVeil() gets text before cursor
5. Crypto.encrypt() called with recipient's public key
   a. Parse recipient's PGP public key from Preferences
   b. Create JcePublicKeyKeyEncryptionMethodGenerator
   c. Encrypt with AES-256 + integrity packet
   d. Return armored PGP ciphertext
6. Text before cursor deleted, ciphertext committed
7. App receives only ciphertext
```

## Data Flow: Decryption

```
1. User receives PGP-encrypted text in any app
2. Typeveil captures text via InputConnection
3. User presses Unveil key
4. handleUnveil() extracts PGP block from text
5. Crypto.decrypt() called
   a. Retrieve user's encrypted private key from Preferences
   b. Decrypt with Android Keystore AES-256-GCM
   c. Decrypt PGP private key with stored passphrase
   d. Open PGP ciphertext stream
   e. Read PGPLiteralData, return plaintext
6. Text before cursor deleted, plaintext committed
7. User sees decrypted message
```

## Data Flow: Key Exchange (via Nostr)

```
User A (Sender)                          User B (Recipient)
─────────────                            ────────────────
1. User B generates PGP key pair
2. User B publishes via:
   publish-key.js --nsec nsec1... pubkey.asc
   └─ Creates Kind 30078 event with PGP block
   └─ Signs with Nostr key
   └─ Publishes to 5+ relays

3. User A adds contact:
   resolve-key.js bob@nostr.com
   └─ NIP-05: HTTPS → .well-known/nostr.json
   └─ Gets bob's hex pubkey
   └─ NIP-78: Query relays for Kind 30078
   └─ Extracts PGP public key

4. User A imports key into Typeveil settings

5. Now User A can encrypt messages for User B
   by selecting them as recipient
```

## Security Model

### What's Protected
- **In-transit** — Apps receive only ciphertext, never plaintext
- **At-rest** — Private key encrypted with passphrase + Keystore
- **In-memory** — Byte arrays zeroed after cryptographic operations
- **Screen capture** — FLAG_SECURE blocks screenshots and recordings
- **Cloud backup** — `allowBackup="false"` prevents ADB/cloud extraction

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

### TypeveilIME.java
Input method service. Manages keyboard layout, captures text, delegates to Crypto.java for encrypt/decrypt. No network calls. No key material.

### Crypto.java
All cryptographic operations. PGP key generation, encryption, decryption. Android Keystore integration. Memory hygiene. SharedPreferences for encrypted storage.

### NostrKeyResolver.java
Lookup-only. Resolves NIP-05 handles to Nostr pubkeys. Stores resolved pubkeys for reference. No cryptographic operations.

### SettingsActivity.java
User-facing configuration. Key generation dialog, recipient key import/export, Nostr cache management, keyboard enable shortcut.

### publish-key.js / resolve-key.js
Companion CLI tools. Run on user's computer, not on the device. Handle Nostr relay communication for PGP key publish and discovery.

## Design Decisions

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
