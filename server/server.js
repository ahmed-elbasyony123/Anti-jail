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
let wsHub = null;

const server = http.createServer((req, res) => {
    handleRequest(req, res).catch(() => {
        if (!res.headersSent) sendJson(res, 500, { error: 'Internal error' });
        else res.destroy();
    });
});

wsHub = createWsHub(server, store);
const handleRequest = createHttpHandler(store, wsHub);

function shutdown() {
    store.close();
    wsHub.close();
}
server.on('close', shutdown);

server.listen(cfg.PORT, cfg.HOST, () => {
    if (cfg.HOST !== '127.0.0.1' && cfg.HOST !== '::1' && cfg.HOST !== 'localhost') {
        console.warn(`[GhostKey Relay] WARNING: bound to ${cfg.HOST}. Behind Tor this should be 127.0.0.1.`);
    }
    console.log(`[GhostKey Relay] protocol v2 listening on http://${cfg.HOST}:${cfg.PORT}`);
    console.log(`[GhostKey Relay] messages are held in RAM only; payloads are not logged`);
});

process.on('unhandledRejection', (reason, promise) => {
    console.error('[GhostKey Relay] Unhandled Rejection at:', promise, 'reason:', reason);
});
process.on('uncaughtException', (err) => {
    console.error('[GhostKey Relay] Uncaught Exception:', err);
});
