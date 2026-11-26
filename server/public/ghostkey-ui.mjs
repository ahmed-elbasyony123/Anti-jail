// Safe rendering of chat messages, cards, and media for the GhostKey web console.
//
// Everything that comes from the other side (text, aliases, file names, MIME types, file data) is
// untrusted. Rules enforced here:
//   - no innerHTML / insertAdjacentHTML: elements are built strictly with DOM APIs and textContent
//   - inline <img> only for data:image/(png|jpeg|webp|gif);base64,<valid base64>; SVG is never inline
//   - audio and file attachments use Blob URLs; arbitrary files force application/octet-stream
//   - file names are sanitized for download

const INLINE_IMAGE = /^image\/(png|jpeg|webp|gif)$/i;
const AUDIO_TYPE = /^audio\/(mpeg|mp3|ogg|wav|x-wav|webm|mp4|aac|flac)$/i;
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

export function isInlineImageType(mime) {
    return typeof mime === 'string' && INLINE_IMAGE.test(mime.trim());
}

export function isPlayableAudioType(mime) {
    return typeof mime === 'string' && AUDIO_TYPE.test(mime.trim());
}

export function isValidBase64(value) {
    return typeof value === 'string' && value.length > 0 && value.length % 4 === 0 && BASE64.test(value);
}

/** A file name that is safe to hand to the download attribute. */
export function safeDownloadName(name) {
    let n = String(name || 'file');
    n = n.replace(/[\u0000-\u001f\u007f]/g, '').replace(/[\\/:*?"<>|]+/g, '_').replace(/^\.+/, '_').trim();
    if (n.length > 80) n = n.slice(0, 80);
    return n || 'file';
}

function el(doc, tag, className, text) {
    const node = doc.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

function renderMedia(doc, payload, ctx) {
    const wrap = el(doc, 'div', 'media');
    const filename = String(payload.filename || 'file');
    const mime = String(payload.mimeType || 'application/octet-stream');

    if (isInlineImageType(mime) && isValidBase64(payload.data)) {
        const img = doc.createElement('img');
        img.className = 'media-image';
        img.alt = filename;
        img.src = `data:${mime.trim().toLowerCase()};base64,${payload.data}`;
        wrap.appendChild(img);
        wrap.appendChild(el(doc, 'div', 'media-name', filename));
        return wrap;
    }

    let bytes = null;
    try {
        bytes = ctx.decodeBase64(payload.data);
    } catch (e) {
        wrap.appendChild(el(doc, 'div', 'media-name', `${filename} (attachment could not be decoded)`));
        return wrap;
    }

    if (isPlayableAudioType(mime)) {
        const audio = doc.createElement('audio');
        audio.className = 'media-audio';
        audio.controls = true;
        audio.src = ctx.createObjectUrl(bytes, mime.trim().toLowerCase());
        const label = filename.startsWith('voice_') ? `Voice note (${filename})` : filename;
        wrap.appendChild(el(doc, 'div', 'media-name', label));
        wrap.appendChild(audio);
        return wrap;
    }

    const card = el(doc, 'div', 'file-card');
    card.appendChild(el(doc, 'span', 'file-name', filename));
    const link = doc.createElement('a');
    link.className = 'btn small';
    link.textContent = 'SAVE';
    link.href = ctx.createObjectUrl(bytes, 'application/octet-stream');
    link.download = safeDownloadName(filename);
    link.rel = 'noopener';
    card.appendChild(link);
    wrap.appendChild(card);
    wrap.appendChild(el(doc, 'div', 'media-name', `type: ${mime.slice(0, 60)}`));
    return wrap;
}

function renderSignature(doc, msg) {
    if (msg.isOutgoing) return null;
    if (msg.isGroup) return el(doc, 'div', 'sig-ok', 'GROUP AES-256-GCM');
    if (msg.signatureStatus === 'VERIFIED') {
        const fp = msg.signerFingerprint ? ` \u00b7 ${msg.signerFingerprint.slice(0, 8)}` : '';
        return el(doc, 'div', 'sig-ok', `VERIFIED SIGNATURE${fp}`);
    }
    if (msg.signatureStatus === 'UNSIGNED') {
        return el(doc, 'div', 'sig-warn', 'UNSIGNED MESSAGE');
    }
    if (msg.signatureStatus === 'INVALID_SIGNATURE') {
        return el(doc, 'div', 'sig-warn', 'INVALID SIGNATURE \u2014 UNVERIFIED SENDER');
    }
    return null;
}

function summarizePayload(payload) {
    if (!payload) return 'Message';
    if (payload.type === 'text') return payload.text.slice(0, 80);
    if (payload.type === 'media') return `[File: ${payload.filename || 'attachment'}]`;
    if (payload.type === 'group_invite') return `[Group Invite: ${payload.groupName || 'Group'}]`;
    if (payload.type === 'introduction') return `[Introduction: ${payload.introAlias || 'Peer'}]`;
    return `[${payload.type || 'message'}]`;
}

function renderSpecialCard(doc, msg, payload, ctx) {
    if (payload.type === 'group_invite') {
        const card = el(doc, 'div', 'special-card');
        card.appendChild(el(doc, 'div', 'special-title', `Group Invite: ${payload.groupName || 'Encrypted Group'}`));
        card.appendChild(el(doc, 'div', 'special-meta', `Drop-ID: ${(payload.groupDropId || '').slice(0, 12)}...`));
        if (!msg.isOutgoing && typeof ctx.onJoinGroupInvite === 'function') {
            const alreadyJoined = typeof ctx.hasGroup === 'function' && ctx.hasGroup(payload.groupDropId);
            const btn = el(doc, 'button', 'btn small', alreadyJoined ? 'Joined' : 'Join Group');
            btn.type = 'button';
            btn.disabled = alreadyJoined;
            btn.addEventListener('click', () => ctx.onJoinGroupInvite(payload));
            card.appendChild(btn);
        }
        return card;
    }
    if (payload.type === 'introduction') {
        const card = el(doc, 'div', 'special-card');
        card.appendChild(el(doc, 'div', 'special-title', `Contact Introduction: ${payload.introAlias || 'Peer'}`));
        card.appendChild(el(doc, 'div', 'special-meta', `Drop-ID: ${(payload.introDropId || '').slice(0, 12)}...`));
        if (payload.introSafetyCode) {
            card.appendChild(el(doc, 'div', 'special-meta', `Safety Code: ${payload.introSafetyCode}`));
        }
        if (!msg.isOutgoing && typeof ctx.onAcceptIntroduction === 'function') {
            const btn = el(doc, 'button', 'btn small', 'Add Contact');
            btn.type = 'button';
            btn.addEventListener('click', () => ctx.onAcceptIntroduction(payload));
            card.appendChild(btn);
        }
        return card;
    }
    if (payload.type === 'group_control') {
        const desc = payload.action === 'key_rotate'
            ? `${payload.senderAlias || 'Member'} rotated the group AES-256 key`
            : payload.action === 'leave'
                ? `${payload.senderAlias || 'Member'} left the group`
                : `Group control: ${payload.action}`;
        return el(doc, 'div', 'system-note', desc);
    }
    if (payload.type === 'profile_update') {
        const who = payload.profileAlias || payload.senderAlias || 'Peer';
        return el(doc, 'div', 'system-note', `Profile updated (${who})`);
    }
    return null;
}

/**
 * Build the DOM node for one message.
 * msg: { id, isOutgoing, isGroup, timestamp, payload, failed, signatureStatus, signerFingerprint, ciphertext, showArmor }
 * ctx: { decodeBase64, createObjectUrl, onToggleArmor, onReply?, findMessageByWireId?, onJoinGroupInvite?, hasGroup?, onAcceptIntroduction? }
 */
export function renderBubble(doc, msg, ctx) {
    const wrapper = el(doc, 'div', 'bubble-row ' + (msg.isOutgoing ? 'out' : 'in'));
    const bubble = el(doc, 'div', 'bubble ' + (msg.isOutgoing ? 'out' : 'in'));

    const head = el(doc, 'div', 'bubble-head');
    const dirLabel = msg.isOutgoing
        ? 'OUTGOING'
        : (msg.isGroup && msg.payload?.senderAlias ? msg.payload.senderAlias : 'INCOMING');
    head.appendChild(el(doc, 'span', 'dir', dirLabel));

    const metaRight = el(doc, 'span', 'bubble-meta-right');
    if (msg.payload && typeof msg.payload.ttl === 'number' && msg.payload.ttl > 0) {
        metaRight.appendChild(el(doc, 'span', 'ttl-tag', `${msg.payload.ttl}s`));
    }
    metaRight.appendChild(el(doc, 'span', 'time', new Date(msg.timestamp).toLocaleTimeString()));
    head.appendChild(metaRight);
    bubble.appendChild(head);

    const sig = renderSignature(doc, msg);
    if (sig) bubble.appendChild(sig);

    if (msg.payload && msg.payload.replyTo && typeof ctx.findMessageByWireId === 'function') {
        const quoted = ctx.findMessageByWireId(msg.payload.replyTo);
        const quoteBox = el(doc, 'div', 'reply-quote');
        const who = quoted
            ? (quoted.isOutgoing ? 'You' : (quoted.payload?.senderAlias || 'Peer'))
            : 'Quoted message';
        quoteBox.appendChild(el(doc, 'div', 'reply-quote-author', who));
        quoteBox.appendChild(el(doc, 'div', 'reply-quote-text', quoted ? summarizePayload(quoted.payload) : msg.payload.replyTo.slice(0, 10)));
        bubble.appendChild(quoteBox);
    }

    if (msg.failed) {
        bubble.appendChild(el(doc, 'p', 'text warn', 'Could not decrypt this message. It stays on the relay and is retried on the next connection.'));
    } else if (msg.unsupportedWire) {
        bubble.appendChild(el(doc, 'p', 'text warn', 'This message is not supported on this version of GhostKey.'));
    } else if (msg.payload && msg.payload.type === 'media') {
        bubble.appendChild(renderMedia(doc, msg.payload, ctx));
    } else if (msg.payload && (msg.payload.type === 'group_invite' || msg.payload.type === 'introduction' || msg.payload.type === 'group_control' || msg.payload.type === 'profile_update')) {
        const card = renderSpecialCard(doc, msg, msg.payload, ctx);
        if (card) bubble.appendChild(card);
    } else {
        const text = msg.payload && typeof msg.payload.text === 'string' ? msg.payload.text : String(msg.fallbackText || '');
        bubble.appendChild(el(doc, 'p', 'text', text));
    }

    const foot = el(doc, 'div', 'bubble-foot');
    if (!msg.failed && typeof ctx.onReply === 'function') {
        const replyBtn = el(doc, 'button', 'link-btn', 'Reply');
        replyBtn.type = 'button';
        replyBtn.addEventListener('click', () => ctx.onReply(msg));
        foot.appendChild(replyBtn);
    }
    const toggle = el(doc, 'button', 'link-btn', msg.showArmor ? 'Hide PGP armor' : 'Inspect PGP armor');
    toggle.type = 'button';
    toggle.addEventListener('click', () => ctx.onToggleArmor(msg.id));
    foot.appendChild(toggle);
    bubble.appendChild(foot);

    if (msg.showArmor) {
        bubble.appendChild(el(doc, 'pre', 'armor', String(msg.ciphertext || '')));
    }

    wrapper.appendChild(bubble);
    return wrapper;
}
