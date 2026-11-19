// GhostKey web console (wire protocol v2).
//
// One identity per tab, kept in memory only. You paste your peer's public key (which carries their
// Drop-ID); messages are signed with your key, encrypted for the peer and posted to the peer's drop.
// Incoming messages are fetched with your secret read token, decrypted, signature-checked, and only
// then acknowledged so the relay can delete them.

import { createCore } from './ghostkey-core.mjs';
import { renderBubble } from './ghostkey-ui.mjs';

const MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024;   // 15 MB media attachment cap
const TTL_SECONDS = 3600;

function backoffMs(attempt) {
    const base = Math.min(4000 * Math.pow(2, Math.min(Math.max(attempt, 0), 10)), 60000);
    const factor = 0.8 + Math.random() * 0.4;
    return Math.min(Math.floor(base * factor), 60000);
}

export function startApp(env) {
    const doc = env.document;
    const win = env.window;
    const fetchFn = env.fetch;
    const WS = env.WebSocket;
    const core = createCore(env.openpgp, { crypto: env.crypto || win.crypto });
    const $ = (id) => doc.getElementById(id);

    const state = {
        identity: null,
        peer: null,
        messages: [],
        seen: new Map(),            // message id -> 'pending' | 'done' | 'failed' (capped at 5000)
        seenNonces: new Set(),      // anti-replay nonce deduplication set
        attachment: null,
        ws: null,
        gen: 0,
        confirmed: false,
        authFailed: false,
        attempt: 0,
        reconnectTimer: null,
        sendChain: Promise.resolve(),
        blobUrls: [],
        disposed: false
    };

    const listeners = new win.AbortController();
    const on = (id, type, handler) => $(id).addEventListener(type, handler, { signal: listeners.signal });

    const origin = win.location.origin;
    const httpOk = /^https?:/.test(origin);

    // ------------------------------------------------------------------ small UI helpers

    function log(text, level = 'info') {
        const box = $('logs-container');
        const line = doc.createElement('div');
        line.className = 'log ' + level;
        line.textContent = `[${new Date().toLocaleTimeString()}] ${text}`;
        box.appendChild(line);
        while (box.childElementCount > 200) box.removeChild(box.firstChild);
        box.scrollTop = box.scrollHeight;
    }

    function setStatus(status) {
        const dot = $('relay-dot');
        const text = $('relay-status-text');
        const hint = $('relay-hint');
        dot.className = 'dot ' + (status === 'CONNECTED' ? 'on' : status === 'CONNECTING' ? 'wait' : 'off');
        text.textContent = status === 'AUTH_FAILED' ? 'AUTH FAILED' : status;
        const hints = {
            CONNECTED: '',
            CONNECTING: 'Connecting to the relay...',
            OFFLINE: httpOk ? 'Not connected. Reconnecting automatically.' : 'Open this page from your relay (http:// or https://) to connect.',
            AUTH_FAILED: 'The relay rejected this mailbox\'s access token and will not retry. Click NEW IDENTITY.',
            WIPED: 'Keys and messages were dropped from this tab. Click NEW IDENTITY to start again.'
        };
        hint.textContent = hints[status] || '';
    }

    function showPeerError(message) {
        const box = $('peer-error');
        box.textContent = message || '';
        box.classList.toggle('hidden', !message);
    }

    function groupFingerprint(fp) {
        return (fp || '').match(/.{1,4}/g)?.join(' ') || '';
    }

    function revokeBlobUrls() {
        for (const url of state.blobUrls) {
            try { env.URL.revokeObjectURL(url); } catch (e) { /* ignore */ }
        }
        state.blobUrls = [];
    }

    const renderCtx = {
        decodeBase64: (b64) => core.base64ToBytes(b64),
        createObjectUrl: (bytes, mime) => {
            const url = env.URL.createObjectURL(new env.Blob([bytes], { type: mime }));
            state.blobUrls.push(url);
            return url;
        },
        onToggleArmor: (id) => {
            const msg = state.messages.find((m) => m.id === id);
            if (msg) {
                msg.showArmor = !msg.showArmor;
                renderMessages(false);
            }
        }
    };

    function renderMessages(forceScroll) {
        const container = $('messages-container');
        const atBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 80;
        revokeBlobUrls();
        container.textContent = '';
        if (state.messages.length === 0) {
            const empty = doc.createElement('div');
            empty.className = 'empty';
            empty.textContent = 'No messages yet. Save your peer\'s key, then send something.';
            container.appendChild(empty);
            return;
        }
        for (const msg of state.messages) container.appendChild(renderBubble(doc, msg, renderCtx));
        if (forceScroll || atBottom) container.scrollTop = container.scrollHeight;
    }

    function renderIdentity() {
        const id = state.identity;
        $('my-fingerprint').textContent = id ? groupFingerprint(id.fingerprint) : 'No keys in this tab.';
        $('my-dropid').textContent = id ? id.dropId : '-';
        $('my-pubkey').value = id ? id.publicKeyArmored : '';
    }

    function renderPeer() {
        const box = $('peer-summary');
        const p = state.peer;
        if (!p) {
            box.className = 'peer-summary none';
            box.textContent = 'No peer key saved yet.';
            $('chat-title').textContent = 'Encrypted chat';
            return;
        }
        if (p.dropId) {
            box.className = 'peer-summary ok';
            box.textContent = `Peer ${groupFingerprint(p.fingerprint).slice(0, 29)}... \u00b7 Drop-ID ${p.dropId.slice(0, 8)}...`;
        } else {
            box.className = 'peer-summary none';
            box.textContent = 'This key has no Drop-ID, so you cannot send to it. Ask your peer to copy their key again from GhostKey v2.';
        }
        $('chat-title').textContent = `Chat with ${p.fingerprint.slice(0, 8)}`;
    }

    // ------------------------------------------------------------------ relay API

    async function api(method, path, { body, token } = {}) {
        const headers = {};
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        if (token) headers['X-Mailbox-Token'] = token;
        const res = await fetchFn(origin + path, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body)
        });
        let json = null;
        try { json = await res.json(); } catch (e) { /* empty body */ }
        return { status: res.status, ok: res.ok, json };
    }

    function ackMessages(ids) {
        const id = state.identity;
        if (!id || ids.length === 0) return;
        const ws = state.ws;
        if (ws && state.confirmed && ws.readyState === 1) {
            try {
                ws.send(JSON.stringify({ type: 'ACK', dropId: id.dropId, messageIds: ids }));
                return;
            } catch (e) { /* fall back to HTTP */ }
        }
        api('POST', '/api/v2/ack', { body: { dropId: id.dropId, messageIds: ids }, token: id.readToken })
            .catch(() => { /* the relay redelivers; duplicates are ignored */ });
    }

    // ------------------------------------------------------------------ incoming

    async function handleIncoming(record) {
        const identity = state.identity;
        if (!identity || !record || typeof record.id !== 'string' || typeof record.ciphertext !== 'string') return;

        const prior = state.seen.get(record.id);
        if (prior === 'done') { ackMessages([record.id]); return; }     // an earlier ACK may have been lost
        if (prior === 'pending') return;
        if (state.seen.size > 5000) {
            const first = state.seen.keys().next().value;
            state.seen.delete(first);
        }
        state.seen.set(record.id, 'pending');

        try {
            const result = await core.decryptVerified(
                record.ciphertext,
                identity.privateKeyArmored,
                state.peer ? state.peer.publicKeyArmored : null
            );
            if (state.identity !== identity) return;                   // identity replaced or wiped meanwhile
            const payload = core.parseWire(result.bytes);

            // Replay attack defense (loophole P2): verify timestamp and deduplicate nonce
            if (payload && payload.nonce) {
                if (state.seenNonces.has(payload.nonce)) {
                    log(`[REPLAY WARNING] Duplicate nonce detected: ${payload.nonce.slice(0, 8)}... Ignored.`, 'warn');
                    ackMessages([record.id]);
                    return;
                }
                if (payload.ts && Math.abs(Date.now() - payload.ts) > 15 * 60 * 1000) {
                    log(`[REPLAY WARNING] Message timestamp skewed by > 15 min (${new Date(payload.ts).toLocaleTimeString()}).`, 'warn');
                }
                state.seenNonces.add(payload.nonce);
                if (state.seenNonces.size > 5000) {
                    const oldest = state.seenNonces.values().next().value;
                    state.seenNonces.delete(oldest);
                }
            }

            upsertMessage({
                id: record.id,
                isOutgoing: false,
                timestamp: record.createdAt || Date.now(),
                payload,
                fallbackText: payload ? '' : new TextDecoder().decode(result.bytes),
                signatureStatus: result.signatureStatus,
                signerFingerprint: result.signerFingerprint,
                ciphertext: record.ciphertext,
                showArmor: false,
                failed: false
            });
            state.seen.set(record.id, 'done');
            log(`[RECV] Message decrypted (${result.signatureStatus}).`, result.signatureStatus === 'VERIFIED' ? 'success' : 'warn');
            renderMessages(false);
            ackMessages([record.id]);                                  // only after it is stored and shown
        } catch (e) {
            if (state.identity !== identity) return;
            state.seen.set(record.id, 'failed');
            log(`[RECV] Could not decrypt message: ${e.message}`, 'error');
            upsertMessage({
                id: record.id, isOutgoing: false, timestamp: record.createdAt || Date.now(),
                payload: null, failed: true, ciphertext: record.ciphertext, showArmor: false
            });
            renderMessages(false);
        }
    }

    function upsertMessage(msg) {
        const i = state.messages.findIndex((m) => m.id === msg.id);
        if (i >= 0) state.messages[i] = msg; else state.messages.push(msg);
    }

    // ------------------------------------------------------------------ connection

    function closeSocketOnly() {
        state.gen++;                 // late events of the old socket are ignored
        state.confirmed = false;
        const ws = state.ws;
        state.ws = null;
        if (ws) { try { ws.close(1000); } catch (e) { /* already closed */ } }
    }

    function scheduleReconnect() {
        if (state.disposed || state.authFailed || !state.identity) return;
        if (state.reconnectTimer) win.clearTimeout(state.reconnectTimer);
        const wait = backoffMs(state.attempt);
        state.attempt++;
        state.reconnectTimer = win.setTimeout(() => { state.reconnectTimer = null; connect(); }, wait);
    }

    function handleAuthFailed() {
        state.authFailed = true;
        if (state.reconnectTimer) { win.clearTimeout(state.reconnectTimer); state.reconnectTimer = null; }
        closeSocketOnly();
        setStatus('AUTH_FAILED');
        log('[RELAY] Mailbox access token rejected.', 'error');
    }

    async function pollPending(gen) {
        const id = state.identity;
        if (!id) return;
        try {
            const r = await api('GET', `/api/v2/inbox/${id.dropId}`, { token: id.readToken });
            if (state.identity !== id) return;
            if (r.status === 401) { handleAuthFailed(); return; }
            if (r.ok && r.json && Array.isArray(r.json.messages)) {
                for (const m of r.json.messages) await handleIncoming(m);
            }
        } catch (e) {
            if (gen === state.gen) log(`[RELAY] Inbox check failed: ${e.message}`, 'warn');
        }
    }

    function connect() {
        closeSocketOnly();
        const id = state.identity;
        if (!id || state.disposed) return;
        if (!httpOk) { setStatus('OFFLINE'); return; }

        setStatus('CONNECTING');
        const gen = ++state.gen;
        let ws;
        try {
            ws = new WS(origin.replace(/^http/, 'ws') + '/api/v2/live');
        } catch (e) {
            setStatus('OFFLINE');
            scheduleReconnect();
            return;
        }
        state.ws = ws;

        ws.onopen = () => {
            if (gen !== state.gen) return;
            state.attempt = 0;
            ws.send(JSON.stringify({ type: 'SUBSCRIBE', dropId: id.dropId, readToken: id.readToken }));
            pollPending(gen);        // also pick up anything queued while we were away
        };

        ws.onmessage = (evt) => {
            if (gen !== state.gen || typeof evt.data !== 'string') return;
            let data;
            try { data = JSON.parse(evt.data); } catch (e) { return; }
            if (data.type === 'SUBSCRIBED') {
                state.confirmed = true;
                setStatus('CONNECTED');
                log('[RELAY] Subscribed to your drop.', 'success');
                for (const [mid, st] of state.seen) if (st === 'failed') state.seen.delete(mid);   // retry undecryptable ones
            } else if (data.type === 'AUTH_FAILED') {
                handleAuthFailed();
            } else if (data.type === 'MESSAGE' && data.message) {
                handleIncoming(data.message);
            }
        };

        const lost = () => {
            if (gen !== state.gen) return;
            state.gen++;
            state.confirmed = false;
            setStatus('OFFLINE');
            scheduleReconnect();
        };
        ws.onclose = lost;
        ws.onerror = lost;
    }

    // ------------------------------------------------------------------ sending

    function friendlySendError(status, json) {
        if (status === 429) return 'rate limit reached or your peer\'s mailbox is full, try again shortly';
        if (status === 503) return 'the relay is at capacity';
        if (status === 413) return 'the message is too large for the relay';
        return (json && json.error) || `relay returned HTTP ${status}`;
    }

    async function sendPayload(payloadBytes) {
        const identity = state.identity;
        const peer = state.peer;
        const ciphertext = await core.encryptSigned(payloadBytes, peer.publicKeyArmored, identity.privateKeyArmored);
        const r = await api('POST', '/api/v2/send', {
            body: { dropId: peer.dropId, ciphertext, ttlSeconds: TTL_SECONDS }
        });
        if (!r.ok) throw new Error(friendlySendError(r.status, r.json));
        state.messages.push({
            id: (r.json && r.json.messageId) || `local-${Date.now()}-${Math.random()}`,
            isOutgoing: true,
            timestamp: Date.now(),
            payload: core.parseWire(payloadBytes),
            ciphertext,
            showArmor: false
        });
        renderMessages(true);
    }

    function queueSend(task) {
        state.sendChain = state.sendChain.then(task).catch((e) => {
            log(`Send failed: ${e.message}`, 'error');
        });
        return state.sendChain;
    }

    async function handleSend(event) {
        event.preventDefault();
        const input = $('message-input');
        const text = input.value.trim();
        const attachment = state.attachment;
        if (!text && !attachment) return;

        if (!state.identity) { log('No keys in this tab. Click NEW IDENTITY first.', 'error'); return; }
        if (!state.peer || !state.peer.dropId) {
            showPeerError('Save your peer\'s public key first. It must include a Drop-ID.');
            return;
        }
        showPeerError('');
        input.value = '';
        clearAttachment();

        if (attachment) {
            await queueSend(() => {
                log(`[SEND] Encrypting ${attachment.filename}...`, 'info');
                return sendPayload(core.buildMedia(attachment.mimeType, attachment.filename, attachment.bytes));
            });
        }
        if (text) {
            await queueSend(() => sendPayload(core.buildText(text)));
        }
    }

    // ------------------------------------------------------------------ attachments

    function clearAttachment() {
        state.attachment = null;
        $('attachment-bar').classList.add('hidden');
        $('media-file-input').value = '';
    }

    async function handleFileSelected(event) {
        const file = event.target.files && event.target.files[0];
        if (!file) return;
        if (file.size > MAX_ATTACHMENT_BYTES) {
            log('Attachment is larger than 15 MB and was not added.', 'error');
            showPeerError('Attachment is larger than 15 MB.');
            event.target.value = '';
            return;
        }
        const bytes = new Uint8Array(await file.arrayBuffer());
        state.attachment = { filename: file.name, mimeType: file.type || 'application/octet-stream', bytes };
        $('attachment-name').textContent = file.name;
        $('attachment-size').textContent = `(${(file.size / 1024).toFixed(1)} KB)`;
        $('attachment-bar').classList.remove('hidden');
        showPeerError('');
    }

    // ------------------------------------------------------------------ identity / peer

    async function newIdentity() {
        log('Generating an Ed25519 + X25519 identity...', 'info');
        try {
            const identity = await core.generateIdentity();
            if (state.disposed) return;
            state.identity = identity;
            state.authFailed = false;
            state.attempt = 0;
            state.seen.clear();
            renderIdentity();
            log('Identity ready. Share your public key with your peer.', 'success');
            connect();
        } catch (e) {
            log(`Key generation failed: ${e.message}`, 'error');
        }
    }

    async function savePeerKey() {
        showPeerError('');
        try {
            const info = await core.parsePeerKey($('peer-key-input').value);
            state.peer = info;
            renderPeer();
            $('peer-key-input').value = '';
            if (!info.dropId) {
                showPeerError('This key has no Drop-ID, so you cannot send to it.');
                log('Peer key saved without a Drop-ID.', 'warn');
            } else {
                log(`Peer key saved (${info.fingerprint.slice(0, 8)}...). Compare the safety code with your peer.`, 'success');
            }
        } catch (e) {
            showPeerError(`Could not use this key: ${e.message}`);
        }
    }

    async function openSafetyModal() {
        const digits = $('safety-code-digits');
        if (!state.identity || !state.peer) {
            digits.textContent = 'Save your peer\'s key first.';
        } else {
            digits.textContent = await core.safetyCode(state.identity.fingerprint, state.peer.fingerprint);
        }
        $('safety-modal').classList.remove('hidden');
    }

    function closeSafetyModal() {
        $('safety-modal').classList.add('hidden');
    }

    async function copyKey() {
        if (!state.identity) return;
        const label = $('copy-key-btn');
        try {
            await win.navigator.clipboard.writeText(state.identity.publicKeyArmored);
        } catch (e) {
            const box = $('my-pubkey');
            box.select();
            try { doc.execCommand('copy'); } catch (err) { /* user can copy manually */ }
        }
        label.textContent = 'COPIED';
        win.setTimeout(() => { label.textContent = 'COPY KEY'; }, 1800);
    }

    function wipeSession() {
        if (state.reconnectTimer) { win.clearTimeout(state.reconnectTimer); state.reconnectTimer = null; }
        closeSocketOnly();
        state.identity = null;
        state.peer = null;
        state.messages = [];
        state.seen.clear();
        state.seenNonces.clear();
        state.attachment = null;
        state.authFailed = false;
        revokeBlobUrls();
        $('peer-key-input').value = '';
        $('logs-container').textContent = '';
        clearAttachment();
        showPeerError('');
        renderIdentity();
        renderPeer();
        renderMessages(true);
        setStatus('WIPED');
        log('Keys, messages and connections were dropped from this tab. The browser may keep copies in memory until it reclaims them.', 'warn');
    }

    function onNewIdentityClick() {
        const confirmFn = env.confirm || win.confirm.bind(win);
        if (state.identity && !confirmFn('Create a new identity? Your peer will need your new public key.')) return;
        state.messages = [];
        renderMessages(true);
        newIdentity();
    }

    // ------------------------------------------------------------------ wiring

    on('chat-form', 'submit', handleSend);
    on('attach-btn', 'click', () => $('media-file-input').click());
    on('media-file-input', 'change', handleFileSelected);
    on('attachment-clear', 'click', clearAttachment);
    on('save-peer-btn', 'click', savePeerKey);
    on('copy-key-btn', 'click', copyKey);
    on('new-identity-btn', 'click', onNewIdentityClick);
    on('panic-btn', 'click', wipeSession);
    on('safety-btn', 'click', openSafetyModal);
    on('safety-close', 'click', closeSafetyModal);
    on('safety-close-x', 'click', closeSafetyModal);
    on('clear-logs-btn', 'click', () => { $('logs-container').textContent = ''; });

    renderMessages(true);
    renderPeer();
    setStatus('OFFLINE');
    const ready = newIdentity();

    return {
        ready,
        getStateForTests: () => state,
        dispose() {
            state.disposed = true;
            listeners.abort();
            if (state.reconnectTimer) win.clearTimeout(state.reconnectTimer);
            closeSocketOnly();
            revokeBlobUrls();
        }
    };
}

// Browser bootstrap. Skipped in Node (tests) and when a host sets window.__GHOSTKEY_NO_AUTOSTART__
// (the React client starts the app itself).
if (typeof window !== 'undefined' && !window.__GHOSTKEY_NO_AUTOSTART__ && typeof window.openpgp !== 'undefined') {
    startApp({
        document: window.document,
        window,
        openpgp: window.openpgp,
        fetch: window.fetch.bind(window),
        WebSocket: window.WebSocket,
        URL: window.URL,
        Blob: window.Blob,
        crypto: window.crypto
    });
} else if (typeof window !== 'undefined' && !window.__GHOSTKEY_NO_AUTOSTART__) {
    const hint = document.getElementById('relay-hint');
    if (hint) hint.textContent = 'openpgp.min.js could not be loaded, so this page cannot work. Reload it from your relay.';
}
