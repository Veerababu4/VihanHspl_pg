import { afterEach, describe, expect, it, vi } from 'vitest'

import { proxy } from './proxy.ts'

const env = { BACKEND_URL: 'https://vihan-backend.onrender.com', PROXY_SECRET: 'proxy-secret' }

function forwarded(): { url: string; init: RequestInit; headers: Headers } {
  const [url, init] = vi.mocked(fetch).mock.calls[0] as [URL, RequestInit]
  return { url: url.toString(), init, headers: new Headers(init.headers) }
}

describe('Cloudflare Pages proxy to the backend', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('forwards path, query, body and cookies with the secret and the visitor IP', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 201 })))
    const request = new Request('https://vihan.pages.dev/api/public/appointments?x=1', {
      method: 'POST',
      headers: { 'CF-Connecting-IP': '203.0.113.7', 'X-Forwarded-For': '6.6.6.6', Cookie: 'vihan_refresh=abc' },
      body: '{"a":1}',
    })

    const response = await proxy({ request, env })

    expect(response.status).toBe(201)
    const { url, init, headers } = forwarded()
    expect(url).toBe('https://vihan-backend.onrender.com/api/public/appointments?x=1')
    expect(init.method).toBe('POST')
    expect(init.redirect).toBe('manual')
    expect(headers.get('X-Proxy-Secret')).toBe('proxy-secret')
    expect(headers.get('X-Forwarded-For')).toBe('203.0.113.7')   // a browser-sent value is replaced
    expect(headers.get('Forwarded')).toBe('for=203.0.113.7;host=vihan.pages.dev;proto=https')
    expect(headers.get('X-Forwarded-Host')).toBe('vihan.pages.dev')
    expect(headers.get('Cookie')).toBe('vihan_refresh=abc')
  })

  it('quotes IPv6 visitors and sends no body on GET', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('ok')))
    await proxy({
      request: new Request('https://vihan.pages.dev/files/doctors/a.jpg', { headers: { 'CF-Connecting-IP': '2001:db8::1' } }),
      env,
    })
    const { init, headers } = forwarded()
    expect(init.body).toBeUndefined()
    expect(headers.get('Forwarded')).toBe('for="[2001:db8::1]";host=vihan.pages.dev;proto=https')
  })

  it('refuses to run without its settings', async () => {
    vi.stubGlobal('fetch', vi.fn())
    const response = await proxy({ request: new Request('https://vihan.pages.dev/api/x'), env: { BACKEND_URL: '', PROXY_SECRET: '' } })
    expect(response.status).toBe(503)
    expect(fetch).not.toHaveBeenCalled()
  })
})
