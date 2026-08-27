import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import '../lib/i18n'
import { fixturePlan, fixtureTransactions } from '../lib/fixtures'
import { TransactionsPage } from './TransactionsPage'

const mockedApi = vi.hoisted(() => ({
  getTransactions: vi.fn(),
  getPlans: vi.fn(),
  getCycles: vi.fn(),
  updateTransaction: vi.fn(),
  createTransaction: vi.fn(),
  deleteTransaction: vi.fn(),
  previewTransactionImport: vi.fn(),
  commitTransactionImport: vi.fn(),
}))

vi.mock('../lib/api', () => ({ api: mockedApi }))

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/transactions']}><TransactionsPage /></MemoryRouter></QueryClientProvider>)
}

beforeEach(() => {
  vi.clearAllMocks()
  const transaction = fixtureTransactions[0]
  mockedApi.getTransactions.mockResolvedValue({ data: [transaction], meta: { status: 'FRESH', source: 'API' } })
  mockedApi.getPlans.mockResolvedValue({ data: [fixturePlan], meta: { status: 'FRESH', source: 'API' } })
  mockedApi.getCycles.mockResolvedValue({ data: fixturePlan.cycles ?? [], meta: { status: 'FRESH', source: 'API' } })
  mockedApi.updateTransaction.mockResolvedValue({ data: transaction, meta: { status: 'FRESH', source: 'API' } })
})

describe('transaction form', () => {
  it('opens an existing ledger entry for editing and sends the canonical payload', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('Monthly DCA')
    await user.click(screen.getByRole('button', { name: 'Edit' }))
    expect(await screen.findByRole('heading', { name: 'Edit transaction' })).toBeInTheDocument()

    const quantity = screen.getByLabelText('Quantity')
    await user.clear(quantity)
    await user.type(quantity, '1.50000000')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(mockedApi.updateTransaction).toHaveBeenCalledWith('txn-001', expect.objectContaining({ instrumentSymbol: 'VOO', transactionType: 'BUY', quantity: '1.50000000', unitPrice: '530.04', currency: 'USD' })))
  })
})
