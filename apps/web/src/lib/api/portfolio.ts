import type { DashboardData, DashboardSummary } from '../../types'
import { apiMeta, request, type ApiResponse } from './transport'
import { isRecord, normalizeApiResponse, normalizeDashboardData, normalizeResult } from './normalize'

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

export const portfolioApi = {
  getDashboard: async (): ApiResponse<DashboardData> => {
    const raw = await request<unknown>('/dashboard')
    const normalized = normalizeResult(raw, normalizeDashboardData, apiMeta())
    return { ...normalized, data: preserveOptionalSummaryFields(raw, normalized.data) }
  },
}
