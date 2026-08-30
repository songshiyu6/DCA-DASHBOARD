import type { AppSettings } from '../../types'
import { apiMeta, request, type ApiResponse } from './transport'
import { normalizeResult, normalizeSettings } from './normalize'
import { DISPLAY_TIME_ZONE_STORAGE_KEY, MARKET_TIME_ZONE_STORAGE_KEY } from '../format'

function persistTimezones(settings: AppSettings): void {
  if (typeof localStorage === 'undefined') return
  localStorage.setItem(MARKET_TIME_ZONE_STORAGE_KEY, settings.marketTimezone ?? 'America/New_York')
  localStorage.setItem(DISPLAY_TIME_ZONE_STORAGE_KEY, settings.displayTimezone ?? 'Asia/Shanghai')
}

function withPersistedTimezones(result: Awaited<ApiResponse<AppSettings>>): Awaited<ApiResponse<AppSettings>> {
  persistTimezones(result.data)
  return result
}

export const settingsApi = {
  getSettings: async (): ApiResponse<AppSettings> => withPersistedTimezones(normalizeResult(await request<unknown>('/settings'), normalizeSettings, apiMeta())),
  updateSettings: async (patch: Partial<AppSettings>): ApiResponse<AppSettings> => withPersistedTimezones(normalizeResult(await request<unknown>('/settings', {
    method: 'PUT',
    body: JSON.stringify(patch),
  }), normalizeSettings, apiMeta())),
}
