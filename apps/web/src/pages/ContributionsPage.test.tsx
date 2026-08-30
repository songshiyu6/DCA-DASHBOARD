import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import '../lib/i18n'
import { fixturePlan } from '../lib/fixtures'
import { ContributionsPage } from './ContributionsPage'

const mockedApi = vi.hoisted(() => ({
  getPlans: vi.fn(),
  getContributionAnalysis: vi.fn(),
  classifyInitialContribution: vi.fn(),
}))

vi.mock('../lib/api', () => ({ api: mockedApi }))

const analysis = {
  totalInvested: '52000',
  initial: { plannedPrincipal: '50000', principal: '50000', value: '53850', pnl: '3850', returnRate: '0.077', averageMarketDays: 92, batchCount: 1, dataStatus: 'FRESH' },
  dca: { plannedPrincipal: null, principal: '2000', value: '2106', pnl: '106', returnRate: '0.053', averageMarketDays: 59, batchCount: 1, dataStatus: 'FRESH' },
  unclassifiedAmount: '800',
  unclassifiedBuys: [{ transactionId: 'legacy-buy', tradeDate: '2026-06-01', symbol: 'VOO', principal: '800' }],
  batches: [
    { type: 'INITIAL', period: null, principal: '50000', value: '53850', pnl: '3850', returnRate: '0.077', averageMarketDays: 92, dataStatus: 'FRESH' },
    { type: 'DCA', period: '2026-07', principal: '2000', value: '2106', pnl: '106', returnRate: '0.053', averageMarketDays: 59, dataStatus: 'FRESH' },
  ],
  dataStatus: 'FRESH',
  asOf: '2026-09-01',
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/contributions']}><ContributionsPage /></MemoryRouter></QueryClientProvider>)
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedApi.getPlans.mockResolvedValue({ data: [fixturePlan], meta: { status: 'FRESH', source: 'API' } })
  mockedApi.getContributionAnalysis.mockResolvedValue({ data: analysis, meta: { status: 'FRESH', source: 'API' } })
  mockedApi.classifyInitialContribution.mockResolvedValue({ data: { ...analysis, unclassifiedAmount: '0', unclassifiedBuys: [] }, meta: { status: 'FRESH', source: 'API' } })
})

describe('contribution analysis', () => {
  it('separates initial capital from monthly DCA batches', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Contributions' })).toBeInTheDocument()
    expect(screen.getAllByText('$50,000.00').length).toBeGreaterThan(0)
    expect(screen.getByText('July 2026')).toBeInTheDocument()
    expect(screen.getByText('92 days')).toBeInTheDocument()
  })

  it('requires an explicit action before an unlinked buy becomes initial capital', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Mark as initial' }))
    await waitFor(() => expect(mockedApi.classifyInitialContribution).toHaveBeenCalledWith('core-plan', 'legacy-buy'))
  })
})
