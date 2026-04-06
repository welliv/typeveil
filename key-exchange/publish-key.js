#!/usr/bin/env node
//
// publish-key.js — Publish your PGP public key to Nostr relays for Typeveil
//
// Usage:
//   cat pubkey.asc | node publish-key.js --nsec nsec1yourkey...
//   node publish-key.js --nsec nsec1yourkey... pubkey.asc
//
// The tool takes your PGP pubkey from stdin or a file, wraps it in a kind 30078
// Nostr event, signs with your Nostr secret key, and pushes to relays.
//

const { relayInit, nip19, nip05, getEventHash, getSignature } = require('nostr-tools');
const fs = require('fs');

const DEFAULT_RELAYS = [
    'wss://relay.damus.io',
    'wss://relay.snort.social',
    'wss://nos.lol',
    'wss://relay.nostr.band',
    'wss://purplepag.es',
];
const KIND = 30078;
const D_TAG = 'typeveil-pgp-pubkey';

function usage() {
    console.error('Usage:');
    console.error('  cat pubkey.asc | node publish-key.js --nsec nsec1...');
    console.error('  node publish-key.js --nsec nsec1... pubkey.asc');
    console.error();
    console.error('Options:');
    console.error('  --nsec <key>    Your Nostr secret key in nsec format');
    console.error('  --key <file>    Path to your PGP public key file');
    console.error('  --relay <url>   Relay URL (can repeat; default: 5 public relays)');
    console.error();
    console.error('What this does:');
    console.error('  Creates a kind-30078 NIP-78 event signed with your Nostr key.');
    console.error('  Anyone with your npub or NIP-05 can find this key via');
    console.error('  Typeveil\'s resolve-key.js.');
    process.exit(0);
}

function parseArgs(argv) {
    const args = { relays: [...DEFAULT_RELAYS] };
    for (let i = 0; i < argv.length; i++) {
        switch (argv[i]) {
            case '--nsec': args.nsec = argv[++i]; break;
            case '--key': args.keyFile = argv[++i]; break;
            case '--relay': args.relays.push(argv[++i]); break;
            case '--help': case '-h': usage(); break;
            default:
                if (!args.pgpKey) args.pgpKey = argv[i];
        }
    }
    return args;
}

async function main() {
    const args = parseArgs(process.argv.slice(2));

    if (!args.nsec) {
        usage();
    }

    const { type, data } = nip19.decode(args.nsec);
    if (type !== 'nsec') {
        process.stderr.write('Provided key is not an nsec\n');
        process.exit(1);
    }
    const sec = data;

    // Derive pubkey and npub for display
    const { getPublicKey } = require('nostr-tools');
    const pubkey = getPublicKey(sec);
    const npub = nip19.npubEncode(pubkey);
    process.stderr.write(`Publishing as: ${npub}\n`);

    // Read PGP pubkey from file argument or stdin
    let pgpKey;
    if (args.pgpKey || args.keyFile) {
        const file = args.keyFile || args.pgpKey;
        pgpKey = fs.readFileSync(file, 'utf-8');
    } else {
        // Read from stdin
        const chunks = [];
        process.stdin.setEncoding('utf-8');
        for await (const chunk of process.stdin) {
            chunks.push(chunk);
        }
        pgpKey = chunks.join('');
    }

    if (!pgpKey.includes('BEGIN PGP PUBLIC KEY BLOCK')) {
        process.stderr.write('Input does not contain a PGP public key block\n');
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
        id: '',
        sig: '',
    };

    // Sign (nostr-tools API: signEvent is deprecated, use getSignature)
    event.id = getEventHash(event);
    event.sig = getSignature(event, sec);

    // Publish to relays
    const relays = [...new Set(args.relays)];
    let success = 0;
    let fail = 0;

    for (const url of relays) {
        try {
            const relay = relayInit(url);
            await relay.connect();
            const ok = await relay.publish(event);
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
    }

    process.stderr.write(`\nDone: ${success} published, ${fail} failed\n`);
    process.stderr.write('Others can find your key with:\n');
    process.stderr.write(`  node resolve-key.js ${npub}\n`);

    // Print the PGP public key to stdout so user can also copy it manually
    console.log(pgpKey.trim());
}

main().catch(e => {
    process.stderr.write(`Aborted: ${e.message}\n`);
    process.exit(1);
});
