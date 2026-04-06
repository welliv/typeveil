# Typeveil — Keyboard Architecture

## Problem
Platform-controlled encryption means platform-controlled decryption. Instagram, WhatsApp, and every messaging app decides whether your messages stay private. They don't.

## Solution
Encrypt at the keyboard level — before the text reaches any app.

```
┌──────────────────────────────────────────────┐
│            Host App (Instagram, etc.)         │
│                                              │
│  ┌──────────────────────────────────────┐    │
│  │  Typeveil Keyboard (Your Code)       │    │
│  │                                      │    │
│  │  Type → [Veil] → Ciphertext → Send  │    │
│  │  Ciphertext → [Unveil] → Read       │    │
│  └──────────────────────────────────────┘    │
└──────────────────────────────────────────────┘
```

## Android Keyboard (Primary)

### InputMethodService
- Custom IME that replaces the default keyboard
- Intercepts every keystroke at the source
- "Veil" key → encrypts entire text field content
- "Unveil" key → decrypts any PGP message

### Key Storage
- Android Keystore (hardware-backed where available)
- Keys never leave the device
- Passphrase-protected (optional, v2)

### How It Works

1. User composes text in any app
2. Presses "Veil" key on Typeveil keyboard
3. Text encrypted with recipient's public key (cached locally)
4. Ciphertext replaces plaintext in the field
5. User sends through any app

Recipient does the same in reverse.

### Key Exchange
- Share public key via contact, QR, or link
- Import recipient keys from contact notes, clipboard, or scan
- No server needed

## Browser Extension (Secondary)

- Content script injects Veil button into text fields
- Service worker handles crypto
- Same OpenPGP protocol as Android

## No-Gos
- No iOS (App Store blocks custom keyboards from clipboard access)
- No servers
- No accounts
- No identity requirements

## Threat Model
- **Protected**: platform surveillance, data harvesting, subpoenas
- **Not protected**: endpoint malware, physical access, weak passphrases

MIT.
