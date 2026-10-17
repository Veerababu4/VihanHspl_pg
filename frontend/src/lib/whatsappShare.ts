import type { TFunction } from 'i18next'

import type { AppointmentView } from '../api/booking'
import { shortDate, timeOf } from './dates'
import { formatFee, formatTime } from './format'

/**
 * "Share on WhatsApp": WhatsApp's own click-to-chat link with the text filled in. The patient picks
 * who to send it to (themselves, family), so no account or API is needed and nothing leaves the
 * browser until they press send. The mobile number is left out on purpose.
 */
export function whatsappShareUrl(text: string): string {
  return `https://wa.me/?text=${encodeURIComponent(text)}`
}

/** The booking as a short WhatsApp message (`origin` = this website, for the "check or change" link). */
export function confirmationShareText(
  a: AppointmentView,
  t: TFunction,
  { hospital, origin, testNotice }: { hospital: string; origin: string; testNotice: boolean },
): string {
  const when = a.startAt ? `${shortDate(a.date)}, ${formatTime(timeOf(a.startAt))}` : shortDate(a.date)
  const where = [a.branch.name, a.branch.address, a.branch.city].filter(Boolean).join(', ') + (a.branch.phone ? ` · ${a.branch.phone}` : '')
  const fee = formatFee(a.doctor.consultationFee)
  const url = `${origin}/appointment-status?ref=${encodeURIComponent(a.bookingRef)}`
  const refLine = t('confirmation.share.ref', { ref: a.bookingRef }) + (a.tokenNo != null ? ` · ${t('confirmation.share.token', { token: a.tokenNo })}` : '')
  return [
    testNotice && t('confirmation.share.testNotice'),
    t('confirmation.share.title', { hospital }),
    refLine,
    t('confirmation.share.patient', { name: a.patientName }),
    t('confirmation.share.doctor', { doctor: a.doctor.fullName, speciality: a.doctor.speciality }),
    t('confirmation.share.when', { when }),
    t('confirmation.share.where', { where }),
    fee && t('confirmation.share.fee', { fee }),
    t('confirmation.share.link', { url }),
  ]
    .filter(Boolean)
    .join('\n')
}
