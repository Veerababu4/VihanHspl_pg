import { Suspense, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, NavLink, Outlet, ScrollRestoration } from 'react-router'

import { useAppInfo, useBranches } from '../api/public'
import { EmergencyButton } from './EmergencyButton'
import { TestSiteMarquee } from './TestSiteMarquee'

const NAV_LINKS = [
  { to: '/doctors', key: 'nav.doctors' },
  { to: '/gallery', key: 'nav.gallery' },
  { to: '/about', key: 'nav.about' },
  { to: '/contact', key: 'nav.contact' },
  { to: '/appointment-status', key: 'nav.myBooking' },
] as const

const navClass = ({ isActive }: { isActive: boolean }) =>
  `rounded-md px-3 py-2 hover:text-brand-700 ${isActive ? 'text-brand-700' : 'text-slate-700'}`

export function PublicLayout() {
  const { t } = useTranslation()
  const { data: appInfo } = useAppInfo()
  const [menuOpen, setMenuOpen] = useState(false)
  const closeMenu = () => setMenuOpen(false)

  return (
    <div className="flex min-h-screen flex-col">
      <ScrollRestoration />
      {appInfo?.testNotice && <TestSiteMarquee />}
      {appInfo?.sampleData && (
        <div role="status" className="bg-amber-400 px-4 py-1 text-center text-sm font-semibold text-amber-950 print:hidden">
          {t('banner.sampleData')}
        </div>
      )}
      <header className="sticky top-0 z-10 border-b border-slate-200 bg-white/95 backdrop-blur print:hidden">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3">
          <Link to="/" onClick={closeMenu} className="flex items-center gap-2" aria-label={t('brand.fullName')}>
            <img src="/favicon.svg" alt="" className="h-9 w-9" />
            <span className="leading-tight">
              <span className="block text-xl font-bold text-brand-700">{t('brand.name')}</span>
              <span className="block text-xs text-slate-500">{t('brand.tagline')}</span>
            </span>
          </Link>

          <nav aria-label="Main" className="hidden items-center gap-1 text-sm font-medium lg:flex">
            {NAV_LINKS.map((l) => (
              <NavLink key={l.to} to={l.to} className={navClass}>
                {t(l.key)}
              </NavLink>
            ))}
            <NavLink to="/staff/login" className="px-3 py-2 text-slate-500 hover:text-brand-700">
              {t('nav.staffLogin')}
            </NavLink>
            <Link to="/book" className="ml-2 rounded-lg bg-brand-600 px-4 py-2 text-white hover:bg-brand-700">
              {t('nav.book')}
            </Link>
          </nav>

          <div className="flex items-center gap-2 lg:hidden">
            <Link to="/book" onClick={closeMenu} className="whitespace-nowrap rounded-lg bg-brand-600 px-3 py-2 text-sm font-medium text-white">
              {t('nav.bookShort')}
            </Link>
            <button
              type="button"
              aria-expanded={menuOpen}
              aria-controls="mobile-menu"
              aria-label={menuOpen ? t('nav.closeMenu') : t('nav.menu')}
              onClick={() => setMenuOpen((o) => !o)}
              className="rounded-md p-2 text-slate-700 hover:bg-slate-100"
            >
              <svg viewBox="0 0 24 24" className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                {menuOpen ? <path d="M6 6l12 12M18 6L6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
              </svg>
            </button>
          </div>
        </div>

        {menuOpen && (
          <nav id="mobile-menu" aria-label="Mobile" className="border-t border-slate-200 bg-white lg:hidden">
            <ul className="mx-auto max-w-6xl px-2 py-2 font-medium">
              {NAV_LINKS.map((l) => (
                <li key={l.to}>
                  <NavLink to={l.to} onClick={closeMenu} className={(s) => `block ${navClass(s)}`}>
                    {t(l.key)}
                  </NavLink>
                </li>
              ))}
              <li>
                <NavLink to="/staff/login" onClick={closeMenu} className="block px-3 py-2 text-slate-500">
                  {t('nav.staffLogin')}
                </NavLink>
              </li>
            </ul>
          </nav>
        )}
      </header>

      <main className="flex-1">
        <Suspense fallback={<p className="mx-auto max-w-6xl px-4 py-12 text-slate-500">{t('common.loading')}</p>}>
          <Outlet />
        </Suspense>
      </main>

      <Footer />
      <EmergencyButton />
    </div>
  )
}

const currentYear = new Date().getFullYear()

function Footer() {
  const { t } = useTranslation()
  const { data: branches } = useBranches()

  return (
    <footer className="bg-brand-900 pb-20 text-brand-100 md:pb-0 print:hidden">
      <div className="mx-auto grid max-w-6xl gap-8 px-4 py-10 md:grid-cols-3">
        <div className="md:col-span-2">
          <p className="mb-3 font-semibold text-white">🚑 {t('footer.emergency')}</p>
          <ul className="grid gap-2 text-sm sm:grid-cols-3">
            {branches?.map((b) => (
              <li key={b.id}>
                <Link to={`/branches/${b.code}`} className="hover:underline">
                  {b.city}
                </Link>
                :{' '}
                {b.emergencyPhone ? (
                  <a href={`tel:${b.emergencyPhone}`} className="font-semibold text-white underline">
                    {b.emergencyPhone}
                  </a>
                ) : (
                  '—'
                )}
              </li>
            ))}
          </ul>
        </div>
        <div>
          <p className="mb-3 font-semibold text-white">{t('footer.quickLinks')}</p>
          <ul className="space-y-1 text-sm">
            {NAV_LINKS.map((l) => (
              <li key={l.to}>
                <Link to={l.to} className="hover:underline">
                  {t(l.key)}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      </div>
      <p className="mx-auto max-w-6xl px-4 pb-6 text-xs text-brand-100/70">
        {t('footer.rights', { year: currentYear })}
      </p>
    </footer>
  )
}
