import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import '../lib/i18n'
import { fixturePlan } from '../lib/fixtures'
import type { DashboardData } from '../types'
import { DashboardPage } from './DashboardPage'

const mockedApi = vi.hoisted(() => ({
  getDashboard: vi.fn(),
  getQuote: vi.fn(),
  getPlans: vi.fn(),
  getContributionAnalysis: vi.fn(),
}))

vi.mock('../lib/api', () => ({ api: mockedApi }))
vi.mock('../components/charts/PortfolioChart', () => ({ PortfolioChart: () => <div data-testid="portfolio-chart" /> }))

const dashboardData: DashboardData = {
  summary: {
    marketValue: '1000.00',
    costBasis: '990.00',
    netInvested: '990.00',
    unrealizedPnl: '10.00',
    realizedPnl: '0',
    dividendIncome: '0',
    totalPnl: '10.00',
    xirr: '0.05',
  },
  nextDca: null,
  portfolioHistory: [
    { date: '2026-08-27', marketValue: '995.00', netInvested: '990.00', dataStatus: 'FRESH' },
  ],
  holdings: [
    {
      symbol: 'VOO',
      name: 'Vanguard S&P 500 ETF',
      shares: '1.4',
      avgCost: '707.14',
      price: '714.29',
      todayPercent: '0.01',
      marketValue: '1000.00',
      costBasis: '990.00',
      unrealizedPnl: '10.00',
      returnPercent: '0.010101',
      allocation: '1',
      dataStatus: 'FRESH',
    },
  ],
  allocation: [],
  contributionProgress: null,
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter><DashboardPage /></MemoryRouter></QueryClientProvider>)
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedApi.getDashboard.mockResolvedValue({ data: dashboardData, meta: { status: 'FRESH', source: 'API', retrievedAt: '2026-08-28T16:00:00Z' } })
  mockedApi.getQuote.mockResolvedValue({ data: { symbol: 'VOO', price: '714.29' }, meta: { status: 'FRESH', source: 'YAHOO' } })
  mockedApi.getPlans.mockResolvedValue({ data: [fixturePlan], meta: { status: 'FRESH', source: 'API' } })
  mockedApi.getContributionAnalysis.mockResolvedValue({ data: { initial: { principal: '50000' } }, meta: { status: 'FRESH', source: 'API' } })
})

describe('dashboard market refresh', () => {
  it('refreshes tracked quotes after the cached dashboard renders and then reloads the dashboard', async () => {
    renderPage()

    await waitFor(() => expect(mockedApi.getQuote).toHaveBeenCalledWith('VOO'))
    await waitFor(() => expect(mockedApi.getDashboard.mock.calls.length).toBeGreaterThanOrEqual(2))
  })

  it('groups live portfolio value, cumulative P/L, and live TWR in the primary summary', async () => {
    renderPage()

    const summary = await screen.findByLabelText('Portfolio')
    expect(within(summary).getByText('$1,000.00')).toBeInTheDocument()
    expect(within(summary).getByText('Net investment')).toBeInTheDocument()
    expect(within(summary).getByText('$990.00')).toBeInTheDocument()
    expect(within(summary).getByText('Cumulative P/L · live TWR')).toBeInTheDocument()
    expect(within(summary).getByText(/\+\$10\.00/)).toBeInTheDocument()
    expect(within(summary).getByText('+1.01%')).toBeInTheDocument()
    expect(screen.getByText('Long-term performance')).toBeInTheDocument()
    expect(screen.getByText('CAGR')).toBeInTheDocument()
    expect(screen.getByText('XIRR')).toBeInTheDocument()
  })

  it('uses the live current valuation for YTD and since-inception TWR while adjusting for contributions', async () => {
    mockedApi.getDashboard.mockResolvedValue({
      data: {
        ...dashboardData,
        summary: {
          ...dashboardData.summary,
          marketValue: '220.00',
          netInvested: '200.00',
          totalPnl: '20.00',
        },
        portfolioHistory: [
          { date: '2026-01-02', marketValue: '100.00', netInvested: '100.00', dataStatus: 'FRESH' },
          { date: '2026-06-01', marketValue: '110.00', netInvested: '100.00', dataStatus: 'FRESH' },
        ],
      },
      meta: { status: 'FRESH', source: 'API', retrievedAt: '2026-09-04T16:00:00Z' },
    })

    renderPage()

    const summary = await screen.findByLabelText('Portfolio')
    expect(within(summary).getByText(/\+\$20\.00/)).toBeInTheDocument()
    expect(within(summary).getByText('+20.00%')).toBeInTheDocument()
    expect(within(summary).queryByText('+10.00%')).not.toBeInTheDocument()

    const ytdLabel = screen.getByText('YTD · TWR')
    const ytdCard = ytdLabel.closest('.metric-card')
    expect(ytdCard).not.toBeNull()
    expect(within(ytdCard as HTMLElement).getByText('+$20.00')).toBeInTheDocument()
    expect(within(ytdCard as HTMLElement).getByText('+20.00%')).toBeInTheDocument()

    const longTerm = screen.getByText('Long-term performance').closest('.dashboard-long-term-heading')
    expect(longTerm).not.toBeNull()
    expect(within(longTerm as HTMLElement).getByText(/Since inception TWR · live/)).toBeInTheDocument()
    expect(within(longTerm as HTMLElement).getByText('+20.00%')).toBeInTheDocument()
  })

  it('shows the opening skipped month as initial capital without adding it to DCA totals', async () => {
    mockedApi.getDashboard.mockResolvedValue({
      data: {
        ...dashboardData,
        contributionProgress: {
          year: 2026,
          executed: '1500',
          planned: '15000',
          remaining: '13500',
          executionRate: '0.1',
          months: [
            { period: '2026-01', planned: '0', executed: '0', status: 'SKIPPED' },
            { period: '2026-02', planned: '1500', executed: '1500', status: 'COMPLETED' },
          ],
        },
      },
      meta: { status: 'FRESH', source: 'API', retrievedAt: '2026-08-28T16:00:00Z' },
    })

    renderPage()

    expect(await screen.findByText('Initial capital')).toBeInTheDocument()
    expect(screen.getByText('$50,000.00')).toBeInTheDocument()
    expect(screen.getByText('$1,500.00')).toBeInTheDocument()
    expect(screen.getByText(/\/ \$15,000\.00/)).toBeInTheDocument()
  })
})
