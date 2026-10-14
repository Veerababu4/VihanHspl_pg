import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'

import {
  useAdminBranches,
  useAdminDoctors,
  useAdminSpecialities,
  useDoctorPhoto,
  useSaveDoctor,
  type AdminBranch,
  type AdminDoctor,
  type AdminSpeciality,
} from '../../../api/admin'
import { ApiError } from '../../../api/client'
import { DoctorAvatar } from '../../../components/DoctorAvatar'
import { Seo } from '../../../components/Seo'
import { Alert, Badge, Button, Field, inputClass, Modal, PageHeader } from '../../../components/ui'
import { applyServerErrors } from '../../../lib/forms'

/** Doctor management (FR-AD2). */
export function DoctorsAdminPage() {
  const { t } = useTranslation()
  const { data: doctors, isPending, isError } = useAdminDoctors()
  const [query, setQuery] = useState('')
  const [editing, setEditing] = useState<AdminDoctor | 'new' | null>(null)

  const q = query.trim().toLowerCase()
  const shown = doctors?.filter(
    (d) => !q || d.fullName.toLowerCase().includes(q) || d.specialityName.toLowerCase().includes(q) || (d.registrationNo ?? '').toLowerCase().includes(q),
  )

  return (
    <>
      <Seo title={t('staff.doctors.title')} />
      <PageHeader title={t('staff.doctors.title')} actions={<Button onClick={() => setEditing('new')}>{t('staff.doctors.add')}</Button>} />
      <input
        type="search"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder={t('staff.doctors.search')}
        aria-label={t('staff.doctors.search')}
        className={`${inputClass} mb-4 max-w-sm`}
      />
      {isPending && <p className="text-slate-500">{t('staff.common.loading')}</p>}
      {isError && <Alert>{t('staff.common.loadError')}</Alert>}
      {shown && (
        <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white">
          {shown.map((d) => (
            <li key={d.id} className="flex items-center gap-4 px-4 py-3">
              <DoctorAvatar name={d.fullName} photoUrl={d.photoUrl} />
              <div className="min-w-0 flex-1">
                <p className="font-medium text-slate-900">
                  {d.fullName} {!d.active && <Badge>{t('staff.common.inactive')}</Badge>}
                </p>
                <p className="text-sm text-slate-600">
                  {d.specialityName} · {d.qualification} · {t('staff.doctors.experience', { count: d.experienceYears })}
                </p>
                {d.registrationNo && <p className="text-xs text-slate-500">{d.registrationNo}</p>}
              </div>
              <Button variant="secondary" onClick={() => setEditing(d)} aria-label={`${t('staff.common.edit')} ${d.fullName}`}>
                {t('staff.common.edit')}
              </Button>
            </li>
          ))}
        </ul>
      )}
      {editing && <DoctorForm doctor={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </>
  )
}

const currentYear = new Date().getFullYear()
const schema = z.object({
  fullName: z.string().trim().min(1, 'Required').max(120),
  gender: z.enum(['', 'M', 'F', 'O']),
  specialityId: z.string().min(1, 'Choose a speciality'),
  qualification: z.string().trim().min(1, 'Required').max(300),
  designation: z.string().trim().max(120),
  registrationNo: z.string().trim().max(40),
  practiceStartYear: z.coerce.number<string>().int().min(1950).max(currentYear, 'Cannot be in the future'),
  languages: z.string().trim().max(200),
  bio: z.string().trim().max(2000),
  consultationFee: z.string().trim().regex(/^(\d{1,6}(\.\d{1,2})?)?$/, 'Enter an amount like 500'),
  branchIds: z.array(z.string()).min(1, 'Select at least one branch'),
  active: z.boolean(),
})
type FormInput = z.input<typeof schema>
type FormOutput = z.output<typeof schema>

interface DoctorFormProps {
  doctor: AdminDoctor | null
  onClose: () => void
}

/**
 * Waits for the speciality and branch lists before showing the form: a <select> mounted before
 * its options exist would silently show (and submit) the first option instead of the saved value.
 * One dialog for both states: swapping dialogs would move keyboard focus out and back.
 */
function DoctorForm(props: DoctorFormProps) {
  const { t } = useTranslation()
  const { data: specialities } = useAdminSpecialities()
  const { data: branches } = useAdminBranches()
  const [current, setCurrent] = useState(props.doctor)
  return (
    <Modal title={current ? t('staff.doctors.editTitle') : t('staff.doctors.addTitle')} onClose={props.onClose} wide>
      {specialities && branches ? (
        <DoctorFormBody {...props} current={current} setCurrent={setCurrent} specialities={specialities} branches={branches} />
      ) : (
        <p className="text-slate-500">{t('staff.common.loading')}</p>
      )}
    </Modal>
  )
}

function DoctorFormBody({
  doctor,
  onClose,
  current,
  setCurrent,
  specialities,
  branches,
}: DoctorFormProps & {
  current: AdminDoctor | null
  setCurrent: (d: AdminDoctor) => void
  specialities: AdminSpeciality[]
  branches: AdminBranch[]
}) {
  const { t } = useTranslation()
  const save = useSaveDoctor()
  const [message, setMessage] = useState<string | null>(null)
  const isNew = current === null

  const form = useForm<FormInput, unknown, FormOutput>({
    resolver: zodResolver(schema),
    defaultValues: {
      fullName: doctor?.fullName ?? 'Dr. ',
      gender: doctor?.gender ?? '',
      specialityId: doctor ? String(doctor.specialityId) : '',
      qualification: doctor?.qualification ?? '',
      designation: doctor?.designation ?? '',
      registrationNo: doctor?.registrationNo ?? '',
      practiceStartYear: doctor ? String(doctor.practiceStartYear) : '',
      languages: doctor?.languages ?? 'Telugu, English',
      bio: doctor?.bio ?? '',
      consultationFee: doctor?.consultationFee != null ? String(doctor.consultationFee) : '',
      branchIds: doctor?.branchIds.map(String) ?? [],
      active: doctor?.active ?? true,
    },
  })
  const errors = form.formState.errors

  const onSubmit = form.handleSubmit(async (v) => {
    setMessage(null)
    const input = {
      fullName: v.fullName,
      gender: v.gender === '' ? null : v.gender,
      specialityId: Number(v.specialityId),
      qualification: v.qualification,
      designation: v.designation || null,
      registrationNo: v.registrationNo || null,
      practiceStartYear: v.practiceStartYear,
      languages: v.languages || null,
      bio: v.bio || null,
      consultationFee: v.consultationFee ? Number(v.consultationFee) : null,
      branchIds: v.branchIds.map(Number),
      active: v.active,
    }
    try {
      const saved = await save.mutateAsync({ id: current?.id, input })
      if (isNew) setCurrent(saved)   // stay open so a photo can be added right away
      else onClose()
    } catch (e) {
      setMessage(applyServerErrors(e, form.setError))
    }
  })

  return (
    <>
      {current && <PhotoEditor doctor={current} onChange={setCurrent} />}
      <form onSubmit={onSubmit} noValidate className="grid gap-4 sm:grid-cols-2">
        {message && (
          <div className="sm:col-span-2">
            <Alert>{message}</Alert>
          </div>
        )}
        <Field label={t('staff.doctors.name')} error={errors.fullName?.message}>
          {(a) => <input {...a} {...form.register('fullName')} className={inputClass} />}
        </Field>
        <Field label={t('staff.doctors.gender')} error={errors.gender?.message}>
          {(a) => (
            <select {...a} {...form.register('gender')} className={inputClass}>
              <option value="">{t('staff.common.none')}</option>
              {(['M', 'F', 'O'] as const).map((g) => (
                <option key={g} value={g}>
                  {t(`staff.doctors.genders.${g}`)}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label={t('staff.doctors.speciality')} error={errors.specialityId?.message}>
          {(a) => (
            <select {...a} {...form.register('specialityId')} className={inputClass}>
              <option value="">{t('staff.doctors.chooseSpeciality')}</option>
              {specialities
                .filter((s) => s.active || String(s.id) === form.getValues('specialityId'))
                .map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
            </select>
          )}
        </Field>
        <Field label={t('staff.doctors.qualification')} error={errors.qualification?.message}>
          {(a) => <input {...a} {...form.register('qualification')} placeholder="MBBS, MS (Ortho)" className={inputClass} />}
        </Field>
        <Field label={t('staff.doctors.designation')} error={errors.designation?.message}>
          {(a) => <input {...a} {...form.register('designation')} className={inputClass} />}
        </Field>
        <Field label={t('staff.doctors.registrationNo')} error={errors.registrationNo?.message}>
          {(a) => <input {...a} {...form.register('registrationNo')} className={inputClass} />}
        </Field>
        <Field label={t('staff.doctors.practiceStartYear')} error={errors.practiceStartYear?.message}>
          {(a) => <input {...a} {...form.register('practiceStartYear')} inputMode="numeric" className={inputClass} />}
        </Field>
        <Field label={t('staff.doctors.fee')} error={errors.consultationFee?.message}>
          {(a) => <input {...a} {...form.register('consultationFee')} inputMode="decimal" className={inputClass} />}
        </Field>
        <Field label={t('staff.doctors.languages')} error={errors.languages?.message}>
          {(a) => <input {...a} {...form.register('languages')} className={inputClass} />}
        </Field>
        <fieldset className="sm:col-span-2">
          <legend className="mb-1 text-sm font-medium text-slate-700">{t('staff.doctors.branches')}</legend>
          <div className="flex flex-wrap gap-4">
            {branches.map((b) => (
              <label key={b.id} className="flex items-center gap-2 text-sm">
                <input type="checkbox" value={String(b.id)} {...form.register('branchIds')} className="h-4 w-4" />
                {b.city}
              </label>
            ))}
          </div>
          {errors.branchIds && <p className="mt-1 text-sm text-emergency-600">{errors.branchIds.message}</p>}
        </fieldset>
        <div className="sm:col-span-2">
          <Field label={t('staff.doctors.bio')} error={errors.bio?.message}>
            {(a) => <textarea {...a} {...form.register('bio')} rows={3} className={inputClass} />}
          </Field>
        </div>
        <label className="flex items-center gap-2 text-sm sm:col-span-2">
          <input type="checkbox" {...form.register('active')} className="h-4 w-4" />
          {t('staff.common.active')} <span className="text-slate-500">— {t('staff.doctors.activeHint')}</span>
        </label>
        <div className="flex justify-end gap-2 sm:col-span-2">
          <Button variant="secondary" onClick={onClose}>
            {t('staff.common.cancel')}
          </Button>
          <Button type="submit" disabled={form.formState.isSubmitting}>
            {form.formState.isSubmitting ? t('staff.common.saving') : t('staff.common.save')}
          </Button>
        </div>
      </form>
    </>
  )
}

function PhotoEditor({ doctor, onChange }: { doctor: AdminDoctor; onChange: (d: AdminDoctor) => void }) {
  const { t } = useTranslation()
  const photo = useDoctorPhoto()
  const [error, setError] = useState<string | null>(null)
  const upload = async (file: File | null) => {
    setError(null)
    try {
      onChange(await photo.mutateAsync({ id: doctor.id, file }))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('staff.common.loadError'))
    }
  }
  return (
    <div className="mb-5 flex items-center gap-4 rounded-lg bg-slate-50 p-3">
      <DoctorAvatar name={doctor.fullName} photoUrl={doctor.photoUrl} size="lg" />
      <div className="space-y-2 text-sm">
        <label className="inline-block cursor-pointer rounded-lg border border-slate-300 bg-white px-3 py-1.5 font-medium hover:bg-slate-50">
          {photo.isPending ? t('staff.common.saving') : t('staff.doctors.uploadPhoto')}
          <input
            type="file"
            accept="image/jpeg,image/png,image/webp"
            className="sr-only"
            disabled={photo.isPending}
            onChange={(e) => {
              const file = e.target.files?.[0]
              e.target.value = ''
              if (file) void upload(file)
            }}
          />
        </label>
        {doctor.photoUrl && (
          <Button variant="link" className="ml-3" onClick={() => upload(null)} disabled={photo.isPending}>
            {t('staff.doctors.removePhoto')}
          </Button>
        )}
        <p className="text-xs text-slate-500">{t('staff.doctors.photoHint')}</p>
        {error && <p className="text-emergency-600">{error}</p>}
      </div>
    </div>
  )
}
