// End-to-end test of the web console: real relay process, real openpgp.js, jsdom pages.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { webcrypto } from 'node:crypto';
import { Blob as NodeBlob } from 'node:buffer';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { JSDOM } from 'jsdom';
import * as openpgp from 'openpgp';
import { startApp } from '../public/app.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const html = fs.readFileSync(path.join(here, '..', 'public', 'index.html'), 'utf8');

function freePort() {
    return new Promise((resolve) => {
        const s = net.createServer().listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => resolve(port)); });
    });
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(fn, ms = 8000, what = 'condition') {
    const t = Date.now();
    while (Date.now() - t < ms) { const v = await fn(); if (v) return v; await sleep(50); }
    throw new Error('timeout waiting for ' + what);
}

const apps = [];
const port = await freePort();
const relay = spawn(process.execPath, [path.join(here, '..', 'server.js')], {
    env: { ...process.env, PORT: String(port) }, stdio: ['ignore', 'pipe', 'pipe']
});
let out = '';
relay.stdout.on('data', (d) => { out += d; });
relay.stderr.on('data', (d) => { out += d; });
await waitFor(() => out.includes('listening'), 5000, 'relay start');

function makeClient(name) {
    const dom = new JSDOM(html, { url: `http://127.0.0.1:${port}/`, pretendToBeVisual: true });
    const win = dom.window;
    const app = startApp({
        document: win.document, window: win, openpgp, fetch: globalThis.fetch,
        WebSocket: win.WebSocket, URL, Blob: NodeBlob, crypto: webcrypto, confirm: () => true
    });
    const doc = win.document;
    const c = {
        name, dom, win, doc, app,
        $: (id) => doc.getElementById(id),
        status: () => doc.getElementById('relay-status-text').textContent,
        myKey: () => doc.getElementById('my-pubkey').value,
        async savePeer(armored) {
            c.$('peer-key-input').value = armored;
            c.$('save-peer-btn').click();
            await waitFor(() => c.$('peer-summary').classList.contains('ok'), 5000, `${name} peer saved`);
        },
        async send(text) {
            c.$('message-input').value = text;
            c.$('chat-form').dispatchEvent(new win.Event('submit', { cancelable: true, bubbles: true }));
        },
        bubbles: () => Array.from(doc.querySelectorAll('#messages-container .bubble')),
        async attach(file) {
            const input = c.$('media-file-input');
            Object.defineProperty(input, 'files', { value: [file], configurable: true });
            input.dispatchEvent(new win.Event('change', { bubbles: true }));
            await waitFor(() => !c.$('attachment-bar').classList.contains('hidden'), 3000, 'attachment bar');
        }
    };
    apps.push(c);
    return c;
}

after(async () => {
    for (const c of apps) { try { c.app.dispose(); c.win.close(); } catch (e) { /* ignore */ } }
    relay.kill();
});

const alice = makeClient('alice');
const bob = makeClient('bob');
const carol = makeClient('carol');

test('both consoles generate identities and connect to the relay', async () => {
    for (const c of [alice, bob, carol]) {
        await c.app.ready;
        await waitFor(() => c.status() === 'CONNECTED', 8000, `${c.name} connected`);
        assert.match(c.myKey(), /^-----BEGIN PGP PUBLIC KEY BLOCK-----\nComment: Drop-ID: [0-9a-f]{64}\n/);
        assert.match(c.$('my-fingerprint').textContent, /^([0-9A-F]{4} ){9}[0-9A-F]{4}$/);
        assert.ok(!c.doc.body.innerHTML.includes(c.app.getStateForTests().identity.readToken), 'read token is never shown');
    }
});

test('importing the peer key, signed text message, ACK deletes it from the relay', async () => {
    await alice.savePeer(bob.myKey());
    await bob.savePeer(alice.myKey());

    await alice.send('hello <b>bob</b> <img src=x onerror=alert(1)>');
    await waitFor(() => bob.bubbles().length === 1, 8000, 'bob receives');
    const b = bob.bubbles()[0];
    assert.equal(b.querySelector('p.text').textContent, 'hello <b>bob</b> <img src=x onerror=alert(1)>');
    assert.equal(b.querySelector('b'), null);
    assert.equal(b.querySelector('img'), null);
    assert.match(b.querySelector('.sig-ok').textContent, /VERIFIED SIGNATURE/);
    assert.equal(alice.bubbles().length, 1, 'sender sees its own message');

    // the relay must have deleted the message after bob's ACK
    const st = bob.app.getStateForTests().identity;
    await waitFor(async () => {
        const r = await fetch(`http://127.0.0.1:${port}/api/v2/inbox/${st.dropId}`, { headers: { 'X-Mailbox-Token': st.readToken } });
        return (await r.json()).count === 0;
    }, 5000, 'ACK processed by the relay');
});

test('reply in the other direction is verified too', async () => {
    await bob.send('hi alice');
    await waitFor(() => alice.bubbles().length === 2, 8000, 'alice receives');
    const last = alice.bubbles()[1];
    assert.equal(last.querySelector('p.text').textContent, 'hi alice');
    assert.match(last.querySelector('.sig-ok').textContent, /VERIFIED SIGNATURE/);
});

test('attachments: whitelisted image inline, SVG as a Blob file card, no active content', async () => {
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');
    await alice.attach({ name: 'dot.png', type: 'image/png', size: png.length, arrayBuffer: async () => png.buffer.slice(png.byteOffset, png.byteOffset + png.length) });
    await alice.send('');
    await waitFor(() => bob.bubbles().length === 3, 8000, 'bob gets image');
    const img = bob.bubbles()[2].querySelector('img');
    assert.ok(img && img.getAttribute('src').startsWith('data:image/png;base64,'));

    const svg = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"><script>alert(2)</script></svg>');
    await alice.attach({ name: '"><script>x.svg', type: 'image/svg+xml', size: svg.length, arrayBuffer: async () => svg.buffer.slice(svg.byteOffset, svg.byteOffset + svg.length) });
    await alice.send('');
    await waitFor(() => bob.bubbles().length === 4, 8000, 'bob gets svg');
    const card = bob.bubbles()[3];
    assert.equal(card.querySelector('img'), null);
    assert.equal(card.querySelector('script, svg'), null);
    assert.ok(card.querySelector('a').getAttribute('href').startsWith('blob:'));
    assert.match(card.querySelector('.sig-ok').textContent, /VERIFIED/);

    const big = { name: 'big.bin', type: 'application/octet-stream', size: 16 * 1024 * 1024, arrayBuffer: async () => new ArrayBuffer(1) };
    const input = alice.$('media-file-input');
    Object.defineProperty(input, 'files', { value: [big], configurable: true });
    input.dispatchEvent(new alice.win.Event('change', { bubbles: true }));
    await sleep(200);
    assert.ok(alice.$('attachment-bar').classList.contains('hidden'), 'files over 15 MB are refused');
});

test('a message from someone who is not the saved peer is flagged as unverified', async () => {
    await carol.savePeer(alice.myKey());
    const before = alice.bubbles().length;
    await carol.send('trust me');
    await waitFor(() => alice.bubbles().length === before + 1, 8000, 'alice receives carol');
    const b = alice.bubbles()[before];
    assert.equal(b.querySelector('.sig-warn').textContent, 'INVALID SIGNATURE — UNVERIFIED SENDER');
    assert.equal(b.querySelector('.sig-ok'), null);
});

test('messages queued while offline are picked up after reconnecting', async () => {
    const st = bob.app.getStateForTests();
    const before = bob.bubbles().length;
    st.ws.close();                                              // drop bob's socket
    await waitFor(() => bob.status() === 'OFFLINE', 3000, 'bob offline');
    await alice.send('while you were away');
    await waitFor(() => bob.status() === 'CONNECTED', 12000, 'bob reconnected with backoff');
    await waitFor(() => bob.bubbles().length === before + 1, 8000, 'bob gets the queued message');
    assert.equal(bob.bubbles()[before].querySelector('p.text').textContent, 'while you were away');
});

test('wipe drops keys and messages; a new identity can be created afterwards', async () => {
    alice.$('panic-btn').click();
    assert.equal(alice.$('my-pubkey').value, '');
    assert.equal(alice.bubbles().length, 0);
    assert.equal(alice.status(), 'WIPED');
    const st = alice.app.getStateForTests();
    assert.equal(st.identity, null);
    assert.equal(st.ws, null);
    alice.$('new-identity-btn').click();
    await waitFor(() => alice.status() === 'CONNECTED', 8000, 'alice reconnected');
    assert.match(alice.myKey(), /BEGIN PGP PUBLIC KEY BLOCK/);
});

test('invalid peer keys are rejected with a message', async () => {
    bob.$('peer-key-input').value = 'garbage';
    bob.$('save-peer-btn').click();
    await waitFor(() => !bob.$('peer-error').classList.contains('hidden'), 3000, 'error shown');
    assert.match(bob.$('peer-error').textContent, /Could not use this key/);
    bob.$('peer-key-input').value = bob.app.getStateForTests().identity.privateKeyArmored;
    bob.$('save-peer-btn').click();
    await waitFor(() => /private key/i.test(bob.$('peer-error').textContent), 3000, 'private key refused');
});

test('encrypted groups: create group, E2EE invite over relay, group AES-GCM messaging', async () => {
    await alice.savePeer(bob.myKey());
    await bob.savePeer(alice.myKey());
    await waitFor(() => alice.$('chat-list').children.length >= 1, 3000, 'alice chat list');

    alice.$('new-group-btn').click();
    alice.$('group-name-input').value = 'Phase6 Group';
    alice.$('create-group-submit').click();
    await waitFor(() => alice.app.getStateForTests().groups.length === 1, 5000, 'alice group created');

    await waitFor(() => bob.bubbles().some((b) => b.textContent.includes('Phase6 Group')), 8000, 'bob receives group invite');
    const inviteBubble = bob.bubbles().find((b) => b.textContent.includes('Phase6 Group'));
    inviteBubble.querySelector('.special-card button').click();
    await waitFor(() => bob.app.getStateForTests().groups.length === 1, 5000, 'bob joined group');

    await alice.send('secret group hello');
    await waitFor(() => bob.bubbles().some((b) => b.textContent.includes('secret group hello')), 8000, 'bob receives group AES msg');
});

test('clear chat for both sides wipes direct conversation on sender and peer', async () => {
    const aliceChatId = bob.app.getStateForTests().identity.dropId;
    const bobChatId = alice.app.getStateForTests().identity.dropId;
    alice.app.getStateForTests().activeChat = { type: 'direct', id: aliceChatId };
    bob.app.getStateForTests().activeChat = { type: 'direct', id: bobChatId };

    await alice.send('temp secret message');
    await waitFor(() => bob.bubbles().some((b) => b.textContent.includes('temp secret message')), 8000, 'bob gets message');

    alice.$('clear-both-btn').click();
    await waitFor(() => alice.bubbles().length === 0, 5000, 'alice cleared locally');
    await waitFor(() => bob.bubbles().length === 0, 8000, 'bob cleared remotely via signed clear_chat');
});

test('5-minute rendezvous link & code pairing sends signed contact_request with Accept/Decline banner', async () => {
    const sender = makeClient('rv-sender');
    const receiver = makeClient('rv-receiver');
    await sender.app.ready;
    await receiver.app.ready;
    await waitFor(() => sender.status() === 'CONNECTED' && receiver.status() === 'CONNECTED', 8000, 'both connected');

    sender.$('my-alias-input').value = 'SenderAlice';
    sender.$('my-alias-input').dispatchEvent(new sender.win.Event('input', { bubbles: true }));
    receiver.$('my-alias-input').value = 'ReceiverBob';
    receiver.$('my-alias-input').dispatchEvent(new receiver.win.Event('input', { bubbles: true }));

    sender.$('quick-pair-btn').click();
    sender.$('rv-generate-btn').click();
    await waitFor(() => !sender.$('rv-result-box').classList.contains('hidden'), 5000, 'rv link generated');
    const inviteLink = sender.$('rv-link-display').value;
    assert.match(inviteLink, /#pair=[2-9A-HJ-NP-Z]{5}-[2-9A-HJ-NP-Z]{5}$/);

    // Simulate opening the #pair= link via hashchange on receiver
    receiver.win.location.hash = inviteLink.slice(inviteLink.indexOf('#'));
    receiver.win.dispatchEvent(new receiver.win.HashChangeEvent('hashchange'));
    await waitFor(() => !receiver.$('pair-preview-panel').classList.contains('hidden'), 5000, 'receiver preview shown');
    assert.match(receiver.$('pair-preview-meta').textContent, /SenderAlice/);

    // Receiver accepts preview -> saves SenderAlice and sends signed contact_request
    receiver.$('pair-preview-accept').click();
    await waitFor(() => receiver.app.getStateForTests().contacts.length === 1, 5000, 'receiver saved sender');

    // Sender sees Accept / Decline banner
    await waitFor(() => !sender.$('contact-requests-banner').classList.contains('hidden'), 8000, 'sender sees contact request banner');
    assert.match(sender.$('contact-requests-banner').textContent, /ReceiverBob/);

    // Sender clicks Accept -> both sides can message with verified signatures
    sender.$('contact-requests-banner').querySelector('.req-accept-btn').click();
    await waitFor(() => sender.app.getStateForTests().contacts.length === 1, 5000, 'sender accepted receiver');
    assert.equal(sender.$('contact-requests-banner').classList.contains('hidden'), true);

    await sender.send('paired via 5m link!');
    await waitFor(() => receiver.bubbles().some((b) => b.textContent.includes('paired via 5m link!')), 8000, 'receiver receives message');
    const b = receiver.bubbles().find((x) => x.textContent.includes('paired via 5m link!'));
    assert.match(b.querySelector('.sig-ok').textContent, /VERIFIED SIGNATURE/);
});
