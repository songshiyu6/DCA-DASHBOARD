import type { AppSettings } from '../../types'
import { apiMeta, request, type ApiResponse } from './transport'
import { normalizeResult, normalizeSettings } from './normalize'

export const settingsApi = {
  getSettings: async (): ApiResponse<AppSettings> => normalizeResult(await request<unknown>('/settings'), normalizeSettings, apiMeta()),
  updateSettings: async (patch: Partial<AppSettings>): ApiResponse<AppSettings> => normalizeResult(await request<unknown>('/settings', {
    method: 'PUT',
    body: JSON.stringify(patch),
  }), normalizeSettings, apiMeta()),
}
