import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'

import { useRecentBookings } from '../../../api/reception'
import { useAuth } from '../../../auth/session'
import { Seo } from '../../../components/Seo'
import { Alert, Button, Field, inputClass, PageHeader } from '../../../components/ui'
import { AppointmentRow } from './AppointmentsPage'

/**
 * The latest 20 bookings for IT Admin, reception and every doctor (a doctor sees their own), with a
 * search by name, mobile or booking ID on any date. The search lives in the URL so it can be shared.
 */
export function RecentBookingsPage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const [search, setSearch] = useState(q)
  const list = useRecentBookings(q || undefined)

  const apply = (value: string) => {
    setSearch(value)
    setParams(value ? { q: value } : {}, { replace: true })
  }

  return (
    <>
      <Seo title={t('staff.recentBookings.title')} />
      <PageHeader title={t('staff.recentBookings.title')} />
      <p className="mb-4 text-slate-700">{t(user?.role === 'DOCTOR' ? 'staff.recentBookings.introDoctor' : 'staff.recentBookings.intro')}</p>

      <form
        role="search"
        className="mb-4 flex flex-wrap items-end gap-3"
        onSubmit={(e) => {
          e.preventDefault()
          apply(search.trim())
        }}
      >
        <div className="min-w-0 flex-1 basis-64 sm:max-w-md">
          <Field label={t('staff.recentBookings.search')} hint={t('staff.recentBookings.searchHint')}>
            {(f) => <input {...f} type="search" value={search} onChange={(e) => setSearch(e.target.value)} className={inputClass} />}
          </Field>
        </div>
        <Button type="submit">{t('staff.recentBookings.searchButton')}</Button>
        {q && (
          <Button variant="secondary" onClick={() => apply('')}>
            {t('staff.recentBookings.showLatest')}
          </Button>
        )}
      </form>

      {list.isPending && <p className="text-slate-500">{t('staff.common.loading')}</p>}
      {list.error && <Alert>{list.error.message}</Alert>}
      {list.data && (
        <>
          <p className="mb-2 text-sm text-slate-600" aria-live="polite">
            {q ? t('staff.recentBookings.results', { count: list.data.length, q }) : t('staff.recentBookings.latest', { count: list.data.length })}
          </p>
          {list.data.length === 0 ? (
            <p className="rounded-xl border border-slate-200 bg-white p-6 text-slate-600">
              {q ? t('staff.recentBookings.noMatch', { q }) : t('staff.recentBookings.empty')}
            </p>
          ) : (
            <ul className="space-y-2">
              {list.data.map((a) => (
                <AppointmentRow key={a.id} appointment={a} showDate />
              ))}
            </ul>
          )}
        </>
      )}
    </>
  )
}
