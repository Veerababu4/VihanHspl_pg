import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type { AppointmentStatus } from './booking'
import { apiGet, apiSend } from './client'
import type { PatientExtras } from './patients'

// Reception, dashboard and reports API (backend ReceptionDtos, PatientDtos, DashboardDtos,
// ReportTable; DESIGN.md §11.4, §11.5).

export type Channel = 'ONLINE' | 'COUNTER' | 'PHONE' | 'WALK_IN'

/** What the logged-in user may do with this appointment now (decided by the server). */
export interface Actions {
  checkIn: boolean
  cancel: boolean
  noShow: boolean
  complete: boolean
  reschedule: boolean
}

export interface StaffAppointment {
  id: number
  bookingRef: string
  status: AppointmentStatus
  tokenNo: number | null
  date: string
  /** null for walk-ins */
  startAt: string | null
  doctorId: number
  doctorName: string | null
  speciality: string | null
  branchCode: string | null
  branchCity: string | null
  patientId: number | null
  uhid: string | null
  patientName: string
  patientAge: number | null
  patientGender: 'M' | 'F' | 'O' | null
  mobile: string
  /** 3 or more no-shows in the last 30 days */
  frequentNoShow: boolean
  reason: string | null
  channel: Channel
  needsAction: boolean
  cancelReason: string | null
  cancelledBy: 'PATIENT' | 'STAFF' | null
  rescheduleCount: number
  checkedInAt: string | null
  /** the visit opened at check-in; the doctor opens it from the list */
  visitId: number | null
  actions: Actions
}

export interface PatientSummary {
  id: number
  uhid: string
  fullName: string
  age: number
  dateOfBirth: string
  dobEstimated: boolean
  gender: 'M' | 'F' | 'O'
  mobile: string
  registeredBranchCode: string | null
  /** "XXXX-XXXX-1234"; the full number never reaches the browser */
  aadhaarMasked: string | null
}

export interface PatientMatch {
  patient: PatientSummary
  sameMobile: boolean
  sameName: boolean
  ageClose: boolean
}

export interface RegisterPatientInput extends Partial<PatientExtras> {
  fullName: string
  age: number | null
  dateOfBirth: string | null
  gender: 'M' | 'F' | 'O'
  mobile: string
  branch: string | null
  consent: boolean
  /** the user saw the "possibly the same person" warning and chose to go on */
  confirmDuplicate?: boolean
}

export interface AppointmentFilters {
  date: string
  branch?: string
  doctor?: number
  status?: AppointmentStatus
  q?: string
}

const query = (params: Record<string, string | number | undefined>) => {
  const s = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => v !== undefined && v !== '' && s.set(k, String(v)))
  const str = s.toString()
  return str ? `?${str}` : ''
}

export const appointmentsUrl = (f: AppointmentFilters) =>
  `/staff/appointments${query({ date: f.date, branch: f.branch, doctor: f.doctor, status: f.status, q: f.q })}`

export const useStaffAppointments = (f: AppointmentFilters) =>
  useQuery({ queryKey: ['staff-appointments', f], queryFn: () => apiGet<StaffAppointment[]>(appointmentsUrl(f)) })

/** The latest 20 bookings (a doctor's own), or the latest 20 matching name, mobile or booking reference. */
export const useRecentBookings = (q: string | undefined) =>
  useQuery({
    queryKey: ['staff-appointments', 'recent', q],
    queryFn: () => apiGet<StaffAppointment[]>(`/staff/appointments/recent${query({ q })}`),
  })

export const useNeedsAction = (branch: string | undefined, enabled: boolean) =>
  useQuery({
    queryKey: ['needs-action', branch],
    queryFn: () => apiGet<StaffAppointment[]>(`/staff/appointments/needs-action${query({ branch })}`),
    enabled,
  })

export const usePatientMatches = (appointmentId: number) =>
  useQuery({
    queryKey: ['patient-matches', appointmentId],
    queryFn: () => apiGet<PatientMatch[]>(`/staff/appointments/${appointmentId}/patient-matches`),
  })

export const findPatientsByMobile = (mobile: string) =>
  apiGet<PatientSummary[]>(`/staff/patients${query({ mobile })}`)

/** Every change refreshes the lists, the call list and the dashboard. */
function useStaffMutation<V>(fn: (v: V) => Promise<StaffAppointment>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: () =>
      Promise.all(
        ['staff-appointments', 'needs-action', 'dashboard', 'slots'].map((k) => queryClient.invalidateQueries({ queryKey: [k] })),
      ),
  })
}

export const useCheckIn = () =>
  useStaffMutation(({ id, patientId }: { id: number; patientId: number }) =>
    apiSend<StaffAppointment>('POST', `/staff/appointments/${id}/check-in`, { patientId }),
  )

export const useChangeStatus = () =>
  useStaffMutation(({ id, status, reason }: { id: number; status: 'NO_SHOW' | 'CANCELLED' | 'COMPLETED'; reason?: string }) =>
    apiSend<StaffAppointment>('PATCH', `/staff/appointments/${id}/status`, { status, reason: reason ?? null }),
  )

export const useStaffReschedule = () =>
  useStaffMutation(({ id, newSlotId, reason }: { id: number; newSlotId: number; reason: string }) =>
    apiSend<StaffAppointment>('POST', `/staff/appointments/${id}/reschedule`, { newSlotId, reason }),
  )

export const useWalkIn = () =>
  useStaffMutation((input: { patientId: number; doctorId: number; branch: string; reason: string | null }) =>
    apiSend<StaffAppointment>('POST', '/staff/appointments/walk-in', input),
  )

export const useRegisterPatient = () =>
  useMutation({ mutationFn: (input: RegisterPatientInput) => apiSend<PatientSummary>('POST', '/staff/patients', input) })

// ---- dashboard ----

export interface Counts {
  booked: number
  checkedIn: number
  completed: number
  cancelled: number
  noShow: number
  /** everything except cancelled */
  total: number
  openSlots: number
}

export interface DashboardSummary {
  date: string
  branch: string | null
  doctorOnly: boolean
  network: Counts
  branches: { branchCode: string; branchName: string; branchCity: string; counts: Counts }[]
  doctors: { doctorId: number; doctorName: string | null; speciality: string | null; branchId: number; branchCode: string; branchCity: string; counts: Counts }[]
}

export const dashboardUrl = (date: string, branch: string | undefined) =>
  `/staff/dashboard/summary${query({ date, branch })}`

export const dashboardExportUrl = (date: string, branch: string | undefined, format: 'csv' | 'xlsx') =>
  `/staff/dashboard/summary/export${query({ date, branch, format })}`

export const useDashboard = (date: string, branch: string | undefined) =>
  useQuery({ queryKey: ['dashboard', date, branch], queryFn: () => apiGet<DashboardSummary>(dashboardUrl(date, branch)) })

// ---- reports ----

export const REPORT_TYPES = ['daily-register', 'doctor-summary', 'branch-summary', 'registrations', 'channels', 'no-show-cancel'] as const
export type ReportType = (typeof REPORT_TYPES)[number]

/** Reports that group by day or month. */
export const PERIOD_REPORTS: ReportType[] = ['branch-summary', 'registrations', 'no-show-cancel']

export interface ReportTable {
  title: string
  subtitle: string
  columns: string[]
  rows: (string | number | null)[][]
}

export interface ReportParams {
  type: ReportType
  from: string
  to: string
  branch?: string
  period?: 'day' | 'month'
}

export const reportUrl = (p: ReportParams, format: 'json' | 'csv' | 'xlsx' = 'json') =>
  `/staff/reports/${p.type}${query({ from: p.from, to: p.to, branch: p.branch, period: p.period, format })}`

export const useReport = (p: ReportParams | null) =>
  useQuery({
    queryKey: ['report', p],
    queryFn: () => apiGet<ReportTable>(reportUrl(p!)),
    enabled: p !== null,
  })
