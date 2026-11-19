/**
 * GhostKey Blind Relay Server (wire protocol v2)
 * -------------------------------------------------------------
 * Design goals and honest limits:
 * 1. Blind store-and-forward: the relay only sees random drop IDs and OpenPGP
 *    ciphertext. It cannot read message contents.
 * 2. Metadata is NOT hidden from the relay operator: message sizes, timing and
 *    drop-ID activity are visible to whoever runs this process.
 * 3. No logging of payloads or client addresses by this code. (Behind Tor all
 *    connections come from 127.0.0.1 anyway.)
 * 4. Messages are held in process memory only; this code never writes them to
 *    disk. The OS may still swap memory, which this code does not control.
 * 5. Mailbox auth: the first caller to present a 32-byte hex readToken for a
 *    dropId claims it (only SHA-256(readToken) is kept). Later reads,
 *    subscriptions and ACKs must present the same token.
 * 6. Delivery is at-least-once: messages stay queued until the client
 *    explicitly ACKs them (or the TTL expires). Clients must de-duplicate by id.
 * 7. Claims live in RAM, so a relay restart forgets them; the first client to
 *    reconnect with the right readToken simply claims the dropId again.
 * 8. Known limitation: because rate limiting is per dropId (not per IP, which
 *    is meaningless behind Tor), someone who guesses or learns a dropId can
 *    fill that mailbox's quota, and someone can occupy active-dropId slots
 *    until their messages expire.
 * 9. Intended to run behind a Tor onion service forwarding to 127.0.0.1.
 */

'use strict';

const http = require('http');
const { WebSocketServer } = require('ws');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

function envInt(name, fallback) {
    const v = Number(process.env[name]);
    return Number.isFinite(v) && v > 0 ? Math.floor(v) : fallback;
}

const PORT = envInt('PORT', 8765);
const HOST = process.env.HOST || '127.0.0.1';

// ---- Limits (the GK_* overrides exist so the test-suite can exercise them) ----
const MAX_BODY_BYTES = 10 * 1024 * 1024;              // HTTP request body cap
const WS_MAX_PAYLOAD = 10 * 1024 * 1024;              // WebSocket frame cap (10 MB)
const DEFAULT_TTL_S = 3600;
const MIN_TTL_S = 60;
const MAX_TTL_S = 86400;
const MAX_MESSAGES_PER_MINUTE = envInt('GK_MAX_MSG_PER_MIN', 30);   // per dropId
const MAX_QUEUE_PER_DROP = envInt('GK_MAX_QUEUE', 100);             // per dropId
const MAX_ACTIVE_DROPS = envInt('GK_MAX_ACTIVE_DROPS', 10000);
const MAX_TOTAL_BYTES = envInt('GK_MAX_TOTAL_BYTES', 256 * 1024 * 1024); // all queues
const CLAIM_IDLE_MS = envInt('GK_CLAIM_IDLE_MS', 3 * 24 * 60 * 60 * 1000);
const GC_INTERVAL_MS = envInt('GK_GC_INTERVAL_MS', 30000);
const HEARTBEAT_MS = envInt('GK_HEARTBEAT_MS', 30000);
const MAX_SUBS_PER_SOCKET = 8;
const MAX_ACK_IDS = 1000;
const INBOX_RESPONSE_BYTES = 24 * 1024 * 1024;        // soft cap per GET /inbox response
const WS_BACKPRESSURE_BYTES = 16 * 1024 * 1024;

const PUBLIC_DIR = path.resolve(__dirname, 'public');
const ID_RE = /^[0-9a-fA-F]{64}$/;

/**
 * drops: dropId (64 lowercase hex) -> {
 *   tokenHash: Buffer(32) | null,   // SHA-256(readToken) once claimed
 *   queue: [{ id, ciphertext, createdAt, ttl, bytes }],
 *   lastActive: ms,
 *   winStart: ms, winCount: number  // 60s rate-limit window for sends
 * }
 * Its size is the number of "active dropIds" (capped by MAX_ACTIVE_DROPS).
 */
const drops = new Map();
let totalBytes = 0;

/** dropId -> Set<WebSocket> (authenticated subscribers) */
const activeSubscriptions = new Map();

function normId(value) {
    return (typeof value === 'string' && ID_RE.test(value)) ? value.toLowerCase() : null;
}

function sha256(buf) {
    return crypto.createHash('sha256').update(buf).digest();
}

function toWire(m) {
    return { id: m.id, ciphertext: m.ciphertext, createdAt: m.createdAt, ttl: m.ttl };
}

function isExpired(m, now) {
    return (now - m.createdAt) >= m.ttl;
}

function newDrop() {
    return { tokenHash: null, queue: [], lastActive: Date.now(), winStart: 0, winCount: 0 };
}

function removeMessages(drop, idSet) {
    const kept = [];
    let removed = 0;
    for (const m of drop.queue) {
        if (idSet.has(m.id)) {
            totalBytes -= m.bytes;
            removed++;
        } else {
            kept.push(m);
        }
    }
    drop.queue = kept;
    return removed;
}

function purgeExpired(drop, now) {
    if (drop.queue.length === 0) return 0;
    const kept = [];
    let purged = 0;
    for (const m of drop.queue) {
        if (isExpired(m, now)) {
            totalBytes -= m.bytes;
            purged++;
        } else {
            kept.push(m);
        }
    }
    drop.queue = kept;
    return purged;
}

/**
 * Verify (or, for an unclaimed dropId, establish) ownership of a mailbox.
 * Returns { ok: true, drop } or { ok: false, reason: 'unauthorized' | 'capacity' }.
 */
function authenticate(dropId, tokenHex) {
    const token = normId(tokenHex);
    if (!token) return { ok: false, reason: 'unauthorized' };
    const presented = sha256(Buffer.from(token, 'hex'));

    let drop = drops.get(dropId);
    if (!drop) {
        if (drops.size >= MAX_ACTIVE_DROPS) return { ok: false, reason: 'capacity' };
        drop = newDrop();
        drops.set(dropId, drop);
    }

    if (!drop.tokenHash) {
        drop.tokenHash = presented;               // first claim
    } else if (!crypto.timingSafeEqual(drop.tokenHash, presented)) {
        return { ok: false, reason: 'unauthorized' };
    }
    drop.lastActive = Date.now();
    return { ok: true, drop };
}

// Periodic GC: expire messages, forget idle claims and empty unclaimed drops
const gcTimer = setInterval(() => {
    const now = Date.now();
    for (const [dropId, drop] of drops.entries()) {
        purgeExpired(drop, now);
        if (drop.queue.length > 0 || activeSubscriptions.has(dropId)) continue;
        if (!drop.tokenHash || (now - drop.lastActive) > CLAIM_IDLE_MS) {
            drops.delete(dropId);
        }
    }
}, GC_INTERVAL_MS);

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

const BASE_HEADERS = {
    'Cache-Control': 'no-store, no-cache, must-revalidate, private',
    'Pragma': 'no-cache',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer'
};

function sendJson(res, statusCode, data, extraHeaders, onDone) {
    if (res.headersSent || res.writableEnded) return;
    const body = JSON.stringify(data);
    res.writeHead(statusCode, Object.assign({
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(body)
    }, BASE_HEADERS, extraHeaders || {}));
    res.end(body, onDone);
}

function rejectTooLarge(req, res) {
    // Answer first so the client can actually read the 413 (closing mid-upload makes many
    // HTTP clients see a connection reset instead). Whatever is still being uploaded is
    // discarded within a hard byte/time bound, after which the connection is destroyed.
    // Nothing is written to the response after destroy().
    req.removeAllListeners('data');
    let drained = 0;
    req.on('data', (chunk) => {
        drained += chunk.length;
        if (drained > MAX_BODY_BYTES) req.destroy();
    });
    const killer = setTimeout(() => req.destroy(), 10000);
    req.once('close', () => clearTimeout(killer));
    sendJson(res, 413, { error: 'Payload too large' });
}

/** Reads and parses a JSON object body. Resolves null after having replied on failure. */
function readJsonBody(req, res) {
    return new Promise((resolve) => {
        const declared = Number(req.headers['content-length']);
        if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) {
            rejectTooLarge(req, res);
            return resolve(null);
        }

        const chunks = [];
        let size = 0;
        let finished = false;

        req.on('data', (chunk) => {
            if (finished) return;
            size += chunk.length;
            if (size > MAX_BODY_BYTES) {
                finished = true;
                chunks.length = 0;
                rejectTooLarge(req, res);
                return resolve(null);
            }
            chunks.push(chunk);
        });

        req.on('end', () => {
            if (finished) return;
            finished = true;
            let parsed;
            try {
                parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
            } catch (e) {
                sendJson(res, 400, { error: 'Malformed JSON payload' });
                return resolve(null);
            }
            if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
                sendJson(res, 400, { error: 'JSON object expected' });
                return resolve(null);
            }
            resolve(parsed);
        });

        const abort = () => {
            if (finished) return;
            finished = true;
            resolve(null);                         // client went away: nobody to answer
        };
        req.on('error', abort);
        req.on('close', abort);
    });
}

function mailboxToken(req) {
    const h = req.headers['x-mailbox-token'];
    return typeof h === 'string' ? h.trim() : '';
}

/** Replies with 401/503 for a failed authenticate() result. */
function replyAuthFailure(res, result) {
    if (result.reason === 'capacity') {
        return sendJson(res, 503, { error: 'Relay at capacity' });
    }
    return sendJson(res, 401, { error: 'Unauthorized' });
}

function parseTtlSeconds(value) {
    if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
        return Math.min(Math.max(Math.floor(value), MIN_TTL_S), MAX_TTL_S);
    }
    return DEFAULT_TTL_S;
}

// ---------------------------------------------------------------------------
// API handlers
// ---------------------------------------------------------------------------

async function handleApi(req, res, pathname) {
    // Neutral health probe
    if (req.method === 'GET' && pathname === '/api/v2/ping') {
        return sendJson(res, 200, { status: 'ok', timestamp: Date.now() });
    }

    // POST /api/v2/register  { dropId }  + X-Mailbox-Token
    if (req.method === 'POST' && pathname === '/api/v2/register') {
        const data = await readJsonBody(req, res);
        if (!data) return;
        const dropId = normId(data.dropId);
        if (!dropId) return sendJson(res, 400, { error: 'Invalid dropId' });
        const auth = authenticate(dropId, mailboxToken(req));
        if (!auth.ok) return replyAuthFailure(res, auth);
        return sendJson(res, 200, { success: true });
    }

    // POST /api/v2/send  { dropId, ciphertext, ttlSeconds }   (no auth: senders only know the dropId)
    if (req.method === 'POST' && pathname === '/api/v2/send') {
        const data = await readJsonBody(req, res);
        if (!data) return;

        const dropId = normId(data.dropId);
        if (!dropId) return sendJson(res, 400, { error: 'Invalid dropId' });
        if (typeof data.ciphertext !== 'string' || data.ciphertext.length === 0) {
            return sendJson(res, 400, { error: 'Ciphertext is required' });
        }

        const bytes = Buffer.byteLength(data.ciphertext, 'utf8');
        if (bytes > MAX_BODY_BYTES) return sendJson(res, 413, { error: 'Payload too large' });

        const now = Date.now();
        let drop = drops.get(dropId);
        if (!drop && drops.size >= MAX_ACTIVE_DROPS) {
            return sendJson(res, 503, { error: 'Relay at capacity' });
        }

        if (drop) {
            purgeExpired(drop, now);
            if ((now - drop.winStart) >= 60000) {
                drop.winStart = now;
                drop.winCount = 0;
            }
            if (drop.winCount >= MAX_MESSAGES_PER_MINUTE) {
                const retry = Math.max(1, Math.ceil((60000 - (now - drop.winStart)) / 1000));
                return sendJson(res, 429, { error: 'Rate limit exceeded' }, { 'Retry-After': String(retry) });
            }
            if (drop.queue.length >= MAX_QUEUE_PER_DROP) {
                return sendJson(res, 429, { error: 'Mailbox full' });
            }
        }

        if (totalBytes + bytes > MAX_TOTAL_BYTES) {
            return sendJson(res, 503, { error: 'Relay at capacity' });
        }

        if (!drop) {
            drop = newDrop();
            drops.set(dropId, drop);
        }
        if ((now - drop.winStart) >= 60000) {
            drop.winStart = now;
            drop.winCount = 0;
        }

        const record = {
            id: crypto.randomUUID(),
            ciphertext: data.ciphertext,
            createdAt: now,
            ttl: parseTtlSeconds(data.ttlSeconds) * 1000,
            bytes
        };
        drop.queue.push(record);
        drop.winCount++;
        totalBytes += bytes;

        pushLive(dropId, record);

        // Deliberately neutral: no hint whether anyone is subscribed or how many messages are queued.
        return sendJson(res, 200, { success: true, messageId: record.id });
    }

    // GET /api/v2/inbox/:dropId  + X-Mailbox-Token   (does NOT delete; client must ACK)
    if (req.method === 'GET' && pathname.startsWith('/api/v2/inbox/')) {
        const dropId = normId(pathname.slice('/api/v2/inbox/'.length));
        if (!dropId) return sendJson(res, 400, { error: 'Invalid dropId' });
        const auth = authenticate(dropId, mailboxToken(req));
        if (!auth.ok) return replyAuthFailure(res, auth);

        const drop = auth.drop;
        purgeExpired(drop, Date.now());

        const messages = [];
        let budget = 0;
        let more = false;
        for (const m of drop.queue) {
            if (messages.length > 0 && budget + m.bytes > INBOX_RESPONSE_BYTES) {
                more = true;
                break;
            }
            budget += m.bytes;
            messages.push(toWire(m));
        }

        return sendJson(res, 200, { dropId, count: messages.length, more, messages });
    }

    // POST /api/v2/ack  { dropId, messageIds }  + X-Mailbox-Token
    if (req.method === 'POST' && pathname === '/api/v2/ack') {
        const data = await readJsonBody(req, res);
        if (!data) return;
        const dropId = normId(data.dropId);
        if (!dropId) return sendJson(res, 400, { error: 'Invalid dropId' });
        const auth = authenticate(dropId, mailboxToken(req));
        if (!auth.ok) return replyAuthFailure(res, auth);

        const ids = parseMessageIds(data.messageIds);
        if (!ids) return sendJson(res, 400, { error: 'messageIds must be an array of strings' });

        const removed = removeMessages(auth.drop, ids);
        return sendJson(res, 200, { success: true, removed });
    }

    return sendJson(res, 404, { error: 'Endpoint not found' });
}

function parseMessageIds(value) {
    if (!Array.isArray(value) || value.length > MAX_ACK_IDS) return null;
    const set = new Set();
    for (const id of value) {
        if (typeof id !== 'string' || id.length === 0 || id.length > 100) return null;
        set.add(id);
    }
    return set;
}

// ---------------------------------------------------------------------------
// Static files (web companion)
// ---------------------------------------------------------------------------

const MIME_TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.mjs': 'application/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.png': 'image/png',
    '.svg': 'image/svg+xml',
    '.ico': 'image/x-icon',
    '.webp': 'image/webp',
    '.woff2': 'font/woff2',
    '.txt': 'text/plain; charset=utf-8'
};

async function serveStatic(req, res, pathname) {
    let decoded;
    try {
        decoded = decodeURIComponent(pathname);
    } catch (e) {
        return sendJson(res, 400, { error: 'Bad request' });
    }
    if (decoded.includes('\0')) return sendJson(res, 400, { error: 'Bad request' });

    const safePath = (decoded === '/' || decoded === '/app') ? '/index.html' : decoded;
    const filePath = path.resolve(PUBLIC_DIR, '.' + safePath);
    if (!(filePath === PUBLIC_DIR || filePath.startsWith(PUBLIC_DIR + path.sep))) {
        return sendJson(res, 404, { error: 'Not found' });
    }

    let stat;
    try {
        stat = await fs.promises.stat(filePath);
    } catch (e) {
        return sendJson(res, 404, { error: 'Not found' });
    }
    if (!stat.isFile()) return sendJson(res, 404, { error: 'Not found' });

    let content;
    try {
        content = await fs.promises.readFile(filePath);
    } catch (e) {
        return sendJson(res, 404, { error: 'Not found' });
    }

    res.writeHead(200, Object.assign({
        'Content-Type': MIME_TYPES[path.extname(filePath).toLowerCase()] || 'application/octet-stream',
        'Content-Length': content.length
    }, BASE_HEADERS));
    res.end(req.method === 'HEAD' ? undefined : content);
}

// ---------------------------------------------------------------------------
// HTTP server
// ---------------------------------------------------------------------------

async function handleRequest(req, res) {
    let pathname;
    try {
        // Fixed base: the Host header is never trusted or needed for routing.
        pathname = new URL(req.url, 'http://localhost').pathname;
    } catch (e) {
        return sendJson(res, 400, { error: 'Bad request' });
    }

    if (pathname.startsWith('/api/')) return handleApi(req, res, pathname);

    if (req.method === 'GET' || req.method === 'HEAD') return serveStatic(req, res, pathname);

    return sendJson(res, 404, { error: 'Endpoint not found' });
}

const server = http.createServer((req, res) => {
    handleRequest(req, res).catch(() => {
        if (!res.headersSent) {
            sendJson(res, 500, { error: 'Internal error' });
        } else {
            res.destroy();
        }
    });
});

// ---------------------------------------------------------------------------
// WebSocket server (live push)
// ---------------------------------------------------------------------------

const wss = new WebSocketServer({ server, path: '/api/v2/live', maxPayload: WS_MAX_PAYLOAD });

function wsSend(ws, obj) {
    if (ws.readyState !== 1 /* OPEN */) return;
    try { ws.send(JSON.stringify(obj)); } catch (e) { /* socket closing */ }
}

function pushLive(dropId, record) {
    const subs = activeSubscriptions.get(dropId);
    if (!subs || subs.size === 0) return;
    const payload = JSON.stringify({ type: 'MESSAGE', dropId, message: toWire(record) });
    for (const ws of subs) {
        // If a socket is badly backed up the message simply stays queued (still ACK-able / re-deliverable).
        if (ws.readyState === 1 && ws.bufferedAmount < WS_BACKPRESSURE_BYTES) {
            try { ws.send(payload); } catch (e) { /* socket closing */ }
        }
    }
}

/** Sends the current backlog one message at a time so large queues cannot balloon socket buffers. */
async function pushBacklog(ws, dropId, drop) {
    const snapshot = drop.queue.slice();
    for (const m of snapshot) {
        if (ws.readyState !== 1 || !ws.subs.has(dropId)) return;
        if (isExpired(m, Date.now())) continue;
        const payload = JSON.stringify({ type: 'MESSAGE', dropId, message: toWire(m) });
        await new Promise((resolve) => {
            try { ws.send(payload, () => resolve()); } catch (e) { resolve(); }
        });
    }
}

function unsubscribe(ws, dropId) {
    ws.subs.delete(dropId);
    const set = activeSubscriptions.get(dropId);
    if (set) {
        set.delete(ws);
        if (set.size === 0) activeSubscriptions.delete(dropId);
    }
}

wss.on('connection', (ws) => {
    ws.subs = new Set();          // every dropId this connection is authenticated for
    ws.isAlive = true;

    ws.on('pong', () => { ws.isAlive = true; });

    ws.on('message', (raw, isBinary) => {
        if (isBinary) return;
        let data;
        try {
            data = JSON.parse(raw.toString('utf8'));
        } catch (e) {
            return;               // ignore malformed packets
        }
        if (!data || typeof data !== 'object') return;

        if (data.type === 'SUBSCRIBE') {
            const dropId = normId(data.dropId);
            if (!dropId) return wsSend(ws, { type: 'ERROR', error: 'Invalid dropId' });

            if (!ws.subs.has(dropId) && ws.subs.size >= MAX_SUBS_PER_SOCKET) {
                return wsSend(ws, { type: 'ERROR', dropId, error: 'Too many subscriptions' });
            }

            const auth = authenticate(dropId, data.readToken);
            if (!auth.ok) {
                return wsSend(ws, {
                    type: auth.reason === 'capacity' ? 'ERROR' : 'AUTH_FAILED',
                    dropId,
                    error: auth.reason === 'capacity' ? 'Relay at capacity' : 'Unauthorized'
                });
            }

            if (!ws.subs.has(dropId)) {
                ws.subs.add(dropId);
                if (!activeSubscriptions.has(dropId)) activeSubscriptions.set(dropId, new Set());
                activeSubscriptions.get(dropId).add(ws);
            }

            wsSend(ws, { type: 'SUBSCRIBED', dropId });
            purgeExpired(auth.drop, Date.now());
            pushBacklog(ws, dropId, auth.drop).catch(() => {});
            return;
        }

        if (data.type === 'ACK') {
            const dropId = normId(data.dropId);
            // The subscription was authenticated with the readToken; ACKs are only honoured on it.
            if (!dropId || !ws.subs.has(dropId)) {
                return wsSend(ws, { type: 'AUTH_FAILED', dropId: dropId || null, error: 'Unauthorized' });
            }
            const ids = parseMessageIds(data.messageIds);
            if (!ids) return wsSend(ws, { type: 'ERROR', dropId, error: 'messageIds must be an array of strings' });
            const drop = drops.get(dropId);
            const removed = drop ? removeMessages(drop, ids) : 0;
            if (drop) drop.lastActive = Date.now();
            return wsSend(ws, { type: 'ACKED', dropId, removed });
        }

        if (data.type === 'UNSUBSCRIBE') {
            const dropId = normId(data.dropId);
            if (dropId && ws.subs.has(dropId)) unsubscribe(ws, dropId);
            return;
        }

        if (data.type === 'PING') {
            return wsSend(ws, { type: 'PONG', timestamp: Date.now() });
        }
    });

    ws.on('close', () => {
        for (const dropId of Array.from(ws.subs)) unsubscribe(ws, dropId);
    });

    ws.on('error', () => {
        ws.terminate();
    });
});

// Server-side heartbeat: dead Tor circuits never send a close frame, so probe and terminate.
const heartbeatTimer = setInterval(() => {
    for (const ws of wss.clients) {
        if (ws.isAlive === false) {
            ws.terminate();
            continue;
        }
        ws.isAlive = false;
        try { ws.ping(); } catch (e) { ws.terminate(); }
    }
}, HEARTBEAT_MS);

function shutdown() {
    clearInterval(gcTimer);
    clearInterval(heartbeatTimer);
}
wss.on('close', shutdown);
server.on('close', shutdown);

server.listen(PORT, HOST, () => {
    if (HOST !== '127.0.0.1' && HOST !== '::1' && HOST !== 'localhost') {
        console.warn(`[GhostKey Relay] WARNING: bound to ${HOST}. Behind Tor this should be 127.0.0.1.`);
    }
    console.log(`[GhostKey Relay] protocol v2 listening on http://${HOST}:${PORT}`);
    console.log(`[GhostKey Relay] messages are held in RAM only; payloads are not logged`);
});
