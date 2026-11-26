// XSS / unsafe-content tests for the message renderer (server/public/ghostkey-ui.mjs).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { renderBubble, safeDownloadName, isInlineImageType, isValidBase64 } from '../public/ghostkey-ui.mjs';

const dom = new JSDOM('<!doctype html><html><body></body></html>');
const doc = dom.window.document;

let urlCounter = 0;
const created = [];
const ctx = {
    decodeBase64: (b64) => Uint8Array.from(Buffer.from(b64, 'base64')),
    createObjectUrl: (bytes, mime) => { created.push(mime); return `blob:test/${++urlCounter}`; },
    onToggleArmor: () => {}
};

const base = { id: 'm1', isOutgoing: false, timestamp: 0, ciphertext: '', showArmor: false };
function render(payload, extra = {}) {
    return renderBubble(doc, { ...base, payload, ...extra }, ctx);
}
function assertNoActiveContent(node) {
    assert.equal(node.querySelectorAll('script, iframe, object, embed, svg, link, style').length, 0);
    for (const e of node.querySelectorAll('*')) {
        for (const a of e.attributes) assert.ok(!/^on/i.test(a.name), `inline handler ${a.name}`);
    }
    for (const a of node.querySelectorAll('a')) assert.match(a.getAttribute('href') || '', /^blob:/);
}

test('text is rendered as text, never as HTML', () => {
    const evil = '<img src=x onerror=alert(1)><script>alert(2)</script>';
    const n = render({ type: 'text', text: evil });
    assertNoActiveContent(n);
    assert.equal(n.querySelector('img'), null);
    assert.equal(n.querySelector('p.text').textContent, evil);
});

test('hostile file names and MIME types cannot inject markup', () => {
    const evilName = '"><script>alert(1)</script><img src=x onerror=alert(2)>.png';
    for (const mimeType of ['image/png" onerror="alert(1)', 'text/html', 'application/x-evil"><script>', 'image/svg+xml']) {
        const n = render({ type: 'media', filename: evilName, mimeType, data: Buffer.from('<svg onload=alert(1)>').toString('base64') });
        assertNoActiveContent(n);
        assert.equal(n.querySelector('img'), null, mimeType);
        assert.ok(n.textContent.includes(evilName));
    }
});

test('SVG is never rendered inline and files are offered as octet-stream Blob URLs', () => {
    created.length = 0;
    const n = render({ type: 'media', filename: 'x.svg', mimeType: 'image/svg+xml', data: Buffer.from('<svg onload=alert(1)/>').toString('base64') });
    assertNoActiveContent(n);
    const link = n.querySelector('a');
    assert.ok(link && link.href.startsWith('blob:'));
    assert.equal(link.getAttribute('download'), 'x.svg');
    assert.deepEqual(created, ['application/octet-stream']);
    assert.ok(!n.innerHTML.includes('data:image/svg'));
});

test('whitelisted images are inlined only as data:image/<type>;base64 with valid base64', () => {
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).toString('base64');
    const n = render({ type: 'media', filename: 'a.png', mimeType: 'image/png', data: png });
    const img = n.querySelector('img');
    assert.ok(img.getAttribute('src').startsWith('data:image/png;base64,'));
    assert.equal(img.getAttribute('alt'), 'a.png');
    assertNoActiveContent(n);

    for (const bad of ['AAAA" onerror="alert(1)', 'javascript:alert(1)', 'AAAA\nBBBB', 'AAA', '']) {
        const m = render({ type: 'media', filename: 'b.png', mimeType: 'image/png', data: bad });
        assert.equal(m.querySelector('img'), null, JSON.stringify(bad));
        assertNoActiveContent(m);
    }
    for (const mime of ['image/svg+xml', 'image/x-icon', 'image/png;charset=x', 'IMAGE/PNG\n']) {
        assert.equal(isInlineImageType(mime), mime === 'IMAGE/PNG\n' ? true : false, mime);
    }
});

test('audio uses a Blob URL; unknown audio types fall back to a file card', () => {
    const n = render({ type: 'media', filename: 'a.mp3', mimeType: 'audio/mpeg', data: Buffer.from('abc').toString('base64') });
    assert.ok(n.querySelector('audio').getAttribute('src').startsWith('blob:'));
    const m = render({ type: 'media', filename: 'a.x', mimeType: 'audio/x-evil', data: Buffer.from('abc').toString('base64') });
    assert.equal(m.querySelector('audio'), null);
    assert.ok(m.querySelector('a').getAttribute('href').startsWith('blob:'));
});

test('download names are sanitised', () => {
    for (const bad of ['../../etc/passwd', 'a\\b:c*?.txt', '.hidden', '\u0000x', '']) {
        const n = safeDownloadName(bad);
        assert.ok(!/[\\/:*?"<>|\u0000-\u001f]/.test(n) && !n.startsWith('.') && n.length > 0 && n.length <= 80, JSON.stringify(n));
    }
    assert.equal(safeDownloadName('x'.repeat(200)).length, 80);
    assert.equal(isValidBase64('QUJD'), true);
});

test('signature badges and failed messages', () => {
    assert.match(render({ type: 'text', text: 'x' }, { signatureStatus: 'VERIFIED', signerFingerprint: 'ABCDEF0123456789' }).querySelector('.sig-ok').textContent, /VERIFIED SIGNATURE · ABCDEF01/);
    assert.equal(render({ type: 'text', text: 'x' }, { signatureStatus: 'UNSIGNED' }).querySelector('.sig-warn').textContent, 'UNSIGNED MESSAGE');
    assert.equal(render({ type: 'text', text: 'x' }, { signatureStatus: 'INVALID_SIGNATURE' }).querySelector('.sig-warn').textContent, 'INVALID SIGNATURE — UNVERIFIED SENDER');
    assert.equal(render({ type: 'text', text: 'x' }, { isOutgoing: true, signatureStatus: 'UNSIGNED' }).querySelector('.sig-warn'), null);
    assert.match(render(null, { failed: true }).textContent, /Could not decrypt/);
    assert.equal(render(null, { fallbackText: '<b>raw</b>' }).querySelector('b'), null);
});

test('armor view is plain text', () => {
    const n = render({ type: 'text', text: 'x' }, { showArmor: true, ciphertext: '<script>alert(1)</script>' });
    assert.equal(n.querySelector('script'), null);
    assert.equal(n.querySelector('pre.armor').textContent, '<script>alert(1)</script>');
});

test('reply quotes, group invites, and introductions render without active markup', () => {
    const quoted = {
        id: 'orig-1',
        isOutgoing: false,
        payload: { msgId: 'orig-1', senderAlias: '<img src=x onerror=alert(1)>', type: 'text', text: '<b>quoted</b>' }
    };
    const replyNode = renderBubble(doc, {
        ...base,
        payload: { type: 'text', text: 'reply text', replyTo: 'orig-1' }
    }, { ...ctx, findMessageByWireId: (id) => (id === 'orig-1' ? quoted : null) });
    assertNoActiveContent(replyNode);
    assert.equal(replyNode.querySelector('.reply-quote-author').textContent, '<img src=x onerror=alert(1)>');
    assert.equal(replyNode.querySelector('.reply-quote-text').textContent, '<b>quoted</b>');

    const invNode = renderBubble(doc, {
        ...base,
        payload: { type: 'group_invite', groupName: '<script>x</script>', groupDropId: 'a'.repeat(64) }
    }, { ...ctx, hasGroup: () => false, onJoinGroupInvite: () => {} });
    assertNoActiveContent(invNode);
    assert.ok(invNode.textContent.includes('<script>x</script>'));

    const unsupportedNode = renderBubble(doc, {
        ...base,
        payload: null,
        unsupportedWire: true,
        fallbackText: '{"v":99,"type":"future_secret","text":"leak"}'
    }, ctx);
    assertNoActiveContent(unsupportedNode);
    assert.match(unsupportedNode.textContent, /not supported on this version/i);
    assert.ok(!unsupportedNode.textContent.includes('future_secret'));
});
