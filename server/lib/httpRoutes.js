'use strict';

const fs = require('fs');
const path = require('path');
const cfg = require('./config');

const BASE_HEADERS = {
    'Cache-Control': 'no-store, no-cache, must-revalidate, private',
    'Pragma': 'no-cache',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self' ws: wss:; frame-ancestors 'none'; object-src 'none'; base-uri 'none';",
    'Referrer-Policy': 'no-referrer'
};

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
    req.removeAllListeners('data');
    let drained = 0;
    req.on('data', (chunk) => {
        drained += chunk.length;
        if (drained > cfg.MAX_BODY_BYTES) req.destroy();
    });
    const killer = setTimeout(() => req.destroy(), 10000);
    req.once('close', () => clearTimeout(killer));
    sendJson(res, 413, { error: 'Payload too large' });
}

function readJsonBody(req, res) {
    return new Promise((resolve) => {
        const ct = req.headers['content-type'];
        if (!ct || !ct.toLowerCase().includes('application/json')) {
            sendJson(res, 415, { error: 'Unsupported media type; application/json expected' });
            return resolve(null);
        }
        if (req.headers['sec-fetch-site'] === 'cross-site') {
            sendJson(res, 403, { error: 'Cross-site requests forbidden' });
            return resolve(null);
        }
        const declared = Number(req.headers['content-length']);
        if (Number.isFinite(declared) && declared > cfg.MAX_BODY_BYTES) {
            rejectTooLarge(req, res);
            return resolve(null);
        }

        const chunks = [];
        let size = 0;
        let finished = false;

        req.on('data', (chunk) => {
            if (finished) return;
            size += chunk.length;
            if (size > cfg.MAX_BODY_BYTES) {
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
            } catch (_) {
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
            resolve(null);
        };
        req.on('error', abort);
        req.on('close', abort);
    });
}

function mailboxToken(req) {
    const h = req.headers['x-mailbox-token'];
    return typeof h === 'string' ? h.trim() : '';
}

function replyAuthFailure(res, result) {
    if (result.reason === 'capacity') return sendJson(res, 503, { error: 'Relay at capacity' });
    if (result.reason === 'rate_limited') {
        return sendJson(res, 429, { error: 'Too many authentication failures, try again shortly' }, { 'Retry-After': '60' });
    }
    return sendJson(res, 401, { error: 'Unauthorized' });
}

function createHttpHandler(store, wsHub) {
    async function handleApi(req, res, pathname) {
        if (req.method === 'GET' && pathname === '/api/v2/ping') {
            return sendJson(res, 200, { status: 'ok', timestamp: Date.now() });
        }

        if (req.method === 'POST' && pathname === '/api/v2/register') {
            const data = await readJsonBody(req, res);
            if (!data) return;
            const dropId = store.normId(data.dropId);
            if (!dropId) return sendJson(res, 400, { error: 'Invalid dropId' });
            const auth = store.authenticate(dropId, mailboxToken(req));
            if (!auth.ok) return replyAuthFailure(res, auth);
            return sendJson(res, 200, { success: true });
        }

        if (req.method === 'POST' && pathname === '/api/v2/send') {
            const data = await readJsonBody(req, res);
            if (!data) return;
            const dropId = store.normId(data.dropId);
            if (!dropId) return sendJson(res, 400, { error: 'Invalid dropId' });
            if (typeof data.ciphertext !== 'string' || data.ciphertext.length === 0) {
                return sendJson(res, 400, { error: 'Ciphertext is required' });
            }
            const enq = store.enqueueMessage(dropId, data.ciphertext, data.ttlSeconds);
            if (!enq.ok) return sendJson(res, enq.status, { error: enq.error }, enq.headers);
            wsHub.pushLive(dropId, enq.record);
            return sendJson(res, 200, { success: true, messageId: enq.record.id });
        }

        if (req.method === 'GET' && pathname.startsWith('/api/v2/inbox/')) {
            const dropId = store.normId(pathname.slice('/api/v2/inbox/'.length));
            if (!dropId) return sendJson(res, 400, { error: 'Invalid dropId' });
            const auth = store.authenticate(dropId, mailboxToken(req));
            if (!auth.ok) return replyAuthFailure(res, auth);

            const drop = auth.drop;
            store.purgeExpired(drop, Date.now());
            const messages = [];
            let budget = 0;
            let more = false;
            for (const m of drop.queue) {
                if (messages.length > 0 && budget + m.bytes > cfg.INBOX_RESPONSE_BYTES) {
                    more = true;
                    break;
                }
                budget += m.bytes;
                messages.push(store.toWire(m));
            }
            return sendJson(res, 200, { dropId, count: messages.length, more, messages });
        }

        if (req.method === 'POST' && pathname === '/api/v2/ack') {
            const data = await readJsonBody(req, res);
            if (!data) return;
            const dropId = store.normId(data.dropId);
            if (!dropId) return sendJson(res, 400, { error: 'Invalid dropId' });
            const auth = store.authenticate(dropId, mailboxToken(req));
            if (!auth.ok) return replyAuthFailure(res, auth);

            const ids = store.parseMessageIds(data.messageIds);
            if (!ids) return sendJson(res, 400, { error: 'messageIds must be an array of strings' });
            const removed = store.removeMessages(auth.drop, ids);
            return sendJson(res, 200, { success: true, removed });
        }

        return sendJson(res, 404, { error: 'Endpoint not found' });
    }

    async function serveStatic(req, res, pathname) {
        let decoded;
        try { decoded = decodeURIComponent(pathname); } catch (_) { return sendJson(res, 400, { error: 'Bad request' }); }
        if (decoded.includes('\0')) return sendJson(res, 400, { error: 'Bad request' });

        const safePath = (decoded === '/' || decoded === '/app' || decoded === '/app/') ? '/index.html' : decoded;
        const filePath = path.resolve(cfg.PUBLIC_DIR, '.' + safePath);
        if (!(filePath === cfg.PUBLIC_DIR || filePath.startsWith(cfg.PUBLIC_DIR + path.sep))) {
            return sendJson(res, 404, { error: 'Not found' });
        }
        if (path.basename(filePath).startsWith('.')) return sendJson(res, 404, { error: 'Not found' });

        let stat;
        try { stat = await fs.promises.stat(filePath); } catch (_) { return sendJson(res, 404, { error: 'Not found' }); }
        if (!stat.isFile()) return sendJson(res, 404, { error: 'Not found' });

        try {
            const real = await fs.promises.realpath(filePath);
            if (!(real === cfg.PUBLIC_DIR || real.startsWith(cfg.PUBLIC_DIR + path.sep))) {
                return sendJson(res, 404, { error: 'Not found' });
            }
        } catch (_) {
            return sendJson(res, 404, { error: 'Not found' });
        }

        res.writeHead(200, Object.assign({
            'Content-Type': MIME_TYPES[path.extname(filePath).toLowerCase()] || 'application/octet-stream',
            'Content-Length': stat.size
        }, BASE_HEADERS));

        if (req.method === 'HEAD') return res.end();

        const stream = fs.createReadStream(filePath);
        res.on('close', () => { if (!stream.destroyed) stream.destroy(); });
        stream.on('error', () => {
            if (!res.headersSent) sendJson(res, 500, { error: 'Internal error' });
            else res.destroy();
        });
        stream.pipe(res);
    }

    return async function handleRequest(req, res) {
        let pathname;
        try { pathname = new URL(req.url, 'http://localhost').pathname; } catch (_) { return sendJson(res, 400, { error: 'Bad request' }); }
        if (pathname.startsWith('/api/')) return handleApi(req, res, pathname);
        if (req.method === 'GET' || req.method === 'HEAD') return serveStatic(req, res, pathname);
        return sendJson(res, 404, { error: 'Endpoint not found' });
    };
}

module.exports = { createHttpHandler, sendJson };
