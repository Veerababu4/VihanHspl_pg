import type { ReactNode } from 'react'
import { createBrowserRouter, Navigate, type RouteObject } from 'react-router'

import { PublicLayout } from './components/PublicLayout'
import { NotFoundPage } from './components/SimplePages'
import { BranchPage } from './features/public/BranchPage'
import { DoctorProfilePage } from './features/public/DoctorProfilePage'
import { DoctorsPage } from './features/public/DoctorsPage'
import { GalleryPage } from './features/public/GalleryPage'
import { HomePage } from './features/public/HomePage'
import { AppointmentStatusPage, BookingPage, ConfirmationPage } from './features/public/booking/lazyBooking'
import { AboutPage, ContactPage } from './features/public/InfoPages'
import { PrivacyPage } from './features/public/PrivacyPage'
import {
  AppointmentsPage,
  AuditPage,
  BranchesAdminPage,
  CounterBookingPage,
  DashboardPage,
  DoctorsAdminPage,
  GalleryAdminPage,
  HistoryPage,
  LoginPage,
  NewPatientPage,
  PatientProfilePage,
  PatientsPage,
  PrescriptionPrintPage,
  QueuePage,
  ProfilePage,
  RecentBookingsPage,
  ReportsPage,
  RequireRole,
  SchedulePage,
  StaffLayout,
  StaffRoot,
  StaffSuspense,
  UsersPage,
  VisitPage,
} from './features/staff/lazyPages'

const itAdmin = (element: ReactNode) => <RequireRole roles={['IT_ADMIN']}>{element}</RequireRole>
// Clinical pages: doctors only (the API refuses everyone else too, DESIGN.md §12.3).
const doctor = (element: ReactNode) => <RequireRole roles={['DOCTOR']}>{element}</RequireRole>

// Route map: DESIGN.md §6. Placeholders are replaced milestone by milestone.
export const routes: RouteObject[] = [
  {
    element: <PublicLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'doctors', element: <DoctorsPage /> },
      { path: 'doctors/:id', element: <DoctorProfilePage /> },
      { path: 'branches/:code', element: <BranchPage /> },
      { path: 'gallery', element: <GalleryPage /> },
      { path: 'about', element: <AboutPage /> },
      { path: 'contact', element: <ContactPage /> },
      { path: 'book', element: <BookingPage /> },
      { path: 'book/confirmation/:ref', element: <ConfirmationPage /> },
      { path: 'appointment-status', element: <AppointmentStatusPage /> },
      { path: 'privacy', element: <PrivacyPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
  {
    path: 'staff',
    element: <StaffSuspense />,
    children: [
      {
        element: <StaffRoot />,
        children: [
          { path: 'login', element: <LoginPage /> },
          {
            element: <StaffLayout />,
            children: [
              { index: true, element: <Navigate to="dashboard" replace /> },
              { path: 'dashboard', element: <DashboardPage /> },
              { path: 'profile', element: <ProfilePage /> },
              { path: 'appointments', element: <AppointmentsPage /> },
              { path: 'bookings', element: <RecentBookingsPage /> },
              { path: 'patients', element: <PatientsPage /> },
              { path: 'patients/new', element: <RequireRole roles={['RECEPTIONIST', 'IT_ADMIN']}><NewPatientPage /></RequireRole> },
              { path: 'patients/:id', element: <PatientProfilePage /> },
              { path: 'patients/:id/history', element: doctor(<HistoryPage />) },
              { path: 'queue', element: doctor(<QueuePage />) },
              { path: 'visits/:id', element: doctor(<VisitPage />) },
              { path: 'visits/:id/print', element: doctor(<PrescriptionPrintPage />) },
              { path: 'reports', element: <RequireRole roles={['RECEPTIONIST', 'IT_ADMIN']}><ReportsPage /></RequireRole> },
              { path: 'book', element: <RequireRole roles={['RECEPTIONIST', 'IT_ADMIN']}><CounterBookingPage /></RequireRole> },
              { path: 'schedule', element: <RequireRole roles={['DOCTOR', 'IT_ADMIN']}><SchedulePage /></RequireRole> },
              { path: 'admin/users', element: itAdmin(<UsersPage />) },
              { path: 'admin/doctors', element: itAdmin(<DoctorsAdminPage />) },
              { path: 'admin/branches', element: itAdmin(<BranchesAdminPage />) },
              { path: 'admin/gallery', element: itAdmin(<GalleryAdminPage />) },
              { path: 'admin/audit', element: itAdmin(<AuditPage />) },
              { path: '*', element: <NotFoundPage /> },
            ],
          },
        ],
      },
    ],
  },
]

export const router = createBrowserRouter(routes)
