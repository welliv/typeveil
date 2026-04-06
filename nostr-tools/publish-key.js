#!/usr/bin/env node
//
// publish-key.js — Publish your PGP public key to Nostr relays for Typeveil
//
// Usage:
//   npx resolve-key.js --nsec nsec1...
//
// The tool takes your PGP pubkey from stdin or a file, wraps it in a kind 30078
// Nostr event, signs with your Nostr secret key, and pushes to relays.
//

const { Relay, nip19, nip05, nip44, finalizeEvent } = require('nostr-tools');
const fs = require('fs');

const DEFAULT_RELAYS = [
    'wss://relay.damus.io',
    'wss://relay.snort.social',
    'wss://nos.lol',
    'wss://relay.nostr.band',
];
const KIND = 30078;
const D_TAG = 'typeveil-pgp-pubkey';

async function publish() {
    const args = process.argv.slice(2);
    const idx = args.indexOf('--nsec');
    if (idx === -1 || !args[idx + 1]) {
        console.error('Usage: node publish-key.js --nsec nsec1... [file.pubkey]');
        console.error('  file.pubkey defaults to stdin');
        process.exit(1);
    }

    const nsec = args[idx + 1];
    const { type, data } = nip19.decode(nsec);
    if (type !== 'nsec') {
        process.stderr.write('Provided key is not an nsec\n');
        process.exit(1);
    }
    const sec = data;

    // Get pubkey for npub display
    const { getPublicKey } = require('nostr-tools');
    const pubkey = getPublicKey(sec);
    const npub = nip19.npubEncode(pubkey);
    process.stderr.write(`Publishing as: ${npub}\n`);

    // Read PGP pubkey — file argument or stdin
    let pgpKey;
    const fileArg = args.find(a => !a.startsWith('--') && a !== nsec);
    if (fileArg) {
        pgpKey = fs.readFileSync(fileArg, 'utf-8');
    } else {
        // Read from stdin
        let data = '';
        process.stdin.setEncoding('utf-8');
        process.stdin.on('readable', () => {
            let chunk;
            while ((chunk = process.stdin.read()) !== null) {
                data += chunk;
            }
        });
        process.stdin.on('end', () => {
            pgpKey = data;
            doPublish(pgpKey, nsec, sec, pubkey);
        });
        return; // Wait for stdin
    }

    if (pgpKey) {
        doPublish(pgpKey, nsec, sec, pubkey);
    }
}

async function doPublish(pgpKey, nsec, sec, pubkey) {
    // Validate
    if (!pgpKey.includes('BEGIN PGP PUBLIC KEY BLOCK')) {
        process.stderr.write('Input is not a PGP public key block\n');
        process.exit(1);
    }

    // Build event
    const now = Math.floor(Date.now() / 1000);
    const event = {
        kind: KIND,
        pubkey,
        created_at: now,
        tags: [
            ['d', D_TAG],
            ['client', 'Typeveil'],
        ],
        content: pgpKey.trim(),
    };

    const signed = finalizeEvent(event, sec);

    // Publish to relays
    const relays = DEFAULT_RELAYS;
    let done = 0;
    let success = 0;
    let fail = 0;

    for (const url of relays) {
        try {
            const relay = await Relay.connect(url);
            const ok = await relay.publish(signed);
            relay.close();
            if (ok) {
                success++;
                process.stderr.write(`  ✓ ${url}\n`);
            } else {
                fail++;
                process.stderr.write(`  ✗ ${url} (rejected)\n`);
            }
        } catch (e) {
            fail++;
            process.stderr.write(`  ✗ ${url} (${e.message})\n`);
        }
        done++;
    }

    process.stderr.write(`\nDone: ${success} published, ${fail} failed\n`);
    process.stderr.write('Recipients can now find your key with:\n');
    process.stderr.write(`  node resolve-key.js ${nip19.npubEncode(pubkey)}\n`);
}

publish().catch(e => {
    process.stderr.write(`Aborted: ${e.message}\n`);
    process.exit(1);
});
