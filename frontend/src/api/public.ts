import { keepPreviousData, useQuery } from '@tanstack/react-query'

import { apiGet } from './client'

// Types mirror the backend DTOs (backend/src/main/java/com/vihan/hospital/**/…Dto).

export interface AppInfo {
  name: string
  /** true when the backend serves fictional sample data (local profile) */
  sampleData: boolean
  /** the confirmation page offers "email me this confirmation" */
  confirmationEmail: boolean
  /** shared texts (WhatsApp) say this is a test project, not a real hospital */
  testNotice: boolean
}

export interface Branch {
  id: number
  code: string
  name: string
  address: string
  city: string
  district: string | null
  pincode: string | null
  phone: string | null
  emergencyPhone: string | null
  email: string | null
  mapUrl: string | null
  imageUrl: string | null
  openingHours: string | null
}

export interface Speciality {
  id: number
  name: string
  description: string | null
  iconUrl: string | null
}

export interface BranchRef {
  code: string
  city: string
}

export interface DoctorSummary {
  id: number
  fullName: string
  gender: 'M' | 'F' | 'O' | null
  speciality: { id: number; name: string }
  qualification: string
  designation: string | null
  experienceYears: number
  languages: string | null
  photoUrl: string | null
  consultationFee: number | null
  branches: BranchRef[]
}

export interface Session {
  /** 'HH:mm' */
  start: string
  end: string
  slotMinutes: number
}

export interface BranchAvailability {
  branchCode: string
  branchCity: string
  /** dayOfWeek: ISO, 1 = Monday … 7 = Sunday */
  days: { dayOfWeek: number; sessions: Session[] }[]
}

export interface DoctorDetail extends DoctorSummary {
  registrationNo: string | null
  bio: string | null
  availability: BranchAvailability[]
}

export interface GalleryImage {
  id: number
  branchCode: string | null
  title: string | null
  caption: string | null
  imageUrl: string
  thumbUrl: string
}

export interface BranchDetail {
  branch: Branch
  doctors: DoctorSummary[]
  gallery: GalleryImage[]
}

export interface DoctorFilters {
  branch?: string
  speciality?: number
  q?: string
}

const FIVE_MINUTES = 5 * 60_000

function query(params: Record<string, string | number | undefined>) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value))
  }
  const s = search.toString()
  return s ? `?${s}` : ''
}

export const useAppInfo = () =>
  useQuery({ queryKey: ['app-info'], queryFn: () => apiGet<AppInfo>('/public/app-info'), staleTime: Infinity })

export const useBranches = () =>
  useQuery({ queryKey: ['branches'], queryFn: () => apiGet<Branch[]>('/public/branches'), staleTime: FIVE_MINUTES })

export const useBranch = (code: string) =>
  useQuery({
    queryKey: ['branch', code],
    queryFn: () => apiGet<BranchDetail>(`/public/branches/${encodeURIComponent(code)}`),
    staleTime: FIVE_MINUTES,
  })

export const useSpecialities = () =>
  useQuery({
    queryKey: ['specialities'],
    queryFn: () => apiGet<Speciality[]>('/public/specialities'),
    staleTime: FIVE_MINUTES,
  })

export const useDoctors = (filters: DoctorFilters = {}) =>
  useQuery({
    queryKey: ['doctors', filters],
    queryFn: () => apiGet<DoctorSummary[]>(`/public/doctors${query({ ...filters })}`),
    staleTime: FIVE_MINUTES,
    placeholderData: keepPreviousData, // keep showing results while a new filter loads
  })

export const useDoctor = (id: number) =>
  useQuery({
    queryKey: ['doctor', id],
    queryFn: () => apiGet<DoctorDetail>(`/public/doctors/${id}`),
    staleTime: FIVE_MINUTES,
    enabled: Number.isInteger(id) && id > 0,
  })

export const useGallery = (branch?: string) =>
  useQuery({
    queryKey: ['gallery', branch ?? 'all'],
    queryFn: () => apiGet<GalleryImage[]>(`/public/gallery${query({ branch })}`),
    staleTime: FIVE_MINUTES,
  })
