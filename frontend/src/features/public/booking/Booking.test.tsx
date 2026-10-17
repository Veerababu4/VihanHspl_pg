import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { AppointmentView, PublicSlot, SlotDay } from '../../../api/booking'
import { NO_CAPTCHA_TOKEN } from '../../../components/Turnstile'
import { addDays, todayIso } from '../../../lib/dates'
import { routes } from '../../../routes'
import { drRamana, drRamanaDetail, huzurnagar, kodad, layoutApi } from '../../../test/fixtures'
import { json, mockApi, renderRoutes, requestBody } from '../../../test/render'

const tomorrow = addDays(todayIso(), 1)
const days: SlotDay[] = Array.from({ length: 14 }, (_, i) => ({ date: addDays(todayIso(), i), openSlots: i === 1 ? 2 : 0 }))
const slots: PublicSlot[] = [
  { slotId: 501, startAt: `${tomorrow}T10:00:00`, endAt: `${tomorrow}T10:20:00`, tokenNo: 1 },
  { slotId: 502, startAt: `${tomorrow}T10:20:00`, endAt: `${tomorrow}T10:40:00`, tokenNo: 2 },
]
const confirmed: AppointmentView = {
  bookingRef: 'VMH-261006-7K3Q', status: 'BOOKED', tokenNo: 2, date: tomorrow, startAt: `${tomorrow}T10:20:00`, endAt: `${tomorrow}T10:40:00`,
  doctor: { id: 6, fullName: 'Dr. Venkata Ramana Pulla', speciality: 'Cardiology', consultationFee: 800 },
  branch: { code: 'KDD', name: 'Vihan Multi Speciality Hospital - Kodad', city: 'Kodad', address: 'Main Road', phone: '0000000003' },
  patientName: 'Ravi Kumar', patientAge: 42, patientGender: 'M', mobileMasked: '98XXXXXX10', reason: null, channel: 'ONLINE',
  rescheduleCount: 0, reschedulesLeft: 2, canCancel: true, canReschedule: true, changeDeadline: `${tomorrow}T09:20:00`,
  needsAction: false, cancelReason: null, uhid: null,
}

const api = {
  ...layoutApi,
  '/public/branches': [huzurnagar, kodad],
  '/public/specialities': [],
  '/public/doctors/6': drRamanaDetail,
  '/public/doctors?branch=KDD': [drRamana],
  '/public/doctors/6/slot-days?branch=KDD': days,
  [`/public/doctors/6/slots?branch=KDD&date=${tomorrow}`]: slots,
}

async function chooseTimeAndFillDetails() {
  // "Continue" is disabled until the hospital and doctor lists have loaded.
  const next = await screen.findByRole('button', { name: 'Continue' })
  await waitFor(() => expect(next).toBeEnabled())
  fireEvent.click(next)
  fireEvent.click(await screen.findByRole('button', { name: /2 free/ }))
  fireEvent.click(await screen.findByRole('button', { name: '10:20, token 2' }))
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
  fireEvent.change(await screen.findByLabelText('Patient name'), { target: { value: 'Ravi Kumar' } })
  fireEvent.change(screen.getByLabelText('Age (years)'), { target: { value: '42' } })
  fireEvent.click(screen.getByLabelText('Male'))
  fireEvent.change(screen.getByLabelText('Mobile number'), { target: { value: '+91 98765 43210' } })
  fireEvent.click(screen.getByRole('checkbox'))
}

describe('Booking wizard', () => {
  it('books the chosen time and shows the confirmation with reference and token', async () => {
    const spy = mockApi({ ...api, 'POST /public/appointments': json(confirmed, 201) })
    renderRoutes(routes, '/book?doctor=6&branch=kdd')

    // Hospital and doctor come from the doctor's "Book" button.
    expect(await screen.findByRole('radio', { name: /Kodad/ })).toBeChecked()
    expect(await screen.findByRole('radio', { name: /Dr. Venkata Ramana Pulla/ })).toBeChecked()
    await chooseTimeAndFillDetails()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm booking' }))

    expect(await screen.findByRole('heading', { name: /Booking confirmed/ })).toBeInTheDocument()
    expect(screen.getByTestId('booking-ref')).toHaveTextContent('VMH-261006-7K3Q')
    expect(screen.getByTestId('token')).toHaveTextContent('2')
    expect(screen.getByText(/arrive 15 minutes early/)).toBeInTheDocument()
    expect(requestBody(spy, 'POST /public/appointments')).toEqual({
      slotId: 502, patientName: 'Ravi Kumar', patientAge: 42, patientGender: 'M', mobile: '9876543210',
      reason: null, uhid: null, consent: true, captchaToken: NO_CAPTCHA_TOKEN,
    })
  })

  it('offers WhatsApp sharing without the mobile, with the test-project notice', async () => {
    mockApi({ ...api, 'POST /public/appointments': json(confirmed, 201) })
    renderRoutes(routes, '/book?doctor=6&branch=KDD')
    await chooseTimeAndFillDetails()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm booking' }))

    const link = await screen.findByRole('link', { name: 'Share on WhatsApp' })
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    const href = link.getAttribute('href') ?? ''
    expect(href).toMatch(/^https:\/\/wa\.me\/\?text=/)
    const text = decodeURIComponent(href.slice('https://wa.me/?text='.length))
    expect(text.split('\n')[0]).toBe('⚠️ TEST PROJECT — not a real hospital. This is not a real medical appointment.')
    expect(text).toContain('Booking ref: VMH-261006-7K3Q · Token: 2')
    expect(text).toContain('Doctor: Dr. Venkata Ramana Pulla (Cardiology)')
    expect(text).toContain('Fee: ₹800, pay at the counter')
    expect(text).toContain(`${window.location.origin}/appointment-status?ref=VMH-261006-7K3Q`)
    expect(text).not.toMatch(/9876543210|98XXXXXX10/)
  })

  it('emails the confirmation with reference, full mobile and the address', async () => {
    const spy = mockApi({
      ...api,
      'POST /public/appointments': json(confirmed, 201),
      'POST /public/appointments/email': { sentTo: 'r***@example.com' },
    })
    renderRoutes(routes, '/book?doctor=6&branch=KDD')
    await chooseTimeAndFillDetails()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm booking' }))

    const field = await screen.findByLabelText('Email address')
    fireEvent.change(field, { target: { value: 'ravi@' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send email' }))
    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument()
    expect(requestBody(spy, 'POST /public/appointments/email')).toBeUndefined()

    fireEvent.change(field, { target: { value: ' ravi@example.com ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send email' }))
    expect(await screen.findByText(/Sent to r\*\*\*@example\.com/)).toHaveAttribute('role', 'status')
    expect(requestBody(spy, 'POST /public/appointments/email')).toEqual({
      bookingRef: 'VMH-261006-7K3Q', mobile: '9876543210', email: 'ravi@example.com',
    })
  })

  it('shows why the email could not be sent', async () => {
    mockApi({
      ...api,
      'POST /public/appointments': json(confirmed, 201),
      'POST /public/appointments/email': () =>
        json({ status: 429, code: 'EMAIL_LIMIT', message: 'Already emailed 3 times', fieldErrors: [] }, 429),
    })
    renderRoutes(routes, '/book?doctor=6&branch=KDD')
    await chooseTimeAndFillDetails()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm booking' }))

    fireEvent.change(await screen.findByLabelText('Email address'), { target: { value: 'ravi@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send email' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Already emailed 3 times')
  })

  it('hides email and WhatsApp when the page has no booking details', async () => {
    mockApi(api)
    renderRoutes(routes, '/book/confirmation/VMH-261006-7K3Q')

    expect(await screen.findByText(/Your booking VMH-261006-7K3Q is saved/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Share on WhatsApp' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Email address')).not.toBeInTheDocument()
  })

  it('checks the details before sending', async () => {
    const spy = mockApi(api)
    renderRoutes(routes, '/book?doctor=6&branch=KDD')
    await chooseTimeAndFillDetails()
    fireEvent.change(screen.getByLabelText('Mobile number'), { target: { value: '12345' } })
    fireEvent.click(screen.getByRole('checkbox'))   // untick consent
    fireEvent.click(screen.getByRole('button', { name: 'Confirm booking' }))

    expect(await screen.findByText('Enter a 10-digit mobile number')).toBeInTheDocument()
    expect(screen.getByText('Please accept to continue')).toBeInTheDocument()
    expect(requestBody(spy, 'POST /public/appointments')).toBeUndefined()
  })

  it('goes back to the times when the slot was just taken', async () => {
    mockApi({
      ...api,
      'POST /public/appointments': () => json({ status: 409, code: 'SLOT_TAKEN', message: 'taken', fieldErrors: [] }, 409),
    })
    renderRoutes(routes, '/book?doctor=6&branch=KDD')
    await chooseTimeAndFillDetails()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm booking' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('that time is no longer available')
    expect(screen.getByRole('listitem', { current: 'step' })).toHaveTextContent('Date & time')
  })
})

describe('Check my booking', () => {
  it('finds a booking by reference and mobile, then cancels it', async () => {
    const spy = mockApi({
      ...layoutApi,
      'POST /public/appointments/lookup': confirmed,
      'POST /public/appointments/cancel': { ...confirmed, status: 'CANCELLED', canCancel: false, canReschedule: false, cancelReason: 'Better now' },
    })
    renderRoutes(routes, '/appointment-status?ref=VMH-261006-7K3Q')

    expect(await screen.findByLabelText('Booking reference')).toHaveValue('VMH-261006-7K3Q')
    fireEvent.change(screen.getByLabelText('Mobile number'), { target: { value: '9876543210' } })
    fireEvent.click(screen.getByRole('button', { name: 'Find my booking' }))
    expect(await screen.findByTestId('booking-ref')).toHaveTextContent('VMH-261006-7K3Q')

    fireEvent.click(screen.getByRole('button', { name: 'Cancel booking' }))
    const panel = screen.getByRole('region', { name: 'Cancel this booking?' })
    fireEvent.change(within(panel).getByLabelText('Reason (optional)'), { target: { value: 'Better now' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Yes, cancel it' }))

    expect(await screen.findByText('Your booking has been cancelled.')).toBeInTheDocument()
    expect(screen.getByText('Cancelled')).toBeInTheDocument()
    expect(requestBody(spy, 'POST /public/appointments/cancel')).toEqual({
      bookingRef: 'VMH-261006-7K3Q', mobile: '9876543210', reason: 'Better now', captchaToken: NO_CAPTCHA_TOKEN,
    })
  })

  it('shows the same message for a wrong mobile as for an unknown reference', async () => {
    mockApi({
      ...layoutApi,
      'POST /public/appointments/lookup': () =>
        json({ status: 404, code: 'BOOKING_NOT_FOUND', message: 'No booking found with that reference and mobile number', fieldErrors: [] }, 404),
    })
    renderRoutes(routes, '/appointment-status')
    fireEvent.change(await screen.findByLabelText('Booking reference'), { target: { value: 'VMH-261006-7K3Q' } })
    fireEvent.change(screen.getByLabelText('Mobile number'), { target: { value: '9999999999' } })
    fireEvent.click(screen.getByRole('button', { name: 'Find my booking' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No booking found with that reference and mobile number')
  })

  it('finds bookings by mobile only, then opens one with its reference', async () => {
    const summary = {
      bookingRef: 'VMH-261006-****', mobileMasked: '98XXXXXX10', patientNameMasked: 'R*** K***', date: tomorrow,
      startAt: `${tomorrow}T10:20:00`, tokenNo: 2, branchName: 'Vihan Multi Speciality Hospital - Kodad', branchCity: 'Kodad',
      status: 'BOOKED', needs: 'BOOKING_REF',
    }
    const spy = mockApi({
      ...layoutApi,
      'POST /public/appointments/find': [summary],
      'POST /public/appointments/lookup': confirmed,
    })
    renderRoutes(routes, '/appointment-status')
    fireEvent.change(await screen.findByLabelText('Mobile number'), { target: { value: '9876543210' } })
    fireEvent.click(screen.getByRole('button', { name: 'Find my booking' }))

    expect(await screen.findByText('R*** K***')).toBeInTheDocument()
    expect(screen.getByText('VMH-261006-****')).toBeInTheDocument()
    expect(screen.queryByText('Dr. Venkata Ramana Pulla')).not.toBeInTheDocument()   // no doctor before it's opened
    expect(requestBody(spy, 'POST /public/appointments/find')).toEqual({ bookingRef: null, mobile: '9876543210', captchaToken: NO_CAPTCHA_TOKEN })

    fireEvent.change(screen.getByLabelText(/enter its booking reference/), { target: { value: 'VMH-261006-7K3Q' } })
    fireEvent.click(screen.getByRole('button', { name: 'Open booking' }))
    expect(await screen.findByTestId('booking-ref')).toHaveTextContent('VMH-261006-7K3Q')
    expect(requestBody(spy, 'POST /public/appointments/lookup')).toEqual({ bookingRef: 'VMH-261006-7K3Q', mobile: '9876543210' })
  })

  it('asks for at least one field and says when nothing matches', async () => {
    mockApi({ ...layoutApi, 'POST /public/appointments/find': [] })
    renderRoutes(routes, '/appointment-status')
    fireEvent.click(await screen.findByRole('button', { name: 'Find my booking' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter your booking reference or your mobile number.')

    fireEvent.change(screen.getByLabelText('Booking reference'), { target: { value: 'VMH-000000-ZZZZ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Find my booking' }))
    expect(await screen.findByText('No booking found with that reference.')).toBeInTheDocument()
  })
})

describe('Counter booking (reception)', () => {
  it('books a slot for a patient at the counter', async () => {
    const receptionist = { id: 3, username: 'reception.kdd', fullName: 'Asha', role: 'RECEPTIONIST', doctorId: null, homeBranchId: 3, mustChangePassword: false }
    const staffSlot = { id: 701, branchId: 3, branchCode: 'KDD', date: tomorrow, startAt: `${tomorrow}T11:00:00`, endAt: `${tomorrow}T11:20:00`, tokenNo: 4, status: 'OPEN', source: 'TEMPLATE', blockReason: null }
    const spy = mockApi({
      ...api,
      'POST /auth/refresh': { accessToken: 't', expiresAt: '2026-10-06T12:00:00Z', user: receptionist },
      [`/staff/doctors/6/slots?from=${todayIso()}&to=${addDays(todayIso(), 13)}`]: [staffSlot],
      'POST /staff/appointments': json({ ...confirmed, channel: 'PHONE', tokenNo: 4 }, 201),
    })
    renderRoutes(routes, '/staff/book')

    await screen.findByRole('option', { name: 'Kodad' })   // options must exist before choosing one
    fireEvent.change(screen.getByLabelText('Hospital'), { target: { value: 'KDD' } })
    await waitFor(() => expect(screen.getByRole('option', { name: /Venkata Ramana/ })).toBeInTheDocument())
    fireEvent.change(screen.getByLabelText('Doctor'), { target: { value: '6' } })
    fireEvent.click(await screen.findByRole('button', { name: /1 free/ }))
    fireEvent.click(await screen.findByRole('button', { name: '11:00, token 4' }))
    fireEvent.click(await screen.findByLabelText('By phone'))
    fireEvent.change(screen.getByLabelText('Patient name'), { target: { value: 'Lakshmi Devi' } })
    fireEvent.change(screen.getByLabelText('Age (years)'), { target: { value: '67' } })
    fireEvent.click(screen.getByLabelText('Female'))
    fireEvent.change(screen.getByLabelText('Mobile number'), { target: { value: '9876543210' } })
    fireEvent.click(screen.getByLabelText('The patient agreed to the privacy notice'))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm booking' }))

    expect(await screen.findByRole('heading', { name: 'Booked' })).toBeInTheDocument()
    expect(requestBody(spy, 'POST /staff/appointments')).toMatchObject({ slotId: 701, channel: 'PHONE', patientGender: 'F', consent: true })
  })
})
