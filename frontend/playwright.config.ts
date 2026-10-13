import { defineConfig } from '@playwright/test'

// End-to-end tests (DESIGN.md §15): the production build (`vite preview`, with the production
// security headers) against the local backend + Oracle with the fictional sample data.
// Start Docker + the backend first, then `npm run e2e`. Uses the installed Microsoft Edge,
// so no browser download is needed.
// E2E_BASE_URL runs the same tests against another, already running site instead (e.g. the
// Cloudflare Pages runtime from `wrangler pages dev`, DESIGN.md §16); E2E_PASSWORD then gives its
// sample-login password.
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:4173'

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 10_000 },
  // One shared local database and per-IP rate limits: run the flows one after another.
  workers: 1,
  fullyParallel: false,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    channel: 'msedge',
    viewport: { width: 1280, height: 900 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run build && npm run preview',
        url: 'http://localhost:4173',
        reuseExistingServer: true,
        timeout: 180_000,
      },
})
