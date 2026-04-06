# Typeveil

A keyboard-level encryption engine built on OpenBoard. Add PGP encryption to any app without switching keyboards.

Typeveil is a fork of [OpenBoard](https://github.com/openboard-team/openboard) with an integrated privacy layer: PGP encryption at the keyboard level, Nostr-based key discovery, zero infrastructure.

## What It Does

Every app sees your plaintext before you send it. Typeveil encrypts at the source — before any app reads what you type.

```
You type → [🔒 Veil] → Ciphertext → App receives encrypted text
They receive → [🔓 Unveil] → Plaintext → Decrypted before they see it
```

## Architecture

Two layered systems, independent but complementary:

### Layer 1 — PGP Encryption (the cryptography)

RSA-2048 OpenPGP keys generated on-device. Private keys encrypted with your passphrase, stored in Android Keystore (hardware-backed). Every message encrypted with AES-256. The keyboard service has **zero network permission** — cryptographic material never touches the internet.

### Layer 2 — Nostr Identity (the key exchange)

PGP died because sharing public keys is social friction. Typeveil uses Nostr to solve this:

- **NIP-05** — Human-readable handles (`alice@nostr.com`) resolve to pubkeys via DNS
- **NIP-19** — Bech32 contact strings (`npub1...`) shareable as QR codes
- **NIP-78** — Kind 30078 events store PGP public keys on relays, globally discoverable, revocable

Key discovery happens in Settings. The IME never makes network calls.

### How We Built It

Rather than building a keyboard from scratch, Typeveil is a fork of OpenBoard — a clean, telemetry-free, community-driven Android keyboard. We added only three integration layers:

```
integration/
├── core/              # VeilEngine, contact manager, mode toggle
├── crypto/            # Crypto.java — OpenPGP encrypt/decrypt
├── nostr/             # NostrKeyResolver — NIP-05 + Kind 30078 resolution
└── settings/          # SettingsActivity — keys, contacts, Nostr resolution
```

The rest of the codebase is OpenBoard, and we upstream our improvements back. This gives users prediction, gestures, auto-correct, themes, multi-language — everything they expect — minus the surveillance.

## Security

- **E2E encryption** at keyboard level — apps see only ciphertext
- **RSA-2048** via OpenPGP (BouncyCastle 1.70)
- **AES-256-GCM** with integrity packets
- **Android Keystore** — hardware-backed where available
- **Zero network** from IME — crypto never touches internet
- **Memory zeroing** — best-effort clearing of sensitive buffers
- **No backup** — `allowBackup="false"`, no Google extraction
- **No telemetry** — no analytics, crash reporting, or cloud sync
- **ProGuard/R8 hardened** — BouncyCastle classes preserved in release builds

### Threat Model

**Adversary:** The platform (Instagram, WhatsApp, X, Telegram)
**Goal:** Prevent them from reading user messages
**Method:** Encrypt at the keyboard before any app reads the text

**Out of scope:** Compromised device, recipient screenshots, physical access. Threat model: *the platform is the adversary, and you want it to read nothing.*

## Install

```
Build:  ./gradlew assembleRelease
Install: adb install -r integration/build/outputs/apk/release/typeveil-release.apk
Enable: Settings → System → Languages & input → Virtual keyboard → Typeveil
```

## Key Exchange

The hardest part of PGP is exchanging public keys. Typeveil uses Nostr to make it frictionless:

**Publish your key once:**
```bash
cd key-exchange && npm install
cat my-pubkey.asc | node publish-key.js --nsec nsec1yourkey...
```

**Find anyone's key:**
```bash
node resolve-key.js alice@example.com
```

**What happens:**
1. NIP-05 resolves `alice@example.com` → Nostr hex pubkey via HTTPS
2. Relays queried for Kind 30078 event tagged `typeveil-pgp-pubkey`
3. PGP public key extracted and printed to stdout
4. Import into Typeveil Settings → done

The keyboard itself has zero network permission. Discovery happens off-device. No crypto ever touches the internet.

## Structure

```
typeveil/
├── integration/          # Typeveil's encryption layer
│   ├── core/              # VeilEngine, contact manager, mode toggle
│   ├── crypto/            # Crypto.java — PGP encrypt/decrypt
│   ├── nostr/             # NostrKeyResolver — NIP-05 + Key discovery
│   └── settings/          # SettingsActivity — keys, contacts, Nostr
├── key-exchange/         # CLI tools for key publish/resolve
│   ├── publish-key.js     # Publish PGP key via NIP-78
│   └── resolve-key.js     # Resolve handle → PGP key from relays
└── docs/                 # Architecture, design decisions, build
```

The OpenBoard fork source lives in the `openboard/` directory (or is included as a submodule for clean upstream tracking).

## Upstream Policy

We fork OpenBoard, don't abandon it. Bug fixes, layout improvements, and non-Typeveil features are submitted upstream. Typeveil-specific code stays in the `integration/` directory. Our CI ensures every release works against the latest OpenBoard stable.

MIT.
