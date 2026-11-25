'use strict';
// Black-box tests for the v2 relay. Run: npm test  (spawns server.js on a free port)
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const net = require('node:net');
const path = require('node:path');
const crypto = require('node:crypto');
const WebSocket = require('ws');

const SERVER = path.join(__dirname, '..', 'server.js');
const hex32 = () => crypto.randomBytes(32).toString('hex');
const deriveDrop = (tok) => crypto.createHash('sha256').update('drop:' + tok, 'utf8').digest('hex');

function freePort() {
    return new Promise((resolve) => {
        const s = net.createServer().listen(0, '127.0.0.1', () => {
            const { port } = s.address();
            s.close(() => resolve(port));
        });
    });
}

async function start(env = {}) {
    const port = await freePort();
    const proc = spawn(process.execPath, [SERVER], {
        env: Object.assign({}, process.env, { PORT: String(port) }, env),
        stdio: ['ignore', 'pipe', 'pipe']
    });
    let out = '';
    proc.stdout.on('data', (d) => { out += d; });
    proc.stderr.on('data', (d) => { out += d; });
    for (let i = 0; i < 100 && !out.includes('listening'); i++) await new Promise((r) => setTimeout(r, 50));
    assert.ok(out.includes('listening'), 'server did not start: ' + out);
    const base = `http://127.0.0.1:${port}`;
    return {
        port, base, get output() { return out; },
        stop: () => new Promise((r) => { proc.on('exit', r); proc.kill(); }),
        req: (method, p, { body, token, headers } = {}) => fetch(base + p, {
            method,
            headers: Object.assign({}, token ? { 'X-Mailbox-Token': token } : {}, body !== undefined ? { 'Content-Type': 'application/json' } : {}, headers || {}),
            body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body))
        })
    };
}

const send = (s, dropId, ciphertext = 'ct', extra = {}) => s.req('POST', '/api/v2/send', { body: Object.assign({ dropId, ciphertext }, extra) });

test('defaults: binds 127.0.0.1, neutral ping, no CORS/HSTS', async () => {
    const s = await start();
    try {
        assert.match(s.output, /http:\/\/127\.0\.0\.1:/);
        const r = await s.req('GET', '/api/v2/ping');
        assert.equal(r.status, 200);
        const j = await r.json();
        assert.deepEqual(Object.keys(j).sort(), ['status', 'timestamp']);
        assert.equal(j.status, 'ok');
        assert.equal(r.headers.get('access-control-allow-origin'), null);
        assert.equal(r.headers.get('strict-transport-security'), null);
        assert.equal((await s.req('GET', '/api/v1/ping')).status, 404);
    } finally { await s.stop(); }
});

test('auth: dropId derived from readToken, 401 on mismatch/missing, no counts leaked, ACK required for deletion', async () => {
    const s = await start();
    try {
        const tok = hex32(), drop = deriveDrop(tok);
        const sent = await (await send(s, drop, 'hello')).json();
        assert.equal(sent.success, true);
        assert.deepEqual(Object.keys(sent).sort(), ['messageId', 'success']);

        let r = await s.req('GET', `/api/v2/inbox/${drop}`);
        assert.equal(r.status, 401);
        assert.ok(!JSON.stringify(await r.json()).includes('count'));

        r = await s.req('GET', `/api/v2/inbox/${drop}`, { token: tok });   // valid owner
        assert.equal(r.status, 200);
        let j = await r.json();
        assert.equal(j.count, 1);
        assert.equal(j.messages[0].ciphertext, 'hello');

        // not deleted by reading
        j = await (await s.req('GET', `/api/v2/inbox/${drop}`, { token: tok })).json();
        assert.equal(j.count, 1);

        // other token cannot read or ACK (anti-squatting & anti-frontrunning)
        assert.equal((await s.req('GET', `/api/v2/inbox/${drop}`, { token: hex32() })).status, 401);
        assert.equal((await s.req('POST', '/api/v2/ack', { token: hex32(), body: { dropId: drop, messageIds: [j.messages[0].id] } })).status, 401);
        assert.equal((await s.req('GET', `/api/v2/inbox/${drop}`, { token: 'not-hex' })).status, 401);

        r = await s.req('POST', '/api/v2/ack', { token: tok, body: { dropId: drop, messageIds: [j.messages[0].id] } });
        assert.deepEqual(await r.json(), { success: true, removed: 1 });
        j = await (await s.req('GET', `/api/v2/inbox/${drop}`, { token: tok })).json();
        assert.equal(j.count, 0);

        // register is idempotent for owner, 401 for others
        assert.equal((await s.req('POST', '/api/v2/register', { token: tok, body: { dropId: drop } })).status, 200);
        assert.equal((await s.req('POST', '/api/v2/register', { token: hex32(), body: { dropId: drop } })).status, 401);
        assert.equal((await s.req('GET', '/api/v2/inbox/short', { token: tok })).status, 400);
    } finally { await s.stop(); }
});

test('ttlSeconds validation never drops messages silently', async () => {
    const s = await start();
    try {
        const tok = hex32(), drop = deriveDrop(tok);
        for (const ttl of [undefined, NaN, 'abc', -5, 0, null, 1e12, 5]) {
            const r = await send(s, drop, 'x', ttl === undefined ? {} : { ttlSeconds: ttl });
            assert.equal(r.status, 200, `ttl=${ttl}`);
        }
        const j = await (await s.req('GET', `/api/v2/inbox/${drop}`, { token: tok })).json();
        assert.equal(j.count, 8);
        for (const m of j.messages) assert.ok(m.ttl >= 60000 && m.ttl <= 86400000, 'ttl clamped, got ' + m.ttl);
    } finally { await s.stop(); }
});

test('input handling: bad JSON, bad ids, oversize body, odd Host, static path traversal', async () => {
    const s = await start();
    try {
        assert.equal((await s.req('POST', '/api/v2/send', { body: '{not json' })).status, 400);
        assert.equal((await send(s, 'zz'.repeat(32))).status, 400);
        assert.equal((await s.req('POST', '/api/v2/send', { body: { dropId: hex32() } })).status, 400);

        const big = await s.req('POST', '/api/v2/send', { body: JSON.stringify({ dropId: hex32(), ciphertext: 'a'.repeat(33 * 1024 * 1024) }) });
        assert.equal(big.status, 413);
        assert.equal((await s.req('GET', '/api/v2/ping')).status, 200, 'server survives oversize body');

        // malformed Host header must not crash the server
        await new Promise((resolve) => {
            const c = net.connect(s.port, '127.0.0.1', () => c.write('GET /api/v2/ping HTTP/1.1\r\nHost: bad host:::\r\nConnection: close\r\n\r\n'));
            let d = ''; c.on('data', (x) => { d += x; }); c.on('close', () => { assert.match(d, /^HTTP\/1\.1 (200|400)/); resolve(); });
        });

        for (const p of ['/..%2f..%2fserver.js', '/%2e%2e/server.js', '/../server.js', '/%00', '/..%5cserver.js']) {
            const r = await new Promise((resolve) => {
                const c = net.connect(s.port, '127.0.0.1', () => c.write(`GET ${p} HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n`));
                let d = ''; c.on('data', (x) => { d += x; }); c.on('close', () => resolve(d));
            });
            assert.doesNotMatch(r, /require\('http'\)/, 'must not leak server source for ' + p);
            assert.match(r, /^HTTP\/1\.1 (400|404)/, p);
        }
        const root = await s.req('GET', '/');
        assert.equal(root.status, 200);
        assert.match(root.headers.get('content-type'), /text\/html/);
    } finally { await s.stop(); }
});

test('per-dropId rate limit (30/min), queue cap, active-dropId cap, global RAM cap', async () => {
    let s = await start();
    try {
        const drop = hex32();
        for (let i = 0; i < 30; i++) assert.equal((await send(s, drop)).status, 200, 'msg ' + i);
        const r = await send(s, drop);
        assert.equal(r.status, 429);
        assert.ok(r.headers.get('retry-after'));
        assert.equal((await send(s, hex32())).status, 200, 'other dropIds unaffected');
    } finally { await s.stop(); }

    s = await start({ GK_MAX_QUEUE: '3', GK_MAX_MSG_PER_MIN: '1000' });
    try {
        const drop = hex32();
        for (let i = 0; i < 3; i++) assert.equal((await send(s, drop)).status, 200);
        assert.equal((await send(s, drop)).status, 429);
    } finally { await s.stop(); }

    s = await start({ GK_MAX_ACTIVE_DROPS: '3' });
    try {
        for (let i = 0; i < 3; i++) assert.equal((await send(s, hex32())).status, 200);
        assert.equal((await send(s, hex32())).status, 503);
        const tCap = hex32(), dCap = deriveDrop(tCap);
        assert.equal((await s.req('GET', `/api/v2/inbox/${dCap}`, { token: tCap })).status, 503);
    } finally { await s.stop(); }

    // Unclaimed drop spam cannot starve reserved slots for authenticated users
    s = await start({ GK_MAX_ACTIVE_DROPS: '4', GK_MAX_UNCLAIMED_DROPS: '2' });
    try {
        assert.equal((await send(s, hex32())).status, 200);
        assert.equal((await send(s, hex32())).status, 200);
        assert.equal((await send(s, hex32())).status, 503, 'unclaimed cap blocks excess spam');
        const tAuth = hex32(), dAuth = deriveDrop(tAuth);
        assert.equal((await s.req('GET', `/api/v2/inbox/${dAuth}`, { token: tAuth })).status, 200, 'reserved claimed slot succeeds');
    } finally { await s.stop(); }

    s = await start({ GK_MAX_TOTAL_BYTES: '1000' });
    try {
        const tok = hex32(), drop = deriveDrop(tok);
        assert.equal((await send(s, drop, 'a'.repeat(600))).status, 200);
        assert.equal((await send(s, hex32(), 'a'.repeat(600))).status, 503);
        // ACK frees accounted memory
        const j = await (await s.req('GET', `/api/v2/inbox/${drop}`, { token: tok })).json();
        await s.req('POST', '/api/v2/ack', { token: tok, body: { dropId: drop, messageIds: [j.messages[0].id] } });
        assert.equal((await send(s, hex32(), 'a'.repeat(600))).status, 200);
    } finally { await s.stop(); }
});

function wsOpen(s, opts) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(`ws://127.0.0.1:${s.port}/api/v2/live`, opts);
        ws.inbox = [];
        ws.on('message', (d) => ws.inbox.push(JSON.parse(d.toString())));
        ws.on('open', () => resolve(ws));
        ws.on('error', reject);
    });
}
const waitFor = async (fn, ms = 3000) => {
    const t = Date.now();
    while (Date.now() - t < ms) { const v = fn(); if (v) return v; await new Promise((r) => setTimeout(r, 25)); }
    throw new Error('timeout waiting for condition');
};

test('websocket: auth, backlog + live push, ACK over WS, multi-subscription, cleanup', async () => {
    const s = await start();
    try {
        const t1 = hex32(), d1 = deriveDrop(t1), t2 = hex32(), d2 = deriveDrop(t2);
        await send(s, d1, 'backlog-1');

        const ws = await wsOpen(s);
        ws.send(JSON.stringify({ type: 'SUBSCRIBE', dropId: d1, readToken: t1 }));   // valid owner claims/subscribes
        await waitFor(() => ws.inbox.find((m) => m.type === 'SUBSCRIBED'));
        // an impostor with a different token on the same dropId must fail (anti-squatting)
        const imp = await wsOpen(s);
        imp.send(JSON.stringify({ type: 'SUBSCRIBE', dropId: d1, readToken: hex32() }));
        await waitFor(() => imp.inbox.find((m) => m.type === 'AUTH_FAILED'));
        assert.ok(!imp.inbox.some((m) => m.type === 'MESSAGE'));
        imp.close();
        ws.close();

        // proper flow with known tokens on two dropIds from one socket
        const c = await wsOpen(s);
        c.send(JSON.stringify({ type: 'SUBSCRIBE', dropId: d2, readToken: t2 }));
        const t3 = hex32(), d3 = deriveDrop(t3);
        c.send(JSON.stringify({ type: 'SUBSCRIBE', dropId: d3, readToken: t3 }));
        await waitFor(() => c.inbox.filter((m) => m.type === 'SUBSCRIBED').length === 2);
        const sent = await (await send(s, d2, 'live-1')).json();
        const msg = await waitFor(() => c.inbox.find((m) => m.type === 'MESSAGE'));
        assert.equal(msg.message.ciphertext, 'live-1');
        assert.equal(msg.message.id, sent.messageId);

        // still queued until ACK
        let j = await (await s.req('GET', `/api/v2/inbox/${d2}`, { token: t2 })).json();
        assert.equal(j.count, 1);

        // ACK for a dropId this socket is not subscribed to is rejected
        c.send(JSON.stringify({ type: 'ACK', dropId: d1, messageIds: ['x'] }));
        await waitFor(() => c.inbox.find((m) => m.type === 'AUTH_FAILED'));

        c.send(JSON.stringify({ type: 'ACK', dropId: d2, messageIds: [msg.message.id] }));
        const acked = await waitFor(() => c.inbox.find((m) => m.type === 'ACKED'));
        assert.equal(acked.removed, 1);
        j = await (await s.req('GET', `/api/v2/inbox/${d2}`, { token: t2 })).json();
        assert.equal(j.count, 0);

        // re-subscribing delivers leftover backlog
        await send(s, d2, 'offline-1');
        c.close();
        await new Promise((r) => setTimeout(r, 100));
        const c2 = await wsOpen(s);
        c2.send(JSON.stringify({ type: 'SUBSCRIBE', dropId: d2, readToken: t2 }));
        const bl = await waitFor(() => c2.inbox.find((m) => m.type === 'MESSAGE'));
        assert.equal(bl.message.ciphertext, 'offline-1');
        c2.close();
    } finally { await s.stop(); }
});

test('websocket: server heartbeat terminates connections that never pong', async () => {
    const s = await start({ GK_HEARTBEAT_MS: '150' });
    try {
        const dead = await wsOpen(s, { autoPong: false });
        const alive = await wsOpen(s);
        let deadClosed = false, aliveClosed = false;
        dead.on('close', () => { deadClosed = true; });
        alive.on('close', () => { aliveClosed = true; });
        await waitFor(() => deadClosed, 2000);
        await new Promise((r) => setTimeout(r, 400));
        assert.equal(aliveClosed, false, 'healthy connection must stay open');
        alive.close();
    } finally { await s.stop(); }
});

test('websocket: oversized frames (>32 MB) are refused', async () => {
    const s = await start();
    try {
        const ws = await wsOpen(s);
        const closed = new Promise((r) => ws.on('close', (code) => r(code)));
        ws.send('x'.repeat(32 * 1024 * 1024 + 10));
        const code = await closed;
        assert.ok(code === 1009 || code === 1006, 'connection must be closed, got ' + code);
        assert.equal((await s.req('GET', '/api/v2/ping')).status, 200, 'server survives');
    } finally { await s.stop(); }
});
