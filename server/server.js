/**
 * GhostKey Blind Relay Server
 * -------------------------------------------------------------
 * Threat Model & Security Properties:
 * 1. Zero-Log: Does NOT log client IP addresses, headers, or payloads.
 * 2. RAM-Only: Messages exist exclusively in memory (Map). Zero disk writes.
 * 3. Blind Dead-Drop: Server only receives SHA-256 mailbox hashes and PGP ciphertexts.
 * 4. Burn-On-Read: Once a message is retrieved or pushed via WebSocket, it is expunged.
 * 5. TTL Eviction: Messages older than their TTL are automatically pruned by GC.
 * 6. Tor-Ready: Designed to run behind a Tor Hidden Service (.onion).
 */

const http = require('http');
const { WebSocketServer } = require('ws');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 8765;
const HOST = process.env.HOST || '0.0.0.0';
const DEFAULT_TTL_MS = 60 * 60 * 1000; // 1 hour max retention in RAM
const MAX_PAYLOAD_BYTES = 10 * 1024 * 1024; // 10 MB per encrypted payload (supports Images, Audio & Files)

/**
 * In-memory Mailbox storage:
 * Key: Mailbox Hash (hex string, e.g. SHA-256 of Recipient Fingerprint)
 * Value: Array of Message objects
 */
const mailboxes = new Map();

/**
 * Active WebSocket connections mapped by Mailbox Hash:
 * Key: Mailbox Hash
 * Value: Set of WebSocket clients subscribed to this mailbox
 */
const activeSubscriptions = new Map();

// Periodic GC: Purge expired messages from RAM every 30 seconds
setInterval(() => {
    const now = Date.now();
    let purgedCount = 0;

    for (const [mailboxHash, messages] of mailboxes.entries()) {
        const remaining = messages.filter(msg => (now - msg.createdAt) < msg.ttl);
        purgedCount += (messages.length - remaining.length);

        if (remaining.length === 0) {
            mailboxes.delete(mailboxHash);
        } else {
            mailboxes.set(mailboxHash, remaining);
        }
    }

    if (purgedCount > 0 && process.env.DEBUG) {
        console.log(`[GC] Evicted ${purgedCount} expired message(s) from RAM.`);
    }
}, 30000);

// Helper to send JSON responses with CORS headers
function sendJson(res, statusCode, data) {
    const body = JSON.stringify(data);
    res.writeHead(statusCode, {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, X-Mailbox-Token',
        'Cache-Control': 'no-store, no-cache, must-revalidate, private',
        'Pragma': 'no-cache',
        'Strict-Transport-Security': 'max-age=31536000; includeSubDomains'
    });
    res.end(body);
}

// HTTP Server
const server = http.createServer((req, res) => {
    // Handle CORS preflight
    if (req.method === 'OPTIONS') {
        res.writeHead(204, {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type, X-Mailbox-Token',
            'Access-Control-Max-Age': '86400'
        });
        return res.end();
    }

    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const pathname = url.pathname;

    // Serve static assets from public/ directory
    let safePath = (pathname === '/' || pathname === '/app') ? '/index.html' : pathname;
    const staticFilePath = path.join(__dirname, 'public', path.normalize(safePath).replace(/^(\.\.[\/\\])+/, ''));
    if (fs.existsSync(staticFilePath) && fs.statSync(staticFilePath).isFile()) {
        const ext = path.extname(staticFilePath).toLowerCase();
        const mimeTypes = {
            '.html': 'text/html; charset=utf-8',
            '.js': 'application/javascript; charset=utf-8',
            '.css': 'text/css; charset=utf-8',
            '.json': 'application/json',
            '.png': 'image/png',
            '.svg': 'image/svg+xml'
        };
        const contentType = mimeTypes[ext] || 'application/octet-stream';
        res.writeHead(200, {
            'Content-Type': contentType,
            'Cache-Control': 'no-store, no-cache, must-revalidate, private'
        });
        return res.end(fs.readFileSync(staticFilePath));
    }

    // Health / Status probe (Stealth: returns neutral status without revealing sensitive details)
    if (pathname === '/api/v1/ping') {
        return sendJson(res, 200, {
            status: 'online',
            service: 'GhostKey Blind Relay',
            encryption: 'OpenPGP Compatible',
            storage: 'RAM-Only / Ephemeral',
            timestamp: Date.now()
        });
    }

    // POST /api/v1/send : Drop a PGP ciphertext message into a mailbox
    if (req.method === 'POST' && pathname === '/api/v1/send') {
        let body = '';
        let bodyLength = 0;

        req.on('data', chunk => {
            bodyLength += chunk.length;
            if (bodyLength > MAX_PAYLOAD_BYTES) {
                req.destroy();
                return sendJson(res, 413, { error: 'Payload too large' });
            }
            body += chunk;
        });

        req.on('end', () => {
            try {
                const data = JSON.parse(body);
                const { mailboxHash, ciphertext, ttlSeconds } = data;

                if (!mailboxHash || typeof mailboxHash !== 'string' || mailboxHash.length < 16) {
                    return sendJson(res, 400, { error: 'Invalid mailboxHash identifier' });
                }

                if (!ciphertext || typeof ciphertext !== 'string') {
                    return sendJson(res, 400, { error: 'Ciphertext is required' });
                }

                const msgId = crypto.randomUUID();
                const ttl = Math.min((ttlSeconds ? ttlSeconds * 1000 : DEFAULT_TTL_MS), DEFAULT_TTL_MS);

                const messageRecord = {
                    id: msgId,
                    ciphertext: ciphertext.trim(),
                    createdAt: Date.now(),
                    ttl: ttl
                };

                // Check if recipient is actively connected via WebSocket
                const activeSockets = activeSubscriptions.get(mailboxHash);
                if (activeSockets && activeSockets.size > 0) {
                    let delivered = false;
                    for (const ws of activeSockets) {
                        if (ws.readyState === 1 /* OPEN */) {
                            ws.send(JSON.stringify({
                                type: 'MESSAGE',
                                message: messageRecord
                            }));
                            delivered = true;
                        }
                    }

                    if (delivered) {
                        // Instant Burn-On-Read: do not store in RAM queue if delivered directly
                        return sendJson(res, 200, {
                            success: true,
                            messageId: msgId,
                            status: 'delivered_live'
                        });
                    }
                }

                // If not delivered live, store in RAM queue until recipient polls
                if (!mailboxes.has(mailboxHash)) {
                    mailboxes.set(mailboxHash, []);
                }
                mailboxes.get(mailboxHash).push(messageRecord);

                return sendJson(res, 200, {
                    success: true,
                    messageId: msgId,
                    status: 'queued_in_memory'
                });
            } catch (err) {
                return sendJson(res, 400, { error: 'Malformed JSON payload' });
            }
        });
        return;
    }

    // GET /api/v1/inbox/:mailboxHash : Fetch and BURN all pending messages for this mailbox
    if (req.method === 'GET' && pathname.startsWith('/api/v1/inbox/')) {
        const mailboxHash = pathname.replace('/api/v1/inbox/', '').trim();
        if (!mailboxHash || mailboxHash.length < 16) {
            return sendJson(res, 400, { error: 'Invalid mailbox identifier' });
        }

        const messages = mailboxes.get(mailboxHash) || [];

        // Burn-On-Read: immediately expunge from RAM
        mailboxes.delete(mailboxHash);

        return sendJson(res, 200, {
            mailboxHash: mailboxHash,
            count: messages.length,
            messages: messages
        });
    }

    // Fallback 404
    sendJson(res, 404, { error: 'Endpoint not found' });
});

// WebSocket Server for live push notifications
const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
    let currentMailbox = null;

    ws.on('message', (messageRaw) => {
        try {
            const data = JSON.parse(messageRaw.toString());

            // SUBSCRIBE to a mailbox
            if (data.type === 'SUBSCRIBE' && data.mailboxHash) {
                currentMailbox = data.mailboxHash;

                if (!activeSubscriptions.has(currentMailbox)) {
                    activeSubscriptions.set(currentMailbox, new Set());
                }
                activeSubscriptions.get(currentMailbox).add(ws);

                ws.send(JSON.stringify({
                    type: 'SUBSCRIBED',
                    mailboxHash: currentMailbox
                }));

                // Deliver any backlog messages currently waiting in RAM and BURN them
                if (mailboxes.has(currentMailbox)) {
                    const pending = mailboxes.get(currentMailbox) || [];
                    mailboxes.delete(currentMailbox);

                    for (const msg of pending) {
                        ws.send(JSON.stringify({
                            type: 'MESSAGE',
                            message: msg
                        }));
                    }
                }
            }

            // PING / HEARTBEAT
            if (data.type === 'PING') {
                ws.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
            }
        } catch (err) {
            // Ignore malformed packets silently
        }
    });

    ws.on('close', () => {
        if (currentMailbox && activeSubscriptions.has(currentMailbox)) {
            const subscribers = activeSubscriptions.get(currentMailbox);
            subscribers.delete(ws);
            if (subscribers.size === 0) {
                activeSubscriptions.delete(currentMailbox);
            }
        }
    });

    ws.on('error', () => {
        ws.terminate();
    });
});

server.listen(PORT, HOST, () => {
    console.log(`=======================================================`);
    console.log(`[GhostKey Relay] Live on http://${HOST}:${PORT}`);
    console.log(`[GhostKey Relay] Architecture: Zero-Disk / RAM Only / Blind`);
    console.log(`[GhostKey Relay] Ready for Tor Onion Service bridging`);
    console.log(`=======================================================`);
});
