import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import '../lib/i18n'
import type { DashboardData } from '../types'
import { DashboardPage } from './DashboardPage'

const mockedApi = vi.hoisted(() => ({
  getDashboard: vi.fn(),
  getQuote: vi.fn(),
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
})

describe('dashboard market refresh', () => {
  it('refreshes tracked quotes after the cached dashboard renders and then reloads the dashboard', async () => {
    renderPage()

    await waitFor(() => expect(mockedApi.getQuote).toHaveBeenCalledWith('VOO'))
    await waitFor(() => expect(mockedApi.getDashboard.mock.calls.length).toBeGreaterThanOrEqual(2))
  })
})
