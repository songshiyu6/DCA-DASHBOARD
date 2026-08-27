import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import '../lib/i18n'
import { DataStateBanner } from './DataState'

describe('data freshness banner', () => {
  it('renders delayed status, as-of date, and provider source', () => {
    render(<DataStateBanner status="STALE" message="Market data delayed" source="YAHOO" asOf="2026-08-26" retrievedAt="2026-08-27T20:02:00Z" />)

    expect(screen.getByText('Market data delayed')).toBeInTheDocument()
    expect(screen.getByText('YAHOO')).toBeInTheDocument()
    expect(screen.getByText(/Aug 26, 2026/)).toBeInTheDocument()
  })

  it('exposes a retry action for unavailable data', () => {
    const onRetry = vi.fn()
    render(<DataStateBanner status="UNAVAILABLE" message="Historical data is temporarily unavailable" onRetry={onRetry} />)

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    expect(onRetry).toHaveBeenCalledOnce()
  })
})
