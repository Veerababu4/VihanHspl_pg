import { useState } from 'react'
import { useTranslation } from 'react-i18next'

import { useAdminUsers, useAuditLog, type AuditFilters } from '../../../api/admin'
import { Seo } from '../../../components/Seo'
import { Alert, Button, inputClass, PageHeader, ScrollRegion } from '../../../components/ui'

// Mirrors the backend AuditAction enum.
const ACTIONS = [
  'LOGIN_SUCCESS', 'LOGIN_FAILED', 'LOGIN_LOCKED', 'LOGIN_BLOCKED', 'LOGOUT', 'PASSWORD_CHANGED', 'REFRESH_TOKEN_REUSE',
  'USER_CREATED', 'USER_UPDATED', 'PASSWORD_RESET',
  'DOCTOR_CREATED', 'DOCTOR_UPDATED', 'DOCTOR_PHOTO_CHANGED',
  'BRANCH_CREATED', 'BRANCH_UPDATED', 'BRANCH_IMAGE_CHANGED',
  'SPECIALITY_CREATED', 'SPECIALITY_UPDATED',
  'GALLERY_IMAGE_ADDED', 'GALLERY_IMAGE_UPDATED', 'GALLERY_IMAGE_DELETED',
  'SCHEDULE_CREATED', 'SCHEDULE_UPDATED', 'SCHEDULE_DELETED', 'SLOTS_GENERATED', 'SLOTS_ADDED',
  'SLOT_BLOCKED', 'SLOT_UNBLOCKED', 'DAY_BLOCKED', 'DAY_UNBLOCKED',
  'LEAVE_ADDED', 'LEAVE_REMOVED', 'HOLIDAY_ADDED', 'HOLIDAY_REMOVED',
  'APPOINTMENT_BOOKED', 'APPOINTMENT_CANCELLED', 'APPOINTMENT_RESCHEDULED',
  'APPOINTMENT_CHECKED_IN', 'APPOINTMENT_STATUS_CHANGED', 'WALK_IN_CREATED', 'PATIENT_CREATED', 'REPORT_EXPORTED',
  'PATIENT_VIEWED', 'PATIENT_UPDATED', 'PATIENT_SEARCHED',
  'VISIT_VIEWED', 'VISIT_UPDATED', 'VISIT_COMPLETED', 'HISTORY_VIEWED', 'MEDICAL_PROFILE_UPDATED',
]

/** Audit log viewer (FR-AD5): filter by user, action and date. */
export function AuditPage() {
  const { t } = useTranslation()
  const [filters, setFilters] = useState<AuditFilters>({ page: 0 })
  const { data, isPending, isError } = useAuditLog(filters)
  const { data: users } = useAdminUsers()
  const set = (patch: Partial<AuditFilters>) => setFilters((f) => ({ ...f, ...patch, page: 0 }))

  return (
    <>
      <Seo title={t('staff.audit.title')} />
      <PageHeader title={t('staff.audit.title')} />
      <div className="mb-4 grid gap-3 rounded-xl border border-slate-200 bg-white p-4 sm:grid-cols-4">
        <label className="text-sm font-medium">
          {t('staff.audit.user')}
          <select className={`${inputClass} mt-1`} value={filters.userId ?? ''} onChange={(e) => set({ userId: e.target.value ? Number(e.target.value) : undefined })}>
            <option value="">{t('staff.audit.anyUser')}</option>
            {users?.map((u) => (
              <option key={u.id} value={u.id}>
                {u.username}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm font-medium">
          {t('staff.audit.action')}
          <select className={`${inputClass} mt-1`} value={filters.action ?? ''} onChange={(e) => set({ action: e.target.value || undefined })}>
            <option value="">{t('staff.audit.anyAction')}</option>
            {ACTIONS.map((a) => (
              <option key={a} value={a}>
                {a}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm font-medium">
          {t('staff.audit.from')}
          <input type="date" className={`${inputClass} mt-1`} value={filters.from ?? ''} onChange={(e) => set({ from: e.target.value || undefined })} />
        </label>
        <label className="text-sm font-medium">
          {t('staff.audit.to')}
          <input type="date" className={`${inputClass} mt-1`} value={filters.to ?? ''} onChange={(e) => set({ to: e.target.value || undefined })} />
        </label>
      </div>

      {isPending && <p className="text-slate-500">{t('staff.common.loading')}</p>}
      {isError && <Alert>{t('staff.common.loadError')}</Alert>}
      {data && data.items.length === 0 && <p className="text-slate-600">{t('staff.audit.empty')}</p>}
      {data && data.items.length > 0 && (
        <>
          <ScrollRegion label={t('staff.audit.title')} className="rounded-xl border border-slate-200 bg-white">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-slate-200 bg-slate-50 text-slate-600">
                <tr>
                  <th className="px-3 py-2">{t('staff.audit.time')}</th>
                  <th className="px-3 py-2">{t('staff.audit.user')}</th>
                  <th className="px-3 py-2">{t('staff.audit.action')}</th>
                  <th className="px-3 py-2">{t('staff.audit.entity')}</th>
                  <th className="px-3 py-2">{t('staff.audit.details')}</th>
                  <th className="px-3 py-2">{t('staff.audit.ip')}</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((e) => (
                  <tr key={e.id} className="border-b border-slate-100 align-top last:border-0">
                    <td className="px-3 py-2 whitespace-nowrap">{new Date(e.createdAt).toLocaleString('en-IN')}</td>
                    <td className="px-3 py-2 font-mono">{e.username ?? <span className="text-slate-500">{t('staff.audit.anonymous')}</span>}</td>
                    <td className="px-3 py-2 font-mono text-xs">{e.action}</td>
                    <td className="px-3 py-2">{e.entityType ? `${e.entityType} ${e.entityId ?? ''}` : '—'}</td>
                    <td className="px-3 py-2 text-slate-600">{e.details ?? ''}</td>
                    <td className="px-3 py-2 font-mono text-xs text-slate-500">{e.ipAddress ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
          <div className="mt-4 flex items-center justify-between text-sm">
            <span>{t('staff.audit.page', { page: data.page + 1, total: Math.max(1, data.totalPages) })}</span>
            <div className="flex gap-2">
              <Button variant="secondary" disabled={data.page === 0} onClick={() => setFilters((f) => ({ ...f, page: f.page - 1 }))}>
                {t('staff.audit.prev')}
              </Button>
              <Button variant="secondary" disabled={data.page + 1 >= data.totalPages} onClick={() => setFilters((f) => ({ ...f, page: f.page + 1 }))}>
                {t('staff.audit.next')}
              </Button>
            </div>
          </div>
        </>
      )}
    </>
  )
}
