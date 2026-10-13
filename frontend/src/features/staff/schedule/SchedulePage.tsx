import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'

import { useAdminDoctors } from '../../../api/admin'
import { useBranches, useDoctor } from '../../../api/public'
import type { ChangeSummary } from '../../../api/schedule'
import { useAuth } from '../../../auth/session'
import { Seo } from '../../../components/Seo'
import { Alert, Field, inputClass, PageHeader } from '../../../components/ui'
import { ChangeSummaryNotice } from './ChangeSummaryNotice'
import { LeaveTab } from './LeaveTab'
import { SlotsTab } from './SlotsTab'
import { WeeklyTab } from './WeeklyTab'

export interface DoctorBranch {
  id: number
  code: string
  city: string
}

/** Context every tab needs: whose schedule, where they work, and how to report a change. */
export interface ScheduleTabProps {
  doctorId: number
  branches: DoctorBranch[]
  onChanged: (summary: ChangeSummary) => void
}

const TABS = ['weekly', 'slots', 'leave'] as const
type Tab = (typeof TABS)[number]

/** Doctor: own schedule. IT Admin: any doctor's (DESIGN.md §6 /staff/schedule). */
export function SchedulePage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [params, setParams] = useSearchParams()
  const isAdmin = user?.role === 'IT_ADMIN'
  const { data: allDoctors } = useAdminDoctors(isAdmin)
  const doctorId = isAdmin ? Number(params.get('doctor')) || allDoctors?.[0]?.id : (user?.doctorId ?? undefined)
  const tab: Tab = (TABS as readonly string[]).includes(params.get('tab') ?? '') ? (params.get('tab') as Tab) : 'weekly'
  const [summary, setSummary] = useState<ChangeSummary | null>(null)

  // The doctor's branches with ids: public doctor profile gives codes, the branch list gives ids.
  const { data: doctor } = useDoctor(doctorId ?? 0)
  const { data: allBranches } = useBranches()
  const branches = useMemo<DoctorBranch[]>(
    () =>
      (doctor?.branches ?? [])
        .map((ref) => allBranches?.find((b) => b.code === ref.code))
        .filter((b) => b !== undefined)
        .map((b) => ({ id: b.id, code: b.code, city: b.city })),
    [doctor, allBranches],
  )

  const setParam = (key: string, value: string) => {
    setSummary(null)
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      next.set(key, value)
      return next
    })
  }

  return (
    <>
      <Seo title={t('staff.schedule.title')} />
      <PageHeader title={doctor ? t('staff.schedule.titleFor', { name: doctor.fullName }) : t('staff.schedule.title')} />

      {isAdmin && (
        <div className="mb-4 max-w-md">
          <Field label={t('staff.schedule.chooseDoctor')}>
            {(a) => (
              <select {...a} className={inputClass} value={doctorId ?? ''} onChange={(e) => setParam('doctor', e.target.value)}>
                {allDoctors
                  ?.filter((d) => d.active)
                  .map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.fullName} — {d.specialityName}
                    </option>
                  ))}
              </select>
            )}
          </Field>
        </div>
      )}

      <div role="tablist" className="mb-5 flex gap-1 border-b border-slate-200">
        {TABS.map((key) => (
          <button
            key={key}
            role="tab"
            type="button"
            aria-selected={tab === key}
            onClick={() => setParam('tab', key)}
            className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
              tab === key ? 'border-brand-600 text-brand-700' : 'border-transparent text-slate-600 hover:text-slate-900'
            }`}
          >
            {t(`staff.schedule.tabs.${key}`)}
          </button>
        ))}
      </div>

      {summary && <ChangeSummaryNotice summary={summary} />}

      {doctorId && doctor && branches.length === 0 && <Alert kind="info">{t('staff.schedule.noBranches')}</Alert>}
      {doctorId && branches.length > 0 && (
        <div role="tabpanel">
          {tab === 'weekly' && <WeeklyTab doctorId={doctorId} branches={branches} onChanged={setSummary} />}
          {tab === 'slots' && <SlotsTab doctorId={doctorId} branches={branches} onChanged={setSummary} />}
          {tab === 'leave' && <LeaveTab doctorId={doctorId} branches={branches} onChanged={setSummary} />}
        </div>
      )}
    </>
  )
}
