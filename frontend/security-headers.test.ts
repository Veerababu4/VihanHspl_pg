import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { contentSecurityPolicy, websiteSecurityHeaders } from './security-headers.ts'

// Tests run in frontend/.
const caddyfile = readFileSync(resolve(process.cwd(), '../deploy/Caddyfile'), 'utf8')
const pagesHeaders = readFileSync(resolve(process.cwd(), 'public/_headers'), 'utf8')

/** The value of `Name "value"` inside the Caddyfile's website header block. */
function caddyHeader(name: string): string | undefined {
  return new RegExp(`^\\s*${name} "([^"]*)"`, 'm').exec(caddyfile)?.[1]
}

/** The value of `  Name: value` in the `/*` block of Cloudflare Pages' _headers file. */
function pagesHeader(name: string): string | undefined {
  const all = pagesHeaders.split(/^\/assets\/\*/m)[0]
  return new RegExp(`^\\s+${name}: (.*)$`, 'm').exec(all)?.[1]?.trim()
}

/** In production the CSP also upgrades any http:// link to https://. */
function production(name: string, value: string): string {
  return name === 'Content-Security-Policy' ? `${value}; upgrade-insecure-requests` : value
}

describe('website security headers', () => {
  it('production (Cloudflare Pages) sends the same headers as npm run preview', () => {
    for (const [name, value] of Object.entries(websiteSecurityHeaders)) {
      expect(pagesHeader(name), name).toBe(production(name, value))
    }
  })

  it('production (Caddy, own server) sends the same headers as npm run preview', () => {
    for (const [name, value] of Object.entries(websiteSecurityHeaders)) {
      expect(caddyHeader(name), name).toBe(production(name, value))
    }
  })

  it('allows only what the site needs', () => {
    expect(contentSecurityPolicy).toContain("script-src 'self' https://challenges.cloudflare.com")
    expect(contentSecurityPolicy).toContain("frame-ancestors 'none'")
    expect(contentSecurityPolicy).not.toContain('unsafe-inline')
    expect(contentSecurityPolicy).not.toContain('unsafe-eval')
  })
})
