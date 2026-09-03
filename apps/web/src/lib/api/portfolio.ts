import type { DashboardData, DataStatus } from '../../types'
import { apiMeta, request, type ApiResponse } from './transport'
import { dataStatus, isRecord, normalizeDashboardData, normalizeResult } from './normalize'

export interface DailySettlementData {
  date: string
  settledAt: string
  marketValue: string | null
  netInvested: string
  dataStatus?: DataStatus
}

export type DashboardDataWithSettlement = DashboardData & {
  dailySettlement?: DailySettlementData | null
}

function decimalString(value: unknown, fallback = '0'): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  return fallback
}

function nullableDecimalString(value: unknown): string | null {
  return typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value)) ? String(value) : null
}

function normalizeDashboardWithSettlement(value: unknown): DashboardDataWithSettlement {
  const dashboard = normalizeDashboardData(value)
  const body = isRecord(value) ? value : {}
  const raw = isRecord(body.dailySettlement) ? body.dailySettlement : null
  if (!raw) return { ...dashboard, dailySettlement: null }
  const status = dataStatus(raw.dataStatus) ?? dataStatus(raw.status)
  return {
    ...dashboard,
    dailySettlement: {
      date: typeof raw.date === 'string' ? raw.date : '',
      settledAt: typeof raw.settledAt === 'string' ? raw.settledAt : '',
      marketValue: nullableDecimalString(raw.marketValue),
      netInvested: decimalString(raw.netInvested),
      ...(status ? { dataStatus: status } : {}),
    },
  }
}

export const portfolioApi = {
  getDashboard: async (): ApiResponse<DashboardDataWithSettlement> => normalizeResult(
    await request<unknown>('/dashboard'), normalizeDashboardWithSettlement, apiMeta(),
  ),
}
