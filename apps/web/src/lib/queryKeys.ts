import type { QueryClient, QueryKey } from '@tanstack/react-query'

export const queryKeys = {
  session: ['session'] as const,
  dashboard: ['dashboard'] as const,
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
  const keys: QueryKey[] = [queryKeys.transactions, queryKeys.dashboard]
  if (planId) keys.push(queryKeys.planCycles(planId), queryKeys.recommendation(planId), queryKeys.contributionAnalysis(planId))
  return Promise.all(keys.map((queryKey) => invalidate(queryClient, queryKey))).then(() => undefined)
}

export function invalidateInstrumentQueries(queryClient: QueryClient, symbol?: string): Promise<void> {
  const keys: QueryKey[] = [queryKeys.instruments]
  if (symbol) keys.push(queryKeys.instrument(symbol))
  return Promise.all(keys.map((queryKey) => invalidate(queryClient, queryKey))).then(() => undefined)
}

export function invalidateInstrumentHistoryQueries(queryClient: QueryClient, symbol: string): Promise<void> {
  const keys: QueryKey[] = [queryKeys.instrument(symbol), queryKeys.prices(symbol), queryKeys.metrics(symbol)]
  return Promise.all(keys.map((queryKey) => invalidate(queryClient, queryKey))).then(() => undefined)
}

export function clearUserQueryCache(queryClient: QueryClient): void {
  queryClient.clear()
}
