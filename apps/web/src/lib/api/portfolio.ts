import type { DashboardData, DashboardSummary, DataStatus } from '../../types'
import { apiMeta, request, type ApiResponse } from './transport'
import { dataStatus, isRecord, normalizeApiResponse, normalizeDashboardData, normalizeResult } from './normalize'

export type PortfolioPerformanceRange = '1M' | '3M' | '1Y' | 'YTD' | 'ALL'
export type PortfolioPerformancePointType = 'REGULAR_CLOSE' | 'LIVE'

export interface PortfolioPerformancePoint {
  date: string
  asOf: string | null
  level: string | null
  returnRate: string | null
  pointType: PortfolioPerformancePointType
  dataStatus: DataStatus
}

export interface PortfolioPerformanceData {
  range: PortfolioPerformanceRange
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
  points: PortfolioPerformancePoint[]
}

type CashReadyDashboardSummary = DashboardSummary & Partial<{
  cashBalance: string | null
  securitiesValue: string | null
  totalPortfolioValue: string | null
  portfolioValue: string | null
  cashAllocation: string | null
  allTwr: string | null
  cagr: string | null
  maxDrawdown: string | null
}>

const optionalSummaryFields = [
  'cashBalance',
  'securitiesValue',
  'totalPortfolioValue',
  'portfolioValue',
  'cashAllocation',
  'allTwr',
  'cagr',
  'maxDrawdown',
] as const

function nullableString(value: unknown): string | null {
  return typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value)) ? String(value) : null
}

function preserveOptionalSummaryFields(raw: unknown, normalized: DashboardData): DashboardData {
  const envelope = normalizeApiResponse<unknown>(raw, apiMeta())
  if (!isRecord(envelope.data) || !isRecord(envelope.data.summary)) return normalized

  const target = normalized.summary as CashReadyDashboardSummary
  for (const field of optionalSummaryFields) {
    const value = envelope.data.summary[field]
    if (typeof value === 'string' || typeof value === 'number') target[field] = String(value)
    else if (value === null) target[field] = null
  }
  return normalized
}

function normalizePerformancePoint(value: unknown): PortfolioPerformancePoint | null {
  if (!isRecord(value) || typeof value.date !== 'string') return null
  const pointType: PortfolioPerformancePointType = value.pointType === 'LIVE' ? 'LIVE' : 'REGULAR_CLOSE'
  return {
    date: value.date,
    asOf: typeof value.asOf === 'string' ? value.asOf : null,
    level: nullableString(value.level),
    returnRate: nullableString(value.returnRate),
    pointType,
    dataStatus: dataStatus(value.dataStatus) ?? 'UNAVAILABLE',
  }
}

function normalizePortfolioPerformanceData(value: unknown): PortfolioPerformanceData {
  if (!isRecord(value)) throw new Error('Invalid portfolio performance response')
  const rawRange = value.range
  const range: PortfolioPerformanceRange = rawRange === '1M' || rawRange === '3M' || rawRange === '1Y'
    || rawRange === 'YTD' || rawRange === 'ALL' ? rawRange : 'ALL'
  const rawPoints = Array.isArray(value.points) ? value.points : []
  return {
    range,
    requestedStartDate: typeof value.requestedStartDate === 'string' ? value.requestedStartDate : null,
    baselineDate: typeof value.baselineDate === 'string' ? value.baselineDate : null,
    inceptionDate: typeof value.inceptionDate === 'string' ? value.inceptionDate : null,
    endpointDate: typeof value.endpointDate === 'string' ? value.endpointDate : null,
    asOf: typeof value.asOf === 'string' ? value.asOf : null,
    twr: nullableString(value.twr),
    cagr: nullableString(value.cagr),
    xirr: nullableString(value.xirr),
    maximumDrawdown: nullableString(value.maximumDrawdown),
    dataStatus: dataStatus(value.dataStatus) ?? 'UNAVAILABLE',
    liveEndpointIncluded: value.liveEndpointIncluded === true,
    externalFlowModel: typeof value.externalFlowModel === 'string' ? value.externalFlowModel : 'UNKNOWN',
    points: rawPoints.flatMap((point) => {
      const normalized = normalizePerformancePoint(point)
      return normalized ? [normalized] : []
    }),
  }
}

export const portfolioApi = {
  getDashboard: async (): ApiResponse<DashboardData> => {
    const raw = await request<unknown>('/dashboard')
    const normalized = normalizeResult(raw, normalizeDashboardData, apiMeta())
    return { ...normalized, data: preserveOptionalSummaryFields(raw, normalized.data) }
  },
  getPortfolioPerformance: async (range: PortfolioPerformanceRange): ApiResponse<PortfolioPerformanceData> => {
    const raw = await request<unknown>(`/performance/portfolio?range=${encodeURIComponent(range)}`)
    return normalizeResult(raw, normalizePortfolioPerformanceData, apiMeta())
  },
}
