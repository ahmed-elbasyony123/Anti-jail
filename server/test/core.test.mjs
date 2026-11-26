// Tests for the shared web core (server/public/ghostkey-core.mjs). Run: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto, createHash, randomBytes, createCipheriv, pbkdf2Sync } from 'node:crypto';
import * as openpgp from 'openpgp';
import { createCore } from '../public/ghostkey-core.mjs';

const core = createCore(openpgp, { crypto: webcrypto });
const utf8 = new TextEncoder();
const utf8d = new TextDecoder();

test('SHA-256 fallback matches the platform implementation', () => {
    for (const len of [0, 1, 3, 55, 56, 57, 63, 64, 65, 119, 120, 128, 1000, 100000]) {
        const data = new Uint8Array(randomBytes(len));
        const expected = createHash('sha256').update(data).digest('hex');
        const got = Array.from(core.sha256Fallback(data)).map((b) => b.toString(16).padStart(2, '0')).join('');
        assert.equal(got, expected, `length ${len}`);
    }
    assert.equal(
        Array.from(core.sha256Fallback(utf8.encode('abc'))).map((b) => b.toString(16).padStart(2, '0')).join(''),
        'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
    );
});

test('safety code equals the Android implementation (reference vectors) and is order independent', async () => {
    const a = '0123456789ABCDEF0123456789ABCDEF01234567';
    const b = 'FEDCBA9876543210FEDCBA9876543210FEDCBA98';
    assert.equal(await core.safetyCode(a, b), '43310 31853 61229 28963 49561 49270');
    assert.equal(await core.safetyCode(b.toLowerCase(), a), '43310 31853 61229 28963 49561 49270');
    assert.equal(await core.safetyCode(null, a), null);
});

test('identity: Ed25519 + X25519 v4 key, SHA-2 signatures, dropId bound into User ID and armor comment', async () => {
    const id = await core.generateIdentity();
    assert.ok(core.isValidHex32(id.dropId) && core.isValidHex32(id.readToken) && id.dropId !== id.readToken);
    assert.match(id.publicKeyArmored, new RegExp(`^Comment: Drop-ID: ${id.dropId}$`, 'm'));
    assert.ok(!id.publicKeyArmored.includes(id.readToken) && !id.privateKeyArmored.includes(id.readToken));
    assert.match(id.fingerprint, /^[0-9A-F]{40}$/);

    const key = await openpgp.readKey({ armoredKey: id.publicKeyArmored });
    assert.equal(key.keyPacket.version, 4);
    assert.equal(openpgp.enums.read(openpgp.enums.publicKey, key.keyPacket.algorithm), 'eddsaLegacy');
    assert.equal(openpgp.enums.read(openpgp.enums.publicKey, key.subkeys[0].keyPacket.algorithm), 'ecdh');
    const selfSig = key.users[0].selfCertifications[0];
    const strong = [openpgp.enums.hash.sha256, openpgp.enums.hash.sha384, openpgp.enums.hash.sha512];
    assert.ok(strong.includes(selfSig.hashAlgorithm));
    assert.ok(strong.includes(key.subkeys[0].bindingSignatures[0].hashAlgorithm));
});

test('parsePeerKey: accepts a good key, rejects swapped Drop-ID, private keys, revoked keys', async () => {
    const a = await core.generateIdentity();
    const b = await core.generateIdentity();
    const info = await core.parsePeerKey('  \n' + a.publicKeyArmored + '\n ');
    assert.equal(info.fingerprint, a.fingerprint);
    assert.equal(info.dropId, a.dropId);

    await assert.rejects(core.parsePeerKey(a.publicKeyArmored.replace(a.dropId, b.dropId)), /does not match/);
    const stripped = a.publicKeyArmored.split('\n').filter((l) => !l.startsWith('Comment:')).join('\n');
    assert.equal((await core.parsePeerKey(stripped)).dropId, a.dropId, 'falls back to the certified User ID');
    await assert.rejects(core.parsePeerKey(a.privateKeyArmored), /private key/i);
    await assert.rejects(core.parsePeerKey('not a key'));

    const priv = await openpgp.readPrivateKey({ armoredKey: a.privateKeyArmored });
    const { publicKey: revoked } = await openpgp.revokeKey({ key: priv, format: 'armored' });
    await assert.rejects(core.parsePeerKey(revoked), /revoked|invalid/i);
});

test('signed round trip: VERIFIED, wrong peer INVALID, no peer INVALID, unsigned UNSIGNED', async () => {
    const alice = await core.generateIdentity();
    const bob = await core.generateIdentity();
    const mallory = await core.generateIdentity();
    const payload = core.buildText('héllo ✓ "quoted" / slash');

    const ct = await core.encryptSigned(payload, bob.publicKeyArmored, alice.privateKeyArmored);
    assert.match(ct, /^-----BEGIN PGP MESSAGE-----/);

    const ok = await core.decryptVerified(ct, bob.privateKeyArmored, alice.publicKeyArmored);
    assert.deepEqual(Array.from(ok.bytes), Array.from(payload));
    assert.equal(ok.signatureStatus, 'VERIFIED');
    assert.equal(ok.signerFingerprint, alice.fingerprint);
    assert.equal(core.parseWire(ok.bytes).text, 'héllo ✓ "quoted" / slash');

    // the message signature itself uses a SHA-2 hash and is a binary-document signature
    const raw = await openpgp.decrypt({
        message: await openpgp.readMessage({ armoredMessage: ct }),
        decryptionKeys: await openpgp.readPrivateKey({ armoredKey: bob.privateKeyArmored }),
        verificationKeys: await openpgp.readKey({ armoredKey: alice.publicKeyArmored }),
        format: 'binary'
    });
    const sigPacket = (await raw.signatures[0].signature).packets[0];
    assert.ok([openpgp.enums.hash.sha256, openpgp.enums.hash.sha384, openpgp.enums.hash.sha512].includes(sigPacket.hashAlgorithm));
    assert.equal(sigPacket.signatureType, openpgp.enums.signature.binary);

    const wrong = await core.decryptVerified(ct, bob.privateKeyArmored, mallory.publicKeyArmored);
    assert.equal(wrong.signatureStatus, 'INVALID_SIGNATURE');
    assert.equal(wrong.signerFingerprint, null);
    const noPeer = await core.decryptVerified(ct, bob.privateKeyArmored, null);
    assert.equal(noPeer.signatureStatus, 'INVALID_SIGNATURE');

    const bobPub = await openpgp.readKey({ armoredKey: bob.publicKeyArmored });
    const unsigned = await openpgp.encrypt({
        message: await openpgp.createMessage({ binary: payload }), encryptionKeys: bobPub, format: 'armored'
    });
    const uns = await core.decryptVerified(unsigned, bob.privateKeyArmored, alice.publicKeyArmored);
    assert.equal(uns.signatureStatus, 'UNSIGNED');

    await assert.rejects(core.decryptVerified(ct, mallory.privateKeyArmored, alice.publicKeyArmored));
});

test('wire format v2: padding buckets, media round trip, strict parsing', async () => {
    const t = core.buildText('x');
    assert.equal(t.length % 512, 0);
    assert.equal(core.buildText('a'.repeat(5)).length, core.buildText('b'.repeat(20)).length, 'same bucket');
    assert.notEqual(utf8d.decode(core.buildText('a')), utf8d.decode(core.buildText('a')), 'pad is random');
    assert.match(utf8d.decode(t), /"v":2/);

    const bytes = new Uint8Array(randomBytes(100000));
    const m = core.buildMedia('image/png', 'a b.png', bytes);
    assert.equal(m.length % (16 * 1024), 0);
    const p = core.parseWire(m);
    assert.equal(p.type, 'media');
    assert.equal(p.mimeType, 'image/png');
    assert.equal(p.filename, 'a b.png');
    assert.deepEqual(Array.from(core.base64ToBytes(p.data)), Array.from(bytes));
    assert.ok(!utf8d.decode(m).includes('data:image'), 'no data: prefix');

    assert.equal(core.parseWire('hello'), null);
    assert.equal(core.parseWire('{"type":"text","text":"x"}'), null, 'v1 style rejected');
    assert.equal(core.parseWire('{"v":3,"type":"text","text":"x"}'), null);
    assert.equal(core.parseWire('{"v":2,"type":"other"}'), null);
    assert.equal(core.parseWire('{"v":2,"type":"group_control"}'), null, 'group_control requires groupId & action');
    const pu = core.parseWire(core.buildProfileUpdate({ profileAlias: 'Alice', senderAlias: 'Alice' }));
    assert.equal(pu.type, 'profile_update');
    assert.equal(pu.profileAlias, 'Alice');
});

test('AES-256-GCM fallback matches Node crypto ciphertexts across edge lengths', () => {
    for (const len of [0, 1, 15, 16, 17, 63, 64, 250]) {
        const key = new Uint8Array(randomBytes(32));
        const iv = new Uint8Array(randomBytes(12));
        const plain = new Uint8Array(randomBytes(len));
        const aad = new Uint8Array(utf8.encode('ghostkey-backup-v1'));

        const cipher = createCipheriv('aes-256-gcm', key, iv);
        cipher.setAAD(aad);
        const enc = Buffer.concat([cipher.update(plain), cipher.final(), cipher.getAuthTag()]);

        const got = core.aes256GcmEncryptFallback(key, iv, plain, aad);
        assert.deepEqual(Buffer.from(got), enc, `GCM encrypt len=${len}`);
        const dec = core.aes256GcmDecryptFallback(key, iv, got, aad);
        assert.deepEqual(Buffer.from(dec), Buffer.from(plain), `GCM decrypt len=${len}`);
    }
    const key = new Uint8Array(randomBytes(32));
    const iv = new Uint8Array(randomBytes(12));
    const ct = core.aes256GcmEncryptFallback(key, iv, utf8.encode('secret'));
    ct[ct.length - 1] ^= 0xff;
    assert.throws(() => core.aes256GcmDecryptFallback(key, iv, ct), /tag mismatch/);
});

test('PBKDF2 fallback matches Node crypto and .gkbackup round-trips', async () => {
    const pass = utf8.encode('correct horse battery');
    const salt = new Uint8Array(randomBytes(16));
    assert.deepEqual(
        Buffer.from(core.pbkdf2HmacSha256Fallback(pass, salt, 200, 32)),
        pbkdf2Sync(pass, salt, 200, 32, 'sha256')
    );

    const id = await core.generateIdentity();
    const peer = await core.generateIdentity();
    const grpKey = core.generateGroupKey();
    const gToken = core.randomHex32();
    const gDrop = await core.deriveDropId(gToken);
    const backupStr = await core.exportEncryptedBackup({
        identity: id,
        userAlias: 'Alice',
        relayUrl: 'http://test.onion',
        contacts: [{ dropId: peer.dropId, alias: 'Bob', publicKeyArmored: peer.publicKeyArmored, fingerprint: peer.fingerprint }],
        groups: [{ id: 'g1', name: 'Ops', groupDropId: gDrop, groupReadToken: gToken, sharedKeyBase64: grpKey, members: [] }]
    }, 'strong-pass-123');

    const restored = await core.importEncryptedBackup(backupStr, 'strong-pass-123');
    assert.equal(restored.identity.dropId, id.dropId);
    assert.equal(restored.identity.readToken, id.readToken);
    assert.equal(restored.contacts.length, 1);
    assert.equal(restored.groups[0].sharedKeyBase64, grpKey);
    await assert.rejects(core.importEncryptedBackup(backupStr, 'wrong-pass-123'));
});

test('group AES-256-GCM messages, invites, Contact Card JSON, replyTo & intro round-trip', async () => {
    const alice = await core.generateIdentity();
    const bob = await core.generateIdentity();
    const cardJson = core.encodeContactCard({
        alias: 'Alice',
        dropId: alice.dropId,
        fingerprint: alice.fingerprint,
        publicKeyArmored: alice.publicKeyArmored
    });
    const parsedCard = await core.parsePeerInput(cardJson);
    assert.equal(parsedCard.dropId, alice.dropId);
    assert.equal(parsedCard.alias, 'Alice');

    const grpKey = core.generateGroupKey();
    const gToken = core.randomHex32();
    const gDrop = await core.deriveDropId(gToken);
    const inviteBytes = core.buildGroupInvite({
        groupId: 'grp-1',
        groupName: 'Alpha',
        groupDropId: gDrop,
        groupReadToken: gToken,
        sharedKey: grpKey,
        senderAlias: 'Alice'
    });
    const parsedInvite = await core.parseWireAsync(inviteBytes);
    assert.equal(parsedInvite.type, 'group_invite');
    assert.equal(parsedInvite.groupName, 'Alpha');

    const badInvite = core.buildGroupInvite({
        groupId: 'grp-1',
        groupName: 'Fake',
        groupDropId: bob.dropId,
        groupReadToken: gToken,
        sharedKey: grpKey
    });
    assert.equal(await core.parseWireAsync(badInvite), null, 'mismatched groupDropId vs readToken rejected');

    const msgBytes = core.buildText('hi group', { senderAlias: 'Alice', groupId: 'grp-1', replyTo: 'm0', ttlSeconds: 60 });
    const ct = await core.encryptGroup(msgBytes, grpKey);
    const pt = await core.decryptGroup(ct, grpKey);
    const parsedMsg = core.parseWire(pt);
    assert.equal(parsedMsg.text, 'hi group');
    assert.equal(parsedMsg.replyTo, 'm0');
    assert.equal(parsedMsg.ttl, 60);

    const clrBytes = core.buildClearChat({ senderAlias: 'Alice' });
    const parsedClr = core.parseWire(clrBytes);
    assert.equal(parsedClr.type, 'clear_chat');
    assert.equal(parsedClr.senderAlias, 'Alice');
});

test('rendezvous seal/open, link extraction, and signed contact_request round-trip', async () => {
    const alice = await core.generateIdentity();
    const bob = await core.generateIdentity();

    const code = core.generateRendezvousCode();
    assert.match(code, /^[2-9A-HJ-NP-Z]{5}-[2-9A-HJ-NP-Z]{5}$/);
    assert.equal(core.extractRendezvousCode(`https://example.onion/#pair=${code}`), code.replace('-', ''));
    assert.equal(core.extractRendezvousCode(`ghostkey://pair?c=${code.toLowerCase()}`), code.replace('-', ''));

    const card = core.encodeContactCard({
        alias: 'Alice',
        dropId: alice.dropId,
        fingerprint: alice.fingerprint,
        publicKeyArmored: alice.publicKeyArmored
    });
    const sealed = await core.sealRendezvousCard(code, card);
    assert.match(sealed.slotId, /^[0-9a-f]{64}$/);
    const opened = await core.openRendezvousCard(code.toLowerCase(), sealed.sealedBlob);
    assert.equal(opened.dropId, alice.dropId);
    assert.equal(opened.fingerprint, alice.fingerprint);
    assert.equal(opened.alias, 'Alice');

    await assert.rejects(core.openRendezvousCard(core.generateRendezvousCode(), sealed.sealedBlob));

    const reqBytes = core.buildContactRequest({
        alias: 'Bob',
        dropId: bob.dropId,
        fingerprint: bob.fingerprint,
        publicKeyArmored: bob.publicKeyArmored
    });
    const parsedReq = core.parseWire(reqBytes);
    assert.equal(parsedReq.type, 'contact_request');
    assert.equal(parsedReq.dropId, bob.dropId);
    assert.equal(parsedReq.fp, bob.fingerprint);
    assert.equal(parsedReq.alias, 'Bob');
});
