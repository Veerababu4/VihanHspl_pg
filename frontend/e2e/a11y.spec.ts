import { AxeBuilder } from '@axe-core/playwright'
import type { Locator, Page } from '@playwright/test'

import { expect, login, test } from './fixtures.ts'

// Automated WCAG 2.1 AA checks (axe-core) on every main screen: contrast, labels, names,
// landmarks, ARIA use. Automated checks find roughly half of the problems; the rest is the
// manual keyboard / screen-reader list in docs/UAT-CHECKLIST.md.

async function expectAccessible(page: Page, label: string): Promise<void> {
  await page.waitForLoadState('networkidle')
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .exclude('iframe')   // Google Maps / Cloudflare content isn't ours to fix
    .analyze()
  const problems = results.violations.map(
    (v) => `${v.id} (${v.impact}): ${v.help} — ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`,
  )
  expect.soft(problems, label).toEqual([])
}

test('public pages meet WCAG 2.1 AA (automated checks)', async ({ page }) => {
  for (const path of ['/', '/doctors', '/branches/MLG', '/gallery', '/about', '/contact', '/appointment-status', '/privacy', '/book']) {
    await page.goto(path)
    await expectAccessible(page, path)
  }
  // Booking wizard, later steps
  await page.goto('/book')
  await page.getByRole('radio', { name: /Kodad/ }).check({ force: true })
  await page.getByRole('radio', { name: /Venkata Ramana/ }).check({ force: true })
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.getByRole('button', { name: /\d+ free/ }).first().click()
  await expectAccessible(page, 'booking: date & time')
  await page.getByRole('button', { name: /token \d+/ }).first().click()
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.getByRole('button', { name: 'Confirm booking' }).click()   // empty form: shows the errors
  await expectAccessible(page, 'booking: details with errors')
})

test('staff screens meet WCAG 2.1 AA (automated checks)', async ({ page }) => {
  await page.goto('/staff/login')
  await expectAccessible(page, 'login')
  await login(page, 'itadmin')
  for (const path of ['/staff/dashboard', '/staff/appointments', '/staff/patients', '/staff/patients/new', '/staff/reports',
    '/staff/schedule', '/staff/book', '/staff/profile', '/staff/admin/users', '/staff/admin/doctors', '/staff/admin/branches',
    '/staff/admin/gallery', '/staff/admin/audit']) {
    await page.goto(path)
    await expectAccessible(page, path)
  }
  await page.goto('/staff/admin/users')
  await page.getByRole('button', { name: 'Add user' }).click()
  await expectAccessible(page, 'dialog: new staff user')
})

/** Opens a dialog from the keyboard, tabs around it both ways, closes it with Escape. */
async function expectFocusTrapped(page: Page, opener: Locator, label: string): Promise<void> {
  await opener.focus()
  await page.keyboard.press('Enter')
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  const focusInside = () => dialog.evaluate((d) => d.contains(document.activeElement))
  // The dialog moves focus in an effect right after it appears, so allow it a moment.
  await expect.poll(focusInside, { message: `${label}: focus moves into the dialog`, timeout: 2_000 }).toBe(true)
  for (let i = 0; i < 30; i++) {
    await page.keyboard.press(i % 4 === 3 ? 'Shift+Tab' : 'Tab')
    expect(await focusInside(), `${label}: focus after key ${i + 1}`).toBe(true)
  }
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(opener, `${label}: focus returns to the opener`).toBeFocused()
}

test('dialogs keep keyboard focus inside and give it back', async ({ page }) => {
  await page.goto('/gallery')
  await expectFocusTrapped(page, page.getByRole('button', { name: /^Open photo/ }).first(), 'gallery lightbox')

  await login(page, 'itadmin')
  await page.goto('/staff/admin/users')
  await expectFocusTrapped(page, page.getByRole('button', { name: 'Add user' }), 'new staff user')
  await page.goto('/staff/admin/doctors')
  await expectFocusTrapped(page, page.getByRole('button', { name: 'Add doctor' }), 'new doctor')
})

test('doctor screens meet WCAG 2.1 AA (automated checks)', async ({ page }) => {
  await login(page, 'doctor006')
  await page.goto('/staff/queue')
  await expectAccessible(page, 'queue')
  const open = page.getByRole('link', { name: /^(Open|View)$/ }).first()
  if (await open.count()) {
    await open.click()
    await page.getByRole('heading', { name: 'Vitals' }).waitFor()
    await expectAccessible(page, 'visit')
    await page.getByRole('link', { name: 'Health history' }).click()
    await expectAccessible(page, 'health history')
  }
})
