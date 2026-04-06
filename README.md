# Typeveil

A keyboard-level encryption engine. PGP for cryptography. Nostr for identity. Zero infrastructure.

Type your message. Press **Veil**. Every app receives ciphertext. Press **Unveil**. Plaintext returns.

Works across Instagram, WhatsApp, X, Telegram, email — anywhere you type.

## What It Does

Typeveil solves a blind spot: keyboards feed plaintext to apps before you send. Typeveil sits between your fingers and the app, encrypting before anything is read.

```
You type → [Veil] → Ciphertext → App sees only encrypted text
They receive → [Unveil] → Plaintext → Read in private
```

No new social network. No servers. No one to convince. Just a different keyboard.

## The Architecture

Two layered systems, independent but complementary:

### Layer 1 — PGP Encryption (the cryptography)

RSA-2048 OpenPGP keys generated on-device. Private keys encrypted with your passphrase, stored in Android Keystore (hardware-backed). Every message encrypted with AES-256. The keyboard itself has **zero network permission** — cryptographic material never touches the internet.

### Layer 2 — Nostr Identity (the key exchange)

PGP died because sharing public keys is social friction. Typeveil uses Nostr to solve this:

- **NIP-05** — Human-readable handles (`alice@nostr.com`) resolve to pubkeys via DNS
- **NIP-19** — Bech32 contact strings (`npub1...`) shareable as QR codes or text
- **NIP-78** — Kind 30078 events store PGP public keys on relays, globally discoverable, revocable

Key discovery happens off-device. The keyboard only sees resolved public keys.

### Flow

```
┌─────────────────────────────────────┐
│          Typeveil Keyboard          │
│                                     │
│  ┌─────────┐    ┌─────────────┐     │
│  │  PGP    │    │  Keystore   │     │
│  │ Encrypt │    │  (Android)  │     │
│  │Decrypt  │    │             │     │
│  └────┬────┘    └──────┬──────┘     │
│       │                │            │
│       └──── Recipients ────────────┘ │
│                                     │
└──────────────┬──────────────────────┘
               │ resolved pubkeys only
               ▼
     Nostr Key Discovery (off-device)
     ├── NIP-05: alice@nostr.com → pubkey
     ├── Kind 30078: PGP pubkey from relays
     └── NIP-19: nprofile QR import
```

## Install

```
Build:  Build APK
Install: adb install app-release.apk
Enable: Settings → System → Languages & input → Virtual keyboard → Typeveil
```

## Quick Start

### 1. Enable Typeveil
Open the app → **Enable Keyboard** → toggle Typeveil on in system settings.

### 2. Generate Your Keys
Tap **Generate Key Pair** → enter a passphrase (minimum 8 characters).

This creates an RSA-2048 key pair. The private key is encrypted with your passphrase and protected by Android Keystore. Your public key is ready to share.

### 3. Share Your Identity

**Option A — Via Nostr (recommended):**
```bash
cd nostr-tools
cat my-pubkey.asc | node publish-key.js --nsec nsec1yourkey...
```
Your PGP key is now discoverable on Nostr relays. Anyone can find it with your handle.

**Option B — Manual (works offline):**
Settings → **Export Public Key** → copy → share with contacts via any channel.

### 4. Add Contacts

**Via Nostr (zero friction):**
Tap **Add Contact** → type `alice@example.com` → resolves via NIP-05 → fetches PGP key from relays.

**Via NIP-19 QR:**
Tap **Scan nprofile** → scan their Nostr profile QR → auto-resolves.

**Via Manual Import:**
Tap **Import Recipient Key** → paste their armored PGP public key.

### 5. Encrypt
Type your message in any app. Press **Veil** (🔒 key on the bottom row). Plaintext is replaced with `-----BEGIN PGP MESSAGE-----`. Send.

### 6. Decrypt
Receive a PGP message. Make sure Typeveil is active. Press **Unveil** (🔓 key). Ciphertext is replaced with the decrypted message.

## Key Exchange

The hardest part of PGP is actually exchanging public keys. Typeveil uses Nostr to make it frictionless:

**Publish your key once:**
```bash
cd nostr-tools && npm install
node publish-key.js --nsec nsec1... my-pubkey.asc
```

**Find anyone's key:**
```bash
node resolve-key.js alice@example.com
```

**What happens:**
1. NIP-05 resolves `alice@example.com` → Nostr hex pubkey via HTTPS
2. Relays queried for Kind 30078 event tagged `typeveil-pgp-pubkey`
3. PGP public key extracted and printed to stdout
4. Paste into Typeveil settings → done

The keyboard itself has zero network permission. Discovery happens off-device. No crypto ever touches the internet.

## Security

- **E2E encryption** at the keyboard level — apps see only ciphertext
- **RSA-2048** via OpenPGP (BouncyCastle 1.70)
- **AES-256-GCM** for message encryption with integrity packets
- **Android Keystore** — private keys hardware-backed where available
- **Passphrase protection** — private key encrypted with AES-256 PBE
- **FLAG_SECURE** — blocks screenshots and screen recordings
- **Zero network** from the keyboard — no crypto touches the internet
- **Memory zeroing** — best effort clearing of sensitive byte arrays
- **No backup** — `allowBackup="false"` prevents cloud extraction
- **No analytics** — no Google Play Services, no Firebase, no telemetry

### What It Won't Protect You From

A compromised device sees plaintext before encryption. A compromised recipient decrypts and screenshots. Keyloggers at the OS layer bypass the keyboard. Device trust is outside Typeveil's scope.

The threat model is simple: **the platform is the adversary, and you want it to read nothing.**

## Structure

```
android/
├── app/src/main/
│   ├── java/com/typeveil/keyboard/
│   │   ├── TypeveilIME.java            # Input method — Veil/Unveil keys
│   │   ├── Crypto.java                 # OpenPGP RSA-2048 encrypt/decrypt
│   │   ├── NostrKeyResolver.java       # NIP-05 HTTP + Kind 30078 relay query
│   │   └── SettingsActivity.java        # Keys, contacts, settings
│   ├── res/
│   │   ├── layout/keyboard.xml         # Keyboard layout with Veil/Unveil
│   │   ├── layout/settings.xml          # Settings UI
│   │   └── xml/qwerty.xml              # QWERTY definition
│   └── AndroidManifest.xml             # No INTERNET. No backup.
│
nostr-tools/
├── publish-key.js                      # Publish PGP key via Kind 30078
├── resolve-key.js                       # Resolve handle → PGP key from relays
└── package.json                        # nostr-tools dependency
```

## Web Extension

A browser variant is included in `web-extension/`. It provides the same encrypt/decrypt flow as a content script injection, for users who primarily type in browsers.

MIT.
