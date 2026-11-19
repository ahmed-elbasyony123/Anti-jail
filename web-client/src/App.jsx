import { useEffect } from 'react'
import * as openpgp from 'openpgp'
import { startApp } from '../../server/public/app.js'

/**
 * React host for GhostKey web console.
 * Rendered using standard JSX without dangerouslySetInnerHTML to eliminate XSS vectors (Issue N1).
 */
export default function App() {
  useEffect(() => {
    const app = startApp({
      document,
      window,
      openpgp,
      fetch: window.fetch.bind(window),
      WebSocket: window.WebSocket,
      URL: window.URL,
      Blob: window.Blob,
      crypto: window.crypto
    })
    return () => app.dispose()
  }, [])

  return (
    <>
      <div id="layout">
        {/* Left: identity, peer, status */}
        <aside id="sidebar">
          <div className="brand-row">
            <span className="brand">GHOSTKEY</span>
            <button id="panic-btn" className="btn danger small" type="button" title="Drop keys, messages and connections from this tab">WIPE</button>
          </div>

          <section className="card">
            <div className="card-title">MY IDENTITY</div>
            <div className="label">Fingerprint</div>
            <div id="my-fingerprint" className="mono-box">Generating keys...</div>
            <div className="label">Drop-ID (where peers send to you)</div>
            <div id="my-dropid" className="mono-box small-text">-</div>
            <div className="label">My public key (share this, it includes the Drop-ID)</div>
            <textarea id="my-pubkey" className="key-box" readOnly rows={4} spellCheck={false}></textarea>
            <div className="row">
              <button id="copy-key-btn" className="btn small" type="button">COPY KEY</button>
              <button id="new-identity-btn" className="btn small ghost" type="button">NEW IDENTITY</button>
            </div>
          </section>

          <section className="card">
            <div className="card-title">PEER</div>
            <div id="peer-summary" className="peer-summary none">No peer key saved yet.</div>
            <div className="label">Paste your peer's public key</div>
            <textarea id="peer-key-input" className="key-box" rows={4} spellCheck={false} placeholder="-----BEGIN PGP PUBLIC KEY BLOCK-----"></textarea>
            <div className="row">
              <button id="save-peer-btn" className="btn small" type="button">SAVE PEER KEY</button>
            </div>
            <div id="peer-error" className="error-text hidden"></div>
          </section>

          <section className="card">
            <div className="status-row">
              <span className="muted">Relay connection</span>
              <span className="status"><span id="relay-dot" className="dot off"></span><span id="relay-status-text">OFFLINE</span></span>
            </div>
            <div className="status-row">
              <span className="muted">Storage</span>
              <span className="accent">this tab's memory only</span>
            </div>
            <p id="relay-hint" className="hint"></p>
          </section>

          <div className="footer-note">GhostKey web console &middot; protocol v2</div>
        </aside>

        {/* Center: chat */}
        <main id="chat">
          <header id="chat-header">
            <div>
              <span id="chat-title">Encrypted chat</span>
              <span className="chip">OpenPGP &middot; Curve25519 &middot; signed</span>
            </div>
            <button id="safety-btn" className="btn small ghost" type="button">SAFETY CODE</button>
          </header>

          <div id="messages-container"></div>

          <footer id="composer">
            <div id="attachment-bar" className="hidden">
              <span id="attachment-name" className="attachment-name">file</span>
              <span id="attachment-size" className="muted"></span>
              <button id="attachment-clear" className="link-btn" type="button">remove</button>
            </div>
            <form id="chat-form" autoComplete="off">
              <input type="file" id="media-file-input" className="hidden" accept="image/*,audio/*,video/*,.pdf,.zip,.txt,.doc,.docx" />
              <button id="attach-btn" className="btn ghost" type="button" title="Attach a file (max 15 MB)">ATTACH</button>
              <input type="text" id="message-input" placeholder="Type a message..." autoComplete="off" />
              <button id="send-btn" className="btn" type="submit">SEND</button>
            </form>
          </footer>
        </main>

        {/* Right: activity log */}
        <aside id="logs-panel">
          <div className="logs-head">
            <span>ACTIVITY LOG</span>
            <button id="clear-logs-btn" className="link-btn" type="button">clear</button>
          </div>
          <div id="logs-container"></div>
          <p className="hint small-pad">Messages are stored on the relay (encrypted) until your browser confirms receipt. This page keeps keys and messages only in memory and forgets them when the tab closes.</p>
        </aside>
      </div>

      {/* Safety code dialog */}
      <div id="safety-modal" className="modal hidden">
        <div className="modal-card">
          <div className="modal-head">
            <span>SAFETY CODE</span>
            <button id="safety-close-x" className="link-btn" type="button">close</button>
          </div>
          <p className="hint">Compare these 30 digits with your peer over a channel you trust, such as a phone call or in person. If they match, the key you saved is very likely your peer's real key and was not swapped on the way. If they differ, do not trust the key.</p>
          <div id="safety-code-digits" className="mono-box center big">-</div>
          <div className="row end">
            <button id="safety-close" className="btn small" type="button">CLOSE</button>
          </div>
        </div>
      </div>
    </>
  )
}
