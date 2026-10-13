// Cloudflare Pages Functions (DESIGN.md §16): the website forwards /api/* and /files/* to the
// backend on Render, so the browser sees one origin, exactly like Vite's dev proxy and Caddy.
// That keeps the SameSite=Strict refresh cookie and the CSP's connect-src 'self' working.
//
// Set in the Pages project (Settings → Variables and secrets):
//   BACKEND_URL   e.g. https://vihan-backend.onrender.com
//   PROXY_SECRET  same value as the backend's PROXY_SECRET (it rejects requests without it)

export interface Env {
  BACKEND_URL: string
  PROXY_SECRET: string
}

interface Context {
  request: Request
  env: Env
}

/** RFC 7239 node: IPv6 addresses must be quoted and in brackets. */
function forwardedFor(ip: string): string {
  return ip.includes(':') ? `"[${ip}]"` : ip
}

export async function proxy({ request, env }: Context): Promise<Response> {
  if (!env.BACKEND_URL || !env.PROXY_SECRET) {
    return new Response('Backend not configured', { status: 503 })
  }
  const url = new URL(request.url)
  const target = new URL(url.pathname + url.search, env.BACKEND_URL)

  const headers = new Headers(request.headers)
  headers.delete('Host')
  headers.set('X-Proxy-Secret', env.PROXY_SECRET)
  // The visitor's IP (set by Cloudflare, not by the browser) for the backend's rate limits and
  // audit log; the host and scheme so the backend treats requests as same-origin.
  const ip = request.headers.get('CF-Connecting-IP') ?? '0.0.0.0'
  const proto = url.protocol.replace(':', '')
  headers.set('Forwarded', `for=${forwardedFor(ip)};host=${url.host};proto=${proto}`)
  headers.set('X-Forwarded-For', ip)
  headers.set('X-Forwarded-Host', url.host)
  headers.set('X-Forwarded-Proto', proto)

  const hasBody = request.method !== 'GET' && request.method !== 'HEAD'
  return fetch(target, {
    method: request.method,
    headers,
    body: hasBody ? request.body : undefined,
    redirect: 'manual',
  })
}
