/**
 * GhostKey Blind Relay Server (wire protocol v2)
 * Clean modular entrypoint composing config, RAM mailbox store, WebSocket live hub, and HTTP routes.
 */

'use strict';

const http = require('http');
const cfg = require('./lib/config');
const { MailboxStore } = require('./lib/mailboxStore');
const { createWsHub } = require('./lib/wsHub');
const { createHttpHandler, sendJson } = require('./lib/httpRoutes');

const store = new MailboxStore();

let handleRequest = null;
const server = http.createServer((req, res) => {
    handleRequest(req, res).catch(() => {
        if (!res.headersSent) sendJson(res, 500, { error: 'Internal error' });
        else res.destroy();
    });
});

// Slowloris and idle-socket hardening
server.requestTimeout = cfg.REQUEST_TIMEOUT_MS;
server.headersTimeout = cfg.HEADERS_TIMEOUT_MS;
server.keepAliveTimeout = cfg.KEEP_ALIVE_TIMEOUT_MS;

const wsHub = createWsHub(server, store);
handleRequest = createHttpHandler(store, wsHub);

let closing = false;
function shutdown() {
    if (closing) return;
    closing = true;
    store.close();
    wsHub.close();
}
server.on('close', shutdown);

function handleSignal() {
    shutdown();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 1500).unref();
}
process.on('SIGINT', handleSignal);
process.on('SIGTERM', handleSignal);

server.listen(cfg.PORT, cfg.HOST, () => {
    if (cfg.HOST !== '127.0.0.1' && cfg.HOST !== '::1' && cfg.HOST !== 'localhost') {
        console.warn(`[GhostKey Relay] WARNING: bound to ${cfg.HOST}. Behind Tor this should be 127.0.0.1.`);
    }
    console.log(`[GhostKey Relay] protocol v2 listening on http://${cfg.HOST}:${cfg.PORT}`);
    console.log('[GhostKey Relay] messages are held in RAM only; payloads are not logged');
});

process.on('unhandledRejection', (reason, promise) => {
    console.error('[GhostKey Relay] Unhandled Rejection at:', promise, 'reason:', reason);
});
process.on('uncaughtException', (err) => {
    console.error('[GhostKey Relay] Uncaught Exception:', err);
});
