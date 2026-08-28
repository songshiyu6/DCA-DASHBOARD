import type { ApiResult, Session } from '../../types'
import { apiMeta, request, resetCsrf, type ApiResponse } from './transport'
import { isRecord, normalizeResult, normalizeSession } from './normalize'

function normalizeLogout(value: unknown): { ok: boolean } {
  if (isRecord(value) && typeof value.ok === 'boolean') return { ok: value.ok }
  return value as { ok: boolean }
}

export const authApi = {
  getSession: async (): ApiResponse<Session> => normalizeResult(await request<unknown>('/auth/session'), normalizeSession, apiMeta()),
  login: async (username: string, password: string): ApiResponse<Session> => normalizeResult(await request<unknown>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  }), normalizeSession, apiMeta()),
  logout: async (): Promise<ApiResult<{ ok: boolean }>> => {
    try {
      return normalizeResult(await request<unknown>('/auth/logout', { method: 'POST' }), normalizeLogout, apiMeta())
    } finally {
      resetCsrf()
    }
  },
}
