import { expect, expectNoSidewaysScroll, randomMobile, test, waitForCaptcha } from './fixtures.ts'

const PUBLIC_PAGES = ['/', '/doctors', '/branches/MLG', '/gallery', '/about', '/contact', '/appointment-status', '/privacy']

test('public pages load under the production security policy', async ({ page }) => {
  for (const path of PUBLIC_PAGES) {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 }).first(), path).toBeVisible()
  }
  await page.goto('/branches/MLG')
  await expect(page.locator('iframe[src*="google.com/maps"]')).toHaveCount(1)
})

test('patient books online, finds the booking, reschedules and cancels it', async ({ page }) => {
  const mobile = randomMobile()

  await page.goto('/doctors?q=venkata')
  await page.getByRole('link', { name: 'Book appointment', exact: true }).first().click()
  await expect(page.getByRole('heading', { name: 'Book an appointment' })).toBeVisible()
  await page.getByRole('radio', { name: /Kodad/ }).check({ force: true })
  await expect(page.getByRole('radio', { name: /Venkata Ramana/ })).toBeVisible()
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.getByRole('button', { name: /\d+ free/ }).first().click()
  await page.getByRole('button', { name: /token \d+/ }).first().click()
  await page.getByRole('button', { name: 'Continue' }).click()

  await page.getByLabel('Patient name').fill('E2E Patient')
  await page.getByLabel('Age (years)').fill('35')
  await page.getByLabel('Female').check()
  await page.getByLabel('Mobile number').fill(mobile)
  await page.getByRole('checkbox').check()
  await waitForCaptcha(page)
  await page.getByRole('button', { name: 'Confirm booking' }).click()
  await expect(page.getByRole('heading', { name: /Booking confirmed/ })).toBeVisible({ timeout: 30_000 })
  const ref = (await page.getByTestId('booking-ref').textContent())!.trim()

  // The link pre-fills the reference: with the mobile too, the booking opens straight away.
  await page.getByRole('link', { name: 'Check or change my booking' }).click()
  await page.getByLabel('Mobile number').fill(mobile)
  await page.getByRole('button', { name: 'Find my booking' }).click()
  await expect(page.getByTestId('booking-ref')).toHaveText(ref)

  // Mobile only: a masked result (no reference, no doctor); entering the reference opens it.
  await page.getByRole('button', { name: 'Check another booking' }).click()
  await page.getByLabel('Booking reference').fill('')
  await page.getByLabel('Mobile number').fill(mobile)
  await waitForCaptcha(page)
  await page.getByRole('button', { name: 'Find my booking' }).click()
  const result = page.getByRole('listitem').filter({ hasText: ref.slice(0, ref.lastIndexOf('-') + 1) + '****' })
  await expect(result).toBeVisible()
  await expect(page.getByText(ref, { exact: true })).toHaveCount(0)
  await result.getByLabel(/enter its booking reference/).fill(ref)
  await result.getByRole('button', { name: 'Open booking' }).click()
  await expect(page.getByTestId('booking-ref')).toHaveText(ref)

  await page.getByRole('button', { name: 'Change date or time' }).click()
  const move = page.getByRole('region', { name: 'Change date or time' })
  await move.getByRole('button', { name: /\d+ free/ }).nth(1).click()
  await move.getByRole('button', { name: /token \d+/ }).first().click()
  await waitForCaptcha(page)
  await move.getByRole('button', { name: 'Move my booking' }).click()
  await expect(page.getByText('Your booking has been moved')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId('booking-ref')).toHaveText(ref)

  await page.getByRole('button', { name: 'Cancel booking' }).click()
  const cancel = page.getByRole('region', { name: 'Cancel this booking?' })
  await cancel.getByLabel('Reason (optional)').fill('E2E test')
  await waitForCaptcha(page)
  await cancel.getByRole('button', { name: 'Yes, cancel it' }).click()
  await expect(page.getByText('Your booking has been cancelled.')).toBeVisible({ timeout: 30_000 })
})

test('phone width: public pages never scroll sideways', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  for (const path of [...PUBLIC_PAGES, '/book']) {
    await page.goto(path)
    await page.waitForLoadState('networkidle')
    await expectNoSidewaysScroll(page)
  }
})
