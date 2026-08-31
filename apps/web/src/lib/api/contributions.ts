import type { ContributionAnalysis, ContributionBatch, ContributionBucket, ContributionClassificationCommit, ContributionClassificationItem, ContributionClassificationPreview, ContributionType, DataStatus, UnclassifiedBuy } from '../../types'
import { apiMeta, request, type ApiResponse } from './transport'
import { normalizeResult } from './normalize'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function stringValue(value: unknown, fallback = '0'): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  return fallback
}

function nullableString(value: unknown): string | null {
  return typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value)) ? String(value) : null
}

function status(value: unknown): DataStatus {
  return value === 'FRESH' || value === 'STALE' || value === 'PARTIAL' || value === 'UNAVAILABLE' || value === 'INSUFFICIENT_HISTORY'
    ? value
    : 'STALE'
}

function contributionType(value: unknown): ContributionType {
  return value === 'INITIAL' || value === 'UNPLANNED' ? value : 'DCA'
}

function normalizeBucket(value: unknown): ContributionBucket {
  const body = isRecord(value) ? value : {}
  return {
    principal: stringValue(body.principal),
    value: nullableString(body.value),
    pnl: nullableString(body.pnl),
    returnRate: nullableString(body.returnRate),
    averageMarketDays: typeof body.averageMarketDays === 'number' ? body.averageMarketDays : 0,
    batchCount: typeof body.batchCount === 'number' ? body.batchCount : 0,
    dataStatus: status(body.dataStatus),
  }
}

function normalizeBatch(value: unknown): ContributionBatch | null {
  if (!isRecord(value)) return null
  return {
    type: contributionType(value.type),
    period: typeof value.period === 'string' ? value.period : null,
    principal: stringValue(value.principal),
    value: nullableString(value.value),
    pnl: nullableString(value.pnl),
    returnRate: nullableString(value.returnRate),
    averageMarketDays: typeof value.averageMarketDays === 'number' ? value.averageMarketDays : 0,
    dataStatus: status(value.dataStatus),
  }
}

function normalizeUnclassified(value: unknown): UnclassifiedBuy | null {
  if (!isRecord(value) || typeof value.transactionId !== 'string') return null
  return {
    transactionId: value.transactionId,
    tradeDate: typeof value.tradeDate === 'string' ? value.tradeDate : '',
    symbol: typeof value.symbol === 'string' ? value.symbol : '',
    principal: stringValue(value.principal),
    eligibleForInitial: value.eligibleForInitial === true,
  }
}

export function normalizeContributionAnalysis(value: unknown): ContributionAnalysis {
  const body = isRecord(value) ? value : {}
  const batches = Array.isArray(body.batches) ? body.batches.map(normalizeBatch).filter((item): item is ContributionBatch => item !== null) : []
  const unclassifiedBuys = Array.isArray(body.unclassifiedBuys)
    ? body.unclassifiedBuys.map(normalizeUnclassified).filter((item): item is UnclassifiedBuy => item !== null)
    : []
  return {
    totalInvested: stringValue(body.totalInvested),
    initial: normalizeBucket(body.initial),
    dca: normalizeBucket(body.dca),
    unclassifiedAmount: stringValue(body.unclassifiedAmount),
    unclassifiedBuys,
    unclassifiedScope: 'ACCOUNT',
    batches,
    dataStatus: status(body.dataStatus),
    asOf: typeof body.asOf === 'string' ? body.asOf : '',
  }
}

function normalizeClassificationPreview(value: unknown): ContributionClassificationPreview {
  const body = isRecord(value) ? value : {}
  const items = Array.isArray(body.items) ? body.items.flatMap((item) => {
    if (!isRecord(item) || typeof item.transactionId !== 'string') return []
    const classification = item.classification === 'INITIAL' ? 'INITIAL' as const : 'UNPLANNED' as const
    const errors = Array.isArray(item.errors) ? item.errors.flatMap((error) => {
      if (!isRecord(error) || typeof error.code !== 'string' || typeof error.message !== 'string') return []
      return [{ code: error.code, message: error.message }]
    }) : []
    return [{
      transactionId: item.transactionId,
      classification,
      tradeDate: typeof item.tradeDate === 'string' ? item.tradeDate : null,
      symbol: typeof item.symbol === 'string' ? item.symbol : null,
      principal: nullableString(item.principal),
      valid: item.valid === true,
      errors,
    }]
  }) : []
  return {
    previewHash: typeof body.previewHash === 'string' ? body.previewHash : null,
    valid: body.valid === true,
    items,
  }
}

function normalizeClassificationCommit(value: unknown): ContributionClassificationCommit {
  const body = isRecord(value) ? value : {}
  return {
    batchId: typeof body.batchId === 'string' ? body.batchId : '',
    transactionIds: Array.isArray(body.transactionIds) ? body.transactionIds.filter((id): id is string => typeof id === 'string') : [],
    analysis: normalizeContributionAnalysis(body.analysis),
  }
}

export const contributionsApi = {
  getContributionAnalysis: async (planId: string): ApiResponse<ContributionAnalysis> => normalizeResult(
    await request<unknown>(`/plans/${encodeURIComponent(planId)}/contribution-analysis`),
    normalizeContributionAnalysis,
    apiMeta(),
  ),
  previewContributionClassifications: async (planId: string, items: ContributionClassificationItem[]): ApiResponse<ContributionClassificationPreview> => normalizeResult(
    await request<unknown>(`/plans/${encodeURIComponent(planId)}/contribution-classifications/preview`, {
      method: 'POST',
      body: JSON.stringify({ items }),
    }),
    normalizeClassificationPreview,
    apiMeta(),
  ),
  commitContributionClassifications: async (planId: string, previewHash: string, items: ContributionClassificationItem[]): ApiResponse<ContributionClassificationCommit> => normalizeResult(
    await request<unknown>(`/plans/${encodeURIComponent(planId)}/contribution-classifications/commit`, {
      method: 'POST',
      body: JSON.stringify({ previewHash, items }),
    }),
    normalizeClassificationCommit,
    apiMeta(),
  ),
}
