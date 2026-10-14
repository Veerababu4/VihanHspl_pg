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

test('a tab opened before a deploy reloads instead of failing', async ({ page, browserErrors }) => {
  await login(page, 'itadmin')
  // After a deploy the old page files are gone, and Cloudflare answers with the HTML page instead.
  let faked = false
  await page.route('**/assets/SchedulePage-*.js', async (route) => {
    if (faked) return route.continue()
    faked = true
    await route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Vihan</title>' })
  })
  await page.getByRole('navigation', { name: 'Menu' }).getByRole('link', { name: 'Schedule' }).click()
  await expect(page.getByRole('heading', { level: 1, name: /^Schedule/ })).toBeVisible()
  expect(faked, 'the old page file was asked for').toBe(true)
  // The failed load is reported before the reload: expected here, not a real browser error.
  const others = browserErrors.filter((e) => !/SchedulePage|module script|dynamically imported module/i.test(e))
  browserErrors.splice(0, browserErrors.length, ...others)
})

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

test('recent bookings: search by name, mobile or booking ID; a doctor sees only their own', async ({ page, browser }) => {
  // Read-only, so it also runs against the live site. Earlier tests in this run have made bookings.
  const rows = page.getByRole('main').getByRole('listitem')
  const search = async (q: string) => {
    await page.getByLabel('Search bookings').fill(q)
    await page.getByRole('button', { name: 'Search', exact: true }).click()
    // The result line names the search ("2 bookings match “…”"), so this waits for these results.
    const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    await expect(page.getByText(new RegExp(`match(es)? “${escaped}”`))).toBeVisible()
    await expect(rows.first(), `a booking matches ${q}`).toBeVisible()
  }

  await login(page, 'itadmin')
  await page.goto('/staff/bookings')
  await expect(page.getByText(/^Latest \d+ booking/)).toBeVisible()
  const first = rows.first()
  await expect(first, 'there is at least one booking to search for').toBeVisible()
  const name = (await first.getAttribute('aria-label'))!.replace(/, [^,]*$/, '')
  const mobile = (await first.locator('a[href^="tel:"]').textContent())!.trim()
  const ref = (await first.locator('.font-mono').textContent())!.trim()

  await search(ref.replaceAll('-', ''))   // a full booking ID works without the dashes
  await expect(rows.first().locator('.font-mono')).toHaveText(ref)

  await search(mobile)
  for (const row of await rows.all()) await expect(row.locator('a[href^="tel:"]')).toHaveText(mobile)

  const word = name.split(' ')[0].toLowerCase()   // part of the name, any case
  await search(word)
  for (const row of await rows.all()) expect((await row.getAttribute('aria-label'))!.toLowerCase()).toContain(word)

  await page.getByRole('button', { name: 'Show latest' }).click()
  await expect(page.getByText(/^Latest \d+ booking/)).toBeVisible()

  // Reception and a doctor, each in a separate browser session.
  const openAs = async (username: string) => {
    const context = await browser.newContext()
    const other = await context.newPage()
    await login(other, username)
    await other.goto('/staff/bookings')
    return { context, other }
  }
  const reception = await openAs('reception.mlg')
  await expect(reception.other.getByText('The latest 20 bookings, newest first.')).toBeVisible()
  await reception.context.close()

  const doctor = await openAs(DOCTOR.username)   // the walk-in test above booked them today
  await expect(doctor.other.getByText('Your latest 20 bookings, newest first.')).toBeVisible()
  await expect(doctor.other.getByText(/^Latest \d+ booking|^No bookings yet/)).toBeVisible()
  const doctorName = DOCTOR.option.split(' — ')[0]
  for (const row of await doctor.other.getByRole('main').getByRole('listitem').all()) {
    await expect(row.getByText(doctorName, { exact: true })).toBeVisible()
  }
  await doctor.context.close()
})

test('reception never sees clinical pages', async ({ page }) => {
  await login(page, 'reception.mlg')
  await expect(page.getByRole('navigation', { name: 'Menu' }).getByRole('link', { name: 'My queue' })).toHaveCount(0)
})
