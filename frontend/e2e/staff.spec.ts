import type { Page } from '@playwright/test'

import { expect, login, test } from './fixtures.ts'

/**
 * The first fictional sample patients (SamplePatientSeeder, fixed random seed: the same in every
 * database), a family sharing one mobile, and a sample doctor who works at Miryalaguda.
 */
const PATIENT_MOBILE = '96973 36649'
const DOCTOR = { username: 'doctor001', option: 'Dr. Srinivas Kandula — General Medicine' }

async function completedAtMiryalaguda(page: Page): Promise<number> {
  await page.getByRole('navigation', { name: 'Menu' }).getByRole('link', { name: 'Dashboard', exact: true }).click()
  await page.getByLabel('Hospital').selectOption({ label: 'Miryalaguda' })
  const card = page.getByRole('region', { name: 'Miryalaguda' })
  await expect(card).toBeVisible()
  await page.waitForLoadState('networkidle')
  const completed = card.locator('dt', { hasText: /^Completed$/ }).locator('xpath=following-sibling::dd[1]')
  return Number(await completed.textContent())
}

test('walk-in at reception → doctor completes the visit → the dashboard counts it', async ({ page, browser }) => {
  await login(page, 'reception.mlg')
  const before = await completedAtMiryalaguda(page)

  await page.getByRole('navigation', { name: 'Menu' }).getByRole('link', { name: 'Appointments' }).click()
  await page.getByRole('button', { name: 'Walk-in' }).click()
  const dialog = page.getByRole('dialog', { name: 'Walk-in patient' })
  await dialog.getByLabel('Patient mobile number').fill(PATIENT_MOBILE)
  await dialog.getByRole('button', { name: 'Find' }).click()
  await dialog.getByRole('button', { name: 'Choose' }).first().click()
  await dialog.getByLabel('Doctor').selectOption({ label: DOCTOR.option })
  await dialog.getByRole('button', { name: 'Add to doctor’s queue' }).click()
  const added = (await page.getByText(/added as walk-in, token \d+/).textContent())!
  const [, patient, token] = /^(.+) added as walk-in, token (\d+)\./.exec(added)!

  // The doctor, in a separate browser session.
  const doctorContext = await browser.newContext()
  const doctor = await doctorContext.newPage()
  await login(doctor, DOCTOR.username)
  await doctor.goto('/staff/queue')
  await doctor.getByRole('listitem', { name: `${patient}, token ${token}` }).getByRole('link', { name: 'Open' }).click()
  await doctor.getByLabel('Chief complaint').fill('Fever for 2 days (E2E)')
  await doctor.getByLabel('Diagnosis').fill('Viral fever (E2E)')
  await doctor.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(doctor.getByText('Saved.')).toBeVisible()
  await doctor.getByRole('button', { name: 'Complete visit' }).click()
  await doctor.getByRole('dialog', { name: 'Complete visit' }).getByRole('button', { name: 'Complete visit' }).click()
  await expect(doctor.getByText('This visit is completed.')).toBeVisible()
  await doctorContext.close()

  await page.reload()
  expect(await completedAtMiryalaguda(page)).toBe(before + 1)
})

test('reception never sees clinical pages', async ({ page }) => {
  await login(page, 'reception.mlg')
  await expect(page.getByRole('navigation', { name: 'Menu' }).getByRole('link', { name: 'My queue' })).toHaveCount(0)
})
