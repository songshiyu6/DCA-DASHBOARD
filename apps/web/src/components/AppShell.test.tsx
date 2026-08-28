import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import '../lib/i18n'
import { AppShell } from './AppShell'

const mockedApi = vi.hoisted(() => ({ logout: vi.fn() }))

vi.mock('../lib/api', () => ({ api: mockedApi }))

beforeEach(() => {
  vi.clearAllMocks()
  mockedApi.logout.mockResolvedValue({ data: { ok: true }, meta: { status: 'FRESH', source: 'API' } })
})

describe('workspace shell session cleanup', () => {
  it('clears cached user data when signing out', async () => {
    const user = userEvent.setup()
    const queryClient = new QueryClient()
    queryClient.setQueryData(['dashboard'], { data: { summary: { marketValue: '100.00' } } })
    queryClient.setQueryData(['transactions'], { data: [{ id: 'txn-1' }] })

    render(<QueryClientProvider client={queryClient}><MemoryRouter><AppShell /></MemoryRouter></QueryClientProvider>)

    await user.click(screen.getByRole('button', { name: /Song/ }))
    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    await waitFor(() => expect(queryClient.getQueryData(['dashboard'])).toBeUndefined())
    expect(queryClient.getQueryData(['transactions'])).toBeUndefined()
    expect(queryClient.getQueryData(['session'])).toMatchObject({ data: { authenticated: false } })
  })
})
