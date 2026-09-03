import type { DataStatus } from '../types'

export type BenchmarkType = 'ETF' | 'INDEX' | 'EQUITY'

export interface BenchmarkSearchResult {
  symbol: string
  name: string
  exchange: string | null
  type: BenchmarkType
}

export interface BenchmarkPricePoint {
  date: string
  value: string
}

export interface BenchmarkHistory {
  symbol: string
  name: string
  type: BenchmarkType
  source: string
  dataStatus: DataStatus
  points: BenchmarkPricePoint[]
}
