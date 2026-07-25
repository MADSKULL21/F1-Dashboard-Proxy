import { screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiStatus } from '@/features/health/api-status'
import { renderWithProviders } from '@/test/render'

/**
 * Covers the three states PRD 9 requires. Fetch is stubbed rather than using a
 * mock-service layer because there is exactly one endpoint at this point; F1
 * introduces enough surface to justify something heavier.
 */
describe('ApiStatus', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function respondWith(body: unknown, ok = true) {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify(body), {
        status: ok ? 200 : 500,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  }

  it('shows a skeleton while the request is in flight', () => {
    vi.mocked(fetch).mockReturnValue(new Promise(() => {}))
    renderWithProviders(<ApiStatus />)

    expect(screen.getByText('Checking backend status')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true')
  })

  it('shows status and version once loaded', async () => {
    respondWith({ status: 'UP', version: '0.0.1-SNAPSHOT', timestamp: '2026-07-25T09:12:03Z' })
    renderWithProviders(<ApiStatus />)

    expect(await screen.findByText('UP')).toBeInTheDocument()
    expect(screen.getByText('0.0.1-SNAPSHOT')).toBeInTheDocument()
  })

  it('shows an error state when the API cannot be reached', async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError('Failed to fetch'))
    renderWithProviders(<ApiStatus />)

    await waitFor(() => {
      expect(screen.getByText('API unreachable')).toBeInTheDocument()
    })
  })
})
