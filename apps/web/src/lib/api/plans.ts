import type { InvestmentPlan, PlanCycle, Recommendation } from '../../types'
import { apiMeta, request, type ApiResponse } from './transport'
import { normalizeCycles, normalizePlan, normalizePlans, normalizeRecommendation, normalizeResult } from './normalize'

export const plansApi = {
  getPlans: async (): ApiResponse<InvestmentPlan[]> => normalizeResult(await request<unknown>('/plans'), normalizePlans, apiMeta()),
  getPlan: async (id: string): ApiResponse<InvestmentPlan> => normalizeResult(await request<unknown>(`/plans/${encodeURIComponent(id)}`), normalizePlan, apiMeta()),
  createPlan: async (plan: Omit<InvestmentPlan, 'id' | 'cycles'>): ApiResponse<InvestmentPlan> => normalizeResult(await request<unknown>('/plans', {
    method: 'POST',
    body: JSON.stringify(plan),
  }), normalizePlan, apiMeta()),
  updatePlan: async (id: string, patch: Partial<InvestmentPlan>): ApiResponse<InvestmentPlan> => normalizeResult(await request<unknown>(`/plans/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(patch),
  }), normalizePlan, apiMeta()),
  getCycles: async (id: string): ApiResponse<PlanCycle[]> => normalizeResult(await request<unknown>(`/plans/${encodeURIComponent(id)}/cycles`), normalizeCycles, apiMeta()),
  getRecommendation: async (id: string): ApiResponse<Recommendation> => normalizeResult(await request<unknown>(`/plans/${encodeURIComponent(id)}/recommendation`), normalizeRecommendation, apiMeta()),
}
