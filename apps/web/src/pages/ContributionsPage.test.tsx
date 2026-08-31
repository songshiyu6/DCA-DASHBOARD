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
  previewContributionClassifications: vi.fn(),
  commitContributionClassifications: vi.fn(),
}))

vi.mock('../lib/api', () => ({ api: mockedApi }))

const analysis = {
  totalInvested: '52000',
  initial: { principal: '50000', value: '53850', pnl: '3850', returnRate: '0.077', averageMarketDays: 92, batchCount: 1, dataStatus: 'FRESH' },
  dca: { principal: '2000', value: '2106', pnl: '106', returnRate: '0.053', averageMarketDays: 59, batchCount: 1, dataStatus: 'FRESH' },
  unclassifiedAmount: '1600',
  unclassifiedBuys: [
    { transactionId: 'opening-buy', tradeDate: '2026-01-01', symbol: 'VOO', principal: '800', eligibleForInitial: true },
    { transactionId: 'legacy-buy', tradeDate: '2026-06-01', symbol: 'QQQ', principal: '800', eligibleForInitial: false },
  ],
  unclassifiedScope: 'ACCOUNT',
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
  mockedApi.previewContributionClassifications.mockResolvedValue({ data: { previewHash: 'preview-1', valid: true, items: [{ transactionId: 'opening-buy', classification: 'INITIAL', tradeDate: '2026-01-01', symbol: 'VOO', principal: '800', valid: true, errors: [] }] }, meta: { status: 'FRESH', source: 'API' } })
  mockedApi.commitContributionClassifications.mockResolvedValue({ data: { batchId: 'batch-1', transactionIds: ['opening-buy'], analysis: { ...analysis, unclassifiedAmount: '800', unclassifiedBuys: [analysis.unclassifiedBuys[1]] } }, meta: { status: 'FRESH', source: 'API' } })
})

describe('contribution analysis', () => {
  it('separates actual initial capital from monthly DCA batches without a planned initial amount', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Contributions' })).toBeInTheDocument()
    expect(screen.getAllByText('$50,000.00').length).toBeGreaterThan(0)
    expect(screen.queryByText('Planned initial capital')).not.toBeInTheDocument()
    expect(screen.getByText('From actual BUY transactions')).toBeInTheDocument()
    expect(screen.getByText('July 2026')).toBeInTheDocument()
    expect(screen.getAllByText('92 days').length).toBeGreaterThan(0)
  })

  it('previews and confirms a mixed bulk classification instead of writing each row immediately', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('checkbox', { name: 'Select VOO 2026-01-01' }))
    await user.click(screen.getByRole('button', { name: 'Preview 1 changes' }))
    await waitFor(() => expect(mockedApi.previewContributionClassifications).toHaveBeenCalledWith('core-plan', [{ transactionId: 'opening-buy', classification: 'INITIAL' }]))
    expect(mockedApi.commitContributionClassifications).not.toHaveBeenCalled()
    await user.click(await screen.findByRole('button', { name: 'Confirm atomic commit' }))
    await waitFor(() => expect(mockedApi.commitContributionClassifications).toHaveBeenCalledWith('core-plan', 'preview-1', [{ transactionId: 'opening-buy', classification: 'INITIAL' }]))
  })

  it('defaults non-opening rows to outside-plan and labels the queue as account-wide', async () => {
    const user = userEvent.setup()
    renderPage()

    expect(await screen.findByText(/account-wide queue/)).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: 'Select QQQ 2026-06-01' }))
    expect(screen.getByRole('combobox', { name: 'QQQ 2026-06-01 classification' })).toHaveValue('UNPLANNED')
  })
})
