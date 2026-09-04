import type { ApiResult, AppSettings, ContributionAnalysis, ContributionBatch, ContributionClassificationCommit, ContributionClassificationItem, ContributionClassificationPreview, DashboardData, EtfMetrics, Instrument, InstrumentSyncResult, InvestmentPlan, PlanCycle, PricePoint, Quote, Recommendation, Session, Transaction, TransactionCsvRow, TransactionImportCommit, TransactionImportPreview, TransactionInput } from '../../types'
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
import { decimal } from '../format'

const fixtureMeta = { status: 'STALE' as const, source: 'FIXTURE', message: 'Demo data only. This workspace is not connected to the API.' }
const DEMO_TODAY = '2026-08-27'
const demoInitialTransactions = new Set<string>()
const demoUnplannedTransactions = new Set<string>()

function localImportPreview(csv: string): ApiResult<TransactionImportPreview> {
  const parsed = parseTransactionCsv(csv)
  return {
    data: {
      batchId: `local-import-${Date.now()}`,
      rows: parsed.rows,
      sourceRows: parsed.rows.map(transactionToCsvRow),
      errors: parsed.errors,
    },
    meta: { ...fixtureMeta, message: 'Demo parser only. Live CSV imports use the server preview as the authority.' },
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

function buyPrincipal(transaction: Transaction): string {
  if (!transaction.quantity || !transaction.unitPrice) return '0'
  return decimal(transaction.quantity).mul(transaction.unitPrice).plus(transaction.fee).toString()
}

function marketDays(date: string): number {
  const start = new Date(`${date}T12:00:00Z`).getTime()
  const end = new Date(`${DEMO_TODAY}T12:00:00Z`).getTime()
  return Math.max(0, Math.round((end - start) / 86_400_000))
}

function batchFromTransactions(type: 'INITIAL' | 'DCA', period: string | null, transactions: Transaction[]): ContributionBatch {
  const principal = transactions.reduce((sum, transaction) => sum.plus(buyPrincipal(transaction)), decimal(0))
  const weightedDays = transactions.reduce((sum, transaction) => sum.plus(decimal(buyPrincipal(transaction)).mul(marketDays(transaction.tradeDate))), decimal(0))
  const averageMarketDays = principal.gt(0) ? weightedDays.div(principal).toDecimalPlaces(0).toNumber() : 0
  return { type, period, principal: principal.toString(), value: principal.toString(), pnl: '0', returnRate: '0', averageMarketDays, dataStatus: 'STALE' }
}

function demoContributionAnalysis(): ApiResult<ContributionAnalysis> {
  const transactions = getFixtureTransactions().data
  const cyclePeriods = new Map(getFixtureCycles('core-plan').data.map((cycle) => [cycle.id, cycle.period]))
  const initialTransactions = transactions.filter((transaction) => transaction.transactionType === 'BUY' && demoInitialTransactions.has(transaction.id))
  const dcaGroups = new Map<string, Transaction[]>()
  for (const transaction of transactions) {
    if (transaction.transactionType !== 'BUY' || !transaction.planCycleId) continue
    const period = cyclePeriods.get(transaction.planCycleId)
    if (!period) continue
    const rows = dcaGroups.get(period) ?? []
    rows.push(transaction)
    dcaGroups.set(period, rows)
  }
  const unclassified = transactions.filter((transaction) => transaction.transactionType === 'BUY' && !transaction.planCycleId && !demoInitialTransactions.has(transaction.id) && !demoUnplannedTransactions.has(transaction.id))
  const initialBatch = batchFromTransactions('INITIAL', null, initialTransactions)
  const dcaBatches = [...dcaGroups.entries()].map(([period, rows]) => batchFromTransactions('DCA', period, rows)).sort((left, right) => (right.period ?? '').localeCompare(left.period ?? ''))
  const dcaPrincipal = dcaBatches.reduce((sum, batch) => sum.plus(batch.principal), decimal(0))
  const dcaWeightedDays = dcaBatches.reduce((sum, batch) => sum.plus(decimal(batch.principal).mul(batch.averageMarketDays)), decimal(0))
  const dcaAverageDays = dcaPrincipal.gt(0) ? dcaWeightedDays.div(dcaPrincipal).toDecimalPlaces(0).toNumber() : 0
  const unclassifiedAmount = unclassified.reduce((sum, transaction) => sum.plus(buyPrincipal(transaction)), decimal(0))
  const batches = [...(decimal(initialBatch.principal).gt(0) ? [initialBatch] : []), ...dcaBatches]
  return {
    data: {
      totalInvested: decimal(initialBatch.principal).plus(dcaPrincipal).toString(),
      initial: { principal: initialBatch.principal, value: initialBatch.value, pnl: initialBatch.pnl, returnRate: initialBatch.returnRate, averageMarketDays: initialBatch.averageMarketDays, batchCount: decimal(initialBatch.principal).gt(0) ? 1 : 0, dataStatus: 'STALE' },
      dca: { principal: dcaPrincipal.toString(), value: dcaPrincipal.toString(), pnl: '0', returnRate: dcaPrincipal.gt(0) ? '0' : null, averageMarketDays: dcaAverageDays, batchCount: dcaBatches.length, dataStatus: 'STALE' },
      unclassifiedAmount: unclassifiedAmount.toString(),
      unclassifiedBuys: unclassified.map((transaction) => ({ transactionId: transaction.id, tradeDate: transaction.tradeDate, symbol: transaction.instrumentSymbol, principal: buyPrincipal(transaction), eligibleForInitial: transaction.tradeDate === getFixturePlan('core-plan').data.startDate })),
      unclassifiedScope: 'ACCOUNT',
      batches,
      dataStatus: 'STALE',
      asOf: DEMO_TODAY,
    },
    meta: fixtureMeta,
  }
}

function demoClassificationPreview(items: ContributionClassificationItem[]): ApiResult<ContributionClassificationPreview> {
  const transactions = new Map(getFixtureTransactions().data.map((transaction) => [transaction.id, transaction]))
  const plan = getFixturePlan('core-plan').data
  const previewItems = items.map((item) => {
    const transaction = transactions.get(item.transactionId)
    const errors = !transaction
      ? [{ code: 'TRANSACTION_NOT_FOUND', message: 'Transaction not found' }]
      : transaction.transactionType !== 'BUY' || transaction.planCycleId || demoInitialTransactions.has(transaction.id) || demoUnplannedTransactions.has(transaction.id)
        ? [{ code: 'CONTRIBUTION_ALREADY_CLASSIFIED', message: 'Transaction cannot be classified' }]
        : item.classification === 'INITIAL' && transaction.tradeDate !== plan.startDate
          ? [{ code: 'INITIAL_CONTRIBUTION_START_DATE_ONLY', message: 'Initial capital is only allowed on the investment plan start date' }]
          : []
    return {
      ...item,
      tradeDate: transaction?.tradeDate ?? null,
      symbol: transaction?.instrumentSymbol ?? null,
      principal: transaction ? buyPrincipal(transaction) : null,
      valid: errors.length === 0,
      errors,
    }
  })
  const valid = previewItems.every((item) => item.valid)
  return { data: { previewHash: valid ? JSON.stringify(items) : null, valid, items: previewItems }, meta: fixtureMeta }
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
  createPlan: async (input: Partial<InvestmentPlan>): Promise<ApiResult<InvestmentPlan>> => createFixturePlan(input),
  updatePlan: async (id: string, input: Partial<InvestmentPlan>): Promise<ApiResult<InvestmentPlan>> => updateFixturePlan(id, input),
  getCycles: async (planId: string): Promise<ApiResult<PlanCycle[]>> => getFixtureCycles(planId),
  getRecommendation: async (planId: string, amount?: string): Promise<ApiResult<Recommendation>> => {
    const result = getFixtureRecommendation(planId, amount)
    return { ...result, data: normalizeRecommendation(result.data) }
  },
  getContributionAnalysis: async (_planId: string): Promise<ApiResult<ContributionAnalysis>> => demoContributionAnalysis(),
  previewContributionClassification: async (items: ContributionClassificationItem[]): Promise<ApiResult<ContributionClassificationPreview>> => demoClassificationPreview(items),
  commitContributionClassification: async (preview: ContributionClassificationPreview): Promise<ApiResult<ContributionClassificationCommit>> => {
    const transactions = new Map(getFixtureTransactions().data.map((transaction) => [transaction.id, transaction]))
    for (const item of preview.items) {
      const transaction = transactions.get(item.transactionId)
      if (!transaction || !item.valid) continue
      if (item.classification === 'INITIAL') demoInitialTransactions.add(item.transactionId)
      else demoUnplannedTransactions.add(item.transactionId)
    }
    return { data: { batchId: `classification-${Date.now()}`, transactionIds: preview.items.filter((item) => item.valid).map((item) => item.transactionId), analysis: demoContributionAnalysis().data }, meta: fixtureMeta }
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
  updateSettings: async (settings: AppSettings): Promise<ApiResult<AppSettings>> => updateFixtureSettings(settings),
}
