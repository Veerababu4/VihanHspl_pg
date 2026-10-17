import { useMutation, useQuery } from '@tanstack/react-query'

import { apiGet, apiSend } from './client'

// Booking API (backend AppointmentDtos.java, DESIGN.md §11.1, §11.4).

export interface SlotDay {
  date: string
  openSlots: number
}

export interface PublicSlot {
  slotId: number
  startAt: string
  endAt: string
  tokenNo: number
}

export type AppointmentStatus = 'BOOKED' | 'CHECKED_IN' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW'

/** The booking as the patient sees it (confirmation, "check my booking"). */
export interface AppointmentView {
  bookingRef: string
  status: AppointmentStatus
  tokenNo: number | null
  date: string
  /** null for a walk-in (no time slot) */
  startAt: string | null
  endAt: string | null
  doctor: { id: number; fullName: string; speciality: string; consultationFee: number | null }
  branch: { code: string; name: string; city: string; address: string; phone: string | null }
  patientName: string
  patientAge: number | null
  patientGender: 'M' | 'F' | 'O' | null
  mobileMasked: string
  reason: string | null
  channel: 'ONLINE' | 'COUNTER' | 'PHONE' | 'WALK_IN'
  rescheduleCount: number
  reschedulesLeft: number
  canCancel: boolean
  canReschedule: boolean
  changeDeadline: string | null
  needsAction: boolean
  cancelReason: string | null
  /** the registered patient this booking is linked to, if any */
  uhid: string | null
}

export interface PatientInput {
  patientName: string
  patientAge: number
  patientGender: 'M' | 'F' | 'O'
  mobile: string
  reason: string | null
  consent: boolean
  /** optional, for returning patients (DESIGN.md §19.5) */
  uhid?: string | null
}

export const useSlotDays = (doctorId: number | undefined, branch: string | undefined) =>
  useQuery({
    queryKey: ['slot-days', doctorId, branch],
    queryFn: () => apiGet<SlotDay[]>(`/public/doctors/${doctorId}/slot-days?branch=${branch}`),
    enabled: !!doctorId && !!branch,
  })

export const usePublicSlots = (doctorId: number | undefined, branch: string | undefined, date: string | undefined) =>
  useQuery({
    queryKey: ['public-slots', doctorId, branch, date],
    queryFn: () => apiGet<PublicSlot[]>(`/public/doctors/${doctorId}/slots?branch=${branch}&date=${date}`),
    enabled: !!doctorId && !!branch && !!date,
  })

export const useBookAppointment = () =>
  useMutation({
    mutationFn: (input: PatientInput & { slotId: number; captchaToken: string | null }) =>
      apiSend<AppointmentView>('POST', '/public/appointments', input),
  })

export const useLookupAppointment = () =>
  useMutation({
    mutationFn: (input: { bookingRef: string; mobile: string }) =>
      apiSend<AppointmentView>('POST', '/public/appointments/lookup', input),
  })

/**
 * One result of "check my booking" with a single field: masked, no doctor. The field that wasn't
 * given stays hidden (masked reference or mobile); `needs` says which one opens the booking.
 */
export interface BookingSummary {
  bookingRef: string
  mobileMasked: string
  patientNameMasked: string
  date: string
  startAt: string | null
  tokenNo: number | null
  branchName: string
  branchCity: string
  status: AppointmentStatus
  needs: 'MOBILE' | 'BOOKING_REF'
}

/** Exactly one of bookingRef / mobile (backend FindRequest); needs the captcha. */
export const useFindAppointments = () =>
  useMutation({
    mutationFn: (input: { bookingRef: string | null; mobile: string | null; captchaToken: string | null }) =>
      apiSend<BookingSummary[]>('POST', '/public/appointments/find', input),
  })

export const useCancelAppointment = () =>
  useMutation({
    mutationFn: (input: { bookingRef: string; mobile: string; reason: string | null; captchaToken: string | null }) =>
      apiSend<AppointmentView>('POST', '/public/appointments/cancel', input),
  })

export const useRescheduleAppointment = () =>
  useMutation({
    mutationFn: (input: { bookingRef: string; mobile: string; newSlotId: number; captchaToken: string | null }) =>
      apiSend<AppointmentView>('POST', '/public/appointments/reschedule', input),
  })

/** Confirmation by email from the confirmation page; the same proof as a lookup (reference + mobile). */
export const useEmailConfirmation = () =>
  useMutation({
    mutationFn: (input: { bookingRef: string; mobile: string; email: string }) =>
      apiSend<{ sentTo: string }>('POST', '/public/appointments/email', input),
  })

/** Counter / phone booking by reception (no captcha). */
export const useStaffBook = () =>
  useMutation({
    mutationFn: (input: PatientInput & { slotId: number; channel: 'COUNTER' | 'PHONE' }) =>
      apiSend<AppointmentView>('POST', '/staff/appointments', input),
  })
