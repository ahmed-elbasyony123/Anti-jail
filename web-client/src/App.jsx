import { useEffect } from 'react'
import * as openpgp from 'openpgp'
import { startApp } from '../../server/public/app.js'
import shellHtml from '../../server/public/index.html?raw'

// The markup is the relay page's own static file (our code, not user input), minus its <script> tags.
const shell = (() => {
  const doc = new DOMParser().parseFromString(shellHtml, 'text/html')
  doc.querySelectorAll('script, meta, link, title').forEach((n) => n.remove())
  return doc.body.innerHTML
})()

/**
 * Thin React host for the shared GhostKey controller (server/public/app.js). Keeping one
 * controller means this client and the relay-served page cannot drift apart on protocol v2:
 * dropId + readToken auth, explicit ACK, message signing and verification, peer key import.
 * Everything stays in memory; nothing is written to storage.
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

  return <div dangerouslySetInnerHTML={{ __html: shell }} />
}
