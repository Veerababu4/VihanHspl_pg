import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'

import { useEmailConfirmation } from '../../../api/booking'
import { ApiError } from '../../../api/client'
import { Alert, Button, Field, inputClass } from '../../../components/ui'

// Same rule as the backend's @Email in spirit: something@domain.tld, no spaces.
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** "Get this confirmation by email" (DESIGN.md §19.8): optional, after booking, needs the full mobile from the booking form. */
export function EmailConfirmationForm({ bookingRef, mobile }: { bookingRef: string; mobile: string }) {
  const { t } = useTranslation()
  const send = useEmailConfirmation()
  const [email, setEmail] = useState('')
  const [invalid, setInvalid] = useState(false)
  const [sentTo, setSentTo] = useState<string | null>(null)

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault()
    const address = email.trim()
    if (!EMAIL.test(address) || address.length > 120) {
      setInvalid(true)
      return
    }
    setInvalid(false)
    try {
      const result = await send.mutateAsync({ bookingRef, mobile, email: address })
      setSentTo(result.sentTo)
    } catch {
      // shown from send.error below
    }
  }

  const error = !send.error ? null : send.error instanceof ApiError ? send.error.message : t('confirmation.email.failed')

  return (
    <section aria-labelledby="email-confirmation-title" className="mt-6 rounded-xl border border-slate-200 bg-white p-5 print:hidden">
      <h2 id="email-confirmation-title" className="font-semibold text-slate-900">
        {t('confirmation.email.title')} <span className="font-normal text-slate-500">{t('confirmation.email.optional')}</span>
      </h2>
      {sentTo ? (
        <div className="mt-3 space-y-2">
          <Alert kind="success">{t('confirmation.email.sent', { to: sentTo })}</Alert>
          <Button
            variant="link"
            onClick={() => {
              setSentTo(null)
              setEmail('')
              send.reset()
            }}
          >
            {t('confirmation.email.sendAgain')}
          </Button>
        </div>
      ) : (
        <form noValidate onSubmit={onSubmit} className="mt-3 flex flex-wrap items-start gap-3">
          <div className="min-w-0 flex-1 basis-64">
            <Field
              label={t('confirmation.email.label')}
              hint={t('confirmation.email.hint')}
              error={invalid ? t('confirmation.email.invalid') : undefined}
            >
              {(p) => (
                <input
                  {...p}
                  type="email"
                  autoComplete="email"
                  inputMode="email"
                  maxLength={120}
                  placeholder={t('confirmation.email.placeholder')}
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className={inputClass}
                />
              )}
            </Field>
          </div>
          <Button type="submit" disabled={send.isPending} className="sm:mt-6">
            {send.isPending ? t('confirmation.email.sending') : t('confirmation.email.send')}
          </Button>
          {error && (
            <div className="basis-full">
              <Alert>{error}</Alert>
            </div>
          )}
        </form>
      )}
    </section>
  )
}
