// GhostKey web core (wire protocol v2). Pure logic: no DOM access, so it can be unit-tested in Node.
//
//   import { createCore } from './ghostkey-core.mjs';
//   const core = createCore(openpgp);          // openpgp.js v6 (global in the browser, npm in Node)
//
// What it implements, identical to the Android app:
//   - identity: Ed25519 (sign/certify) + X25519 (encrypt) OpenPGP v4 "legacy" keys, strong-hash (SHA-2) signatures,
//     random 32-byte hex dropId and readToken, dropId bound into the certified User ID and
//     repeated in a "Comment: Drop-ID:" armor header
//   - messages are signed first and then encrypted (the signature is inside the ciphertext)
//   - plaintext is the unified v2 JSON { v, type, text|mimeType+filename+data, pad }

export function createCore(openpgp, options = {}) {
    const cryptoObj = options.crypto || globalThis.crypto;
    if (!cryptoObj || typeof cryptoObj.getRandomValues !== 'function') {
        throw new Error('No secure random number generator available');
    }
    const enums = openpgp.enums;

    const VERSION = 2;
    const SMALL_BUCKET = 512;
    const LARGE_BUCKET = 16 * 1024;
    const LARGE_THRESHOLD = 64 * 1024;
    const MIN_PAD_CHARS = 12;
    const HEX64 = /^[0-9a-f]{64}$/;
    const DROP_ID_USER_ID = /drop:([0-9a-f]{64})/;
    const DROP_ID_COMMENT = /^Comment:\s*Drop-ID:\s*([0-9a-f]{64})\s*$/im;
    const ACCEPTED_HASHES = [enums.hash.sha256, enums.hash.sha384, enums.hash.sha512];

    // ---------------------------------------------------------------- bytes / text helpers

    const utf8 = new TextEncoder();
    const utf8d = new TextDecoder('utf-8');

    function randomBytes(n) {
        const out = new Uint8Array(n);
        cryptoObj.getRandomValues(out);
        return out;
    }

    function bytesToHex(bytes) {
        let s = '';
        for (let i = 0; i < bytes.length; i++) s += bytes[i].toString(16).padStart(2, '0');
        return s;
    }

    function randomHex32() {
        return bytesToHex(randomBytes(32));
    }

    function isValidHex32(value) {
        return typeof value === 'string' && HEX64.test(value);
    }

    function bytesToBase64(bytes) {
        let binary = '';
        const chunk = 0x4000;
        for (let i = 0; i < bytes.length; i += chunk) {
            const sub = bytes.subarray(i, i + chunk);
            binary += String.fromCharCode.apply(null, sub);
        }
        return btoa(binary);
    }

    function base64ToBytes(b64) {
        const binary = atob(b64);
        const out = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
        return out;
    }

    function randomBase64Url(chars) {
        const raw = randomBytes(Math.ceil((chars * 3) / 4) + 3);
        return bytesToBase64(raw).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '').slice(0, chars);
    }

    // ---------------------------------------------------------------- SHA-256

    // Pure JS fallback: SubtleCrypto only exists in secure contexts, and a page served over plain
    // http (for example from a relay behind an onion address) may not count as one.
    const K = new Uint32Array([
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ]);

    function sha256Fallback(data) {
        const rotr = (x, n) => (x >>> n) | (x << (32 - n));
        const len = data.length;
        const paddedLen = (((len + 9 + 63) >> 6) << 6);
        const buf = new Uint8Array(paddedLen);
        buf.set(data);
        buf[len] = 0x80;
        const view = new DataView(buf.buffer);
        view.setUint32(paddedLen - 8, Math.floor(len / 0x20000000), false);
        view.setUint32(paddedLen - 4, (len << 3) >>> 0, false);

        const h = new Uint32Array([
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
        ]);
        const w = new Uint32Array(64);
        for (let off = 0; off < paddedLen; off += 64) {
            for (let i = 0; i < 16; i++) w[i] = view.getUint32(off + i * 4, false);
            for (let i = 16; i < 64; i++) {
                const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
                const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
                w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
            }
            let [a, b, c, d, e, f, g, hh] = h;
            for (let i = 0; i < 64; i++) {
                const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
                const ch = (e & f) ^ (~e & g);
                const t1 = (hh + S1 + ch + K[i] + w[i]) | 0;
                const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
                const maj = (a & b) ^ (a & c) ^ (b & c);
                const t2 = (S0 + maj) | 0;
                hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
            }
            h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
            h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + hh) | 0;
        }
        const out = new Uint8Array(32);
        const ov = new DataView(out.buffer);
        for (let i = 0; i < 8; i++) ov.setUint32(i * 4, h[i], false);
        return out;
    }

    async function sha256(bytes) {
        if (cryptoObj.subtle && typeof cryptoObj.subtle.digest === 'function') {
            try {
                return new Uint8Array(await cryptoObj.subtle.digest('SHA-256', bytes));
            } catch (e) {
                // fall through to the JS implementation
            }
        }
        return sha256Fallback(bytes);
    }

    // ---------------------------------------------------------------- identity

    function insertArmorComment(armored, comment) {
        const lines = armored.replace(/\r\n/g, '\n').split('\n');
        const begin = lines.findIndex((l) => l.startsWith('-----BEGIN PGP PUBLIC KEY BLOCK-----'));
        if (begin < 0) throw new Error('Unexpected armor format');
        lines.splice(begin + 1, 0, `Comment: ${comment}`);
        return lines.join('\n');
    }

    async function deriveDropId(tokenHex) {
        if (!isValidHex32(tokenHex)) return null;
        const hash = await sha256(utf8.encode('drop:' + tokenHex.toLowerCase()));
        return bytesToHex(hash);
    }

    /**
     * New identity. publicKeyArmored carries both the certified User ID "ghostkey drop:<dropId>"
     * and the convenience header "Comment: Drop-ID: <dropId>".
     * dropId is derived cryptographically from readToken: dropId = SHA-256("drop:" + readToken).
     * readToken is secret: never put it in the key block and never share it.
     */
    async function generateIdentity() {
        const readToken = randomHex32();
        const dropId = await deriveDropId(readToken);
        const { privateKey, publicKey } = await openpgp.generateKey({
            type: 'ecc',
            curve: 'ed25519Legacy',
            userIDs: [{ name: `ghostkey drop:${dropId}` }],
            format: 'armored'
        });
        const parsed = await openpgp.readKey({ armoredKey: publicKey });
        return {
            privateKeyArmored: privateKey,
            publicKeyArmored: insertArmorComment(publicKey, `Drop-ID: ${dropId}`),
            fingerprint: parsed.getFingerprint().toUpperCase(),
            dropId,
            readToken
        };
    }

    /**
     * Validate a pasted peer key block. Throws if there is no usable (valid, non-revoked,
     * non-expired) encryption key, or if the Drop-ID in the header disagrees with the one
     * certified inside the key.
     */
    async function parsePeerKey(armored) {
        const text = String(armored || '').trim();
        const key = await openpgp.readKey({ armoredKey: text });
        if (key.isPrivate()) throw new Error('This is a private key. Paste only your peer\'s PUBLIC key.');
        await key.verifyPrimaryKey();            // throws if revoked, expired or invalid
        await key.getEncryptionKey();            // throws if no usable encryption subkey

        const fromComment = (DROP_ID_COMMENT.exec(text) || [])[1] || null;
        let fromUserId = null;
        const checks = await key.verifyAllUsers();
        for (const uid of key.getUserIDs()) {
            const m = DROP_ID_USER_ID.exec(uid);
            if (!m) continue;
            const ok = checks.some((c) => c.userID === uid && c.valid === true);
            if (!ok) throw new Error('The key\'s User ID has an invalid self-signature');
            fromUserId = m[1];
        }
        if (fromUserId && fromComment && fromUserId !== fromComment.toLowerCase()) {
            throw new Error('The Drop-ID in the key header does not match the key itself');
        }
        return {
            publicKeyArmored: text,
            fingerprint: key.getFingerprint().toUpperCase(),
            dropId: fromUserId || (fromComment ? fromComment.toLowerCase() : null)
        };
    }

    // ---------------------------------------------------------------- wire format v2

    function withPad(obj) {
        obj.pad = '';
        const baseLen = utf8.encode(JSON.stringify(obj)).length;
        const bucket = baseLen < LARGE_THRESHOLD ? SMALL_BUCKET : LARGE_BUCKET;
        let target = Math.ceil(baseLen / bucket) * bucket;
        if (target - baseLen < MIN_PAD_CHARS) target += bucket;
        obj.pad = randomBase64Url(target - baseLen);
        return utf8.encode(JSON.stringify(obj));
    }

    function buildText(text) {
        return withPad({
            v: VERSION,
            type: 'text',
            text: String(text),
            ts: Date.now(),
            nonce: bytesToHex(randomBytes(16))
        });
    }

    function buildMedia(mimeType, filename, bytes) {
        return withPad({
            v: VERSION,
            type: 'media',
            mimeType: String(mimeType || 'application/octet-stream'),
            filename: String(filename || 'file'),
            data: bytesToBase64(bytes),
            ts: Date.now(),
            nonce: bytesToHex(randomBytes(16))
        });
    }

    /** Returns { type, text, ts, nonce } or { type, mimeType, filename, data, ts, nonce } (data = base64), or null. */
    function parseWire(input) {
        try {
            const text = typeof input === 'string' ? input : utf8d.decode(input);
            const obj = JSON.parse(text);
            if (!obj || typeof obj !== 'object' || obj.v !== VERSION) return null;
            const ts = typeof obj.ts === 'number' ? obj.ts : null;
            const nonce = typeof obj.nonce === 'string' ? obj.nonce : null;
            if (obj.type === 'text' && typeof obj.text === 'string') {
                return {
                    type: 'text',
                    text: obj.text,
                    senderAlias: typeof obj.senderAlias === 'string' ? obj.senderAlias : null,
                    ttl: typeof obj.ttl === 'number' ? obj.ttl : null,
                    ts,
                    nonce
                };
            }
            if (obj.type === 'media' && typeof obj.data === 'string') {
                return {
                    type: 'media',
                    mimeType: typeof obj.mimeType === 'string' ? obj.mimeType : 'application/octet-stream',
                    filename: typeof obj.filename === 'string' ? obj.filename : 'file',
                    data: obj.data,
                    senderAlias: typeof obj.senderAlias === 'string' ? obj.senderAlias : null,
                    ttl: typeof obj.ttl === 'number' ? obj.ttl : null,
                    ts,
                    nonce
                };
            }
            if (obj.type === 'group_invite' || obj.type === 'introduction' ||
                obj.type === 'profile_update' || obj.type === 'group_control') {
                return {
                    type: obj.type,
                    unsupported: true,
                    text: `[${obj.type} message received - unsupported on web console]`,
                    senderAlias: typeof obj.senderAlias === 'string' ? obj.senderAlias : null,
                    ts,
                    nonce
                };
            }
            return null;
        } catch (e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- encrypt / decrypt

    // Note: for Ed25519 keys openpgp.js picks the signature hash itself (SHA-512). The Android app
    // accepts SHA-256, SHA-384 and SHA-512 and rejects anything weaker.
    const ENCRYPT_CONFIG = {
        preferredSymmetricAlgorithm: enums.symmetric.aes256,
        aeadProtect: false          // classic integrity-protected packets, same as the Android app
    };

    /** Sign with our key, then encrypt for the peer. plaintext: Uint8Array. Returns armored text. */
    async function encryptSigned(plaintext, peerPublicKeyArmored, myPrivateKeyArmored) {
        const peerKey = await openpgp.readKey({ armoredKey: peerPublicKeyArmored });
        const myKey = await openpgp.readPrivateKey({ armoredKey: myPrivateKeyArmored });
        // binary (not text) so the signature type is "binary document", as the Android app expects
        const message = await openpgp.createMessage({ binary: plaintext });
        return openpgp.encrypt({
            message,
            encryptionKeys: peerKey,
            signingKeys: myKey,
            format: 'armored',
            config: ENCRYPT_CONFIG
        });
    }

    /**
     * Decrypt and check the signature against the stored peer key.
     * Returns { bytes, signatureStatus: 'VERIFIED' | 'UNSIGNED' | 'INVALID_SIGNATURE', signerFingerprint }.
     * Throws only when decryption itself fails; a bad signature is reported, not thrown.
     */
    async function decryptVerified(armoredMessage, myPrivateKeyArmored, peerPublicKeyArmored) {
        const myKey = await openpgp.readPrivateKey({ armoredKey: myPrivateKeyArmored });
        const message = await openpgp.readMessage({ armoredMessage });
        const peerKey = peerPublicKeyArmored
            ? await openpgp.readKey({ armoredKey: peerPublicKeyArmored })
            : null;

        const result = await openpgp.decrypt({
            message,
            decryptionKeys: myKey,
            verificationKeys: peerKey ? [peerKey] : [],
            format: 'binary'
        });

        let status = 'UNSIGNED';
        let signer = null;
        if (result.signatures && result.signatures.length > 0) {
            status = 'INVALID_SIGNATURE';
            if (peerKey) {
                try {
                    const sig = result.signatures[0];
                    const verified = await sig.verified;            // rejects when it does not verify
                    const packet = (await sig.signature).packets[0];
                    if (verified === true &&
                        packet.signatureType === enums.signature.binary &&
                        ACCEPTED_HASHES.includes(packet.hashAlgorithm)) {
                        status = 'VERIFIED';
                        signer = peerKey.getFingerprint().toUpperCase();
                    }
                } catch (e) {
                    status = 'INVALID_SIGNATURE';
                    signer = null;
                }
            }
        }
        return { bytes: result.data, signatureStatus: status, signerFingerprint: signer };
    }

    // ---------------------------------------------------------------- safety code

    /** 30-digit code from both fingerprints, order independent (same algorithm as Android). */
    async function safetyCode(fp1, fp2) {
        if (!fp1 || !fp2) return null;
        const clean = [fp1, fp2].map((f) => f.replace(/ /g, '').trim().toUpperCase()).sort();
        const hash = await sha256(utf8.encode(clean.join(':')));
        const groups = [];
        for (let i = 0; i < 6; i++) {
            const num = ((hash[i * 2] & 0xff) << 8) | (hash[i * 2 + 1] & 0xff);
            groups.push(String(num % 100000).padStart(5, '0'));
        }
        return groups.join(' ');
    }

    return {
        VERSION,
        randomHex32,
        isValidHex32,
        deriveDropId,
        bytesToBase64,
        base64ToBytes,
        sha256,
        sha256Fallback,
        generateIdentity,
        parsePeerKey,
        buildText,
        buildMedia,
        parseWire,
        encryptSigned,
        decryptVerified,
        safetyCode
    };
}
