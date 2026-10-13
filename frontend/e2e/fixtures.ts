import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { test as base, expect, type Page } from '@playwright/test'

export { expect }

/**
 * Every test fails if the browser reports a Content-Security-Policy violation or a script error,
 * so a new third-party resource can't slip in without updating security-headers.ts.
 */
export const test = base.extend<{ browserErrors: string[] }>({
  browserErrors: [
    async ({ context }, use) => {
      const errors: string[] = []
      await context.addInitScript(() => {
        document.addEventListener('securitypolicyviolation', (e: SecurityPolicyViolationEvent) =>
          console.error(`CSP violation: ${e.violatedDirective} blocked ${e.blockedURI || 'inline'} (${e.sourceFile}:${e.lineNumber})`),
        )
      })
      context.on('console', (m) => {
        if (m.type() === 'error') errors.push(`${m.text()} @ ${m.location().url}`)
      })
      context.on('weberror', (e) => errors.push(e.error().message))
      await use(errors)
      expect(errors, 'browser errors / CSP violations').toEqual([])
    },
    { auto: true },
  ],
})

/** Password of the local sample logins: E2E_PASSWORD, else DEV_SAMPLE_PASSWORD from backend/.env. */
export function samplePassword(): string {
  if (process.env.E2E_PASSWORD) return process.env.E2E_PASSWORD
  const env = readFileSync(resolve(process.cwd(), '../backend/.env'), 'utf8')
  const line = env.split(/\r?\n/).find((l) => l.startsWith('DEV_SAMPLE_PASSWORD='))
  if (!line) throw new Error('Set E2E_PASSWORD or DEV_SAMPLE_PASSWORD in backend/.env')
  return line.slice('DEV_SAMPLE_PASSWORD='.length).replace(/^"|"$/g, '')
}

export async function login(page: Page, username: string): Promise<void> {
  await page.goto('/staff/login')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(samplePassword())
  await page.getByRole('button', { name: 'Log in' }).click()
  await expect(page).not.toHaveURL(/\/staff\/login/)
}

/** A fresh mobile number per test: the 3-bookings-per-mobile-per-day rule spans test runs. */
export function randomMobile(): string {
  return '98' + String(Math.floor(Math.random() * 1e8)).padStart(8, '0')
}

/**
 * With a Turnstile site key (frontend/.env.local) the real widget runs; Cloudflare's test key
 * passes by itself after a moment. Waits until every widget on the page has its token.
 * Without a site key there is no widget and nothing to wait for.
 */
export async function waitForCaptcha(page: Page): Promise<void> {
  await page.waitForTimeout(500)   // a widget mounted by the click before this one
  // Each captcha box (data-testid="captcha") gets its token input only once Cloudflare's script
  // has loaded, which can take seconds; waiting per box never mistakes "not loaded yet" for "none".
  for (const box of await page.getByTestId('captcha').all()) {
    await expect(box.locator('input[name="cf-turnstile-response"]')).not.toHaveValue('', { timeout: 20_000 })
  }
}

/** Fails if the page is wider than the window (sideways scrolling on phones). */
export async function expectNoSidewaysScroll(page: Page): Promise<void> {
  const { scroll, client } = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }))
  expect(scroll, `page width on ${page.url()}`).toBeLessThanOrEqual(client)
}
