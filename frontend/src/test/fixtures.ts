import type { Branch, DoctorDetail, DoctorSummary, GalleryImage, Speciality } from '../api/public'

export const kodad: Branch = {
  id: 3, code: 'KDD', name: 'Vihan Multi Speciality Hospital - Kodad', address: 'Main Road', city: 'Kodad',
  district: 'Suryapet', pincode: null, phone: '0000000003', emergencyPhone: '0000000103', email: null,
  mapUrl: 'https://www.google.com/maps?q=Kodad&output=embed', imageUrl: null, openingHours: 'OPD: Mon-Sat',
}

export const huzurnagar: Branch = {
  ...kodad, id: 2, code: 'HZN', name: 'Vihan Multi Speciality Hospital - Huzurnagar', city: 'Huzurnagar',
  phone: '0000000002', emergencyPhone: '0000000102',
}

export const cardiology: Speciality = { id: 6, name: 'Cardiology', description: 'Heart care', iconUrl: null }
export const ent: Speciality = { id: 12, name: 'ENT', description: null, iconUrl: null }

export const drRamana: DoctorSummary = {
  id: 6, fullName: 'Dr. Venkata Ramana Pulla', gender: 'M', speciality: { id: 6, name: 'Cardiology' },
  qualification: 'MBBS, MD, DM (Cardiology)', designation: 'Chief Cardiologist', experienceYears: 25,
  languages: 'Telugu, English', photoUrl: null, consultationFee: 800,
  branches: [{ code: 'HZN', city: 'Huzurnagar' }, { code: 'KDD', city: 'Kodad' }],
}

export const drHaritha: DoctorSummary = {
  ...drRamana, id: 12, fullName: 'Dr. Haritha Kommu', gender: 'F', speciality: { id: 12, name: 'ENT' },
  qualification: 'MBBS, MS (ENT)', designation: 'Consultant', experienceYears: 1, consultationFee: 400,
  branches: [{ code: 'KDD', city: 'Kodad' }],
}

export const drRamanaDetail: DoctorDetail = {
  ...drRamana,
  registrationNo: 'SAMPLE-006',
  bio: 'Sample profile.',
  availability: [
    { branchCode: 'HZN', branchCity: 'Huzurnagar', days: [{ dayOfWeek: 2, sessions: [{ start: '10:00', end: '14:00', slotMinutes: 20 }] }] },
    {
      branchCode: 'KDD', branchCity: 'Kodad',
      days: [{ dayOfWeek: 3, sessions: [{ start: '10:00', end: '14:00', slotMinutes: 20 }, { start: '17:00', end: '19:00', slotMinutes: 20 }] }],
    },
  ],
}

export const wardPhoto: GalleryImage = {
  id: 1, branchCode: 'KDD', title: 'Patient ward', caption: 'Placeholder image',
  imageUrl: '/placeholders/ward.svg', thumbUrl: '/placeholders/ward.svg',
}

export const pharmacyPhoto: GalleryImage = { ...wardPhoto, id: 2, title: 'Pharmacy', imageUrl: '/placeholders/pharmacy.svg' }

/** Responses every page needs (layout: app info + branches for footer / emergency button). */
export const layoutApi = {
  '/public/app-info': { name: 'Vihan', sampleData: false, confirmationEmail: true, testNotice: true },
  '/public/branches': [huzurnagar, kodad],
}
