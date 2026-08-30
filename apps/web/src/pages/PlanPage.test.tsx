import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import '../lib/i18n'
import { fixtureInstruments, fixturePlan } from '../lib/fixtures'
import type { InvestmentPlan } from '../types'
import { PlanPage } from './PlanPage'

const mockedApi = vi.hoisted(() => ({
  getPlans: vi.fn(),
  getInstruments: vi.fn(),
  getCycles: vi.fn(),
  getRecommendation: vi.fn(),
  getContributionAnalysis: vi.fn(),
  updateInitialCapital: vi.fn(),
  createPlan: vi.fn(),
  updatePlan: vi.fn(),
}))

vi.mock('../lib/api', () => ({ api: mockedApi }))

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/plan']}><PlanPage /></MemoryRouter></QueryClientProvider>)
}

beforeEach(() => {
  vi.clearAllMocks()
  const plan: InvestmentPlan = { ...fixturePlan, cycles: [] }
  mockedApi.getPlans.mockResolvedValue({ data: [plan], meta: { status: 'FRESH', source: 'API' } })
  mockedApi.getInstruments.mockResolvedValue({ data: fixtureInstruments, meta: { status: 'FRESH', source: 'API' } })
  mockedApi.getCycles.mockResolvedValue({ data: [], meta: { status: 'FRESH', source: 'API' } })
  mockedApi.getRecommendation.mockResolvedValue({ data: { amount: '1500.00', method: 'CONTRIBUTION_FIRST', dataStatus: 'FRESH', items: [] }, meta: { status: 'FRESH', source: 'API' } })
  mockedApi.getContributionAnalysis.mockResolvedValue({ data: { totalInvested: '0', initial: { plannedPrincipal: '50000.00', principal: '0', value: '0', pnl: '0', returnRate: null, averageMarketDays: 0, batchCount: 0, dataStatus: 'FRESH' }, dca: { plannedPrincipal: null, principal: '0', value: '0', pnl: '0', returnRate: null, averageMarketDays: 0, batchCount: 0, dataStatus: 'FRESH' }, unclassifiedAmount: '0', unclassifiedBuys: [], batches: [], dataStatus: 'FRESH', asOf: '2026-08-27' }, meta: { status: 'FRESH', source: 'API' } })
  mockedApi.updatePlan.mockResolvedValue({ data: plan, meta: { status: 'FRESH', source: 'API' } })
  mockedApi.updateInitialCapital.mockResolvedValue({ data: {}, meta: { status: 'FRESH', source: 'API' } })
})

describe('plan editor', () => {
  it('blocks a plan whose target weights do not total 100 percent', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Initial capital')).toHaveValue('50000.00'))
    const weight = screen.getByLabelText('VOO target weight')
    await user.clear(weight)
    await user.type(weight, '99.00')
    await user.tab()

    expect(await screen.findByText('Target weights must total 100.00%.')).toBeInTheDocument()
    expect(weight).toHaveAttribute('aria-describedby', 'plan-asset-0-weight-error')
    expect(screen.getByRole('button', { name: /Save changes/ })).toBeDisabled()
  })

  it('saves initial capital separately from the monthly DCA plan payload', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Initial capital')).toHaveValue('50000.00'))
    const saveButton = screen.getByRole('button', { name: /Save changes/ })
    await user.click(saveButton)

    await waitFor(() => expect(mockedApi.updatePlan).toHaveBeenCalledWith('core-plan', expect.objectContaining({ monthlyBudget: '1500.00', assets: expect.arrayContaining([{ symbol: 'VOO', targetWeight: '0.50000000' }]) })))
    expect(mockedApi.updateInitialCapital).toHaveBeenCalledWith('core-plan', '50000.00')
  })
})
