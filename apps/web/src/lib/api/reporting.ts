import type { MultiCurrencyReport } from '../../types'
import { normalizeApiResponse } from './normalize'
import { apiMeta, request, type ApiResponse } from './transport'

function result<T>(body: unknown): ApiResponse<T> {
  return Promise.resolve(normalizeApiResponse<T>(body, apiMeta()))
}

export const reportingApi = {
  getMultiCurrencyReport: async (range: '1M' | '3M' | '1Y' | 'YTD' | 'ALL' = 'ALL'): ApiResponse<MultiCurrencyReport> =>
    result<MultiCurrencyReport>(await request<unknown>(`/reporting/multicurrency?range=${range}`)),
  syncUsdCny: async (): ApiResponse<unknown> => result<unknown>(await request<unknown>('/fx/usd-cny/sync', { method: 'POST' })),
}
