import { lazy, Suspense } from 'react'
import { Outlet } from 'react-router'

import { reloadForNewVersion } from '../../lib/reloadForNewVersion'

// The staff portal is loaded only when someone opens /staff, so public visitors on mobile data
// never download it (separate bundles).

export const StaffRoot = lazy(() => import('./StaffLayout').then((m) => ({ default: m.StaffRoot }), reloadForNewVersion))
export const StaffLayout = lazy(() => import('./StaffLayout').then((m) => ({ default: m.StaffLayout }), reloadForNewVersion))
export const RequireRole = lazy(() => import('./StaffLayout').then((m) => ({ default: m.RequireRole }), reloadForNewVersion))
export const LoginPage = lazy(() => import('./LoginPage').then((m) => ({ default: m.LoginPage }), reloadForNewVersion))
export const DashboardPage = lazy(() => import('./dashboard/DashboardPage').then((m) => ({ default: m.DashboardPage }), reloadForNewVersion))
export const AppointmentsPage = lazy(() => import('./appointments/AppointmentsPage').then((m) => ({ default: m.AppointmentsPage }), reloadForNewVersion))
export const RecentBookingsPage = lazy(() => import('./appointments/RecentBookingsPage').then((m) => ({ default: m.RecentBookingsPage }), reloadForNewVersion))
export const PatientsPage = lazy(() => import('./patients/PatientsPage').then((m) => ({ default: m.PatientsPage }), reloadForNewVersion))
export const NewPatientPage = lazy(() => import('./patients/PatientsPage').then((m) => ({ default: m.NewPatientPage }), reloadForNewVersion))
export const PatientProfilePage = lazy(() => import('./patients/PatientProfilePage').then((m) => ({ default: m.PatientProfilePage }), reloadForNewVersion))
export const QueuePage = lazy(() => import('./clinical/QueuePage').then((m) => ({ default: m.QueuePage }), reloadForNewVersion))
export const VisitPage = lazy(() => import('./clinical/VisitPage').then((m) => ({ default: m.VisitPage }), reloadForNewVersion))
export const PrescriptionPrintPage = lazy(() => import('./clinical/PrescriptionPrintPage').then((m) => ({ default: m.PrescriptionPrintPage }), reloadForNewVersion))
export const HistoryPage = lazy(() => import('./clinical/HistoryPage').then((m) => ({ default: m.HistoryPage }), reloadForNewVersion))
export const ReportsPage = lazy(() => import('./reports/ReportsPage').then((m) => ({ default: m.ReportsPage }), reloadForNewVersion))
export const ProfilePage = lazy(() => import('./StaffPages').then((m) => ({ default: m.ProfilePage }), reloadForNewVersion))
export const UsersPage = lazy(() => import('./admin/UsersPage').then((m) => ({ default: m.UsersPage }), reloadForNewVersion))
export const DoctorsAdminPage = lazy(() => import('./admin/DoctorsAdminPage').then((m) => ({ default: m.DoctorsAdminPage }), reloadForNewVersion))
export const BranchesAdminPage = lazy(() => import('./admin/BranchesAdminPage').then((m) => ({ default: m.BranchesAdminPage }), reloadForNewVersion))
export const GalleryAdminPage = lazy(() => import('./admin/GalleryAdminPage').then((m) => ({ default: m.GalleryAdminPage }), reloadForNewVersion))
export const SchedulePage = lazy(() => import('./schedule/SchedulePage').then((m) => ({ default: m.SchedulePage }), reloadForNewVersion))
export const CounterBookingPage = lazy(() => import('./booking/CounterBookingPage').then((m) => ({ default: m.CounterBookingPage }), reloadForNewVersion))
export const AuditPage = lazy(() => import('./admin/AuditPage').then((m) => ({ default: m.AuditPage }), reloadForNewVersion))

/** Loading line while the first staff bundle downloads (before the session provider exists). */
export function StaffSuspense() {
  return (
    <Suspense fallback={<p className="p-8 text-slate-500">Loading…</p>}>
      <Outlet />
    </Suspense>
  )
}
