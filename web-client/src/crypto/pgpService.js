import * as openpgp from 'openpgp';

/**
 * OpenPGP Cryptographic Service for GhostKey Web Companion
 */

export async function deriveMailboxHash(fingerprintOrId, salt = 'ghostkey-blind-relay-v1') {
  const enc = new TextEncoder();
  const data = enc.encode(`${fingerprintOrId.trim()}:${salt}`);
  const hashBuffer = await crypto.subtle.digest('SHA-256', data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
}

export async function generateNewKeyPair(name, email, passphrase = '') {
  const { privateKey, publicKey } = await openpgp.generateKey({
    type: 'ecc', // Curve25519 (Fast, state-of-the-art security, small key size)
    curve: 'curve25519',
    userIDs: [{ name, email }],
    passphrase: passphrase || undefined
  });

  const parsedKey = await openpgp.readKey({ armoredKey: publicKey });
  const fingerprint = parsedKey.getFingerprint();

  return {
    privateKeyArmored: privateKey,
    publicKeyArmored: publicKey,
    fingerprint: fingerprint
  };
}

export async function encryptMessage(plaintext, recipientPublicKeyArmored) {
  const publicKey = await openpgp.readKey({ armoredKey: recipientPublicKeyArmored.trim() });
  
  // Anti-traffic-analysis: Add random stealth padding
  const paddingLength = Math.floor(Math.random() * 64) + 16;
  const paddedPlaintext = `${plaintext}\n<!-- padding: ${'#'.repeat(paddingLength)} -->`;

  const encrypted = await openpgp.encrypt({
    message: await openpgp.createMessage({ text: paddedPlaintext }),
    encryptionKeys: publicKey
  });

  return encrypted;
}

export async function decryptMessage(ciphertextArmored, privateKeyArmored, passphrase = '') {
  let privateKey = await openpgp.readPrivateKey({ armoredKey: privateKeyArmored.trim() });
  
  if (passphrase) {
    privateKey = await openpgp.decryptKey({
      privateKey,
      passphrase
    });
  }

  const message = await openpgp.readMessage({ armoredMessage: ciphertextArmored.trim() });
  const { data: decrypted } = await openpgp.decrypt({
    message,
    decryptionKeys: privateKey
  });

  // Strip stealth padding comment
  const clean = decrypted.replace(/\n<!-- padding: #+ -->$/, '');
  return clean;
}
