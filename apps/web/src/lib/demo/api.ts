import type { ApiResult, AppSettings, DashboardData, EtfMetrics, Instrument, InstrumentSyncResult, InvestmentPlan, PlanCycle, PricePoint, Quote, Recommendation, Session, Transaction, TransactionCsvRow, TransactionImportCommit, TransactionImportPreview, TransactionInput } from '../../types'
import {
  createFixturePlan,
  createFixtureTransaction,
  deleteFixtureTransaction,
  getFixtureCycles,
  getFixtureDashboard,
  getFixtureInstrument,
  getFixtureInstruments,
  getFixtureMetrics,
  getFixturePlan,
  getFixturePlans,
  getFixturePrices,
  getFixtureQuote,
  getFixtureRecommendation,
  getFixtureSession,
  getFixtureSettings,
  getFixtureTransactions,
  importFixtureTransactions,
  searchFixtureInstruments,
  trackFixtureInstrument,
  untrackFixtureInstrument,
  updateFixturePlan,
  updateFixtureSettings,
  updateFixtureTransaction,
} from './fixtures'
import { parseTransactionCsv } from '../transactionCsv'
import { normalizeDashboardData, normalizeMetrics, normalizePricePoints, normalizeRecommendation, normalizeSettings, normalizeSyncResult } from '../api/normalize'

const fixtureMeta = { status: 'STALE' as const, source: 'FIXTURE', message: 'Demo data only. This workspace is not connected to the API.' }

function localImportPreview(csv: string): ApiResult<TransactionImportPreview> {
  const parsed = parseTransactionCsv(csv)
  return {
    data: {
      batchId: `local-import-${Date.now()}`,
      rows: parsed.rows,
      sourceRows: parsed.rows.map(transactionToCsvRow),
      errors: parsed.errors,
    },
    meta: { ...fixtureMeta, message: 'Demo data only. CSV validated locally.' },
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

function localImportCommit(preview: TransactionImportPreview): ApiResult<TransactionImportCommit> {
  const result = importFixtureTransactions(preview.rows)
  return {
    data: { batchId: preview.batchId, importedRows: result.data.length, transactionIds: result.data.map((transaction) => transaction.id) },
    meta: result.meta,
  }
}

export const demoApi = {
  getSession: async (): Promise<ApiResult<Session>> => getFixtureSession(),
  login: async (_username: string, _password: string): Promise<ApiResult<Session>> => getFixtureSession(),
  logout: async (): Promise<ApiResult<{ ok: boolean }>> => ({ data: { ok: true }, meta: getFixtureSession().meta }),
  getDashboard: async (): Promise<ApiResult<DashboardData>> => {
    const result = getFixtureDashboard()
    return { ...result, data: normalizeDashboardData(result.data) }
  },
  getInstruments: async (): Promise<ApiResult<Instrument[]>> => getFixtureInstruments(),
  searchInstruments: async (query: string): Promise<ApiResult<Instrument[]>> => searchFixtureInstruments(query),
  getInstrument: async (symbol: string): Promise<ApiResult<Instrument>> => getFixtureInstrument(symbol),
  trackInstrument: async (symbol: string): Promise<ApiResult<Instrument>> => trackFixtureInstrument(symbol),
  untrackInstrument: async (symbol: string): Promise<ApiResult<Instrument>> => untrackFixtureInstrument(symbol),
  syncInstrument: async (symbol: string): Promise<ApiResult<InstrumentSyncResult>> => {
    const result = {
      data: { symbol: symbol.toUpperCase(), barsSaved: 0, splitsSaved: 0, status: 'STALE' as const, completedAt: new Date().toISOString(), message: 'Demo data only. History sync is not sent to the API.' },
      meta: fixtureMeta,
    }
    return { ...result, data: normalizeSyncResult(result.data) }
  },
  getQuote: async (symbol: string): Promise<ApiResult<Quote>> => getFixtureQuote(symbol),
  getMetrics: async (symbol: string): Promise<ApiResult<EtfMetrics>> => {
    const result = getFixtureMetrics(symbol)
    return { ...result, data: normalizeMetrics(result.data) }
  },
  getPrices: async (symbol: string, range: string): Promise<ApiResult<PricePoint[]>> => {
    const result = getFixturePrices(symbol, range)
    return { ...result, data: normalizePricePoints(result.data) }
  },
  getPlans: async (): Promise<ApiResult<InvestmentPlan[]>> => getFixturePlans(),
  getPlan: async (id: string): Promise<ApiResult<InvestmentPlan>> => getFixturePlan(id),
  createPlan: async (plan: Omit<InvestmentPlan, 'id' | 'cycles'>): Promise<ApiResult<InvestmentPlan>> => createFixturePlan(plan),
  updatePlan: async (id: string, patch: Partial<InvestmentPlan>): Promise<ApiResult<InvestmentPlan>> => updateFixturePlan(id, patch),
  getCycles: async (id: string): Promise<ApiResult<PlanCycle[]>> => getFixtureCycles(id),
  getRecommendation: async (id: string): Promise<ApiResult<Recommendation>> => {
    const result = getFixtureRecommendation(id)
    return { ...result, data: normalizeRecommendation(result.data) }
  },
  getTransactions: async (): Promise<ApiResult<Transaction[]>> => getFixtureTransactions(),
  createTransaction: async (input: TransactionInput): Promise<ApiResult<Transaction>> => createFixtureTransaction(input),
  updateTransaction: async (id: string, input: TransactionInput): Promise<ApiResult<Transaction>> => updateFixtureTransaction(id, input),
  previewTransactionImport: async (csv: string): Promise<ApiResult<TransactionImportPreview>> => localImportPreview(csv),
  commitTransactionImport: async (preview: TransactionImportPreview): Promise<ApiResult<TransactionImportCommit>> => localImportCommit(preview),
  deleteTransaction: async (id: string): Promise<ApiResult<{ id: string }>> => deleteFixtureTransaction(id),
  getSettings: async (): Promise<ApiResult<AppSettings>> => {
    const result = getFixtureSettings()
    return { ...result, data: normalizeSettings(result.data) }
  },
  updateSettings: async (patch: Partial<AppSettings>): Promise<ApiResult<AppSettings>> => {
    const result = updateFixtureSettings(patch)
    return { ...result, data: normalizeSettings(result.data) }
  },
}
