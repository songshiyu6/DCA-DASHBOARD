import type { Transaction, TransactionCsvRow, TransactionImportCommit, TransactionImportPreview, TransactionInput } from '../../types'
import { apiMeta, request, type ApiResponse } from './transport'
import { normalizeApiResponse, normalizeResult, normalizeTransaction, normalizeTransactions } from './normalize'

interface ServerCsvPreviewRow {
  rowNumber: number
  row: TransactionCsvRow
  valid: boolean
  errors: string[]
  fingerprint: string
}

interface ServerCsvPreview {
  batchId: string
  totalRows: number
  validRows: number
  invalidRows: number
  rows: ServerCsvPreviewRow[]
}

function csvRowToTransaction(row: TransactionCsvRow): TransactionInput {
  const trade = row.type === 'BUY' || row.type === 'SELL'
  return {
    tradeDate: row.date,
    transactionType: row.type,
    instrumentSymbol: row.symbol.toUpperCase(),
    quantity: trade ? row.quantity ?? undefined : undefined,
    unitPrice: trade ? row.price ?? undefined : undefined,
    amount: trade ? undefined : row.amount ?? undefined,
    fee: row.fee || '0',
    currency: 'USD',
    notes: row.notes || undefined,
    planCycleId: row.planCycleId ?? null,
  }
}

function transactionToCsvRow(transaction: TransactionInput): TransactionCsvRow {
  return {
    date: transaction.tradeDate,
    type: transaction.transactionType,
    symbol: transaction.instrumentSymbol,
    quantity: transaction.quantity,
    price: transaction.unitPrice,
    fee: transaction.fee,
    amount: transaction.amount,
    planCycleId: transaction.planCycleId,
    notes: transaction.notes,
  }
}

function normalizeDelete(value: unknown): { id: string } {
  if (typeof value === 'object' && value !== null && 'id' in value && typeof value.id === 'string') return { id: value.id }
  return value as { id: string }
}

export const transactionsApi = {
  getTransactions: async (): ApiResponse<Transaction[]> => normalizeResult(await request<unknown>('/transactions'), normalizeTransactions, apiMeta()),
  createTransaction: async (input: TransactionInput): ApiResponse<Transaction> => normalizeResult(await request<unknown>('/transactions', {
    method: 'POST',
    body: JSON.stringify(input),
  }), normalizeTransaction, apiMeta()),
  updateTransaction: async (id: string, input: TransactionInput): ApiResponse<Transaction> => normalizeResult(await request<unknown>(`/transactions/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  }), normalizeTransaction, apiMeta()),
  previewTransactionImport: async (csv: string): ApiResponse<TransactionImportPreview> => {
    const form = new FormData()
    form.append('file', new Blob([csv], { type: 'text/csv' }), 'transactions.csv')
    const response = normalizeApiResponse<ServerCsvPreview>(await request<unknown>('/transactions/import/preview', { method: 'POST', body: form }), apiMeta())
    const errors = response.data.rows.flatMap((item) => item.errors.map((error) => `Row ${item.rowNumber}: ${error}`))
    return {
      data: {
        batchId: response.data.batchId,
        rows: response.data.rows.map((item) => csvRowToTransaction(item.row)),
        sourceRows: response.data.rows.map((item) => item.row),
        errors,
        duplicateRows: response.data.rows.filter((item) => item.errors.some((error) => error.toLowerCase().includes('duplicate'))).map((item) => item.rowNumber),
      },
      meta: response.meta,
    }
  },
  commitTransactionImport: async (preview: TransactionImportPreview): ApiResponse<TransactionImportCommit> => normalizeResult(await request<unknown>('/transactions/import/commit', {
    method: 'POST',
    body: JSON.stringify({
      batchId: preview.batchId,
      rows: preview.sourceRows ?? preview.rows.map(transactionToCsvRow),
    }),
  }), (value) => value as TransactionImportCommit, apiMeta()),
  deleteTransaction: async (id: string): ApiResponse<{ id: string }> => normalizeResult(await request<unknown>(`/transactions/${encodeURIComponent(id)}`, { method: 'DELETE' }), normalizeDelete, apiMeta()),
}
