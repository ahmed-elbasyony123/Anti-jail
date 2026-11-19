import * as openpgp from 'openpgp'
import { createCore } from '../../../server/public/ghostkey-core.mjs'

/**
 * OpenPGP service for the GhostKey web client, wire protocol v2.
 *
 * The logic is shared with the page served by the relay (server/public) so both web clients behave
 * exactly like the Android app: Ed25519 + X25519 identity with a random dropId and readToken,
 * signed-then-encrypted messages, signature verification against the stored peer key, and the
 * unified v2 JSON plaintext.
 */
export const core = createCore(openpgp)

export const {
  generateIdentity,
  parsePeerKey,
  encryptSigned,
  decryptVerified,
  buildText,
  buildMedia,
  parseWire,
  safetyCode,
  base64ToBytes
} = core
