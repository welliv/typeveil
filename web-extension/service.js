/**
 * Typeveil Extension Service Worker (Manifest V3)
 * 
 * Crypto engine using the Web Crypto API.
 * 
 * Flow:
 * 1. generateKeyPair(passphrase) → RSA-OAEP 2048 + RSA-PSS 2048
 * 2. encrypt(plaintext, recipientPubKey) → RSA-OAEP encrypt AES-GCM key + AES-GCM encrypt text
 * 3. decrypt(ciphertextJSON) → using stored private key (encrypted by passphrase-derived key)
 * 
 * Keys stored in chrome.storage.local, encrypted with passphrase-derived AES-GCM.
 */

const PASSPHRASE_HASH_SALT = 'typeveil-passphrase-salt-v1';
const KEY_PAIR_STORAGE_KEY = 'typeveil_key_pair';

// ==================== KEY GENERATION ====================

async function generateKeyPair(passphrase) {
  // Generate RSA-OAEP for encryption
  const encryptKey = await crypto.subtle.generateKey(
    {
      name: 'RSA-OAEP',
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]), // 65537
      hash: 'SHA-256',
    },
    true,
    ['encrypt', 'decrypt']
  );

  // Export public key (JWK)
  const pubJwk = await crypto.subtle.exportKey('jwk', encryptKey.publicKey);
  
  // Export private key (JWK) and encrypt it with passphrase
  const privJwk = await crypto.subtle.exportKey('jwk', encryptKey.privateKey);
  const encryptedPriv = await encryptWithPassphrase(privJwk, passphrase);
  
  // Store encrypted private key
  await chrome.storage.local.set({
    [KEY_PAIR_STORAGE_KEY]: {
      encryptedPrivateKey: encryptedPriv,
      publicKeyJwk: pubJwk,
      salt: PASSPHRASE_HASH_SALT,
    }
  });

  // Also export armored format for sharing
  const armoredPubKey = jwkToArmored(pubJwk);
  localStorage.setItem('typeveil_pubkey', armoredPubKey);

  return {
    success: true,
    publicKeyArmored: armoredPubKey,
  };
}

// ==================== ENCRYPTION ====================

async function encryptText(plaintext, recipientPublicKeyArmored) {
  if (!recipientPublicKeyArmored) {
    return { error: 'No recipient public key set' };
  }

  try {
    // Parse recipient public key
    const recipientPubKey = await armoredToCryptoKey(recipientPublicKeyArmored);
    if (!recipientPubKey) return { error: 'Invalid public key' };

    // Generate random AES-GCM key for this message
    const aesKey = await crypto.subtle.generateKey(
      { name: 'AES-GCM', length: 256 },
      true,
      ['encrypt']
    );

    const iv = crypto.getRandomValues(new Uint8Array(12));
    const encoder = new TextEncoder();
    const data = encoder.encode(plaintext);

    // Encrypt the data with AES-GCM
    const ciphertext = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv },
      aesKey,
      data
    );

    // Encrypt the AES key with recipient's RSA public key
    const encryptedAesKey = await crypto.subtle.encrypt(
      { name: 'RSA-OAEP' },
      recipientPubKey,
      await crypto.subtle.exportKey('raw', aesKey)
    );

    // Package: IV (12) + encrypted AES key length (4) + encrypted AES key + ciphertext
    const encAesKey = new Uint8Array(encryptedAesKey);
    const aesKeyLen = new Uint8Array(4);
    new DataView(aesKeyLen.buffer).setUint32(0, encAesKey.length);

    const result = new Uint8Array(12 + 4 + encAesKey.length + ciphertext.byteLength);
    result.set(iv, 0);
    result.set(aesKeyLen, 12);
    result.set(encAesKey, 16);
    result.set(new Uint8Array(ciphertext), 16 + 4 + encAesKey.length);

    // Base64 encode and wrap in PGP-like header for easy identification
    const b64 = btoa(String.fromCharCode(...result));
    return {
      ciphertext: `-----BEGIN TYPEVEIL MESSAGE-----\n${wrap64(b64)}\n-----END TYPEVEIL MESSAGE-----`,
    };
  } catch (e) {
    return { error: 'Encryption failed: ' + e.message };
  }
}

// ==================== DECRYPTION ====================

async function decryptText(armoredCipherText) {
  try {
    // Extract base64 content
    const clean = armoredCipherText
      .replace('-----BEGIN TYPEVEIL MESSAGE-----', '')
      .replace('-----END TYPEVEIL MESSAGE-----', '')
      .replace(/\s/g, '');
    
    const data = Uint8Array.from(atob(clean), c => c.charCodeAt(0));

    // Check if this is a Typeveil message
    if (data.length < 20) return { error: 'Invalid message' };

    // Parse components
    const iv = data.slice(0, 12);
    const encryptedAesKeyLen = new DataView(data.buffer, 12, 4).getUint32(0);
    const encryptedAesKey = data.slice(16, 16 + encryptedAesKeyLen);
    const ciphertext = data.slice(16 + encryptedAesKeyLen);

    // Load key pair
    const stored = await chrome.storage.local.get(KEY_PAIR_STORAGE_KEY);
    const keyData = stored[KEY_PAIR_STORAGE_KEY];
    if (!keyData) return { error: 'No key pair found. Generate one first.' };

    // Decrypt private key using stored passphrase
    // We need the passphrase for this — in the extension model, we cache it in session
    const cachedPassphrase = sessionStorage.getItem('typeveil_passphrase');
    if (!cachedPassphrase) return { error: 'Passphrase required. Close and reopen popup to enter it.' };

    const privJwk = await decryptWithPassphrase(keyData.encryptedPrivateKey, cachedPassphrase);
    const privateKey = await crypto.subtle.importKey(
      'jwk',
      privJwk,
      { name: 'RSA-OAEP', hash: 'SHA-256' },
      false,
      ['decrypt']
    );

    // Decrypt AES key
    const aesKeyRaw = await crypto.subtle.decrypt(
      { name: 'RSA-OAEP' },
      privateKey,
      encryptedAesKey
    );

    const aesKey = await crypto.subtle.importKey(
      'raw',
      aesKeyRaw,
      'AES-GCM',
      false,
      ['decrypt']
    );

    // Decrypt data
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv },
      aesKey,
      ciphertext
    );

    const decoder = new TextDecoder();
    return { plaintext: decoder.decode(plaintext) };

  } catch (e) {
    return { error: 'Decryption failed: ' + e.message };
  }
}

// ==================== PASSPHRASE-BASED ENCRYPTION FOR KEY STORAGE ====================

async function deriveKeyFromPassphrase(passphrase, salt) {
  const encoder = new TextEncoder();
  const keyMaterial = await crypto.subtle.importKey(
    'raw',
    encoder.encode(passphrase),
    'PBKDF2',
    false,
    ['deriveKey']
  );
  
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt: encoder.encode(salt), iterations: 100000, hash: 'SHA-256' },
    keyMaterial,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  );
}

async function encryptWithPassphrase(data, passphrase) {
  const key = await deriveKeyFromPassphrase(passphrase, PASSPHRASE_HASH_SALT);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encoder = new TextEncoder();
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    key,
    encoder.encode(JSON.stringify(data))
  );
  
  const result = new Uint8Array(12 + ciphertext.byteLength);
  result.set(iv, 0);
  result.set(new Uint8Array(ciphertext), 12);
  return btoa(String.fromCharCode(...result));
}

async function decryptWithPassphrase(encodedData, passphrase) {
  const data = Uint8Array.from(atob(encodedData), c => c.charCodeAt(0));
  const iv = data.slice(0, 12);
  const ciphertext = data.slice(12);
  
  const key = await deriveKeyFromPassphrase(passphrase, PASSPHRASE_HASH_SALT);
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv },
    key,
    ciphertext
  );
  
  return JSON.parse(new TextDecoder().decode(plaintext));
}

// ==================== PGP-STYLE ARMORED FORMAT ====================

/**
 * Converts a JWK public key to PGP-style armored format for display/sharing.
 * This is NOT real PGP — it's a Typeveil-specific format that looks like PGP.
 */
function jwkToArmored(jwk) {
  const json = JSON.stringify(jwk);
  const b64 = btoa(json);
  return `-----BEGIN TYPEVEIL PUBLIC KEY-----\n${wrap64(b64)}\n-----END TYPEVEIL PUBLIC KEY-----`;
}

function armoredToCryptoKey(armoredKey) {
  try {
    const clean = armoredKey
      .replace('-----BEGIN TYPEVEIL PUBLIC KEY-----', '')
      .replace('-----END TYPEVEIL PUBLIC KEY-----', '')
      .replace(/\s/g, '');
    
    const json = atob(clean);
    const jwk = JSON.parse(json);
    
    return crypto.subtle.importKey(
      'jwk',
      jwk,
      { name: 'RSA-OAEP', hash: 'SHA-256' },
      false,
      ['encrypt']
    );
  } catch (e) {
    return null;
  }
}

function wrap64(str) {
  const lines = [];
  for (let i = 0; i < str.length; i += 64) {
    lines.push(str.substring(i, i + 64));
  }
  return lines.join('\n');
}

// ==================== MESSAGE HANDLING ====================

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  switch (msg.type) {
  case 'encrypt':
    (async () => {
      const recipKey = msg.recipientKey || 
        (await new Promise(r => chrome.storage.local.get('typeveil_recipient_key', d => r(d.typeveil_recipient_key || null))));
      encryptText(msg.text, recipKey).then(sendResponse);
    })();
    break;
    case 'decrypt':
      decryptText(msg.text).then(sendResponse);
      break;
    case 'generate-keypair':
      generateKeyPair(msg.passphrase).then(sendResponse);
      break;
    case 'get-status':
      chrome.storage.local.get(KEY_PAIR_STORAGE_KEY, (result) => {
        const hasKeys = !!result[KEY_PAIR_STORAGE_KEY];
        const hasPassphrase = !!sessionStorage.getItem('typeveil_passphrase');
        sendResponse({ hasKeys, hasPassphrase });
      });
      break;
    case 'set-passphrase':
      sessionStorage.setItem('typeveil_passphrase', msg.passphrase);
      sendResponse({ success: true });
      break;
    case 'export-public-key':
      chrome.storage.local.get(KEY_PAIR_STORAGE_KEY, (result) => {
        if (!result[KEY_PAIR_STORAGE_KEY]) {
          sendResponse({ error: 'No keys generated' });
          return;
        }
        const jwk = result[KEY_PAIR_STORAGE_KEY].publicKeyJwk;
        sendResponse({ publicKeyArmored: jwkToArmored(jwk) });
      });
      break;
    case 'set-recipient-key':
      // Validate the key
      armoredToCryptoKey(msg.key).then((key) => {
        if (!key) {
          sendResponse({ success: false, error: 'Invalid key format' });
        } else {
          chrome.storage.local.set({ typeveil_recipient_key: msg.key }, () => {
            sendResponse({ success: true });
          });
        }
      });
      break;
    case 'get-recipient-key':
      chrome.storage.local.get('typeveil_recipient_key', (result) => {
        sendResponse({ key: result.typeveil_recipient_key || null });
      });
      break;
  }
  return true; // async response
});
