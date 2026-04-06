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

const { Relay, nip19, nip05 } = require('nostr-tools');

const DEFAULT_RELAYS = [
    'wss://relay.damus.io',
    'wss://relay.snort.social',
    'wss://nos.lol',
    'wss://relay.nostr.band',
];
const KIND = 30078;
const D_TAG = 'typeveil-pgp-pubkey';
const TIMEOUT = 10000;

async function resolve() {
    const args = process.argv.slice(2);
    if (args.length < 1) {
        console.error('Usage: node resolve-key.js <nostr-identity> [relay...]');
        console.error('  npub1... / nprofile1... / hex / name@domain.com');
        process.exit(1);
    }

    let identity = args[0];
    const customRelays = args.filter(a => a.startsWith('--relay')).length > 0
        ? args.filter((a, i) => i > 0 && args[i-1] === '--relay')
        : [];
    const relays = customRelays.length > 0 ? customRelays : DEFAULT_RELAYS;
    let pubkey = identity;

    // Resolve NIP-05 handle
    if (identity.includes('@')) {
        process.stderr.write(`Resolving NIP-05: ${identity}\n`);
        try {
            const result = await nip05.queryProfile(identity);
            if (!result || !result.pubkey) {
                throw new Error('NIP-05 not found');
            }
            pubkey = result.pubkey;
            process.stderr.write(`  pubkey: ${pubkey.slice(0, 16)}...\n`);
        } catch (e) {
            process.stderr.write(`  FAILED: ${identity} — ${e.message}\n`);
            process.stdout.write('ERROR\n');
            process.exit(1);
        }
    }

    // Decode bech32
    if (pubkey.startsWith('npub1') || pubkey.startsWith('nprofile1')) {
        try {
            const decoded = nip19.decode(pubkey);
            pubkey = decoded.type === 'nprofile' ? decoded.data.pubkey : decoded.data;
        } catch (e) {
            process.stderr.write(`  FAILED decode: ${e.message}\n`);
            process.exit(1);
        }
    }

    // Strip nsec or hex prefix if present
    if (pubkey.startsWith('nsec1')) pubkey = pubkey.slice(5);
    pubkey = pubkey.toLowerCase();

    // Query relays
    process.stderr.write(`Querying ${relays.length} relays...\n`);

    const connections = [];
    for (const url of relays) {
        try {
            const relay = await Relay.connect(url);
            connections.push(relay);
            process.stderr.write(`  connected: ${url}\n`);
        } catch (e) {
            process.stderr.write(`  offline:  ${url}\n`);
        }
    }

    if (connections.length === 0) {
        process.stderr.write('No relays available\n');
        process.exit(1);
    }

    const filter = {
        kinds: [KIND],
        authors: [pubkey],
        '#d': [D_TAG],
        limit: 1,
    };

    let found = null;
    const timeout = setTimeout(() => {
        connections.forEach(r => r.close());
        if (!found) {
            process.stderr.write('Timeout: no key found\n');
            process.exit(1);
        }
    }, TIMEOUT);

    const subscription = `tv-${Date.now()}`;

    for (const relay of connections) {
        const sub = relay.subscribe([filter], {
            id: subscription,
            onevent(event) {
                if (event.content && event.content.includes('BEGIN PGP PUBLIC KEY BLOCK')) {
                    found = event.content;
                }
            },
            oneose() {
                if (found) {
                    clearTimeout(timeout);
                    connections.forEach(r => r.close());
                    console.log(found);
                    process.stderr.write(`\nKey found. Import in Typeveil Settings → Import Recipient Key.\n`);
                    process.exit(0);
                }
            },
            onclose() {
                // Individual relay closed
            },
        });

        // Close subscription after timeout
        setTimeout(() => {
            try { sub.close(); } catch (e) {}
        }, TIMEOUT);
    }

    // If nothing found and oneose hasn't fired
    setTimeout(() => {
        connections.forEach(r => r.close());
        if (!found) {
            process.stderr.write('\nNo Typeveil PGP key found on any relay.\n');
            process.stderr.write('They may not have published one yet, or use a different identity.\n');
            process.exit(1);
        }
    }, TIMEOUT + 1000);
}

resolve().catch(e => {
    process.stderr.write(`Error: ${e.message}\n`);
    process.exit(1);
});
