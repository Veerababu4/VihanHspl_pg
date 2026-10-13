import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

// Cloudflare Turnstile "I am human" widget (DESIGN.md §19.2). Tokens are single-use: change
// `resetKey` after every failed submit to get a fresh one.

interface TurnstileApi {
  render(el: HTMLElement, options: Record<string, unknown>): string
  remove(widgetId: string): void
}

declare global {
  interface Window {
    turnstile?: TurnstileApi
  }
}

const SCRIPT_URL = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'
/** Sent when no site key is configured (tests, offline development with CAPTCHA_ENABLED=false). */
export const NO_CAPTCHA_TOKEN = 'no-captcha-configured'

let loading: Promise<void> | null = null

function loadScript(): Promise<void> {
  if (window.turnstile) return Promise.resolve()
  if (!loading) {
    loading = new Promise<void>((resolve, reject) => {
      const script = document.createElement('script')
      script.src = SCRIPT_URL
      script.async = true
      script.onload = () => resolve()
      script.onerror = () => {
        loading = null
        reject(new Error('Turnstile failed to load'))
      }
      document.head.appendChild(script)
    })
  }
  return loading
}

export function Turnstile({ onToken, resetKey = 0 }: { onToken: (token: string | null) => void; resetKey?: number }) {
  const { t } = useTranslation()
  const siteKey = import.meta.env.VITE_TURNSTILE_SITE_KEY as string | undefined
  const container = useRef<HTMLDivElement>(null)
  const latestOnToken = useRef(onToken)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    latestOnToken.current = onToken
  })

  useEffect(() => {
    if (!siteKey) {
      latestOnToken.current(NO_CAPTCHA_TOKEN)
      return
    }
    latestOnToken.current(null)
    let widgetId: string | undefined
    let cancelled = false
    loadScript()
      .then(() => {
        if (cancelled || !container.current || !window.turnstile) return
        widgetId = window.turnstile.render(container.current, {
          sitekey: siteKey,
          language: 'en',
          callback: (token: string) => latestOnToken.current(token),
          'expired-callback': () => latestOnToken.current(null),
          'error-callback': () => latestOnToken.current(null),
        })
      })
      .catch(() => setFailed(true))
    return () => {
      cancelled = true
      if (widgetId && window.turnstile) window.turnstile.remove(widgetId)
    }
  }, [siteKey, resetKey])

  if (!siteKey) return null
  return (
    <div data-testid="captcha">
      <div ref={container} className="min-h-[65px]" />
      {failed && (
        <p role="alert" className="text-sm text-emergency-600">
          {t('booking.captchaLoadFailed')}
        </p>
      )}
    </div>
  )
}
