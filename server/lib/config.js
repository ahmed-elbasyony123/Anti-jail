'use strict';

const path = require('path');

function envInt(name, fallback) {
    const v = Number(process.env[name]);
    return Number.isFinite(v) && v > 0 ? Math.floor(v) : fallback;
}

const MAX_ACTIVE_DROPS = envInt('GK_MAX_ACTIVE_DROPS', 10000);

module.exports = {
    PORT: envInt('PORT', 8765),
    HOST: process.env.HOST || '127.0.0.1',
    // 32 MB covers 15 MB raw attachments after WireFormat Base64 (+33%) and OpenPGP armor (+37%)
    MAX_BODY_BYTES: 32 * 1024 * 1024,
    WS_MAX_PAYLOAD: 32 * 1024 * 1024,
    DEFAULT_TTL_S: 3600,
    MIN_TTL_S: 60,
    MAX_TTL_S: 86400,
    MAX_MESSAGES_PER_MINUTE: envInt('GK_MAX_MSG_PER_MIN', 30),
    MAX_QUEUE_PER_DROP: envInt('GK_MAX_QUEUE', 100),
    MAX_ACTIVE_DROPS,
    // Cap unclaimed drops so unauthenticated spam cannot starve claimed mailboxes
    MAX_UNCLAIMED_DROPS: envInt('GK_MAX_UNCLAIMED_DROPS', Math.min(MAX_ACTIVE_DROPS, 5000)),
    MAX_TOTAL_BYTES: envInt('GK_MAX_TOTAL_BYTES', 512 * 1024 * 1024),
    CLAIM_IDLE_MS: envInt('GK_CLAIM_IDLE_MS', 3 * 24 * 60 * 60 * 1000),
    GC_INTERVAL_MS: envInt('GK_GC_INTERVAL_MS', 30000),
    HEARTBEAT_MS: envInt('GK_HEARTBEAT_MS', 30000),
    REQUEST_TIMEOUT_MS: envInt('GK_REQUEST_TIMEOUT_MS', 30000),
    HEADERS_TIMEOUT_MS: envInt('GK_HEADERS_TIMEOUT_MS', 15000),
    KEEP_ALIVE_TIMEOUT_MS: envInt('GK_KEEP_ALIVE_TIMEOUT_MS', 5000),
    MAX_SUBS_PER_SOCKET: 8,
    MAX_ACK_IDS: 1000,
    INBOX_RESPONSE_BYTES: 36 * 1024 * 1024,
    WS_BACKPRESSURE_BYTES: 36 * 1024 * 1024,
    RENDEZVOUS_DEFAULT_TTL_S: 300,
    RENDEZVOUS_MIN_TTL_S: 60,
    RENDEZVOUS_MAX_TTL_S: 900,
    MAX_RENDEZVOUS_SLOTS: envInt('GK_MAX_RENDEZVOUS_SLOTS', 2000),
    MAX_RENDEZVOUS_BYTES: envInt('GK_MAX_RENDEZVOUS_BYTES', 131072),
    PUBLIC_DIR: path.resolve(__dirname, '..', 'public'),
    ID_RE: /^[0-9a-fA-F]{64}$/
};
