import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
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
  it('offers a retry after session lookup fails and recovers to the login screen', async () => {
    mockedApi.getSession.mockRejectedValue(new Error('offline'))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retryDelay: 0 } } })
    render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/settings']}><App /></MemoryRouter></QueryClientProvider>)
    const retry = await screen.findByRole('button', { name: 'Retry' })
    expect(screen.getByRole('alert')).toBeInTheDocument()
    mockedApi.getSession.mockResolvedValue({ data: { authenticated: false } })
    fireEvent.click(retry)
    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
  })

  it('loads the login page when browser preferences cannot be read', async () => {
    const read = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('Blocked', 'SecurityError') })
    try {
      const queryClient = new QueryClient()
      render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/login']}><App /></MemoryRouter></QueryClientProvider>)
      expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
    } finally {
      read.mockRestore()
    }
  })

  it('protects workspace routes and redirects unauthenticated sessions to login', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/settings']}><App /></MemoryRouter></QueryClientProvider>)

    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
    expect(mockedApi.getSession).toHaveBeenCalledTimes(1)
  })
})
