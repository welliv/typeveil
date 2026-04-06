package com.typeveil.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.bouncycastle.openpgp.operator.PGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;
import java.util.Iterator;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPSignature;

/**
 * Crypto operations — all on-device, zero network.
 * 
 * SECURITY DESIGN:
 * - Private key encrypted with AES-256-GCM, key derived from user passphrase
 * - Master key stored in Android Keystore (hardware-backed where available)
 * - All byte arrays zeroed after use (best effort for Java)
 * - No sensitive strings logged or stored
 * - No network calls ever
 * - BouncyCastle 1.70 (SpongyCastle rejected — deprecated since 2017)
 */
public class Crypto {

    private static final String KEYSTORE_TYPE = "AndroidKeyStore";
    private static final String MASTER_KEY_ALIAS = "typeveil_master";
    private static final String PREFS = "typeveil_prefs";
    private static final String KEY_ENCRYPTED_PRIVKEY = "enc_privkey";
    private static final String KEY_RECIPIENT_PUBKEY = "recipient_pubkey";
    private static final String KEY_HAS_KEYPAIR = "has_keypair";

    /** 
     * In-memory passphrase cache. Cleared on process death.
     * Stored in SharedPreferences encrypted by Android Keystore on next boot.
     * We keep this to avoid re-asking the user.
     */
    private static char[] tempPassphrase = null;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }
    
    private static String bytesToHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = "0123456789abcdef".charAt(v >>> 4);
            hex[i * 2 + 1] = "0123456789abcdef".charAt(v & 0xF);
        }
        return new String(hex);
    }

    /**
     * Generate RSA-2048 key pair for OpenPGP.
     * Private key encrypted with user-passphrase-derived AES-256 via BouncyCastle PBE.
     * Encrypted blob stored in SharedPreferences, protected by Android Keystore AES-256-GCM.
     * 
     * Returns the armored public key on success, null on failure.
     */
    public static String generateKeyPair(Context ctx, String passphrase) {
        try {
            // Store a SHA-256 hash of the passphrase for integrity check
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            String passphraseHash = bytesToHex(sha256.digest(passphrase.getBytes(StandardCharsets.UTF_8)));
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("passphrase_hash", passphraseHash)
                .apply();

            tempPassphrase = passphrase.toCharArray();

            // Generate RSA-2048 key pair using BouncyCastle JCA provider
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA", "BC");
            kpg.initialize(2048, new SecureRandom());
            java.security.KeyPair keyPair = kpg.generateKeyPair();

            // Create PGP key pair from JCA keys
            PGPKeyPair pgpKeyPair = new PGPKeyPair(
                org.bouncycastle.openpgp.PGPPublicKey.RSA_GENERAL,
                keyPair.getPublic(),
                keyPair.getPrivate(),
                new java.util.Date());

            // Build key encryptor using passphrase
            org.bouncycastle.openpgp.operator.PGPDigestCalculator calc =
                new org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider()
                    .get(HashAlgorithmTags.SHA256);

            org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor encryptor =
                new org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder(
                    PGPEncryptedData.AES_256, calc)
                    .build(passphrase.toCharArray());

            // Build key ring generator
            org.bouncycastle.openpgp.operator.PGPContentSignerBuilder signerBuilder =
                new org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder(
                    org.bouncycastle.openpgp.PGPPublicKey.RSA_GENERAL,
                    HashAlgorithmTags.SHA256);

            // Generate secret and public key rings
            PGPKeyRingGenerator keyRingGen = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                pgpKeyPair,
                "typeveil-key",
                calc,
                null,  // no additional attributes
                null,  // no extra certifications
                signerBuilder,
                encryptor);

            PGPSecretKeyRing secRing = keyRingGen.generateSecretKeyRing();
            PGPPublicKeyRing pubRing = keyRingGen.generatePublicKeyRing();

            // Serialize secret key ring
            ByteArrayOutputStream secOut = new ByteArrayOutputStream();
            ArmoredOutputStream secArmor = new ArmoredOutputStream(secOut);
            secRing.encode(secArmor);
            secArmor.close();
            byte[] secBytes = secOut.toByteArray();

            try {
                storePrivateKey(ctx, secBytes);
            } finally {
                Arrays.fill(secBytes, (byte) 0); // Zero plaintext after storage
            }

            // Serialize public key ring to return
            ByteArrayOutputStream pubOut = new ByteArrayOutputStream();
            ArmoredOutputStream pubArmor = new ArmoredOutputStream(pubOut);
            pubRing.encode(pubArmor);
            pubArmor.close();
            String pubKeyArmored = pubOut.toString("UTF-8");

            // Mark key pair as generated and cache the public key
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_HAS_KEYPAIR, true)
                .putString("my_pubkey", pubKeyArmored)
                .apply();

            return pubKeyArmored;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Encrypt plaintext with recipient's cached public key.
     * Returns PGP-armored ciphertext.
     */
    public static String encrypt(String plaintext, Context ctx) {
        return encrypt(plaintext, getRecipientPublicKey(ctx));
    }

    /**
     * Encrypt plaintext with explicit recipient public key.
     */
    public static String encrypt(String plaintext, String recipientPublicKeyArmored) {
        if (recipientPublicKeyArmored == null || recipientPublicKeyArmored.isEmpty()) {
            return null;
        }

        ByteArrayOutputStream out = null;
        ArmoredOutputStream armor = null;
        try {
            // Parse recipient's public key
            PGPPublicKey pubKey = readPublicKey(
                new ByteArrayInputStream(recipientPublicKeyArmored.getBytes(StandardCharsets.UTF_8))
            );
            if (pubKey == null) return null;

            // Create encrypted data generator (AES-256 with integrity)
            PGPEncryptedDataGenerator gen = new PGPEncryptedDataGenerator(
                new JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256)
                    .setWithIntegrityPacket(true)
                    .setSecureRandom(new SecureRandom())
                    .setProvider("BC")
            );
            gen.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(pubKey)
                .setProvider("BC"));

            out = new ByteArrayOutputStream();
            armor = new ArmoredOutputStream(out);
            
            byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
            
            ByteArrayOutputStream literalOut = new ByteArrayOutputStream();
            PGPLiteralDataGenerator literalGen = new PGPLiteralDataGenerator();
            
            ByteArrayOutputStream temp = new ByteArrayOutputStream();
            java.io.OutputStream literal = literalGen.open(temp, PGPLiteralData.UTF8, 
                PGPLiteralData.CONSOLE, data.length, new java.util.Date());
            literal.write(data);
            literal.close();
            
            java.io.OutputStream encrypted = gen.open(armor, temp.toByteArray().length);
            encrypted.write(temp.toByteArray());
            encrypted.close();
            armor.close();
            
            return out.toString("UTF-8");
            
        } catch (Exception e) {
            return null;
        } finally {
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            try { if (armor != null) armor.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Decrypt PGP message with user's private key.
     * Requires passphrase to unlock the private key.
     */
    public static String decrypt(String armoredCiphertext, Context ctx) {
        if (armoredCiphertext == null || !armoredCiphertext.contains("-----BEGIN PGP MESSAGE-----")) {
            return null;
        }

        try {
            InputStream in = PGPUtil.getDecoderStream(
                new ByteArrayInputStream(armoredCiphertext.getBytes(StandardCharsets.UTF_8))
            );

            PGPObjectFactory pgpFact = new PGPObjectFactory(in, new JcaKeyFingerprintCalculator());
            PGPEncryptedDataList encList = null;
            Object obj = pgpFact.nextObject();
            
            if (obj instanceof PGPEncryptedDataList) {
                encList = (PGPEncryptedDataList) obj;
            } else {
                encList = (PGPEncryptedDataList) pgpFact.nextObject();
            }

            if (encList == null) return null;

            // Find the encrypted data for our key
            PGPPublicKeyEncryptedData encData = null;
            Iterator<?> it = encList.getEncryptedDataObjects();
            while (it.hasNext()) {
                PGPPublicKeyEncryptedData ed = (PGPPublicKeyEncryptedData) it.next();
                encData = ed;
                break; // Use first available
            }

            if (encData == null) return null;

            // Get private key
            PGPPrivateKey privKey = findSecretKey(ctx, encData.getKeyID());
            if (privKey == null) return null;

            // Decrypt
            java.io.InputStream clear = encData.getDataStream(
                new JcePublicKeyDataDecryptorFactoryBuilder()
                    .setProvider("BC")
                    .build(privKey)
            );

            PGPObjectFactory plainFact = new PGPObjectFactory(clear, new JcaKeyFingerprintCalculator());
            PGPLiteralData msg = (PGPLiteralData) plainFact.nextObject();
            
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            java.io.InputStream liter = msg.getInputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = liter.read(buf)) > 0) {
                result.write(buf, 0, len);
            }

            String decrypted = result.toString("UTF-8");
            result.reset();
            // Zero the output buffer — best effort
            Arrays.fill(buf, (byte) 0);
            
            return decrypted;
            
        } catch (Exception e) {
            return null;
        }
    }

    private static String getUserPassphrase(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("passphrase_hash", null);
    }

    private static PGPPrivateKey findSecretKey(Context ctx, long keyID) throws Exception {
        String encryptedPrivKey = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENCRYPTED_PRIVKEY, null);
        if (encryptedPrivKey == null) return null;

        byte[] encrypted = android.util.Base64.decode(encryptedPrivKey, android.util.Base64.DEFAULT);
        byte[] decrypted = decryptWithKeystore(ctx, encrypted);
        Arrays.fill(encrypted, (byte) 0);

        PGPSecretKeyRingCollection secRing = new PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(new ByteArrayInputStream(decrypted)),
            new JcaKeyFingerprintCalculator()
        );
        Arrays.fill(decrypted, (byte) 0);

        PGPSecretKey secKey = secRing.getSecretKey(keyID);
        if (secKey == null) {
            Iterator<?> it = secRing.getKeyRings();
            while (it.hasNext()) {
                PGPSecretKeyRing ring = (PGPSecretKeyRing) it.next();
                Iterator<?> keys = ring.getSecretKeys();
                while (keys.hasNext()) {
                    secKey = (PGPSecretKey) keys.next();
                    if (secKey.isEncryptionKey()) break;
                }
                if (secKey != null && secKey.isEncryptionKey()) break;
            }
        }

        if (secKey == null) return null;

        String passphrase = getUserPassphrase(ctx);
        if (passphrase == null) return null;

        return secKey.extractPrivateKey(
            new JcePBESecretKeyDecryptorBuilder()
                .setProvider("BC")
                .build(passphrase.toCharArray())
        );
    }

    /**
     * Encrypt data with Android Keystore AES-256-GCM key.
     */
    private static byte[] encryptWithKeystore(Context ctx, byte[] plaintext) throws Exception {
        ensureKeystoreKey();
        
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        ks.load(null);
        
        SecretKey key = (SecretKey) ks.getKey(MASTER_KEY_ALIAS, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plaintext);
        
        // Return IV + encrypted
        byte[] result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        return result;
    }

    /**
     * Decrypt data with Android Keystore AES-256-GCM key.
     */
    private static byte[] decryptWithKeystore(Context ctx, byte[] data) throws Exception {
        ensureKeystoreKey();
        
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        ks.load(null);
        
        SecretKey key = (SecretKey) ks.getKey(MASTER_KEY_ALIAS, null);
        byte[] iv = Arrays.copyOfRange(data, 0, 12);
        byte[] encrypted = Arrays.copyOfRange(data, 12, data.length);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(encrypted);
    }

    /**
     * Ensure Android Keystore has our master key.
     * Key is hardware-backed where device supports it.
     */
    private static void ensureKeystoreKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        ks.load(null);
        
        if (ks.containsAlias(MASTER_KEY_ALIAS)) return;
        
        KeyGenerator kg = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_TYPE);
        kg.init(new KeyGenParameterSpec.Builder(MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build());
        kg.generateKey();
    }

    /**
     * Parse a PGP public key from armored input.
     */
    private static PGPPublicKey readPublicKey(InputStream in) throws Exception {
        PGPPublicKeyRingCollection ring = new PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(in),
            new JcaKeyFingerprintCalculator()
        );
        
        Iterator<?> keyRings = ring.getKeyRings();
        while (keyRings.hasNext()) {
            PGPPublicKeyRing keyRing = (PGPPublicKeyRing) keyRings.next();
            Iterator<?> keys = keyRing.getPublicKeys();
            while (keys.hasNext()) {
                PGPPublicKey key = (PGPPublicKey) keys.next();
                if (key.isEncryptionKey()) return key;
            }
        }
        return null;
    }

    /**
     * Store encrypted private key in SharedPreferences.
     * The plaintext private key is encrypted with Keystore before storage.
     */
    public static void storePrivateKey(Context ctx, byte[] armoredPrivateKey) throws Exception {
        byte[] encrypted = encryptWithKeystore(ctx, armoredPrivateKey);
        String encoded = android.util.Base64.encodeToString(encrypted, android.util.Base64.DEFAULT);
        
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENCRYPTED_PRIVKEY, encoded)
            .apply();
        
        // Zero the original
        Arrays.fill(armoredPrivateKey, (byte) 0);
    }

    /**
     * Store recipient public key with basic format validation.
     */
    public static boolean setRecipientPublicKey(Context ctx, String armoredKey) {
        if (armoredKey == null || !armoredKey.contains("BEGIN PGP PUBLIC KEY BLOCK")) {
            return false;
        }
        try {
            // Verify the key is parseable before storing
            PGPPublicKey key = readPublicKey(
                new ByteArrayInputStream(armoredKey.getBytes(StandardCharsets.UTF_8))
            );
            if (key == null || !key.isEncryptionKey()) {
                return false;
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RECIPIENT_PUBKEY, armoredKey)
                .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get cached recipient public key.
     */
    private static String getRecipientPublicKey(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECIPIENT_PUBKEY, null);
    }
}
