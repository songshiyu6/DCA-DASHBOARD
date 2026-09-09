import type { QueryClient, QueryKey } from '@tanstack/react-query'

export const queryKeys = {
  session: ['session'] as const,
  dashboard: ['dashboard'] as const,
  portfolioPerformance: ['portfolio-performance'] as const,
  multiCurrencyReports: ['multi-currency-report'] as const,
  multiCurrencyReport: (range: '1M' | '3M' | '1Y' | 'YTD' | 'ALL' = 'ALL') => ['multi-currency-report', range] as const,
  instruments: ['instruments'] as const,
  instrument: (symbol: string) => ['instrument', symbol] as const,
  instrumentSearch: (query: string) => ['instrument-search', query] as const,
  quote: (symbol: string) => ['quote', symbol] as const,
  metrics: (symbol: string) => ['metrics', symbol] as const,
  prices: (symbol: string) => ['prices', symbol] as const,
  pricesRange: (symbol: string, range: string) => ['prices', symbol, range] as const,
  plans: ['plans'] as const,
  plan: (id: string) => ['plan', id] as const,
  planCycles: (id: string) => ['plan-cycles', id] as const,
  recommendation: (id: string) => ['recommendation', id] as const,
  contributionAnalysis: (id: string) => ['contribution-analysis', id] as const,
  transactions: ['transactions'] as const,
  transactionCycles: (id: string) => ['transaction-cycles', id] as const,
  settings: ['settings'] as const,
  funds: ['funds'] as const,
  fundPurchases: (id: string) => ['fund-purchases', id] as const,
  fundNav: (id: string) => ['fund-nav', id] as const,
  fundCalendar: (id: string) => ['fund-calendar', id] as const,
  autoDcaRules: ['auto-dca-rules'] as const,
  autoDcaProjection: (id: string, groupBy: 'MONTH' | 'YEAR', includeDaily = false) => ['auto-dca-projection', id, groupBy, includeDaily] as const,
} as const

function invalidate(queryClient: QueryClient, queryKey: QueryKey): Promise<void> {
  return queryClient.invalidateQueries({ queryKey }).then(() => undefined)
}

export function invalidatePlanQueries(queryClient: QueryClient, planId?: string): Promise<void> {
  const keys: QueryKey[] = [queryKeys.plans, queryKeys.dashboard]
  if (planId) keys.push(queryKeys.planCycles(planId), queryKeys.recommendation(planId), queryKeys.contributionAnalysis(planId))
  return Promise.all(keys.map((queryKey) => invalidate(queryClient, queryKey))).then(() => undefined)
}

export function invalidateTransactionQueries(queryClient: QueryClient, planId?: string): Promise<void> {
  const keys: QueryKey[] = [queryKeys.transactions, queryKeys.dashboard, queryKeys.portfolioPerformance, queryKeys.multiCurrencyReports]
  if (planId) keys.push(queryKeys.planCycles(planId), queryKeys.recommendation(planId), queryKeys.contributionAnalysis(planId))
  return Promise.all(keys.map((queryKey) => invalidate(queryClient, queryKey))).then(() => undefined)
}

export function invalidateInstrumentQueries(queryClient: QueryClient, symbol?: string): Promise<void> {
  const keys: QueryKey[] = [queryKeys.instruments]
  if (symbol) keys.push(queryKeys.instrument(symbol))
  return Promise.all(keys.map((queryKey) => invalidate(queryClient, queryKey))).then(() => undefined)
}

export function invalidateInstrumentHistoryQueries(queryClient: QueryClient, symbol: string): Promise<void> {
  const keys: QueryKey[] = [queryKeys.instrument(symbol), queryKeys.prices(symbol), queryKeys.metrics(symbol), queryKeys.portfolioPerformance, queryKeys.multiCurrencyReports]
  return Promise.all(keys.map((queryKey) => invalidate(queryClient, queryKey))).then(() => undefined)
}

export function invalidateFundQueries(queryClient: QueryClient, fundId?: string): Promise<void> {
  const keys: QueryKey[] = [queryKeys.funds, queryKeys.autoDcaRules, queryKeys.multiCurrencyReports]
  if (fundId) keys.push(queryKeys.fundPurchases(fundId), queryKeys.fundNav(fundId), queryKeys.fundCalendar(fundId))
  return Promise.all(keys.map((queryKey) => invalidate(queryClient, queryKey))).then(() => undefined)
}

export function invalidateAutoDcaQueries(queryClient: QueryClient): Promise<void> {
  return Promise.all([
    invalidate(queryClient, queryKeys.autoDcaRules),
    invalidate(queryClient, queryKeys.multiCurrencyReports),
  ]).then(() => undefined)
}

export function clearUserQueryCache(queryClient: QueryClient): void {
  queryClient.clear()
}
