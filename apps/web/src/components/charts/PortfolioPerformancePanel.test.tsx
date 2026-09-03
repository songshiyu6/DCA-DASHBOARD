import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import '../../lib/i18n'
import { PortfolioPerformancePanel } from './PortfolioPerformancePanel'

const mockedBenchmarksApi = vi.hoisted(() => ({
  search: vi.fn(),
  history: vi.fn(),
}))

vi.mock('../../lib/api/benchmarks', () => ({ benchmarksApi: mockedBenchmarksApi }))

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><PortfolioPerformancePanel history={[]} /></QueryClientProvider>)
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  mockedBenchmarksApi.search.mockResolvedValue({
    data: [
      { symbol: 'QQQ', name: 'Invesco QQQ Trust', exchange: 'NASDAQ', type: 'ETF' },
      { symbol: '^GSPC', name: 'S&P 500', exchange: 'SNP', type: 'INDEX' },
      { symbol: 'AAPL', name: 'Apple Inc.', exchange: 'NASDAQ', type: 'EQUITY' },
    ],
    meta: { status: 'FRESH', source: 'YAHOO' },
  })
  mockedBenchmarksApi.history.mockResolvedValue({
    data: { symbol: 'AAPL', name: 'Apple Inc.', type: 'EQUITY', source: 'YAHOO', dataStatus: 'FRESH', points: [] },
    meta: { status: 'FRESH', source: 'YAHOO' },
  })
})

describe('portfolio performance benchmark search', () => {
  it('debounces typing, shows ETF/index/equity results, and persists an equity selection', async () => {
    renderPanel()
    const input = screen.getByRole('textbox')

    fireEvent.change(input, { target: { value: 'Q' } })
    fireEvent.change(input, { target: { value: 'QQ' } })
    fireEvent.change(input, { target: { value: 'QQQ' } })

    await waitFor(() => expect(mockedBenchmarksApi.search).toHaveBeenCalledTimes(1), { timeout: 1_500 })
    expect(mockedBenchmarksApi.search).toHaveBeenCalledWith('QQQ')
    expect(await screen.findByText('QQQ')).toBeInTheDocument()
    expect(screen.getByText('^GSPC')).toBeInTheDocument()
    expect(screen.getByText('AAPL')).toBeInTheDocument()
    expect(screen.getAllByText('EQUITY').length).toBeGreaterThan(0)

    fireEvent.click(screen.getByText('AAPL'))

    await waitFor(() => expect(localStorage.getItem('dca-performance-benchmarks-v1')).toContain('"type":"EQUITY"'))
  })
})
