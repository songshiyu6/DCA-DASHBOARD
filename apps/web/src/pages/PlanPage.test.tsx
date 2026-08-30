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
  const plan: InvestmentPlan = { ...fixturePlan, monthlyBudget: '1500.000000', cycles: [] }
  mockedApi.getPlans.mockResolvedValue({ data: [plan], meta: { status: 'FRESH', source: 'API' } })
  mockedApi.getInstruments.mockResolvedValue({ data: fixtureInstruments, meta: { status: 'FRESH', source: 'API' } })
  mockedApi.getCycles.mockResolvedValue({ data: [], meta: { status: 'FRESH', source: 'API' } })
  mockedApi.getRecommendation.mockResolvedValue({ data: { amount: '1500.00', method: 'CONTRIBUTION_FIRST', dataStatus: 'FRESH', items: [] }, meta: { status: 'FRESH', source: 'API' } })
  mockedApi.getContributionAnalysis.mockResolvedValue({ data: { initial: { principal: '50000' } }, meta: { status: 'FRESH', source: 'API' } })
  mockedApi.updatePlan.mockResolvedValue({ data: plan, meta: { status: 'FRESH', source: 'API' } })
})

describe('plan editor', () => {
  it('blocks a plan whose target weights do not total 100 percent', async () => {
    const user = userEvent.setup()
    renderPage()

    const weight = await screen.findByLabelText('VOO target weight')
    await user.clear(weight)
    await user.type(weight, '99.00')
    await user.tab()

    expect(await screen.findByText('Target weights must total 100.00%.')).toBeInTheDocument()
    expect(weight).toHaveAttribute('aria-describedby', 'plan-asset-0-weight-error')
    expect(screen.getByRole('button', { name: /Save changes/ })).toBeDisabled()
  })

  it('compacts stored decimal scale in editable fields and still saves canonical values', async () => {
    const user = userEvent.setup()
    renderPage()

    expect(await screen.findByLabelText('Monthly budget')).toHaveValue('1500')
    expect(screen.getByLabelText('VOO target weight')).toHaveValue('50')
    expect(screen.queryByLabelText('Initial capital')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Save changes/ }))

    await waitFor(() => expect(mockedApi.updatePlan).toHaveBeenCalledWith('core-plan', expect.objectContaining({
      monthlyBudget: '1500.00',
      assets: expect.arrayContaining([{ symbol: 'VOO', targetWeight: '0.50000000' }]),
    })))
  })

  it('shows the skipped opening cycle as the actual initial contribution', async () => {
    mockedApi.getCycles.mockResolvedValue({
      data: [{ id: 'cycle-2026-01', period: '2026-01', plannedAmount: '0', executedAmount: '0', status: 'SKIPPED', assets: [] }],
      meta: { status: 'FRESH', source: 'API' },
    })

    renderPage()

    expect(await screen.findByText('$50,000.00')).toBeInTheDocument()
    expect(screen.getAllByText('Initial capital').length).toBeGreaterThan(0)
    expect(screen.queryByText('SKIPPED')).not.toBeInTheDocument()
  })
})
