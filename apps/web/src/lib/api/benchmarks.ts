import type { BenchmarkHistory, BenchmarkSearchResult, BenchmarkType } from '../benchmarkTypes'
import { apiMeta, request, type ApiResponse } from './transport'
import { dataStatus, isRecord, normalizeApiResponse } from './normalize'

function stringValue(value: unknown, fallback = ''): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  return fallback
}

function benchmarkType(value: unknown): BenchmarkType | null {
  return value === 'ETF' || value === 'INDEX' || value === 'EQUITY' ? value : null
}

function normalizeSearch(value: unknown): BenchmarkSearchResult[] {
  if (!Array.isArray(value)) return []
  return value.filter(isRecord).flatMap((item) => {
    const type = benchmarkType(item.type)
    const symbol = stringValue(item.symbol)
    if (!type || !symbol) return []
    return [{
      symbol,
      name: stringValue(item.name, symbol),
      exchange: typeof item.exchange === 'string' ? item.exchange : null,
      type,
    }]
  })
}

function normalizeHistory(value: unknown): BenchmarkHistory {
  const body = isRecord(value) ? value : {}
  const type = benchmarkType(body.type) ?? 'ETF'
  const points = Array.isArray(body.points) ? body.points.filter(isRecord).flatMap((point) => {
    const date = stringValue(point.date)
    const price = stringValue(point.value)
    return date && price ? [{ date, value: price }] : []
  }) : []
  return {
    symbol: stringValue(body.symbol),
    name: stringValue(body.name, stringValue(body.symbol)),
    type,
    source: stringValue(body.source, 'YAHOO'),
    dataStatus: dataStatus(body.dataStatus) ?? 'STALE',
    points,
  }
}

const isDemo = import.meta.env.VITE_APP_MODE === 'demo'

export const benchmarksApi = {
  search: async (query: string): ApiResponse<BenchmarkSearchResult[]> => {
    if (isDemo) return { data: [], meta: { ...apiMeta(), status: 'STALE', source: 'FIXTURE' } }
    const result = normalizeApiResponse<unknown>(
      await request<unknown>(`/benchmarks/search?q=${encodeURIComponent(query)}`),
      apiMeta(),
    )
    return { ...result, data: normalizeSearch(result.data) }
  },
  history: async (item: BenchmarkSearchResult): ApiResponse<BenchmarkHistory> => {
    if (isDemo) return {
      data: { symbol: item.symbol, name: item.name, type: item.type, source: 'FIXTURE', dataStatus: 'STALE', points: [] },
      meta: { ...apiMeta(), status: 'STALE', source: 'FIXTURE' },
    }
    const params = new URLSearchParams({ symbol: item.symbol, name: item.name, type: item.type, range: '5Y' })
    const result = normalizeApiResponse<unknown>(await request<unknown>(`/benchmarks/history?${params.toString()}`), apiMeta())
    return { ...result, data: normalizeHistory(result.data) }
  },
}
