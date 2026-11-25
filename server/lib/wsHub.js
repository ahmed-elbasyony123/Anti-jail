'use strict';

const { WebSocketServer } = require('ws');
const cfg = require('./config');

function createWsHub(server, store) {
    const wss = new WebSocketServer({
        server,
        path: '/api/v2/live',
        maxPayload: cfg.WS_MAX_PAYLOAD,
        verifyClient: (info, callback) => {
            const origin = info.origin;
            if (!origin) return callback(true);
            try {
                const host = new URL(origin).hostname.toLowerCase();
                if (host === 'localhost' || host === '127.0.0.1' || host === '::1' || host.endsWith('.onion')) {
                    return callback(true);
                }
                return callback(false, 403, 'Forbidden Origin');
            } catch (_) {
                return callback(false, 400, 'Invalid Origin');
            }
        }
    });

    function wsSend(ws, obj) {
        if (ws.readyState !== 1) return;
        try { ws.send(JSON.stringify(obj)); } catch (_) {}
    }

    function pushLive(dropId, record) {
        const subs = store.activeSubscriptions.get(dropId);
        if (!subs || subs.size === 0) return;
        const payload = JSON.stringify({ type: 'MESSAGE', dropId, message: store.toWire(record) });
        for (const ws of subs) {
            if (ws.readyState === 1 && ws.bufferedAmount < cfg.WS_BACKPRESSURE_BYTES) {
                try { ws.send(payload); } catch (_) {}
            }
        }
    }

    async function pushBacklog(ws, dropId, drop) {
        const snapshot = drop.queue.slice();
        for (const m of snapshot) {
            if (ws.readyState !== 1 || !ws.subs.has(dropId)) return;
            if (store.isExpired(m, Date.now())) continue;
            const payload = JSON.stringify({ type: 'MESSAGE', dropId, message: store.toWire(m) });
            await new Promise((resolve) => {
                try { ws.send(payload, () => resolve()); } catch (_) { resolve(); }
            });
        }
    }

    function unsubscribe(ws, dropId) {
        ws.subs.delete(dropId);
        const set = store.activeSubscriptions.get(dropId);
        if (set) {
            set.delete(ws);
            if (set.size === 0) store.activeSubscriptions.delete(dropId);
        }
    }

    wss.on('connection', (ws) => {
        ws.subs = new Set();
        ws.isAlive = true;
        ws.on('pong', () => { ws.isAlive = true; });

        ws.on('message', (raw, isBinary) => {
            if (isBinary) return;
            let data;
            try { data = JSON.parse(raw.toString('utf8')); } catch (_) { return; }
            if (!data || typeof data !== 'object') return;

            if (data.type === 'SUBSCRIBE') {
                const dropId = store.normId(data.dropId);
                if (!dropId) return wsSend(ws, { type: 'ERROR', error: 'Invalid dropId' });
                if (!ws.subs.has(dropId) && ws.subs.size >= cfg.MAX_SUBS_PER_SOCKET) {
                    return wsSend(ws, { type: 'ERROR', dropId, error: 'Too many subscriptions' });
                }
                const auth = store.authenticate(dropId, data.readToken);
                if (!auth.ok) {
                    return wsSend(ws, {
                        type: auth.reason === 'capacity' ? 'ERROR' : auth.reason === 'rate_limited' ? 'RATE_LIMITED' : 'AUTH_FAILED',
                        dropId,
                        error: auth.reason === 'capacity' ? 'Relay at capacity' : auth.reason === 'rate_limited' ? 'Too many auth failures' : 'Unauthorized'
                    });
                }
                if (!ws.subs.has(dropId)) {
                    ws.subs.add(dropId);
                    if (!store.activeSubscriptions.has(dropId)) store.activeSubscriptions.set(dropId, new Set());
                    store.activeSubscriptions.get(dropId).add(ws);
                }
                wsSend(ws, { type: 'SUBSCRIBED', dropId });
                store.purgeExpired(auth.drop, Date.now());
                pushBacklog(ws, dropId, auth.drop).catch(() => {});
                return;
            }

            if (data.type === 'ACK') {
                const dropId = store.normId(data.dropId);
                if (!dropId || !ws.subs.has(dropId)) {
                    return wsSend(ws, { type: 'AUTH_FAILED', dropId: dropId || null, error: 'Unauthorized' });
                }
                const ids = store.parseMessageIds(data.messageIds);
                if (!ids) return wsSend(ws, { type: 'ERROR', dropId, error: 'messageIds must be an array of strings' });
                const drop = store.drops.get(dropId);
                const removed = drop ? store.removeMessages(drop, ids) : 0;
                if (drop) drop.lastActive = Date.now();
                return wsSend(ws, { type: 'ACKED', dropId, removed });
            }

            if (data.type === 'UNSUBSCRIBE') {
                const dropId = store.normId(data.dropId);
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
        ws.on('error', () => ws.terminate());
    });

    const heartbeatTimer = setInterval(() => {
        for (const ws of wss.clients) {
            if (ws.isAlive === false) { ws.terminate(); continue; }
            ws.isAlive = false;
            try { ws.ping(); } catch (_) { ws.terminate(); }
        }
    }, cfg.HEARTBEAT_MS);

    function close() {
        clearInterval(heartbeatTimer);
    }
    wss.on('close', close);

    return { wss, pushLive, close };
}

module.exports = { createWsHub };
