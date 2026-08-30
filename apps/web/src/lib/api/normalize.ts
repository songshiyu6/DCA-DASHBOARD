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
  PlanAsset,
  PlanCycle,
  PlanCycleAsset,
  PricePoint,
  Quote,
  Recommendation,
  RecommendationItem,
  Session,
  Transaction,
  TransactionImportPreview,
  TransactionInput,
} from '../../types'

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

export function dataStatus(value: unknown): DataStatus | undefined {
  return value === 'FRESH' || value === 'STALE' || value === 'PARTIAL' || value === 'UNAVAILABLE' || value === 'INSUFFICIENT_HISTORY' ? value : undefined
}

function stringValue(value: unknown, fallback = '0'): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  return fallback
}

function nullableString(value: unknown): string | null {
  return typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value)) ? String(value) : null
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

export function normalizeResult<T>(body: unknown, normalizer: (value: unknown) => T, fallbackMeta: DataMeta): ApiResult<T> {
  const result = normalizeApiResponse<unknown>(body, fallbackMeta)
  return { ...result, data: normalizer(result.data) }
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

export function normalizeSession(value: unknown): Session {
  const body = isRecord(value) ? value : {}
  return {
    authenticated: body.authenticated === true,
    ...(typeof body.username === 'string' ? { username: body.username } : {}),
  }
}

export function normalizeInstrument(value: unknown): Instrument {
  const body = isRecord(value) ? value : {}
  const status = dataStatus(body.dataStatus) ?? dataStatus(body.status)
  return {
    id: stringValue(body.id, ''),
    symbol: stringValue(body.symbol, ''),
    name: stringValue(body.name, ''),
    exchange: stringValue(body.exchange, ''),
    currency: stringValue(body.currency, 'USD'),
    instrumentType: 'ETF',
    issuer: stringValue(body.issuer, ''),
    expenseRatio: nullableString(body.expenseRatio),
    aum: nullableString(body.aum),
    dividendYield: nullableString(body.dividendYield),
    nav: nullableString(body.nav),
    tracked: body.tracked === true,
    ...(status ? { dataStatus: status } : {}),
  }
}

export function normalizeInstruments(value: unknown): Instrument[] {
  return Array.isArray(value) ? value.filter(isRecord).map(normalizeInstrument) : []
}

export function normalizeQuote(value: unknown): Quote {
  const body = isRecord(value) ? value : {}
  const status = dataStatus(body.status) ?? dataStatus(body.dataStatus)
  const quote: Quote = {
    symbol: stringValue(body.symbol, ''),
    price: stringValue(body.price),
    previousClose: stringValue(body.previousClose),
    change: stringValue(body.change),
    changePercent: stringValue(body.changePercent),
    marketTimestamp: stringValue(body.marketTimestamp, ''),
    retrievedAt: stringValue(body.retrievedAt, ''),
    source: stringValue(body.source, 'API'),
    ...(status ? { status } : {}),
  }
  if ('bid' in body) quote.bid = nullableString(body.bid)
  if ('ask' in body) quote.ask = nullableString(body.ask)
  if ('nav' in body) quote.nav = nullableString(body.nav)
  if ('navDate' in body) quote.navDate = typeof body.navDate === 'string' ? body.navDate : null
  return quote
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
    } : null,
    portfolioHistory: rawHistory.filter(isRecord).map((point) => ({
      date: typeof point.date === 'string' ? point.date : '',
      marketValue: nullableString(point.marketValue),
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

export function normalizeRecommendation(value: unknown): Recommendation {
  const body = isRecord(value) ? value : {}
  return {
    amount: stringValue(body.amount),
    items: normalizeRecommendationItems(body.items),
    method: typeof body.method === 'string' ? body.method : 'CONTRIBUTION_FIRST',
    dataStatus: dataStatus(body.dataStatus) ?? dataStatus(body.status) ?? 'STALE',
    message: typeof body.message === 'string' ? body.message : undefined,
  }
}

export function normalizeMetrics(value: unknown): EtfMetrics {
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

export function normalizePricePoints(value: unknown): PricePoint[] {
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

function normalizePlanAsset(value: unknown): PlanAsset {
  const body = isRecord(value) ? value : {}
  return {
    ...(typeof body.id === 'string' ? { id: body.id } : {}),
    symbol: stringValue(body.symbol, ''),
    targetWeight: stringValue(body.targetWeight),
  }
}

function cycleStatus(value: unknown): PlanCycle['status'] {
  return value === 'UPCOMING' || value === 'OPEN' || value === 'PARTIAL' || value === 'COMPLETED' || value === 'SKIPPED' ? value : 'UPCOMING'
}

function normalizePlanCycleAsset(value: unknown): PlanCycleAsset {
  const body = isRecord(value) ? value : {}
  return {
    symbol: stringValue(body.symbol, ''),
    targetWeight: stringValue(body.targetWeight),
    plannedAmount: stringValue(body.plannedAmount),
    executedAmount: stringValue(body.executedAmount),
  }
}

export function normalizePlanCycle(value: unknown): PlanCycle {
  const body = isRecord(value) ? value : {}
  return {
    id: stringValue(body.id, ''),
    period: stringValue(body.period, ''),
    plannedAmount: stringValue(body.plannedAmount),
    executedAmount: stringValue(body.executedAmount),
    status: cycleStatus(body.status),
    openedAt: typeof body.openedAt === 'string' ? body.openedAt : null,
    completedAt: typeof body.completedAt === 'string' ? body.completedAt : null,
    assets: Array.isArray(body.assets) ? body.assets.filter(isRecord).map(normalizePlanCycleAsset) : [],
  }
}

export function normalizeCycles(value: unknown): PlanCycle[] {
  return Array.isArray(value) ? value.filter(isRecord).map(normalizePlanCycle) : []
}

export function normalizePlan(value: unknown): InvestmentPlan {
  const body = isRecord(value) ? value : {}
  return {
    id: stringValue(body.id, ''),
    name: stringValue(body.name, ''),
    currency: stringValue(body.currency, 'USD'),
    frequency: body.frequency === 'WEEKLY' || body.frequency === 'BIWEEKLY' ? body.frequency : 'MONTHLY',
    monthlyBudget: stringValue(body.monthlyBudget),
    startDate: stringValue(body.startDate, ''),
    executionStartDay: typeof body.executionStartDay === 'number' ? body.executionStartDay : 1,
    executionEndDay: typeof body.executionEndDay === 'number' ? body.executionEndDay : 31,
    status: body.status === 'PAUSED' || body.status === 'ARCHIVED' ? body.status : 'ACTIVE',
    assets: Array.isArray(body.assets) ? body.assets.filter(isRecord).map(normalizePlanAsset) : [],
    ...(Array.isArray(body.cycles) ? { cycles: normalizeCycles(body.cycles) } : {}),
  }
}

export function normalizePlans(value: unknown): InvestmentPlan[] {
  return Array.isArray(value) ? value.filter(isRecord).map(normalizePlan) : []
}

function transactionType(value: unknown): Transaction['transactionType'] {
  return value === 'SELL' || value === 'DIVIDEND' || value === 'FEE' ? value : 'BUY'
}

function contributionType(value: unknown): Transaction['contributionType'] {
  return value === 'INITIAL' || value === 'DCA' || value === 'UNPLANNED' ? value : null
}

export function normalizeTransaction(value: unknown): Transaction {
  const body = isRecord(value) ? value : {}
  return {
    id: stringValue(body.id, ''),
    instrumentSymbol: stringValue(body.instrumentSymbol ?? body.symbol, '').toUpperCase(),
    planCycleId: typeof body.planCycleId === 'string' ? body.planCycleId : null,
    contributionType: contributionType(body.contributionType),
    contributionPlanId: typeof body.contributionPlanId === 'string' ? body.contributionPlanId : null,
    transactionType: transactionType(body.transactionType ?? body.type),
    tradeDate: stringValue(body.tradeDate ?? body.date, ''),
    quantity: nullableString(body.quantity),
    unitPrice: nullableString(body.unitPrice ?? body.price),
    amount: nullableString(body.amount),
    fee: stringValue(body.fee),
    currency: 'USD',
    total: nullableString(body.total),
    notes: typeof body.notes === 'string' ? body.notes : null,
    ledgerOrder: typeof body.ledgerOrder === 'number' ? body.ledgerOrder : null,
  }
}

export function normalizeTransactions(value: unknown): Transaction[] {
  return Array.isArray(value) ? value.filter(isRecord).map(normalizeTransaction) : []
}

export function normalizeSettings(value: unknown): AppSettings {
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

export function normalizeSyncResult(value: unknown): InstrumentSyncResult {
  const body = isRecord(value) ? value : {}
  return {
    symbol: stringValue(body.symbol, ''),
    barsSaved: typeof body.barsSaved === 'number' ? body.barsSaved : 0,
    splitsSaved: typeof body.splitsSaved === 'number' ? body.splitsSaved : 0,
    status: dataStatus(body.status) ?? dataStatus(body.dataStatus) ?? 'STALE',
    completedAt: stringValue(body.completedAt, ''),
    message: typeof body.message === 'string' ? body.message : null,
  }
}

export function normalizeTransactionImportPreview(value: unknown): TransactionImportPreview {
  const body = isRecord(value) ? value : {}
  const rows = Array.isArray(body.rows) ? body.rows.filter(isRecord) : []
  return {
    batchId: stringValue(body.batchId, ''),
    rows: rows.map((item) => {
      const transaction = normalizeTransaction(item.row ?? item)
      return {
        instrumentSymbol: transaction.instrumentSymbol,
        planCycleId: transaction.planCycleId,
        transactionType: transaction.transactionType,
        tradeDate: transaction.tradeDate,
        ...(transaction.quantity === null ? {} : { quantity: transaction.quantity }),
        ...(transaction.unitPrice === null ? {} : { unitPrice: transaction.unitPrice }),
        ...(transaction.amount === null ? {} : { amount: transaction.amount }),
        fee: transaction.fee,
        currency: 'USD' as const,
        ...(transaction.notes === null ? {} : { notes: transaction.notes }),
      } satisfies TransactionInput
    }),
    errors: rows.flatMap((item) => Array.isArray(item.errors) ? item.errors.filter((error): error is string => typeof error === 'string') : []),
  }
}
