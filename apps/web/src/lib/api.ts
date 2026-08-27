import type {
  ApiResult,
  AppSettings,
  ContributionProgress,
  DashboardData,
  DataMeta,
  DataStatus,
  EtfMetrics,
  Instrument,
  InstrumentSyncResult,
  InvestmentPlan,
  PlanCycle,
  PricePoint,
  Quote,
  Recommendation,
  Session,
  Transaction,
  TransactionImportCommit,
  TransactionImportPreview,
  TransactionInput,
  TransactionCsvRow,
  NextDca,
  RecommendationItem,
} from '../types'
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
  parseTransactionCsv,
  searchFixtureInstruments,
  trackFixtureInstrument,
  untrackFixtureInstrument,
  updateFixturePlan,
  updateFixtureTransaction,
  updateFixtureSettings,
} from './fixtures'

const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '/api/v1').replace(/\/+$/, '')
const FORCE_FIXTURES = import.meta.env.VITE_FORCE_FIXTURES === 'true'
const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

let csrfToken: string | null = null
let csrfHeaderName = 'X-CSRF-TOKEN'
let csrfRequest: Promise<string | null> | null = null

export class ApiError extends Error {
  status: number
  code?: string

  constructor(message: string, status = 0, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function dataStatus(value: unknown): DataStatus | undefined {
  return value === 'FRESH' || value === 'STALE' || value === 'PARTIAL' || value === 'UNAVAILABLE' || value === 'INSUFFICIENT_HISTORY' ? value : undefined
}

function readMeta(body: unknown, fallback: DataMeta): DataMeta {
  if (!isRecord(body)) return fallback
  const nested = isRecord(body.meta) ? body.meta : {}
  const summary = isRecord(body.summary) ? body.summary : {}
  const status = dataStatus(nested.dataStatus) ?? dataStatus(nested.status) ?? dataStatus(body.dataStatus)
    ?? dataStatus(body.status) ?? dataStatus(summary.dataStatus) ?? dataStatus(summary.status)
  const bodyAsOf = typeof body.asOf === 'string' ? body.asOf : typeof summary.asOf === 'string' ? summary.asOf : undefined
  return {
    ...fallback,
    ...(status ? { status } : {}),
    ...(typeof nested.source === 'string' || typeof body.source === 'string' ? { source: typeof nested.source === 'string' ? nested.source : body.source as string } : {}),
    ...(typeof nested.asOf === 'string' || bodyAsOf ? { asOf: typeof nested.asOf === 'string' ? nested.asOf : bodyAsOf } : {}),
    ...(typeof nested.retrievedAt === 'string' || typeof body.retrievedAt === 'string' ? { retrievedAt: typeof nested.retrievedAt === 'string' ? nested.retrievedAt : body.retrievedAt as string } : {}),
    ...(typeof nested.message === 'string' || typeof body.message === 'string' ? { message: typeof nested.message === 'string' ? nested.message : body.message as string } : {}),
  }
}

export function normalizeApiResponse<T>(body: unknown, fallbackMeta: DataMeta): ApiResult<T> {
  const meta = readMeta(body, fallbackMeta)
  if (isRecord(body) && Object.prototype.hasOwnProperty.call(body, 'data')) {
    return { data: body.data as T, meta }
  }
  const pageKeys = new Set(['items', 'page', 'size', 'totalItems', 'totalPages'])
  if (isRecord(body) && Object.prototype.hasOwnProperty.call(body, 'items')
      && Object.keys(body).every((key) => pageKeys.has(key))) {
    return { data: body.items as T, meta }
  }
  return { data: body as T, meta }
}

function stringValue(value: unknown, fallback = '0'): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  return fallback
}

function nullableString(value: unknown): string | null {
  return typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value)) ? String(value) : null
}

function normalizeRecommendationItem(value: unknown): RecommendationItem | null {
  if (!isRecord(value) || typeof value.symbol !== 'string') return null
  return {
    symbol: value.symbol,
    currentWeight: stringValue(value.currentWeight),
    targetWeight: stringValue(value.targetWeight),
    gap: stringValue(value.gap),
    suggestedAmount: stringValue(value.suggestedAmount),
    currentValue: nullableString(value.currentValue),
    positiveGap: nullableString(value.positiveGap),
    valueGap: nullableString(value.valueGap),
  }
}

function normalizeRecommendationItems(value: unknown): RecommendationItem[] {
  return Array.isArray(value)
    ? value.map(normalizeRecommendationItem).filter((item): item is RecommendationItem => item !== null)
    : []
}

export function normalizeDashboardData(value: unknown): DashboardData {
  const body = isRecord(value) ? value : {}
  const summary = isRecord(body.summary) ? body.summary : {}
  const rawHoldings = Array.isArray(body.holdings) ? body.holdings : []
  const rawAllocation = Array.isArray(body.allocation) ? body.allocation : []
  const rawHistory = Array.isArray(body.portfolioHistory) ? body.portfolioHistory : []
  const rawNextDca = isRecord(body.nextDca) ? body.nextDca : null
  const rawProgress = isRecord(body.contributionProgress) ? body.contributionProgress : null
  const rawMonths = rawProgress && Array.isArray(rawProgress.months)
    ? rawProgress.months
    : rawProgress && Array.isArray(rawProgress.cycles) ? rawProgress.cycles : []
  return {
    summary: {
      marketValue: stringValue(summary.marketValue),
      costBasis: stringValue(summary.costBasis),
      netInvested: stringValue(summary.netInvested),
      unrealizedPnl: nullableString(summary.unrealizedPnl),
      realizedPnl: stringValue(summary.realizedPnl),
      dividendIncome: stringValue(summary.dividendIncome),
      totalPnl: nullableString(summary.totalPnl),
      xirr: nullableString(summary.xirr),
    },
    nextDca: rawNextDca ? {
      period: typeof rawNextDca.period === 'string' ? rawNextDca.period : '',
      amount: stringValue(rawNextDca.amount),
      daysRemaining: typeof rawNextDca.daysRemaining === 'number' ? rawNextDca.daysRemaining : 0,
      items: normalizeRecommendationItems(rawNextDca.items),
      dataStatus: dataStatus(rawNextDca.dataStatus) ?? dataStatus(rawNextDca.status),
      message: typeof rawNextDca.message === 'string' ? rawNextDca.message : undefined,
    } satisfies NextDca : null,
    portfolioHistory: rawHistory.filter(isRecord).map((point) => ({
      date: typeof point.date === 'string' ? point.date : '',
      marketValue: stringValue(point.marketValue),
      netInvested: stringValue(point.netInvested),
      dataStatus: dataStatus(point.dataStatus) ?? dataStatus(point.status),
    })),
    holdings: rawHoldings.filter(isRecord).map((holding) => ({
      symbol: typeof holding.symbol === 'string' ? holding.symbol : '',
      name: typeof holding.name === 'string' ? holding.name : '',
      shares: stringValue(holding.shares),
      avgCost: stringValue(holding.avgCost),
      price: nullableString(holding.price),
      todayPercent: nullableString(holding.todayPercent ?? holding.today),
      marketValue: nullableString(holding.marketValue),
      costBasis: stringValue(holding.costBasis),
      unrealizedPnl: nullableString(holding.unrealizedPnl),
      returnPercent: nullableString(holding.returnPercent ?? holding.returnRate),
      allocation: nullableString(holding.allocation),
      dataStatus: dataStatus(holding.dataStatus) ?? dataStatus(holding.status),
    })),
    allocation: rawAllocation.filter(isRecord).map((row) => ({
      symbol: typeof row.symbol === 'string' ? row.symbol : '',
      targetWeight: nullableString(row.targetWeight),
      actualWeight: nullableString(row.actualWeight),
      drift: nullableString(row.drift),
      marketValue: nullableString(row.marketValue),
    })),
    contributionProgress: rawProgress ? {
      year: typeof rawProgress.year === 'number' ? rawProgress.year : new Date().getFullYear(),
      executed: stringValue(rawProgress.executed),
      planned: stringValue(rawProgress.planned),
      remaining: stringValue(rawProgress.remaining),
      executionRate: stringValue(rawProgress.executionRate),
      months: rawMonths.filter(isRecord).map((month) => ({
        period: typeof month.period === 'string' ? month.period : '',
        planned: stringValue(month.planned ?? month.plannedAmount),
        executed: stringValue(month.executed ?? month.executedAmount),
        status: typeof month.status === 'string' ? month.status as ContributionProgress['months'][number]['status'] : 'NONE',
      })),
    } : null,
  }
}

function normalizeRecommendation(value: unknown): Recommendation {
  const body = isRecord(value) ? value : {}
  return {
    amount: stringValue(body.amount),
    items: normalizeRecommendationItems(body.items),
    method: typeof body.method === 'string' ? body.method : 'CONTRIBUTION_FIRST',
    dataStatus: dataStatus(body.dataStatus) ?? dataStatus(body.status) ?? 'STALE',
    message: typeof body.message === 'string' ? body.message : undefined,
  }
}

function normalizeMetrics(value: unknown): EtfMetrics {
  const body = isRecord(value) ? value : {}
  return {
    oneDay: nullableString(body.oneDay),
    oneMonth: nullableString(body.oneMonth),
    threeMonths: nullableString(body.threeMonths),
    ytd: nullableString(body.ytd),
    oneYear: nullableString(body.oneYear),
    threeYearCagr: nullableString(body.threeYearCagr),
    fiftyTwoWeekHigh: nullableString(body.fiftyTwoWeekHigh),
    fiftyTwoWeekLow: nullableString(body.fiftyTwoWeekLow),
    currentDrawdown: nullableString(body.currentDrawdown),
    maxDrawdown1Y: nullableString(body.maxDrawdown1Y),
    dataStatus: dataStatus(body.dataStatus) ?? dataStatus(body.status),
    asOf: typeof body.asOf === 'string' ? body.asOf : undefined,
  }
}

function normalizePricePoints(value: unknown): PricePoint[] {
  if (!Array.isArray(value)) return []
  return value.filter(isRecord).map((point) => {
    const high = nullableString(point.high)
    const low = nullableString(point.low)
    return {
      date: typeof point.date === 'string' ? point.date : '',
      open: nullableString(point.open) ?? undefined,
      ...(high === null ? {} : { high }),
      ...(low === null ? {} : { low }),
      close: stringValue(point.close),
      adjustedClose: nullableString(point.adjustedClose),
      volume: nullableString(point.volume) ?? undefined,
    }
  })
}

function normalizeSettings(value: unknown): AppSettings {
  const body = isRecord(value) ? value : {}
  const primary = body.primaryProvider ?? body.marketDataPrimary
  const fallback = body.fallbackProvider ?? body.marketDataFallback
  return {
    baseCurrency: 'USD',
    primaryProvider: primary === 'TWELVE_DATA' || primary === 'ALPHA_VANTAGE' ? primary : 'YAHOO',
    fallbackProvider: fallback === 'YAHOO' || fallback === 'TWELVE_DATA' || fallback === 'ALPHA_VANTAGE' || fallback === 'NONE' ? fallback : 'TWELVE_DATA',
    twelveDataConfigured: body.twelveDataConfigured === true,
    alphaVantageConfigured: body.alphaVantageConfigured === true,
    theme: body.theme === 'LIGHT' || body.theme === 'DARK' ? body.theme : 'SYSTEM',
    timezone: typeof body.timezone === 'string' ? body.timezone : 'America/New_York',
  }
}

function errorFromResponse(body: unknown, status: number): ApiError {
  if (isRecord(body)) {
    const detail = typeof body.detail === 'string' ? body.detail : typeof body.message === 'string' ? body.message : undefined
    const code = typeof body.code === 'string' ? body.code : undefined
    if (detail) return new ApiError(detail, status, code)
  }
  return new ApiError(`Request failed with ${status}`, status)
}

async function request<T>(path: string, init: RequestInit = {}, retryCsrf = true): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')

  if (MUTATING_METHODS.has(method) && !headers.has(csrfHeaderName)) {
    const token = await getCsrfToken()
    if (token) headers.set(csrfHeaderName, token)
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...init, method, credentials: 'include', headers })
  } catch {
    throw new ApiError('Network unavailable')
  }

  const text = await response.text()
  let body: unknown = null
  if (text) {
    try {
      body = JSON.parse(text) as unknown
    } catch {
      body = text
    }
  }

  if (response.status === 403 && MUTATING_METHODS.has(method) && retryCsrf) {
    csrfToken = null
    csrfHeaderName = 'X-CSRF-TOKEN'
    return request<T>(path, init, false)
  }
  if (!response.ok) throw errorFromResponse(body, response.status)
  return body as T
}

async function getCsrfToken(): Promise<string | null> {
  if (csrfToken) return csrfToken
  if (csrfRequest) return csrfRequest
  csrfRequest = (async () => {
    const body = await request<unknown>('/auth/csrf')
    const value = isRecord(body) && 'data' in body ? body.data : body
    const payload = isRecord(value) ? value : isRecord(body) ? body : undefined
    const token = typeof value === 'string' ? value : payload && typeof payload.token === 'string' ? payload.token : payload && typeof payload.csrfToken === 'string' ? payload.csrfToken : null
    const headerName = payload && typeof payload.headerName === 'string' ? payload.headerName
      : isRecord(body) && typeof body.headerName === 'string' ? body.headerName : null
    if (headerName) csrfHeaderName = headerName
    csrfToken = token
    return token
  })()
  try {
    return await csrfRequest
  } finally {
    csrfRequest = null
  }
}

function fallbackMeta(error: unknown, local: DataMeta): DataMeta {
  return {
    ...local,
    status: 'STALE',
    source: 'FIXTURE',
    message: error instanceof Error ? `${error.message}. Showing local demo data.` : local.message,
  }
}

async function read<T>(path: string, fallback: () => ApiResult<T>): Promise<ApiResult<T>> {
  if (FORCE_FIXTURES) return fallback()
  try {
    return normalizeApiResponse(await request<unknown>(path), { status: 'FRESH', source: 'API', retrievedAt: new Date().toISOString() })
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 0) throw error
    const local = fallback()
    return { ...local, meta: fallbackMeta(error, local.meta) }
  }
}

async function mutate<T>(path: string, body: unknown, fallback: () => ApiResult<T>, method = 'POST'): Promise<ApiResult<T>> {
  if (FORCE_FIXTURES) return fallback()
  return normalizeApiResponse(await request<unknown>(path, { method, body: body === undefined ? undefined : JSON.stringify(body) }), { status: 'FRESH', source: 'API', retrievedAt: new Date().toISOString() })
}

function localImportPreview(csv: string): ApiResult<TransactionImportPreview> {
  const parsed = parseTransactionCsv(csv)
  return {
    data: { batchId: `local-import-${Date.now()}`, rows: parsed.rows, sourceRows: parsed.rows.map(transactionToCsvRow), errors: parsed.errors },
    meta: { status: 'STALE', source: 'FIXTURE', message: 'API unavailable. CSV validated locally.' },
  }
}

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

function localImportCommit(preview: TransactionImportPreview): ApiResult<TransactionImportCommit> {
  const result = importFixtureTransactions(preview.rows)
  return {
    data: { batchId: preview.batchId, importedRows: result.data.length, transactionIds: result.data.map((transaction) => transaction.id) },
    meta: result.meta,
  }
}

export const api = {
  getSession: (): Promise<ApiResult<Session>> => read('/auth/session', getFixtureSession),
  login: (username: string, password: string): Promise<ApiResult<Session>> => mutate('/auth/login', { username, password }, getFixtureSession),
  logout: async (): Promise<ApiResult<{ ok: boolean }>> => {
    const result = await mutate('/auth/logout', undefined, () => ({ data: { ok: true }, meta: getFixtureSession().meta }))
    csrfToken = null
    csrfHeaderName = 'X-CSRF-TOKEN'
    csrfRequest = null
    return result
  },
  getDashboard: async (): Promise<ApiResult<DashboardData>> => {
    const result = await read('/dashboard', getFixtureDashboard)
    return { ...result, data: normalizeDashboardData(result.data) }
  },
  getInstruments: (): Promise<ApiResult<Instrument[]>> => read('/instruments', getFixtureInstruments),
  searchInstruments: (query: string): Promise<ApiResult<Instrument[]>> => read(`/instruments/search?q=${encodeURIComponent(query)}`, () => searchFixtureInstruments(query)),
  getInstrument: (symbol: string): Promise<ApiResult<Instrument>> => read(`/instruments/${encodeURIComponent(symbol.toUpperCase())}`, () => getFixtureInstrument(symbol)),
  trackInstrument: (symbol: string): Promise<ApiResult<Instrument>> => mutate('/instruments', { symbol: symbol.toUpperCase() }, () => trackFixtureInstrument(symbol)),
  untrackInstrument: (symbol: string): Promise<ApiResult<Instrument>> => mutate(`/instruments/${encodeURIComponent(symbol.toUpperCase())}`, undefined, () => untrackFixtureInstrument(symbol), 'DELETE'),
  syncInstrument: async (symbol: string): Promise<ApiResult<InstrumentSyncResult>> => {
    const result = await mutate(`/instruments/${encodeURIComponent(symbol.toUpperCase())}/sync`, undefined,
      () => ({ data: { symbol: symbol.toUpperCase(), barsSaved: 0, splitsSaved: 0, status: 'STALE' as const, completedAt: new Date().toISOString(), message: 'API unavailable. Retry when the API is reachable.' }, meta: { status: 'STALE', source: 'FIXTURE' } }))
    const value: Record<string, unknown> = isRecord(result.data) ? result.data : {}
    return {
      ...result,
      data: {
        symbol: typeof value.symbol === 'string' ? value.symbol : symbol.toUpperCase(),
        barsSaved: typeof value.barsSaved === 'number' ? value.barsSaved : 0,
        splitsSaved: typeof value.splitsSaved === 'number' ? value.splitsSaved : 0,
        status: dataStatus(value.status) ?? dataStatus(value.dataStatus) ?? result.meta.status,
        completedAt: typeof value.completedAt === 'string' ? value.completedAt : new Date().toISOString(),
        message: typeof value.message === 'string' ? value.message : null,
      },
    }
  },
  getQuote: (symbol: string): Promise<ApiResult<Quote>> => read(`/instruments/${encodeURIComponent(symbol.toUpperCase())}/quote`, () => getFixtureQuote(symbol)),
  getMetrics: async (symbol: string): Promise<ApiResult<EtfMetrics>> => {
    const result = await read(`/instruments/${encodeURIComponent(symbol.toUpperCase())}/metrics`, () => getFixtureMetrics(symbol))
    return { ...result, data: normalizeMetrics(result.data) }
  },
  getPrices: async (symbol: string, range: string): Promise<ApiResult<PricePoint[]>> => {
    const result = await read(`/instruments/${encodeURIComponent(symbol.toUpperCase())}/prices?range=${encodeURIComponent(range)}`, () => getFixturePrices(symbol, range))
    return { ...result, data: normalizePricePoints(result.data) }
  },
  getPlans: (): Promise<ApiResult<InvestmentPlan[]>> => read('/plans', getFixturePlans),
  getPlan: (id: string): Promise<ApiResult<InvestmentPlan>> => read(`/plans/${encodeURIComponent(id)}`, () => getFixturePlan(id)),
  createPlan: (plan: Omit<InvestmentPlan, 'id' | 'cycles'>): Promise<ApiResult<InvestmentPlan>> => mutate('/plans', plan, () => createFixturePlan(plan)),
  updatePlan: (id: string, patch: Partial<InvestmentPlan>): Promise<ApiResult<InvestmentPlan>> => mutate(`/plans/${encodeURIComponent(id)}`, patch, () => updateFixturePlan(id, patch), 'PUT'),
  getCycles: (id: string): Promise<ApiResult<PlanCycle[]>> => read(`/plans/${encodeURIComponent(id)}/cycles`, () => getFixtureCycles(id)),
  getRecommendation: async (id: string): Promise<ApiResult<Recommendation>> => {
    const result = await read(`/plans/${encodeURIComponent(id)}/recommendation`, () => getFixtureRecommendation(id))
    return { ...result, data: normalizeRecommendation(result.data) }
  },
  getTransactions: (): Promise<ApiResult<Transaction[]>> => read('/transactions', getFixtureTransactions),
  createTransaction: (input: TransactionInput): Promise<ApiResult<Transaction>> => mutate('/transactions', input, () => createFixtureTransaction(input)),
  updateTransaction: (id: string, input: TransactionInput): Promise<ApiResult<Transaction>> => mutate(`/transactions/${encodeURIComponent(id)}`, input, () => updateFixtureTransaction(id, input), 'PUT'),
  previewTransactionImport: async (csv: string): Promise<ApiResult<TransactionImportPreview>> => {
    if (FORCE_FIXTURES) return localImportPreview(csv)
    const form = new FormData()
    form.append('file', new Blob([csv], { type: 'text/csv' }), 'transactions.csv')
    const response = normalizeApiResponse<ServerCsvPreview>(await request<unknown>('/transactions/import/preview', { method: 'POST', body: form }), { status: 'FRESH', source: 'API', retrievedAt: new Date().toISOString() })
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
  commitTransactionImport: (preview: TransactionImportPreview): Promise<ApiResult<TransactionImportCommit>> => mutate('/transactions/import/commit', {
    batchId: preview.batchId,
    rows: preview.sourceRows ?? preview.rows.map(transactionToCsvRow),
  }, () => localImportCommit(preview)),
  deleteTransaction: (id: string): Promise<ApiResult<{ id: string }>> => mutate(`/transactions/${encodeURIComponent(id)}`, undefined, () => deleteFixtureTransaction(id), 'DELETE'),
  getSettings: async (): Promise<ApiResult<AppSettings>> => {
    const result = await read('/settings', getFixtureSettings)
    return { ...result, data: normalizeSettings(result.data) }
  },
  updateSettings: async (patch: Partial<AppSettings>): Promise<ApiResult<AppSettings>> => {
    const result = await mutate('/settings', patch, () => updateFixtureSettings(patch), 'PUT')
    return { ...result, data: normalizeSettings(result.data) }
  },
}
