# Typeveil

A keyboard that encrypts your messages before any app sees them.

Works across Instagram, WhatsApp, X, Telegram, email — anywhere you type.

## How It Works

```
You type → Press "Veil" → Ciphertext replaces plaintext → Send anywhere.
They receive → Press "Unveil" → Plaintext restores → Read.
```

One keyboard. Every app. Every platform.

## Install

```
Build: Android Studio → Build APK
Install: adb install -r app-release.apk
Enable: Settings → System → Languages & input → Virtual keyboard → Typeveil
```

## Security

- E2E encryption at the keyboard level — apps never see plaintext
- RSA-2048 key generation via OpenPGP (BouncyCastle 1.70)
- Keys in Android Keystore (hardware-backed where available)
- Private key encrypted with passphrase-derived AES-256
- FLAG_SECURE blocks screenshots and screen recordings
- Zero network calls, zero telemetry, zero infrastructure
- Memory zeroing after cryptographic operations

## What It Won't Protect You From

A compromised device sees plaintext. A compromised recipient decrypts and screenshots. Keyloggers bypass the keyboard layer. These are not Typeveil problems — they're device trust problems.

"The platform never sees plaintext" is true. "The platform is the attacker" is false — Typeveil assumes the platform is the adversary.

## Tech

```
android/app/src/main/
├── java/.../TypeveilIME.java       # InputMethodService — the keyboard
├── java/.../Crypto.java            # OpenPGP encrypt/decrypt, RSA-2048 keys, Keystore
├── java/.../SettingsActivity.java  # Enable keyboard, generate keys, manage recipients
├── res/xml/
│   ├── qwerty.xml                  # Full QWERTY with Veil/Unveil keys
│   └── method.xml                  # IME registration
├── res/layout/
│   ├── keyboard.xml                # Keyboard view
│   └── settings.xml                # Minimal settings UI
└── AndroidManifest.xml
```

MIT.
