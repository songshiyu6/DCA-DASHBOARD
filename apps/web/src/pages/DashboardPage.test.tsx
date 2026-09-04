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
vi.mock('../components/charts/PortfolioPerformancePanel', () => ({
  PortfolioPerformancePanel: (props: { inceptionCagr?: string | null; inceptionXirr?: string | null; maxDrawdown?: string | null }) => (
    <div data-testid="performance-panel" data-cagr={props.inceptionCagr ?? ''} data-xirr={props.inceptionXirr ?? ''} data-max-drawdown={props.maxDrawdown ?? ''} />
  ),
}))

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

  it('presents Portfolio, Today, and Cash without the old standalone YTD or inception TWR cards', async () => {
    renderPage()

    const portfolio = await screen.findByLabelText('Portfolio')
    expect(within(portfolio).getByText('Total value')).toBeInTheDocument()
    expect(within(portfolio).getByText('$1,000.00')).toBeInTheDocument()
    expect(within(portfolio).getByText('Net investment')).toBeInTheDocument()
    expect(within(portfolio).getByText('$990.00')).toBeInTheDocument()
    expect(within(portfolio).getByText('Cumulative P/L')).toBeInTheDocument()
    expect(within(portfolio).getByText('+$10.00')).toBeInTheDocument()
    expect(within(portfolio).getByText('Cumulative return · ALL TWR')).toBeInTheDocument()
    expect(within(portfolio).getByText('+0.51%')).toBeInTheDocument()

    expect(screen.getByText('Today')).toBeInTheDocument()
    expect(screen.getByLabelText('Cash')).toBeInTheDocument()
    expect(screen.queryByText('YTD · TWR')).not.toBeInTheDocument()
    expect(screen.queryByText('Long-term performance')).not.toBeInTheDocument()
    expect(screen.queryByText('Since inception TWR')).not.toBeInTheDocument()
  })

  it('uses optional PR A cash fields when available instead of inventing a cash balance', async () => {
    const cashReady = {
      ...dashboardData,
      summary: {
        ...dashboardData.summary,
        cashBalance: '250.00',
        securitiesValue: '1000.00',
        totalPortfolioValue: '1250.00',
        cashAllocation: '0.20',
        allTwr: '0.12',
        cagr: '0.08',
        maxDrawdown: '-0.06',
      },
    } as DashboardData
    mockedApi.getDashboard.mockResolvedValue({ data: cashReady, meta: { status: 'FRESH', source: 'API', retrievedAt: '2026-08-28T16:00:00Z' } })

    renderPage()

    const portfolio = await screen.findByLabelText('Portfolio')
    expect(within(portfolio).getByText('$1,250.00')).toBeInTheDocument()
    expect(within(portfolio).getByText('+12.00%')).toBeInTheDocument()

    const cash = screen.getByLabelText('Cash')
    expect(within(cash).getByText('Cash balance')).toBeInTheDocument()
    expect(within(cash).getByText('$250.00')).toBeInTheDocument()
    expect(within(cash).getByText('Securities value')).toBeInTheDocument()
    expect(within(cash).getByText('$1,000.00')).toBeInTheDocument()
    expect(within(cash).getByText('Total portfolio value')).toBeInTheDocument()
    expect(within(cash).getByText('$1,250.00')).toBeInTheDocument()
    expect(within(cash).getByText('Cash allocation')).toBeInTheDocument()
    expect(within(cash).getByText('20.00%')).toBeInTheDocument()

    const performance = await screen.findByTestId('performance-panel')
    expect(performance).toHaveAttribute('data-cagr', '0.08')
    expect(performance).toHaveAttribute('data-xirr', '0.05')
    expect(performance).toHaveAttribute('data-max-drawdown', '-0.06')
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
