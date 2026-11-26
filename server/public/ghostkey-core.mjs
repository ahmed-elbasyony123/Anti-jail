// GhostKey web core (wire protocol v2). Pure logic: no DOM access, so it can be unit-tested in Node.
//
//   import { createCore } from './ghostkey-core.mjs';
//   const core = createCore(openpgp);          // openpgp.js v6 (global in the browser, npm in Node)
//
// Implements 100% Android GhostKey v3.0 wire, OpenPGP, Group AES-256-GCM, and .gkbackup parity.

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
    const MAX_AVATAR_DECODED_BYTES = 64 * 1024;
    const BACKUP_PBKDF2_ITERATIONS = 100_000;
    const HEX64 = /^[0-9a-f]{64}$/;
    const HEX32 = /^[0-9a-f]{32}$/i;
    const DROP_ID_USER_ID = /drop:([0-9a-f]{64})/;
    const DROP_ID_COMMENT = /^Comment:\s*Drop-ID:\s*([0-9a-f]{64})\s*$/im;
    const ACCEPTED_HASHES = [enums.hash.sha256, enums.hash.sha384, enums.hash.sha512];

    const utf8 = new TextEncoder();
    const utf8d = new TextDecoder('utf-8');
    const AAD_BACKUP = utf8.encode('ghostkey-backup-v1');
    const AAD_RENDEZVOUS = utf8.encode('ghostkey-rendezvous-v1');
    const RENDEZVOUS_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
    const RENDEZVOUS_CODE_LEN = 10;

    // ---------------------------------------------------------------- bytes / text helpers

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

    function randomHex16() {
        return bytesToHex(randomBytes(16));
    }

    function isValidHex32(value) {
        return typeof value === 'string' && HEX64.test(value);
    }

    function sanitizeAlias(raw) {
        return String(raw || '').replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, 48);
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
        const clean = String(b64 || '').trim();
        const binary = atob(clean);
        const out = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
        return out;
    }

    function randomBase64Url(chars) {
        const raw = randomBytes(Math.ceil((chars * 3) / 4) + 3);
        return bytesToBase64(raw).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '').slice(0, chars);
    }

    function constantTimeEqual(a, b) {
        if (a.length !== b.length) return false;
        let diff = 0;
        for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff === 0;
    }

    function isValidAes256Key(key) {
        if (typeof key !== 'string' || !key.trim()) return false;
        const trimmed = key.trim();
        if (HEX64.test(trimmed.toLowerCase())) return true;
        try {
            return base64ToBytes(trimmed).length === 32;
        } catch (_) {
            return false;
        }
    }

    function normalizeAes256KeyBytes(keyStr) {
        const trimmed = String(keyStr || '').trim();
        if (HEX64.test(trimmed.toLowerCase())) {
            const out = new Uint8Array(32);
            for (let i = 0; i < 32; i++) {
                out[i] = parseInt(trimmed.slice(i * 2, i * 2 + 2), 16);
            }
            return out;
        }
        const bytes = base64ToBytes(trimmed);
        if (bytes.length !== 32) throw new Error('Group key must be exactly 32 bytes (AES-256)');
        return bytes;
    }

    // ---------------------------------------------------------------- SHA-256 / HMAC / PBKDF2

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
            } catch (e) { /* fall through */ }
        }
        return sha256Fallback(bytes);
    }

    function hmacSha256Fallback(keyBytes, message) {
        const block = 64;
        let k = keyBytes;
        if (k.length > block) k = sha256Fallback(k);
        const ipad = new Uint8Array(block + message.length);
        const opad = new Uint8Array(block + 32);
        for (let i = 0; i < block; i++) {
            const kb = i < k.length ? k[i] : 0;
            ipad[i] = kb ^ 0x36;
            opad[i] = kb ^ 0x5c;
        }
        ipad.set(message, block);
        const inner = sha256Fallback(ipad);
        opad.set(inner, block);
        return sha256Fallback(opad);
    }

    function pbkdf2HmacSha256Fallback(passwordBytes, saltBytes, iterations = BACKUP_PBKDF2_ITERATIONS, dkLen = 32) {
        const hLen = 32;
        const blocks = Math.ceil(dkLen / hLen);
        const out = new Uint8Array(blocks * hLen);
        for (let bi = 1; bi <= blocks; bi++) {
            const seed = new Uint8Array(saltBytes.length + 4);
            seed.set(saltBytes, 0);
            seed[saltBytes.length] = (bi >>> 24) & 0xff;
            seed[saltBytes.length + 1] = (bi >>> 16) & 0xff;
            seed[saltBytes.length + 2] = (bi >>> 8) & 0xff;
            seed[saltBytes.length + 3] = bi & 0xff;
            let u = hmacSha256Fallback(passwordBytes, seed);
            const t = new Uint8Array(u);
            for (let iter = 1; iter < iterations; iter++) {
                u = hmacSha256Fallback(passwordBytes, u);
                for (let j = 0; j < hLen; j++) t[j] ^= u[j];
            }
            out.set(t, (bi - 1) * hLen);
        }
        return out.slice(0, dkLen);
    }

    async function pbkdf2HmacSha256(passwordBytes, saltBytes, iterations = BACKUP_PBKDF2_ITERATIONS, dkLen = 32) {
        if (cryptoObj.subtle && typeof cryptoObj.subtle.importKey === 'function') {
            try {
                const key = await cryptoObj.subtle.importKey('raw', passwordBytes, { name: 'PBKDF2' }, false, ['deriveBits']);
                const bits = await cryptoObj.subtle.deriveBits(
                    { name: 'PBKDF2', salt: saltBytes, iterations, hash: 'SHA-256' },
                    key,
                    dkLen * 8
                );
                return new Uint8Array(bits);
            } catch (_) { /* fall through */ }
        }
        return pbkdf2HmacSha256Fallback(passwordBytes, saltBytes, iterations, dkLen);
    }

    // ---------------------------------------------------------------- AES-256-GCM (SubtleCrypto + Pure-JS fallback)

    const AES_SBOX = new Uint8Array([
        0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
        0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
        0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
        0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
        0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
        0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
        0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
        0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
        0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
        0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
        0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
        0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
        0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
        0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
        0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
        0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
    ]);
    const AES_RCON = [0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36];

    function aes256ExpandKey(key) {
        const w = new Uint8Array(240); // 60 * 4 bytes for 14 rounds
        w.set(key.subarray(0, 32), 0);
        let bytes = 32;
        let rconIter = 0;
        const temp = new Uint8Array(4);
        while (bytes < 240) {
            temp.set(w.subarray(bytes - 4, bytes));
            if (bytes % 32 === 0) {
                const t0 = temp[0];
                temp[0] = AES_SBOX[temp[1]] ^ AES_RCON[rconIter++];
                temp[1] = AES_SBOX[temp[2]];
                temp[2] = AES_SBOX[temp[3]];
                temp[3] = AES_SBOX[t0];
            } else if (bytes % 32 === 16) {
                for (let j = 0; j < 4; j++) temp[j] = AES_SBOX[temp[j]];
            }
            for (let j = 0; j < 4; j++) {
                w[bytes] = w[bytes - 32] ^ temp[j];
                bytes++;
            }
        }
        return w;
    }

    function aes256EncryptBlock(roundKeys, block) {
        const s = new Uint8Array(block);
        const xtime = (x) => ((x << 1) ^ ((x & 0x80) ? 0x1b : 0)) & 0xff;
        for (let i = 0; i < 16; i++) s[i] ^= roundKeys[i];
        for (let r = 1; r <= 14; r++) {
            for (let i = 0; i < 16; i++) s[i] = AES_SBOX[s[i]];
            let t = s[1]; s[1] = s[5]; s[5] = s[9]; s[9] = s[13]; s[13] = t;
            t = s[2]; s[2] = s[10]; s[10] = t; t = s[6]; s[6] = s[14]; s[14] = t;
            t = s[15]; s[15] = s[11]; s[11] = s[7]; s[7] = s[3]; s[3] = t;
            if (r < 14) {
                for (let c = 0; c < 16; c += 4) {
                    const a0 = s[c], a1 = s[c+1], a2 = s[c+2], a3 = s[c+3];
                    const xr = a0 ^ a1 ^ a2 ^ a3;
                    s[c]   ^= xr ^ xtime(a0 ^ a1);
                    s[c+1] ^= xr ^ xtime(a1 ^ a2);
                    s[c+2] ^= xr ^ xtime(a2 ^ a3);
                    s[c+3] ^= xr ^ xtime(a3 ^ a0);
                }
            }
            const rkOff = r * 16;
            for (let i = 0; i < 16; i++) s[i] ^= roundKeys[rkOff + i];
        }
        return s;
    }

    function gf128Mul(x, y) {
        const z = new Uint8Array(16);
        const v = new Uint8Array(y);
        for (let i = 0; i < 16; i++) {
            for (let bit = 7; bit >= 0; bit--) {
                if ((x[i] >> bit) & 1) {
                    for (let k = 0; k < 16; k++) z[k] ^= v[k];
                }
                const lsb = v[15] & 1;
                for (let k = 15; k > 0; k--) {
                    v[k] = (v[k] >>> 1) | ((v[k - 1] & 1) << 7);
                }
                v[0] = v[0] >>> 1;
                if (lsb) v[0] ^= 0xe1;
            }
        }
        return z;
    }

    function ghash(H, aad, ciphertext) {
        let y = new Uint8Array(16);
        const feedBlocks = (buf) => {
            for (let off = 0; off < buf.length; off += 16) {
                const blk = new Uint8Array(16);
                blk.set(buf.subarray(off, Math.min(off + 16, buf.length)));
                for (let k = 0; k < 16; k++) y[k] ^= blk[k];
                y = gf128Mul(y, H);
            }
        };
        if (aad && aad.length > 0) feedBlocks(aad);
        if (ciphertext && ciphertext.length > 0) feedBlocks(ciphertext);
        const lenBlk = new Uint8Array(16);
        const dv = new DataView(lenBlk.buffer);
        const aadBits = (aad ? aad.length : 0) * 8;
        const ctBits = (ciphertext ? ciphertext.length : 0) * 8;
        dv.setUint32(0, Math.floor(aadBits / 0x100000000), false);
        dv.setUint32(4, aadBits >>> 0, false);
        dv.setUint32(8, Math.floor(ctBits / 0x100000000), false);
        dv.setUint32(12, ctBits >>> 0, false);
        for (let k = 0; k < 16; k++) y[k] ^= lenBlk[k];
        return gf128Mul(y, H);
    }

    function gcmInc32(counter) {
        const next = new Uint8Array(counter);
        let c = 1;
        for (let i = 15; i >= 12 && c; i--) {
            const sum = next[i] + c;
            next[i] = sum & 0xff;
            c = sum >>> 8;
        }
        return next;
    }

    function gcmJ0(iv) {
        const j0 = new Uint8Array(16);
        j0.set(iv.subarray(0, 12), 0);
        j0[15] = 1;
        return j0;
    }

    function aes256GcmEncryptFallback(keyBytes, ivBytes, plainBytes, aadBytes = null) {
        if (keyBytes.length !== 32 || ivBytes.length !== 12) throw new Error('Invalid key or 12-byte IV');
        const rk = aes256ExpandKey(keyBytes);
        const H = aes256EncryptBlock(rk, new Uint8Array(16));
        const j0 = gcmJ0(ivBytes);
        let ctr = j0;
        const out = new Uint8Array(plainBytes.length + 16);
        for (let off = 0; off < plainBytes.length; off += 16) {
            ctr = gcmInc32(ctr);
            const ks = aes256EncryptBlock(rk, ctr);
            const end = Math.min(off + 16, plainBytes.length);
            for (let i = off; i < end; i++) out[i] = plainBytes[i] ^ ks[i - off];
        }
        const ct = out.subarray(0, plainBytes.length);
        const tagMask = aes256EncryptBlock(rk, j0);
        const s = ghash(H, aadBytes, ct);
        for (let i = 0; i < 16; i++) out[plainBytes.length + i] = s[i] ^ tagMask[i];
        return out; // ciphertext || 16-byte tag
    }

    function aes256GcmDecryptFallback(keyBytes, ivBytes, ctPlusTag, aadBytes = null) {
        if (keyBytes.length !== 32 || ivBytes.length !== 12 || ctPlusTag.length < 16) {
            throw new Error('Invalid AES-GCM parameters');
        }
        const rk = aes256ExpandKey(keyBytes);
        const H = aes256EncryptBlock(rk, new Uint8Array(16));
        const j0 = gcmJ0(ivBytes);
        const ctLen = ctPlusTag.length - 16;
        const ct = ctPlusTag.subarray(0, ctLen);
        const tag = ctPlusTag.subarray(ctLen);
        const tagMask = aes256EncryptBlock(rk, j0);
        const s = ghash(H, aadBytes, ct);
        const expectedTag = new Uint8Array(16);
        for (let i = 0; i < 16; i++) expectedTag[i] = s[i] ^ tagMask[i];
        if (!constantTimeEqual(tag, expectedTag)) {
            throw new Error('AES-GCM authentication tag mismatch');
        }
        const plain = new Uint8Array(ctLen);
        let ctr = j0;
        for (let off = 0; off < ctLen; off += 16) {
            ctr = gcmInc32(ctr);
            const ks = aes256EncryptBlock(rk, ctr);
            const end = Math.min(off + 16, ctLen);
            for (let i = off; i < end; i++) plain[i] = ct[i] ^ ks[i - off];
        }
        return plain;
    }

    async function aes256GcmEncryptRaw(keyBytes, ivBytes, plainBytes, aadBytes = null) {
        if (cryptoObj.subtle && typeof cryptoObj.subtle.importKey === 'function') {
            try {
                const k = await cryptoObj.subtle.importKey('raw', keyBytes, { name: 'AES-GCM' }, false, ['encrypt']);
                const params = { name: 'AES-GCM', iv: ivBytes, tagLength: 128 };
                if (aadBytes && aadBytes.length > 0) params.additionalData = aadBytes;
                const buf = await cryptoObj.subtle.encrypt(params, k, plainBytes);
                return new Uint8Array(buf);
            } catch (_) { /* fall through */ }
        }
        return aes256GcmEncryptFallback(keyBytes, ivBytes, plainBytes, aadBytes);
    }

    async function aes256GcmDecryptRaw(keyBytes, ivBytes, ctPlusTag, aadBytes = null) {
        if (cryptoObj.subtle && typeof cryptoObj.subtle.importKey === 'function') {
            try {
                const k = await cryptoObj.subtle.importKey('raw', keyBytes, { name: 'AES-GCM' }, false, ['decrypt']);
                const params = { name: 'AES-GCM', iv: ivBytes, tagLength: 128 };
                if (aadBytes && aadBytes.length > 0) params.additionalData = aadBytes;
                const buf = await cryptoObj.subtle.decrypt(params, k, ctPlusTag);
                return new Uint8Array(buf);
            } catch (err) {
                // Only fall back when WebCrypto itself is unavailable/unsupported, not on auth failure
                if (err && (err.name === 'OperationError' || String(err.message || '').includes('tag'))) {
                    throw new Error('AES-GCM decryption failed');
                }
            }
        }
        return aes256GcmDecryptFallback(keyBytes, ivBytes, ctPlusTag, aadBytes);
    }

    function generateGroupKey() {
        return bytesToBase64(randomBytes(32));
    }

    async function encryptGroup(plaintextBytes, sharedKeyStr) {
        const keyBytes = normalizeAes256KeyBytes(sharedKeyStr);
        const iv = randomBytes(12);
        const ctPlusTag = await aes256GcmEncryptRaw(keyBytes, iv, plaintextBytes, null);
        const combined = new Uint8Array(12 + ctPlusTag.length);
        combined.set(iv, 0);
        combined.set(ctPlusTag, 12);
        return bytesToBase64(combined);
    }

    async function decryptGroup(base64Ciphertext, sharedKeyStr) {
        const keyBytes = normalizeAes256KeyBytes(sharedKeyStr);
        const combined = base64ToBytes(base64Ciphertext);
        if (combined.length < 28) throw new Error('Invalid group ciphertext');
        const iv = combined.subarray(0, 12);
        const body = combined.subarray(12);
        return aes256GcmDecryptRaw(keyBytes, iv, body, null);
    }

    // ---------------------------------------------------------------- identity & contact cards

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

    async function parsePeerKey(armored) {
        const text = String(armored || '').trim();
        const key = await openpgp.readKey({ armoredKey: text });
        if (key.isPrivate()) throw new Error('This is a private key. Paste only your peer\'s PUBLIC key.');
        await key.verifyPrimaryKey();
        await key.getEncryptionKey();

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

    function encodeContactCard({ alias, dropId, fingerprint, publicKeyArmored }) {
        return JSON.stringify({
            v: 1,
            type: 'ghostkey_contact',
            alias: sanitizeAlias(alias),
            dropId: String(dropId || '').trim().toLowerCase(),
            fp: String(fingerprint || '').trim().toUpperCase(),
            key: String(publicKeyArmored || '').trim()
        });
    }

    async function parsePeerInput(rawInput, fallbackAlias = '') {
        const text = String(rawInput || '').trim();
        if (!text) throw new Error('Paste a public key or Contact Card JSON');
        if (text.startsWith('{')) {
            let obj;
            try { obj = JSON.parse(text); } catch (_) { throw new Error('Malformed Contact Card JSON'); }
            if (!obj || obj.type !== 'ghostkey_contact' || typeof obj.key !== 'string') {
                throw new Error('Not a valid GhostKey Contact Card');
            }
            const parsed = await parsePeerKey(obj.key);
            if (obj.dropId && parsed.dropId && obj.dropId.toLowerCase() !== parsed.dropId) {
                throw new Error('Contact Card Drop-ID does not match the PGP key');
            }
            if (obj.fp && obj.fp.toUpperCase() !== parsed.fingerprint) {
                throw new Error('Contact Card fingerprint does not match the PGP key');
            }
            return {
                ...parsed,
                alias: sanitizeAlias(obj.alias || fallbackAlias) || `Peer ${parsed.fingerprint.slice(0, 6)}`
            };
        }
        const parsed = await parsePeerKey(text);
        return {
            ...parsed,
            alias: sanitizeAlias(fallbackAlias) || `Peer ${parsed.fingerprint.slice(0, 6)}`
        };
    }

    // ---------------------------------------------------------------- rendezvous (5-min burn-after-reading pairing)

    function generateRendezvousCode() {
        const bytes = randomBytes(RENDEZVOUS_CODE_LEN);
        let raw = '';
        for (let i = 0; i < RENDEZVOUS_CODE_LEN; i++) {
            raw += RENDEZVOUS_ALPHABET[bytes[i] & 31];
        }
        return `${raw.slice(0, 5)}-${raw.slice(5)}`;
    }

    function normalizeRendezvousCode(rawInput) {
        const cleaned = String(rawInput || '').toUpperCase().replace(/[^0-9A-Z]/g, '');
        if (cleaned.length !== RENDEZVOUS_CODE_LEN) {
            throw new Error(`Invite code must be ${RENDEZVOUS_CODE_LEN} characters`);
        }
        for (let i = 0; i < cleaned.length; i++) {
            if (!RENDEZVOUS_ALPHABET.includes(cleaned[i])) {
                throw new Error(`Invalid character "${cleaned[i]}" in invite code`);
            }
        }
        return cleaned;
    }

    function extractRendezvousCode(rawInput) {
        const text = String(rawInput || '').trim();
        if (!text) throw new Error('Enter a pairing code or invite link');
        const hashMatch = /(?:^|[#&?])(?:pair|c)=([^&#\s]+)/i.exec(text);
        if (hashMatch && hashMatch[1]) {
            return normalizeRendezvousCode(decodeURIComponent(hashMatch[1]));
        }
        if (text.includes('#')) {
            const frag = text.slice(text.indexOf('#') + 1).replace(/^pair=/i, '');
            if (frag) return normalizeRendezvousCode(decodeURIComponent(frag));
        }
        return normalizeRendezvousCode(text);
    }

    async function deriveRendezvousSlotId(codeInput) {
        const canonical = normalizeRendezvousCode(codeInput);
        return bytesToHex(await sha256(utf8.encode(`gk-rv-id-v1:${canonical}`)));
    }

    async function sealRendezvousCard(codeInput, cardInput) {
        const canonical = normalizeRendezvousCode(codeInput);
        const slotId = await deriveRendezvousSlotId(canonical);
        const salt = randomBytes(16);
        const iv = randomBytes(12);
        const keyBytes = await pbkdf2HmacSha256(utf8.encode(`gk-rv-enc-v1:${canonical}`), salt, BACKUP_PBKDF2_ITERATIONS, 32);
        const plainStr = typeof cardInput === 'string' ? cardInput : encodeContactCard(cardInput);
        const ctPlusTag = await aes256GcmEncryptRaw(keyBytes, iv, utf8.encode(plainStr), AAD_RENDEZVOUS);
        return {
            slotId,
            sealedBlob: JSON.stringify({
                v: 1,
                salt: bytesToBase64(salt),
                iv: bytesToBase64(iv),
                ct: bytesToBase64(ctPlusTag)
            })
        };
    }

    async function openRendezvousCard(codeInput, sealedBlobStr) {
        const canonical = normalizeRendezvousCode(codeInput);
        let wrapper;
        try { wrapper = JSON.parse(String(sealedBlobStr || '').trim()); } catch (_) {
            throw new Error('Malformed rendezvous payload');
        }
        if (!wrapper || wrapper.v !== 1 || !wrapper.salt || !wrapper.iv || !wrapper.ct) {
            throw new Error('Unsupported rendezvous payload version');
        }
        const salt = base64ToBytes(wrapper.salt);
        const iv = base64ToBytes(wrapper.iv);
        const ct = base64ToBytes(wrapper.ct);
        const keyBytes = await pbkdf2HmacSha256(utf8.encode(`gk-rv-enc-v1:${canonical}`), salt, BACKUP_PBKDF2_ITERATIONS, 32);
        const plainBytes = await aes256GcmDecryptRaw(keyBytes, iv, ct, AAD_RENDEZVOUS);
        return parsePeerInput(utf8d.decode(plainBytes));
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

    function buildEnvelope(type, opts = {}, extra = {}) {
        const obj = {
            v: VERSION,
            type,
            msgId: (opts.msgId && String(opts.msgId).trim()) || randomHex16(),
            ts: Date.now(),
            nonce: randomHex16()
        };
        if (opts.senderAlias && String(opts.senderAlias).trim()) obj.senderAlias = String(opts.senderAlias).trim();
        if (opts.groupId && String(opts.groupId).trim()) obj.groupId = String(opts.groupId).trim();
        if (typeof opts.ttlSeconds === 'number' && opts.ttlSeconds > 0) obj.ttl = Math.floor(opts.ttlSeconds);
        if (opts.replyTo && String(opts.replyTo).trim()) obj.replyTo = String(opts.replyTo).trim();
        Object.assign(obj, extra);
        return withPad(obj);
    }

    function buildText(text, opts = {}) {
        return buildEnvelope('text', opts, { text: String(text) });
    }

    function buildMedia(mimeType, filename, bytes, opts = {}) {
        return buildEnvelope('media', opts, {
            mimeType: String(mimeType || 'application/octet-stream'),
            filename: String(filename || 'file'),
            data: bytesToBase64(bytes)
        });
    }

    function buildGroupInvite({ groupId, groupName, groupDropId, groupReadToken, sharedKey, senderAlias, ttlSeconds, msgId }) {
        return buildEnvelope('group_invite', { groupId, senderAlias, ttlSeconds, msgId }, {
            groupName: String(groupName || 'Encrypted Group').trim(),
            groupDropId: String(groupDropId || '').trim().toLowerCase(),
            groupReadToken: String(groupReadToken || '').trim().toLowerCase(),
            sharedKey: String(sharedKey || '').trim()
        });
    }

    function buildIntroduction({ introAlias, introDropId, introArmoredKey, introSafetyCode, senderAlias, ttlSeconds, msgId }) {
        const extra = {
            introAlias: sanitizeAlias(introAlias) || 'Peer',
            introDropId: String(introDropId || '').trim().toLowerCase(),
            introArmoredKey: String(introArmoredKey || '').trim()
        };
        if (introSafetyCode && String(introSafetyCode).trim()) {
            extra.introSafetyCode = String(introSafetyCode).trim();
        }
        return buildEnvelope('introduction', { senderAlias, ttlSeconds, msgId }, extra);
    }

    function buildProfileUpdate({ profileAlias, avatarBase64, avatarHash, senderAlias, msgId }) {
        const extra = {};
        if (profileAlias && String(profileAlias).trim()) extra.profileAlias = sanitizeAlias(profileAlias);
        if (avatarHash && String(avatarHash).trim()) extra.avatarHash = String(avatarHash).trim().toLowerCase();
        if (avatarBase64 && String(avatarBase64).trim()) extra.avatarData = String(avatarBase64).trim();
        return buildEnvelope('profile_update', { senderAlias, msgId }, extra);
    }

    function buildGroupControl({ groupId, action, targetDropId, newSharedKey, senderAlias, msgId }) {
        const extra = { action: String(action || '').trim() };
        if (targetDropId && String(targetDropId).trim()) extra.targetDropId = String(targetDropId).trim().toLowerCase();
        if (newSharedKey && String(newSharedKey).trim()) extra.newSharedKey = String(newSharedKey).trim();
        return buildEnvelope('group_control', { groupId, senderAlias, msgId }, extra);
    }

    function buildClearChat({ senderAlias, msgId } = {}) {
        return buildEnvelope('clear_chat', { senderAlias, msgId }, {});
    }

    function buildContactRequest({ alias, dropId, fingerprint, publicKeyArmored, avatarBase64, avatarHash, senderAlias, msgId }) {
        const cleanAlias = sanitizeAlias(alias || senderAlias) || `Peer ${String(fingerprint || '').slice(0, 6)}`;
        const extra = {
            alias: cleanAlias,
            dropId: String(dropId || '').trim().toLowerCase(),
            fp: String(fingerprint || '').trim().toUpperCase(),
            key: String(publicKeyArmored || '').trim()
        };
        if (avatarHash && String(avatarHash).trim()) extra.avatarHash = String(avatarHash).trim().toLowerCase();
        if (avatarBase64 && String(avatarBase64).trim()) extra.avatarData = String(avatarBase64).trim();
        return buildEnvelope('contact_request', { senderAlias: sanitizeAlias(senderAlias) || cleanAlias, msgId }, extra);
    }

    function optSafeString(obj, key) {
        if (!Object.prototype.hasOwnProperty.call(obj, key) || obj[key] === null || obj[key] === undefined) return null;
        const s = String(obj[key]).trim();
        return !s || s.toLowerCase() === 'null' ? null : s;
    }

    async function parseWireAsync(input) {
        const parsed = parseWire(input);
        if (parsed && parsed.type === 'group_invite') {
            const derived = await deriveDropId(parsed.groupReadToken);
            if (derived !== parsed.groupDropId) return null;
        }
        return parsed;
    }

    function parseWire(input) {
        try {
            const text = typeof input === 'string' ? input : utf8d.decode(input);
            const obj = JSON.parse(text);
            if (!obj || typeof obj !== 'object' || obj.v !== VERSION) return null;

            const ts = typeof obj.ts === 'number' ? obj.ts : null;
            const nonce = optSafeString(obj, 'nonce');
            const msgId = optSafeString(obj, 'msgId') || nonce;
            const replyTo = optSafeString(obj, 'replyTo');
            const senderAlias = optSafeString(obj, 'senderAlias');
            const groupId = optSafeString(obj, 'groupId');
            const rawTtl = typeof obj.ttl === 'number' && obj.ttl > 0 ? Math.floor(obj.ttl) : null;
            const ttl = rawTtl !== null ? Math.min(rawTtl, 30 * 86400) : null;

            const common = { ts, nonce, msgId, replyTo, senderAlias, groupId, ttl };

            if (obj.type === 'text' && typeof obj.text === 'string') {
                return { type: 'text', text: obj.text, ...common };
            }
            if (obj.type === 'media' && typeof obj.data === 'string') {
                const rawFilename = optSafeString(obj, 'filename') || 'file';
                const filename = rawFilename.replace(/[\\/]/g, '_').replace(/\.\./g, '_');
                return {
                    type: 'media',
                    mimeType: optSafeString(obj, 'mimeType') || 'application/octet-stream',
                    filename,
                    data: obj.data,
                    ...common
                };
            }
            if (obj.type === 'group_invite') {
                const groupDropId = optSafeString(obj, 'groupDropId')?.toLowerCase();
                const groupReadToken = optSafeString(obj, 'groupReadToken')?.toLowerCase();
                const sharedKey = optSafeString(obj, 'sharedKey');
                if (!groupDropId || !groupReadToken || !sharedKey ||
                    !isValidHex32(groupDropId) || !isValidHex32(groupReadToken) ||
                    !isValidAes256Key(sharedKey)) {
                    return null;
                }
                return {
                    type: 'group_invite',
                    groupName: optSafeString(obj, 'groupName') || 'Encrypted Group',
                    groupDropId,
                    groupReadToken,
                    sharedKey,
                    ...common
                };
            }
            if (obj.type === 'introduction') {
                const introDropId = optSafeString(obj, 'introDropId')?.toLowerCase();
                const introArmoredKey = optSafeString(obj, 'introArmoredKey');
                if (!introDropId || !isValidHex32(introDropId) ||
                    !introArmoredKey || !introArmoredKey.includes('-----BEGIN PGP PUBLIC KEY BLOCK-----')) {
                    return null;
                }
                return {
                    type: 'introduction',
                    introAlias: sanitizeAlias(optSafeString(obj, 'introAlias') || 'Peer') || 'Peer',
                    introDropId,
                    introArmoredKey,
                    introSafetyCode: optSafeString(obj, 'introSafetyCode'),
                    ...common
                };
            }
            if (obj.type === 'profile_update') {
                const profileAlias = optSafeString(obj, 'profileAlias');
                const avatarHash = optSafeString(obj, 'avatarHash')?.toLowerCase() || null;
                const avatarBase64 = optSafeString(obj, 'avatarData');
                if (avatarBase64) {
                    let decoded;
                    try { decoded = base64ToBytes(avatarBase64); } catch (_) { return null; }
                    if (decoded.length > MAX_AVATAR_DECODED_BYTES) return null;
                }
                return {
                    type: 'profile_update',
                    profileAlias: profileAlias ? sanitizeAlias(profileAlias) : null,
                    avatarHash,
                    avatarBase64,
                    ...common
                };
            }
            if (obj.type === 'group_control') {
                const action = optSafeString(obj, 'action');
                if (!action || !groupId) return null;
                const newSharedKey = optSafeString(obj, 'newSharedKey');
                if (action === 'key_rotate' && !isValidAes256Key(newSharedKey)) return null;
                return {
                    type: 'group_control',
                    action,
                    targetDropId: optSafeString(obj, 'targetDropId')?.toLowerCase() || null,
                    newSharedKey,
                    ...common
                };
            }
            if (obj.type === 'clear_chat') {
                return { type: 'clear_chat', ...common };
            }
            if (obj.type === 'contact_request') {
                const reqDropId = optSafeString(obj, 'dropId')?.toLowerCase();
                const fp = optSafeString(obj, 'fp')?.toUpperCase();
                const key = optSafeString(obj, 'key');
                if (!reqDropId || !isValidHex32(reqDropId) || !fp || !key ||
                    !key.includes('-----BEGIN PGP PUBLIC KEY BLOCK-----')) {
                    return null;
                }
                const avatarHash = optSafeString(obj, 'avatarHash')?.toLowerCase() || null;
                const avatarBase64 = optSafeString(obj, 'avatarData');
                if (avatarBase64) {
                    let decoded;
                    try { decoded = base64ToBytes(avatarBase64); } catch (_) { return null; }
                    if (decoded.length > MAX_AVATAR_DECODED_BYTES) return null;
                }
                return {
                    type: 'contact_request',
                    alias: sanitizeAlias(optSafeString(obj, 'alias') || senderAlias || 'Peer') || 'Peer',
                    dropId: reqDropId,
                    fp,
                    key,
                    avatarHash,
                    avatarBase64,
                    ...common
                };
            }
            return null;
        } catch (e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- OpenPGP encrypt / decrypt

    const ENCRYPT_CONFIG = {
        preferredSymmetricAlgorithm: enums.symmetric.aes256,
        aeadProtect: false
    };

    async function encryptSigned(plaintext, peerPublicKeyArmored, myPrivateKeyArmored) {
        const peerKey = await openpgp.readKey({ armoredKey: peerPublicKeyArmored });
        const myKey = await openpgp.readPrivateKey({ armoredKey: myPrivateKeyArmored });
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
     * Decrypt OpenPGP armored message and verify against a single key or array of candidate peer public keys.
     * Backward compatible with 3rd arg as either string|null or string[].
     */
    async function decryptVerified(armoredMessage, myPrivateKeyArmored, peerKeysOrSingle) {
        const myKey = await openpgp.readPrivateKey({ armoredKey: myPrivateKeyArmored });
        const message = await openpgp.readMessage({ armoredMessage });
        const rawList = Array.isArray(peerKeysOrSingle)
            ? peerKeysOrSingle.filter(Boolean)
            : (peerKeysOrSingle ? [peerKeysOrSingle] : []);
        const parsedPeers = [];
        for (const k of rawList) {
            try { parsedPeers.push(await openpgp.readKey({ armoredKey: k })); } catch (_) {}
        }

        const result = await openpgp.decrypt({
            message,
            decryptionKeys: myKey,
            verificationKeys: parsedPeers,
            format: 'binary'
        });

        let status = 'UNSIGNED';
        let signer = null;
        if (result.signatures && result.signatures.length > 0) {
            status = 'INVALID_SIGNATURE';
            for (const sig of result.signatures) {
                try {
                    const verified = await sig.verified;
                    const packet = (await sig.signature).packets[0];
                    if (verified === true &&
                        packet.signatureType === enums.signature.binary &&
                        ACCEPTED_HASHES.includes(packet.hashAlgorithm)) {
                        status = 'VERIFIED';
                        const keyIdHex = sig.keyID ? sig.keyID.toHex().toUpperCase() : '';
                        const matched = parsedPeers.find((pk) => {
                            const fp = pk.getFingerprint().toUpperCase();
                            return fp.endsWith(keyIdHex) || pk.getKeyIDs().some((id) => id.toHex().toUpperCase() === keyIdHex);
                        });
                        signer = matched ? matched.getFingerprint().toUpperCase() : (parsedPeers[0]?.getFingerprint().toUpperCase() || null);
                        break;
                    }
                } catch (_) {
                    // keep trying other candidate signatures if any
                }
            }
        }
        return { bytes: result.data, signatureStatus: status, signerFingerprint: signer };
    }

    // ---------------------------------------------------------------- encrypted .gkbackup (Android SecureStore parity)

    async function exportEncryptedBackup(stateSnapshot, passphrase) {
        if (!passphrase || String(passphrase).length < 6) {
            throw new Error('Backup passphrase must be at least 6 characters');
        }
        const id = stateSnapshot.identity;
        if (!id) throw new Error('No identity to back up');
        const contacts = Array.isArray(stateSnapshot.contacts) ? stateSnapshot.contacts : [];
        const groups = Array.isArray(stateSnapshot.groups) ? stateSnapshot.groups : [];
        const firstPeer = contacts[0] || null;
        const payloadObj = {
            privateKeyArmored: id.privateKeyArmored,
            publicKeyArmored: id.publicKeyArmored,
            fingerprint: id.fingerprint,
            dropId: id.dropId,
            readToken: id.readToken,
            userAlias: stateSnapshot.userAlias || '',
            relayUrl: stateSnapshot.relayUrl || '',
            contacts: contacts.map((c) => ({
                id: c.dropId,
                alias: c.alias,
                dropId: c.dropId,
                publicKeyArmored: c.publicKeyArmored,
                fingerprint: c.fingerprint,
                addedAt: c.addedAt || Date.now(),
                isVerified: Boolean(c.isVerified),
                keyChangedWarning: Boolean(c.keyChangedWarning),
                isPinned: Boolean(c.isPinned),
                isMuted: Boolean(c.isMuted),
                isArchived: Boolean(c.isArchived),
                ...(c.safetyCode ? { safetyCode: c.safetyCode } : {}),
                ...(c.disappearingTtlSeconds ? { disappearingTtlSeconds: c.disappearingTtlSeconds } : {}),
                ...(c.avatarBase64 ? { avatarBase64: c.avatarBase64 } : {}),
                ...(c.avatarHash ? { avatarHash: c.avatarHash } : {})
            })),
            groups: groups.map((g) => ({
                id: g.id,
                name: g.name,
                groupDropId: g.groupDropId,
                groupReadToken: g.groupReadToken,
                sharedKeyBase64: g.sharedKeyBase64,
                members: (g.members || []).map((m) => ({
                    dropId: m.dropId,
                    alias: m.alias,
                    publicKeyArmored: m.publicKeyArmored,
                    fingerprint: m.fingerprint
                })),
                createdAt: g.createdAt || Date.now(),
                isAdmin: Boolean(g.isAdmin),
                isPinned: Boolean(g.isPinned),
                isMuted: Boolean(g.isMuted),
                isArchived: Boolean(g.isArchived)
            }))
        };
        if (stateSnapshot.myAvatarBase64) payloadObj.myAvatarBase64 = stateSnapshot.myAvatarBase64;
        if (stateSnapshot.myAvatarHash) payloadObj.myAvatarHash = stateSnapshot.myAvatarHash;
        if (firstPeer) {
            payloadObj.peerPublicKeyArmored = firstPeer.publicKeyArmored;
            payloadObj.peerFingerprint = firstPeer.fingerprint;
            payloadObj.peerDropId = firstPeer.dropId;
        }

        const salt = randomBytes(16);
        const iv = randomBytes(12);
        const keyBytes = await pbkdf2HmacSha256(utf8.encode(String(passphrase)), salt, BACKUP_PBKDF2_ITERATIONS, 32);
        const ctPlusTag = await aes256GcmEncryptRaw(keyBytes, iv, utf8.encode(JSON.stringify(payloadObj)), AAD_BACKUP);
        return JSON.stringify({
            v: 1,
            salt: bytesToBase64(salt),
            iv: bytesToBase64(iv),
            ct: bytesToBase64(ctPlusTag)
        });
    }

    async function importEncryptedBackup(backupJsonStr, passphrase) {
        if (!passphrase) throw new Error('Passphrase is required');
        let wrapper;
        try { wrapper = JSON.parse(String(backupJsonStr || '').trim()); } catch (_) {
            throw new Error('Invalid backup file format');
        }
        if (!wrapper || wrapper.v !== 1 || !wrapper.salt || !wrapper.iv || !wrapper.ct) {
            throw new Error('Unsupported backup format version');
        }
        const salt = base64ToBytes(wrapper.salt);
        const iv = base64ToBytes(wrapper.iv);
        const ct = base64ToBytes(wrapper.ct);
        const keyBytes = await pbkdf2HmacSha256(utf8.encode(String(passphrase)), salt, BACKUP_PBKDF2_ITERATIONS, 32);
        const plainBytes = await aes256GcmDecryptRaw(keyBytes, iv, ct, AAD_BACKUP);
        const parsed = JSON.parse(utf8d.decode(plainBytes));
        if (!parsed.publicKeyArmored || !parsed.privateKeyArmored || !parsed.dropId || !parsed.readToken) {
            throw new Error('Backup is missing identity keys');
        }
        const contacts = Array.isArray(parsed.contacts) ? parsed.contacts.slice() : [];
        if (contacts.length === 0 && parsed.peerPublicKeyArmored && parsed.peerDropId) {
            contacts.push({
                id: parsed.peerDropId,
                alias: `Peer ${String(parsed.peerFingerprint || '').slice(0, 6)}`,
                dropId: parsed.peerDropId,
                publicKeyArmored: parsed.peerPublicKeyArmored,
                fingerprint: parsed.peerFingerprint || '',
                addedAt: Date.now()
            });
        }
        const groups = Array.isArray(parsed.groups) ? parsed.groups : [];
        return {
            identity: {
                privateKeyArmored: parsed.privateKeyArmored,
                publicKeyArmored: parsed.publicKeyArmored,
                fingerprint: String(parsed.fingerprint || '').toUpperCase(),
                dropId: String(parsed.dropId).toLowerCase(),
                readToken: String(parsed.readToken).toLowerCase()
            },
            userAlias: parsed.userAlias || '',
            relayUrl: parsed.relayUrl || '',
            myAvatarBase64: parsed.myAvatarBase64 || null,
            myAvatarHash: parsed.myAvatarHash || null,
            contacts,
            groups
        };
    }

    // ---------------------------------------------------------------- safety code

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
        randomHex16,
        isValidHex32,
        isValidAes256Key,
        sanitizeAlias,
        deriveDropId,
        bytesToHex,
        bytesToBase64,
        base64ToBytes,
        sha256,
        sha256Fallback,
        hmacSha256Fallback,
        pbkdf2HmacSha256Fallback,
        pbkdf2HmacSha256,
        aes256GcmEncryptFallback,
        aes256GcmDecryptFallback,
        generateGroupKey,
        encryptGroup,
        decryptGroup,
        generateIdentity,
        parsePeerKey,
        parsePeerInput,
        encodeContactCard,
        generateRendezvousCode,
        normalizeRendezvousCode,
        extractRendezvousCode,
        deriveRendezvousSlotId,
        sealRendezvousCard,
        openRendezvousCard,
        buildText,
        buildMedia,
        buildGroupInvite,
        buildIntroduction,
        buildProfileUpdate,
        buildGroupControl,
        buildClearChat,
        buildContactRequest,
        parseWire,
        parseWireAsync,
        encryptSigned,
        decryptVerified,
        exportEncryptedBackup,
        importEncryptedBackup,
        safetyCode
    };
}
