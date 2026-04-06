# TypeVeil

PGP-encrypted Android keyboard. Every keystroke stays yours.

## What It Does

TypeVeil sits between your thumbs and the app. Everything you type can be encrypted with PGP before the receiving app ever reads it. No cloud. No servers. No telemetry.

## Architecture

```
┌─────────────────────────────────────────────────┐
│                   TypeVeil IME                   │
│                                                   │
│  ┌──────────┐   ┌───────────┐   ┌────────────┐  │
│  │ Keyboard │──▶│ VeilEngine│──▶│  Crypto    │  │
│  │   View   │   │ (threads) │   │  (PGP)     │  │
│  └──────────┘   └───────────┘   └────────────┘  │
│                          │                        │
│                    ┌─────┴─────┐                  │
│                    │  Contact   │                  │
│                    │  Manager   │                  │
│                    └─────┬─────┘                  │
│                          │                        │
│                    ┌─────┴─────┐                  │
│                    │  NostrKey  │  (Settings only) │
│                    │ Resolver   │                  │
│                    └───────────┘                  │
└─────────────────────────────────────────────────┘
```

## Build

```bash
# Prerequisites: JDK 17, Android SDK (API 34)
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/sdk

./gradlew assembleDebug      # Debug APK (~9MB)
./gradlew assembleRelease    # Release APK, ProGuard shrunk (~5MB)
```

Output: `app/build/outputs/apk/`

## Security Model

- **Zero-network IME**: The keyboard itself never makes network calls. Only the Settings activity can resolve keys via Nostr.
- **Hardware-backed keys**: RSA-4096 PGP keypair sealed with AES-256-GCM. AES key lives in Android Keystore.
- **Memory clearing**: Sensitive arrays are zeroed after each crypto operation.
- **Screenshot protection**: FLAG_SECURE enforced in the keyboard window.
- **Clipboard cleared**: Clipboard is wiped after encryption to prevent plaintext leakage.
- **No telemetry**: Zero analytics, zero crash reporting, zero cloud dependencies.

## Stack

| Component | Details |
|-----------|---------|
| Min API | 23 (Android 6.0) |
| Target API | 34 (Android 14) |
| Cipher | RSA-4096 + AES-256-GCM |
| Crypto | BouncyCastle 1.78 |
| Language | Pure Java |
| Build | Gradle 8.2, AGP 8.2.0 |

## Install

1. Enable in Settings → System → Keyboard → Enable TypeVeil
2. Switch to TypeVeil as default keyboard
3. Open TypeVeil Settings → generate keys
4. Add contacts via PGP public key or Nostr resolution
5. Tap Veil to encrypt, Unveil to decrypt

## Threat Model

TypeVeil protects against:
- App-level keyloggers (encrypted before the app sees the text)
- Clipboard eavesdropping (auto-clear after encryption)
- Screenshot capture (FLAG_SECURE on keyboard window)
- Device extraction (hardware-backed key storage)

TypeVeil does *not* protect against:
- Compromised Android OS / root-level keyloggers
- Physical access + unlocked device
- Compromised OpenBoard base (we build from scratch, no fork debt)

## License

Apache 2.0
