#!/usr/bin/env node
//
// resolve-key.js — Resolve a Nostr handle to their PGP public key for Typeveil
//
// Usage:
//   node resolve-key.js alice@example.com
//   node resolve-key.js npub1abc123...
//
// What it does:
//   1. If NIP-05 handle: resolves via HTTPS → .well-known/nostr.json → pubkey
//   2. If npub: decodes directly to pubkey
//   3. Queries relays for Kind 30078 event tagged typeveil-pgp-pubkey
//   4. Prints the armored PGP public key to stdout
//
// This is a CLI companion. The keyboard itself has zero network permission.
//

const { relayInit, nip19, getEventHash, getSignature, getPublicKey } = require('nostr-tools');

const RELAYS = [
    'wss://relay.damus.io',
    'wss://relay.nostr.band',
    'wss://nos.lol',
];
const KIND = 30078;
const D_TAG = 'typeveil-pgp-pubkey';

async function resolvePubkey(handle) {
    if (handle.startsWith('npub1')) {
        // Already a Nostr pubkey
        const decoded = nip19.decode(handle);
        return decoded.data;
    }

    // NIP-05 resolution
    const { nip05 } = require('nostr-tools');
    const result = await nip05.queryProfile(handle);
    if (!result || !result.pubkey) {
        process.stderr.write(`Could not resolve: ${handle}\n`);
        process.exit(1);
    }
    return result.pubkey;
}

async function main() {
    const handle = process.argv[2];
    if (!handle) {
        process.stderr.write('Usage: node resolve-key.js <handle|npub>\n');
        process.stderr.write('\nExamples:\n');
        process.stderr.write('  node resolve-key.js alice@example.com\n');
        process.stderr.write('  node resolve-key.js npub1abc123...\n');
        process.exit(1);
    }

    process.stderr.write(`Resolving ${handle}...\n`);
    const pubkey = await resolvePubkey(handle);
    process.stderr.write(`Nostr pubkey: ${pubkey.slice(0, 16)}...\n`);

    // Query relays for Kind 30078
    let pgpKey = null;
    let relayUsed = '';

    for (const url of RELAYS) {
        process.stderr.write(`  Querying ${url}... `);
        try {
            const pgp = await queryRelay(url, pubkey);
            if (pgp) {
                pgpKey = pgp;
                relayUsed = url;
                process.stderr.write('found ✓\n');
                break;
            } else {
                process.stderr.write('not found\n');
            }
        } catch (e) {
            process.stderr.write(`error: ${e.message}\n`);
        }
    }

    if (!pgpKey) {
        process.stderr.write('\nNo PGP key found on relays.\n');
        process.stderr.write('They may not have published it yet.\n');
        process.exit(1);
    }

    // Verify it's a valid PGP key
    if (!pgpKey.includes('BEGIN PGP PUBLIC KEY BLOCK')) {
        process.stderr.write('\nInvalid PGP key retrieved.\n');
        process.exit(1);
    }

    // Print key to stdout (for piping)
    process.stdout.write(pgpKey + '\n');
    process.stderr.write(`\nResolved via: ${relayUsed}\n`);
    process.stderr.write('Import this into Typeveil Settings → Import PGP Key\n');
}

async function queryRelay(url, pubkey) {
    return new Promise((resolve, reject) => {
        const relay = relayInit(url);
        let resolved = false;
        const timeout = setTimeout(() => {
            if (!resolved) {
                relay.close();
                resolve(null);
            }
        }, 5000);

        relay.on('connect', () => {
            const sub = relay.sub([
                { kinds: [KIND], authors: [pubkey], '#d': [D_TAG], limit: 1 }
            ]);

            sub.on('event', event => {
                clearTimeout(timeout);
                resolved = true;
                relay.close();
                resolve(event.content);
            });

            sub.on('eose', () => {
                clearTimeout(timeout);
                resolved = true;
                relay.close();
                resolve(null);
            });
        });

        relay.on('error', (e) => {
            clearTimeout(timeout);
            resolved = true;
            reject(e);
        });

        relay.connect().catch(reject);
    });
}

main().catch(e => {
    process.stderr.write(`Aborted: ${e.message}\n`);
    process.exit(1);
});
