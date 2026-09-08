import type { DataStatus } from './types'

export type PerformanceRange = '1M' | '3M' | '1Y' | 'YTD' | 'ALL'

export interface MultiCurrencyFundPosition {
  fundCode: string
  fundName: string
  shares: string
  nav: string
  navDate: string
  marketValueCny: string
  marketValueUsd: string | null
}

export interface MultiCurrencySummary {
  reportingCurrency: 'USD'
  usdAccountValue: string | null
  cnyFundValue: string | null
  cnyFundValueUsd: string | null
  combinedValueUsd: string | null
  usdExternalFlow: string | null
  cnyAutoDcaExternalFlowUsd: string | null
  combinedExternalFlowUsd: string | null
  combinedPnlUsd: string | null
  usdCnyRate: string | null
  usdCnyRateDate: string | null
  status: DataStatus
  asOf: string | null
  funds: MultiCurrencyFundPosition[]
}

export interface MultiCurrencyPerformancePoint {
  date: string
  asOf: string | null
  level: string | null
  returnRate: string | null
  pointType: 'REGULAR_CLOSE' | 'LIVE'
  dataStatus: DataStatus
}

export interface MultiCurrencyPerformance {
  range: PerformanceRange
  requestedStartDate: string | null
  baselineDate: string | null
  inceptionDate: string | null
  endpointDate: string | null
  asOf: string | null
  twr: string | null
  cagr: string | null
  xirr: string | null
  maximumDrawdown: string | null
  dataStatus: DataStatus
  liveEndpointIncluded: boolean
  externalFlowModel: string
  points: MultiCurrencyPerformancePoint[]
}

export interface MultiCurrencyReport {
  summary: MultiCurrencySummary
  performance: MultiCurrencyPerformance
}
