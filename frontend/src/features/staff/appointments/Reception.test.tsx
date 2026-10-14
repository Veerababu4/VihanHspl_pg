import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { DashboardSummary, PatientSummary, ReportTable, StaffAppointment } from '../../../api/reception'
import type { Me } from '../../../auth/session'
import { todayIso } from '../../../lib/dates'
import { routes } from '../../../routes'
import { drHaritha, huzurnagar, kodad, layoutApi } from '../../../test/fixtures'
import { mockApi, renderRoutes, requestBody } from '../../../test/render'

const receptionist: Me = { id: 2, username: 'reception.kdd', fullName: 'Asha Receptionist', role: 'RECEPTIONIST', doctorId: null, homeBranchId: 3, mustChangePassword: false }
const doctor: Me = { ...receptionist, id: 9, username: 'doctor012', fullName: 'Dr. Haritha Kommu', role: 'DOCTOR', doctorId: 12, homeBranchId: null }
const session = (user: Me) => ({ accessToken: 't', expiresAt: '2026-10-07T12:00:00Z', user })
const today = todayIso()

const noActions = { checkIn: false, cancel: false, noShow: false, complete: false, reschedule: false }
const booking = (over: Partial<StaffAppointment> = {}): StaffAppointment => ({
  id: 7, bookingRef: 'VMH-261007-7K3Q', status: 'BOOKED', tokenNo: 2, date: today, startAt: `${today}T10:20:00`,
  doctorId: 12, doctorName: 'Dr. Haritha Kommu', speciality: 'ENT', branchCode: 'KDD', branchCity: 'Kodad',
  patientId: null, uhid: null, patientName: 'Ravi Kumar', patientAge: 42, patientGender: 'M', mobile: '9876543210',
  frequentNoShow: false, reason: 'Ear pain', channel: 'ONLINE', needsAction: false, cancelReason: null, cancelledBy: null,
  rescheduleCount: 0, checkedInAt: null, visitId: null,
  actions: { ...noActions, checkIn: true, cancel: true, reschedule: true },
  ...over,
})
const ravi: PatientSummary = {
  id: 11, uhid: 'VMH-26-000011', fullName: 'Ravi Kumar', age: 42, dateOfBirth: '1984-07-01', dobEstimated: true, gender: 'M',
  mobile: '9876543210', registeredBranchCode: 'KDD', aadhaarMasked: null,
}
const checkedIn = booking({ status: 'CHECKED_IN', patientId: 11, uhid: ravi.uhid, actions: noActions })

/** A receptionist at Kodad: the list opens on her home branch. */
const deskApi = {
  ...layoutApi,
  '/public/branches': [huzurnagar, kodad],
  'POST /auth/refresh': session(receptionist),
  '/public/doctors': [drHaritha],
  '/public/doctors?branch=KDD': [drHaritha],
  [`/staff/appointments?date=${today}`]: [],
  [`/staff/appointments?date=${today}&branch=KDD`]: [booking()],
  '/staff/appointments/needs-action': [],
  '/staff/appointments/needs-action?branch=KDD': [],
}

// The first test also waits for the lazily loaded staff bundle, hence the longer timeout.
const row = (name: RegExp) => screen.findByRole('listitem', { name }, { timeout: 15000 })

describe('Appointments (reception)', () => {
  it('checks a booking in as a suggested registered patient', async () => {
    const spy = mockApi({
      ...deskApi,
      '/staff/appointments/7/patient-matches': [{ patient: ravi, sameMobile: true, sameName: true, ageClose: true }],
      'POST /staff/appointments/7/check-in': checkedIn,
    })
    renderRoutes(routes, '/staff/appointments')

    const item = await row(/^Ravi Kumar, 10:20 AM/)
    expect(within(item).getByText('9876543210')).toBeInTheDocument()   // staff see the full number
    expect(within(item).queryByRole('button', { name: 'Complete' })).not.toBeInTheDocument()
    fireEvent.click(within(item).getByRole('button', { name: 'Check in' }))

    const dialog = await screen.findByRole('dialog', { name: 'Check in Ravi Kumar' })
    expect(await within(dialog).findByText('same mobile')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Check in as this patient' }))

    expect(await screen.findByText('Ravi Kumar checked in (token 2).')).toBeInTheDocument()
    expect(requestBody(spy, 'POST /staff/appointments/7/check-in')).toEqual({ patientId: 11 })
  })

  it('registers a new patient pre-filled from the booking, then checks in', async () => {
    const spy = mockApi({
      ...deskApi,
      '/staff/appointments/7/patient-matches': [],
      'POST /staff/patients': { ...ravi, id: 12 },
      'POST /staff/appointments/7/check-in': checkedIn,
    })
    renderRoutes(routes, '/staff/appointments')

    fireEvent.click(within(await row(/^Ravi Kumar/)).getByRole('button', { name: 'Check in' }))
    const dialog = await screen.findByRole('dialog', { name: 'Check in Ravi Kumar' })
    expect(await within(dialog).findByText('No registered patient with this mobile or name.')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Register as a new patient' }))

    expect(within(dialog).getByLabelText('Full name')).toHaveValue('Ravi Kumar')
    expect(within(dialog).getByLabelText('Age (years)')).toHaveValue('42')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Register as a new patient' }))
    expect(await within(dialog).findByText('The patient must agree to continue')).toBeInTheDocument()

    fireEvent.click(within(dialog).getByLabelText('The patient agreed to the privacy notice'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Register as a new patient' }))

    expect(await screen.findByText('Ravi Kumar checked in (token 2).')).toBeInTheDocument()
    expect(requestBody(spy, 'POST /staff/patients')).toEqual({
      fullName: 'Ravi Kumar', age: 42, dateOfBirth: null, gender: 'M', mobile: '9876543210', branch: 'KDD', consent: true,
      aadhaar: null, altMobile: null, address: null, city: null, bloodGroup: null, emergencyName: null, emergencyPhone: null,
      confirmDuplicate: false,
    })
    expect(requestBody(spy, 'POST /staff/appointments/7/check-in')).toEqual({ patientId: 12 })
  })

  it('needs a reason to cancel', async () => {
    const spy = mockApi({
      ...deskApi,
      'PATCH /staff/appointments/7/status': booking({ status: 'CANCELLED', cancelReason: 'Patient phoned', actions: noActions }),
    })
    renderRoutes(routes, '/staff/appointments')

    fireEvent.click(within(await row(/^Ravi Kumar/)).getByRole('button', { name: 'Cancel' }))
    const dialog = screen.getByRole('dialog', { name: 'Cancel booking' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel booking' }))
    expect(within(dialog).getByText('Please give a reason')).toBeInTheDocument()

    fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: 'Patient phoned' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel booking' }))
    expect(await screen.findByText('Booking of Ravi Kumar cancelled.')).toBeInTheDocument()
    expect(requestBody(spy, 'PATCH /staff/appointments/7/status')).toEqual({ status: 'CANCELLED', reason: 'Patient phoned' })
  })

  it('lists bookings hit by leave so reception can call the patients', async () => {
    mockApi({
      ...deskApi,
      '/staff/appointments/needs-action?branch=KDD': [booking({ id: 8, patientName: 'Sita Rani', date: '2026-10-09', startAt: '2026-10-09T11:00:00', needsAction: true })],
    })
    renderRoutes(routes, '/staff/appointments')

    const panel = await screen.findByRole('region', { name: 'Patients to call (1)' })
    const item = within(panel).getByRole('listitem', { name: /^Sita Rani/ })
    expect(within(item).getByRole('link', { name: '9876543210' })).toHaveAttribute('href', 'tel:9876543210')
    expect(within(item).getByRole('button', { name: 'Reschedule' })).toBeInTheDocument()
  })

  it('adds a registered walk-in patient to a doctor’s queue', async () => {
    const spy = mockApi({
      ...deskApi,
      '/staff/patients?mobile=9876543210': [ravi],
      'POST /staff/appointments/walk-in': booking({ id: 20, tokenNo: 14, startAt: null, channel: 'WALK_IN', status: 'CHECKED_IN', actions: noActions }),
    })
    renderRoutes(routes, '/staff/appointments')

    fireEvent.click(await screen.findByRole('button', { name: 'Walk-in' }))
    const dialog = screen.getByRole('dialog', { name: 'Walk-in patient' })
    fireEvent.change(within(dialog).getByLabelText('Patient mobile number'), { target: { value: '+91 98765 43210' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Find' }))
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Choose' }))

    expect(within(dialog).getByText('Patient: Ravi Kumar (VMH-26-000011)')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Hospital')).toHaveValue('KDD')
    fireEvent.change(await within(dialog).findByLabelText('Doctor'), { target: { value: '12' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add to doctor’s queue' }))

    expect(await screen.findByText('Ravi Kumar added as walk-in, token 14.')).toBeInTheDocument()
    expect(requestBody(spy, 'POST /staff/appointments/walk-in')).toEqual({ patientId: 11, doctorId: 12, branch: 'KDD', reason: null })
  })
})

describe('Appointments (doctor)', () => {
  it('shows only the doctor’s own list and lets them complete a visit', async () => {
    const spy = mockApi({
      ...layoutApi,
      'POST /auth/refresh': session(doctor),
      '/public/doctors': [drHaritha],
      [`/staff/appointments?date=${today}`]: [{ ...checkedIn, actions: { ...noActions, complete: true } }],
      'PATCH /staff/appointments/7/status': { ...checkedIn, status: 'COMPLETED' },
    })
    renderRoutes(routes, '/staff/appointments')

    const item = await row(/^Ravi Kumar/)
    expect(screen.queryByLabelText('Doctor')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Walk-in' })).not.toBeInTheDocument()
    expect(within(item).getByText('UHID VMH-26-000011')).toBeInTheDocument()

    fireEvent.click(within(item).getByRole('button', { name: 'Complete' }))
    const dialog = screen.getByRole('dialog', { name: 'Mark the visit of Ravi Kumar as completed?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Complete' }))
    expect(await screen.findByText('Visit of Ravi Kumar completed.')).toBeInTheDocument()
    expect(requestBody(spy, 'PATCH /staff/appointments/7/status')).toEqual({ status: 'COMPLETED', reason: null })
    expect(spy.mock.calls.some(([url]) => String(url).includes('needs-action'))).toBe(false)
  })
})

// ---- dashboard and reports ----

const counts = (total: number, open: number) => ({ booked: total, checkedIn: 0, completed: 0, cancelled: 1, noShow: 0, total, openSlots: open })
const summary: DashboardSummary = {
  date: today, branch: 'KDD', doctorOnly: false, network: counts(3, 20),
  branches: [{ branchCode: 'KDD', branchName: 'Kodad', branchCity: 'Kodad', counts: counts(3, 20) }],
  doctors: [{ doctorId: 12, doctorName: 'Dr. Haritha Kommu', speciality: 'ENT', branchId: 3, branchCode: 'KDD', branchCity: 'Kodad', counts: counts(3, 20) }],
}

describe('Dashboard', () => {
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => 'blob:export')
    URL.revokeObjectURL = vi.fn()
  })

  it('opens on the receptionist’s branch and exports the table', async () => {
    const exportUrl = `/staff/dashboard/summary/export?date=${today}&branch=KDD&format=csv`
    const spy = mockApi({
      ...deskApi,
      [`/staff/dashboard/summary?date=${today}`]: { ...summary, branch: null },
      [`/staff/dashboard/summary?date=${today}&branch=KDD`]: summary,
      [exportUrl]: new Response('Branch,Doctor\r\n', { headers: { 'Content-Type': 'text/csv' } }),
    })
    renderRoutes(routes, '/staff/dashboard')

    await waitFor(() => expect(screen.getByLabelText('Hospital')).toHaveValue('KDD'))
    const card = await screen.findByRole('region', { name: 'Kodad' })   // one hospital chosen: its name, not "All hospitals"
    expect(within(card).getByText('3', { selector: 'p' })).toBeInTheDocument()   // the total
    const link = await screen.findByRole('link', { name: 'Dr. Haritha Kommu' })
    expect(link).toHaveAttribute('href', `/staff/appointments?date=${today}&branch=KDD&doctor=12`)

    fireEvent.click(screen.getByRole('button', { name: 'Export CSV' }))
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled())
    expect(spy.mock.calls.some(([url]) => String(url) === `/api${exportUrl}`)).toBe(true)
  })

  it('shows a doctor only their own figures, without export', async () => {
    mockApi({
      ...layoutApi,
      'POST /auth/refresh': session(doctor),
      [`/staff/dashboard/summary?date=${today}`]: { ...summary, branch: null, doctorOnly: true },
    })
    renderRoutes(routes, '/staff/dashboard')

    expect(await screen.findByRole('region', { name: 'Your appointments' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Hospital')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Export CSV' })).not.toBeInTheDocument()
  })
})

describe('Reports', () => {
  it('shows the chosen report and offers the group-by choice only where it applies', async () => {
    const table: ReportTable = {
      title: 'Online vs counter bookings', subtitle: `${today} · Kodad`,
      columns: ['Branch', 'Online', 'Counter', 'Phone', 'Walk-in', 'Total', 'Online %'],
      rows: [['Kodad', 2, 0, 1, 0, 3, 66.7]],
    }
    const spy = mockApi({ ...deskApi, [`/staff/reports/channels?from=${today}&to=${today}&branch=KDD&format=json`]: table })
    renderRoutes(routes, '/staff/reports')

    await waitFor(() => expect(screen.getByLabelText('Hospital')).toHaveValue('KDD'))
    fireEvent.change(screen.getByLabelText('Report'), { target: { value: 'registrations' } })
    expect(screen.getByLabelText('Group by')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Report'), { target: { value: 'channels' } })
    expect(screen.queryByLabelText('Group by')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Show report' }))

    expect(await screen.findByRole('heading', { name: 'Online vs counter bookings' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '66.7' })).toBeInTheDocument()
    expect(screen.getByText('1 row')).toBeInTheDocument()
    expect(spy.mock.calls.some(([url]) => String(url).includes('format=json'))).toBe(true)
  })

  it('is not offered to doctors', async () => {
    mockApi({ ...layoutApi, 'POST /auth/refresh': session(doctor), [`/staff/dashboard/summary?date=${today}`]: summary })
    renderRoutes(routes, '/staff/reports')
    expect(await screen.findByText('You do not have access to this page.')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Reports' })).not.toBeInTheDocument()
  })
})

describe('Recent bookings', () => {
  it('shows the latest bookings and searches by name, mobile or booking ID', async () => {
    const older = booking({ id: 30, bookingRef: 'VMH-250101-AB23', patientName: 'Sita Rani', date: '2025-01-03', startAt: '2025-01-03T11:00:00', status: 'COMPLETED', actions: noActions })
    mockApi({
      ...deskApi,
      '/staff/appointments/recent': [booking()],
      '/staff/appointments/recent?q=sita': [older],
      '/staff/appointments/recent?q=nobody': [],
    })
    renderRoutes(routes, '/staff/bookings')

    const latest = await row(/^Ravi Kumar/)
    expect(screen.getByText('Latest 1 booking')).toBeInTheDocument()
    expect(within(latest).queryByRole('button', { name: 'Check in' })).not.toBeInTheDocument()   // actions are on the day list
    expect(within(latest).getByRole('link', { name: 'Open in day list' })).toHaveAttribute(
      'href', `/staff/appointments?date=${today}&branch=&q=VMH-261007-7K3Q`,
    )
    expect(screen.getByRole('link', { name: 'Recent bookings' })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Search bookings'), { target: { value: ' sita ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    const found = await row(/^Sita Rani/)
    expect(within(found).getByText('Fri, 3 Jan 2025')).toBeInTheDocument()   // another year shows the year
    expect(screen.getByText('1 booking matches “sita”')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Search bookings'), { target: { value: 'nobody' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText('No bookings match “nobody”.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Show latest' }))
    expect(await row(/^Ravi Kumar/)).toBeInTheDocument()
  })

  it('is open to doctors, with their own bookings', async () => {
    mockApi({ ...layoutApi, 'POST /auth/refresh': session(doctor), '/staff/appointments/recent': [booking()] })
    renderRoutes(routes, '/staff/bookings')

    expect(await row(/^Ravi Kumar/)).toBeInTheDocument()
    expect(screen.getByText('Your latest 20 bookings, newest first. Search finds your bookings on any date.')).toBeInTheDocument()
  })
})
