const passphraseInput = document.getElementById('passphrase');
const generateBtn = document.getElementById('btn-gen');
const exportBtn = document.getElementById('btn-export');
const veilBtn = document.getElementById('btn-veil');
const unveilBtn = document.getElementById('btn-unveil');
const plainInput = document.getElementById('plain-input');
const cipherOutput = document.getElementById('cipher-output');
const cipherInput = document.getElementById('cipher-input');
const plainOutput = document.getElementById('plain-output');
const recipientKeyInput = document.getElementById('recipient-key');
const importRecipientBtn = document.getElementById('btn-import');
const copyEncBtn = document.getElementById('btn-copy-enc');
const copyPlainBtn = document.getElementById('btn-copy-plain');
const keyStatus = document.getElementById('key-status');
const genForm = document.getElementById('generate-form');
const keyInfo = document.getElementById('key-info-display');
const encryptSection = document.getElementById('encrypt-section');
const decryptSection = document.getElementById('decrypt-section');
const statusBar = document.getElementById('status-bar');
const recipientStatus = document.getElementById('recipient-status');

// Load status
function updateStatus() {
  chrome.runtime.sendMessage({ type: 'get-status' }, (status) => {
    if (status.hasKeys) {
      if (status.hasPassphrase) {
        keyStatus.innerHTML = '<span class="key-info">Key pair loaded ✓</span>';
        genForm.style.display = 'none';
        keyInfo.style.display = 'block';
        encryptSection.style.display = 'block';
        decryptSection.style.display = 'block';
        statusBar.textContent = 'Ready to encrypt and decrypt.';
      } else {
        keyStatus.innerHTML = '<span class="no-keys">Key pair stored but locked. Re-enter passphrase.</span>';
        genForm.style.display = 'block';
        keyInfo.style.display = 'none';
        encryptSection.style.display = 'none';
        decryptSection.style.display = 'none';
        statusBar.textContent = 'Enter your passphrase to unlock keys.';
        passphraseInput.placeholder = 'Enter passphrase to unlock';
        generateBtn.textContent = 'Unlock';
      }
    } else {
      keyStatus.innerHTML = '<span class="no-keys">No key pair generated yet.</span>';
      genForm.style.display = 'block';
      keyInfo.style.display = 'none';
      encryptSection.style.display = 'none';
      decryptSection.style.display = 'none';
      statusBar.textContent = 'Generate a key pair to start.';
      generateBtn.textContent = 'Generate Key Pair';
    }
    
    // Load existing recipient key
    chrome.runtime.sendMessage({ type: 'get-recipient-key' }, (resp) => {
      if (resp.key) {
        recipientKeyInput.value = resp.key;
        recipientStatus.textContent = 'Recipient key stored ✓';
      }
    });
  });
}

// Generate/Unlock
generateBtn.addEventListener('click', () => {
  const passphrase = passphraseInput.value;
  if (passphrase.length < 8) {
    statusBar.textContent = 'Passphrase must be at least 8 characters';
    return;
  }

  chrome.runtime.sendMessage({ type: 'generate-keypair', passphrase }, (resp) => {
    if (resp.success) {
      statusBar.textContent = 'Key pair generated ✓';
      sessionStorage.setItem('typeveil_passphrase', passphrase);
      updateStatus();
    } else {
      statusBar.textContent = 'Failed: ' + (resp.error || 'Unknown error');
    }
  });
});

// Export public key
exportBtn.addEventListener('click', () => {
  chrome.runtime.sendMessage({ type: 'export-public-key' }, (resp) => {
    if (resp.publicKeyArmored) {
      navigator.clipboard.writeText(resp.publicKeyArmored);
      statusBar.textContent = 'Public key copied to clipboard ✓';
    } else {
      statusBar.textContent = resp.error || 'Failed to export';
    }
  });
});

// Encrypt (Veil)
veilBtn.addEventListener('click', () => {
  const text = plainInput.value;
  if (!text) {
    statusBar.textContent = 'Nothing to encrypt';
    return;
  }
  if (text.startsWith('-----BEGIN')) {
    statusBar.textContent = 'Already encrypted';
    return;
  }

  chrome.runtime.sendMessage({ type: 'get-recipient-key' }, (resp) => {
    if (!resp.key) {
      statusBar.textContent = 'Import a recipient public key first';
      return;
    }

    statusBar.textContent = 'Encrypting...';
    chrome.runtime.sendMessage({ type: 'encrypt', text, recipientKey: resp.key }, (result) => {
      if (result.ciphertext) {
        cipherOutput.value = result.ciphertext;
        statusBar.textContent = 'Veiled ✓';
      } else {
        statusBar.textContent = result.error || 'Encryption failed';
      }
    });
  });
});

// Decrypt (Unveil)
unveilBtn.addEventListener('click', () => {
  const text = cipherInput.value;
  if (!text.includes('-----BEGIN')) {
    statusBar.textContent = 'No encrypted message found';
    return;
  }

  // Extract PGP-like block
  const start = text.indexOf('-----BEGIN TYPEVEIL');
  const end = text.indexOf('-----END TYPEVEIL');
  const blockToDecrypt = start >= 0 && end >= 0
    ? text.substring(start, end + '-----END TYPEVEIL MESSAGE-----'.length)
    : text;

  statusBar.textContent = 'Decrypting...';
  chrome.runtime.sendMessage({ type: 'decrypt', text: blockToDecrypt }, (result) => {
    if (result.plaintext) {
      plainOutput.value = result.plaintext;
      statusBar.textContent = 'Unveiled ✓';
    } else {
      statusBar.textContent = result.error || 'Decryption failed';
    }
  });
});

// Import recipient key
importRecipientBtn.addEventListener('click', () => {
  const key = recipientKeyInput.value.trim();
  if (!key.includes('-----BEGIN') || !key.includes('PUBLIC KEY')) {
    recipientStatus.textContent = 'Invalid key format';
    return;
  }

  chrome.runtime.sendMessage({ type: 'set-recipient-key', key }, (resp) => {
    if (resp.success) {
      recipientStatus.textContent = 'Recipient key imported ✓';
    } else {
      recipientStatus.textContent = resp.error || 'Import failed';
    }
  });
});

// Copy buttons
copyEncBtn.addEventListener('click', () => {
  if (cipherOutput.value) {
    navigator.clipboard.writeText(cipherOutput.value);
    statusBar.textContent = 'Ciphertext copied ✓';
  }
});

copyPlainBtn.addEventListener('click', () => {
  if (plainOutput.value) {
    navigator.clipboard.writeText(plainOutput.value);
    statusBar.textContent = 'Plaintext copied ✓';
  }
});

// Run on load
updateStatus();
