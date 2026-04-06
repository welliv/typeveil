#!/usr/bin/env node
//
// resolve-key.js — Find someone's PGP public key via Nostr
//
// Usage:
//   node resolve-key.js alice@example.com
//   node resolve-key.js npub1abc123...
//   node resolve-key.js nprofile1...
//   node resolve-key.js hex123abc... --relay wss://your-relay.com
//
// Resolution:
//   1. NIP-05 handle → pubkey via DNS
//   2. Query relays for kind 30078 d-tag "typeveil-pgp-pubkey"
//   3. Output armored PGP key block to stdout
//
// Pipe output directly into your Typeveil import, or save to file.
//

const { relayInit, nip19, nip05 } = require('nostr-tools');

const DEFAULT_RELAYS = [
    'wss://relay.damus.io',
    'wss://relay.snort.social',
    'wss://nos.lol',
    'wss://relay.nostr.band',
];
const KIND = 30078;
const D_TAG = 'typeveil-pgp-pubkey';
const TIMEOUT = 10000;

async function main() {
    const args = process.argv.slice(2);
    if (args.length < 1) {
        console.error('Usage: node resolve-key.js <nostr-identity> [relay...]');
        process.exit(1);
    }

    let identity = args[0];
    let pubkey = identity;

    // Resolve NIP-05 handle
    if (identity.includes('@')) {
        process.stderr.write(`Resolving NIP-05: ${identity}\n`);
        try {
            const result = await nip05.queryProfile(identity);
            if (!result || !result.pubkey) {
                process.stderr.write(`  FAILED: ${identity} not found\n`);
                process.exit(1);
            }
            pubkey = result.pubkey;
            process.stderr.write(`  pubkey: ${pubkey.slice(0, 16)}...\n`);
        } catch (e) {
            process.stderr.write(`  FAILED: ${e.message}\n`);
            process.exit(1);
        }
    }

    // Decode bech32
    if (pubkey.startsWith('npub1') || pubkey.startsWith('nprofile1')) {
        try {
            const decoded = nip19.decode(pubkey);
            pubkey = decoded.type === 'nprofile' ? decoded.data.pubkey : decoded.data;
            process.stderr.write(`  decoded pubkey: ${pubkey.slice(0, 16)}...\n`);
        } catch (e) {
            process.stderr.write(`  FAILED decode: ${e.message}\n`);
            process.exit(1);
        }
    }

    // Connect to relays
    const relayUrls = args.filter((a, i) => i > 0 && args[i - 1] === '--relay');
    const urls = relayUrls.length > 0 ? relayUrls : DEFAULT_RELAYS;
    process.stderr.write(`Connecting to ${urls.length} relays...\n`);

    const relays = [];
    for (const url of urls) {
        try {
            const relay = relayInit(url);
            await relay.connect();
            relays.push(relay);
            process.stderr.write(`  ✓ ${url}\n`);
        } catch (e) {
            process.stderr.write(`  ✗ ${url}\n`);
        }
    }

    if (relays.length === 0) {
        process.stderr.write('No relays connected\n');
        process.exit(1);
    }

    // Query for kind 30078 with d-tag "typeveil-pgp-pubkey"
    const filter = {
        kinds: [KIND],
        authors: [pubkey],
        '#d': [D_TAG],
        limit: 1,
    };

    let found = null;

    const promises = relays.map(relay => {
        return new Promise((resolve) => {
            const sub = relay.sub([filter]);

            sub.on('event', (event) => {
                if (event.content && event.content.includes('BEGIN PGP PUBLIC KEY BLOCK')) {
                    found = event.content;
                    sub.unsub();
                    resolve(true);
                }
            });

            sub.on('eose', () => {
                resolve(false);
            });

            setTimeout(() => {
                try { sub.unsub(); } catch (e) {}
                resolve(false);
            }, TIMEOUT);
        });
    });

    await Promise.all(promises);

    // Close relays
    relays.forEach(r => r.close());

    if (!found) {
        process.stderr.write('\nNo Typeveil PGP key found for this identity.\n');
        process.stderr.write('They may not have published one yet.\n');
        process.exit(1);
    }

    // Output the key to stdout
    console.log(found);
    process.stderr.write('\nKey found. Import in Typeveil Settings → Import Recipient Key.\n');
}

main().catch(e => {
    process.stderr.write(`Error: ${e.message}\n`);
    process.exit(1);
});
