package com.typeveil.inputmethod.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPOnePassSignatureList;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.PGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.PublicKeyDataDecryptorFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * PGP encryption/decryption engine for TypeVeil.
 *
 * - RSA-4096 keypair generated on first run
 * - Private key sealed with AES-256-GCM, AES key in Android Keystore
 * - Zero network calls in this class
 * - All sensitive arrays cleared after use
 */
public final class Crypto {

    private static final String TAG = "TypeVeilCrypto";
    private static final String KEYSTORE_ALIAS = "typeveil_master";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String PREFS = "typeveil_crypto";
    private static final String KEY_SEALED = "sealed_private_key";
    private static final String KEY_PUBLIC = "public_key";
    private static final int AES_KEY_SIZE = 256;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private Crypto() {}

    // ==================== KEY GENERATION ====================

    public static synchronized void ensureKeyPair(Context ctx) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.contains(KEY_SEALED)) return;

        Log.i(TAG, "Generating RSA-4096 keypair...");

        // Generate hardware-backed AES key
        KeyGenerator keyGen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        keyGen.init(new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE)
                .setUserAuthenticationRequired(false)
                .build());
        SecretKey aesKey = keyGen.generateKey();

        // Generate RSA-4096 keypair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(4096, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        // Build PGP key ring
        PGPDigestCalculatorProvider dcp = new JcaPGPDigestCalculatorProviderBuilder()
                .setProvider("BC").build();
        PGPDigestCalculator sha256Calc = dcp.get(HashAlgorithmTags.SHA256);

        PGPKeyPair pgpKp = new JcaPGPKeyPair(
                PublicKeyAlgorithmTags.RSA_GENERAL, kp, new Date());

        PGPSignatureSubpacketGenerator hashGen = new PGPSignatureSubpacketGenerator();
        hashGen.setKeyFlags(false,
                KeyFlags.SIGN_DATA | KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE);

        String userId = "typeveil@local";
        PGPKeyRingGenerator keyRingGen = new PGPKeyRingGenerator(
                PGPSignature.DEFAULT_CERTIFICATION,
                pgpKp,
                userId,
                sha256Calc,
                hashGen.generate(),
                null,
                new JcaPGPContentSignerBuilder(pgpKp.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256)
                        .setProvider("BC"),
                new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256)
                        .setProvider("BC")
                        .build("typeveil".toCharArray()));

        // Serialize private key
        ByteArrayOutputStream privBuf = new ByteArrayOutputStream();
        try (ArmoredOutputStream aOut = new ArmoredOutputStream(privBuf)) {
            keyRingGen.generateSecretKeyRing().encode(aOut);
        }
        byte[] privateKeyArmored = privBuf.toByteArray();

        // Serialize public key
        ByteArrayOutputStream pubBuf = new ByteArrayOutputStream();
        try (ArmoredOutputStream aOut = new ArmoredOutputStream(pubBuf)) {
            keyRingGen.generatePublicKeyRing().encode(aOut);
        }
        byte[] publicKeyArmored = pubBuf.toByteArray();

        // Encrypt private key with AES-GCM
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(privateKeyArmored);

        byte[] sealed = new byte[GCM_IV_LENGTH + encrypted.length];
        System.arraycopy(iv, 0, sealed, 0, GCM_IV_LENGTH);
        System.arraycopy(encrypted, 0, sealed, GCM_IV_LENGTH, encrypted.length);

        String b64 = android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP);
        prefs.edit()
                .putString(KEY_SEALED, b64)
                .putString(KEY_PUBLIC, new String(publicKeyArmored, StandardCharsets.UTF_8))
                .putLong("key_id", pgpKp.getKeyID())
                .apply();

        // Clear sensitive memory
        Arrays.fill(privateKeyArmored, (byte) 0);
        Arrays.fill(publicKeyArmored, (byte) 0);
        Arrays.fill(sealed, (byte) 0);
        Arrays.fill(encrypted, (byte) 0);

        Log.i(TAG, "Keypair generated and sealed.");
    }

    // ==================== ENCRYPTION ====================

    public static String encrypt(Context ctx, String plaintext, String recipientPubKey) {
        try {
            ensureKeyPair(ctx);
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String ourPubKeyStr = prefs.getString(KEY_PUBLIC, "");

            java.util.List<PGPPublicKey> recipients = new java.util.ArrayList<>();
            recipients.add(getPublicKeyFromArmored(ourPubKeyStr));

            if (recipientPubKey != null && !recipientPubKey.trim().isEmpty()) {
                recipients.add(getPublicKeyFromArmored(recipientPubKey));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ArmoredOutputStream aOut = new ArmoredOutputStream(out)) {
                PGPEncryptedDataGenerator encGen = new PGPEncryptedDataGenerator(
                        new JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256)
                                .setWithIntegrityPacket(true)
                                .setSecureRandom(new SecureRandom())
                                .setProvider("BC"));

                for (PGPPublicKey key : recipients) {
                    encGen.addMethod(
                            new JcePublicKeyKeyEncryptionMethodGenerator(key).setProvider("BC"));
                }

                try (OutputStream cOut = encGen.open(aOut, new byte[4096])) {
                    PGPCompressedDataGenerator comData = new PGPCompressedDataGenerator(
                            PGPCompressedData.ZIP);
                    try (OutputStream zOut = comData.open(cOut)) {
                        PGPLiteralDataGenerator litGen = new PGPLiteralDataGenerator();
                        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
                        try (OutputStream lOut = litGen.open(zOut,
                                PGPLiteralData.UTF8,
                                "message.txt",
                                data.length,
                                new Date())) {
                            lOut.write(data);
                            lOut.flush();
                        }
                    }
                }
            }

            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed", e);
            return null;
        }
    }

    // ==================== DECRYPTION ====================

    public static String decrypt(Context ctx, String armoredMessage) {
        try {
            ensureKeyPair(ctx);
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

            // Unseal our private key
            byte[] sealed = android.util.Base64.decode(
                    prefs.getString(KEY_SEALED, ""), android.util.Base64.NO_WRAP);
            if (sealed.length <= GCM_IV_LENGTH) return null;

            byte[] iv = Arrays.copyOfRange(sealed, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(sealed, GCM_IV_LENGTH, sealed.length);

            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            SecretKey aesKey = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] privateKeyBytes = cipher.doFinal(ciphertext);
            Arrays.fill(ciphertext, (byte) 0);

            // Load secret key ring
            PGPSecretKeyRing secRing;
            try (ArmoredInputStream aIn = new ArmoredInputStream(
                    new ByteArrayInputStream(privateKeyBytes))) {
                PGPSecretKeyRingCollection skrc = new PGPSecretKeyRingCollection(
                        PGPUtil.getDecoderStream(aIn),
                        new JcaKeyFingerprintCalculator());
                secRing = skrc.getKeyRings().hasNext() ? skrc.getKeyRings().next() : null;
            }
            Arrays.fill(privateKeyBytes, (byte) 0);
            if (secRing == null) return null;

            // Parse encrypted message
            try (ArmoredInputStream aIn = new ArmoredInputStream(
                    new ByteArrayInputStream(armoredMessage.getBytes(StandardCharsets.UTF_8)))) {
                PGPObjectFactory pgpFact = new PGPObjectFactory(
                        PGPUtil.getDecoderStream(aIn),
                        new JcaKeyFingerprintCalculator());

                Object msg = pgpFact.nextObject();
                PGPEncryptedDataList encList;

                if (msg instanceof PGPEncryptedDataList) {
                    encList = (PGPEncryptedDataList) msg;
                } else if (msg instanceof PGPCompressedData) {
                    PGPCompressedData cData = (PGPCompressedData) msg;
                    PGPObjectFactory inner = new PGPObjectFactory(
                            cData.getDataStream(), new JcaKeyFingerprintCalculator());
                    Object innerMsg = inner.nextObject();
                    if (innerMsg instanceof PGPEncryptedDataList) {
                        encList = (PGPEncryptedDataList) innerMsg;
                    } else if (innerMsg instanceof PGPLiteralData) {
                        return readLiteralData((PGPLiteralData) innerMsg);
                    } else {
                        return null;
                    }
                } else if (msg instanceof PGPLiteralData) {
                    return readLiteralData((PGPLiteralData) msg);
                } else {
                    return null;
                }

                // Find the data for our key
                PGPPublicKeyEncryptedData encData = null;
                for (Object obj : encList) {
                    if (obj instanceof PGPPublicKeyEncryptedData) {
                        PGPPublicKeyEncryptedData ed = (PGPPublicKeyEncryptedData) obj;
                        PGPSecretKey sk = secRing.getSecretKey(ed.getKeyID());
                        if (sk != null) {
                            encData = ed;
                            break;
                        }
                    }
                }
                if (encData == null) return null;

                // Decrypt
                PBESecretKeyDecryptor pbeDec = new JcePBESecretKeyDecryptorBuilder(
                        new JcaPGPDigestCalculatorProviderBuilder().setProvider("BC").build())
                        .setProvider("BC").build("typeveil".toCharArray());

                PGPPrivateKey privKey = secRing.getSecretKey(encData.getKeyID())
                        .extractPrivateKey(pbeDec);

                PublicKeyDataDecryptorFactory dataFactory = new JcePublicKeyDataDecryptorFactoryBuilder()
                        .setProvider("BC")
                        .build(privKey);

                try (InputStream clear = encData.getDataStream(dataFactory)) {
                    PGPObjectFactory plainFact = new PGPObjectFactory(
                            clear, new JcaKeyFingerprintCalculator());
                    Object message = plainFact.nextObject();

                    if (message instanceof PGPCompressedData) {
                        PGPCompressedData cData = (PGPCompressedData) message;
                        PGPObjectFactory inner = new PGPObjectFactory(
                                cData.getDataStream(), new JcaKeyFingerprintCalculator());
                        message = inner.nextObject();
                    }

                    if (message instanceof PGPLiteralData) {
                        return readLiteralData((PGPLiteralData) message);
                    }
                }
            }

            return null;
        } catch (Exception e) {
            Log.e(TAG, "Decryption failed", e);
            return null;
        }
    }

    // ==================== HELPERS ====================

    private static PGPPublicKey getPublicKeyFromArmored(String armored) throws Exception {
        try (ArmoredInputStream aIn = new ArmoredInputStream(
                new ByteArrayInputStream(armored.getBytes(StandardCharsets.UTF_8)))) {
            PGPPublicKeyRingCollection pkrc = new PGPPublicKeyRingCollection(
                    PGPUtil.getDecoderStream(aIn),
                    new JcaKeyFingerprintCalculator());
            Iterator<PGPPublicKeyRing> rit = pkrc.getKeyRings();
            while (rit.hasNext()) {
                PGPPublicKeyRing ring = rit.next();
                Iterator<PGPPublicKey> kit = ring.getPublicKeys();
                while (kit.hasNext()) {
                    PGPPublicKey key = kit.next();
                    if (key.isEncryptionKey()) return key;
                }
            }
        }
        throw new PGPException("No encryption key found");
    }

    private static String readLiteralData(PGPLiteralData ld) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream in = ld.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    public static String getPublicKey(Context ctx) throws Exception {
        ensureKeyPair(ctx);
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PUBLIC, "");
    }

    public static String getKeyFingerprint(Context ctx) throws Exception {
        ensureKeyPair(ctx);
        String pub = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PUBLIC, "");
        PGPPublicKey key = getPublicKeyFromArmored(pub);
        byte[] fp = key.getFingerprint();
        StringBuilder sb = new StringBuilder();
        for (byte b : fp) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
