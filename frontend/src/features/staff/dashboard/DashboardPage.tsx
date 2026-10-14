import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'

import { apiDownload } from '../../../api/client'
import { useBranches } from '../../../api/public'
import { dashboardExportUrl, useDashboard, type Counts } from '../../../api/reception'
import { useAuth } from '../../../auth/session'
import { Seo } from '../../../components/Seo'
import { Alert, Button, Field, inputClass, PageHeader, ScrollRegion } from '../../../components/ui'
import { todayIso } from '../../../lib/dates'

const COLUMNS: (keyof Counts)[] = ['total', 'booked', 'checkedIn', 'completed', 'cancelled', 'noShow', 'openSlots']

/**
 * Day counts: network, per branch and per doctor (FR-D1–D3, FR-D5). A receptionist starts on their
 * home branch; a doctor sees only their own figures.
 */
export function DashboardPage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const desk = user?.role === 'RECEPTIONIST' || user?.role === 'IT_ADMIN'
  const { data: branches } = useBranches()
  const homeBranch = branches?.find((b) => b.id === user?.homeBranchId)?.code
  const [params, setParams] = useSearchParams()
  const date = params.get('date') ?? todayIso()
  const branch = params.has('branch') ? params.get('branch') || undefined : user?.role === 'RECEPTIONIST' ? homeBranch : undefined
  const set = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params)
    Object.entries(changes).forEach(([k, v]) => next.set(k, v))
    setParams(next, { replace: true })
  }
  const summary = useDashboard(date, branch)
  const [exportError, setExportError] = useState<string | null>(null)

  const download = async (format: 'csv' | 'xlsx') => {
    setExportError(null)
    try {
      await apiDownload(dashboardExportUrl(date, branch, format), `dashboard-${date}${branch ? `-${branch}` : ''}.${format}`)
    } catch (e) {
      setExportError(e instanceof Error ? e.message : String(e))
    }
  }

  const label = (k: keyof Counts) => t(`staff.dashboard.${k}`)
  const s = summary.data

  return (
    <>
      <Seo title={t('staff.dashboard.title')} />
      <PageHeader
        title={t('staff.dashboard.title')}
        actions={
          desk && (
            <div className="flex gap-2 print:hidden">
              <Button variant="secondary" onClick={() => download('csv')}>{t('staff.dashboard.exportCsv')}</Button>
              <Button variant="secondary" onClick={() => download('xlsx')}>{t('staff.dashboard.exportXlsx')}</Button>
            </div>
          )
        }
      />
      <p className="-mt-4 mb-5 text-slate-600">{t('staff.dashboard.welcome', { name: user?.fullName })}</p>

      <div className="mb-6 grid max-w-xl gap-3 sm:grid-cols-2 print:hidden">
        <Field label={t('staff.dashboard.date')}>
          {(f) => <input {...f} type="date" value={date} onChange={(e) => set({ date: e.target.value || todayIso() })} className={inputClass} />}
        </Field>
        {!s?.doctorOnly && (
          <Field label={t('staff.dashboard.branch')}>
            {(f) => (
              <select {...f} value={branch ?? ''} onChange={(e) => set({ branch: e.target.value })} className={inputClass}>
                <option value="">{t('staff.dashboard.allBranches')}</option>
                {branches?.map((b) => (
                  <option key={b.code} value={b.code}>
                    {b.city}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
      </div>

      {exportError && <div className="mb-4"><Alert>{exportError}</Alert></div>}
      {summary.isPending && <p className="text-slate-500">{t('staff.common.loading')}</p>}
      {summary.error && <Alert>{summary.error.message}</Alert>}

      {s && (
        <>
          <div className="mb-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <CountCard
              title={s.doctorOnly ? t('staff.dashboard.own') : s.branch ? (s.branches[0]?.branchCity ?? s.branch) : t('staff.dashboard.network')}
              counts={s.network}
              highlight
            />
            {!s.doctorOnly &&
              s.branches.length > 1 &&
              s.branches.map((b) => <CountCard key={b.branchCode} title={b.branchCity} counts={b.counts} />)}
          </div>

          <section aria-labelledby="per-doctor">
            <h2 id="per-doctor" className="mb-2 text-lg font-semibold text-slate-900">
              {t('staff.dashboard.perDoctor')}
            </h2>
            {s.doctors.length === 0 ? (
              <p className="rounded-xl border border-slate-200 bg-white p-6 text-slate-600">{t('staff.dashboard.noData')}</p>
            ) : (
              <ScrollRegion label={t('staff.dashboard.perDoctor')} className="rounded-xl border border-slate-200 bg-white">
                <table className="w-full text-sm">
                  <thead className="bg-slate-50 text-left text-slate-600">
                    <tr>
                      <th scope="col" className="px-3 py-2 font-medium">{t('staff.dashboard.doctor')}</th>
                      <th scope="col" className="px-3 py-2 font-medium">{t('staff.dashboard.branch')}</th>
                      {COLUMNS.map((k) => (
                        <th key={k} scope="col" className="px-3 py-2 text-right font-medium">{label(k)}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {s.doctors.map((d) => (
                      <tr key={`${d.doctorId}-${d.branchCode}`}>
                        <th scope="row" className="px-3 py-2 text-left font-medium">
                          <Link
                            to={`/staff/appointments?date=${date}&branch=${d.branchCode}${s.doctorOnly ? '' : `&doctor=${d.doctorId}`}`}
                            className="text-brand-700 hover:underline"
                          >
                            {d.doctorName}
                          </Link>
                          <span className="block text-xs font-normal text-slate-500">{d.speciality}</span>
                        </th>
                        <td className="px-3 py-2">{d.branchCity}</td>
                        {COLUMNS.map((k) => (
                          <td key={k} className={`px-3 py-2 text-right tabular-nums ${k === 'total' ? 'font-semibold' : ''}`}>
                            {d.counts[k]}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </ScrollRegion>
            )}
          </section>
        </>
      )}
    </>
  )
}

function CountCard({ title, counts, highlight }: { title: string; counts: Counts; highlight?: boolean }) {
  const { t } = useTranslation()
  return (
    <section
      aria-label={title}
      className={`rounded-xl border p-4 ${highlight ? 'border-brand-100 bg-brand-50' : 'border-slate-200 bg-white'}`}
    >
      <h2 className="text-sm font-medium text-slate-600">{title}</h2>
      <p className="text-3xl font-bold text-slate-900 tabular-nums">{counts.total}</p>
      <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-0.5 text-sm">
        {(['booked', 'checkedIn', 'completed', 'cancelled', 'noShow', 'openSlots'] as const).map((k) => (
          <div key={k} className="flex justify-between gap-2">
            <dt className="text-slate-600">{t(`staff.dashboard.${k}`)}</dt>
            <dd className="font-medium tabular-nums">{counts[k]}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}
