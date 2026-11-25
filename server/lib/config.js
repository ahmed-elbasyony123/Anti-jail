'use strict';

const path = require('path');

function envInt(name, fallback) {
    const v = Number(process.env[name]);
    return Number.isFinite(v) && v > 0 ? Math.floor(v) : fallback;
}

module.exports = {
    PORT: envInt('PORT', 8765),
    HOST: process.env.HOST || '127.0.0.1',
    MAX_BODY_BYTES: 25 * 1024 * 1024,
    WS_MAX_PAYLOAD: 25 * 1024 * 1024,
    DEFAULT_TTL_S: 3600,
    MIN_TTL_S: 60,
    MAX_TTL_S: 86400,
    MAX_MESSAGES_PER_MINUTE: envInt('GK_MAX_MSG_PER_MIN', 30),
    MAX_QUEUE_PER_DROP: envInt('GK_MAX_QUEUE', 100),
    MAX_ACTIVE_DROPS: envInt('GK_MAX_ACTIVE_DROPS', 10000),
    MAX_TOTAL_BYTES: envInt('GK_MAX_TOTAL_BYTES', 512 * 1024 * 1024),
    CLAIM_IDLE_MS: envInt('GK_CLAIM_IDLE_MS', 3 * 24 * 60 * 60 * 1000),
    GC_INTERVAL_MS: envInt('GK_GC_INTERVAL_MS', 30000),
    HEARTBEAT_MS: envInt('GK_HEARTBEAT_MS', 30000),
    MAX_SUBS_PER_SOCKET: 8,
    MAX_ACK_IDS: 1000,
    INBOX_RESPONSE_BYTES: 32 * 1024 * 1024,
    WS_BACKPRESSURE_BYTES: 32 * 1024 * 1024,
    PUBLIC_DIR: path.resolve(__dirname, '..', 'public'),
    ID_RE: /^[0-9a-fA-F]{64}$/
};
