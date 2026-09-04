import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import '../../lib/i18n'
import type { PortfolioHistoryPoint } from '../../types'
import { PortfolioPerformancePanel } from './PortfolioPerformancePanel'

const mockedBenchmarksApi = vi.hoisted(() => ({
  search: vi.fn(),
  history: vi.fn(),
}))

vi.mock('../../lib/api/benchmarks', () => ({ benchmarksApi: mockedBenchmarksApi }))

function renderPanel(history: PortfolioHistoryPoint[] = [], props: Partial<React.ComponentProps<typeof PortfolioPerformancePanel>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const renderWithHistory = (points: PortfolioHistoryPoint[]) => (
    <QueryClientProvider client={queryClient}><PortfolioPerformancePanel history={points} {...props} /></QueryClientProvider>
  )
  const view = render(renderWithHistory(history))
  return {
    ...view,
    rerenderPanel: (points: PortfolioHistoryPoint[]) => view.rerender(renderWithHistory(points)),
  }
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

  it('refetches selected benchmarks when the latest fresh portfolio close advances', async () => {
    localStorage.setItem('dca-performance-benchmarks-v1', JSON.stringify([{
      symbol: 'QQQ', name: 'Invesco QQQ Trust', exchange: 'NASDAQ', type: 'ETF', visible: true,
    }]))
    mockedBenchmarksApi.history.mockResolvedValue({
      data: { symbol: 'QQQ', name: 'Invesco QQQ Trust', type: 'ETF', source: 'YAHOO', dataStatus: 'FRESH', points: [] },
      meta: { status: 'FRESH', source: 'YAHOO' },
    })
    const september2: PortfolioHistoryPoint[] = [
      { date: '2026-09-02', marketValue: '0', netInvested: '0', dataStatus: 'FRESH' },
    ]
    const september3: PortfolioHistoryPoint[] = [
      ...september2,
      { date: '2026-09-03', marketValue: '0', netInvested: '0', dataStatus: 'FRESH' },
    ]

    const view = renderPanel(september2)
    await waitFor(() => expect(mockedBenchmarksApi.history).toHaveBeenCalledTimes(1))

    view.rerenderPanel(september3)

    await waitFor(() => expect(mockedBenchmarksApi.history).toHaveBeenCalledTimes(2))
  })
})

describe('performance range summary', () => {
  it('offers ALL and uses PR B range summary values when provided', () => {
    renderPanel([], {
      inceptionCagr: '0.08',
      inceptionXirr: '0.07',
      maxDrawdown: '-0.12',
      rangeSummary: {
        ALL: { twr: '0.25', cagr: '0.10', xirr: '0.09', maxDrawdown: '-0.15' },
      },
    })

    expect(screen.getByRole('heading', { name: 'Investment performance (TWR)' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'ALL' }))

    const summary = screen.getByLabelText('Performance summary')
    expect(within(summary).getByText('ALL TWR')).toBeInTheDocument()
    expect(within(summary).getByText('+25.00%')).toBeInTheDocument()
    expect(within(summary).getByText('+10.00%')).toBeInTheDocument()
    expect(within(summary).getByText('+9.00%')).toBeInTheDocument()
    expect(within(summary).getByText('-15.00%')).toBeInTheDocument()
    expect(within(summary).getAllByText('ALL range').length).toBeGreaterThanOrEqual(3)
  })

  it('labels legacy CAGR/XIRR fallbacks as since-inception metrics instead of pretending they follow the selected range', () => {
    renderPanel([], { inceptionCagr: '0.08', inceptionXirr: '0.07' })

    const summary = screen.getByLabelText('Performance summary')
    expect(within(summary).getByText('Since inception annualized')).toBeInTheDocument()
    expect(within(summary).getByText('Since inception money-weighted')).toBeInTheDocument()
  })
})
