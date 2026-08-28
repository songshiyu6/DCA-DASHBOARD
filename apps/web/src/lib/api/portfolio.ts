import type { DashboardData } from '../../types'
import { apiMeta, request, type ApiResponse } from './transport'
import { normalizeDashboardData, normalizeResult } from './normalize'

export const portfolioApi = {
  getDashboard: async (): ApiResponse<DashboardData> => normalizeResult(await request<unknown>('/dashboard'), normalizeDashboardData, apiMeta()),
}
