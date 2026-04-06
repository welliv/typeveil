/* Typeveil Chrome Extension — content script */
(function() {
  'use strict';

  const VEIL_BTN = 'typeveil-veil';
  const UNVEIL_BTN = 'typeveil-unveil';
  const TOOLBAR_CLASS = 'typeveil-toolbar';

  let activeTextarea = null;

  function createToolbar(textarea) {
    if (textarea.parentElement && textarea.parentElement.classList.contains(TOOLBAR_CLASS)) {
      return textarea.parentElement.querySelector('button');
    }

    const wrapper = document.createElement('div');
    wrapper.className = TOOLBAR_CLASS;
    wrapper.style.cssText = 'display:inline-flex;gap:4px;margin:2px 0;';

    const veilBtn = document.createElement('button');
    veilBtn.type = 'button';
    veilBtn.textContent = 'Veil';
    veilBtn.id = VEIL_BTN;
    veilBtn.style.cssText = 'background:#065f46;color:#34d399;border:1px solid #0a8a5e;border-radius:3px;padding:2px 10px;cursor:pointer;font-size:12px;font-weight:600;';
    veilBtn.title = 'Encrypt this text';

    const unveilBtn = document.createElement('button');
    unveilBtn.type = 'button';
    unveilBtn.textContent = 'Unveil';
    unveilBtn.id = UNVEIL_BTN;
    unveilBtn.style.cssText = 'background:#1e3a5f;color:#60a5fa;border:1px solid #2a5a8f;border-radius:3px;padding:2px 10px;cursor:pointer;font-size:12px;font-weight:600;';
    unveilBtn.title = 'Decrypt PGP message';

    veilBtn.addEventListener('click', () => veilText(textarea));
    unveilBtn.addEventListener('click', () => unveilText(textarea));

    const parent = textarea.parentElement;
    parent.insertBefore(wrapper, textarea.nextSibling);
    wrapper.appendChild(veilBtn);
    wrapper.appendChild(unveilBtn);

    return veilBtn;
  }

  function veilText(textarea) {
    const text = textarea.value;
    if (!text || text.startsWith('-----BEGIN PGP MESSAGE-----')) return;

    chrome.runtime.sendMessage({ type: 'encrypt', text: text }, (response) => {
      if (response && response.ciphertext) {
        textarea.value = response.ciphertext;
        flashButton(textarea, 'Veiled');
      } else {
        flashButton(textarea, response?.error || 'Failed — import recipient key first');
      }
    });
  }

  function unveilText(textarea) {
    const text = textarea.value;
    if (!text || !text.includes('-----BEGIN PGP MESSAGE-----')) return;

    const start = text.indexOf('-----BEGIN PGP MESSAGE-----');
    const end = text.indexOf('-----END PGP MESSAGE-----');
    if (end === -1) return;

    const pgpBlock = text.substring(start, end + '-----END PGP MESSAGE-----'.length);
    chrome.runtime.sendMessage({ type: 'decrypt', text: pgpBlock }, (response) => {
      if (response && response.plaintext) {
        textarea.value = text.substring(0, start) + response.plaintext + text.substring(end + '-----END PGP MESSAGE-----'.length);
        flashButton(textarea, 'Unveiled');
      } else {
        flashButton(textarea, response?.error || 'Decrypt failed');
      }
    });
  }

  function flashButton(textarea, msg) {
    const wrapper = textarea.parentElement;
    if (!wrapper) return;
    const btn = wrapper.querySelector('#' + VEIL_BTN);
    if (!btn) return;

    const orig = btn.textContent;
    btn.textContent = msg;
    btn.style.color = msg.startsWith('V') ? '#34d399' : '#f87171';
    setTimeout(() => {
      btn.textContent = orig;
      btn.style.color = '#34d399';
    }, 1500);
  }

  // Inject Veil/Unveil buttons into every visible textarea
  function scanTextareas() {
    document.querySelectorAll('textarea, input[type="text"]').forEach((el) => {
      if (!el.closest('.typeveil-toolbar') && !el.closest('#typeveil-toolbar-wrap')) {
        createToolbar(el);
      }
    });
  }

  // Run on load and watch for new textareas
  scanTextareas();
  const observer = new MutationObserver((mutations) => {
    let changed = false;
    mutations.forEach((m) => {
      m.addedNodes.forEach((node) => {
        if (node.tagName === 'TEXTAREA' || node.tagName === 'INPUT') {
          changed = true;
        } else if (node.querySelectorAll) {
          const tas = node.querySelectorAll('textarea, input[type="text"]');
          if (tas.length) changed = true;
        }
      });
    });
    if (changed) scanTextareas();
  });
  observer.observe(document.body, { childList: true, subtree: true });
})();
