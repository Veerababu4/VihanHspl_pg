import { useTranslation } from 'react-i18next'
import { Link, useLocation, useParams } from 'react-router'

import type { AppointmentView } from '../../../api/booking'
import { useAppInfo } from '../../../api/public'
import { Seo } from '../../../components/Seo'
import { Button } from '../../../components/ui'
import { confirmationShareText, whatsappShareUrl } from '../../../lib/whatsappShare'
import { AppointmentCard } from './AppointmentCard'
import { EmailConfirmationForm } from './EmailConfirmationForm'

/** FR-B2: reference, token, doctor, branch, time and "arrive 15 min early"; printable, shareable on WhatsApp, can be emailed. */
export function ConfirmationPage() {
  const { t } = useTranslation()
  const ref = useParams().ref ?? ''
  const state = useLocation().state as { appointment?: AppointmentView; mobile?: string } | null
  const appointment = state?.appointment
  const appInfo = useAppInfo().data

  const shareText =
    appointment &&
    confirmationShareText(appointment, t, {
      hospital: appInfo?.name ?? 'Vihan Multi Speciality Hospital',
      origin: window.location.origin,
      testNotice: appInfo?.testNotice ?? true,   // until app-info answers, say so rather than not
    })

  return (
    <div className="mx-auto max-w-3xl px-4 py-10">
      <Seo title={t('confirmation.title')} />
      <h1 className="text-3xl font-bold text-slate-900">✅ {t('confirmation.title')}</h1>
      <p className="mt-1 mb-6 text-slate-600 print:hidden">{t('confirmation.subtitle')}</p>

      {appointment ? (
        <AppointmentCard appointment={appointment} />
      ) : (
        // Page reloaded: the details aren't kept in the browser, so point to the lookup page.
        <p className="rounded-xl border border-slate-200 bg-white p-6">{t('confirmation.missing', { ref })}</p>
      )}

      {appointment && state?.mobile && appInfo?.confirmationEmail && (
        <EmailConfirmationForm bookingRef={appointment.bookingRef} mobile={state.mobile} />
      )}

      <div className="mt-6 flex flex-wrap gap-3 print:hidden">
        {shareText && (
          <a
            href={whatsappShareUrl(shareText)}
            target="_blank"
            rel="noopener noreferrer"
            title={t('confirmation.whatsappHint')}
            className="inline-flex items-center gap-2 rounded-lg bg-[#1f7a4d] px-4 py-2 text-sm font-medium text-white hover:bg-[#17603c]"
          >
            <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4 fill-current">
              <path d="M12 2a10 10 0 0 0-8.6 15.1L2 22l5-1.3A10 10 0 1 0 12 2Zm0 18.2a8.2 8.2 0 0 1-4.2-1.2l-.3-.2-3 .8.8-2.9-.2-.3A8.2 8.2 0 1 1 12 20.2Zm4.5-6.1c-.2-.1-1.5-.7-1.7-.8s-.4-.1-.6.1-.7.8-.8 1-.3.2-.5.1a6.7 6.7 0 0 1-3.3-2.9c-.2-.4.2-.4.7-1.3.1-.2 0-.3 0-.4l-.8-1.8c-.2-.5-.4-.4-.6-.4h-.5a1 1 0 0 0-.7.3 3 3 0 0 0-.9 2.2 5.2 5.2 0 0 0 1.1 2.7 11.8 11.8 0 0 0 4.5 4c1.7.7 2.3.8 3.2.6a2.7 2.7 0 0 0 1.8-1.2 2.2 2.2 0 0 0 .2-1.3c-.1-.1-.3-.2-.5-.3Z" />
            </svg>
            {t('confirmation.whatsapp')}
          </a>
        )}
        {appointment && <Button onClick={() => window.print()}>{t('confirmation.print')}</Button>}
        <Link
          to={`/appointment-status?ref=${encodeURIComponent(ref)}`}
          className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium hover:bg-slate-50"
        >
          {t('confirmation.check')}
        </Link>
        <Link to="/" className="px-4 py-2 text-sm font-medium text-brand-700 hover:underline">
          {t('confirmation.home')}
        </Link>
      </div>
    </div>
  )
}
