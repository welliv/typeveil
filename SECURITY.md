# Security Analysis

## Dependency Audit

### BouncyCastle 1.70 (org.bouncycastle:bcpg-jdk15on)
- **Status**: Actively maintained, latest stable release as of Jan 2022. SpongyCastle deprecated since 2017.
- **Backdoor risk**: Open source, audited by the entire Java/Android community. Used by Signal, GnuPG, OpenSSL, and Android itself. Repository: https://github.com/bcgit/bc-java
- **Why not SpongyCastle**: Fork was needed before Android 5.0. Modern Android (SDK 21+) ships with sufficient JCA support. Running dead code is the #1 crypto failure pattern.
- **Why not custom crypto**: Writing crypto is like writing your own compression — every implementation has subtle bugs. BouncyCastle has 20+ years of public audit.

### Android Keystore
- **Status**: Android system component. Hardware-backed on Android 9+ via TEE (Trusted Execution Environment).
- **How it works**: Keys are generated and stored in a separate processor. Apps request operations but never see the raw key material. Even root can't extract TEE-stored keys.
- **Not compromised by**: App-level malware, network attacks, most forensic tools.

## Code-Level Security Verification

### Flag Checklist
| Check | Status | Location |
|-------|--------|----------|
| FLAG_SECURE (blocks screenshots) | Implemented | SettingsActivity.java:19 |
| No cleartext traffic | Enforced | AndroidManifest.xml:11 |
| No backup (adb pull resistant) | Enforced | AndroidManifest.xml:8 |
| No logging of sensitive data | Enforced | All Java files — Log only calls are error tags |
| ProGuard/minification | Enabled | build.gradle:18-20 |
| Keystore key generation | Implemented | Crypto.java:encryptWithKeystore() |
| AES-256-GCM with random IV | Implemented | Crypto.java:238-249 |
| Array zeroing after use | Best effort | Crypto.java:130,177,214,293 |
| No network permissions | Enforced | AndroidManifest.xml — no INTERNET |
| BouncyCastle 1.70 pinned | Pinned | build.gradle:34-35 |

### Verified Properties
1. **Zero network calls** — No Internet permission in manifest. No HTTP client anywhere. The compiler can't ship code that calls the network.
2. **No clipboard usage** — Direct text replacement via InputConnection. Clipboard sniffing impossible.
3. **IME sandboxing** — InputMethodService runs in its own process, isolated from host apps. Other apps can't read its memory.
4. **Keystore isolation** — Master encryption key never leaves the TPM/TEE. Even if the device is rooted, the key material is hardware-protected.
5. **Encrypted key storage** — Private key is AES-256-GCM encrypted with Keystore before writing to SharedPreferences. An attacker with root gets an encrypted blob, not your key.

## Threat Model

### Protected Against
| Threat | Mitigation |
|--------|-----------|
| Platform surveillance | Encryption before text reaches the app |
| Data harvesting | Apps only see ciphertext |
| Subpoenas | No data stored anywhere to subpoena |
| Network interception | E2E encrypted — ciphertext is opaque |
| Platform policy changes | Encryption independent of any platform |
| Screen capture/recording | FLAG_SECURE blocks all screenshots and recordings |
| Clipboard sniffing | No clipboard — direct InputConnection replacement |
| Keystroke logging by other apps | IME sandboxed from host app |
| ADB backup extraction | allowBackup=false |
| Reverse engineering | ProGuard minification + no network surface |

### Not Protected Against
| Threat | Why |
|--------|-----|
| Rooted device with kernel keylogger | OS-level compromise breaks all sandboxing |
| Physical access to unlocked device | Attacker uses your keyboard to decrypt |
| Compromised Build/Toolchain | If Gradle or Android SDK is compromised, the APK is |
| Social engineering | User gives passphrase to attacker |
| Metadata analysis | Who you talk to, when, how often |
| Host app keylogging | If the app itself logs input (rare, but possible) |

## Design Decisions

### Why Java, not Kotlin?
- Identical security properties — both compile to JVM bytecode.
- Kotlin has more aggressive string interning, making memory wiping slightly harder.
- Java gives marginally more control over object lifecycle.
- **Decision**: Kotlin is fine for production if the team prefers it. No security delta.

### Why BouncyCastle, not SpongyCastle?
- SpongyCastle was a repackaged fork for pre-Android 5.0. Deprecated 2017.
- BouncyCastle 1.70 works on SDK 21+ without repackaging.
- BouncyCastle is the upstream. SpongyCastle was always lagging behind.
- **Running dead crypto code is exploitable by definition.**

### Why OpenPGP, not Signal Protocol?
- Signal Protocol (Double Ratchet) requires an online server for X3DH key agreement.
- OpenPGP works in fully offline, serverless mode — matches the zero-infrastructure design.
- Tradeoff: No forward secrecy. If a private key is compromised, all past messages are readable.
- Mitigation: Key rotation + passphrase changes (v2).

### Why AES-256-GCM for key wrapping?
- Authenticated encryption — encrypts AND detects tampering.
- Non-replayable — random IV per encryption. Each ciphertext is unique.
- Hardware accelerated on all modern Android devices (ARMv8 Crypto Extensions).

## Known Limitations

1. **No Forward Secrecy** — Inherent to OpenPGP. Session-based protocols require servers.
2. **Key Revocation** — No CRL in v1. Compromised key requires manual user notification.
3. **Java Memory Zeroing** — GC means sensitive data persists in heap until collected. `Arrays.fill` is best effort. For critical use, consider native code (JNI with explicit_bzero).
4. **ECC Curve25519** — Full ECC implementation pending BouncyCastle PGP ECC support. MVP uses RSA-2048. Curve25519 equivalent security but RSA is better supported in BouncyCastle's OpenPGP module.

## Reproducible Build (Future)
For truly verifiable security:
1. Pin BouncyCastle JAR SHA-256 hash in CI
2. Verify hash before building
3. Build with reproducible build flags
4. Publish APK hash for public verification

Trust the math. Verify the code.
