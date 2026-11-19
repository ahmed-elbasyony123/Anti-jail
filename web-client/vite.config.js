import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The client shares its protocol code, UI markup and controller with the page the relay serves
// (../server/public), so Vite is allowed to read from the parent folder.
// In development, /api (including the WebSocket) is proxied to a local relay so that the page and
// the relay share one origin; the relay itself does not send CORS headers.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
    fs: { allow: ['..'] },
    proxy: {
      '/api': { target: 'http://127.0.0.1:8765', ws: true, changeOrigin: false }
    }
  }
})
