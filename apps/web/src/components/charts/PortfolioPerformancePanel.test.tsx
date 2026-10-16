import type { ComponentProps } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import '../../lib/i18n'
import type { PortfolioHistoryPoint } from '../../types'
import { PortfolioPerformancePanel } from './PortfolioPerformancePanel'

const mockedEcharts = vi.hoisted(() => ({
  use: vi.fn(),
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}))
const mockedBenchmarksApi = vi.hoisted(() => ({
  search: vi.fn(),
  history: vi.fn(),
}))
const mockedAppApi = vi.hoisted(() => ({
  getPortfolioPerformance: vi.fn(),
}))

vi.mock('echarts/core', () => mockedEcharts)
vi.mock('../../lib/api/benchmarks', () => ({ benchmarksApi: mockedBenchmarksApi }))
vi.mock('../../lib/api', () => ({ api: mockedAppApi }))

function renderPanel(history: PortfolioHistoryPoint[] = [], props: Partial<ComponentProps<typeof PortfolioPerformancePanel>> = {}) {
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
  mockedAppApi.getPortfolioPerformance.mockRejectedValue(new Error('performance unavailable in fallback test'))
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

  it('plots each market on its own dates while retaining explicit unavailable points', async () => {
    localStorage.setItem('dca-performance-benchmarks-v1', JSON.stringify([{
      symbol: '000001.SS', name: 'Shanghai', type: 'INDEX', visible: true,
    }]))
    mockedBenchmarksApi.history.mockResolvedValue({
      data: { points: [
        { date: '2026-09-04', value: '100' },
        { date: '2026-09-07', value: '102' },
        { date: '2026-09-08', value: '103' },
        { date: '2026-09-10', value: '104' },
      ] },
    })
    renderPanel([
      { date: '2026-09-04', marketValue: '100', netInvested: '100', dataStatus: 'FRESH' },
      { date: '2026-09-08', marketValue: '101', netInvested: '100', dataStatus: 'FRESH' },
      { date: '2026-09-09', marketValue: null, netInvested: '100', dataStatus: 'PARTIAL' },
      { date: '2026-09-10', marketValue: '102', netInvested: '100', dataStatus: 'FRESH' },
    ])
    await waitFor(() => {
      const chart = mockedEcharts.init.mock.results.at(-1)?.value
      const option = chart?.setOption.mock.calls.at(-1)?.[0]
      expect(option?.series).toHaveLength(2)
      expect(option.xAxis.type).toBe('time')
      expect(option.useUTC).toBe(true)
      expect(option.series[0].data.map(([date]: [string, number | null]) => date.slice(0, 10))).toEqual([
        '2026-09-04', '2026-09-08', '2026-09-09', '2026-09-10',
      ])
      expect(option.series[0].data[2]).toEqual(['2026-09-09T00:00:00Z', null])
      expect(option.series[0].connectNulls).toBe(false)
      expect(option.series[1].data.map(([date]: [string, number]) => date.slice(0, 10))).toEqual([
        '2026-09-04', '2026-09-07', '2026-09-08', '2026-09-10',
      ])
      const tooltip = option.tooltip.formatter([
        { axisValue: Date.parse('2026-09-09'), seriesName: 'Unavailable', value: ['2026-09-09', null] },
      ])
      expect(tooltip).toContain('2026-09-09')
      expect(tooltip).not.toContain('0.00%')
    })
  })

  it('refetches selected benchmarks when the latest portfolio endpoint advances', async () => {
    localStorage.setItem('dca-performance-benchmarks-v1', JSON.stringify([{
      symbol: 'QQQ', name: 'Invesco QQQ Trust', exchange: 'NASDAQ', type: 'ETF', visible: true,
    }]))
    mockedBenchmarksApi.history.mockResolvedValue({
      data: { symbol: 'QQQ', name: 'Invesco QQQ Trust', type: 'ETF', source: 'YAHOO', dataStatus: 'FRESH', points: [] },
      meta: { status: 'FRESH', source: 'YAHOO' },
    })
    const september2: PortfolioHistoryPoint[] = [
      { date: '2026-09-02', marketValue: '100', netInvested: '100', dataStatus: 'FRESH' },
    ]
    const september3: PortfolioHistoryPoint[] = [
      ...september2,
      { date: '2026-09-03', marketValue: '101', netInvested: '100', dataStatus: 'FRESH' },
    ]

    const view = renderPanel(september2)
    await waitFor(() => expect(mockedBenchmarksApi.history).toHaveBeenCalledTimes(1))

    view.rerenderPanel(september3)

    await waitFor(() => expect(mockedBenchmarksApi.history).toHaveBeenCalledTimes(2))
  })
})

describe('performance range summary', () => {
  it('offers ALL and uses provided integration fallback values with correct metric semantics', () => {
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
    expect(within(summary).getByText('Since inception annualized')).toBeInTheDocument()
    expect(within(summary).getByText('Since inception money-weighted')).toBeInTheDocument()
    expect(within(summary).getAllByText('Follows selected range').length).toBeGreaterThanOrEqual(2)
  })

  it('prefers realtime server performance for the selected range', async () => {
    mockedAppApi.getPortfolioPerformance.mockResolvedValue({
      data: {
        range: 'ALL', requestedStartDate: '2026-09-01', baselineDate: '2026-09-01', inceptionDate: '2026-09-01', endpointDate: '2026-09-03', asOf: '2026-09-03T18:00:00Z',
        twr: '0.12', cagr: '0.31', xirr: '0.24', maximumDrawdown: '-0.04', dataStatus: 'FRESH', liveEndpointIncluded: true,
        externalFlowModel: 'CASH_LEDGER_DEPOSIT_WITHDRAWAL',
        points: [
          { date: '2026-09-01', asOf: null, level: '1', returnRate: '0', pointType: 'REGULAR_CLOSE', dataStatus: 'FRESH' },
          { date: '2026-09-03', asOf: '2026-09-03T18:00:00Z', level: '1.12', returnRate: '0.12', pointType: 'LIVE', dataStatus: 'FRESH' },
        ],
      },
      meta: { status: 'FRESH', source: 'API' },
    })
    renderPanel([{ date: '2026-09-01', marketValue: '100', netInvested: '100', dataStatus: 'FRESH' }])

    fireEvent.click(screen.getByRole('button', { name: 'ALL' }))
    await waitFor(() => expect(mockedAppApi.getPortfolioPerformance).toHaveBeenCalledWith('ALL'))

    const summary = screen.getByLabelText('Performance summary')
    expect(await within(summary).findByText('+12.00%')).toBeInTheDocument()
    expect(within(summary).getByText('+31.00%')).toBeInTheDocument()
    expect(within(summary).getByText('+24.00%')).toBeInTheDocument()
    expect(within(summary).getByText('-4.00%')).toBeInTheDocument()
  })

  it('labels CAGR/XIRR fallbacks as since-inception metrics instead of pretending they follow the selected range', () => {
    renderPanel([], { inceptionCagr: '0.08', inceptionXirr: '0.07' })

    const summary = screen.getByLabelText('Performance summary')
    expect(within(summary).getByText('Since inception annualized')).toBeInTheDocument()
    expect(within(summary).getByText('Since inception money-weighted')).toBeInTheDocument()
  })
})
