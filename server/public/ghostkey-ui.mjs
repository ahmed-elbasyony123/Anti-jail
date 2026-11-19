// Safe rendering of chat messages for the web console.
//
// Everything that comes from the other side (text, file names, MIME types, file data) is
// untrusted. Rules enforced here:
//   - no innerHTML / insertAdjacentHTML with message data: elements are built with the DOM API
//     and textContent only
//   - inline <img> only for data:image/(png|jpeg|webp|gif);base64,<valid base64>; SVG is never
//     rendered inline
//   - audio and file attachments are exposed through Blob URLs, never as raw data: links, and
//     files are always given the generic application/octet-stream type
//   - file names are reduced to a harmless download name

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
        const label = filename.startsWith('voice_') ? `🎙️ ENCRYPTED OPUS VOICE NOTE (${filename})` : `🎙️ ${filename}`;
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

/**
 * Build the DOM node for one message.
 * msg: { id, isOutgoing, timestamp, payload, failed, signatureStatus, signerFingerprint, ciphertext, showArmor }
 * ctx: { decodeBase64(b64)->Uint8Array, createObjectUrl(bytes, mime)->string, onToggleArmor(id) }
 */
export function renderBubble(doc, msg, ctx) {
    const wrapper = el(doc, 'div', 'bubble-row ' + (msg.isOutgoing ? 'out' : 'in'));
    const bubble = el(doc, 'div', 'bubble ' + (msg.isOutgoing ? 'out' : 'in'));

    const head = el(doc, 'div', 'bubble-head');
    head.appendChild(el(doc, 'span', 'dir', msg.isOutgoing ? 'OUTGOING' : 'INCOMING'));
    head.appendChild(el(doc, 'span', 'time', new Date(msg.timestamp).toLocaleTimeString()));
    bubble.appendChild(head);

    const sig = renderSignature(doc, msg);
    if (sig) bubble.appendChild(sig);

    if (msg.failed) {
        bubble.appendChild(el(doc, 'p', 'text warn', 'Could not decrypt this message. It stays on the relay and is retried on the next connection.'));
    } else if (msg.payload && msg.payload.type === 'media') {
        bubble.appendChild(renderMedia(doc, msg.payload, ctx));
    } else {
        const text = msg.payload && typeof msg.payload.text === 'string' ? msg.payload.text : String(msg.fallbackText || '');
        bubble.appendChild(el(doc, 'p', 'text', text));
    }

    const foot = el(doc, 'div', 'bubble-foot');
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
