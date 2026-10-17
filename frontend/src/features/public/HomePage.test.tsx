import { fireEvent, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { Branch } from '../../api/public'
import { PublicLayout } from '../../components/PublicLayout'
import { mockApi, renderRoutes } from '../../test/render'
import { HomePage } from './HomePage'

const routes = [{ element: <PublicLayout />, children: [{ index: true, element: <HomePage /> }] }]

const kodad: Branch = {
  id: 3, code: 'KDD', name: 'Vihan Multi Speciality Hospital - Kodad', address: 'Main Road', city: 'Kodad',
  district: 'Suryapet', pincode: null, phone: '0000000003', emergencyPhone: '0000000103', email: null,
  mapUrl: null, imageUrl: null, openingHours: 'OPD: Mon-Sat',
}

describe('HomePage', () => {
  it('lists branches from the API with emergency call links', async () => {
    mockApi({ '/public/branches': [kodad], '/public/app-info': { name: 'Vihan', sampleData: false } })
    renderRoutes(routes)

    expect(await screen.findByRole('heading', { name: 'Kodad' })).toBeInTheDocument()
    const emergencyLinks = screen.getAllByRole('link', { name: '0000000103' })
    expect(emergencyLinks[0]).toHaveAttribute('href', 'tel:0000000103')
  })

  it('shows the SAMPLE DATA banner only when the backend says so', async () => {
    mockApi({ '/public/branches': [], '/public/app-info': { name: 'Vihan', sampleData: true } })
    renderRoutes(routes)

    expect(await screen.findByRole('status')).toHaveTextContent('SAMPLE DATA')
  })

  it('shows the scrolling "not a real hospital" line, which can be paused', async () => {
    mockApi({ '/public/branches': [], '/public/app-info': { name: 'Vihan', sampleData: false, testNotice: true } })
    renderRoutes(routes)

    const note = await screen.findByRole('note', { name: 'Test website notice' })
    expect(note).toHaveTextContent('is NOT a real hospital')
    const pause = within(note).getByRole('button', { name: 'Pause' })
    expect(pause).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(pause)
    expect(within(note).getByRole('button', { name: 'Play' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('hides the test-site line when the backend turns it off', async () => {
    mockApi({ '/public/branches': [kodad], '/public/app-info': { name: 'Vihan', sampleData: false, testNotice: false } })
    renderRoutes(routes)

    expect(await screen.findByRole('heading', { name: 'Kodad' })).toBeInTheDocument()
    expect(screen.queryByRole('note', { name: 'Test website notice' })).not.toBeInTheDocument()
  })

  it('shows an error when branches cannot be loaded', async () => {
    mockApi({
      '/public/branches': new Response('{}', { status: 500 }),
      '/public/app-info': { name: 'Vihan', sampleData: false },
    })
    renderRoutes(routes)

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load hospitals')
  })
})
