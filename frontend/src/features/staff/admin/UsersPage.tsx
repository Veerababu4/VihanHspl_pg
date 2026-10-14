import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'

import {
  useAdminBranches,
  useAdminDoctors,
  useAdminUsers,
  useCreateUser,
  useResetPassword,
  useUpdateUser,
  type AdminBranch,
  type AdminDoctor,
  type AdminUser,
  type UserWithPassword,
} from '../../../api/admin'
import type { Role } from '../../../auth/session'
import { Seo } from '../../../components/Seo'
import { Alert, Badge, Button, Field, inputClass, Modal, PageHeader, ScrollRegion } from '../../../components/ui'
import { applyServerErrors } from '../../../lib/forms'

const ROLES: Role[] = ['RECEPTIONIST', 'DOCTOR', 'IT_ADMIN']

/** Staff accounts (FR-AD1). */
export function UsersPage() {
  const { t } = useTranslation()
  const { data: users, isPending, isError } = useAdminUsers()
  const { data: branches } = useAdminBranches()
  const resetPassword = useResetPassword()
  const [editing, setEditing] = useState<AdminUser | 'new' | null>(null)
  const [issued, setIssued] = useState<UserWithPassword | null>(null)
  const branchName = (id: number | null) => branches?.find((b) => b.id === id)?.city ?? t('staff.common.none')

  const onReset = async (u: AdminUser) => {
    if (!window.confirm(t('staff.users.resetConfirm', { name: u.fullName }))) return
    setIssued(await resetPassword.mutateAsync(u.id))
  }

  return (
    <>
      <Seo title={t('staff.users.title')} />
      <PageHeader title={t('staff.users.title')} actions={<Button onClick={() => setEditing('new')}>{t('staff.users.add')}</Button>} />
      {isPending && <p className="text-slate-500">{t('staff.common.loading')}</p>}
      {isError && <Alert>{t('staff.common.loadError')}</Alert>}
      {users && (
        <ScrollRegion label={t('staff.users.title')} className="rounded-xl border border-slate-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50 text-slate-600">
              <tr>
                <th className="px-4 py-3">{t('staff.users.username')}</th>
                <th className="px-4 py-3">{t('staff.users.fullName')}</th>
                <th className="px-4 py-3">{t('staff.users.role')}</th>
                <th className="px-4 py-3">{t('staff.users.homeBranch')}</th>
                <th className="px-4 py-3">{t('staff.users.status')}</th>
                <th className="px-4 py-3">{t('staff.users.lastLogin')}</th>
                <th className="px-4 py-3">
                  <span className="sr-only">{t('staff.common.actions')}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id} className="border-b border-slate-100 last:border-0">
                  <td className="px-4 py-3 font-mono">{u.username}</td>
                  <td className="px-4 py-3">
                    {u.fullName}
                    {u.doctorName && u.doctorName !== u.fullName && <span className="block text-xs text-slate-500">{u.doctorName}</span>}
                  </td>
                  <td className="px-4 py-3">{t(`staff.roles.${u.role}`)}</td>
                  <td className="px-4 py-3">{branchName(u.homeBranchId)}</td>
                  <td className="space-x-1 px-4 py-3">
                    <Badge tone={u.active ? 'good' : 'neutral'}>{u.active ? t('staff.common.active') : t('staff.common.inactive')}</Badge>
                    {u.locked && <Badge tone="bad">{t('staff.users.locked')}</Badge>}
                    {u.mustChangePassword && <Badge tone="warn">{t('staff.users.mustChange')}</Badge>}
                  </td>
                  <td className="px-4 py-3 text-slate-600">
                    {u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString('en-IN') : t('staff.users.never')}
                  </td>
                  <td className="space-x-3 px-4 py-3 text-right whitespace-nowrap">
                    <Button variant="link" onClick={() => setEditing(u)} aria-label={`${t('staff.common.edit')} ${u.username}`}>
                      {t('staff.common.edit')}
                    </Button>
                    <Button variant="link" onClick={() => onReset(u)} aria-label={`${t('staff.users.resetPassword')} ${u.username}`}>
                      {t('staff.users.resetPassword')}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollRegion>
      )}
      {editing && (
        <UserForm
          user={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onCreated={(result) => {
            setEditing(null)
            setIssued(result)
          }}
        />
      )}
      {issued && <TemporaryPassword issued={issued} onClose={() => setIssued(null)} />}
    </>
  )
}

const schema = z
  .object({
    username: z.string().trim(),
    fullName: z.string().trim().min(1, 'Required').max(120),
    role: z.enum(['DOCTOR', 'RECEPTIONIST', 'IT_ADMIN']),
    doctorId: z.string(),
    homeBranchId: z.string(),
    mobile: z.string().trim().regex(/^([6-9]\d{9})?$/, 'Must be a 10-digit mobile number'),
    email: z.union([z.literal(''), z.string().trim().email()]),
    active: z.boolean(),
  })
  .refine((v) => v.role !== 'DOCTOR' || v.doctorId !== '', { path: ['doctorId'], message: 'Select the doctor profile' })
  .refine((v) => v.role !== 'RECEPTIONIST' || v.homeBranchId !== '', { path: ['homeBranchId'], message: 'Select the home branch' })
type FormValues = z.infer<typeof schema>

interface UserFormProps {
  user: AdminUser | null
  onClose: () => void
  onCreated: (r: UserWithPassword) => void
}

/**
 * Waits for the doctor and branch lists before showing the form: a <select> mounted before its
 * options exist would silently show (and submit) the first option instead of the saved value.
 * One dialog for both states: swapping dialogs would move keyboard focus out and back.
 */
function UserForm(props: UserFormProps) {
  const { t } = useTranslation()
  const { data: doctors } = useAdminDoctors()
  const { data: branches } = useAdminBranches()
  return (
    <Modal title={props.user ? t('staff.users.editTitle') : t('staff.users.addTitle')} onClose={props.onClose}>
      {doctors && branches ? (
        <UserFormBody {...props} doctors={doctors} branches={branches} />
      ) : (
        <p className="text-slate-500">{t('staff.common.loading')}</p>
      )}
    </Modal>
  )
}

function UserFormBody({
  user,
  onClose,
  onCreated,
  doctors,
  branches,
}: UserFormProps & { doctors: AdminDoctor[]; branches: AdminBranch[] }) {
  const { t } = useTranslation()
  const create = useCreateUser()
  const update = useUpdateUser()
  const [message, setMessage] = useState<string | null>(null)
  const isNew = user === null
  const form = useForm<FormValues>({
    resolver: zodResolver(
      isNew ? schema.refine((v) => /^[a-z0-9._-]{3,60}$/.test(v.username), { path: ['username'], message: t('staff.users.usernameHint') }) : schema,
    ),
    defaultValues: {
      username: user?.username ?? '',
      fullName: user?.fullName ?? '',
      role: user?.role ?? 'RECEPTIONIST',
      doctorId: user?.doctorId ? String(user.doctorId) : '',
      homeBranchId: user?.homeBranchId ? String(user.homeBranchId) : '',
      mobile: user?.mobile ?? '',
      email: user?.email ?? '',
      active: user?.active ?? true,
    },
  })
  const role = useWatch({ control: form.control, name: 'role' })
  const errors = form.formState.errors

  // Picking a doctor fills in the name, since a doctor login is for that person.
  const onDoctorChange = (id: string) => {
    const d = doctors.find((x) => String(x.id) === id)
    if (d && !form.getValues('fullName')) form.setValue('fullName', d.fullName)
  }

  const onSubmit = form.handleSubmit(async (v) => {
    setMessage(null)
    const input = {
      fullName: v.fullName,
      role: v.role,
      doctorId: v.role === 'DOCTOR' && v.doctorId ? Number(v.doctorId) : null,
      homeBranchId: v.homeBranchId ? Number(v.homeBranchId) : null,
      mobile: v.mobile || null,
      email: v.email || null,
    }
    try {
      if (isNew) {
        onCreated(await create.mutateAsync({ ...input, username: v.username }))
      } else {
        await update.mutateAsync({ id: user.id, input: { ...input, active: v.active } })
        onClose()
      }
    } catch (e) {
      setMessage(applyServerErrors(e, form.setError))
    }
  })

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      {message && <Alert>{message}</Alert>}
      {isNew ? (
        <Field label={t('staff.users.username')} error={errors.username?.message} hint={t('staff.users.usernameHint')}>
          {(a) => <input {...a} {...form.register('username')} autoCapitalize="none" className={inputClass} />}
        </Field>
      ) : (
        <p className="text-sm">
          {t('staff.users.username')}: <span className="font-mono">{user.username}</span>
        </p>
      )}
      <Field label={t('staff.users.role')} error={errors.role?.message}>
        {(a) => (
          <select {...a} {...form.register('role')} className={inputClass}>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {t(`staff.roles.${r}`)}
              </option>
            ))}
          </select>
        )}
      </Field>
      {role === 'DOCTOR' && (
        <Field label={t('staff.users.doctor')} error={errors.doctorId?.message}>
          {(a) => (
            <select {...a} {...form.register('doctorId', { onChange: (e) => onDoctorChange(e.target.value) })} className={inputClass}>
              <option value="">{t('staff.users.chooseDoctor')}</option>
              {doctors.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.fullName} — {d.specialityName}
                </option>
              ))}
            </select>
          )}
        </Field>
      )}
      <Field label={t('staff.users.fullName')} error={errors.fullName?.message}>
        {(a) => <input {...a} {...form.register('fullName')} className={inputClass} />}
      </Field>
      <Field label={t('staff.users.homeBranch')} error={errors.homeBranchId?.message}>
        {(a) => (
          <select {...a} {...form.register('homeBranchId')} className={inputClass}>
            <option value="">{t('staff.users.chooseBranch')}</option>
            {branches.map((b) => (
              <option key={b.id} value={b.id}>
                {b.city}
              </option>
            ))}
          </select>
        )}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label={t('staff.users.mobile')} error={errors.mobile?.message}>
          {(a) => <input {...a} {...form.register('mobile')} inputMode="numeric" className={inputClass} />}
        </Field>
        <Field label={t('staff.users.email')} error={errors.email?.message}>
          {(a) => <input {...a} {...form.register('email')} type="email" className={inputClass} />}
        </Field>
      </div>
      {!isNew && (
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" {...form.register('active')} className="h-4 w-4" />
          {t('staff.common.active')} <span className="text-slate-500">— {t('staff.users.activeHint')}</span>
        </label>
      )}
      <div className="flex justify-end gap-2 pt-2">
        <Button variant="secondary" onClick={onClose}>
          {t('staff.common.cancel')}
        </Button>
        <Button type="submit" disabled={form.formState.isSubmitting}>
          {form.formState.isSubmitting ? t('staff.common.saving') : t('staff.common.save')}
        </Button>
      </div>
    </form>
  )
}

function TemporaryPassword({ issued, onClose }: { issued: UserWithPassword; onClose: () => void }) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(issued.temporaryPassword)
      setCopied(true)
    } catch {
      /* clipboard blocked: the password is still visible to read out */
    }
  }
  return (
    <Modal title={t('staff.users.tempPasswordTitle')} onClose={onClose}>
      <p className="text-sm text-slate-700">
        {t('staff.users.tempPasswordText', { name: issued.user.fullName, username: issued.user.username })}
      </p>
      <div className="my-4 flex items-center gap-3">
        <code className="rounded-lg bg-slate-100 px-4 py-2 font-mono text-lg tracking-wider" data-testid="temp-password">
          {issued.temporaryPassword}
        </code>
        <Button variant="secondary" onClick={copy}>
          {copied ? t('staff.users.copied') : t('staff.users.copy')}
        </Button>
      </div>
      <div className="flex justify-end">
        <Button onClick={onClose}>{t('staff.users.done')}</Button>
      </div>
    </Modal>
  )
}
