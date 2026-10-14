import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'

import type { AppointmentStatus } from '../../../api/booking'
import { useBranches, useDoctors } from '../../../api/public'
import { useChangeStatus, useNeedsAction, useStaffAppointments, type StaffAppointment } from '../../../api/reception'
import { useAuth } from '../../../auth/session'
import { Seo } from '../../../components/Seo'
import { Alert, Badge, Button, Field, inputClass, Modal, PageHeader } from '../../../components/ui'
import { shortDate, timeOf, todayIso } from '../../../lib/dates'
import { formatTime } from '../../../lib/format'
import { CheckInDialog } from './CheckInDialog'
import { RescheduleDialog } from './RescheduleDialog'
import { WalkInDialog } from './WalkInDialog'

const STATUSES: AppointmentStatus[] = ['BOOKED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW']

const statusTone: Record<AppointmentStatus, 'neutral' | 'good' | 'warn' | 'bad'> = {
  BOOKED: 'neutral',
  CHECKED_IN: 'warn',
  COMPLETED: 'good',
  CANCELLED: 'bad',
  NO_SHOW: 'bad',
}

type Dialog =
  | { kind: 'checkIn' | 'cancel' | 'noShow' | 'complete' | 'reschedule'; appointment: StaffAppointment }
  | { kind: 'walkIn' }

/**
 * Reception's day list (FR-R1, FR-R2): filters live in the URL so a link (e.g. from the dashboard)
 * opens the same view. Which buttons show comes from the server ({@link StaffAppointment.actions}).
 */
export function AppointmentsPage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const desk = user?.role === 'RECEPTIONIST' || user?.role === 'IT_ADMIN'
  const { data: branches } = useBranches()
  const homeBranch = branches?.find((b) => b.id === user?.homeBranchId)?.code

  const [params, setParams] = useSearchParams()
  const date = params.get('date') ?? todayIso()
  // A receptionist starts on their own hospital; "all" is an explicit choice (FR-D3).
  const branch = params.has('branch') ? params.get('branch') || undefined : user?.role === 'RECEPTIONIST' ? homeBranch : undefined
  const doctor = Number(params.get('doctor')) || undefined
  const status = (params.get('status') as AppointmentStatus | null) ?? undefined
  const q = params.get('q') ?? ''
  const [search, setSearch] = useState(q)
  const set = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params)
    if (!next.has('branch') && branch) next.set('branch', branch)
    Object.entries(changes).forEach(([k, v]) => next.set(k, v ?? ''))
    setParams(next, { replace: true })
  }

  const { data: doctors } = useDoctors({ branch })
  const list = useStaffAppointments({ date, branch, doctor, status, q: q || undefined })
  const needsAction = useNeedsAction(branch, desk)
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [done, setDone] = useState<string | null>(null)

  const finished = (message: string) => {
    setDialog(null)
    setDone(message)
  }

  return (
    <>
      <Seo title={t('staff.appointments.title')} />
      <PageHeader
        title={t('staff.appointments.title')}
        actions={
          desk && (
            <div className="flex gap-2">
              <Button onClick={() => { setDone(null); setDialog({ kind: 'walkIn' }) }}>{t('staff.appointments.walkIn')}</Button>
              <Link to="/staff/book" className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-800 hover:bg-slate-50">
                {t('staff.appointments.newBooking')}
              </Link>
            </div>
          )
        }
      />

      {done && (
        <div className="mb-4">
          <Alert kind="success">{done}</Alert>
        </div>
      )}

      {desk && needsAction.data && needsAction.data.length > 0 && (
        <section aria-labelledby="needs-action" className="mb-6 rounded-xl border border-amber-300 bg-amber-50 p-4">
          <h2 id="needs-action" className="font-semibold text-amber-950">
            {t('staff.appointments.needsActionTitle', { count: needsAction.data.length })}
          </h2>
          <p className="mb-3 text-sm text-amber-900">{t('staff.appointments.needsActionIntro')}</p>
          <ul className="space-y-2">
            {needsAction.data.map((a) => (
              <AppointmentRow key={a.id} appointment={a} showDate onAction={(kind) => { setDone(null); setDialog({ kind, appointment: a }) }} />
            ))}
          </ul>
        </section>
      )}

      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <Field label={t('staff.appointments.date')}>
          {(f) => <input {...f} type="date" value={date} onChange={(e) => set({ date: e.target.value || todayIso() })} className={inputClass} />}
        </Field>
        <Field label={t('staff.appointments.branch')}>
          {(f) => (
            <select {...f} value={branch ?? ''} onChange={(e) => set({ branch: e.target.value, doctor: undefined })} className={inputClass}>
              <option value="">{t('staff.appointments.allBranches')}</option>
              {branches?.map((b) => (
                <option key={b.code} value={b.code}>
                  {b.city}
                </option>
              ))}
            </select>
          )}
        </Field>
        {user?.role !== 'DOCTOR' && (
          <Field label={t('staff.appointments.doctor')}>
            {(f) => (
              <select {...f} value={doctor ?? ''} onChange={(e) => set({ doctor: e.target.value })} className={inputClass}>
                <option value="">{t('staff.appointments.allDoctors')}</option>
                {doctors?.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.fullName}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        <Field label={t('staff.appointments.status')}>
          {(f) => (
            <select {...f} value={status ?? ''} onChange={(e) => set({ status: e.target.value })} className={inputClass}>
              <option value="">{t('staff.appointments.allStatuses')}</option>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {t(`status.statusLabel.${s}`)}
                </option>
              ))}
            </select>
          )}
        </Field>
        <form
          role="search"
          onSubmit={(e) => {
            e.preventDefault()
            set({ q: search.trim() })
          }}
        >
          <Field label={t('staff.appointments.search')} hint={t('staff.appointments.searchHint')}>
            {(f) => <input {...f} type="search" value={search} onChange={(e) => setSearch(e.target.value)} className={inputClass} />}
          </Field>
        </form>
      </div>

      {list.isPending && <p className="text-slate-500">{t('staff.common.loading')}</p>}
      {list.error && <Alert>{list.error.message}</Alert>}
      {list.data && (
        <>
          <p className="mb-2 text-sm text-slate-600" aria-live="polite">
            {t('staff.appointments.count', { count: list.data.length })}
          </p>
          {list.data.length === 0 ? (
            <p className="rounded-xl border border-slate-200 bg-white p-6 text-slate-600">{t('staff.appointments.empty')}</p>
          ) : (
            <ul className="space-y-2">
              {list.data.map((a) => (
                <AppointmentRow key={a.id} appointment={a} onAction={(kind) => { setDone(null); setDialog({ kind, appointment: a }) }} />
              ))}
            </ul>
          )}
        </>
      )}

      {dialog?.kind === 'walkIn' && (
        <WalkInDialog
          defaultBranch={branch ?? homeBranch}
          onClose={() => setDialog(null)}
          onDone={(a) => finished(t('staff.appointments.done.walkIn', { name: a.patientName, token: a.tokenNo }))}
        />
      )}
      {dialog?.kind === 'checkIn' && (
        <CheckInDialog
          appointment={dialog.appointment}
          onClose={() => setDialog(null)}
          onDone={(a) => finished(t('staff.appointments.done.CHECKED_IN', { name: a.patientName, token: a.tokenNo }))}
        />
      )}
      {dialog?.kind === 'reschedule' && (
        <RescheduleDialog
          appointment={dialog.appointment}
          onClose={() => setDialog(null)}
          onDone={(a) =>
            finished(t('staff.appointments.done.moved', { name: a.patientName, when: a.startAt ? `${shortDate(a.date)}, ${formatTime(timeOf(a.startAt))}` : shortDate(a.date) }))
          }
        />
      )}
      {(dialog?.kind === 'cancel' || dialog?.kind === 'noShow' || dialog?.kind === 'complete') && (
        <StatusDialog
          kind={dialog.kind}
          appointment={dialog.appointment}
          onClose={() => setDialog(null)}
          onDone={(a) => finished(t(`staff.appointments.done.${a.status}`, { name: a.patientName }))}
        />
      )}
    </>
  )
}

type RowAction = 'checkIn' | 'noShow' | 'cancel' | 'complete' | 'reschedule'

/**
 * One appointment. Without {@code onAction} (lists outside this page) the row links to its day list,
 * where the actions are; a full booking reference finds it there on any date.
 */
export function AppointmentRow({
  appointment: a,
  showDate,
  onAction,
}: {
  appointment: StaffAppointment
  showDate?: boolean
  onAction?: (kind: RowAction) => void
}) {
  const { t } = useTranslation()
  const { user } = useAuth()
  // A doctor records and completes the visit on the visit screen (M8), not with a bare "Complete".
  const visitLink = user?.role === 'DOCTOR' && a.visitId != null && a.doctorId === user.doctorId
  const time = a.startAt ? formatTime(timeOf(a.startAt)) : t('staff.appointments.walkInTime')
  const buttons: { kind: RowAction; variant: 'primary' | 'secondary' | 'danger' }[] = [
    { kind: 'checkIn', variant: 'primary' },
    { kind: 'complete', variant: 'primary' },
    { kind: 'reschedule', variant: 'secondary' },
    { kind: 'noShow', variant: 'secondary' },
    { kind: 'cancel', variant: 'danger' },
  ]
  return (
    <li className="flex flex-wrap items-start gap-x-6 gap-y-3 rounded-xl border border-slate-200 bg-white p-4" aria-label={`${a.patientName}, ${time}`}>
      <div className="w-20 shrink-0">
        <p className="text-xs text-slate-500">{t('staff.appointments.token')}</p>
        <p className="text-2xl font-bold text-brand-700">{a.tokenNo ?? '—'}</p>
        <p className="text-sm text-slate-700">
          {showDate && (
            <span className="block">
              {shortDate(a.date)}
              {a.date.slice(0, 4) !== todayIso().slice(0, 4) && ` ${a.date.slice(0, 4)}`}
            </span>
          )}
          {time}
        </p>
      </div>
      <div className="min-w-0 flex-1 basis-56">
        <p className="font-semibold text-slate-900">
          {a.patientName}{' '}
          <span className="text-sm font-normal text-slate-600">
            {a.patientAge != null && `${a.patientAge} y`}
            {a.patientGender && ` · ${t(`booking.genders.${a.patientGender}`)}`}
          </span>
        </p>
        <p className="text-sm text-slate-700">
          <a href={`tel:${a.mobile}`} className="text-brand-700 underline">
            {a.mobile}
          </a>{' '}
          · <span className="font-mono text-xs">{a.bookingRef}</span>
        </p>
        <div className="mt-1 flex flex-wrap gap-1">
          <Badge tone={statusTone[a.status]}>{t(`status.statusLabel.${a.status}`)}</Badge>
          <Badge>{t(`staff.channels.${a.channel}`)}</Badge>
          {a.uhid && a.patientId && (
            <Link to={`/staff/patients/${a.patientId}`} className="rounded-full hover:ring-2 hover:ring-emerald-300">
              <Badge tone="good">{t('staff.appointments.linked', { uhid: a.uhid })}</Badge>
            </Link>
          )}
          {a.needsAction && <Badge tone="warn">{t('staff.appointments.needsAction')}</Badge>}
          {a.frequentNoShow && <Badge tone="bad">{t('staff.appointments.frequentNoShow')}</Badge>}
        </div>
        {a.cancelReason && <p className="mt-1 text-sm text-slate-600">{t('staff.appointments.reasonShown', { reason: a.cancelReason })}</p>}
      </div>
      <div className="min-w-0 basis-48 text-sm">
        <p className="font-medium text-slate-900">{a.doctorName}</p>
        <p className="text-slate-600">
          {a.speciality} · {a.branchCity}
        </p>
        {a.reason && <p className="mt-1 text-slate-600">{a.reason}</p>}
      </div>
      <div className="flex w-full flex-wrap gap-2 sm:w-auto">
        {visitLink && (
          <Link to={`/staff/visits/${a.visitId}`} className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700">
            {t('staff.appointments.openVisit')}
          </Link>
        )}
        {onAction ? (
          buttons
            .filter((b) => a.actions[b.kind] && !(visitLink && b.kind === 'complete'))
            .map((b) => (
              <Button key={b.kind} variant={b.variant} onClick={() => onAction(b.kind)}>
                {t(`staff.appointments.${b.kind}`)}
              </Button>
            ))
        ) : (
          <Link
            to={`/staff/appointments?${new URLSearchParams({ date: a.date, branch: '', q: a.bookingRef })}`}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-800 hover:bg-slate-50"
          >
            {t('staff.appointments.openInList')}
          </Link>
        )}
      </div>
    </li>
  )
}

/** Cancel (reason required), no-show and complete: a confirmation, then one PATCH. */
function StatusDialog({
  kind,
  appointment: a,
  onClose,
  onDone,
}: {
  kind: 'cancel' | 'noShow' | 'complete'
  appointment: StaffAppointment
  onClose: () => void
  onDone: (a: StaffAppointment) => void
}) {
  const { t } = useTranslation()
  const change = useChangeStatus()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const status = ({ cancel: 'CANCELLED', noShow: 'NO_SHOW', complete: 'COMPLETED' } as const)[kind]
  const title =
    kind === 'cancel'
      ? t('staff.appointments.cancelTitle')
      : t(kind === 'noShow' ? 'staff.appointments.confirmNoShow' : 'staff.appointments.confirmComplete', { name: a.patientName })

  const submit = async () => {
    if (kind === 'cancel' && !reason.trim()) {
      setError(t('staff.appointments.cancelReasonRequired'))
      return
    }
    setError(null)
    try {
      onDone(await change.mutateAsync({ id: a.id, status, reason: reason.trim() || undefined }))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <Modal title={title} onClose={onClose}>
      <div className="space-y-4">
        {error && <Alert>{error}</Alert>}
        {kind === 'cancel' && (
          <>
            <p className="text-sm text-slate-700">
              {a.patientName} · {a.startAt ? `${shortDate(a.date)}, ${formatTime(timeOf(a.startAt))}` : shortDate(a.date)} · {a.doctorName}
            </p>
            <Field label={t('staff.appointments.cancelReason')}>
              {(f) => <input {...f} value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} className={inputClass} />}
            </Field>
          </>
        )}
        <div className="flex gap-3">
          <Button variant={kind === 'cancel' ? 'danger' : 'primary'} onClick={submit} disabled={change.isPending}>
            {kind === 'cancel' ? t('staff.appointments.confirmCancel') : t(`staff.appointments.${kind}`)}
          </Button>
          <Button variant="secondary" onClick={onClose}>
            {t('staff.appointments.keep')}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
