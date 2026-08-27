import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import '../lib/i18n'
import { EtfsPage } from './EtfsPage'

const mockedApi = vi.hoisted(() => ({
  getInstruments: vi.fn(),
  getQuote: vi.fn(),
  searchInstruments: vi.fn(),
  trackInstrument: vi.fn(),
}))

vi.mock('../lib/api', () => ({ api: mockedApi }))

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter><EtfsPage /></MemoryRouter></QueryClientProvider>)
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedApi.getInstruments.mockResolvedValue({ data: [], meta: { status: 'FRESH', source: 'API' } })
  mockedApi.searchInstruments.mockResolvedValue({ data: [], meta: { status: 'FRESH', source: 'API' } })
})

describe('ETF search', () => {
  it('renders provider-confirmed QQQ and VOO results', async () => {
    mockedApi.searchInstruments.mockResolvedValue({
      data: [
        { symbol: 'QQQ', name: 'Invesco QQQ Trust', exchange: 'NASDAQ', currency: 'USD', type: 'ETF', tracked: false },
        { symbol: 'VOO', name: 'Vanguard S&P 500 ETF', exchange: 'NYSEArca', currency: 'USD', type: 'ETF', tracked: false },
      ],
      meta: { status: 'FRESH', source: 'API' },
    })
    renderPage()

    fireEvent.click(screen.getAllByRole('button', { name: /Add ETF/i })[0])
    fireEvent.change(screen.getByPlaceholderText('Search by ticker or fund name'), { target: { value: 'QQQ' } })

    expect(await screen.findByText(/Invesco QQQ Trust/)).toBeInTheDocument()
    expect(screen.getByText(/Vanguard S&P 500 ETF/)).toBeInTheDocument()
    expect(mockedApi.searchInstruments).toHaveBeenCalledWith('QQQ')
  })

  it('shows a provider error instead of an empty-result message', async () => {
    mockedApi.searchInstruments.mockRejectedValue(new Error('ETF search is temporarily unavailable'))
    renderPage()

    fireEvent.click(screen.getAllByRole('button', { name: /Add ETF/i })[0])
    fireEvent.change(screen.getByPlaceholderText('Search by ticker or fund name'), { target: { value: 'VOO' } })

    expect(await screen.findByRole('alert')).toHaveTextContent('ETF search is temporarily unavailable')
    expect(screen.queryByText('没有匹配的 ETF。')).not.toBeInTheDocument()
  })
})
