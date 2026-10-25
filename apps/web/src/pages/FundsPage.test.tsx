import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import '../lib/i18n'
import { FundsPage } from './FundsPage'

const api = vi.hoisted(() => ({
  getFunds: vi.fn(), getAutoDcaRules: vi.fn(), lookupFund: vi.fn(),
  getFundNav: vi.fn(), getFundCalendar: vi.fn(), getFundPurchases: vi.fn(),
}))
vi.mock('../lib/api', () => ({ api }))
vi.mock('../components/charts/FundNavChart', () => ({ FundNavChart: () => null }))
const meta = { status: 'FRESH', source: 'API' }
const funds = ['000001', '000002'].map((code) => ({
  id: code, code, name: `Fund ${code}`, currency: 'CNY', managementFeeRate: '0',
  confirmationTradingDays: 1, shareClass: 'A', calendarCode: 'CN',
}))
const lookup = { code: '000001', name: 'First fund', managementFeeRate: '0', confirmationTradingDays: 1, shareClass: 'A' }

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><FundsPage /></QueryClientProvider>)
}

beforeEach(() => {
  vi.resetAllMocks()
  api.getFunds.mockResolvedValue({ data: funds, meta })
  api.getAutoDcaRules.mockResolvedValue({ data: [], meta })
  api.getFundNav.mockResolvedValue({ data: [], meta })
  api.getFundPurchases.mockResolvedValue({ data: [], meta })
  api.getFundCalendar.mockResolvedValue({ data: { calendarAvailable: false, missingNavDates: [] }, meta })
})
afterEach(() => vi.useRealTimers())

describe('fund editor boundaries', () => {
  it('ignores metadata arriving after the user changes the fund code', async () => {
    let complete!: (value: unknown) => void
    api.lookupFund.mockImplementation(() => new Promise((resolve) => { complete = resolve }))
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Add fund' }))
    await user.type(screen.getByLabelText('Fund code'), '000001')
    await user.click(screen.getByRole('button', { name: 'Lookup' }))
    await user.clear(screen.getByLabelText('Fund code'))
    await user.type(screen.getByLabelText('Fund code'), '000002')
    await act(async () => complete({ data: lookup, meta }))
    expect(screen.getByLabelText('Fund code')).toHaveValue('000002')
    expect(screen.queryByText('First fund')).not.toBeInTheDocument()
  })

  it('ignores lookup results from a closed editor when a new editor opens', async () => {
    let complete!: (value: unknown) => void
    api.lookupFund.mockImplementation(() => new Promise((resolve) => { complete = resolve }))
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Add fund' }))
    await user.type(screen.getByLabelText('Fund code'), '000001')
    await user.click(screen.getByRole('button', { name: 'Lookup' }))
    await user.click(screen.getAllByRole('button', { name: 'Cancel' }).at(-1)!)
    await user.click(screen.getByRole('button', { name: 'Add fund' }))
    await act(async () => complete({ data: lookup, meta }))
    expect(screen.getByLabelText('Fund code')).toHaveValue('')
  })

  it('uses the Shanghai business date for purchases before UTC midnight', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-24T17:00:00Z'))
    renderPage()
    fireEvent.click((await screen.findAllByRole('button', { name: 'Record purchase' }))[0])
    const date = screen.getByLabelText(/Purchase date/)
    expect(date).toHaveValue('2026-10-25')
    expect(date).toHaveAttribute('max', '2026-10-25')
  })

  it('discards a purchase editor when switching to another fund', async () => {
    renderPage()
    fireEvent.click((await screen.findAllByRole('button', { name: 'Record purchase' }))[0])
    expect(within(screen.getByRole('dialog')).getByText(/000001 · Fund/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '000002 Fund 000002' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })
})
