import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import '../lib/i18n'
import { fixturePlan, fixtureTransactions } from '../lib/fixtures'
import type { TransactionImportPreview } from '../types'
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
  mockedApi.createTransaction.mockResolvedValue({ data: transaction, meta: { status: 'FRESH', source: 'API' } })
  mockedApi.deleteTransaction.mockResolvedValue({ data: { id: transaction.id }, meta: { status: 'FRESH', source: 'API' } })
  mockedApi.commitTransactionImport.mockResolvedValue({ data: { batchId: 'batch-1', importedRows: 2, transactionIds: ['txn-a', 'txn-b'] }, meta: { status: 'FRESH', source: 'API' } })
})

describe('transaction form', () => {
  it('closes the dialog with Escape and returns focus to its opener', async () => {
    const user = userEvent.setup()
    renderPage()

    const opener = await screen.findByRole('button', { name: 'Add transaction' })
    await user.click(opener)
    expect(screen.getByRole('dialog', { name: 'Add transaction' })).toBeInTheDocument()

    await user.keyboard('{Escape}')

    expect(screen.queryByRole('dialog', { name: 'Add transaction' })).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
  })

  it('opens an existing ledger entry for editing and sends the canonical BUY payload', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('Monthly DCA')
    await user.click(screen.getByRole('button', { name: 'Edit' }))
    expect(await screen.findByRole('heading', { name: 'Edit transaction' })).toBeInTheDocument()

    const quantity = screen.getByLabelText('Quantity')
    await user.clear(quantity)
    await user.type(quantity, '1.50000000')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(mockedApi.updateTransaction).toHaveBeenCalledWith('txn-001', expect.objectContaining({ instrumentSymbol: 'VOO', transactionType: 'BUY', quantity: '1.50000000', unitPrice: '530.04', currency: 'USD', contributionType: 'DCA', planCycleId: 'cycle-2026-01' })))
  })

  it('keeps DCA execution on BUY and distinguishes it from account funding', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Add transaction' }))
    expect(screen.getByLabelText('DCA execution')).toBeInTheDocument()
    expect(screen.getByText(/DEPOSIT only funds the account/)).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Type'), 'DEPOSIT')

    expect(screen.queryByLabelText('Ticker')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('DCA execution')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Amount')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Amount'), '1000')
    await user.click(screen.getByRole('button', { name: 'Save transaction' }))

    await waitFor(() => expect(mockedApi.createTransaction).toHaveBeenCalledWith(expect.objectContaining({
      transactionType: 'DEPOSIT',
      tradeDate: '2026-08-27',
      amount: '1000',
      fee: '0',
      currency: 'USD',
    })))
    const payload = mockedApi.createTransaction.mock.calls[0][0]
    expect(payload).not.toHaveProperty('instrumentSymbol')
  })

  it('allows an account-level FEE without a symbol', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Add transaction' }))
    await user.selectOptions(screen.getByLabelText('Type'), 'FEE')

    const symbol = screen.getByLabelText('Symbol (optional)')
    await user.clear(symbol)
    await user.type(screen.getByLabelText('Amount'), '4.25')
    await user.click(screen.getByRole('button', { name: 'Save transaction' }))

    await waitFor(() => expect(mockedApi.createTransaction).toHaveBeenCalled())
    const payload = mockedApi.createTransaction.mock.calls[0][0]
    expect(payload).toEqual(expect.objectContaining({ transactionType: 'FEE', amount: '4.25', fee: '0' }))
    expect(payload).not.toHaveProperty('instrumentSymbol')
  })

  it('only enables initial purchase classification on the investment plan start date', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Add transaction' }))
    const date = screen.getByLabelText('Date')
    const source = screen.getByLabelText('DCA execution')
    const initialOption = screen.getByRole('option', { name: /Initial purchase/ })

    expect(initialOption).toBeDisabled()

    await user.clear(date)
    await user.type(date, '2026-01-01')
    expect(initialOption).toBeEnabled()
    await user.selectOptions(source, 'INITIAL')

    await user.type(screen.getByLabelText('Quantity'), '2')
    await user.type(screen.getByLabelText('Unit price'), '500')
    await user.click(screen.getByRole('button', { name: 'Save transaction' }))

    await waitFor(() => expect(mockedApi.createTransaction).toHaveBeenCalledWith(expect.objectContaining({
      instrumentSymbol: 'VOO',
      transactionType: 'BUY',
      tradeDate: '2026-01-01',
      contributionType: 'INITIAL',
      contributionPlanId: 'core-plan',
      planCycleId: null,
    })))
  })
})

describe('server-authoritative CSV import', () => {
  it('does not gate preview on browser financial validation and commits only the server preview', async () => {
    const user = userEvent.setup()
    const preview = {
      batchId: 'batch-1',
      rows: [
        { tradeDate: '2026-09-01', transactionType: 'DEPOSIT' as never, amount: '10000', fee: '0', currency: 'USD' as const },
        { tradeDate: '2026-09-01', transactionType: 'BUY' as const, instrumentSymbol: 'VOO', quantity: '20', unitPrice: '600', fee: '0', currency: 'USD' as const },
      ],
      errors: [],
      warnings: ['Large cash movement'],
      cashImpact: '-2000.00',
      securityImpact: '+20 VOO',
      resultingCashBalance: '-2000.00',
      negativeCash: true,
      rowImpacts: [
        { rowNumber: 2, cashImpact: '+10000.00' },
        { rowNumber: 3, cashImpact: '-12000.00', securityImpact: '+20 VOO' },
      ],
    } as unknown as TransactionImportPreview
    mockedApi.previewTransactionImport.mockResolvedValue({ data: preview, meta: { status: 'FRESH', source: 'API' } })

    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Import CSV' }))

    const editor = screen.getByLabelText('CSV input')
    await user.clear(editor)
    await user.type(editor, 'date,type,amount\n2026-09-01,DEPOSIT,10000')

    expect(screen.getByRole('button', { name: 'Server preview' })).toBeEnabled()
    expect(screen.queryByText(/Missing columns/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Server preview' }))

    await waitFor(() => expect(mockedApi.previewTransactionImport).toHaveBeenCalledWith('date,type,amount\n2026-09-01,DEPOSIT,10000'))
    expect(await screen.findByText('Server preview', { selector: '.csv-preview-header span' })).toBeInTheDocument()
    expect(screen.getByText('Cash account')).toBeInTheDocument()
    expect(screen.getByText(/cash may be negative after import/i)).toBeInTheDocument()
    expect(screen.getByText('Large cash movement')).toBeInTheDocument()

    const commitButton = screen.getByRole('button', { name: /Import 2 rows/ })
    expect(commitButton).toBeEnabled()
    await user.click(commitButton)

    await waitFor(() => expect(mockedApi.commitTransactionImport).toHaveBeenCalledWith(preview))
  })
})
