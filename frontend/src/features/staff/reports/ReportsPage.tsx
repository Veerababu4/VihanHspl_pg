import { useState } from 'react'
import { useTranslation } from 'react-i18next'

import { apiDownload } from '../../../api/client'
import { useBranches } from '../../../api/public'
import { PERIOD_REPORTS, REPORT_TYPES, reportUrl, useReport, type ReportParams, type ReportType } from '../../../api/reception'
import { useAuth } from '../../../auth/session'
import { Seo } from '../../../components/Seo'
import { Alert, Button, Field, inputClass, PageHeader, ScrollRegion } from '../../../components/ui'
import { addDays, todayIso } from '../../../lib/dates'

/**
 * Reports (FR-D6): pick a report, a date range and a branch; view it here, export it to CSV or
 * Excel, or print it (the browser's "Save as PDF" covers PDF).
 */
export function ReportsPage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const { data: branches } = useBranches()
  const today = todayIso()
  const homeBranch = branches?.find((b) => b.id === user?.homeBranchId)?.code
  const [draft, setDraft] = useState<ReportParams>({ type: 'daily-register', from: today, to: today, period: 'day' })
  const [branchChoice, setBranchChoice] = useState<string | undefined>()
  const branch = branchChoice ?? (user?.role === 'RECEPTIONIST' ? homeBranch : undefined)
  const [shown, setShown] = useState<ReportParams | null>(null)
  const report = useReport(shown)
  const [exportError, setExportError] = useState<string | null>(null)
  const set = (changes: Partial<ReportParams>) => setDraft((d) => ({ ...d, ...changes }))

  const download = async (format: 'csv' | 'xlsx') => {
    if (!shown) return
    setExportError(null)
    try {
      await apiDownload(reportUrl(shown, format), `${shown.type}-${shown.from}-to-${shown.to}${shown.branch ? `-${shown.branch}` : ''}.${format}`)
    } catch (e) {
      setExportError(e instanceof Error ? e.message : String(e))
    }
  }

  const r = report.data
  return (
    <>
      <Seo title={t('staff.reports.title')} />
      <PageHeader title={t('staff.reports.title')} />

      <form
        className="mb-6 grid gap-3 rounded-xl border border-slate-200 bg-white p-4 sm:grid-cols-2 lg:grid-cols-3 print:hidden"
        onSubmit={(e) => {
          e.preventDefault()
          setShown({ ...draft, branch, period: PERIOD_REPORTS.includes(draft.type) ? draft.period : undefined })
        }}
      >
        <Field label={t('staff.reports.type')}>
          {(f) => (
            <select {...f} value={draft.type} onChange={(e) => set({ type: e.target.value as ReportType })} className={inputClass}>
              {REPORT_TYPES.map((type) => (
                <option key={type} value={type}>
                  {t(`staff.reports.types.${type}`)}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label={t('staff.reports.from')}>
          {(f) => <input {...f} type="date" value={draft.from} max={draft.to} onChange={(e) => set({ from: e.target.value || today })} className={inputClass} />}
        </Field>
        <Field label={t('staff.reports.to')}>
          {(f) => <input {...f} type="date" value={draft.to} min={draft.from} onChange={(e) => set({ to: e.target.value || draft.from })} className={inputClass} />}
        </Field>
        <Field label={t('staff.reports.branch')}>
          {(f) => (
            <select {...f} value={branch ?? ''} onChange={(e) => setBranchChoice(e.target.value)} className={inputClass}>
              <option value="">{t('staff.reports.allBranches')}</option>
              {branches?.map((b) => (
                <option key={b.code} value={b.code}>
                  {b.city}
                </option>
              ))}
            </select>
          )}
        </Field>
        {PERIOD_REPORTS.includes(draft.type) && (
          <Field label={t('staff.reports.period')}>
            {(f) => (
              <select {...f} value={draft.period} onChange={(e) => set({ period: e.target.value as 'day' | 'month' })} className={inputClass}>
                <option value="day">{t('staff.reports.periods.day')}</option>
                <option value="month">{t('staff.reports.periods.month')}</option>
              </select>
            )}
          </Field>
        )}
        <div className="flex items-end gap-2">
          <Button type="submit">{t('staff.reports.show')}</Button>
          <Button variant="link" onClick={() => set({ from: addDays(today, -29), to: today })}>
            {t('staff.reports.last30')}
          </Button>
        </div>
      </form>

      {exportError && <div className="mb-4"><Alert>{exportError}</Alert></div>}
      {report.isFetching && <p className="text-slate-500">{t('staff.common.loading')}</p>}
      {report.error && <Alert>{report.error.message}</Alert>}

      {r && !report.isFetching && (
        <section aria-labelledby="report-title">
          <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 id="report-title" className="text-lg font-semibold text-slate-900">{r.title}</h2>
              <p className="text-sm text-slate-600">{r.subtitle}</p>
              {shown?.type === 'daily-register' && <p className="text-xs text-slate-500">{t('staff.reports.mobileMasked')}</p>}
            </div>
            <div className="flex gap-2 print:hidden">
              <Button variant="secondary" onClick={() => download('csv')}>{t('staff.dashboard.exportCsv')}</Button>
              <Button variant="secondary" onClick={() => download('xlsx')}>{t('staff.dashboard.exportXlsx')}</Button>
              <Button variant="secondary" onClick={() => window.print()}>{t('staff.common.print')}</Button>
            </div>
          </div>
          {r.rows.length === 0 ? (
            <p className="rounded-xl border border-slate-200 bg-white p-6 text-slate-600">{t('staff.reports.empty')}</p>
          ) : (
            <>
              <ScrollRegion label={r.title} className="rounded-xl border border-slate-200 bg-white print:overflow-visible print:border-0">
                <table className="w-full text-sm">
                  <thead className="bg-slate-50 text-left text-slate-600">
                    <tr>
                      {r.columns.map((c) => (
                        <th key={c} scope="col" className="px-3 py-2 font-medium whitespace-nowrap">{c}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {r.rows.map((row, i) => (
                      <tr key={i}>
                        {row.map((cell, j) => (
                          <td key={j} className={`px-3 py-1.5 ${typeof cell === 'number' ? 'text-right tabular-nums' : ''}`}>
                            {cell ?? '—'}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </ScrollRegion>
              <p className="mt-2 text-sm text-slate-500">{t('staff.reports.rows', { count: r.rows.length })}</p>
            </>
          )}
        </section>
      )}
    </>
  )
}
