import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { AdminUser } from '../../api/admin'
import type { Me } from '../../auth/session'
import { routes } from '../../routes'
import { layoutApi } from '../../test/fixtures'
import { authHeader, json, mockApi, renderRoutes, requestBody } from '../../test/render'

const admin: Me = { id: 1, username: 'itadmin', fullName: 'Sample IT Admin', role: 'IT_ADMIN', doctorId: null, homeBranchId: 1, mustChangePassword: false }
const receptionist: Me = { ...admin, id: 2, username: 'reception.mlg', fullName: 'Asha Receptionist', role: 'RECEPTIONIST' }
const session = (user: Me, token = 'token-1') => ({ accessToken: token, expiresAt: '2026-10-06T12:00:00Z', user })
const noSession = () => json({ code: 'SESSION_EXPIRED', message: 'Your session has ended.' }, 401)

const userRow: AdminUser = {
  id: 2, username: 'reception.mlg', fullName: 'Asha Receptionist', role: 'RECEPTIONIST', doctorId: null, doctorName: null,
  homeBranchId: 3, mobile: null, email: null, active: true, mustChangePassword: false, locked: false, lastLoginAt: null,
}

const publicApi = { ...layoutApi, '/public/specialities': [], '/public/doctors': [] }

function fillLogin(username: string, password: string) {
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: username } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
}

describe('Staff login', () => {
  it('sends anonymous users to the login page and back after logging in', async () => {
    const spy = mockApi({ ...publicApi, 'POST /auth/refresh': noSession, 'POST /auth/login': session(admin) })
    renderRoutes(routes, '/staff/admin/users')

    expect(await screen.findByRole('heading', { name: 'Staff login' })).toBeInTheDocument()
    fillLogin('itadmin', 'secret-123')

    expect(await screen.findByRole('heading', { name: 'Staff users' })).toBeInTheDocument()   // the page first asked for
    expect(requestBody(spy, '/auth/login')).toEqual({ username: 'itadmin', password: 'secret-123' })
    expect(authHeader(spy, '/admin/users')).toBe('Bearer token-1')
  })

  it('shows the server message for a wrong password and clears the field', async () => {
    mockApi({
      ...publicApi,
      'POST /auth/refresh': noSession,
      'POST /auth/login': () => json({ code: 'INVALID_CREDENTIALS', message: 'Invalid username or password' }, 401),
    })
    renderRoutes(routes, '/staff/login')
    await screen.findByRole('heading', { name: 'Staff login' })
    fillLogin('itadmin', 'wrong')

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password')
    expect(screen.getByLabelText('Password')).toHaveValue('')
  })

  it('after logout, the next person starts at the dashboard, not the previous page', async () => {
    mockApi({
      ...publicApi,
      'POST /auth/refresh': session(admin),
      'POST /auth/logout': () => new Response(null, { status: 204 }),
      'POST /auth/login': session(receptionist, 'token-2'),
      '/admin/users': [],
      '/admin/branches': [],
    })
    renderRoutes(routes, '/staff/admin/users')
    await screen.findByRole('heading', { name: 'Staff users' })

    // Name, role and Log out sit in the top bar (top right), not in the side menu.
    const topBar = screen.getAllByRole('banner').find((h) => within(h).queryByText('Sample IT Admin'))!
    expect(within(topBar).getByText('IT Admin')).toBeInTheDocument()
    expect(within(screen.getByRole('complementary')).queryByRole('button', { name: 'Log out' })).not.toBeInTheDocument()
    fireEvent.click(within(topBar).getByRole('button', { name: 'Log out' }))
    await screen.findByRole('heading', { name: 'Staff login' })
    fillLogin('reception.mlg', 'secret-123')

    expect(await screen.findByText('Welcome, Asha Receptionist')).toBeInTheDocument()
  })

  it('restores the session from the refresh cookie after a page reload', async () => {
    mockApi({ ...publicApi, 'POST /auth/refresh': session(admin) })
    renderRoutes(routes, '/staff/dashboard')
    expect(await screen.findByText('Welcome, Sample IT Admin')).toBeInTheDocument()
  })
})

describe('Temporary password', () => {
  it('must be changed before the portal opens', async () => {
    const spy = mockApi({
      ...publicApi,
      'POST /auth/refresh': session({ ...receptionist, mustChangePassword: true }),
      'POST /auth/change-password': session(receptionist, 'token-2'),
    })
    renderRoutes(routes, '/staff/dashboard')

    expect(await screen.findByRole('heading', { name: 'Set your own password' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'Temp1234abcd' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'short' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'short' } })
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    expect(await screen.findByText('At least 8 characters, with letters and digits.', { selector: '[id$="-error"]' })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'MyNewPass9' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'MyNewPass9' } })
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))

    expect(await screen.findByText('Welcome, Asha Receptionist')).toBeInTheDocument()
    expect(requestBody(spy, '/auth/change-password')).toEqual({ currentPassword: 'Temp1234abcd', newPassword: 'MyNewPass9' })
  })
})

describe('Roles', () => {
  it('hides IT Admin pages from a receptionist', async () => {
    mockApi({ ...publicApi, 'POST /auth/refresh': session(receptionist) })
    renderRoutes(routes, '/staff/admin/users')

    expect(await screen.findByText('You do not have access to this page.')).toBeInTheDocument()
    const nav = screen.getAllByRole('navigation', { name: 'Menu' })[0]
    expect(within(nav).queryByRole('link', { name: 'Staff users' })).not.toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toBeInTheDocument()
  })
})

describe('Expired access token', () => {
  it('is refreshed once and the request retried', async () => {
    let refreshes = 0
    let userCalls = 0
    const spy = mockApi({
      ...publicApi,
      'POST /auth/refresh': () => session(admin, `token-${++refreshes}`),
      '/admin/branches': [],
      '/admin/users': () => (++userCalls === 1 ? json({ code: 'UNAUTHORIZED' }, 401) : [userRow]),
    })
    renderRoutes(routes, '/staff/admin/users')

    expect(await screen.findByText('reception.mlg')).toBeInTheDocument()
    expect(authHeader(spy, '/admin/users', 0)).toBe('Bearer token-1')
    expect(authHeader(spy, '/admin/users', 1)).toBe('Bearer token-2')
  })
})

describe('Users page', () => {
  it('creates a user and shows the temporary password once', async () => {
    const spy = mockApi({
      ...publicApi,
      'POST /auth/refresh': session(admin),
      '/admin/users': [userRow],
      '/admin/branches': [{ id: 3, code: 'KDD', city: 'Kodad' }],
      '/admin/doctors': [],
      'POST /admin/users': json({ user: { ...userRow, id: 9, username: 'new.user', fullName: 'New User' }, temporaryPassword: 'Xy7kQ2mN4pRs' }, 201),
    })
    renderRoutes(routes, '/staff/admin/users')

    fireEvent.click(await screen.findByRole('button', { name: 'Add user' }))
    await screen.findByLabelText('Username')   // form appears once its option lists have loaded
    const dialog = screen.getByRole('dialog', { name: 'New staff user' })
    fireEvent.change(within(dialog).getByLabelText('Username'), { target: { value: 'new.user' } })
    fireEvent.change(within(dialog).getByLabelText('Full name'), { target: { value: 'New User' } })
    await waitFor(() => expect(within(dialog).getAllByRole('option', { name: 'Kodad' }).length).toBeGreaterThan(0))
    fireEvent.change(within(dialog).getByLabelText('Home branch'), { target: { value: '3' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await screen.findByTestId('temp-password')).toHaveTextContent('Xy7kQ2mN4pRs')
    expect(requestBody(spy, 'POST /admin/users')).toMatchObject({ username: 'new.user', fullName: 'New User', role: 'RECEPTIONIST', homeBranchId: 3, doctorId: null })
  })

  it('requires a doctor profile for a doctor login', async () => {
    mockApi({ ...publicApi, 'POST /auth/refresh': session(admin), '/admin/users': [], '/admin/branches': [], '/admin/doctors': [] })
    renderRoutes(routes, '/staff/admin/users')

    fireEvent.click(await screen.findByRole('button', { name: 'Add user' }))
    await screen.findByLabelText('Username')   // form appears once its option lists have loaded
    const dialog = screen.getByRole('dialog', { name: 'New staff user' })
    fireEvent.change(within(dialog).getByLabelText('Role'), { target: { value: 'DOCTOR' } })
    fireEvent.change(within(dialog).getByLabelText('Username'), { target: { value: 'dr.x' } })
    fireEvent.change(within(dialog).getByLabelText('Full name'), { target: { value: 'Dr. X' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await within(dialog).findByText('Select the doctor profile')).toBeInTheDocument()
  })
})
