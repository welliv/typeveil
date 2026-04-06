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
- ECC encryption via OpenPGP (BouncyCastle 1.70)
- Keys in Android Keystore (hardware-backed where available)
- Private key encrypted with AES-256-GCM, passphrase-derived
- FLAG_SECURE blocks screenshots and screen recordings
- Zero network calls, zero telemetry, zero infrastructure

## Tech

```
android/app/src/main/
├── java/.../TypeveilIME.java       # InputMethodService — the keyboard
├── java/.../Crypto.java            # OpenPGP encrypt/decrypt, Android Keystore
├── java/.../SettingsActivity.java  # Enable keyboard, generate keys
├── res/xml/
│   ├── qwerty.xml                  # Full QWERTY with Veil/Unveil keys
│   └── method.xml                  # IME registration
├── res/layout/
│   ├── keyboard.xml                # Keyboard view
│   └── settings.xml                # Minimal settings UI
└── AndroidManifest.xml
```

MIT.
