/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        stealth: {
          bg: '#0A0D0F',
          surface: '#12171D',
          panel: '#1A2129',
          border: '#28333E',
          accent: '#00FF9D',
          cyan: '#00E5FF',
          crimson: '#FF3366',
          amber: '#FFB800',
          text: '#E6EDF3',
          muted: '#8B949E'
        }
      },
      fontFamily: {
        mono: ['"JetBrains Mono"', 'Menlo', 'Monaco', 'Courier New', 'monospace']
      }
    },
  },
  plugins: [],
}
