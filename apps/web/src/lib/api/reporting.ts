import type { MultiCurrencyReport, PerformanceRange } from '../../reportingTypes'
import { normalizeApiResponse } from './normalize'
import { apiMeta, request, type ApiResponse } from './transport'

function result<T>(body: unknown): ApiResponse<T> {
  return Promise.resolve(normalizeApiResponse<T>(body, apiMeta()))
}

export const reportingApi = {
  getMultiCurrencyReport: async (range: PerformanceRange = 'ALL'): ApiResponse<MultiCurrencyReport> =>
    result<MultiCurrencyReport>(await request<unknown>(`/reporting/multicurrency?range=${range}`)),
  syncUsdCny: async (): ApiResponse<unknown> => result<unknown>(await request<unknown>('/fx/usd-cny/sync', { method: 'POST' })),
}
