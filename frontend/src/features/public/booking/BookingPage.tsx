import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useSearchParams } from 'react-router'

import { useBookAppointment, usePublicSlots, useSlotDays } from '../../../api/booking'
import { ApiError } from '../../../api/client'
import { useBranches, useDoctor, useDoctors, useSpecialities } from '../../../api/public'
import { DoctorAvatar } from '../../../components/DoctorAvatar'
import { Seo } from '../../../components/Seo'
import { Turnstile } from '../../../components/Turnstile'
import { Alert, Button, inputClass } from '../../../components/ui'
import { shortDate, timeOf } from '../../../lib/dates'
import { formatFee, formatTime } from '../../../lib/format'
import { applyServerErrors } from '../../../lib/forms'
import { emptyPatient, normalizeMobile, patientSchema, type PatientFormInput, type PatientFormOutput } from './patientSchema'
import { PatientFields } from './PatientFields'
import { SlotPicker } from './SlotPicker'

type Step = 'doctor' | 'time' | 'details'
const STEPS: Step[] = ['doctor', 'time', 'details']
const SLOT_GONE = ['SLOT_TAKEN', 'SLOT_TOO_SOON', 'SLOT_UNAVAILABLE', 'OUT_OF_WINDOW']

/** Online booking wizard (FR-B1): hospital & doctor → date & time → details + captcha → confirm. */
export function BookingPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [params] = useSearchParams()
  const preDoctorId = Number(params.get('doctor')) || undefined

  const [step, setStep] = useState<Step>('doctor')
  const [chosenBranch, setBranch] = useState<string | undefined>(params.get('branch')?.toUpperCase() ?? undefined)
  const [speciality, setSpeciality] = useState<number | undefined>()
  const [doctorId, setDoctorId] = useState<number | undefined>(preDoctorId)
  const [date, setDate] = useState<string>()
  const [slotId, setSlotId] = useState<number>()
  const [notice, setNotice] = useState<string | null>(null)

  const { data: branches } = useBranches()
  const { data: specialities } = useSpecialities()
  const { data: preDoctor } = useDoctor(preDoctorId ?? 0)
  // Coming from a doctor's page: if that doctor works at only one hospital, it is chosen already.
  const branch = chosenBranch ?? (preDoctor?.branches.length === 1 ? preDoctor.branches[0].code : undefined)
  const { data: doctors } = useDoctors({ branch, speciality })
  const doctor = doctors?.find((d) => d.id === doctorId) ?? (preDoctor?.id === doctorId ? preDoctor : undefined)
  const doctorWorksHere = !!doctor && doctor.branches.some((b) => b.code === branch)

  const { data: days } = useSlotDays(step !== 'doctor' ? doctorId : undefined, branch)
  const { data: slots, isFetching: slotsLoading } = usePublicSlots(doctorId, branch, step !== 'doctor' ? date : undefined)
  const slot = slots?.find((s) => s.slotId === slotId)
  const branchInfo = branches?.find((b) => b.code === branch)

  const book = useBookAppointment()
  const [captchaToken, setCaptchaToken] = useState<string | null>(null)
  const [captchaReset, setCaptchaReset] = useState(0)
  const form = useForm<PatientFormInput, unknown, PatientFormOutput>({ resolver: zodResolver(patientSchema(t)), defaultValues: emptyPatient })
  const [submitError, setSubmitError] = useState<string | null>(null)

  const go = (next: Step) => {
    setNotice(null)
    setStep(next)
  }

  const onSubmit = form.handleSubmit(async (v) => {
    if (!slotId) return
    setSubmitError(null)
    if (!captchaToken) {
      setSubmitError(t('booking.captchaHint'))
      return
    }
    try {
      const appointment = await book.mutateAsync({
        slotId,
        patientName: v.patientName,
        patientAge: v.patientAge,
        patientGender: v.patientGender,
        mobile: normalizeMobile(v.mobile) ?? v.mobile,
        reason: v.reason || null,
        uhid: v.uhid || null,
        consent: v.consent,
        captchaToken,
      })
      // The full mobile goes along (in memory / history only, never the URL) for "email me this confirmation".
      navigate(`/book/confirmation/${appointment.bookingRef}`, { state: { appointment, mobile: normalizeMobile(v.mobile) ?? v.mobile } })
    } catch (e) {
      setCaptchaReset((n) => n + 1)   // captcha tokens are single-use
      if (e instanceof ApiError && SLOT_GONE.includes(e.code)) {
        await queryClient.invalidateQueries({ queryKey: ['public-slots'] })
        await queryClient.invalidateQueries({ queryKey: ['slot-days'] })
        setSlotId(undefined)
        setStep('time')
        setNotice(t('booking.slotGone'))
        return
      }
      setSubmitError(applyServerErrors(e, form.setError))
    }
  })

  return (
    <div className="mx-auto max-w-6xl px-4 py-10">
      <Seo title={t('seo.bookTitle')} description={t('seo.bookDescription')} />
      <h1 className="mb-6 text-3xl font-bold text-slate-900">{t('booking.title')}</h1>

      <ol className="mb-6 flex gap-2 text-sm">
        {STEPS.map((s, i) => (
          <li
            key={s}
            aria-current={s === step ? 'step' : undefined}
            className={`flex-1 rounded-lg border px-3 py-2 ${
              s === step ? 'border-brand-600 bg-brand-50 font-semibold text-brand-700' : 'border-slate-200 bg-white text-slate-500'
            }`}
          >
            {i + 1}. {t(`booking.steps.${s}`)}
          </li>
        ))}
      </ol>

      <div className="grid gap-6 lg:grid-cols-3">
        <section className="min-w-0 rounded-xl border border-slate-200 bg-white p-5 lg:col-span-2">
          {notice && (
            <div className="mb-4">
              <Alert>{notice}</Alert>
            </div>
          )}

          {step === 'doctor' && (
            <div className="space-y-5">
              <fieldset>
                <legend className="mb-2 text-sm font-medium text-slate-700">{t('booking.branch')}</legend>
                <div className="grid gap-2 sm:grid-cols-3">
                  {branches?.map((b) => (
                    <label
                      key={b.code}
                      className={`cursor-pointer rounded-lg border px-4 py-3 text-sm ${b.code === branch ? 'border-brand-600 bg-brand-50' : 'border-slate-300'}`}
                    >
                      <input type="radio" name="branch" value={b.code} checked={b.code === branch} onChange={() => setBranch(b.code)} className="sr-only" />
                      <span className="font-semibold">{b.city}</span>
                      <span className="block text-xs text-slate-500">{b.district}</span>
                    </label>
                  ))}
                </div>
              </fieldset>

              {branch && (
                <>
                  <label className="block max-w-sm text-sm font-medium text-slate-700">
                    {t('booking.speciality')}
                    <select
                      className={`${inputClass} mt-1`}
                      value={speciality ?? ''}
                      onChange={(e) => setSpeciality(e.target.value ? Number(e.target.value) : undefined)}
                    >
                      <option value="">{t('booking.allSpecialities')}</option>
                      {specialities?.map((s) => (
                        <option key={s.id} value={s.id}>
                          {s.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <fieldset>
                    <legend className="mb-2 text-sm font-medium text-slate-700">{t('booking.doctor')}</legend>
                    {doctors?.length === 0 && <p className="text-slate-600">{t('booking.noDoctors')}</p>}
                    <div className="grid gap-2 sm:grid-cols-2">
                      {doctors?.map((d) => (
                        <label
                          key={d.id}
                          className={`flex cursor-pointer items-center gap-3 rounded-lg border p-3 ${d.id === doctorId ? 'border-brand-600 bg-brand-50' : 'border-slate-300'}`}
                        >
                          <input type="radio" name="doctor" value={d.id} checked={d.id === doctorId} onChange={() => setDoctorId(d.id)} className="sr-only" />
                          <DoctorAvatar name={d.fullName} photoUrl={d.photoUrl} />
                          <span className="text-sm">
                            <span className="block font-semibold text-slate-900">{d.fullName}</span>
                            <span className="block text-brand-700">{d.speciality.name}</span>
                            <span className="block text-slate-500">{d.qualification}</span>
                          </span>
                        </label>
                      ))}
                    </div>
                  </fieldset>
                </>
              )}
              <div className="flex justify-end">
                <Button disabled={!branch || !doctorWorksHere} onClick={() => go('time')}>
                  {t('booking.next')}
                </Button>
              </div>
            </div>
          )}

          {step === 'time' && (
            <div className="space-y-5">
              {days && (
                <SlotPicker
                  days={days}
                  date={date}
                  onDate={(d) => {
                    setDate(d)
                    setSlotId(undefined)
                  }}
                  slots={slots?.map((s) => ({ id: s.slotId, startAt: s.startAt, tokenNo: s.tokenNo }))}
                  slotsLoading={slotsLoading}
                  slotId={slotId}
                  onSlot={setSlotId}
                />
              )}
              <div className="flex justify-between">
                <Button variant="secondary" onClick={() => go('doctor')}>
                  {t('booking.back')}
                </Button>
                <Button disabled={!slot} onClick={() => go('details')}>
                  {t('booking.next')}
                </Button>
              </div>
            </div>
          )}

          {step === 'details' && (
            <form onSubmit={onSubmit} noValidate className="space-y-5">
              {submitError && <Alert>{submitError}</Alert>}
              <PatientFields
                form={form}
                consentLabel={
                  <>
                    {t('booking.consent')}{' '}
                    <Link to="/privacy" target="_blank" className="text-accent-600 underline">
                      {t('booking.privacyLink')}
                    </Link>
                    .
                  </>
                }
              />
              <Turnstile onToken={setCaptchaToken} resetKey={captchaReset} />
              <div className="flex justify-between">
                <Button variant="secondary" onClick={() => go('time')}>
                  {t('booking.back')}
                </Button>
                <Button type="submit" disabled={book.isPending}>
                  {book.isPending ? t('booking.booking') : t('booking.confirm')}
                </Button>
              </div>
            </form>
          )}
        </section>

        <aside className="h-fit rounded-xl border border-slate-200 bg-white p-5 text-sm">
          <h2 className="mb-3 font-semibold text-slate-900">{t('booking.summary')}</h2>
          <dl className="space-y-3">
            {branchInfo && (
              <div>
                <dt className="text-slate-500">{t('booking.branch')}</dt>
                <dd className="font-medium">{branchInfo.name}</dd>
              </div>
            )}
            {doctor && doctorWorksHere && (
              <div>
                <dt className="text-slate-500">{t('booking.doctor')}</dt>
                <dd className="font-medium">
                  {doctor.fullName} <span className="block font-normal text-slate-600">{doctor.speciality.name}</span>
                </dd>
              </div>
            )}
            {slot && (
              <div>
                <dt className="text-slate-500">{t('booking.time')}</dt>
                <dd className="font-medium">
                  {shortDate(slot.startAt.slice(0, 10))}, {formatTime(timeOf(slot.startAt))} · {t('booking.tokenShort', { token: slot.tokenNo })}
                </dd>
              </div>
            )}
          </dl>
          {doctor && doctorWorksHere && doctor.consultationFee != null && (
            <p className="mt-3 text-slate-600">{t('booking.fee', { fee: formatFee(doctor.consultationFee) })}</p>
          )}
        </aside>
      </div>
    </div>
  )
}
