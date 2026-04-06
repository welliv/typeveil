# Typeveil

A keyboard that encrypts your messages before any app sees them.

Works across Instagram, WhatsApp, X, Telegram, email — anywhere you type.

## The Problem

Every keyboard gives your text to the app. Typeveil changes that. You type, press Veil, and the app sees cipher instead of plaintext. No new app. No one to convince. Just a different keyboard.

## How It Works

```
You type → Press "Veil" → Ciphertext replaces plaintext → Send anywhere.
They receive → Press "Unveil" → Plaintext restores → Read.
```

One keyboard. Every app. Zero trust required.

## Install

```
Build: Android Studio → Build APK
Install: adb install -r app-release.apk
Enable: Settings → System → Languages & input → Virtual keyboard → Typeveil
```

## Key Exchange (The Hard Part, Made Simple)

PGP died because sharing public keys is social friction. Typeveil fixes this with Nostr.

### Option A: Manual (works today)

Generate your key pair → Copy your public key → Send it to your contact via any channel → They paste it in Settings → Import Recipient Key.

### Option B: Nostr (zero friction)

Publish your key once. Anyone finds it by typing your handle. No central server. No authority.

**Publish your key:**
```bash
cd nostr-tools && npm install
cat ~/my-pgp-pubkey.asc | node publish-key.js --nsec nsec1yourkey...
```

**Find someone's key:**
```bash
node resolve-key.js alice@example.com
```

Output is their armored PGP public key. Paste into Settings → Import Recipient Key. Done.

**What's happening under the hood:**
- **NIP-05** maps `name@example.com` → Nostr pubkey via DNS
- **Kind 30078** (arbitrary app data) stores the PGP block on relays like a decentralized key directory
- `resolve-key.js` queries relays, extracts the key, prints to stdout
- **The keyboard itself has zero network permission** — no crypto ever touches the internet, discovery happens off-device

## Security

- E2E encryption at keyboard level — apps never see plaintext
- RSA-2048 via OpenPGP (BouncyCastle 1.70)
- Keys in Android Keystore (hardware-backed where available)
- Private key encrypted with passphrase-derived AES-256
- FLAG_SECURE blocks screenshots and screen recordings
- Zero network calls from the keyboard — discovery happens off-device
- Memory zeroing after all cryptographic operations

## What It Won't Protect You From

A compromised device sees plaintext. A compromised recipient decrypts and screenshots. Keyloggers bypass the keyboard layer. These aren't Typeveil problems — they're device trust problems.

Every tool is limited by its threat model. Typeveil's is simple: *the platform is the adversary, and you want it to read nothing.*

## Tech

```
android/                          # The keyboard — network-free
├── java/.../TypeveilIME.java       # InputMethodService — Veil/Unveil keys
├── java/.../Crypto.java            # OpenPGP RSA-2048 encrypt/decrypt, Keystore
├── java/.../SettingsActivity.java  # Keys, recipients, settings UI
├── java/.../NostrKeyResolver.java  # NIP-05 HTTP resolver (lookup only)
├── res/xml/qwerty.xml              # Full QWERTY with Veil/Unveil keys
├── res/layout/settings.xml         # Minimal dark-mode settings UI
└── AndroidManifest.xml             # No INTERNET. No backup. FLAG_SECURE.

nostr-tools/                    # Key exchange companion (off-device)
├── resolve-key.js              # Resolve handle → PGP key via Nostr relays
├── publish-key.js              # Publish your PGP key to 5 public relays
└── package.json                # nostr-tools dependency
```

MIT.
