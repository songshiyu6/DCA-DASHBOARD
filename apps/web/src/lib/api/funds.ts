import type {
  AutoDcaProjection,
  AutoDcaRule,
  AutoDcaRuleInput,
  FundCalendarAudit,
  FundNavPoint,
  FundSyncResult,
  MutualFund,
  MutualFundInput,
} from '../../types'
import { normalizeApiResponse } from './normalize'
import { apiMeta, request, type ApiResponse } from './transport'

export interface FundLookupResult {
  code: string
  name: string
  fundType: string | null
  managementFeeRate: string | null
  confirmationTradingDays: number | null
  shareClass: string | null
  inceptionDate: string | null
  latestNavDate: string | null
  latestNav: string | null
  historicalNavAvailable: boolean
  source: string
}

function result<T>(body: unknown): ApiResponse<T> {
  return Promise.resolve(normalizeApiResponse<T>(body, apiMeta()))
}

function queryRange(startDate?: string, endDate?: string): string {
  const params = new URLSearchParams()
  if (startDate) params.set('startDate', startDate)
  if (endDate) params.set('endDate', endDate)
  const value = params.toString()
  return value ? `?${value}` : ''
}

export const fundsApi = {
  getFunds: async (): ApiResponse<MutualFund[]> => result<MutualFund[]>(await request<unknown>('/funds')),
  lookupFund: async (code: string): ApiResponse<FundLookupResult> => result<FundLookupResult>(await request<unknown>(`/funds/lookup?code=${encodeURIComponent(code)}`)),
  createFund: async (input: MutualFundInput): ApiResponse<MutualFund> => result<MutualFund>(await request<unknown>('/funds', {
    method: 'POST',
    body: JSON.stringify(input),
  })),
  updateFund: async (id: string, input: MutualFundInput): ApiResponse<MutualFund> => result<MutualFund>(await request<unknown>(`/funds/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  })),
  getFundNav: async (id: string): ApiResponse<FundNavPoint[]> => result<FundNavPoint[]>(await request<unknown>(`/funds/${encodeURIComponent(id)}/nav`)),
  putFundNav: async (id: string, navDate: string, nav: string): ApiResponse<FundNavPoint> => result<FundNavPoint>(await request<unknown>(`/funds/${encodeURIComponent(id)}/nav`, {
    method: 'PUT',
    body: JSON.stringify({ navDate, nav, source: 'MANUAL' }),
  })),
  syncFund: async (id: string, startDate?: string, endDate?: string): ApiResponse<FundSyncResult> => result<FundSyncResult>(await request<unknown>(`/funds/${encodeURIComponent(id)}/sync${queryRange(startDate, endDate)}`, { method: 'POST' })),
  getFundCalendar: async (id: string, startDate?: string, endDate?: string): ApiResponse<FundCalendarAudit> => result<FundCalendarAudit>(await request<unknown>(`/funds/${encodeURIComponent(id)}/calendar${queryRange(startDate, endDate)}`)),
  getAutoDcaRules: async (): ApiResponse<AutoDcaRule[]> => result<AutoDcaRule[]>(await request<unknown>('/auto-dca/rules')),
  createAutoDcaRule: async (input: AutoDcaRuleInput): ApiResponse<AutoDcaRule> => result<AutoDcaRule>(await request<unknown>('/auto-dca/rules', {
    method: 'POST',
    body: JSON.stringify(input),
  })),
  updateAutoDcaRule: async (id: string, input: AutoDcaRuleInput): ApiResponse<AutoDcaRule> => result<AutoDcaRule>(await request<unknown>(`/auto-dca/rules/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  })),
  getAutoDcaProjection: async (id: string, groupBy: 'MONTH' | 'YEAR', includeDaily = false): ApiResponse<AutoDcaProjection> => result<AutoDcaProjection>(await request<unknown>(`/auto-dca/rules/${encodeURIComponent(id)}/projection?groupBy=${groupBy}&includeDaily=${includeDaily ? 'true' : 'false'}`)),
}
