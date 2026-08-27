import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import './lib/i18n'
import App from './App'

const mockedApi = vi.hoisted(() => ({ getSession: vi.fn() }))

vi.mock('./lib/api', () => ({ api: mockedApi }))

beforeEach(() => {
  vi.clearAllMocks()
  mockedApi.getSession.mockResolvedValue({ data: { authenticated: false }, meta: { status: 'FRESH', source: 'API' } })
})

describe('application routing', () => {
  it('protects workspace routes and redirects unauthenticated sessions to login', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/settings']}><App /></MemoryRouter></QueryClientProvider>)

    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
    expect(mockedApi.getSession).toHaveBeenCalledTimes(1)
  })
})
