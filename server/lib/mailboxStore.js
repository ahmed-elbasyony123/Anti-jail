'use strict';

const crypto = require('crypto');
const cfg = require('./config');

class MailboxStore {
    constructor() {
        this.drops = new Map();
        this.totalBytes = 0;
        this.authFailures = new Map();
        this.activeSubscriptions = new Map();
        this.gcTimer = setInterval(() => this.runGc(), cfg.GC_INTERVAL_MS);
    }

    normId(value) {
        return (typeof value === 'string' && cfg.ID_RE.test(value)) ? value.toLowerCase() : null;
    }

    deriveDropId(tokenHex) {
        const token = this.normId(tokenHex);
        if (!token) return null;
        return crypto.createHash('sha256').update('drop:' + token, 'utf8').digest('hex');
    }

    toWire(m) {
        return { id: m.id, ciphertext: m.ciphertext, createdAt: m.createdAt, ttl: m.ttl };
    }

    isExpired(m, now) {
        return (now - m.createdAt) >= m.ttl;
    }

    newDrop(now = Date.now()) {
        return { claimed: false, queue: [], lastActive: now, winStart: now, winCount: 0 };
    }

    parseTtlSeconds(value) {
        if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
            return Math.min(Math.max(Math.floor(value), cfg.MIN_TTL_S), cfg.MAX_TTL_S);
        }
        return cfg.DEFAULT_TTL_S;
    }

    parseMessageIds(value) {
        if (!Array.isArray(value) || value.length > cfg.MAX_ACK_IDS) return null;
        const set = new Set();
        for (const id of value) {
            if (typeof id !== 'string' || id.length === 0 || id.length > 100) return null;
            set.add(id);
        }
        return set;
    }

    removeMessages(drop, idSet) {
        const kept = [];
        let removed = 0;
        for (const m of drop.queue) {
            if (idSet.has(m.id)) {
                this.totalBytes -= m.bytes;
                removed++;
            } else {
                kept.push(m);
            }
        }
        drop.queue = kept;
        return removed;
    }

    purgeExpired(drop, now) {
        if (drop.queue.length === 0) return 0;
        const kept = [];
        let purged = 0;
        for (const m of drop.queue) {
            if (this.isExpired(m, now)) {
                this.totalBytes -= m.bytes;
                purged++;
            } else {
                kept.push(m);
            }
        }
        drop.queue = kept;
        return purged;
    }

    authenticate(dropId, tokenHex) {
        const token = this.normId(tokenHex);
        if (!token) return { ok: false, reason: 'unauthorized' };

        const expectedDropId = this.deriveDropId(token);
        const expectedBuf = expectedDropId ? Buffer.from(expectedDropId, 'utf8') : null;
        const dropBuf = dropId ? Buffer.from(dropId, 'utf8') : null;
        const isValidToken = expectedBuf && dropBuf &&
            expectedBuf.length === dropBuf.length &&
            crypto.timingSafeEqual(expectedBuf, dropBuf);

        const now = Date.now();
        const failRecord = this.authFailures.get(dropId);

        if (!isValidToken) {
            if (failRecord && failRecord.lockedUntil > now) {
                return { ok: false, reason: 'rate_limited' };
            }
            if (this.authFailures.size >= 10000) {
                const oldestKey = this.authFailures.keys().next().value;
                if (oldestKey) this.authFailures.delete(oldestKey);
            }
            const rec = failRecord || { count: 0, winStart: now, lockedUntil: 0 };
            if (now - rec.winStart > 60000) { rec.winStart = now; rec.count = 0; }
            rec.count++;
            if (rec.count >= 15) rec.lockedUntil = now + 60000;
            this.authFailures.set(dropId, rec);
            return { ok: false, reason: 'unauthorized' };
        }

        this.authFailures.delete(dropId);
        let drop = this.drops.get(dropId);
        if (!drop) {
            if (this.drops.size >= cfg.MAX_ACTIVE_DROPS) return { ok: false, reason: 'capacity' };
            drop = this.newDrop(now);
            this.drops.set(dropId, drop);
        }
        drop.claimed = true;
        drop.lastActive = now;
        return { ok: true, drop };
    }

    enqueueMessage(dropId, ciphertext, ttlSeconds) {
        const bytes = Buffer.byteLength(ciphertext, 'utf8');
        if (bytes > cfg.MAX_BODY_BYTES) return { ok: false, status: 413, error: 'Payload too large' };

        const now = Date.now();
        let drop = this.drops.get(dropId);
        if (!drop && this.drops.size >= cfg.MAX_ACTIVE_DROPS) {
            return { ok: false, status: 503, error: 'Relay at capacity' };
        }

        if (drop) {
            this.purgeExpired(drop, now);
            if ((now - drop.winStart) >= 60000) {
                drop.winStart = now;
                drop.winCount = 0;
            }
            if (drop.winCount >= cfg.MAX_MESSAGES_PER_MINUTE) {
                const retry = Math.max(1, Math.ceil((60000 - (now - drop.winStart)) / 1000));
                return { ok: false, status: 429, error: 'Rate limit exceeded', headers: { 'Retry-After': String(retry) } };
            }
            if (drop.queue.length >= cfg.MAX_QUEUE_PER_DROP) {
                return { ok: false, status: 429, error: 'Mailbox full' };
            }
        }

        if (this.totalBytes + bytes > cfg.MAX_TOTAL_BYTES) {
            return { ok: false, status: 503, error: 'Relay at capacity' };
        }

        if (!drop) {
            drop = this.newDrop(now);
            this.drops.set(dropId, drop);
        }

        const record = {
            id: crypto.randomUUID(),
            ciphertext,
            createdAt: now,
            ttl: this.parseTtlSeconds(ttlSeconds) * 1000,
            bytes
        };
        drop.queue.push(record);
        drop.winCount++;
        this.totalBytes += bytes;
        return { ok: true, record };
    }

    runGc() {
        const now = Date.now();
        for (const [dropId, drop] of this.drops.entries()) {
            this.purgeExpired(drop, now);
            if (drop.queue.length > 0 || this.activeSubscriptions.has(dropId)) continue;
            if (!drop.claimed || (now - drop.lastActive) > cfg.CLAIM_IDLE_MS) {
                this.drops.delete(dropId);
            }
        }
        for (const [dropId, rec] of this.authFailures.entries()) {
            if (now > rec.lockedUntil && (now - rec.winStart) > 300000) {
                this.authFailures.delete(dropId);
            }
        }
    }

    close() {
        clearInterval(this.gcTimer);
    }
}

module.exports = { MailboxStore };
