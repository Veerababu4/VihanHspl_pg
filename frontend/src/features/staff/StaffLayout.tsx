import { Suspense, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, Navigate, NavLink, Outlet, useLocation } from 'react-router'

import { AuthProvider } from '../../auth/AuthContext'
import { useAuth, type Role } from '../../auth/session'
import { Seo } from '../../components/Seo'
import { Button } from '../../components/ui'
import { ChangePasswordForm } from './ChangePasswordForm'

/**
 * Wraps every /staff route with the session; the public site never loads it. Pages are
 * lazy-loaded, so each level has its own Suspense boundary: a page that is still downloading
 * must never unmount the session provider or the layout above it.
 */
export function StaffRoot() {
  return (
    <AuthProvider>
      <Suspense fallback={<Loading />}>
        <Outlet />
      </Suspense>
    </AuthProvider>
  )
}

function Loading() {
  const { t } = useTranslation()
  return <p className="p-8 text-slate-500">{t('staff.common.loading')}</p>
}

interface NavItem {
  to: string
  key: string
  roles: Role[]
}

// Menu per role (DESIGN.md §6).
const NAV: NavItem[] = [
  { to: '/staff/dashboard', key: 'staff.nav.dashboard', roles: ['DOCTOR', 'RECEPTIONIST', 'IT_ADMIN'] },
  { to: '/staff/queue', key: 'staff.nav.queue', roles: ['DOCTOR'] },
  { to: '/staff/appointments', key: 'staff.nav.appointments', roles: ['DOCTOR', 'RECEPTIONIST', 'IT_ADMIN'] },
  { to: '/staff/bookings', key: 'staff.nav.recentBookings', roles: ['DOCTOR', 'RECEPTIONIST', 'IT_ADMIN'] },
  { to: '/staff/patients', key: 'staff.nav.patients', roles: ['DOCTOR', 'RECEPTIONIST', 'IT_ADMIN'] },
  { to: '/staff/book', key: 'staff.nav.newBooking', roles: ['RECEPTIONIST', 'IT_ADMIN'] },
  { to: '/staff/reports', key: 'staff.nav.reports', roles: ['RECEPTIONIST', 'IT_ADMIN'] },
  { to: '/staff/schedule', key: 'staff.nav.schedule', roles: ['DOCTOR', 'IT_ADMIN'] },
  { to: '/staff/admin/users', key: 'staff.nav.users', roles: ['IT_ADMIN'] },
  { to: '/staff/admin/doctors', key: 'staff.nav.doctors', roles: ['IT_ADMIN'] },
  { to: '/staff/admin/branches', key: 'staff.nav.branches', roles: ['IT_ADMIN'] },
  { to: '/staff/admin/gallery', key: 'staff.nav.gallery', roles: ['IT_ADMIN'] },
  { to: '/staff/admin/audit', key: 'staff.nav.audit', roles: ['IT_ADMIN'] },
  { to: '/staff/profile', key: 'staff.nav.profile', roles: ['DOCTOR', 'RECEPTIONIST', 'IT_ADMIN'] },
]

/** Logged-in area: redirects to login when there is no session; forces a temporary password change. */
export function StaffLayout() {
  const { t } = useTranslation()
  const { status, user, loggedOut, logout } = useAuth()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)

  if (status === 'checking') {
    return <Loading />
  }
  if (status === 'anonymous' || !user) {
    // Expired session: come back here after logging in. Explicit logout: start fresh, because the
    // next person at this computer may have a different role.
    return <Navigate to="/staff/login" replace state={loggedOut ? undefined : { from: location.pathname + location.search }} />
  }

  const onLogout = () => void logout()   // the anonymous state above redirects to the login page

  if (user.mustChangePassword) {
    return (
      <div className="min-h-screen bg-slate-50">
        <header className="flex items-center justify-between gap-4 border-b border-slate-200 bg-white px-4 py-3 md:px-8">
          <Brand />
          <UserMenu name={user.fullName} role={user.role} onLogout={onLogout} />
        </header>
        <div className="mx-auto max-w-lg px-4 py-12">
          <Seo title={t('staff.password.forcedTitle')} />
          <h1 className="text-2xl font-bold text-slate-900">{t('staff.password.forcedTitle')}</h1>
          <p className="mt-2 mb-6 text-slate-600">{t('staff.password.forcedText')}</p>
          <ChangePasswordForm />
        </div>
      </div>
    )
  }

  const items = NAV.filter((n) => n.roles.includes(user.role))
  const nav = (
    <ul className="space-y-1">
      {items.map((n) => (
        <li key={n.to}>
          <NavLink
            to={n.to}
            onClick={() => setMenuOpen(false)}
            className={({ isActive }) =>
              `block rounded-lg px-3 py-2 text-sm font-medium ${isActive ? 'bg-brand-600 text-white' : 'text-slate-700 hover:bg-slate-100'}`
            }
          >
            {t(n.key)}
          </NavLink>
        </li>
      ))}
    </ul>
  )

  return (
    <div className="min-h-screen bg-slate-50 md:flex">
      <aside className="hidden w-60 shrink-0 flex-col border-r border-slate-200 bg-white p-4 md:flex print:hidden">
        <Brand />
        <nav aria-label={t('staff.nav.menu')} className="mt-6 flex-1">
          {nav}
        </nav>
      </aside>

      <header className="flex items-center justify-between border-b border-slate-200 bg-white px-4 py-3 md:hidden print:hidden">
        <Brand />
        <button
          type="button"
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((o) => !o)}
          className="rounded-md border border-slate-300 px-3 py-1.5 text-sm"
        >
          {t('staff.nav.menu')}
        </button>
      </header>
      {menuOpen && (
        <div className="border-b border-slate-200 bg-white p-4 md:hidden">
          <nav aria-label={t('staff.nav.menu')}>{nav}</nav>
          <UserBox name={user.fullName} role={user.role} onLogout={onLogout} />
        </div>
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        {/* Who is logged in + Log out, top right on larger screens; phones have them in the menu. */}
        <header className="hidden items-center justify-end border-b border-slate-200 bg-white px-8 py-3 md:flex print:hidden">
          <UserMenu name={user.fullName} role={user.role} onLogout={onLogout} />
        </header>
        <main className="min-w-0 flex-1 px-4 py-6 md:px-8">
          <Suspense fallback={<Loading />}>
            <Outlet />
          </Suspense>
        </main>
      </div>
    </div>
  )
}

function Brand() {
  return (
    <Link to="/staff/dashboard" className="flex items-center gap-2">
      <img src="/favicon.svg" alt="" className="h-8 w-8" />
      <span className="font-bold text-brand-700">Vihan Staff</span>
    </Link>
  )
}

/** Top-right bar: name and role, link to the website, Log out. */
function UserMenu({ name, role, onLogout }: { name: string; role: Role; onLogout: () => void }) {
  const { t } = useTranslation()
  return (
    <div className="flex items-center gap-4 text-sm">
      <p className="text-right leading-tight">
        <span className="block font-medium text-slate-900">{name}</span>
        <span className="block text-slate-500">{t(`staff.roles.${role}`)}</span>
      </p>
      <Link to="/" className="hidden text-slate-600 hover:underline sm:inline">
        {t('staff.nav.viewSite')}
      </Link>
      <Button variant="secondary" onClick={onLogout}>
        {t('staff.nav.logout')}
      </Button>
    </div>
  )
}

/** The same, inside the phone menu. */
function UserBox({ name, role, onLogout }: { name: string; role: Role; onLogout: () => void }) {
  const { t } = useTranslation()
  return (
    <div className="mt-4 border-t border-slate-200 pt-4 text-sm">
      <p className="font-medium text-slate-900">{name}</p>
      <p className="text-slate-500">{t(`staff.roles.${role}`)}</p>
      <div className="mt-2 flex gap-4">
        <Button variant="link" onClick={onLogout}>
          {t('staff.nav.logout')}
        </Button>
        <Link to="/" className="text-slate-500 hover:underline">
          {t('staff.nav.viewSite')}
        </Link>
      </div>
    </div>
  )
}

/** Hides a page from roles that can't use it (the API refuses them anyway). */
export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  if (!user || !roles.includes(user.role)) {
    return <p className="text-slate-600">{t('staff.forbidden')}</p>
  }
  return <>{children}</>
}
