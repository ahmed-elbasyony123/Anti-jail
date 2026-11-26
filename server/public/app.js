// GhostKey Web Console v3.0 (wire protocol v2, RAM-only).
// Supports multi-contact OpenPGP DMs, AES-256-GCM groups, reply-to quotes, disappearing TTL,
// profile avatars, contact introductions, key-change warnings, .gkbackup export/import,
// bilingual EN/AR (RTL/LTR), and Light/Dark themes.

import { createCore } from './ghostkey-core.mjs';
import { renderBubble, isInlineImageType, isValidBase64 } from './ghostkey-ui.mjs';
import { getStrings } from './ghostkey-i18n.mjs';

const MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024;
const MAX_AVATAR_BYTES = 25 * 1024;
const DEFAULT_TTL_SECONDS = 3600;

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
        userAlias: '',
        myAvatarBase64: null,
        myAvatarHash: null,
        peer: null,                 // backward-compat pointer to active direct contact (or first contact)
        contacts: [],               // [{ dropId, alias, publicKeyArmored, fingerprint, addedAt, keyChangedWarning, avatarBase64, avatarHash }]
        contactRequests: [],        // [{ dropId, alias, fingerprint, publicKeyArmored, avatarBase64, avatarHash, receivedAt }]
        groups: [],                 // [{ id, name, groupDropId, groupReadToken, sharedKeyBase64, members, createdAt, isAdmin }]
        activeChat: null,           // { type: 'direct' | 'group', id: string } | null
        messagesByChat: new Map(),  // chatId -> msg[]
        unreadByChat: new Map(),    // chatId -> number
        seen: new Map(),            // relay record id -> 'pending' | 'done' | 'failed'
        seenNonces: new Set(),
        attachment: null,
        replyToMsg: null,
        pendingPairInvite: null,
        rvExpiresAt: 0,
        rvCountdownTimer: null,
        ttlSeconds: 0,
        theme: 'light',
        lang: 'en',
        logsCollapsed: false,
        privacyShield: true,
        backupMode: 'export',
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
    const on = (id, type, handler) => {
        const node = $(id);
        if (node) node.addEventListener(type, handler, { signal: listeners.signal });
    };

    const origin = win.location.origin;
    const httpOk = /^https?:/.test(origin);

    // ------------------------------------------------------------------ theme / i18n / helpers

    function log(text, level = 'info') {
        const box = $('logs-container');
        if (!box) return;
        const line = doc.createElement('div');
        line.className = 'log ' + level;
        line.textContent = `[${new Date().toLocaleTimeString()}] ${text}`;
        box.appendChild(line);
        while (box.childElementCount > 200) box.removeChild(box.firstChild);
        box.scrollTop = box.scrollHeight;
    }

    function applyThemeAndLang() {
        const s = getStrings(state.lang);
        const root = doc.documentElement;
        if (root) {
            root.setAttribute('data-theme', state.theme);
            root.setAttribute('lang', state.lang);
            root.setAttribute('dir', s.dir);
        }
        if ($('theme-btn')) $('theme-btn').textContent = state.theme === 'dark' ? s.themeLight : s.themeDark;
        if ($('lang-btn')) $('lang-btn').textContent = state.lang === 'ar' ? 'EN' : 'AR';
        if ($('panic-btn')) $('panic-btn').textContent = s.wipe;
        if ($('chats-section-title')) $('chats-section-title').textContent = s.chatsTitle;
        if ($('new-group-btn')) $('new-group-btn').textContent = s.newGroupBtn;
        if ($('save-peer-btn')) $('save-peer-btn').textContent = s.savePeerBtn;
        if ($('quick-pair-btn')) $('quick-pair-btn').textContent = s.quickPairBtn;
        if ($('peer-key-input')) $('peer-key-input').placeholder = s.addPeerPlaceholder;
        if ($('pair-modal-title')) $('pair-modal-title').textContent = s.pairModalTitle;
        if ($('pair-tab-create')) $('pair-tab-create').textContent = s.pairTabCreate;
        if ($('pair-tab-redeem')) $('pair-tab-redeem').textContent = s.pairTabRedeem;
        if ($('pair-create-hint')) $('pair-create-hint').textContent = s.pairCreateHint;
        if ($('rv-generate-btn')) $('rv-generate-btn').textContent = s.pairGenerateBtn;
        if ($('pair-redeem-hint')) $('pair-redeem-hint').textContent = s.pairRedeemHint;
        if ($('rv-redeem-btn')) $('rv-redeem-btn').textContent = s.pairRedeemBtn;
        if ($('rv-input')) $('rv-input').placeholder = s.pairInputPlaceholder;
        renderContactRequests();
        if ($('identity-section-title')) $('identity-section-title').textContent = s.myIdentity;
        if ($('my-alias-input')) $('my-alias-input').placeholder = s.aliasPlaceholder;
        if ($('copy-key-btn')) $('copy-key-btn').textContent = s.copyKey;
        if ($('copy-card-btn')) $('copy-card-btn').textContent = s.copyCard;
        if ($('new-identity-btn')) $('new-identity-btn').textContent = s.newIdentity;
        if ($('export-backup-btn')) $('export-backup-btn').textContent = s.exportBackup;
        if ($('import-backup-btn')) $('import-backup-btn').textContent = s.importBackup;
        if ($('message-input')) $('message-input').placeholder = s.msgPlaceholder;
        if ($('send-btn')) $('send-btn').textContent = s.sendBtn;
        if ($('ptt-btn')) $('ptt-btn').textContent = s.recBtn;
        if ($('safety-btn')) $('safety-btn').textContent = s.safetyCodeBtn;
        if ($('introduce-btn')) $('introduce-btn').textContent = s.introduceBtn;
        if ($('group-manage-btn')) $('group-manage-btn').textContent = s.groupInfoBtn;
        if ($('clear-both-btn')) $('clear-both-btn').textContent = s.clearBothBtn;
        if ($('logs-title')) $('logs-title').textContent = s.activityLog;
        if ($('broadcast-profile-btn')) $('broadcast-profile-btn').textContent = s.broadcastProfileBtn;
        if ($('privacy-btn')) {
            $('privacy-btn').textContent = state.privacyShield ? s.shieldOn : s.shieldOff;
            $('privacy-btn').classList.toggle('active', state.privacyShield);
        }
        if ($('privacy-veil-title')) $('privacy-veil-title').textContent = s.shieldTitle;
        if ($('privacy-veil-desc')) $('privacy-veil-desc').textContent = s.shieldDesc;
        doc.body?.classList?.toggle('shield-active', state.privacyShield);
    }

    function setStatus(status) {
        const dot = $('relay-dot');
        const text = $('relay-status-text');
        const hint = $('relay-hint');
        const brand = $('brand-title');
        const dotClass = status === 'CONNECTED' ? 'on' : status === 'CONNECTING' ? 'wait' : 'off';
        if (dot) dot.className = 'dot ' + dotClass;
        if (brand) brand.className = 'brand ' + (status === 'CONNECTED' ? 'connected' : status === 'CONNECTING' ? 'connecting' : 'offline');
        if (text) text.textContent = status === 'AUTH_FAILED' ? 'AUTH FAILED' : status;
        const hints = {
            CONNECTED: '',
            CONNECTING: 'Connecting to the relay...',
            OFFLINE: httpOk ? 'Not connected. Reconnecting automatically.' : 'Open this page from your relay (http:// or https://) to connect.',
            AUTH_FAILED: 'The relay rejected this mailbox\'s access token and will not retry. Click New Identity.',
            WIPED: 'Keys, contacts, groups and messages were dropped from RAM.'
        };
        if (hint) hint.textContent = hints[status] || '';
    }

    function showPeerError(message) {
        const box = $('peer-error');
        if (!box) return;
        box.textContent = message || '';
        box.classList.toggle('hidden', !message);
    }

    function groupFingerprint(fp) {
        return (fp || '').match(/.{1,4}/g)?.join(' ') || '';
    }

    function renderAvatarNode(node, label, avatarBase64) {
        if (!node) return;
        node.textContent = '';
        if (avatarBase64 && isValidBase64(avatarBase64)) {
            const img = doc.createElement('img');
            img.alt = label || 'Avatar';
            img.src = `data:image/jpeg;base64,${avatarBase64}`;
            node.appendChild(img);
        } else {
            const initials = String(label || '?').trim().slice(0, 2).toUpperCase() || '?';
            node.textContent = initials;
        }
    }

    function revokeBlobUrls() {
        for (const url of state.blobUrls) {
            try { env.URL.revokeObjectURL(url); } catch (_) {}
        }
        state.blobUrls = [];
    }

    function activeChatId() {
        if (state.activeChat) return state.activeChat.id;
        return state.contacts[0]?.dropId || 'inbox';
    }

    function getActiveContact() {
        if (state.activeChat?.type === 'direct') {
            return state.contacts.find((c) => c.dropId === state.activeChat.id) || null;
        }
        return state.peer;
    }

    function getActiveGroup() {
        if (state.activeChat?.type === 'group') {
            return state.groups.find((g) => g.id === state.activeChat.id) || null;
        }
        return null;
    }

    function activeMessagesList(chatId = activeChatId()) {
        if (!state.messagesByChat.has(chatId)) state.messagesByChat.set(chatId, []);
        return state.messagesByChat.get(chatId);
    }

    function upsertMessage(chatId, msg) {
        const list = activeMessagesList(chatId);
        const idx = list.findIndex((m) => m.id === msg.id);
        if (idx >= 0) list[idx] = msg;
        else list.push(msg);
    }

    function findMessageByWireId(wireId) {
        if (!wireId) return null;
        for (const list of state.messagesByChat.values()) {
            const found = list.find((m) => m.payload?.msgId === wireId || m.id === wireId);
            if (found) return found;
        }
        return null;
    }

    // ------------------------------------------------------------------ rendering

    const renderCtx = {
        decodeBase64: (b64) => core.base64ToBytes(b64),
        createObjectUrl: (bytes, mime) => {
            const url = env.URL.createObjectURL(new env.Blob([bytes], { type: mime }));
            state.blobUrls.push(url);
            return url;
        },
        onToggleArmor: (id) => {
            const list = activeMessagesList();
            const msg = list.find((m) => m.id === id);
            if (msg) {
                msg.showArmor = !msg.showArmor;
                renderMessages(false);
            }
        },
        onReply: (msg) => {
            state.replyToMsg = msg;
            renderReplyBar();
            $('message-input')?.focus();
        },
        findMessageByWireId,
        hasGroup: (groupDropId) => state.groups.some((g) => g.groupDropId === groupDropId),
        onJoinGroupInvite: (payload) => joinGroupFromInvite(payload),
        onAcceptIntroduction: (payload) => acceptContactIntroduction(payload)
    };

    function renderReplyBar() {
        const bar = $('reply-bar');
        if (!bar) return;
        const m = state.replyToMsg;
        if (!m) {
            bar.classList.add('hidden');
            return;
        }
        const s = getStrings(state.lang);
        const author = m.isOutgoing ? 'You' : (m.payload?.senderAlias || 'Peer');
        $('reply-preview-author').textContent = `${s.replyingTo} ${author}`;
        $('reply-preview-text').textContent = m.payload?.text || m.payload?.filename || `[${m.payload?.type || 'message'}]`;
        bar.classList.remove('hidden');
    }

    function renderMessages(forceScroll) {
        const container = $('messages-container');
        if (!container) return;
        const atBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 80;
        revokeBlobUrls();
        container.textContent = '';

        const list = activeMessagesList();
        if (list.length === 0) {
            const empty = doc.createElement('div');
            empty.className = 'empty';
            empty.textContent = getStrings(state.lang).emptyStream;
            container.appendChild(empty);
            return;
        }
        for (const msg of list) container.appendChild(renderBubble(doc, msg, renderCtx));
        if (forceScroll || atBottom) container.scrollTop = container.scrollHeight;
    }

    function renderIdentity() {
        const id = state.identity;
        $('my-fingerprint').textContent = id ? groupFingerprint(id.fingerprint) : 'No keys in this tab.';
        $('my-dropid').textContent = id ? id.dropId : '-';
        $('my-pubkey').value = id ? id.publicKeyArmored : '';
        if ($('my-alias-input')) $('my-alias-input').value = state.userAlias || '';
        renderAvatarNode($('my-avatar-preview'), state.userAlias || 'GK', state.myAvatarBase64);
    }

    function acceptContactRequest(dropId) {
        const idx = state.contactRequests.findIndex((r) => r.dropId === dropId);
        if (idx < 0) return;
        const [req] = state.contactRequests.splice(idx, 1);
        const keyChanged = registerContact({
            dropId: req.dropId,
            alias: req.alias,
            publicKeyArmored: req.publicKeyArmored,
            fingerprint: req.fingerprint
        });
        const saved = state.contacts.find((c) => c.dropId === req.dropId);
        if (saved && req.avatarBase64 && isValidBase64(req.avatarBase64)) {
            saved.avatarBase64 = req.avatarBase64;
            saved.avatarHash = req.avatarHash || null;
        }
        renderContactRequests();
        selectChat('direct', req.dropId);
        if (state.identity && (state.userAlias || state.myAvatarBase64)) {
            const profileBytes = core.buildProfileUpdate({
                profileAlias: state.userAlias || undefined,
                avatarBase64: state.myAvatarBase64 || undefined,
                avatarHash: state.myAvatarHash || undefined,
                senderAlias: state.userAlias || undefined
            });
            queueSend(() => encryptAndPostDirect(saved, profileBytes)).catch(() => {});
        }
        log(`[PAIR] Accepted contact request from ${req.alias}${keyChanged ? ' (key changed!)' : ''}.`, keyChanged ? 'warn' : 'success');
    }

    function declineContactRequest(dropId) {
        const idx = state.contactRequests.findIndex((r) => r.dropId === dropId);
        if (idx < 0) return;
        const [removed] = state.contactRequests.splice(idx, 1);
        renderContactRequests();
        log(`[PAIR] Declined contact request from ${removed.alias}.`, 'warn');
    }

    function renderContactRequests() {
        const box = $('contact-requests-banner');
        if (!box) return;
        box.textContent = '';
        if (!state.contactRequests || state.contactRequests.length === 0) {
            box.classList.add('hidden');
            return;
        }
        const s = getStrings(state.lang);
        box.classList.remove('hidden');
        for (const req of state.contactRequests) {
            const card = doc.createElement('div');
            card.className = 'request-card';

            const meta = doc.createElement('div');
            meta.className = 'request-meta';
            const name = doc.createElement('span');
            name.className = 'request-name';
            name.textContent = `${s.requestsHeader}: ${req.alias}`;
            const fp = doc.createElement('span');
            fp.className = 'request-fp';
            fp.textContent = groupFingerprint(req.fingerprint.slice(0, 12));
            meta.appendChild(name);
            meta.appendChild(fp);

            const row = doc.createElement('div');
            row.className = 'row compact';
            const acceptBtn = doc.createElement('button');
            acceptBtn.type = 'button';
            acceptBtn.className = 'btn small flex1 req-accept-btn';
            acceptBtn.textContent = s.acceptBtn;
            acceptBtn.addEventListener('click', () => acceptContactRequest(req.dropId));

            const declineBtn = doc.createElement('button');
            declineBtn.type = 'button';
            declineBtn.className = 'btn small ghost flex1 req-decline-btn';
            declineBtn.textContent = s.declineBtn;
            declineBtn.addEventListener('click', () => declineContactRequest(req.dropId));

            row.appendChild(acceptBtn);
            row.appendChild(declineBtn);
            card.appendChild(meta);
            card.appendChild(row);
            box.appendChild(card);
        }
    }

    function renderChatList() {
        const listEl = $('chat-list');
        if (!listEl) return;
        listEl.textContent = '';
        const activeId = activeChatId();

        for (const c of state.contacts) {
            const item = doc.createElement('button');
            item.type = 'button';
            item.className = 'chat-item' + (state.activeChat?.type === 'direct' && activeId === c.dropId ? ' active' : '');
            const av = doc.createElement('div');
            av.className = 'avatar';
            renderAvatarNode(av, c.alias, c.avatarBase64);
            item.appendChild(av);

            const body = doc.createElement('div');
            body.className = 'chat-item-body';
            const title = doc.createElement('div');
            title.className = 'chat-item-title';
            title.textContent = c.keyChangedWarning ? `! ${c.alias}` : c.alias;
            const sub = doc.createElement('div');
            sub.className = 'chat-item-sub';
            const msgs = activeMessagesList(c.dropId);
            const last = msgs[msgs.length - 1];
            sub.textContent = last ? (last.payload?.text || last.payload?.filename || 'Encrypted message') : `${c.fingerprint.slice(0, 8)}...`;
            body.appendChild(title);
            body.appendChild(sub);
            item.appendChild(body);

            const unread = state.unreadByChat.get(c.dropId) || 0;
            if (unread > 0) {
                const badge = doc.createElement('span');
                badge.className = 'unread-badge';
                badge.textContent = String(unread);
                item.appendChild(badge);
            }
            item.addEventListener('click', () => selectChat('direct', c.dropId));
            listEl.appendChild(item);
        }

        for (const g of state.groups) {
            const item = doc.createElement('button');
            item.type = 'button';
            item.className = 'chat-item' + (state.activeChat?.type === 'group' && activeId === g.id ? ' active' : '');
            const av = doc.createElement('div');
            av.className = 'avatar';
            renderAvatarNode(av, g.name, null);
            item.appendChild(av);

            const body = doc.createElement('div');
            body.className = 'chat-item-body';
            const title = doc.createElement('div');
            title.className = 'chat-item-title';
            title.textContent = `# ${g.name}`;
            const sub = doc.createElement('div');
            sub.className = 'chat-item-sub';
            const msgs = activeMessagesList(g.id);
            const last = msgs[msgs.length - 1];
            sub.textContent = last ? (last.payload?.text || last.payload?.filename || 'Group message') : `${g.members.length} members · AES-GCM`;
            body.appendChild(title);
            body.appendChild(sub);
            item.appendChild(body);

            const unread = state.unreadByChat.get(g.id) || 0;
            if (unread > 0) {
                const badge = doc.createElement('span');
                badge.className = 'unread-badge';
                badge.textContent = String(unread);
                item.appendChild(badge);
            }
            item.addEventListener('click', () => selectChat('group', g.id));
            listEl.appendChild(item);
        }
    }

    function renderPeerAndHeader() {
        const s = getStrings(state.lang);
        const box = $('peer-summary');
        const activeContact = getActiveContact();
        const activeGroup = getActiveGroup();

        state.peer = activeContact || state.contacts[0] || null;

        if (!state.peer && !activeGroup) {
            if (box) {
                box.className = 'peer-summary none';
                box.textContent = s.noPeerYet;
            }
            $('chat-title').textContent = s.encryptedChat;
            if ($('chat-subtitle')) $('chat-subtitle').textContent = s.chatSubtitleDirect;
            renderAvatarNode($('chat-avatar'), '?', null);
            $('key-warning-banner')?.classList.add('hidden');
            $('introduce-btn')?.classList.add('hidden');
            $('group-manage-btn')?.classList.add('hidden');
            $('clear-both-btn')?.classList.add('hidden');
            $('safety-btn')?.classList.remove('hidden');
        } else if (activeGroup) {
            if (box) {
                box.className = 'peer-summary ok';
                box.textContent = `Group ${activeGroup.name} · ${activeGroup.groupDropId.slice(0, 8)}...`;
            }
            $('chat-title').textContent = activeGroup.name;
            if ($('chat-subtitle')) $('chat-subtitle').textContent = `${s.chatSubtitleGroup} · ${activeGroup.members.length} members`;
            renderAvatarNode($('chat-avatar'), activeGroup.name, null);
            $('key-warning-banner')?.classList.add('hidden');
            $('introduce-btn')?.classList.add('hidden');
            $('group-manage-btn')?.classList.remove('hidden');
            $('clear-both-btn')?.classList.add('hidden');
            $('safety-btn')?.classList.add('hidden');
        } else if (activeContact) {
            if (box) {
                box.className = activeContact.dropId ? 'peer-summary ok' : 'peer-summary none';
                box.textContent = activeContact.dropId
                    ? `${activeContact.alias} (${groupFingerprint(activeContact.fingerprint).slice(0, 19)}...) · Drop-ID ${activeContact.dropId.slice(0, 8)}...`
                    : 'This key has no Drop-ID, so you cannot send to it.';
            }
            $('chat-title').textContent = activeContact.alias || `Chat ${activeContact.fingerprint.slice(0, 8)}`;
            if ($('chat-subtitle')) $('chat-subtitle').textContent = s.chatSubtitleDirect;
            renderAvatarNode($('chat-avatar'), activeContact.alias, activeContact.avatarBase64);
            $('key-warning-banner')?.classList.toggle('hidden', !activeContact.keyChangedWarning);
            $('introduce-btn')?.classList.toggle('hidden', state.contacts.length < 2);
            $('group-manage-btn')?.classList.add('hidden');
            $('clear-both-btn')?.classList.remove('hidden');
            $('safety-btn')?.classList.remove('hidden');
        }
        renderChatList();
    }

    function selectChat(type, id) {
        state.activeChat = { type, id };
        state.unreadByChat.set(id, 0);
        state.replyToMsg = null;
        renderReplyBar();
        renderPeerAndHeader();
        renderMessages(true);
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
        try { json = await res.json(); } catch (_) {}
        return { status: res.status, ok: res.ok, json };
    }

    function tokenForDropId(dropId) {
        if (state.identity && state.identity.dropId === dropId) return state.identity.readToken;
        const g = state.groups.find((x) => x.groupDropId === dropId);
        return g ? g.groupReadToken : null;
    }

    function ackMessages(ids, dropId = state.identity?.dropId) {
        if (!dropId || !ids || ids.length === 0) return;
        const token = tokenForDropId(dropId);
        if (!token) return;
        const ws = state.ws;
        if (ws && state.confirmed && ws.readyState === 1) {
            try {
                ws.send(JSON.stringify({ type: 'ACK', dropId, messageIds: ids }));
                return;
            } catch (_) {}
        }
        api('POST', '/api/v2/ack', { body: { dropId, messageIds: ids }, token }).catch(() => {});
    }

    // ------------------------------------------------------------------ incoming

    function registerContact(parsed, extra = {}) {
        const existingIdx = state.contacts.findIndex((c) => c.dropId === parsed.dropId);
        let keyChanged = false;
        if (existingIdx >= 0) {
            const old = state.contacts[existingIdx];
            keyChanged = old.fingerprint !== parsed.fingerprint;
            state.contacts[existingIdx] = {
                ...old,
                alias: extra.keepOldAlias ? old.alias : (parsed.alias || old.alias),
                publicKeyArmored: parsed.publicKeyArmored,
                fingerprint: parsed.fingerprint,
                keyChangedWarning: old.keyChangedWarning || keyChanged
            };
        } else {
            state.contacts.push({
                dropId: parsed.dropId,
                alias: parsed.alias || `Peer ${parsed.fingerprint.slice(0, 6)}`,
                publicKeyArmored: parsed.publicKeyArmored,
                fingerprint: parsed.fingerprint,
                addedAt: Date.now(),
                keyChangedWarning: false,
                avatarBase64: null,
                avatarHash: null
            });
        }
        return keyChanged;
    }

    async function handleIncoming(record, sourceDropId) {
        const identity = state.identity;
        if (!identity || !record || typeof record.id !== 'string' || typeof record.ciphertext !== 'string') return;
        const mailboxDropId = sourceDropId || identity.dropId;

        const prior = state.seen.get(record.id);
        if (prior === 'done') { ackMessages([record.id], mailboxDropId); return; }
        if (prior === 'pending') return;
        if (state.seen.size > 5000) {
            const first = state.seen.keys().next().value;
            state.seen.delete(first);
        }
        state.seen.set(record.id, 'pending');

        try {
            const group = state.groups.find((g) => g.groupDropId === mailboxDropId);
            let plainBytes;
            let signatureStatus = null;
            let signerFingerprint = null;

            if (group) {
                plainBytes = await core.decryptGroup(record.ciphertext, group.sharedKeyBase64);
            } else {
                const candidateKeys = state.contacts.map((c) => c.publicKeyArmored).filter(Boolean);
                const result = await core.decryptVerified(record.ciphertext, identity.privateKeyArmored, candidateKeys);
                if (state.identity !== identity) return;
                plainBytes = result.bytes;
                signatureStatus = result.signatureStatus;
                signerFingerprint = result.signerFingerprint;
            }

            const payload = await core.parseWireAsync(plainBytes);
            if (state.identity !== identity) return;

            if (!group && payload && payload.type === 'contact_request') {
                let verifiedPeer = null;
                try {
                    const embedded = await core.parsePeerKey(payload.key);
                    if (embedded.dropId === payload.dropId && embedded.fingerprint === payload.fp) {
                        const reqCheck = await core.decryptVerified(record.ciphertext, identity.privateKeyArmored, [embedded.publicKeyArmored]);
                        if (reqCheck.signatureStatus === 'VERIFIED' && reqCheck.signerFingerprint === embedded.fingerprint) {
                            verifiedPeer = embedded;
                        }
                    }
                } catch (_) {
                    verifiedPeer = null;
                }
                if (state.identity !== identity) return;
                state.seen.set(record.id, 'done');
                if (!verifiedPeer || verifiedPeer.dropId === identity.dropId) {
                    log('[SECURITY] Rejected invalid or forged contact request.', 'warn');
                    ackMessages([record.id], mailboxDropId);
                    return;
                }
                if (payload.nonce) {
                    if (state.seenNonces.has(payload.nonce)) {
                        ackMessages([record.id], mailboxDropId);
                        return;
                    }
                    state.seenNonces.add(payload.nonce);
                }
                const existing = state.contacts.find((c) => c.dropId === verifiedPeer.dropId);
                if (existing) {
                    if (existing.fingerprint !== verifiedPeer.fingerprint) {
                        log(`[SECURITY] Rejected contact request for ${existing.alias}: fingerprint mismatch!`, 'warn');
                    } else {
                        log(`[PAIR] ${existing.alias} is already in your contacts.`, 'info');
                    }
                    ackMessages([record.id], mailboxDropId);
                    return;
                }
                const reqEntry = {
                    dropId: verifiedPeer.dropId,
                    alias: payload.alias || `Peer ${verifiedPeer.fingerprint.slice(0, 6)}`,
                    fingerprint: verifiedPeer.fingerprint,
                    publicKeyArmored: verifiedPeer.publicKeyArmored,
                    avatarBase64: payload.avatarBase64 || null,
                    avatarHash: payload.avatarHash || null,
                    receivedAt: Date.now()
                };
                const prevReqIdx = state.contactRequests.findIndex((r) => r.dropId === reqEntry.dropId);
                if (prevReqIdx >= 0) state.contactRequests[prevReqIdx] = reqEntry;
                else state.contactRequests.push(reqEntry);
                renderContactRequests();
                notifyIncoming(payload);
                log(`[PAIR] New verified contact request from ${reqEntry.alias} (${reqEntry.fingerprint.slice(0, 8)}...).`, 'success');
                ackMessages([record.id], mailboxDropId);
                return;
            }

            if (payload && payload.nonce) {
                if (state.seenNonces.has(payload.nonce)) {
                    log(`[REPLAY WARNING] Duplicate nonce ${payload.nonce.slice(0, 8)}... ignored.`, 'warn');
                    ackMessages([record.id], mailboxDropId);
                    return;
                }
                if (payload.ts && Math.abs(Date.now() - payload.ts) > 15 * 60 * 1000) {
                    log(`[REPLAY WARNING] Skewed timestamp (${new Date(payload.ts).toLocaleTimeString()}).`, 'warn');
                }
                state.seenNonces.add(payload.nonce);
                if (state.seenNonces.size > 5000) {
                    state.seenNonces.delete(state.seenNonces.values().next().value);
                }
            }

            const matchedContact = signerFingerprint
                ? state.contacts.find((c) => c.fingerprint === signerFingerprint)
                : null;
            const targetChatId = group ? group.id : (matchedContact?.dropId || activeChatId());

            if (payload && signatureStatus === 'VERIFIED' && matchedContact) {
                if (payload.type === 'clear_chat' && !group) {
                    state.messagesByChat.set(matchedContact.dropId, []);
                    state.unreadByChat.set(matchedContact.dropId, 0);
                    state.seen.set(record.id, 'done');
                    log(`[CHAT] ${matchedContact.alias} cleared this chat for both sides.`, 'warn');
                    renderChatList();
                    if (matchedContact.dropId === activeChatId()) renderMessages(true);
                    ackMessages([record.id], mailboxDropId);
                    return;
                }
                if (payload.type === 'profile_update') {
                    if (payload.profileAlias) matchedContact.alias = payload.profileAlias;
                    if (payload.avatarBase64 && isValidBase64(payload.avatarBase64)) {
                        matchedContact.avatarBase64 = payload.avatarBase64;
                        matchedContact.avatarHash = payload.avatarHash;
                    }
                    renderPeerAndHeader();
                } else if (payload.type === 'group_control' && payload.action === 'key_rotate' && payload.newSharedKey) {
                    const targetGroup = state.groups.find((g) => g.id === payload.groupId);
                    if (targetGroup) {
                        targetGroup.sharedKeyBase64 = payload.newSharedKey;
                        log(`[GROUP] ${matchedContact.alias} rotated AES-256 key for ${targetGroup.name}.`, 'warn');
                    }
                }
            }

            let fallbackText = '';
            let unsupportedWire = false;
            if (!payload) {
                const rawStr = new TextDecoder().decode(plainBytes);
                try {
                    const obj = JSON.parse(rawStr.trim());
                    if (obj && typeof obj === 'object' && ('v' in obj || 'type' in obj)) {
                        unsupportedWire = true;
                    } else {
                        fallbackText = rawStr;
                    }
                } catch (_) {
                    fallbackText = rawStr;
                }
            }

            upsertMessage(targetChatId, {
                id: record.id,
                isOutgoing: false,
                isGroup: Boolean(group),
                timestamp: record.createdAt || Date.now(),
                payload,
                fallbackText,
                unsupportedWire,
                signatureStatus,
                signerFingerprint,
                ciphertext: record.ciphertext,
                showArmor: false,
                failed: false
            });

            if (targetChatId !== activeChatId()) {
                state.unreadByChat.set(targetChatId, (state.unreadByChat.get(targetChatId) || 0) + 1);
            }
            state.seen.set(record.id, 'done');
            log(`[RECV] ${group ? 'Group message decrypted (AES-256-GCM)' : `Message decrypted (${signatureStatus})`}.`,
                group || signatureStatus === 'VERIFIED' ? 'success' : 'warn');
            renderChatList();
            if (targetChatId === activeChatId()) renderMessages(false);
            notifyIncoming(payload);
            ackMessages([record.id], mailboxDropId);
        } catch (e) {
            if (state.identity !== identity) return;
            state.seen.set(record.id, 'failed');
            log(`[RECV] Could not decrypt message: ${e.message}`, 'error');
            const fallbackChatId = state.groups.find((g) => g.groupDropId === mailboxDropId)?.id || activeChatId();
            upsertMessage(fallbackChatId, {
                id: record.id,
                isOutgoing: false,
                isGroup: mailboxDropId !== identity.dropId,
                timestamp: record.createdAt || Date.now(),
                payload: null,
                failed: true,
                ciphertext: record.ciphertext,
                showArmor: false
            });
            if (fallbackChatId === activeChatId()) renderMessages(false);
        }
    }

    // ------------------------------------------------------------------ connection

    function closeSocketOnly() {
        state.gen++;
        state.confirmed = false;
        const ws = state.ws;
        state.ws = null;
        if (ws) { try { ws.close(1000); } catch (_) {} }
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

    async function pollPending(gen, dropId = state.identity?.dropId, readToken = state.identity?.readToken) {
        if (!dropId || !readToken) return;
        const capturedIdentity = state.identity;
        try {
            const r = await api('GET', `/api/v2/inbox/${dropId}`, { token: readToken });
            if (state.identity !== capturedIdentity) return;
            if (r.status === 401 && dropId === capturedIdentity?.dropId) { handleAuthFailed(); return; }
            if (r.ok && r.json && Array.isArray(r.json.messages)) {
                for (const m of r.json.messages) await handleIncoming(m, dropId);
            }
        } catch (e) {
            if (gen === state.gen) log(`[RELAY] Inbox check failed: ${e.message}`, 'warn');
        }
    }

    function subscribeDropOnSocket(ws, dropId, readToken) {
        if (!ws || ws.readyState !== 1) return;
        ws.send(JSON.stringify({ type: 'SUBSCRIBE', dropId, readToken }));
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
        } catch (_) {
            setStatus('OFFLINE');
            scheduleReconnect();
            return;
        }
        state.ws = ws;

        ws.onopen = () => {
            if (gen !== state.gen) return;
            state.attempt = 0;
            subscribeDropOnSocket(ws, id.dropId, id.readToken);
            pollPending(gen, id.dropId, id.readToken);
            for (const g of state.groups) {
                subscribeDropOnSocket(ws, g.groupDropId, g.groupReadToken);
                pollPending(gen, g.groupDropId, g.groupReadToken);
            }
        };

        ws.onmessage = (evt) => {
            if (gen !== state.gen || typeof evt.data !== 'string') return;
            let data;
            try { data = JSON.parse(evt.data); } catch (_) { return; }
            if (data.type === 'SUBSCRIBED') {
                if (data.dropId === id.dropId) {
                    state.confirmed = true;
                    setStatus('CONNECTED');
                    log('[RELAY] Subscribed to your drop.', 'success');
                }
                for (const [mid, st] of state.seen) if (st === 'failed') state.seen.delete(mid);
            } else if (data.type === 'AUTH_FAILED') {
                if (!data.dropId || data.dropId === id.dropId) handleAuthFailed();
            } else if (data.type === 'MESSAGE' && data.message) {
                handleIncoming(data.message, data.dropId || id.dropId);
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
        if (status === 429) return 'rate limit reached or mailbox is full';
        if (status === 503) return 'the relay is at capacity';
        if (status === 413) return 'the message is too large for the relay';
        return (json && json.error) || `relay returned HTTP ${status}`;
    }

    async function encryptAndPostDirect(contact, payloadBytes, relayTtl = DEFAULT_TTL_SECONDS) {
        const ciphertext = await core.encryptSigned(payloadBytes, contact.publicKeyArmored, state.identity.privateKeyArmored);
        const r = await api('POST', '/api/v2/send', {
            body: { dropId: contact.dropId, ciphertext, ttlSeconds: relayTtl }
        });
        if (!r.ok) throw new Error(friendlySendError(r.status, r.json));
        return { ciphertext, messageId: r.json?.messageId };
    }

    async function encryptAndPostGroup(group, payloadBytes, relayTtl = DEFAULT_TTL_SECONDS) {
        const ciphertext = await core.encryptGroup(payloadBytes, group.sharedKeyBase64);
        const r = await api('POST', '/api/v2/send', {
            body: { dropId: group.groupDropId, ciphertext, ttlSeconds: relayTtl }
        });
        if (!r.ok) throw new Error(friendlySendError(r.status, r.json));
        return { ciphertext, messageId: r.json?.messageId };
    }

    async function sendToActiveChat(payloadBytes) {
        const activeGroup = getActiveGroup();
        const activeContact = getActiveContact();
        const ttl = state.ttlSeconds > 0 ? state.ttlSeconds : DEFAULT_TTL_SECONDS;
        const sent = activeGroup
            ? await encryptAndPostGroup(activeGroup, payloadBytes, ttl)
            : await encryptAndPostDirect(activeContact, payloadBytes, ttl);

        const chatId = activeGroup ? activeGroup.id : activeContact.dropId;
        upsertMessage(chatId, {
            id: sent.messageId || `local-${Date.now()}-${Math.random()}`,
            isOutgoing: true,
            isGroup: Boolean(activeGroup),
            timestamp: Date.now(),
            payload: core.parseWire(payloadBytes),
            ciphertext: sent.ciphertext,
            showArmor: false
        });
        renderChatList();
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

        if (!state.identity) { log('No keys in this tab. Click New Identity first.', 'error'); return; }
        const activeGroup = getActiveGroup();
        const activeContact = getActiveContact();
        if (!activeGroup && (!activeContact || !activeContact.dropId)) {
            showPeerError('Save your peer\'s public key first. It must include a Drop-ID.');
            return;
        }
        showPeerError('');
        input.value = '';
        const replyTo = state.replyToMsg ? (state.replyToMsg.payload?.msgId || state.replyToMsg.id) : null;
        state.replyToMsg = null;
        renderReplyBar();
        clearAttachment();

        const commonOpts = {
            senderAlias: state.userAlias || undefined,
            groupId: activeGroup ? activeGroup.id : undefined,
            ttlSeconds: state.ttlSeconds > 0 ? state.ttlSeconds : undefined,
            replyTo: replyTo || undefined
        };

        if (attachment) {
            await queueSend(() => {
                log(`[SEND] Encrypting ${attachment.filename}...`, 'info');
                return sendToActiveChat(core.buildMedia(attachment.mimeType, attachment.filename, attachment.bytes, commonOpts));
            });
        }
        if (text) {
            await queueSend(() => sendToActiveChat(core.buildText(text, commonOpts)));
        }
    }

    // ------------------------------------------------------------------ groups & introductions

    async function joinGroupFromInvite(payload) {
        if (state.groups.some((g) => g.groupDropId === payload.groupDropId)) return;
        const group = {
            id: payload.groupId || core.randomHex16(),
            name: payload.groupName || 'Encrypted Group',
            groupDropId: payload.groupDropId,
            groupReadToken: payload.groupReadToken,
            sharedKeyBase64: payload.sharedKey,
            members: [],
            createdAt: Date.now(),
            isAdmin: false
        };
        state.groups.push(group);
        if (state.ws && state.confirmed) {
            subscribeDropOnSocket(state.ws, group.groupDropId, group.groupReadToken);
            pollPending(state.gen, group.groupDropId, group.groupReadToken);
        }
        log(`[GROUP] Joined "${group.name}".`, 'success');
        selectChat('group', group.id);
    }

    async function acceptContactIntroduction(payload) {
        try {
            const parsed = await core.parsePeerInput(payload.introArmoredKey, payload.introAlias);
            registerContact(parsed);
            renderPeerAndHeader();
            log(`[INTRO] Added contact "${parsed.alias}".`, 'success');
        } catch (e) {
            log(`[INTRO] Could not add contact: ${e.message}`, 'error');
        }
    }

    function openCreateGroupModal() {
        const list = $('group-members-list');
        const err = $('group-modal-error');
        err?.classList.add('hidden');
        list.textContent = '';
        if (state.contacts.length === 0) {
            err.textContent = getStrings(state.lang).noContactsAvailable;
            err.classList.remove('hidden');
        } else {
            for (const c of state.contacts) {
                const label = doc.createElement('label');
                label.className = 'check-item';
                const cb = doc.createElement('input');
                cb.type = 'checkbox';
                cb.value = c.dropId;
                cb.checked = true;
                label.appendChild(cb);
                const span = doc.createElement('span');
                span.textContent = `${c.alias} (${c.fingerprint.slice(0, 8)}...)`;
                label.appendChild(span);
                list.appendChild(label);
            }
        }
        $('group-name-input').value = '';
        $('group-modal').classList.remove('hidden');
    }

    async function submitCreateGroup() {
        const name = $('group-name-input').value.trim();
        const err = $('group-modal-error');
        if (!name) {
            err.textContent = 'Group name is required.';
            err.classList.remove('hidden');
            return;
        }
        const selectedDrops = Array.from($('group-members-list').querySelectorAll('input[type="checkbox"]:checked')).map((x) => x.value);
        const members = state.contacts.filter((c) => selectedDrops.includes(c.dropId));
        if (members.length === 0) {
            err.textContent = 'Select at least one member.';
            err.classList.remove('hidden');
            return;
        }

        const groupReadToken = core.randomHex32();
        const groupDropId = await core.deriveDropId(groupReadToken);
        const group = {
            id: core.randomHex16(),
            name: core.sanitizeAlias(name) || 'Encrypted Group',
            groupDropId,
            groupReadToken,
            sharedKeyBase64: core.generateGroupKey(),
            members: members.map((m) => ({
                dropId: m.dropId,
                alias: m.alias,
                publicKeyArmored: m.publicKeyArmored,
                fingerprint: m.fingerprint
            })),
            createdAt: Date.now(),
            isAdmin: true
        };
        state.groups.push(group);
        if (state.ws && state.confirmed) {
            subscribeDropOnSocket(state.ws, group.groupDropId, group.groupReadToken);
        }
        const inviteBytes = core.buildGroupInvite({
            groupId: group.id,
            groupName: group.name,
            groupDropId: group.groupDropId,
            groupReadToken: group.groupReadToken,
            sharedKey: group.sharedKeyBase64,
            senderAlias: state.userAlias
        });
        for (const m of members) {
            queueSend(() => encryptAndPostDirect(m, inviteBytes));
        }
        $('group-modal').classList.add('hidden');
        log(`[GROUP] Created "${group.name}" & sent ${members.length} E2EE invite(s).`, 'success');
        selectChat('group', group.id);
    }

    function openGroupManageModal() {
        const g = getActiveGroup();
        if (!g) return;
        $('group-manage-info').textContent = `Group: ${g.name}\nDrop-ID: ${g.groupDropId}\nMembers: ${g.members.length}`;
        $('group-manage-modal').classList.remove('hidden');
    }

    async function rotateGroupKey() {
        const g = getActiveGroup();
        if (!g) return;
        const newKey = core.generateGroupKey();
        g.sharedKeyBase64 = newKey;
        const ctrlBytes = core.buildGroupControl({
            groupId: g.id,
            action: 'key_rotate',
            newSharedKey: newKey,
            senderAlias: state.userAlias
        });
        for (const m of g.members) {
            const c = state.contacts.find((x) => x.dropId === m.dropId);
            if (c) queueSend(() => encryptAndPostDirect(c, ctrlBytes));
        }
        $('group-manage-modal').classList.add('hidden');
        log(`[GROUP] Rotated AES-256 key for "${g.name}".`, 'success');
    }

    async function leaveActiveGroup() {
        const g = getActiveGroup();
        if (!g) return;
        const ctrlBytes = core.buildGroupControl({
            groupId: g.id,
            action: 'leave',
            targetDropId: state.identity?.dropId,
            senderAlias: state.userAlias
        });
        queueSend(() => encryptAndPostGroup(g, ctrlBytes));
        state.groups = state.groups.filter((x) => x.id !== g.id);
        state.activeChat = state.contacts[0] ? { type: 'direct', id: state.contacts[0].dropId } : null;
        $('group-manage-modal').classList.add('hidden');
        renderPeerAndHeader();
        renderMessages(true);
    }

    function openIntroduceModal() {
        const active = getActiveContact();
        if (!active || state.contacts.length < 2) return;
        const sel = $('introduce-select');
        sel.textContent = '';
        for (const c of state.contacts) {
            if (c.dropId === active.dropId) continue;
            const opt = doc.createElement('option');
            opt.value = c.dropId;
            opt.textContent = `${c.alias} (${c.fingerprint.slice(0, 8)}...)`;
            sel.appendChild(opt);
        }
        $('introduce-modal').classList.remove('hidden');
    }

    async function submitIntroduce() {
        const active = getActiveContact();
        const target = state.contacts.find((c) => c.dropId === $('introduce-select').value);
        if (!active || !target) return;
        const sc = await core.safetyCode(state.identity.fingerprint, target.fingerprint);
        const bytes = core.buildIntroduction({
            introAlias: target.alias,
            introDropId: target.dropId,
            introArmoredKey: target.publicKeyArmored,
            introSafetyCode: sc,
            senderAlias: state.userAlias
        });
        await queueSend(() => sendToActiveChat(bytes));
        $('introduce-modal').classList.add('hidden');
        log(`[INTRO] Sent introduction of ${target.alias} to ${active.alias}.`, 'success');
    }

    // ------------------------------------------------------------------ avatar & profile broadcast

    async function handleAvatarPicked(event) {
        const file = event.target.files?.[0];
        if (!file) return;
        if (!isInlineImageType(file.type)) {
            log('Avatar must be PNG, JPEG, or WebP.', 'error');
            return;
        }
        if (file.size > MAX_AVATAR_BYTES) {
            log('Avatar must be 25 KB or smaller.', 'error');
            return;
        }
        const bytes = new Uint8Array(await file.arrayBuffer());
        const b64 = core.bytesToBase64(bytes);
        const hash = core.bytesToHex(await core.sha256(bytes));
        state.myAvatarBase64 = b64;
        state.myAvatarHash = hash;
        renderIdentity();
        log('[PROFILE] Avatar updated in RAM. Click Sync Profile to broadcast.', 'info');
    }

    async function broadcastProfile() {
        if (!state.identity) return;
        state.userAlias = core.sanitizeAlias($('my-alias-input')?.value || '');
        if (state.contacts.length === 0) {
            log('[PROFILE] Alias saved locally (no contacts to sync yet).', 'info');
            return;
        }
        const bytes = core.buildProfileUpdate({
            profileAlias: state.userAlias || undefined,
            avatarBase64: state.myAvatarBase64 || undefined,
            avatarHash: state.myAvatarHash || undefined,
            senderAlias: state.userAlias || undefined
        });
        for (const c of state.contacts) {
            queueSend(() => encryptAndPostDirect(c, bytes));
        }
        log(`[PROFILE] Broadcast profile update to ${state.contacts.length} contact(s).`, 'success');
    }

    // ------------------------------------------------------------------ .gkbackup export / import

    function openBackupModal(mode) {
        state.backupMode = mode;
        const s = getStrings(state.lang);
        $('backup-modal-title').textContent = mode === 'export' ? s.backupModalExportTitle : s.backupModalImportTitle;
        $('backup-file-row').classList.toggle('hidden', mode !== 'import');
        $('backup-passphrase').value = '';
        $('backup-error').classList.add('hidden');
        if ($('backup-file-input')) $('backup-file-input').value = '';
        $('backup-modal').classList.remove('hidden');
    }

    async function submitBackup() {
        const pass = $('backup-passphrase').value;
        const err = $('backup-error');
        err.classList.add('hidden');
        try {
            if (state.backupMode === 'export') {
                const content = await core.exportEncryptedBackup({
                    identity: state.identity,
                    userAlias: state.userAlias,
                    relayUrl: origin,
                    myAvatarBase64: state.myAvatarBase64,
                    myAvatarHash: state.myAvatarHash,
                    contacts: state.contacts,
                    groups: state.groups
                }, pass);
                const blob = new (env.Blob || win.Blob)([content], { type: 'application/json' });
                const url = env.URL.createObjectURL(blob);
                const a = doc.createElement('a');
                a.href = url;
                a.download = `ghostkey-backup-${new Date().toISOString().slice(0, 10)}.gkbackup`;
                doc.body.appendChild(a);
                a.click();
                doc.body.removeChild(a);
                env.URL.revokeObjectURL(url);
                $('backup-modal').classList.add('hidden');
                log('[BACKUP] Exported encrypted .gkbackup.', 'success');
            } else {
                const file = $('backup-file-input').files?.[0];
                if (!file) throw new Error('Select a .gkbackup file first');
                const jsonStr = await file.text();
                const restored = await core.importEncryptedBackup(jsonStr, pass);
                state.identity = restored.identity;
                state.userAlias = restored.userAlias || '';
                state.myAvatarBase64 = restored.myAvatarBase64 || null;
                state.myAvatarHash = restored.myAvatarHash || null;
                state.contacts = restored.contacts || [];
                state.groups = restored.groups || [];
                state.seen.clear();
                state.seenNonces.clear();
                state.messagesByChat.clear();
                state.unreadByChat.clear();
                state.authFailed = false;
                state.attempt = 0;
                state.activeChat = state.contacts[0]
                    ? { type: 'direct', id: state.contacts[0].dropId }
                    : state.groups[0] ? { type: 'group', id: state.groups[0].id } : null;
                renderIdentity();
                renderPeerAndHeader();
                renderMessages(true);
                connect();
                $('backup-modal').classList.add('hidden');
                log(`[BACKUP] Restored identity + ${state.contacts.length} contact(s) + ${state.groups.length} group(s).`, 'success');
            }
        } catch (e) {
            err.textContent = e.message || 'Backup error';
            err.classList.remove('hidden');
        }
    }

    // ------------------------------------------------------------------ attachments & PTT

    function clearAttachment() {
        state.attachment = null;
        $('attachment-bar')?.classList.add('hidden');
        if ($('media-file-input')) $('media-file-input').value = '';
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

    let mediaRecorder = null;
    let audioChunks = [];
    let recTimerInterval = null;
    let recStartTime = 0;

    function showRecordingUI(isRecording) {
        const bar = $('recording-bar');
        const form = $('chat-form');
        if (isRecording) {
            bar?.classList.remove('hidden');
            form?.classList.add('hidden');
        } else {
            bar?.classList.add('hidden');
            form?.classList.remove('hidden');
            if (recTimerInterval) { win.clearInterval(recTimerInterval); recTimerInterval = null; }
        }
    }

    function updateRecTimer() {
        const elapsed = Math.floor((Date.now() - recStartTime) / 1000);
        const mm = String(Math.floor(elapsed / 60)).padStart(2, '0');
        const ss = String(elapsed % 60).padStart(2, '0');
        if ($('rec-timer')) $('rec-timer').textContent = `${mm}:${ss}`;
    }

    async function startRecording() {
        if (!getActiveGroup() && (!getActiveContact() || !getActiveContact().dropId)) {
            showPeerError('Save your peer\'s public key first. It must include a Drop-ID.');
            return;
        }
        const nav = win.navigator;
        if (!nav?.mediaDevices?.getUserMedia || typeof win.MediaRecorder === 'undefined') {
            log('Microphone recording is not supported in this browser.', 'error');
            return;
        }
        try {
            const stream = await nav.mediaDevices.getUserMedia({ audio: true });
            const MR = win.MediaRecorder;
            let mimeType = 'audio/webm;codecs=opus';
            let ext = 'webm';
            if (MR.isTypeSupported?.('audio/ogg;codecs=opus')) { mimeType = 'audio/ogg;codecs=opus'; ext = 'ogg'; }
            else if (MR.isTypeSupported?.('audio/mp4')) { mimeType = 'audio/mp4'; ext = 'm4a'; }
            mediaRecorder = new MR(stream, { mimeType, audioBitsPerSecond: 32000 });
            audioChunks = [];
            mediaRecorder.ondataavailable = (e) => { if (e.data && e.data.size > 0) audioChunks.push(e.data); };
            mediaRecorder.onstop = async () => {
                stream.getTracks().forEach((t) => t.stop());
                if (audioChunks.length === 0) return;
                const BlobClass = env.Blob || win.Blob;
                const blob = new BlobClass(audioChunks, { type: mimeType });
                if (blob.size < 500) { log('Voice note was too short.', 'warn'); return; }
                const bytes = new Uint8Array(await blob.arrayBuffer());
                const rawMime = mimeType.split(';')[0];
                const activeGroup = getActiveGroup();
                const opts = {
                    senderAlias: state.userAlias || undefined,
                    groupId: activeGroup ? activeGroup.id : undefined,
                    ttlSeconds: state.ttlSeconds > 0 ? state.ttlSeconds : undefined
                };
                await queueSend(() => sendToActiveChat(core.buildMedia(rawMime, `voice_${Date.now()}.${ext}`, bytes, opts)));
            };
            mediaRecorder.start(200);
            recStartTime = Date.now();
            showRecordingUI(true);
            updateRecTimer();
            recTimerInterval = win.setInterval(updateRecTimer, 200);
        } catch (err) {
            log(`Microphone error: ${err.message}`, 'error');
        }
    }

    function cancelRecording() {
        if (mediaRecorder && mediaRecorder.state !== 'inactive') {
            audioChunks = [];
            mediaRecorder.ondataavailable = null;
            mediaRecorder.onstop = null;
            try { mediaRecorder.stream?.getTracks().forEach((t) => t.stop()); mediaRecorder.stop(); } catch (_) {}
        }
        showRecordingUI(false);
    }

    function stopAndSendRecording() {
        if (mediaRecorder && mediaRecorder.state !== 'inactive') mediaRecorder.stop();
        showRecordingUI(false);
    }

    // ------------------------------------------------------------------ identity / peer actions

    async function newIdentity() {
        log('Generating an Ed25519 + X25519 identity...', 'info');
        try {
            const identity = await core.generateIdentity();
            if (state.disposed) return;
            state.identity = identity;
            state.authFailed = false;
            state.attempt = 0;
            state.seen.clear();
            state.seenNonces.clear();
            renderIdentity();
            log('Identity ready. Share your public key or Contact Card with your peer.', 'success');
            connect();
        } catch (e) {
            log(`Key generation failed: ${e.message}`, 'error');
        }
    }

    async function savePeerKey() {
        showPeerError('');
        try {
            const info = await core.parsePeerInput($('peer-key-input').value);
            if (!info.dropId) {
                state.peer = info;
                showPeerError('This key has no Drop-ID, so you cannot send to it.');
                log('Peer key saved without a Drop-ID.', 'warn');
                return;
            }
            const keyChanged = registerContact(info);
            $('peer-key-input').value = '';
            selectChat('direct', info.dropId);
            if (keyChanged) {
                log(`[WARNING] Key fingerprint changed for ${info.alias}! Verify Safety Code.`, 'warn');
            } else {
                log(`Peer saved (${info.alias} · ${info.fingerprint.slice(0, 8)}...).`, 'success');
            }
        } catch (e) {
            showPeerError(`Could not use this key: ${e.message}`);
        }
    }

    async function openSafetyModal() {
        const digits = $('safety-code-digits');
        const peer = getActiveContact();
        if (!state.identity || !peer) {
            digits.textContent = 'Save your peer\'s key first.';
        } else {
            digits.textContent = await core.safetyCode(state.identity.fingerprint, peer.fingerprint);
        }
        if ($('safety-modal')) $('safety-modal').classList.remove('hidden');
    }

    // ------------------------------------------------------------------ rendezvous pairing (5-min link / code)

    function clearRendezvousTimer() {
        if (state.rvCountdownTimer) {
            win.clearInterval(state.rvCountdownTimer);
            state.rvCountdownTimer = null;
        }
        state.rvExpiresAt = 0;
    }

    function showPairError(msg) {
        const box = $('pair-error');
        if (!box) return;
        box.textContent = msg || '';
        box.classList.toggle('hidden', !msg);
    }

    function switchPairMode(mode) {
        showPairError('');
        $('pair-tab-create')?.classList.toggle('active', mode === 'create');
        $('pair-tab-redeem')?.classList.toggle('active', mode === 'redeem');
        $('pair-create-panel')?.classList.toggle('hidden', mode !== 'create');
        $('pair-redeem-panel')?.classList.toggle('hidden', mode !== 'redeem');
        $('pair-preview-panel')?.classList.toggle('hidden', mode !== 'preview');
    }

    function openPairModal(mode = 'create') {
        switchPairMode(mode);
        $('pair-modal')?.classList.remove('hidden');
    }

    async function sendSignedContactRequest(contact) {
        if (!state.identity || !contact) return;
        const bytes = core.buildContactRequest({
            alias: state.userAlias || `Peer ${state.identity.fingerprint.slice(0, 6)}`,
            dropId: state.identity.dropId,
            fingerprint: state.identity.fingerprint,
            publicKeyArmored: state.identity.publicKeyArmored,
            avatarBase64: state.myAvatarBase64 || undefined,
            avatarHash: state.myAvatarHash || undefined,
            senderAlias: state.userAlias || undefined
        });
        await queueSend(() => encryptAndPostDirect(contact, bytes));
    }

    async function generateRendezvousInvite() {
        showPairError('');
        if (!state.identity) { showPairError('Generate an identity first.'); return; }
        try {
            const code = core.generateRendezvousCode();
            const card = core.encodeContactCard({
                alias: state.userAlias || `Peer ${state.identity.fingerprint.slice(0, 6)}`,
                dropId: state.identity.dropId,
                fingerprint: state.identity.fingerprint,
                publicKeyArmored: state.identity.publicKeyArmored
            });
            const { slotId, sealedBlob } = await core.sealRendezvousCard(code, card);
            const r = await api('POST', '/api/v2/rendezvous', {
                body: { slotId, sealedBlob, ttlSeconds: 300 }
            });
            if (!r.ok) throw new Error(r.json?.error || `HTTP ${r.status}`);

            const link = `${origin}/#pair=${code}`;
            if ($('rv-code-display')) $('rv-code-display').textContent = code;
            if ($('rv-link-display')) $('rv-link-display').value = link;
            $('rv-result-box')?.classList.remove('hidden');

            clearRendezvousTimer();
            state.rvExpiresAt = r.json?.expiresAt || (Date.now() + 300 * 1000);
            const updateCountdown = () => {
                const rem = Math.max(0, Math.ceil((state.rvExpiresAt - Date.now()) / 1000));
                const mins = Math.floor(rem / 60);
                const secs = String(rem % 60).padStart(2, '0');
                if ($('rv-countdown')) {
                    $('rv-countdown').textContent = rem > 0
                        ? `Expires in ${mins}:${secs} · One-time use`
                        : 'Code expired. Generate a new one.';
                }
                if (rem <= 0) clearRendezvousTimer();
            };
            updateCountdown();
            state.rvCountdownTimer = win.setInterval(updateCountdown, 1000);
            log(`[PAIR] Generated 5-minute rendezvous link (${code}).`, 'success');
        } catch (e) {
            showPairError(e.message);
        }
    }

    async function redeemRendezvousInput(rawInput) {
        showPairError('');
        if (!state.identity) { showPairError('Identity not ready yet.'); return; }
        try {
            const canonical = core.extractRendezvousCode(rawInput);
            const formatted = `${canonical.slice(0, 5)}-${canonical.slice(5)}`;
            const slotId = await core.deriveRendezvousSlotId(canonical);
            const r = await api('GET', `/api/v2/rendezvous/${slotId}`);
            if (!r.ok || !r.json?.sealedBlob) {
                throw new Error(r.json?.error || 'Invite link/code not found, expired, or already used.');
            }
            const peerInfo = await core.openRendezvousCard(canonical, r.json.sealedBlob);
            if (peerInfo.dropId === state.identity.dropId) {
                throw new Error('Cannot pair with your own identity.');
            }
            state.pendingPairInvite = { code: formatted, peerInfo };
            if ($('pair-preview-meta')) {
                $('pair-preview-meta').textContent = `${peerInfo.alias}\nFP: ${groupFingerprint(peerInfo.fingerprint)}\nDrop-ID: ${peerInfo.dropId.slice(0, 16)}...`;
            }
            if ($('pair-open-app-link')) {
                $('pair-open-app-link').href = `ghostkey://pair?c=${encodeURIComponent(formatted)}`;
            }
            switchPairMode('preview');
            $('pair-modal')?.classList.remove('hidden');
        } catch (e) {
            showPairError(e.message);
        }
    }

    async function confirmPairPreview(accepted) {
        const pending = state.pendingPairInvite;
        state.pendingPairInvite = null;
        if (!accepted || !pending) {
            $('pair-modal')?.classList.add('hidden');
            return;
        }
        const info = pending.peerInfo;
        const keyChanged = registerContact(info);
        selectChat('direct', info.dropId);
        $('pair-modal')?.classList.add('hidden');
        if ($('rv-input')) $('rv-input').value = '';
        const saved = state.contacts.find((c) => c.dropId === info.dropId);
        if (saved) await sendSignedContactRequest(saved);
        log(`[PAIR] Paired with ${info.alias} and sent signed contact request${keyChanged ? ' (key changed!)' : ''}.`, keyChanged ? 'warn' : 'success');
    }

    function checkUrlPairHash() {
        const hash = win.location?.hash || '';
        if (!hash || !hash.includes('pair=')) return;
        const match = /pair=([0-9A-Za-z-]+)/i.exec(hash);
        try {
            if (win.history?.replaceState) {
                win.history.replaceState(null, '', (win.location.pathname || '/') + (win.location.search || ''));
            }
        } catch (_) {}
        if (match && match[1]) {
            openPairModal('redeem');
            redeemRendezvousInput(match[1]);
        }
    }

    async function copyTextFeedback(btnId, text, defaultLabel) {
        const btn = $(btnId);
        try { await win.navigator.clipboard.writeText(text); } catch (_) {}
        if (btn) {
            btn.textContent = getStrings(state.lang).copied;
            win.setTimeout(() => { btn.textContent = defaultLabel; }, 1800);
        }
    }

    function wipeSession() {
        cancelRecording();
        if (state.reconnectTimer) { win.clearTimeout(state.reconnectTimer); state.reconnectTimer = null; }
        closeSocketOnly();
        state.identity = null;
        state.userAlias = '';
        state.myAvatarBase64 = null;
        state.myAvatarHash = null;
        state.peer = null;
        state.contacts = [];
        state.contactRequests = [];
        state.groups = [];
        state.activeChat = null;
        state.messagesByChat.clear();
        state.unreadByChat.clear();
        state.seen.clear();
        state.seenNonces.clear();
        state.attachment = null;
        state.replyToMsg = null;
        state.pendingPairInvite = null;
        clearRendezvousTimer();
        state.authFailed = false;
        revokeBlobUrls();
        if ($('peer-key-input')) $('peer-key-input').value = '';
        if ($('logs-container')) $('logs-container').textContent = '';
        $('pair-modal')?.classList.add('hidden');
        $('rv-result-box')?.classList.add('hidden');
        clearAttachment();
        renderReplyBar();
        renderContactRequests();
        showPeerError('');
        renderIdentity();
        renderPeerAndHeader();
        renderMessages(true);
        setStatus('WIPED');
        log('Keys, contacts, groups and messages dropped from RAM.', 'warn');
    }

    function onNewIdentityClick() {
        const confirmFn = env.confirm || win.confirm.bind(win);
        if (state.identity && !confirmFn('Create a new identity? Your peers will need your new public key.')) return;
        state.messagesByChat.clear();
        state.unreadByChat.clear();
        renderMessages(true);
        newIdentity();
    }

    // ------------------------------------------------------------------ notifications

    let titleBlinkTimer = null;
    let unreadTitleCount = 0;
    const originalTitle = doc.title || 'GhostKey';

    function playIncomingChime() {
        try {
            const AudioContext = win.AudioContext || win.webkitAudioContext;
            if (!AudioContext) return;
            const ctx = new AudioContext();
            const now = ctx.currentTime;
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.type = 'sine';
            osc.frequency.setValueAtTime(587.33, now);
            osc.frequency.setValueAtTime(880, now + 0.08);
            gain.gain.setValueAtTime(0.15, now);
            gain.gain.exponentialRampToValueAtTime(0.001, now + 0.35);
            osc.connect(gain);
            gain.connect(ctx.destination);
            osc.start(now);
            osc.stop(now + 0.35);
            osc.onended = () => { try { ctx.close(); } catch (_) {} };
        } catch (_) {}
    }

    function notifyIncoming() {
        if (doc.visibilityState !== 'visible') {
            playIncomingChime();
            unreadTitleCount++;
            if (titleBlinkTimer) win.clearInterval(titleBlinkTimer);
            let toggle = false;
            titleBlinkTimer = win.setInterval(() => {
                if (doc.visibilityState === 'visible') {
                    win.clearInterval(titleBlinkTimer);
                    titleBlinkTimer = null;
                    unreadTitleCount = 0;
                    doc.title = originalTitle;
                    return;
                }
                doc.title = toggle ? `(${unreadTitleCount}) New encrypted message` : 'GhostKey';
                toggle = !toggle;
            }, 900);
        }
        if (typeof win !== 'undefined' && 'Notification' in win && win.Notification.permission === 'granted' && doc.visibilityState !== 'visible') {
            try {
                const n = new win.Notification('GhostKey', { body: 'New encrypted message received', tag: 'ghostkey-incoming', renotify: true });
                n.onclick = () => { win.focus(); n.close(); };
            } catch (_) {}
        }
    }

    function updateNotifBtn() {
        const btn = $('notif-btn');
        if (!btn) return;
        const perm = win.Notification?.permission;
        btn.textContent = perm === 'granted' ? 'Alerts ON' : 'Alerts';
    }

    async function toggleNotifications() {
        if (!('Notification' in win)) return;
        if (win.Notification.permission === 'default') {
            try { await win.Notification.requestPermission(); updateNotifBtn(); } catch (_) {}
        }
    }

    // ------------------------------------------------------------------ wiring

    on('chat-form', 'submit', handleSend);
    on('attach-btn', 'click', () => $('media-file-input')?.click());
    on('media-file-input', 'change', handleFileSelected);
    on('attachment-clear', 'click', clearAttachment);
    on('reply-clear', 'click', () => { state.replyToMsg = null; renderReplyBar(); });
    on('ptt-btn', 'click', startRecording);
    on('cancel-rec-btn', 'click', cancelRecording);
    on('stop-send-rec-btn', 'click', stopAndSendRecording);
    on('save-peer-btn', 'click', savePeerKey);
    on('quick-pair-btn', 'click', () => openPairModal('create'));
    on('pair-close', 'click', () => { state.pendingPairInvite = null; $('pair-modal')?.classList.add('hidden'); });
    on('pair-tab-create', 'click', () => switchPairMode('create'));
    on('pair-tab-redeem', 'click', () => switchPairMode('redeem'));
    on('rv-generate-btn', 'click', generateRendezvousInvite);
    on('rv-copy-link-btn', 'click', () => copyTextFeedback('rv-copy-link-btn', $('rv-link-display')?.value || '', 'Copy Link'));
    on('rv-copy-code-btn', 'click', () => copyTextFeedback('rv-copy-code-btn', $('rv-code-display')?.textContent || '', 'Copy Code'));
    on('rv-redeem-btn', 'click', () => redeemRendezvousInput($('rv-input')?.value || ''));
    on('pair-preview-accept', 'click', () => confirmPairPreview(true));
    on('pair-preview-decline', 'click', () => confirmPairPreview(false));
    on('copy-key-btn', 'click', () => state.identity && copyTextFeedback('copy-key-btn', state.identity.publicKeyArmored, getStrings(state.lang).copyKey));
    on('copy-card-btn', 'click', () => {
        if (!state.identity) return;
        const card = core.encodeContactCard({
            alias: state.userAlias || `Peer ${state.identity.fingerprint.slice(0, 6)}`,
            dropId: state.identity.dropId,
            fingerprint: state.identity.fingerprint,
            publicKeyArmored: state.identity.publicKeyArmored
        });
        copyTextFeedback('copy-card-btn', card, getStrings(state.lang).copyCard);
    });
    on('new-identity-btn', 'click', onNewIdentityClick);
    on('panic-btn', 'click', wipeSession);
    on('safety-btn', 'click', openSafetyModal);
    on('safety-close', 'click', () => $('safety-modal').classList.add('hidden'));
    on('safety-close-x', 'click', () => $('safety-modal').classList.add('hidden'));
    on('new-group-btn', 'click', openCreateGroupModal);
    on('group-modal-close', 'click', () => $('group-modal').classList.add('hidden'));
    on('create-group-submit', 'click', submitCreateGroup);
    on('group-manage-btn', 'click', openGroupManageModal);
    on('group-manage-close', 'click', () => $('group-manage-modal').classList.add('hidden'));
    on('rotate-key-btn', 'click', rotateGroupKey);
    on('leave-group-btn', 'click', leaveActiveGroup);
    on('introduce-btn', 'click', openIntroduceModal);
    on('introduce-close', 'click', () => $('introduce-modal').classList.add('hidden'));
    on('introduce-submit', 'click', submitIntroduce);
    on('clear-both-btn', 'click', async () => {
        const active = getActiveContact();
        if (!active || !state.identity || getActiveGroup()) return;
        const confirmFn = env.confirm || win.confirm.bind(win);
        if (!confirmFn(`Clear chat history with ${active.alias} for BOTH sides?`)) return;
        state.messagesByChat.set(active.dropId, []);
        state.unreadByChat.set(active.dropId, 0);
        renderChatList();
        renderMessages(true);
        const bytes = core.buildClearChat({ senderAlias: state.userAlias || undefined });
        await queueSend(() => encryptAndPostDirect(active, bytes));
        log(`[CHAT] Cleared chat with ${active.alias} for both sides.`, 'warn');
    });
    on('export-backup-btn', 'click', () => openBackupModal('export'));
    on('import-backup-btn', 'click', () => openBackupModal('import'));
    on('backup-close', 'click', () => $('backup-modal').classList.add('hidden'));
    on('backup-submit', 'click', submitBackup);
    on('avatar-pick-btn', 'click', () => $('avatar-file-input')?.click());
    on('avatar-file-input', 'change', handleAvatarPicked);
    on('broadcast-profile-btn', 'click', broadcastProfile);
    on('my-alias-input', 'input', (e) => { state.userAlias = core.sanitizeAlias(e.target.value); });
    on('ttl-select', 'change', (e) => { state.ttlSeconds = Number(e.target.value) || 0; });
    on('theme-btn', 'click', () => { state.theme = state.theme === 'dark' ? 'light' : 'dark'; applyThemeAndLang(); });
    on('lang-btn', 'click', () => { state.lang = state.lang === 'ar' ? 'en' : 'ar'; applyThemeAndLang(); renderPeerAndHeader(); renderMessages(false); });
    on('privacy-btn', 'click', () => {
        state.privacyShield = !state.privacyShield;
        if (!state.privacyShield) $('privacy-veil')?.classList.add('hidden');
        applyThemeAndLang();
    });
    on('toggle-logs-btn', 'click', () => { state.logsCollapsed = !state.logsCollapsed; $('layout')?.classList.toggle('logs-collapsed', state.logsCollapsed); });
    on('clear-logs-btn', 'click', () => { if ($('logs-container')) $('logs-container').textContent = ''; });
    on('notif-btn', 'click', toggleNotifications);

    const setPrivacyVeil = (show) => {
        if (!state.privacyShield) return;
        $('privacy-veil')?.classList.toggle('hidden', !show);
    };
    if (win.addEventListener) {
        win.addEventListener('blur', () => setPrivacyVeil(true), { signal: listeners.signal });
        win.addEventListener('focus', () => setPrivacyVeil(false), { signal: listeners.signal });
        win.addEventListener('hashchange', checkUrlPairHash, { signal: listeners.signal });
    }
    if (doc.addEventListener) {
        doc.addEventListener('keydown', (e) => {
            if (!state.privacyShield) return;
            const k = (e.key || '').toLowerCase();
            if (((e.ctrlKey || e.metaKey) && (k === 'p' || k === 's')) || e.key === 'PrintScreen') {
                e.preventDefault();
                setPrivacyVeil(true);
                log('[SHIELD] Capture/print shortcut blocked; tab veiled. Note: OS screenshots require Android app (FLAG_SECURE).', 'warn');
                if (e.key === 'PrintScreen' && win.navigator?.clipboard?.writeText) {
                    win.navigator.clipboard.writeText('').catch(() => {});
                }
            }
        }, { signal: listeners.signal });
        doc.addEventListener('visibilitychange', () => {
            setPrivacyVeil(doc.visibilityState !== 'visible');
            if (doc.visibilityState === 'visible' && titleBlinkTimer) {
                win.clearInterval(titleBlinkTimer);
                titleBlinkTimer = null;
                unreadTitleCount = 0;
                doc.title = originalTitle;
            }
        }, { signal: listeners.signal });
    }

    const reaperInterval = win.setInterval(() => {
        const now = Date.now();
        let activeChanged = false;
        const currentId = activeChatId();
        for (const [chatId, list] of state.messagesByChat.entries()) {
            const filtered = list.filter((m) => {
                const ttl = m.payload?.ttl;
                return !(ttl && typeof ttl === 'number' && (now - m.timestamp) >= (ttl * 1000));
            });
            if (filtered.length !== list.length) {
                state.messagesByChat.set(chatId, filtered);
                if (chatId === currentId) activeChanged = true;
            }
        }
        if (activeChanged) renderMessages(false);
    }, 1000);

    applyThemeAndLang();
    renderMessages(true);
    renderPeerAndHeader();
    updateNotifBtn();
    setStatus('OFFLINE');
    const ready = newIdentity().then(() => { checkUrlPairHash(); });

    return {
        ready,
        getStateForTests: () => state,
        dispose() {
            state.disposed = true;
            listeners.abort();
            clearRendezvousTimer();
            if (state.reconnectTimer) win.clearTimeout(state.reconnectTimer);
            if (reaperInterval) win.clearInterval(reaperInterval);
            if (titleBlinkTimer) win.clearInterval(titleBlinkTimer);
            closeSocketOnly();
            revokeBlobUrls();
        }
    };
}

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
